//! A port of the iPhone app's PlaybackPipelineTests and TrackBufferTests. The pipeline is driven the way the hardware
//! drives it: a thread pulls 480 frames (10 ms) at a time.

mod common;

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use boomcore::pipeline::{PipelineEvent, PipelineItem, PlaybackPipeline};
use boomcore::track::TrackBuffer;
use common::*;
use PipelineEvent::*;

/// The timing-sensitive tests run one at a time, as the Swift suite does.
static SERIAL: Mutex<()> = Mutex::new(());

fn serial() -> MutexGuard<'static, ()> {
    SERIAL.lock().unwrap_or_else(|p| p.into_inner())
}

#[derive(Default)]
struct Heard {
    left: Vec<f32>,
    right: Vec<f32>,
    events: Vec<PipelineEvent>,
}

/// Stands in for the audio hardware: pulls from the pipeline and keeps what it heard.
struct Listener {
    pipeline: Arc<PlaybackPipeline>,
    heard: Arc<Mutex<Heard>>,
    stop: Arc<AtomicBool>,
    thread: Option<JoinHandle<()>>,
}

impl Listener {
    fn new() -> Listener {
        let pipeline = Arc::new(PlaybackPipeline::new());
        let heard = Arc::new(Mutex::new(Heard::default()));
        let stop = Arc::new(AtomicBool::new(false));
        let sink = heard.clone();
        pipeline.set_event_sink(Some(Box::new(move |event| sink.lock().unwrap().events.push(event))));
        let (puller, box_, flag) = (pipeline.clone(), heard.clone(), stop.clone());
        let thread = std::thread::spawn(move || {
            let (mut left, mut right) = (vec![0f32; 480], vec![0f32; 480]);
            while !flag.load(Ordering::Relaxed) {
                let silent = puller.render(&mut left, &mut right);
                if !silent {
                    let mut heard = box_.lock().unwrap();
                    heard.left.extend_from_slice(&left);
                    heard.right.extend_from_slice(&right);
                }
                // Each pull is 10 ms of audio; resting 0.6 ms makes the listener run about 16 times real time, which
                // the decode thread outruns comfortably, as it does the real hardware.
                std::thread::sleep(Duration::from_micros(600));
            }
        });
        Listener { pipeline, heard, stop, thread: Some(thread) }
    }

    fn events(&self) -> Vec<PipelineEvent> {
        self.heard.lock().unwrap().events.clone()
    }
    fn left(&self) -> Vec<f32> {
        self.heard.lock().unwrap().left.clone()
    }
    fn heard_frames(&self) -> usize {
        self.heard.lock().unwrap().left.len()
    }

    /// Waits until `condition` holds, for at most `seconds`.
    fn wait(&self, seconds: f64, condition: impl Fn(&Listener) -> bool) -> bool {
        let deadline = Instant::now() + Duration::from_secs_f64(seconds);
        while Instant::now() < deadline {
            if condition(self) {
                return true;
            }
            std::thread::sleep(Duration::from_millis(5));
        }
        condition(self)
    }
}

impl Drop for Listener {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
        self.pipeline.stop();
    }
}

fn item(id: u64, buffer: TrackBuffer) -> PipelineItem {
    PipelineItem { id, buffer: Arc::new(buffer), gain: 1.0 }
}

fn tone(samples: &[f32]) -> f64 {
    frequency(samples, 48_000.0)
}

/// The longest run of near-silence inside `samples`, in frames.
fn longest_quiet_run(samples: &[f32]) -> usize {
    let (mut longest, mut run) = (0, 0);
    for sample in samples {
        if sample.abs() < 0.002 {
            run += 1;
            longest = longest.max(run);
        } else {
            run = 0;
        }
    }
    longest
}

fn near(a: usize, b: i64, tolerance: i64) -> bool {
    (a as i64 - b).abs() <= tolerance
}

const SONG_FRAMES: i64 = 601 * 960 - 312;

#[test]
fn plays_a_song_from_start_to_end() {
    let _serial = serial();
    let (buffer, _) = fixture_buffer("sine-opus.webm", true);
    let listener = Listener::new();
    listener.pipeline.load(item(1, buffer), 0.0, true);
    assert!(listener.wait(5.0, |l| l.events().contains(&Ended(1))));
    assert_eq!(listener.events().first(), Some(&Playing(1)));
    // 601 packets of 960 frames, less the 312-frame lead-in; the hardware's last pull is padded with silence.
    assert!(near(listener.heard_frames(), SONG_FRAMES, 1_500), "heard {}", listener.heard_frames());
    let heard = listener.left();
    assert!((tone(&heard[48_000..480_000]) - 440.0).abs() < 1.0);
    // The lead-in is gone: the tone is there from the first milliseconds.
    assert!(heard[..960].iter().fold(0f32, |m, s| m.max(s.abs())) > 0.02);
    assert!((listener.pipeline.position() - 12.0).abs() < 0.1);
}

