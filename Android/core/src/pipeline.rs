//! Turns songs' compressed packets into the stream of samples the audio hardware pulls. A port of the iPhone app's
//! `PlaybackPipeline`.
//!
//! A decode thread keeps a ring buffer a few seconds ahead of the render thread. Everything is converted to one fixed
//! format (48 kHz stereo float): YouTube's Opus is already 48 kHz, so it passes through unresampled, and one song can
//! run into the next without the output ever being reconfigured.

use std::sync::mpsc::{self, Receiver, RecvTimeoutError, Sender};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use crate::decode::{DecodeError, PacketDecoder, Pcm, To48k};
use crate::ffi::{self, BoomRenderer, RingPtr};
use crate::track::{PacketRun, TrackBuffer};

pub const SAMPLE_RATE: f64 = 48_000.0;
/// About 5.5 seconds: generous, since jumps swap in a fresh ring rather than waiting for this one to drain.
const RING_FRAMES: u32 = 1 << 18;
/// Audio that must be in hand before playback starts or restarts after running dry.
const START_FRAMES: u32 = 4_800;
const RESTART_FRAMES: u32 = 19_200;
const TICK: Duration = Duration::from_millis(20);

/// A song handed to the pipeline: its audio (which may still be arriving) and how loud to play it.
#[derive(Clone)]
pub struct PipelineItem {
    /// Chosen by the caller; reported back in the events.
    pub id: u64,
    pub buffer: Arc<TrackBuffer>,
    /// A linear factor applied to this song only (loudness normalisation). 1 leaves it as recorded.
    pub gain: f32,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FailReason {
    /// The stream is in a format there is no decoder for.
    Unsupported,
    /// A packet could not be decoded.
    Undecodable,
}

impl FailReason {
    /// What the listener is told, in the iPhone app's words.
    pub fn message(self) -> &'static str {
        match self {
            FailReason::Unsupported => "This song's audio format isn't supported.",
            FailReason::Undecodable => "This song's audio couldn't be decoded.",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PipelineEvent {
    /// The song's audio is now being heard (after a load, a jump, or a wait for more audio).
    Playing(u64),
    /// Playback moved on to the next song. Always followed at once by `Playing` (it ran in without a gap) or
    /// `Waiting` (its audio is not in hand yet) for that song.
    Advanced(u64),
    /// There is nothing to hear at the song's current place yet (after a load or a jump), or the audio in hand ran
    /// out. Playback starts by itself when the audio arrives.
    Waiting(u64),
    /// The last song played to its end.
    Ended(u64),
    Failed(u64, FailReason),
}

pub type EventSink = Box<dyn FnMut(PipelineEvent) + Send>;

/// What `position` reads, kept apart from the decode thread so any thread can ask without waiting for it.
#[derive(Default)]
struct Timeline {
    ring: Option<RingPtr>,
    item: Option<(u64, Arc<TrackBuffer>)>,
    start_offset: i64,
    ring_start: u64,
    held: f64,
}

/// The renderer pointer, shared by the control side and the render thread (its functions are made for that).
struct RendererPtr(*mut BoomRenderer);
unsafe impl Send for RendererPtr {}
unsafe impl Sync for RendererPtr {}

enum Command {
    SetEventSink(Option<EventSink>),
    Load(PipelineItem, f64, bool),
    SetNext(Option<PipelineItem>),
    Play,
    Pause,
    Seek(f64),
    Stop,
    SetItemGain(u64, f32),
    Shutdown,
}

pub struct PlaybackPipeline {
    renderer: Arc<RendererPtr>,
    timeline: Arc<Mutex<Timeline>>,
    commands: Mutex<Sender<Command>>,
    thread: Option<JoinHandle<()>>,
}

impl Default for PlaybackPipeline {
    fn default() -> Self {
        Self::new()
    }
}

fn lock<T>(mutex: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

impl PlaybackPipeline {
    pub fn new() -> Self {
        let raw = unsafe { ffi::boom_renderer_create() };
        assert!(!raw.is_null(), "out of memory creating the renderer");
        let renderer = Arc::new(RendererPtr(raw));
        let timeline = Arc::new(Mutex::new(Timeline::default()));
        let (sender, receiver) = mpsc::channel();
        let mut decoding = Decoding::new(renderer.clone(), timeline.clone());
        let thread = std::thread::Builder::new()
            .name("boom-pipeline".into())
            .spawn(move || decoding.run(receiver))
            .expect("could not start the decode thread");
        PlaybackPipeline { renderer, timeline, commands: Mutex::new(sender), thread: Some(thread) }
    }

    fn send(&self, command: Command) {
        let _ = lock(&self.commands).send(command);
    }

    // The render thread

    /// Fills the hardware's buffers. Real-time safe (no locks, no allocation). One render thread at a time.
    /// Returns true when it wrote only silence.
    #[inline]
    pub fn render(&self, left: &mut [f32], right: &mut [f32]) -> bool {
        let frames = left.len().min(right.len()) as u32;
        unsafe { ffi::boom_renderer_render(self.renderer.0, left.as_mut_ptr(), right.as_mut_ptr(), frames) }
    }

    pub(crate) fn renderer_raw(&self) -> *mut BoomRenderer {
        self.renderer.0
    }

    // Control (any thread)

    pub fn set_event_sink(&self, sink: Option<EventSink>) {
        self.send(Command::SetEventSink(sink));
    }

    /// Starts on a song, dropping whatever was playing.
    pub fn load(&self, item: PipelineItem, seconds: f64, autoplay: bool) {
        // The new song and place are reported from this moment, before the decode thread has caught up.
        let start = seconds.max(0.0);
        *lock(&self.timeline) =
            Timeline { ring: None, item: Some((item.id, item.buffer.clone())), start_offset: 0, ring_start: 0, held: start };
        self.send(Command::Load(item, start, autoplay));
    }

    /// Says what follows the current song, so it joins on without a gap. None: nothing follows.
    pub fn set_next(&self, item: Option<PipelineItem>) {
        self.send(Command::SetNext(item));
    }

    pub fn play(&self) {
        self.send(Command::Play);
    }

    pub fn pause(&self) {
        self.send(Command::Pause);
    }

    pub fn seek(&self, seconds: f64) {
        // `position` answers with the target at once, so a scrubber never sees the old place after letting go.
        let target = seconds.max(0.0);
        {
            let mut timeline = lock(&self.timeline);
            timeline.ring = None;
            timeline.held = target;
        }
        self.send(Command::Seek(target));
    }

    pub fn stop(&self) {
        self.send(Command::Stop);
    }

    /// The overall level as a linear factor.
    pub fn set_gain(&self, gain: f32) {
        unsafe { ffi::boom_renderer_set_gain(self.renderer.0, gain) }
    }

    /// Changes the current or next song's own level (loudness normalisation switched on or off).
    pub fn set_item_gain(&self, id: u64, gain: f32) {
        self.send(Command::SetItemGain(id, gain));
    }

    /// Seconds into the song being heard.
    pub fn position(&self) -> f64 {
        position(&lock(&self.timeline))
    }

    /// The song being heard.
    pub fn current_item_id(&self) -> Option<u64> {
        lock(&self.timeline).item.as_ref().map(|(id, _)| *id)
    }

    /// The song being heard, with its packet store.
    pub fn current_item(&self) -> Option<(u64, Arc<TrackBuffer>)> {
        lock(&self.timeline).item.clone()
    }

    /// Render calls so far (for diagnostics).
    pub fn render_count(&self) -> u64 {
        unsafe { ffi::boom_renderer_render_count(self.renderer.0) }
    }
}

impl Drop for PlaybackPipeline {
    fn drop(&mut self) {
        self.send(Command::Shutdown);
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
        // The decode thread has let go of every ring; nothing renders any more (the output is closed first).
        unsafe { ffi::boom_renderer_destroy(self.renderer.0) };
    }
}

fn position(timeline: &Timeline) -> f64 {
    let Some(ring) = timeline.ring else { return timeline.held };
    let frames = timeline.start_offset + ring.frames_read() as i64 - timeline.ring_start as i64;
    (frames as f64 / SAMPLE_RATE).max(0.0)
}

// The decode thread

/// Where a deck is in its life. It starts (and restarts, after a jump) unplaced.
enum Stage {
    /// Not decoding yet: waiting to be set to start at this many seconds into the song, which needs the stream's
    /// header and first packet to have arrived.
    Unplaced { start_at: f64 },
    /// Decoding packets into the ring.
    Decoding,
    /// Every packet of the song has been decoded (or it could not be decoded at all).
    Finished,
}

/// Decoding state of one song.
struct Deck {
    item: PipelineItem,
    stage: Stage,
    // Made once the stream's header is in, and kept for the deck's life.
    decoder: Option<PacketDecoder>,
    resampler: Option<To48k>,
    next_packet: usize,
    /// Frames one packet decodes to (at the decoder's rate), from the store's packet length. Set when placed.
    frames_per_packet: usize,
    /// Frames (at 48 kHz) still to drop before writing: the encoder's lead-in, or the run-up to a jump target.
    discard: usize,
    /// Where in the song, in 48 kHz frames, this deck's first written frame lies.
    start_offset: i64,
    /// The ring's write count when this deck's first frame went in.
    ring_start: u64,
    frames_written: u64,
}

impl Deck {
    fn new(item: PipelineItem, start_at: f64) -> Deck {
        Deck {
            item,
            stage: Stage::Unplaced { start_at },
            decoder: None,
            resampler: None,
            next_packet: 0,
            frames_per_packet: 960,
            discard: 0,
            start_offset: 0,
            ring_start: 0,
            frames_written: 0,
        }
    }

    fn is_finished(&self) -> bool {
        matches!(self.stage, Stage::Finished)
    }

    /// Back to the start of its life, to begin at `seconds` in a fresh ring.
    fn restart(&mut self, seconds: f64) {
        self.stage = Stage::Unplaced { start_at: seconds };
        self.frames_written = 0;
        self.ring_start = 0;
        if let Some(decoder) = &mut self.decoder {
            decoder.reset();
        }
        if let Some(resampler) = &mut self.resampler {
            resampler.reset();
        }
    }
}

/// What is coming out. Changed only in `set_output`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Output {
    /// Paused by the listener: silent, and the place is kept.
    Held,
    /// Meant to play, but the audio at this place is not in hand yet: silent until it arrives.
    Waiting,
    /// Audio is being heard.
    Sounding,
    /// The last song played to its end.
    Ended,
}

struct Decoding {
    renderer: Arc<RendererPtr>,
    timeline: Arc<Mutex<Timeline>>,
    decks: Vec<Deck>,
    ring: Option<RingPtr>,
    output: Output,
    sink: Option<EventSink>,
    ticking: bool,
    /// Rings the render thread may still be reading for one more call, and when they may go.
    retired: Vec<(RingPtr, Instant)>,
    // Scratch, kept to avoid allocating per packet.
    run: PacketRun,
    decoded: Pcm,
    converted: Pcm,
}

impl Decoding {
    fn new(renderer: Arc<RendererPtr>, timeline: Arc<Mutex<Timeline>>) -> Decoding {
        Decoding {
            renderer,
            timeline,
            decks: Vec::new(),
            ring: None,
            output: Output::Held,
            sink: None,
            ticking: false,
            retired: Vec::new(),
            run: PacketRun::default(),
            decoded: Pcm::default(),
            converted: Pcm::default(),
        }
    }

    fn run(&mut self, commands: Receiver<Command>) {
        let mut next_tick = Instant::now() + TICK;
        loop {
            let received = if self.ticking {
                commands.recv_timeout(next_tick.saturating_duration_since(Instant::now()))
            } else {
                commands.recv().map_err(|_| RecvTimeoutError::Disconnected)
            };
            match received {
                Ok(Command::Shutdown) | Err(RecvTimeoutError::Disconnected) => break,
                Ok(command) => self.handle(command),
                Err(RecvTimeoutError::Timeout) => {}
            }
            if self.ticking && Instant::now() >= next_tick {
                self.tick();
                next_tick = Instant::now() + TICK;
            }
            self.free_retired(false);
        }
        self.set_output(Output::Held);
        let last = unsafe { ffi::boom_renderer_swap_ring(self.renderer.0, std::ptr::null_mut()) };
        if !last.is_null() {
            self.retire(RingPtr(last));
        }
        self.free_retired(true);
    }

    fn handle(&mut self, command: Command) {
        match command {
            Command::SetEventSink(sink) => self.sink = sink,
            Command::Load(item, seconds, autoplay) => {
                self.decks = vec![Deck::new(item, 0.0)];
                self.rebuild(seconds, if autoplay { Output::Waiting } else { Output::Held });
                self.ticking = true;
            }
            Command::SetNext(item) => self.set_next(item),
            Command::Play => {
                if self.output == Output::Held {
                    self.set_output(Output::Waiting);
                    self.tick();
                }
            }
            Command::Pause => {
                if matches!(self.output, Output::Waiting | Output::Sounding) {
                    self.set_output(Output::Held);
                }
            }
            Command::Seek(seconds) => {
                if !self.decks.is_empty() {
                    // A jump keeps the listener's intent: playing carries on from the new place, paused stays paused.
                    // After the end, the jump leaves it paused; `play()` starts it.
                    self.rebuild(seconds, self.carry_on());
                }
            }
            Command::Stop => {
                self.set_output(Output::Held);
                self.decks.clear();
                self.ticking = false;
                let old = unsafe { ffi::boom_renderer_swap_ring(self.renderer.0, std::ptr::null_mut()) };
                if !old.is_null() {
                    self.retire(RingPtr(old));
                }
                self.ring = None;
                *lock(&self.timeline) = Timeline::default();
            }
            Command::SetItemGain(id, gain) => self.set_item_gain(id, gain),
            Command::Shutdown => {}
        }
    }

    /// Playing carries on as waiting (to play as soon as audio is there); anything else stays held.
    fn carry_on(&self) -> Output {
        if matches!(self.output, Output::Waiting | Output::Sounding) { Output::Waiting } else { Output::Held }
    }

    fn emit(&mut self, event: PipelineEvent) {
        if let Some(sink) = &mut self.sink {
            sink(event);
        }
    }

    fn current_seconds(&self) -> f64 {
        position(&lock(&self.timeline))
    }

    fn set_next(&mut self, item: Option<PipelineItem>) {
        if self.decks.is_empty() {
            return;
        }
        if self.decks.len() == 2 && item.as_ref().map(|i| i.id) == Some(self.decks[1].item.id) {
            return;
        }
        // Some of the old follower may already be queued in the ring behind the current song.
        let already_queued = self.decks[1..].iter().any(|deck| deck.frames_written > 0);
        let has_item = item.is_some();
        self.decks.truncate(1);
        if let Some(item) = item {
            self.decks.push(Deck::new(item, 0.0));
        }
        if already_queued {
            let seconds = self.current_seconds();
            let next = if self.output == Output::Held { Output::Held } else { Output::Waiting };
            self.rebuild(seconds, next);
        } else if let Some(ring) = self.ring {
            if has_item && self.output != Output::Ended {
                // Reopen a ring that was closed because nothing followed.
                ring.set_finished(false);
            }
        }
    }

    fn set_item_gain(&mut self, id: u64, gain: f32) {
        let Some(index) = self.decks.iter().position(|deck| deck.item.id == id) else { return };
        if self.decks[index].item.gain == gain {
            return;
        }
        let mut item = self.decks[index].item.clone();
        item.gain = gain;
        let needs_rebuild = self.decks[index].frames_written > 0;
        self.decks[index] = Deck::new(item, 0.0);
        if needs_rebuild {
            let seconds = self.current_seconds();
            self.rebuild(seconds, self.carry_on());
        }
    }

    /// Frees a ring the render thread may still be reading for one more call.
    fn retire(&mut self, ring: RingPtr) {
        self.retired.push((ring, Instant::now() + Duration::from_secs(1)));
    }

    fn free_retired(&mut self, all: bool) {
        let now = Instant::now();
        self.retired.retain(|&(ring, when)| {
            if all || now >= when {
                unsafe { ring.destroy() };
                false
            } else {
                true
            }
        });
    }

    /// The one place the output state changes: it also tells the render thread to sound or hold.
    fn set_output(&mut self, next: Output) {
        self.output = next;
        unsafe { ffi::boom_renderer_set_playing(self.renderer.0, next == Output::Sounding) };
    }

    /// Starts afresh with the first deck at `start` seconds: a new ring, every deck back to unplaced, then swapped in
    /// with a fade. `next` is the state to carry on in: `Waiting` to play as soon as audio is there, `Held` to stay
    /// paused.
    fn rebuild(&mut self, start: f64, next: Output) {
        if self.decks.is_empty() {
            return;
        }
        let Some(fresh) = RingPtr::create(RING_FRAMES) else { return };
        for (index, deck) in self.decks.iter_mut().enumerate() {
            deck.restart(if index == 0 { start } else { 0.0 });
        }

        // Fade out what is sounding before the swap, so a jump never clicks.
        if self.output == Output::Sounding {
            self.set_output(Output::Held);
            let deadline = Instant::now() + Duration::from_millis(40);
            while !unsafe { ffi::boom_renderer_is_idle(self.renderer.0) } && Instant::now() < deadline {
                std::thread::sleep(Duration::from_millis(1));
            }
        }
        self.set_output(next);
        self.ring = Some(fresh);
        let first = &self.decks[0].item;
        *lock(&self.timeline) = Timeline {
            ring: Some(fresh),
            item: Some((first.id, first.buffer.clone())),
            start_offset: (start * SAMPLE_RATE) as i64,
            ring_start: 0,
            held: start,
        };
        let first_id = first.id;
        self.decode_ahead(24_000);
        let old = unsafe { ffi::boom_renderer_swap_ring(self.renderer.0, fresh.0) };
        if !old.is_null() {
            self.retire(RingPtr(old));
        }
        self.tick();
        // Nothing to hear at the new place yet: the audio there is still on its way.
        if self.output == Output::Waiting {
            self.emit(PipelineEvent::Waiting(first_id));
        }
    }

    fn tick(&mut self) {
        let Some(ring) = self.ring else { return };
        if self.decks.is_empty() {
            return;
        }
        self.decode_ahead(48_000);

        // Has the render thread crossed into the next song?
        let read = ring.frames_read();
        while self.decks.len() > 1 {
            let next = &self.decks[1];
            let crossed = next.frames_written > 0 && read >= next.ring_start;
            let waiting_at_the_join =
                self.decks[0].is_finished() && next.frames_written == 0 && ring.available_read() == 0;
            if !(crossed || waiting_at_the_join) {
                break;
            }
            self.decks.remove(0);
            let next = &self.decks[0];
            let id = next.item.id;
            *lock(&self.timeline) = Timeline {
                ring: Some(ring),
                item: Some((id, next.item.buffer.clone())),
                start_offset: next.start_offset,
                ring_start: if next.frames_written > 0 { next.ring_start } else { ring.frames_written() },
                held: 0.0,
            };
            self.emit(PipelineEvent::Advanced(id));
            // Say at once whether the new song is being heard or still awaited.
            if waiting_at_the_join && self.output == Output::Sounding {
                self.set_output(Output::Waiting);
            }
            match self.output {
                Output::Sounding => self.emit(PipelineEvent::Playing(id)),
                Output::Waiting => self.emit(PipelineEvent::Waiting(id)),
                Output::Held | Output::Ended => {}
            }
        }
        let Some(heard) = self.decks.first() else { return };
        let heard_id = heard.item.id;
        let heard_started = heard.frames_written > 0;

        let available = ring.available_read();
        let finished = ring.is_finished();
        match self.output {
            Output::Held | Output::Ended => {}
            Output::Sounding => {
                if available == 0 {
                    if finished {
                        self.set_output(Output::Ended);
                        self.emit(PipelineEvent::Ended(heard_id));
                    } else {
                        self.set_output(Output::Waiting);
                        self.emit(PipelineEvent::Waiting(heard_id));
                    }
                }
            }
            Output::Waiting => {
                if finished && available == 0 {
                    // Nothing left to play at all (a jump to the very end).
                    self.set_output(Output::Ended);
                    self.emit(PipelineEvent::Ended(heard_id));
                    return;
                }
                let enough = if heard_started { RESTART_FRAMES } else { START_FRAMES };
                let everything_is_in = finished || self.decks.iter().all(Deck::is_finished);
                if available >= enough || (everything_is_in && available > 0) {
                    self.set_output(Output::Sounding);
                    self.emit(PipelineEvent::Playing(heard_id));
                }
            }
        }
    }

    /// Decodes into the ring until it is full, the audio in hand runs out, or `budget` frames were written.
    fn decode_ahead(&mut self, budget: usize) {
        let Some(ring) = self.ring else { return };
        let mut produced = 0;
        while produced < budget {
            let Some(index) = self.decks.iter().position(|deck| !deck.is_finished()) else {
                if !ring.is_finished() {
                    ring.set_finished(true);
                }
                return;
            };
            if !self.place(index) {
                return;
            }
            let deck = &mut self.decks[index];
            let Some(decoder) = &deck.decoder else { return };

            let ratio = SAMPLE_RATE / decoder.sample_rate as f64;
            let frames_per_packet = (deck.frames_per_packet as f64 * ratio).ceil() as i64 + 8;
            let room = ring.available_write() as i64;
            let count = 12.min((room - frames_per_packet) / frames_per_packet);
            if count < 1 {
                return;
            }

            let got = deck.item.buffer.packets(deck.next_packet, count as usize, &mut self.run);
            if got == 0 {
                if !deck.item.buffer.has_reached_end(deck.next_packet) {
                    return;
                }
                // The song's last packet is decoded: what the resampler still holds goes out too.
                if let Some(resampler) = &mut deck.resampler {
                    if (room as usize) < resampler.tail_bound() {
                        return;
                    }
                    self.converted.clear();
                    if resampler.flush(&mut self.converted).is_ok() {
                        produced += write(&mut self.converted, deck, ring);
                    }
                }
                deck.stage = Stage::Finished;
                continue;
            }
            deck.next_packet += got;
            self.decoded.clear();
            let result = (|| -> Result<(), DecodeError> {
                let decoder = deck.decoder.as_mut().expect("placed");
                decoder.decode(self.run.iter(), &mut self.decoded)?;
                if let Some(resampler) = &mut deck.resampler {
                    self.converted.clear();
                    resampler.push(&self.decoded, &mut self.converted)?;
                    std::mem::swap(&mut self.decoded, &mut self.converted);
                }
                Ok(())
            })();
            match result {
                Ok(()) => produced += write(&mut self.decoded, deck, ring),
                Err(_) => {
                    deck.stage = Stage::Finished;
                    let id = deck.item.id;
                    self.emit(PipelineEvent::Failed(id, FailReason::Undecodable));
                    return;
                }
            }
        }
    }

    /// Gets an unplaced deck ready to decode from its start position. That needs the stream's header (to make the
    /// decoder) and its first packet (to know the packet length); until both are in, the answer is false. A deck
    /// that is already decoding is left as it is.
    fn place(&mut self, index: usize) -> bool {
        let deck = &mut self.decks[index];
        let start = match deck.stage {
            Stage::Unplaced { start_at } => start_at,
            Stage::Decoding => return true,
            Stage::Finished => return false,
        };
        let Some(info) = deck.item.buffer.info() else { return false };
        if deck.decoder.is_none() {
            let made = PacketDecoder::new(&info).and_then(|decoder| {
                let resampler =
                    if decoder.sample_rate != SAMPLE_RATE as u32 { Some(To48k::new(decoder.sample_rate)?) } else { None };
                Ok((decoder, resampler))
            });
            match made {
                Ok((decoder, resampler)) => {
                    deck.decoder = Some(decoder);
                    deck.resampler = resampler;
                }
                Err(_) => {
                    deck.stage = Stage::Finished;
                    let id = deck.item.id;
                    self.emit(PipelineEvent::Failed(id, FailReason::Unsupported));
                    return false;
                }
            }
        }
        let packet_duration = deck.item.buffer.packet_duration();
        if packet_duration == 0 {
            return false;
        }
        let decoder = deck.decoder.as_mut().expect("made above");

        // In the decoder's output, the moment `start` sits after the encoder's lead-in.
        let rate = decoder.sample_rate as f64;
        let lead_in = info.lead_in as f64 / info.timescale as f64 * rate;
        let target = (start * rate + lead_in).round().max(0.0) as usize;
        // The packet length as the store counts packets (the iPhone code takes the decoder's assumed 960 for Opus,
        // which is right for YouTube's 20 ms packets only).
        let frames_per_packet = ((packet_duration as f64 * rate / info.timescale as f64).round() as usize).max(1);
        deck.frames_per_packet = frames_per_packet;
        // Opus needs 80 ms of run-up after a jump, whatever its packet length.
        let preroll = if frames_per_packet == decoder.frames_per_packet {
            decoder.preroll_packets
        } else {
            3840usize.div_ceil(frames_per_packet).max(2)
        };
        let wanted_packet = target / frames_per_packet;
        let first_packet = wanted_packet.saturating_sub(preroll);
        let ratio = SAMPLE_RATE / rate;
        deck.next_packet = first_packet;
        deck.discard = ((target - first_packet * frames_per_packet) as f64 * ratio).round() as usize;
        deck.start_offset = (start * SAMPLE_RATE).round() as i64;
        decoder.reset();
        if let Some(resampler) = &mut deck.resampler {
            resampler.reset();
        }
        deck.stage = Stage::Decoding;
        true
    }
}

/// Writes decoded audio to the ring, less whatever the deck still has to drop. Returns the frames written.
fn write(pcm: &mut Pcm, deck: &mut Deck, ring: RingPtr) -> usize {
    let mut skip = 0;
    if deck.discard > 0 {
        skip = deck.discard.min(pcm.len());
        deck.discard -= skip;
    }
    if pcm.len() <= skip {
        return 0;
    }
    let gain = deck.item.gain;
    let (left, right) = (&mut pcm.left[skip..], &mut pcm.right[skip..]);
    if gain != 1.0 {
        left.iter_mut().chain(right.iter_mut()).for_each(|sample| *sample *= gain);
    }
    if deck.frames_written == 0 {
        deck.ring_start = ring.frames_written();
    }
    let written = ring.write(left, right);
    deck.frames_written += written as u64;
    written as usize
}
