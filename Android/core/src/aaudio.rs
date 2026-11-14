//! The output on Android: an AAudio stream (float, 48 kHz, stereo, usage media, content type music) whose callback
//! pulls from the pipeline's renderer and then runs the sound processor in place. A port of the iPhone app's
//! `AudioOutput` for the parts that are about the hardware.
//!
//! The callback never blocks, locks or allocates. When the route changes (headphones in or out, Bluetooth) AAudio
//! disconnects the stream; the error callback (which must not touch the stream itself) wakes a small keeper thread,
//! and that thread closes the stream and opens a new one on the new route.

use std::cell::UnsafeCell;
use std::ffi::{c_char, c_void, CString};
use std::sync::atomic::{AtomicI32, AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::thread::JoinHandle;

use crate::ffi::{BoomDSP, BoomRenderer};
use crate::output::render_block;

#[repr(C)]
pub struct AAudioStreamBuilder {
    _private: [u8; 0],
}

#[repr(C)]
pub struct AAudioStream {
    _private: [u8; 0],
}

type DataCallback = unsafe extern "C" fn(*mut AAudioStream, *mut c_void, *mut c_void, i32) -> i32;
type ErrorCallback = unsafe extern "C" fn(*mut AAudioStream, *mut c_void, i32);

const AAUDIO_OK: i32 = 0;
const AAUDIO_DIRECTION_OUTPUT: i32 = 0;
const AAUDIO_FORMAT_PCM_FLOAT: i32 = 2;
const AAUDIO_SHARING_MODE_SHARED: i32 = 1;
const AAUDIO_PERFORMANCE_MODE_POWER_SAVING: i32 = 11;
const AAUDIO_USAGE_MEDIA: i32 = 1;
const AAUDIO_CONTENT_TYPE_MUSIC: i32 = 2;
const AAUDIO_STREAM_STATE_STARTING: i32 = 3;
const AAUDIO_STREAM_STATE_STARTED: i32 = 4;
const AAUDIO_CALLBACK_RESULT_CONTINUE: i32 = 0;
const AAUDIO_CALLBACK_RESULT_STOP: i32 = 1;
/// Android 14's sample-rate conversion quality setting: the best the system has, should it convert.
const AAUDIO_SRC_QUALITY_HIGHEST: i32 = 4;

// AAudio itself is in Android 8 (API 26), this app's oldest.
unsafe extern "C" {
    fn AAudio_createStreamBuilder(builder: *mut *mut AAudioStreamBuilder) -> i32;
    fn AAudioStreamBuilder_setDirection(builder: *mut AAudioStreamBuilder, direction: i32);
    fn AAudioStreamBuilder_setSampleRate(builder: *mut AAudioStreamBuilder, rate: i32);
    fn AAudioStreamBuilder_setChannelCount(builder: *mut AAudioStreamBuilder, channels: i32);
    fn AAudioStreamBuilder_setFormat(builder: *mut AAudioStreamBuilder, format: i32);
    fn AAudioStreamBuilder_setSharingMode(builder: *mut AAudioStreamBuilder, mode: i32);
    fn AAudioStreamBuilder_setPerformanceMode(builder: *mut AAudioStreamBuilder, mode: i32);
    fn AAudioStreamBuilder_setDataCallback(builder: *mut AAudioStreamBuilder, callback: DataCallback, user: *mut c_void);
    fn AAudioStreamBuilder_setErrorCallback(builder: *mut AAudioStreamBuilder, callback: ErrorCallback, user: *mut c_void);
    fn AAudioStreamBuilder_openStream(builder: *mut AAudioStreamBuilder, stream: *mut *mut AAudioStream) -> i32;
    fn AAudioStreamBuilder_delete(builder: *mut AAudioStreamBuilder) -> i32;
    fn AAudioStream_requestStart(stream: *mut AAudioStream) -> i32;
    fn AAudioStream_requestStop(stream: *mut AAudioStream) -> i32;
    fn AAudioStream_close(stream: *mut AAudioStream) -> i32;
    fn AAudioStream_getSampleRate(stream: *mut AAudioStream) -> i32;
    fn AAudioStream_getChannelCount(stream: *mut AAudioStream) -> i32;
    fn AAudioStream_getFramesPerBurst(stream: *mut AAudioStream) -> i32;
    fn AAudioStream_getBufferSizeInFrames(stream: *mut AAudioStream) -> i32;
    fn AAudioStream_getXRunCount(stream: *mut AAudioStream) -> i32;
    fn AAudioStream_getState(stream: *mut AAudioStream) -> i32;
    fn AAudio_convertResultToText(result: i32) -> *const c_char;

    fn dlopen(name: *const c_char, flags: i32) -> *mut c_void;
    fn dlsym(handle: *mut c_void, name: *const c_char) -> *mut c_void;
    fn __android_log_write(priority: i32, tag: *const c_char, text: *const c_char) -> i32;
}

pub(crate) fn log(text: &str) {
    if let Ok(text) = CString::new(text) {
        unsafe { __android_log_write(4, c"BoomCore".as_ptr(), text.as_ptr()) };
    }
}

fn result_text(result: i32) -> String {
    let text = unsafe { AAudio_convertResultToText(result) };
    if text.is_null() {
        return result.to_string();
    }
    unsafe { std::ffi::CStr::from_ptr(text) }.to_string_lossy().into_owned()
}

/// A builder setter from a later Android than 8, looked up at run time so the library still loads on older ones.
fn later_setter(name: &std::ffi::CStr) -> Option<unsafe extern "C" fn(*mut AAudioStreamBuilder, i32)> {
    unsafe {
        let library = dlopen(c"libaaudio.so".as_ptr(), 2);
        if library.is_null() {
            return None;
        }
        let symbol = dlsym(library, name.as_ptr());
        (!symbol.is_null()).then(|| std::mem::transmute::<*mut c_void, unsafe extern "C" fn(*mut AAudioStreamBuilder, i32)>(symbol))
    }
}

/// Frames rendered per pass inside one callback; a larger callback is done in several passes.
const SCRATCH_FRAMES: usize = 2048;

/// What the callbacks see. Lives at a fixed address (boxed) for the output's whole life.
struct CallbackState {
    renderer: *mut BoomRenderer,
    dsp: *mut BoomDSP,
    scratch: UnsafeCell<[Vec<f32>; 2]>,
    channels: AtomicI32,
    callbacks: AtomicU64,
    frames: AtomicU64,
    /// Frames for which the renderer had audio to give (not holding, not starved).
    sounding_frames: AtomicU64,
    signal: Arc<Signal>,
}

unsafe impl Send for CallbackState {}
unsafe impl Sync for CallbackState {}

#[derive(Default)]
struct Signal {
    request: Mutex<Request>,
    wake: Condvar,
}

#[derive(Default, Clone, Copy, PartialEq, Eq)]
enum Request {
    #[default]
    Nothing,
    Reopen,
    Quit,
}

unsafe extern "C" fn data_callback(_stream: *mut AAudioStream, user: *mut c_void, audio: *mut c_void, frames: i32) -> i32 {
    let outcome = std::panic::catch_unwind(|| unsafe {
        let state = &*(user as *const CallbackState);
        let channels = state.channels.load(Ordering::Relaxed).max(1) as usize;
        let frames = frames.max(0) as usize;
        let out = std::slice::from_raw_parts_mut(audio as *mut f32, frames * channels);
        let [left, right] = &mut *state.scratch.get();
        let mut done = 0;
        let mut sounding = 0u64;
        while done < frames {
            let count = (frames - done).min(SCRATCH_FRAMES);
            let (l, r) = (&mut left[..count], &mut right[..count]);
            let (holding, _) = render_block(state.renderer, state.dsp, l, r);
            if !holding {
                sounding += count as u64;
            }
            let block = &mut out[done * channels..(done + count) * channels];
            match channels {
                2 => {
                    for (frame, (a, b)) in block.chunks_exact_mut(2).zip(l.iter().zip(r.iter())) {
                        frame[0] = *a;
                        frame[1] = *b;
                    }
                }
                1 => {
                    for (sample, (a, b)) in block.iter_mut().zip(l.iter().zip(r.iter())) {
                        *sample = 0.5 * (a + b);
                    }
                }
                _ => {
                    for (frame, (a, b)) in block.chunks_exact_mut(channels).zip(l.iter().zip(r.iter())) {
                        frame.fill(0.0);
                        frame[0] = *a;
                        frame[1] = *b;
                    }
                }
            }
            done += count;
        }
        state.callbacks.fetch_add(1, Ordering::Relaxed);
        state.frames.fetch_add(frames as u64, Ordering::Relaxed);
        state.sounding_frames.fetch_add(sounding, Ordering::Relaxed);
    });
    if outcome.is_ok() { AAUDIO_CALLBACK_RESULT_CONTINUE } else { AAUDIO_CALLBACK_RESULT_STOP }
}

unsafe extern "C" fn error_callback(_stream: *mut AAudioStream, user: *mut c_void, error: i32) {
    // Not the audio thread, but the stream must not be stopped or closed from here: hand over to the keeper.
    let state = unsafe { &*(user as *const CallbackState) };
    log(&format!("output: the stream stopped ({}); reopening", result_text(error)));
    let mut request = state.signal.request.lock().unwrap_or_else(|p| p.into_inner());
    if *request == Request::Nothing {
        *request = Request::Reopen;
    }
    state.signal.wake.notify_all();
}

struct Stream(*mut AAudioStream);
unsafe impl Send for Stream {}

#[derive(Default)]
struct Control {
    stream: Option<Stream>,
    wants_running: bool,
    /// The rate the device runs at when nothing asks for another, learnt on the first open.
    native_rate: i32,
    reopens: u64,
}

/// What the output is doing, for the screens and the checks.
#[derive(Clone, Copy, Debug, Default)]
pub struct OutputInfo {
    pub running: bool,
    pub sample_rate: i32,
    pub native_rate: i32,
    /// The system converts 48 kHz to the device's own rate.
    pub resampled: bool,
    pub channels: i32,
    pub frames_per_burst: i32,
    pub buffer_frames: i32,
    pub xruns: i32,
    pub callbacks: u64,
    pub frames: u64,
    pub sounding_frames: u64,
    pub reopens: u64,
}

struct Shared {
    state: Box<CallbackState>,
    control: Mutex<Control>,
}

pub struct AudioOutput {
    shared: Arc<Shared>,
    keeper: Option<JoinHandle<()>>,
}

impl AudioOutput {
    /// # Safety
    /// `renderer` and `dsp` must outlive this output.
    pub unsafe fn new(renderer: *mut BoomRenderer, dsp: *mut BoomDSP) -> AudioOutput {
        let signal = Arc::new(Signal::default());
        let state = Box::new(CallbackState {
            renderer,
            dsp,
            scratch: UnsafeCell::new([vec![0.0; SCRATCH_FRAMES], vec![0.0; SCRATCH_FRAMES]]),
            channels: AtomicI32::new(2),
            callbacks: AtomicU64::new(0),
            frames: AtomicU64::new(0),
            sounding_frames: AtomicU64::new(0),
            signal: signal.clone(),
        });
        let shared = Arc::new(Shared { state, control: Mutex::new(Control::default()) });
        let keeper_shared = shared.clone();
        let keeper = std::thread::Builder::new()
            .name("boom-output".into())
            .spawn(move || keep(keeper_shared, signal))
            .expect("could not start the output keeper");
        AudioOutput { shared, keeper: Some(keeper) }
    }

    /// Opens the stream (if it is not open) and starts it. False when the device refused.
    pub fn start(&self) -> bool {
        let mut control = self.shared.lock();
        control.wants_running = true;
        if control.stream.is_none() {
            control.stream = self.shared.open(&mut control);
        }
        let Some(stream) = &control.stream else { return false };
        // Already running (asked for twice): that is what was wanted, and a second request would be refused.
        let state = unsafe { AAudioStream_getState(stream.0) };
        if state == AAUDIO_STREAM_STATE_STARTING || state == AAUDIO_STREAM_STATE_STARTED {
            return true;
        }
        let result = unsafe { AAudioStream_requestStart(stream.0) };
        if result != AAUDIO_OK {
            log(&format!("output: start refused ({})", result_text(result)));
        }
        result == AAUDIO_OK
    }

    /// Stops and closes the stream, letting the device go.
    pub fn stop(&self) {
        let mut control = self.shared.lock();
        control.wants_running = false;
        if let Some(stream) = control.stream.take() {
            unsafe {
                AAudioStream_requestStop(stream.0);
                AAudioStream_close(stream.0);
            }
        }
    }

    pub fn info(&self) -> OutputInfo {
        let control = self.shared.lock();
        let state = &self.shared.state;
        let mut info = OutputInfo {
            native_rate: control.native_rate,
            callbacks: state.callbacks.load(Ordering::Relaxed),
            frames: state.frames.load(Ordering::Relaxed),
            sounding_frames: state.sounding_frames.load(Ordering::Relaxed),
            reopens: control.reopens,
            ..OutputInfo::default()
        };
        if let Some(stream) = &control.stream {
            unsafe {
                info.running = control.wants_running;
                info.sample_rate = AAudioStream_getSampleRate(stream.0);
                info.channels = AAudioStream_getChannelCount(stream.0);
                info.frames_per_burst = AAudioStream_getFramesPerBurst(stream.0);
                info.buffer_frames = AAudioStream_getBufferSizeInFrames(stream.0);
                info.xruns = AAudioStream_getXRunCount(stream.0);
            }
            info.resampled = info.native_rate > 0 && info.native_rate != info.sample_rate;
        }
        info
    }
}

impl Drop for AudioOutput {
    fn drop(&mut self) {
        self.stop();
        {
            let signal = &self.shared.state.signal;
            *signal.request.lock().unwrap_or_else(|p| p.into_inner()) = Request::Quit;
            signal.wake.notify_all();
        }
        if let Some(keeper) = self.keeper.take() {
            let _ = keeper.join();
        }
    }
}

impl Shared {
    fn lock(&self) -> std::sync::MutexGuard<'_, Control> {
        self.control.lock().unwrap_or_else(|p| p.into_inner())
    }

    /// Opens a stream at 48 kHz. The first time, the device is asked for its own rate first: when that is 48 kHz the
    /// stream is the device's own; otherwise the system converts, and `OutputInfo` says so.
    fn open(&self, control: &mut Control) -> Option<Stream> {
        if control.native_rate == 0 {
            let probe = self.open_at(0)?;
            let rate = unsafe { AAudioStream_getSampleRate(probe.0) };
            control.native_rate = rate;
            if rate == 48_000 {
                return Some(probe);
            }
            unsafe { AAudioStream_close(probe.0) };
        }
        self.open_at(48_000)
    }

    fn open_at(&self, rate: i32) -> Option<Stream> {
        unsafe {
            let mut builder: *mut AAudioStreamBuilder = std::ptr::null_mut();
            if AAudio_createStreamBuilder(&mut builder) != AAUDIO_OK || builder.is_null() {
                return None;
            }
            AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
            AAudioStreamBuilder_setSampleRate(builder, rate);
            AAudioStreamBuilder_setChannelCount(builder, 2);
            AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
            AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
            // Music, not a game: a deep buffer saves battery, and 5 ms of latency more or less is not heard.
            AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_POWER_SAVING);
            if let Some(set_usage) = later_setter(c"AAudioStreamBuilder_setUsage") {
                set_usage(builder, AAUDIO_USAGE_MEDIA);
            }
            if let Some(set_content) = later_setter(c"AAudioStreamBuilder_setContentType") {
                set_content(builder, AAUDIO_CONTENT_TYPE_MUSIC);
            }
            if let Some(set_quality) = later_setter(c"AAudioStreamBuilder_setSampleRateConversionQuality") {
                set_quality(builder, AAUDIO_SRC_QUALITY_HIGHEST);
            }
            let user = &*self.state as *const CallbackState as *mut c_void;
            AAudioStreamBuilder_setDataCallback(builder, data_callback, user);
            AAudioStreamBuilder_setErrorCallback(builder, error_callback, user);
            let mut stream: *mut AAudioStream = std::ptr::null_mut();
            let result = AAudioStreamBuilder_openStream(builder, &mut stream);
            AAudioStreamBuilder_delete(builder);
            if result != AAUDIO_OK || stream.is_null() {
                log(&format!("output: could not open a stream at {rate} Hz ({})", result_text(result)));
                return None;
            }
            self.state.channels.store(AAudioStream_getChannelCount(stream), Ordering::Relaxed);
            log(&format!(
                "output: open at {} Hz, {} channels, bursts of {} frames",
                AAudioStream_getSampleRate(stream),
                AAudioStream_getChannelCount(stream),
                AAudioStream_getFramesPerBurst(stream)
            ));
            Some(Stream(stream))
        }
    }
}

