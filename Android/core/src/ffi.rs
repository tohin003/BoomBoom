//! The iPhone app's real-time C, compiled into this library by `build.rs` (`Packages/BoomEngine/Sources/CBoomAudio`):
//! the lock-free ring, the click-free renderer and the sound processor. Declarations mirror the headers in its
//! `include/` folder; the small types below wrap the pointers so the rest of the crate never handles them raw.

use std::os::raw::c_double;

#[repr(C)]
pub struct BoomRing {
    _private: [u8; 0],
}

#[repr(C)]
pub struct BoomRenderer {
    _private: [u8; 0],
}

#[repr(C)]
pub struct BoomDSP {
    _private: [u8; 0],
}

pub const BOOM_DSP_BANDS: usize = 10;

/// `BoomSoundParams` in `BoomDSP.h`, field for field.
#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct SoundParams {
    pub enabled: bool,
    pub band_gain_db: [f32; BOOM_DSP_BANDS],
    pub preamp_db: f32,
    pub headroom_db: f32,
    pub bass_db: f32,
    pub treble_db: f32,
    pub boost_db: f32,
    pub limiter: bool,
    pub balance: f32,
    pub width: f32,
    pub deep_bass: f32,
    pub air: f32,
    pub crossfeed: f32,
    pub spatial: f32,
}

impl Default for SoundParams {
    /// Off, and everything at its neutral place (as the iPhone app's `SoundProfile()`).
    fn default() -> Self {
        SoundParams {
            enabled: false,
            band_gain_db: [0.0; BOOM_DSP_BANDS],
            preamp_db: 0.0,
            headroom_db: 0.0,
            bass_db: 0.0,
            treble_db: 0.0,
            boost_db: 0.0,
            limiter: true,
            balance: 0.0,
            width: 1.0,
            deep_bass: 0.0,
            air: 0.0,
            crossfeed: 0.0,
            spatial: 0.0,
        }
    }
}

/// `BoomLevels` in `BoomDSP.h`.
#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Levels {
    pub peak_left: f32,
    pub peak_right: f32,
    pub rms_left: f32,
    pub rms_right: f32,
    pub gain_reduction_db: f32,
}

unsafe extern "C" {
    pub fn boom_ring_create(capacity_frames: u32, channels: u32) -> *mut BoomRing;
    pub fn boom_ring_destroy(ring: *mut BoomRing);
    pub fn boom_ring_available_read(ring: *const BoomRing) -> u32;
    pub fn boom_ring_available_write(ring: *const BoomRing) -> u32;
    pub fn boom_ring_write(ring: *mut BoomRing, planes: *const *const f32, frames: u32) -> u32;
    pub fn boom_ring_frames_read(ring: *const BoomRing) -> u64;
    pub fn boom_ring_frames_written(ring: *const BoomRing) -> u64;
    pub fn boom_ring_set_finished(ring: *mut BoomRing, finished: bool);
    pub fn boom_ring_is_finished(ring: *const BoomRing) -> bool;
    pub fn boom_ring_underruns(ring: *const BoomRing) -> u32;

    pub fn boom_renderer_create() -> *mut BoomRenderer;
    pub fn boom_renderer_destroy(renderer: *mut BoomRenderer);
    pub fn boom_renderer_swap_ring(renderer: *mut BoomRenderer, ring: *mut BoomRing) -> *mut BoomRing;
    pub fn boom_renderer_set_playing(renderer: *mut BoomRenderer, playing: bool);
    pub fn boom_renderer_set_gain(renderer: *mut BoomRenderer, gain: f32);
    pub fn boom_renderer_render_count(renderer: *const BoomRenderer) -> u64;
    pub fn boom_renderer_is_idle(renderer: *const BoomRenderer) -> bool;
    pub fn boom_renderer_render(renderer: *mut BoomRenderer, left: *mut f32, right: *mut f32, frames: u32) -> bool;

    pub fn boom_dsp_create(sample_rate: c_double) -> *mut BoomDSP;
    pub fn boom_dsp_destroy(dsp: *mut BoomDSP);
    pub fn boom_dsp_band_frequency(band: u32) -> c_double;
    pub fn boom_dsp_latency(dsp: *const BoomDSP) -> u32;
    pub fn boom_dsp_set_params(dsp: *mut BoomDSP, params: *const SoundParams);
    pub fn boom_dsp_process(dsp: *mut BoomDSP, left: *mut f32, right: *mut f32, frames: u32, input_silent: bool)
    -> bool;
    pub fn boom_dsp_set_head_turn(dsp: *mut BoomDSP, radians: f32);
    pub fn boom_dsp_take_levels(dsp: *mut BoomDSP, levels: *mut Levels);
    pub fn boom_dsp_spectrum(dsp: *mut BoomDSP, decibels: *mut f32, bands: u32, low_hz: c_double, high_hz: c_double);
    pub fn boom_dsp_response(
        params: *const SoundParams,
        sample_rate: c_double,
        frequencies: *const c_double,
        count: u32,
        decibels: *mut c_double,
    );
    pub fn boom_dsp_peak_response(params: *const SoundParams, sample_rate: c_double) -> c_double;
}

