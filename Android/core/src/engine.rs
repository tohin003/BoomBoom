//! The engine, one per process: the playback pipeline, the sound processor and the output, with the pipeline's
//! events waiting in a queue for the app to collect. What the JNI surface (`jni_api.rs`) drives.
//!
//! Events are queued and the app drains them (`poll_events`, which may wait), rather than being called into Kotlin
//! from the decode thread: no native thread ever attaches to the Java VM or holds a Java reference, an app that is
//! slow to read loses nothing (the queue keeps the last few hundred), and the events reach Kotlin on a thread of
//! Kotlin's choosing.

use std::collections::VecDeque;
use std::sync::{Arc, Condvar, Mutex, OnceLock};
use std::time::{Duration, Instant};

use crate::ffi::{Dsp, Levels, SoundParams};
use crate::pipeline::{PipelineEvent, PipelineItem, PlaybackPipeline, SAMPLE_RATE};
use crate::track::TrackBuffer;

const MOST_QUEUED_EVENTS: usize = 512;

#[derive(Default)]
pub struct EventQueue {
    events: Mutex<VecDeque<PipelineEvent>>,
    arrived: Condvar,
}

impl EventQueue {
    pub fn push(&self, event: PipelineEvent) {
        let mut events = self.events.lock().unwrap_or_else(|p| p.into_inner());
        if events.len() >= MOST_QUEUED_EVENTS {
            events.pop_front();
        }
        events.push_back(event);
        self.arrived.notify_all();
    }

    /// Everything queued, waiting up to `timeout` for the first event when there is none.
    pub fn drain(&self, timeout: Duration) -> Vec<PipelineEvent> {
        let deadline = Instant::now() + timeout;
        let mut events = self.events.lock().unwrap_or_else(|p| p.into_inner());
        while events.is_empty() {
            let now = Instant::now();
            if now >= deadline {
                break;
            }
            events = self.arrived.wait_timeout(events, deadline - now).unwrap_or_else(|p| p.into_inner()).0;
        }
        events.drain(..).collect()
    }
}

/// What the output reports, whichever platform this runs on.
#[derive(Clone, Copy, Debug, Default)]
pub struct OutputInfo {
    pub running: bool,
    pub sample_rate: i32,
    pub native_rate: i32,
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

pub struct Engine {
    pub pipeline: Arc<PlaybackPipeline>,
    pub dsp: Arc<Dsp>,
    events: Arc<EventQueue>,
    sound: Mutex<SoundParams>,
    /// One reader of levels and one of the spectrum at a time, as the processor asks.
    readers: Mutex<()>,
    #[cfg(target_os = "android")]
    output: Mutex<Option<crate::aaudio::AudioOutput>>,
}

static ENGINE: OnceLock<Engine> = OnceLock::new();

/// The process's engine, made on first use.
pub fn engine() -> &'static Engine {
    ENGINE.get_or_init(Engine::new)
}

impl Engine {
    fn new() -> Engine {
        let pipeline = Arc::new(PlaybackPipeline::new());
        let events = Arc::new(EventQueue::default());
        let queue = events.clone();
        pipeline.set_event_sink(Some(Box::new(move |event| queue.push(event))));
        let dsp = Arc::new(Dsp::new(SAMPLE_RATE));
        dsp.set_params(&SoundParams::default());
        Engine {
            pipeline,
            dsp,
            events,
            sound: Mutex::new(SoundParams::default()),
            readers: Mutex::new(()),
            #[cfg(target_os = "android")]
            output: Mutex::new(None),
        }
    }

    // Output

    /// Opens and starts the audio output. False when the device refused (or on a system without AAudio).
    pub fn start_output(&self) -> bool {
        #[cfg(target_os = "android")]
        {
            let mut output = self.output.lock().unwrap_or_else(|p| p.into_inner());
            let output = output.get_or_insert_with(|| unsafe {
                // The engine lives as long as the process, so the renderer and processor outlive the output.
                crate::aaudio::AudioOutput::new(self.pipeline.renderer_raw(), self.dsp.raw())
            });
            output.start()
        }
        #[cfg(not(target_os = "android"))]
        false
    }

    pub fn stop_output(&self) {
        #[cfg(target_os = "android")]
        if let Some(output) = self.output.lock().unwrap_or_else(|p| p.into_inner()).as_ref() {
            output.stop();
        }
    }

    pub fn output_info(&self) -> OutputInfo {
        #[cfg(target_os = "android")]
        if let Some(output) = self.output.lock().unwrap_or_else(|p| p.into_inner()).as_ref() {
            let i = output.info();
            return OutputInfo {
                running: i.running,
                sample_rate: i.sample_rate,
                native_rate: i.native_rate,
                resampled: i.resampled,
                channels: i.channels,
                frames_per_burst: i.frames_per_burst,
                buffer_frames: i.buffer_frames,
                xruns: i.xruns,
                callbacks: i.callbacks,
                frames: i.frames,
                sounding_frames: i.sounding_frames,
                reopens: i.reopens,
            };
        }
        OutputInfo::default()
    }

    // Playback

    pub fn load(&self, buffer: Arc<TrackBuffer>, id: u64, seconds: f64, autoplay: bool, gain: f32) {
        self.pipeline.load(PipelineItem { id, buffer, gain }, seconds, autoplay);
    }

    pub fn set_next(&self, next: Option<(Arc<TrackBuffer>, u64, f32)>) {
        self.pipeline.set_next(next.map(|(buffer, id, gain)| PipelineItem { id, buffer, gain }));
    }

    /// Seconds into the song being heard.
    pub fn position(&self) -> f64 {
        self.pipeline.position()
    }

    /// The length of the song being heard, once known.
    pub fn duration(&self) -> Option<f64> {
        self.pipeline.current_item().and_then(|(_, buffer)| buffer.duration())
    }

    /// How much of the song being heard is in hand, 0...1.
    pub fn buffered_fraction(&self) -> f64 {
        self.pipeline.current_item().map(|(_, buffer)| buffer.covered_fraction()).unwrap_or(0.0)
    }

    pub fn poll_events(&self, timeout: Duration) -> Vec<PipelineEvent> {
        self.events.drain(timeout)
    }

    // Sound

    /// Sets the whole sound at once. It glides in over a few hundredths of a second.
    pub fn apply_sound(&self, params: &SoundParams) {
        let mut current = self.sound.lock().unwrap_or_else(|p| p.into_inner());
        if *current != *params {
            *current = *params;
            self.dsp.set_params(params);
        }
    }

    pub fn set_head_turn(&self, radians: f32) {
        self.dsp.set_head_turn(radians);
    }

    pub fn take_levels(&self) -> Levels {
        let _reader = self.readers.lock().unwrap_or_else(|p| p.into_inner());
        self.dsp.take_levels()
    }

    pub fn spectrum(&self, bands: usize, low_hz: f64, high_hz: f64) -> Vec<f32> {
        let _reader = self.readers.lock().unwrap_or_else(|p| p.into_inner());
        self.dsp.spectrum(bands, low_hz, high_hz)
    }

    pub fn processing_latency(&self) -> u32 {
        self.dsp.latency()
    }
}