/// The keeper thread: reopens the stream after the route changed.
fn keep(shared: Arc<Shared>, signal: Arc<Signal>) {
    loop {
        let request = {
            let mut request = signal.request.lock().unwrap_or_else(|p| p.into_inner());
            while *request == Request::Nothing {
                request = signal.wake.wait(request).unwrap_or_else(|p| p.into_inner());
            }
            std::mem::take(&mut *request)
        };
        if request == Request::Quit {
            return;
        }
        let mut control = shared.lock();
        if let Some(stream) = control.stream.take() {
            unsafe {
                AAudioStream_requestStop(stream.0);
                AAudioStream_close(stream.0);
            }
        }
        // The new route may run at another rate: learn it again.
        control.native_rate = 0;
        control.reopens += 1;
        // In the moment a route changes (headphones connecting over Bluetooth) the new device may not take a stream
        // yet: try again for a few seconds rather than fall silent until the listener presses play.
        let mut tries = 0u64;
        while control.wants_running && control.stream.is_none() {
            if let Some(stream) = shared.open(&mut control) {
                let result = unsafe { AAudioStream_requestStart(stream.0) };
                log(&format!("output: reopened on the new route ({})", result_text(result)));
                if result == AAUDIO_OK {
                    control.stream = Some(stream);
                    break;
                }
                unsafe { AAudioStream_close(stream.0) };
            }
            tries += 1;
            if tries >= REOPEN_TRIES {
                log("output: the new route did not take a stream; the player opens one when it next looks");
                break;
            }
            drop(control);
            std::thread::sleep(std::time::Duration::from_millis(200 * tries));
            if *signal.request.lock().unwrap_or_else(|p| p.into_inner()) == Request::Quit {
                return;
            }
            control = shared.lock();
            // The rate learnt on a failed try says nothing about the next.
            control.native_rate = 0;
        }
    }
}

/// How often the keeper tries to open a stream on a new route (over about seven seconds) before leaving it be.
const REOPEN_TRIES: u64 = 8;