#[test]
fn jumps_land_on_the_asked_moment() {
    let _serial = serial();
    let (buffer, _) = fixture_buffer("sine-opus.webm", true);
    let listener = Listener::new();
    // Loaded but held, so that what is heard afterwards is exactly what follows the jump.
    listener.pipeline.load(item(1, buffer), 0.0, false);
    listener.pipeline.seek(9.0);
    // The new place is reported at once, not only after the decode thread has got there.
    assert_eq!(listener.pipeline.position(), 9.0);
    assert_eq!(listener.pipeline.current_item_id(), Some(1));
    std::thread::sleep(Duration::from_millis(60));
    assert_eq!(listener.heard_frames(), 0);
    assert!((listener.pipeline.position() - 9.0).abs() < 0.001);
    listener.pipeline.play();
    assert!(listener.wait(5.0, |l| l.events().contains(&Ended(1))));
    // Three seconds remain after the nine-second mark (12.01 s less the lead-in).
    assert!(near(listener.heard_frames(), SONG_FRAMES - 9 * 48_000, 1_500), "heard {}", listener.heard_frames());
    assert!((listener.pipeline.position() - 12.0).abs() < 0.1);
    let left = listener.left();
    assert!((tone(&left[4_800..120_000]) - 440.0).abs() < 2.0);
    // The jump's run-up is discarded: no burst of decoder noise at the start.
    assert!(left[..480].iter().fold(0f32, |m, s| m.max(s.abs())) < 1.0);
}

#[test]
fn jumps_while_playing_carry_on_from_the_new_place() {
    let _serial = serial();
    let (buffer, _) = fixture_buffer("sine-opus.webm", true);
    let listener = Listener::new();
    listener.pipeline.load(item(1, buffer), 0.0, true);
    assert!(listener.wait(2.0, |l| l.heard_frames() > 24_000));
    listener.pipeline.seek(2.0);
    std::thread::sleep(Duration::from_millis(30));
    listener.pipeline.seek(10.5);
    assert!(listener.wait(5.0, |l| l.events().contains(&Ended(1))));
    assert!((listener.pipeline.position() - 12.0).abs() < 0.1);
    assert_eq!(listener.events().last(), Some(&Ended(1)));
    assert!(!listener.events().iter().any(|e| matches!(e, Failed(..))));
}

#[test]
fn starts_part_way_through_when_asked() {
    let _serial = serial();
    let (buffer, _) = fixture_buffer("sine-aac.frag.mp4", true);
    let listener = Listener::new();
    listener.pipeline.load(item(1, buffer), 10.0, true);
    assert!(listener.wait(5.0, |l| l.events().contains(&Ended(1))));
    // The 44.1 kHz song comes out at 48 kHz: two seconds remain.
    let frames = listener.heard_frames();
    assert!(frames > (1.9 * 48_000.0) as usize && frames < (2.2 * 48_000.0) as usize, "heard {frames}");
    assert!((tone(&listener.left()[4_800..86_400]) - 440.0).abs() < 2.0);
}

#[test]
fn the_next_song_joins_on_without_a_gap() {
    let _serial = serial();
    let (first, _) = fixture_buffer("sine-opus.webm", true);
    let (second, _) = fixture_buffer("sine-opus.webm", true);
    let listener = Listener::new();
    listener.pipeline.load(item(1, first), 9.0, true);
    listener.pipeline.set_next(Some(item(2, second)));
    assert!(listener.wait(8.0, |l| l.events().contains(&Ended(2))));
    assert_eq!(listener.events(), vec![Playing(1), Advanced(2), Playing(2), Ended(2)]);
    // Three seconds of the first song, then all of the second.
    assert!(near(listener.heard_frames(), 3 * 48_000 + SONG_FRAMES, 2_400), "heard {}", listener.heard_frames());
    // Around the join: no dropout longer than the codec's own padding (well under 30 ms).
    let join = 3 * 48_000;
    let left = listener.left();
    let quiet = longest_quiet_run(&left[join - 9_600..join + 9_600]);
    println!("the join: longest quiet run {quiet} frames");
    assert!(quiet < 1_440);
}

