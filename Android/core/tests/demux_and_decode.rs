//! A port of the iPhone app's DemuxAndDecodeTests: the two stream parsers and the decoders, on the test tones.

mod common;

use boomcore::decode::{PacketDecoder, Pcm, To48k};
use boomcore::demux::webm::opus_packet_samples;
use boomcore::demux::{Codec, DemuxError, Packet};
use common::*;

fn decode_all(decoder: &mut PacketDecoder, packets: &[Packet], batch: usize) -> Pcm {
    let mut pcm = Pcm::default();
    for chunk in packets.chunks(batch) {
        decoder.decode(chunk.iter().map(|p| p.data.as_slice()), &mut pcm).unwrap();
    }
    pcm
}

// WebM / Opus

#[test]
fn webm_reads_the_header_and_every_packet() {
    let data = fixture("sine-opus.webm");
    let result = demux(&mut webm(), &[&data]).unwrap();
    assert_eq!(result.track.codec, Codec::Opus);
    assert_eq!(result.track.timescale, 48_000);
    assert_eq!(result.track.channels, 2);
    assert_eq!(result.track.lead_in, 312);
    assert!(result.track.magic_cookie.starts_with(b"OpusHead"));
    assert!((result.track.duration.unwrap() - 12.0).abs() < 0.1);
    assert_eq!(result.packets.len(), 601);
    assert_eq!(result.packets.iter().map(|p| p.data.len()).sum::<usize>(), 247_614);
}

#[test]
fn webm_packet_times_are_exact_and_gapless() {
    let result = demux(&mut webm(), &[&fixture("sine-opus.webm")]).unwrap();
    for (index, packet) in result.packets.iter().enumerate() {
        assert_eq!(packet.duration, 960);
        assert_eq!(packet.decode_time, index as i64 * 960);
    }
}

#[test]
fn webm_pieces_of_any_size_give_the_same_packets() {
    let data = fixture("sine-opus.webm");
    let whole = demux(&mut webm(), &[&data]).unwrap();
    for seed in [1u64, 2, 3] {
        let shredded = demux(&mut webm(), &shred(&data, seed, if seed == 1 { 7 } else { 5000 })).unwrap();
        assert_eq!(shredded.packets, whole.packets);
        assert_eq!(shredded.track, whole.track);
    }
}

fn find_all(data: &[u8], marker: &[u8]) -> Vec<usize> {
    data.windows(marker.len()).enumerate().filter(|(_, w)| *w == marker).map(|(i, _)| i).collect()
}

#[test]
fn webm_a_reset_mid_cluster_picks_up_at_the_next_cluster() {
    let data = fixture("sine-opus.webm");
    let whole = demux(&mut webm(), &[&data]).unwrap();
    // Where the clusters start (the element id 1F 43 B6 75).
    let starts = find_all(&data, &[0x1F, 0x43, 0xB6, 0x75]);
    assert!(starts.len() >= 3);
    let mut demuxer = webm();
    // The player appends the header and part of the first cluster, then jumps.
    demuxer.feed(&data[..starts[0] + 5000]).unwrap();
    demuxer.reset();
    let later: Vec<Packet> = demuxer
        .feed(&data[starts[2]..])
        .unwrap()
        .into_iter()
        .flat_map(|e| match e {
            boomcore::demux::DemuxEvent::Packets(p) => p,
            _ => vec![],
        })
        .collect();
    let first = later.first().unwrap().decode_time;
    assert!(first > 0);
    let expected: Vec<Packet> = whole.packets.iter().filter(|p| p.decode_time >= first).cloned().collect();
    assert_eq!(later, expected);
}

#[test]
fn opus_decodes_to_the_test_tones() {
    let result = demux(&mut webm(), &[&fixture("sine-opus.webm")]).unwrap();
    let mut decoder = PacketDecoder::new(&result.track).unwrap();
    assert_eq!(decoder.sample_rate, 48_000);
    assert_eq!(decoder.frames_per_packet, 960);
    // In batches, as the player will.
    let pcm = decode_all(&mut decoder, &result.packets, 50);
    assert!((pcm.len() as i64 - 601 * 960).abs() <= 960);
    let body = 48_000..48_000 * 11;
    let left = frequency(&pcm.left[body.clone()], 48_000.0);
    let right = frequency(&pcm.right[body.clone()], 48_000.0);
    println!("opus tones: left {left:.2} Hz, right {right:.2} Hz, libopus {}", boomcore::decode::opus_version());
    assert!((left - 440.0).abs() < 1.0);
    assert!((right - 660.0).abs() < 1.0);
    let peak = pcm.left[body].iter().fold(0f32, |m, s| m.max(s.abs()));
    assert!(peak > 0.05 && peak <= 1.0);
}

