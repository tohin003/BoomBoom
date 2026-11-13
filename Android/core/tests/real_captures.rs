//! Checks against streams captured from the real player, when a folder of them is at hand:
//!   BOOM_CAPTURES=/path/with/real-opus.webm+real-aac.mp4 cargo test --release --test real_captures -- --nocapture
//! The captures are music and are never added to the repository. Each test passes quietly when they are absent.

mod common;

use std::path::PathBuf;

use boomcore::decode::{PacketDecoder, Pcm, To48k};
use boomcore::demux::Packet;
use common::*;

fn capture(name: &str) -> Option<Vec<u8>> {
    let path = captures()?.join(name);
    path.is_file().then(|| std::fs::read(&path).unwrap())
}

fn read_f32(path: &std::path::Path) -> Vec<f32> {
    std::fs::read(path).unwrap().chunks_exact(4).map(|b| f32::from_le_bytes(b.try_into().unwrap())).collect()
}

fn check_stream(name: &str, data: &[u8]) -> (boomcore::demux::TrackInfo, Vec<Packet>) {
    let mut demuxer = if name.ends_with(".webm") { webm() } else { mp4() };
    let whole = demux(&mut demuxer, &shred(data, 9, 200_000)).unwrap();
    let mut expected = 0i64;
    let mut gaps = 0;
    for packet in &whole.packets {
        if packet.decode_time != expected {
            gaps += 1;
        }
        expected = packet.decode_time + packet.duration as i64;
    }
    let track = whole.track;
    println!(
        "REAL {name}: {:?} {} Hz, {} packets, {} bytes, {:.3} s, gaps {gaps}, lead-in {}, stated {:?} s, default {:?}",
        track.codec,
        track.sample_rate,
        whole.packets.len(),
        whole.packets.iter().map(|p| p.data.len()).sum::<usize>(),
        expected as f64 / track.timescale as f64,
        track.lead_in,
        track.duration,
        track.default_packet_duration
    );
    assert_eq!(gaps, 0);
    assert!(whole.packets.len() > 1000);
    (track, whole.packets)
}

/// The app's Opus decoding against the reference decoder (libopus in floating point through ffmpeg), sample for
/// sample, over the first minute. Make the reference with
///   ffmpeg -request_sample_fmt flt -c:a libopus -i real-opus.webm -t 60 -f f32le -ac 2 -ar 48000 real-opus.reference.f32
/// The iPhone app (Apple's decoder) measures -136.6 dB; the bar is -120 dB.
#[test]
fn opus_decoding_matches_the_reference_decoder() {
    let (Some(stream), Some(folder)) = (capture("real-opus.webm"), captures()) else { return };
    let wanted = folder.join("real-opus.reference.f32");
    if !wanted.is_file() {
        return;
    }
    let (track, packets) = check_stream("real-opus.webm", &stream);
    let mut decoder = PacketDecoder::new(&track).unwrap();
    let mut pcm = Pcm::default();
    // A minute of the song, as the reference holds, in batches as the player decodes.
    for batch in packets[..3100.min(packets.len())].chunks(50) {
        decoder.decode(batch.iter().map(|p| p.data.as_slice()), &mut pcm).unwrap();
    }
    let reference = read_f32(&wanted);
    let frames = reference.len() / 2;
    // The two decoders may drop a different number of start-up frames: find how they line up, then compare.
    let difference = |shift: usize, from: usize, count: usize| {
        let (mut error, mut signal, mut worst) = (0.0f64, 0.0f64, 0.0f32);
        for index in from..from + count {
            for (mine, theirs) in [(pcm.left[index + shift], reference[2 * index]), (pcm.right[index + shift], reference[2 * index + 1])] {
                error += (mine as f64 - theirs as f64).powi(2);
                signal += (theirs as f64).powi(2);
                worst = worst.max((mine - theirs).abs());
            }
        }
        (error, signal, worst)
    };
    let shift = (0..=2000).min_by(|&a, &b| difference(a, 48_000, 4_800).0.total_cmp(&difference(b, 48_000, 4_800).0)).unwrap();
    let count = frames.min(pcm.len() - shift) - 96_000;
    let (error, signal, worst) = difference(shift, 48_000, count);
    let decibels = 10.0 * (error.max(1e-30) / signal).log10();
    println!(
        "REAL decode: {count} frames compared from frame {shift} on; the difference from the reference decoder is \
         {decibels:.1} dB below the music, largest single difference {worst:e} ({})",
        boomcore::decode::opus_version()
    );
    assert!(count > 2_000_000);
    assert!(decibels < -120.0);
}