/// A ring pointer that may be handed between threads: the ring's own functions are what make that safe (one writer,
/// one reader, atomics inside).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct RingPtr(pub *mut BoomRing);
unsafe impl Send for RingPtr {}
unsafe impl Sync for RingPtr {}

impl RingPtr {
    pub fn create(frames: u32) -> Option<RingPtr> {
        let raw = unsafe { boom_ring_create(frames, 2) };
        (!raw.is_null()).then_some(RingPtr(raw))
    }
    pub fn available_read(self) -> u32 {
        unsafe { boom_ring_available_read(self.0) }
    }
    pub fn available_write(self) -> u32 {
        unsafe { boom_ring_available_write(self.0) }
    }
    pub fn frames_read(self) -> u64 {
        unsafe { boom_ring_frames_read(self.0) }
    }
    pub fn frames_written(self) -> u64 {
        unsafe { boom_ring_frames_written(self.0) }
    }
    pub fn is_finished(self) -> bool {
        unsafe { boom_ring_is_finished(self.0) }
    }
    pub fn set_finished(self, finished: bool) {
        unsafe { boom_ring_set_finished(self.0, finished) }
    }
    /// Writer thread only.
    pub fn write(self, left: &[f32], right: &[f32]) -> u32 {
        let frames = left.len().min(right.len()) as u32;
        let planes = [left.as_ptr(), right.as_ptr()];
        unsafe { boom_ring_write(self.0, planes.as_ptr(), frames) }
    }
    /// # Safety
    /// Nothing may use the ring afterwards (the renderer has let go of it for at least one render call).
    pub unsafe fn destroy(self) {
        unsafe { boom_ring_destroy(self.0) }
    }
}

/// The sound processor (`BoomDSP`). Control calls from any one thread; `process` from the audio thread only.
pub struct Dsp {
    raw: *mut BoomDSP,
}

unsafe impl Send for Dsp {}
unsafe impl Sync for Dsp {}

impl Dsp {
    pub fn new(sample_rate: f64) -> Dsp {
        let raw = unsafe { boom_dsp_create(sample_rate) };
        assert!(!raw.is_null(), "no memory for the sound processor");
        Dsp { raw }
    }

    pub fn raw(&self) -> *mut BoomDSP {
        self.raw
    }

    pub fn latency(&self) -> u32 {
        unsafe { boom_dsp_latency(self.raw) }
    }

    pub fn set_params(&self, params: &SoundParams) {
        unsafe { boom_dsp_set_params(self.raw, params) }
    }

    /// The audio thread. Returns true when the output is all zeros.
    pub fn process(&self, left: &mut [f32], right: &mut [f32], input_silent: bool) -> bool {
        let frames = left.len().min(right.len()) as u32;
        unsafe { boom_dsp_process(self.raw, left.as_mut_ptr(), right.as_mut_ptr(), frames, input_silent) }
    }

    pub fn set_head_turn(&self, radians: f32) {
        unsafe { boom_dsp_set_head_turn(self.raw, radians) }
    }

    pub fn take_levels(&self) -> Levels {
        let mut levels = Levels::default();
        unsafe { boom_dsp_take_levels(self.raw, &mut levels) };
        levels
    }

    pub fn spectrum(&self, bands: usize, low_hz: f64, high_hz: f64) -> Vec<f32> {
        let mut result = vec![-120.0f32; bands];
        if bands > 0 {
            unsafe { boom_dsp_spectrum(self.raw, result.as_mut_ptr(), bands as u32, low_hz, high_hz) };
        }
        result
    }
}

impl Drop for Dsp {
    fn drop(&mut self) {
        unsafe { boom_dsp_destroy(self.raw) }
    }
}

/// The gain in decibels that the bands, bass and treble of `params` give at each frequency: the curve a screen draws.
pub fn response(params: &SoundParams, sample_rate: f64, frequencies: &[f64]) -> Vec<f64> {
    let mut result = vec![0.0; frequencies.len()];
    if !frequencies.is_empty() {
        unsafe {
            boom_dsp_response(params, sample_rate, frequencies.as_ptr(), frequencies.len() as u32, result.as_mut_ptr())
        };
    }
    result
}

/// The largest gain of that curve between 20 Hz and 20 kHz, in decibels, never below 0.
pub fn peak_response(params: &SoundParams, sample_rate: f64) -> f64 {
    unsafe { boom_dsp_peak_response(params, sample_rate) }
}

/// The bands' centre frequencies in hertz.
pub fn band_frequencies() -> [f64; BOOM_DSP_BANDS] {
    std::array::from_fn(|band| unsafe { boom_dsp_band_frequency(band as u32) })
}