// Fragmented MP4 / AAC

#[test]
fn mp4_reads_the_header_and_every_packet() {
    let result = demux(&mut mp4(), &[&fixture("sine-aac.frag.mp4")]).unwrap();
    assert_eq!(result.track.codec, Codec::Aac);
    assert_eq!(result.track.timescale, 44_100);
    assert_eq!(result.track.sample_rate, 44_100.0);
    assert_eq!(result.track.channels, 2);
    // AAC-LC, 44.1 kHz, stereo (ffmpeg adds an extension after these two bytes).
    assert_eq!(&result.track.audio_specific_config[..2], &[0x12, 0x10]);
    assert_eq!(result.packets.len(), 518);
    assert_eq!(result.packets.iter().map(|p| p.data.len()).sum::<usize>(), 192_034);
}

#[test]
fn mp4_packet_times_are_gapless() {
    let result = demux(&mut mp4(), &[&fixture("sine-aac.frag.mp4")]).unwrap();
    let mut expected = 0i64;
    for packet in &result.packets {
        assert_eq!(packet.decode_time, expected);
        expected += packet.duration as i64;
    }
    assert!(result.packets[..result.packets.len() - 1].iter().all(|p| p.duration == 1024));
}

#[test]
fn mp4_pieces_of_any_size_give_the_same_packets() {
    let data = fixture("sine-aac.frag.mp4");
    let whole = demux(&mut mp4(), &[&data]).unwrap();
    for seed in [1u64, 2, 3] {
        let shredded = demux(&mut mp4(), &shred(&data, seed, if seed == 1 { 7 } else { 5000 })).unwrap();
        assert_eq!(shredded.packets, whole.packets);
        assert_eq!(shredded.track, whole.track);
    }
}

#[test]
fn mp4_a_reset_mid_fragment_picks_up_at_the_next_fragment() {
    let data = fixture("sine-aac.frag.mp4");
    let whole = demux(&mut mp4(), &[&data]).unwrap();
    let starts: Vec<usize> = find_all(&data, b"moof").into_iter().map(|i| i - 4).collect();
    assert!(starts.len() >= 3);
    let mut demuxer = mp4();
    demuxer.feed(&data[..starts[0] + 3000]).unwrap();
    demuxer.reset();
    let later: Vec<Packet> = demuxer
        .feed(&data[starts[2]..])
        .unwrap()
        .into_iter()
        .flat_map(|e| match e {
            boomcore::demux::DemuxEvent::Packets(p) => p,
            _ => vec![],
        })
        .collect();
    let first = later.first().unwrap().decode_time;
    assert!(first > 0);
    let expected: Vec<Packet> = whole.packets.iter().filter(|p| p.decode_time >= first).cloned().collect();
    assert_eq!(later, expected);
}

#[test]
fn mp4_garbage_is_refused_not_crashed_on() {
    let mut demuxer = mp4();
    let garbage = [0xFF, 0xFF, 0xFF, 0xFF, 0x6D, 0x64, 0x61, 0x74, 0, 0, 0, 0];
    assert!(matches!(demuxer.feed(&garbage), Err(DemuxError::Corrupt(_))));
    // After a refusal the demuxer starts clean.
    let result = demux(&mut demuxer, &[&fixture("sine-aac.frag.mp4")]).unwrap();
    assert_eq!(result.packets.len(), 518);
}

#[test]
fn aac_decodes_to_the_test_tones() {
    let result = demux(&mut mp4(), &[&fixture("sine-aac.frag.mp4")]).unwrap();
    let mut decoder = PacketDecoder::new(&result.track).unwrap();
    assert_eq!(decoder.sample_rate, 44_100);
    assert_eq!(decoder.frames_per_packet, 1024);
    let pcm = decode_all(&mut decoder, &result.packets, 43);
    assert!((pcm.len() as i64 - 518 * 1024).abs() <= 2 * 1024);
    let body = 44_100..44_100 * 11;
    let left = frequency(&pcm.left[body.clone()], 44_100.0);
    let right = frequency(&pcm.right[body.clone()], 44_100.0);
    println!("aac tones: left {left:.2} Hz, right {right:.2} Hz, lead-in {}", result.track.lead_in);
    assert!((left - 440.0).abs() < 1.0);
    assert!((right - 660.0).abs() < 1.0);
    // One eighth of full scale, as made.
    let level = rms(&pcm.left[body]);
    assert!((level - 0.125 / 2f64.sqrt()).abs() < 0.005, "rms {level}");
}

