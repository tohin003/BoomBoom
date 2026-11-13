//! Shared by the tests: the iPhone app's test streams (used where they are, never copied), and small helpers.
#![allow(dead_code)]

use std::path::PathBuf;

use boomcore::demux::{AudioDemuxer, DemuxError, DemuxEvent, Packet, TrackInfo};
use boomcore::track::TrackBuffer;

// The fixtures are 12-second stereo test tones made with ffmpeg in the same shapes YouTube serves:
//   sine-opus.webm      Opus, 48 kHz, 20 ms packets, WebM clusters (ffprobe: 601 packets, 247,614 bytes)
//   sine-aac.frag.mp4   AAC-LC, 44.1 kHz, fragmented MP4 (ffprobe: 518 packets, 192,034 bytes)
// Left channel 440 Hz, right channel 660 Hz, at one eighth of full scale.
pub fn fixture(name: &str) -> Vec<u8> {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../Packages/BoomEngine/Tests/BoomAudioTests/Fixtures")
        .join(name);
    std::fs::read(&path).unwrap_or_else(|e| panic!("fixture {}: {e}", path.display()))
}

pub struct Demuxed {
    pub track: TrackInfo,
    pub packets: Vec<Packet>,
}

pub fn demux(demuxer: &mut AudioDemuxer, pieces: &[&[u8]]) -> Result<Demuxed, DemuxError> {
    let mut track = None;
    let mut packets = Vec::new();
    for piece in pieces {
        for event in demuxer.feed(piece)? {
            match event {
                DemuxEvent::Track(info) => track = Some(info),
                DemuxEvent::Packets(more) => packets.extend(more),
            }
        }
    }
    Ok(Demuxed { track: track.expect("no track header"), packets })
}

pub fn webm() -> AudioDemuxer {
    AudioDemuxer::for_mime_type("audio/webm; codecs=\"opus\"").unwrap()
}

pub fn mp4() -> AudioDemuxer {
    AudioDemuxer::for_mime_type("audio/mp4; codecs=\"mp4a.40.2\"").unwrap()
}

/// Cuts `data` into pieces of random sizes, the way a network delivers it.
pub fn shred(data: &[u8], seed: u64, largest: usize) -> Vec<&[u8]> {
    let mut state = seed;
    let mut next = || {
        state = state.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1_442_695_040_888_963_407);
        (state >> 33) as usize
    };
    let mut pieces = Vec::new();
    let mut offset = 0;
    while offset < data.len() {
        let size = (data.len() - offset).min(1 + next() % largest);
        pieces.push(&data[offset..offset + size]);
        offset += size;
    }
    pieces
}

/// Counts upward zero crossings per second: the tone's frequency.
pub fn frequency(samples: &[f32], sample_rate: f64) -> f64 {
    let crossings = samples.windows(2).filter(|w| w[0] < 0.0 && w[1] >= 0.0).count();
    crossings as f64 / (samples.len() as f64 / sample_rate)
}

pub fn rms(samples: &[f32]) -> f64 {
    (samples.iter().map(|&s| s as f64 * s as f64).sum::<f64>() / samples.len().max(1) as f64).sqrt()
}

pub fn decibels(ratio: f64) -> f64 {
    20.0 * ratio.log10()
}

/// A packet store holding a fixture stream; `feed_all` false leaves the packets out (they are returned).
pub fn fixture_buffer(name: &str, feed_all: bool) -> (TrackBuffer, Vec<Packet>) {
    let data = fixture(name);
    let mut demuxer = if name.ends_with(".webm") { webm() } else { mp4() };
    let result = demux(&mut demuxer, &[&data]).unwrap();
    let buffer = TrackBuffer::new();
    buffer.set_track(&result.track, None);
    if feed_all {
        let refs: Vec<_> = result.packets.iter().map(Packet::as_ref).collect();
        buffer.append(&refs);
        buffer.seal();
    }
    (buffer, result.packets)
}

pub fn append(buffer: &TrackBuffer, packets: &[Packet]) {
    let refs: Vec<_> = packets.iter().map(Packet::as_ref).collect();
    buffer.append(&refs);
}

/// The folder of real captures, when one is given (BOOM_CAPTURES). The captures are music and never enter the
/// repository.
pub fn captures() -> Option<PathBuf> {
    std::env::var_os("BOOM_CAPTURES").map(PathBuf::from).filter(|p| p.is_dir())
}