#[test]
fn the_next_song_joins_on_without_a_gap_across_formats() {
    // Not in the iPhone suite: an Opus song followed by a 44.1 kHz AAC one (resampled), and the AAC tail flushed.
    let _serial = serial();
    let (first, _) = fixture_buffer("sine-opus.webm", true);
    let (second, _) = fixture_buffer("sine-aac.frag.mp4", true);
    let listener = Listener::new();
    listener.pipeline.load(item(1, first), 10.0, true);
    listener.pipeline.set_next(Some(item(2, second)));
    assert!(listener.wait(8.0, |l| l.events().contains(&Ended(2))));
    assert_eq!(listener.events(), vec![Playing(1), Advanced(2), Playing(2), Ended(2)]);
    let aac_frames = (518.0 * 1024.0 * 48_000.0 / 44_100.0f64).round() as i64;
    assert!(near(listener.heard_frames(), 2 * 48_000 - 312 + aac_frames, 2_400), "heard {}", listener.heard_frames());
    let join = 2 * 48_000 - 312;
    let left = listener.left();
    // The AAC test stream starts with the encoder's priming (1024 frames at 44.1 kHz, 1115 at 48 kHz) and states no
    // edit list to drop it, so it is heard, as on the iPhone; beyond that, the join adds nothing.
    let quiet = longest_quiet_run(&left[join - 9_600..join + 9_600]);
    let first_sound = left[join..].iter().position(|s| s.abs() >= 0.002).unwrap();
    println!("Opus to AAC join: longest quiet run {quiet} frames; the AAC song's first sound {first_sound} frames in");
    assert!(quiet < 1_440 + 1_115);
}

#[test]
fn waits_when_audio_runs_out_and_carries_on_when_it_arrives() {
    let _serial = serial();
    let (buffer, packets) = fixture_buffer("sine-opus.webm", false);
    let buffer = Arc::new(buffer);
    let listener = Listener::new();
    listener.pipeline.load(PipelineItem { id: 1, buffer: buffer.clone(), gain: 1.0 }, 0.0, true);
    // Nothing yet: no sound, and the pipeline says it is waiting.
    std::thread::sleep(Duration::from_millis(80));
    assert_eq!(listener.events(), vec![Waiting(1)]);
    assert_eq!(listener.heard_frames(), 0);
    append(&buffer, &packets[..150]);
    assert!(listener.wait(3.0, |l| l.events().iter().filter(|&&e| e == Waiting(1)).count() == 2));
    assert_eq!(listener.events().get(1), Some(&Playing(1)));
    append(&buffer, &packets[150..]);
    buffer.seal();
    assert!(listener.wait(5.0, |l| l.events().contains(&Ended(1))));
    assert_eq!(listener.events(), vec![Waiting(1), Playing(1), Waiting(1), Playing(1), Ended(1)]);
    assert!(near(listener.heard_frames(), SONG_FRAMES, 1_500));
}

#[test]
fn pause_holds_the_place() {
    let _serial = serial();
    let (buffer, _) = fixture_buffer("sine-opus.webm", true);
    let listener = Listener::new();
    listener.pipeline.load(item(1, buffer), 0.0, true);
    assert!(listener.wait(2.0, |l| l.heard_frames() > 48_000));
    listener.pipeline.pause();
    std::thread::sleep(Duration::from_millis(120));
    let held = listener.pipeline.position();
    let heard = listener.heard_frames();
    std::thread::sleep(Duration::from_millis(150));
    assert_eq!(listener.pipeline.position(), held);
    assert_eq!(listener.heard_frames(), heard);
    listener.pipeline.play();
    assert!(listener.wait(5.0, |l| l.events().contains(&Ended(1))));
    assert!(near(listener.heard_frames(), SONG_FRAMES, 2_000));
}

/// The listener jumped ahead in a song: the skipped part is not in hand, the end is. Playback must run to the end and
/// into the next song without waiting for the skipped part.
#[test]
fn the_end_of_a_song_does_not_wait_for_its_skipped_middle() {
    let _serial = serial();
    let (first, packets) = fixture_buffer("sine-opus.webm", false);
    append(&first, &packets[..100]);
    append(&first, &packets[500..]);
    first.seal();
    assert!(!first.is_complete());
    assert!(first.has_reached_end(601));
    let (second, _) = fixture_buffer("sine-opus.webm", true);
    let listener = Listener::new();
    listener.pipeline.load(item(1, first), 10.5, true);
    listener.pipeline.set_next(Some(item(2, second)));
    assert!(listener.wait(8.0, |l| l.events().contains(&Ended(2))));
    assert_eq!(listener.events(), vec![Playing(1), Advanced(2), Playing(2), Ended(2)]);
    // A second and a half of the first song, then all of the second.
    assert!(near(listener.heard_frames(), 72_000 + SONG_FRAMES, 2_400), "heard {}", listener.heard_frames());
}