/// The whole AAC capture through the decoder and the resampler, packet by packet as the pipeline feeds them: every
/// packet decodes, the 48 kHz output has exactly the length the 44.1 kHz audio calls for, and it is the same, to the
/// last bit that matters, as resampling the whole song in one piece (so no join between packets leaves a trace).
/// With BOOM_AAC_REFERENCE (ffmpeg's decoding of the same file, `ffmpeg -i real-aac.mp4 -f f32le -ac 2 x.f32`), the
/// decoding itself is compared with ffmpeg's AAC decoder too.
#[test]
fn aac_decodes_whole_and_gapless_at_48k() {
    let Some(stream) = capture("real-aac.mp4") else { return };
    let (track, packets) = check_stream("real-aac.mp4", &stream);
    let mut decoder = PacketDecoder::new(&track).unwrap();
    assert_eq!(decoder.sample_rate, 44_100);
    let mut resampler = To48k::new(decoder.sample_rate).unwrap();
    let mut decoded = Pcm::default();
    let mut out = Pcm::default();
    let mut step = Pcm::default();
    for packet in &packets {
        step.clear();
        decoder.decode([packet.data.as_slice()], &mut step).unwrap();
        assert_eq!(step.len(), 1024);
        decoded.left.extend_from_slice(&step.left);
        decoded.right.extend_from_slice(&step.right);
        resampler.push(&step, &mut out).unwrap();
    }
    resampler.flush(&mut out).unwrap();
    let expected = (decoded.len() as f64 * 48_000.0 / 44_100.0).round() as usize;
    assert_eq!(out.len(), expected);
    assert!(out.left.iter().chain(&out.right).all(|s| s.is_finite()));
    let peak = out.left.iter().fold(0f32, |m, s| m.max(s.abs()));

    // The same song resampled in one piece.
    let mut whole = To48k::new(44_100).unwrap();
    let mut one_piece = Pcm::default();
    whole.push(&decoded, &mut one_piece).unwrap();
    whole.flush(&mut one_piece).unwrap();
    assert_eq!(one_piece.len(), out.len());
    let (mut error, mut signal) = (0.0f64, 0.0f64);
    for i in 0..out.len() {
        error += (out.left[i] as f64 - one_piece.left[i] as f64).powi(2) + (out.right[i] as f64 - one_piece.right[i] as f64).powi(2);
        signal += (one_piece.left[i] as f64).powi(2) + (one_piece.right[i] as f64).powi(2);
    }
    let seams = if error == 0.0 { f64::NEG_INFINITY } else { 10.0 * (error / signal).log10() };
    println!(
        "REAL aac: {} packets decoded to {} frames at 44.1 kHz, {} frames ({:.3} s) at 48 kHz, peak {peak:.3}; \
         packet by packet against in one piece: {seams:.1} dB",
        packets.len(),
        decoded.len(),
        out.len(),
        out.len() as f64 / 48_000.0
    );
    assert!(seams < -120.0);
    assert!(peak > 0.05 && peak <= 1.5);

    if let Some(reference) = std::env::var_os("BOOM_AAC_REFERENCE").map(PathBuf::from).filter(|p| p.is_file()) {
        let reference = read_f32(&reference);
        let frames = (reference.len() / 2).min(decoded.len());
        let (mut error, mut signal, mut worst) = (0.0f64, 0.0f64, 0.0f32);
        for i in 0..frames {
            for (mine, theirs) in [(decoded.left[i], reference[2 * i]), (decoded.right[i], reference[2 * i + 1])] {
                error += (mine as f64 - theirs as f64).powi(2);
                signal += (theirs as f64).powi(2);
                worst = worst.max((mine - theirs).abs());
            }
        }
        let decibels = 10.0 * (error.max(1e-30) / signal).log10();
        println!(
            "REAL aac against ffmpeg's decoder: {frames} frames ({} in the reference), difference {decibels:.1} dB, \
             largest single difference {worst:e}",
            reference.len() / 2
        );
        assert!(decibels < -90.0);
    }
}

/// The whole AAC capture played through the pipeline itself (pulled by hand, as the hardware would): one `Playing`,
/// one `Ended`, never waiting, and the samples heard are exactly those of decoding and resampling the song directly
/// (after the renderer's fade-in over the first pull).
#[test]
fn aac_plays_whole_through_the_pipeline() {
    use boomcore::pipeline::{PipelineEvent, PipelineItem, PlaybackPipeline};
    use std::sync::{Arc, Mutex};

    let Some(stream) = capture("real-aac.mp4") else { return };
    let (track, packets) = check_stream("real-aac.mp4", &stream);
    // The direct way, for comparison.
    let mut decoder = PacketDecoder::new(&track).unwrap();
    let mut resampler = To48k::new(44_100).unwrap();
    let (mut step, mut direct) = (Pcm::default(), Pcm::default());
    for packet in &packets {
        step.clear();
        decoder.decode([packet.data.as_slice()], &mut step).unwrap();
        resampler.push(&step, &mut direct).unwrap();
    }
    resampler.flush(&mut direct).unwrap();

    let buffer = boomcore::track::TrackBuffer::new();
    buffer.set_track(&track, None);
    let refs: Vec<_> = packets.iter().map(Packet::as_ref).collect();
    buffer.append(&refs);
    buffer.seal();
    let pipeline = Arc::new(PlaybackPipeline::new());
    let events = Arc::new(Mutex::new(Vec::new()));
    let sink = events.clone();
    pipeline.set_event_sink(Some(Box::new(move |e| sink.lock().unwrap().push(e))));
    pipeline.load(PipelineItem { id: 9, buffer: Arc::new(buffer), gain: 1.0 }, 0.0, true);
    let (mut left, mut right) = (Vec::new(), Vec::new());
    let (mut l, mut r) = (vec![0f32; 480], vec![0f32; 480]);
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(120);
    while !events.lock().unwrap().contains(&PipelineEvent::Ended(9)) && std::time::Instant::now() < deadline {
        if !pipeline.render(&mut l, &mut r) {
            left.extend_from_slice(&l);
            right.extend_from_slice(&r);
        }
        std::thread::sleep(std::time::Duration::from_micros(300));
    }
    pipeline.stop();
    let events = events.lock().unwrap().clone();
    println!("REAL aac through the pipeline: {} frames heard, {} expected; events {events:?}", left.len(), direct.len());
    assert_eq!(events, vec![PipelineEvent::Playing(9), PipelineEvent::Ended(9)]);
    // The last pull is padded with silence.
    assert!(left.len() >= direct.len() && left.len() < direct.len() + 480);
    assert_eq!(&left[480..direct.len()], &direct.left[480..]);
    assert_eq!(&right[480..direct.len()], &direct.right[480..]);
}