#[test]
fn the_resampler_carries_the_tones_to_48k_without_seams() {
    let result = demux(&mut mp4(), &[&fixture("sine-aac.frag.mp4")]).unwrap();
    let mut decoder = PacketDecoder::new(&result.track).unwrap();
    let mut resampler = To48k::new(44_100).unwrap();
    let mut out = Pcm::default();
    let mut pcm = Pcm::default();
    // Packet by packet, as the pipeline feeds it.
    for packet in &result.packets {
        pcm.clear();
        decoder.decode([packet.data.as_slice()], &mut pcm).unwrap();
        resampler.push(&pcm, &mut out).unwrap();
    }
    resampler.flush(&mut out).unwrap();
    let decoded_frames = 518 * 1024;
    let expected = (decoded_frames as f64 * 48_000.0 / 44_100.0).round() as usize;
    assert_eq!(out.len(), expected);
    let body = 48_000..48_000 * 11;
    assert!((frequency(&out.left[body.clone()], 48_000.0) - 440.0).abs() < 1.0);
    assert!((frequency(&out.right[body.clone()], 48_000.0) - 660.0).abs() < 1.0);
    // The test tone was encoded with noise about 35 dB down (ffmpeg's own decoder measures the same); resampling
    // must add nothing to it.
    let mut plain = Pcm::default();
    let mut fresh = PacketDecoder::new(&result.track).unwrap();
    fresh.decode(result.packets.iter().map(|p| p.data.as_slice()), &mut plain).unwrap();
    let before = residual_db(&plain.left[44_100..44_100 * 11], 440.0, 44_100.0);
    let after = residual_db(&out.left[body], 440.0, 48_000.0);
    println!("440 Hz tone: residual {before:.2} dB at 44.1 kHz, {after:.2} dB after resampling to 48 kHz");
    assert!((after - before).abs() < 0.2);
}

#[test]
fn the_resampler_adds_nothing_audible_to_a_pure_tone() {
    // A pure 440 Hz sine at 44.1 kHz, fed in packets of 1024 as AAC decodes, comes out at 48 kHz as a pure sine.
    let sine: Vec<f32> =
        (0..44_100 * 4).map(|n| (0.125 * (2.0 * std::f64::consts::PI * 440.0 * n as f64 / 44_100.0).sin()) as f32).collect();
    let mut resampler = To48k::new(44_100).unwrap();
    let mut out = Pcm::default();
    for chunk in sine.chunks(1024) {
        resampler.push(&Pcm { left: chunk.to_vec(), right: chunk.to_vec() }, &mut out).unwrap();
    }
    resampler.flush(&mut out).unwrap();
    assert_eq!(out.len(), 48_000 * 4);
    let residual = residual_db(&out.left[4_800..48_000 * 4 - 4_800], 440.0, 48_000.0);
    println!("pure 440 Hz sine through the resampler: residual {residual:.1} dB");
    assert!(residual < -120.0);
}

/// How far below a sine of `hertz` everything else in `samples` lies, in dB.
fn residual_db(samples: &[f32], hertz: f64, rate: f64) -> f64 {
    let (mut s, mut c) = (0.0, 0.0);
    for (n, &x) in samples.iter().enumerate() {
        let angle = 2.0 * std::f64::consts::PI * hertz * n as f64 / rate;
        s += x as f64 * angle.sin();
        c += x as f64 * angle.cos();
    }
    let (a, b) = (2.0 * s / samples.len() as f64, 2.0 * c / samples.len() as f64);
    let mut error = 0.0;
    let mut signal = 0.0;
    for (n, &x) in samples.iter().enumerate() {
        let angle = 2.0 * std::f64::consts::PI * hertz * n as f64 / rate;
        let fit = a * angle.sin() + b * angle.cos();
        error += (x as f64 - fit).powi(2);
        signal += fit * fit;
    }
    10.0 * (error / signal).log10()
}

#[test]
fn opus_packet_lengths_follow_the_first_byte() {
    // 0xFC: CELT full band, 20 ms, stereo, one frame: what YouTube sends.
    assert_eq!(opus_packet_samples(0xFC, 0), 960);
    // SILK 60 ms, one frame.
    assert_eq!(opus_packet_samples(0x18, 0), 2880);
    // CELT 10 ms, two frames.
    assert_eq!(opus_packet_samples(0xF1, 0), 960);
    // CELT 2.5 ms, a counted packet of 8 frames.
    assert_eq!(opus_packet_samples(0xE3, 8), 960);
}

#[test]
fn unknown_mime_types_get_no_demuxer() {
    assert!(boomcore::demux::AudioDemuxer::for_mime_type("video/mp4").is_none());
    assert!(boomcore::demux::AudioDemuxer::for_mime_type("audio/ogg").is_none());
}