#[test]
fn a_song_that_cannot_be_decoded_says_so() {
    // Not in the iPhone suite: a stream whose setup the decoder refuses is reported, not played as silence.
    let _serial = serial();
    let (buffer, packets) = fixture_buffer("sine-opus.webm", false);
    let mut info = buffer.info().unwrap();
    info.magic_cookie[18] = 255; // an Opus channel mapping family no decoder here knows
    let broken = TrackBuffer::new();
    broken.set_track(&info, None);
    append(&broken, &packets);
    broken.seal();
    let listener = Listener::new();
    listener.pipeline.load(item(7, broken), 0.0, true);
    assert!(listener.wait(3.0, |l| l.events().iter().any(|e| matches!(e, Failed(7, _)))));
    assert_eq!(listener.heard_frames(), 0);
}

// The packet store

#[test]
fn the_track_buffer_knows_what_it_holds_and_what_is_missing() {
    let (_, packets) = fixture_buffer("sine-opus.webm", true);
    let (shell, _) = fixture_buffer("sine-opus.webm", false);
    append(&shell, &packets[..100]);
    // Twelve seconds of 20 ms packets, give or take the last one.
    assert!(matches!(shell.expected_packet_count(), Some(600) | Some(601)));
    append(&shell, &packets[300..400]);
    append(&shell, &packets[50..120]);
    assert_eq!(shell.held_packet_count(), 220);
    assert_eq!(shell.contiguous_end(0), 120);
    assert_eq!(shell.contiguous_end(350), 400);
    assert_eq!(shell.contiguous_end(200), 200);
    assert_eq!(shell.first_gap(0), Some(120));
    assert_eq!(shell.first_gap(320), Some(400));
    assert!(!shell.is_complete());
    assert_eq!(shell.packet_data(118, 10).len(), 2);
    assert!(shell.packet_data(120, 10).is_empty());
    append(&shell, &packets);
    shell.seal();
    assert!(shell.is_complete());
    assert_eq!(shell.first_gap(0), None);
    assert!(shell.has_reached_end(601));
    assert!(!shell.has_reached_end(600));
    assert_eq!(shell.covered_fraction(), 1.0);

    // The end is known as soon as the tail is in and sealed, whatever is missing before it.
    let (holed, _) = fixture_buffer("sine-opus.webm", false);
    append(&holed, &packets[..10]);
    // Inside the song nothing counts as its end, however little is in hand.
    assert!(!holed.has_reached_end(10));
    assert!(!holed.has_reached_end(599));
    append(&holed, &packets[590..]);
    holed.seal();
    assert!(!holed.is_complete());
    assert!(holed.has_reached_end(601));
    assert!(!holed.has_reached_end(300));
    assert_eq!(holed.first_gap(595), Some(10));
    // Times and packets agree.
    assert_eq!(holed.packet_index_at(9.0), 450);
    assert!((holed.time_of_packet(450) - 9.0).abs() < 1e-9);
}

#[test]
fn the_track_buffer_survives_being_written_to_disk_and_read_back() {
    let (buffer, packets) = fixture_buffer("sine-aac.frag.mp4", true);
    let file = buffer.serialized().unwrap();
    let restored = TrackBuffer::from_serialized(&file).unwrap();
    assert!(restored.is_complete());
    assert_eq!(restored.info(), buffer.info());
    assert_eq!(restored.held_packet_count(), packets.len());
    let first: Vec<Vec<u8>> = packets[..5].iter().map(|p| p.data.clone()).collect();
    assert_eq!(restored.packet_data(0, 5), first);
    assert_eq!(restored.packet_data(packets.len() - 1, 5), vec![packets.last().unwrap().data.clone()]);
    assert!(TrackBuffer::from_serialized(&file[..file.len() - 10]).is_none());
    assert!(TrackBuffer::from_serialized(b"nonsense").is_none());

    // And through a real file.
    let path = std::env::temp_dir().join(format!("boomcore-test-{}.track", std::process::id()));
    assert!(buffer.write_to(&path));
    let read = TrackBuffer::read_from(&path).unwrap();
    assert_eq!(read.held_bytes(), buffer.held_bytes());
    std::fs::remove_file(&path).unwrap();
    // A song not wholly in hand is not written.
    let (partial, _) = fixture_buffer("sine-aac.frag.mp4", false);
    append(&partial, &packets[..100]);
    assert!(!partial.write_to(&path));
    assert!(!path.exists());
}
