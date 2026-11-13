//! The C sound processor called from Rust, on made-up signals (from the iPhone app's SoundProcessorTests), and the
//! whole chain rendered offline the way AAudio pulls it (from its AudioOutputTests).

mod common;

use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant};

use boomcore::ffi::{self, Dsp, SoundParams};
use boomcore::output::OfflineOutput;
use boomcore::pipeline::{PipelineItem, PlaybackPipeline};
use common::*;

const RATE: f64 = 48_000.0;

static SERIAL: Mutex<()> = Mutex::new(());

fn serial() -> MutexGuard<'static, ()> {
    SERIAL.lock().unwrap_or_else(|p| p.into_inner())
}

fn on(change: impl FnOnce(&mut SoundParams)) -> SoundParams {
    let mut params = SoundParams { enabled: true, limiter: true, width: 1.0, ..SoundParams::default() };
    change(&mut params);
    params
}

fn process(dsp: &Dsp, left: &[f32], right: &[f32]) -> (Vec<f32>, Vec<f32>) {
    let (mut left, mut right) = (left.to_vec(), right.to_vec());
    for (l, r) in left.chunks_mut(480).zip(right.chunks_mut(480)) {
        dsp.process(l, r, false);
    }
    (left, right)
}

fn sine(hertz: f64, amplitude: f64, seconds: f64) -> Vec<f32> {
    (0..(seconds * RATE) as usize).map(|n| (amplitude * (2.0 * std::f64::consts::PI * hertz * n as f64 / RATE).sin()) as f32).collect()
}

/// A small deterministic noise source (the tests must not depend on a random crate).
fn noise(seed: u64, count: usize) -> Vec<f32> {
    let mut state = seed;
    (0..count)
        .map(|_| {
            state = state.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1_442_695_040_888_963_407);
            ((state >> 40) as f64 / (1u64 << 24) as f64 * 2.0 - 1.0) as f32
        })
        .collect()
}

// The processor on its own

#[test]
fn flat_passes_every_sample_unchanged_and_only_later() {
    for params in [SoundParams::default(), on(|_| {})] {
        let dsp = Dsp::new(RATE);
        dsp.set_params(&params);
        let (left, right) = (noise(1, 48_000), noise(2, 48_000));
        let (out_left, out_right) = process(&dsp, &left, &right);
        let latency = dsp.latency() as usize;
        // The limiter's look-ahead (5 ms) and the reach of its true-peak measurement.
        assert_eq!(latency, 247);
        assert!(out_left[..latency].iter().all(|&s| s == 0.0));
        assert_eq!(&out_left[latency..], &left[..left.len() - latency]);
        assert_eq!(&out_right[latency..], &right[..right.len() - latency]);
    }
}

#[test]
fn the_limiter_never_lets_a_sample_past_its_ceiling() {
    let dsp = Dsp::new(RATE);
    // Loud noise and a loud low tone, boosted by 18 dB.
    dsp.set_params(&on(|p| {
        p.boost_db = 12.0;
        p.preamp_db = 6.0;
        p.band_gain_db[1] = 9.0;
    }));
    let tone = sine(60.0, 0.9, 2.0);
    let left: Vec<f32> = noise(3, tone.len()).iter().zip(&tone).map(|(n, t)| 0.5 * n + t).collect();
    let right = noise(4, tone.len());
    let (out_left, out_right) = process(&dsp, &left, &right);
    let peak = out_left.iter().chain(&out_right).fold(0f32, |m, s| m.max(s.abs()));
    println!("limiter: peak {peak:.6} against a ceiling of 0.966051 (-0.3 dB)");
    assert!(peak <= 0.966_051);
    assert!(dsp.take_levels().gain_reduction_db > 6.0);
}

#[test]
fn a_band_gives_what_the_response_curve_says() {
    let params = on(|p| {
        p.limiter = false;
        p.band_gain_db[4] = 12.0;
    });
    let curve = ffi::response(&params, RATE, &[500.0, 440.0, 660.0, 2000.0]);
    assert!((curve[0] - 12.0).abs() < 0.01);
    for (index, hertz) in [500.0, 440.0, 660.0, 2000.0].into_iter().enumerate() {
        let dsp = Dsp::new(RATE);
        dsp.set_params(&params);
        let input = sine(hertz, 0.05, 1.0);
        let (out, _) = process(&dsp, &input, &input);
        let lift = decibels(rms(&out[24_000..]) / rms(&input[24_000..]));
        println!("band 5 at +12 dB: {hertz} Hz measured {lift:+.3} dB, the curve says {:+.3} dB", curve[index]);
        assert!((lift - curve[index]).abs() < 0.05);
    }
    // The peak of the curve, without an engine, is the band's own +12 dB.
    assert!((ffi::peak_response(&params, RATE) - 12.0).abs() < 0.05);
    assert_eq!(ffi::band_frequencies(), [32.0, 64.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0]);
    // Off: no curve at all.
    assert!(ffi::response(&SoundParams::default(), RATE, &[500.0])[0] == 0.0);
}

// The whole chain, rendered offline

fn loaded(params: &SoundParams) -> OfflineOutput {
    let (buffer, _) = fixture_buffer("sine-opus.webm", true);
    let pipeline = Arc::new(PlaybackPipeline::new());
    let output = OfflineOutput::new(pipeline.clone());
    output.dsp.set_params(params);
    pipeline.load(PipelineItem { id: 1, buffer: Arc::new(buffer), gain: 1.0 }, 0.0, true);
    output
}

/// Pulls `seconds` of sound through the chain, skipping the silence before playback starts.
fn render(output: &OfflineOutput, seconds: f64) -> (Vec<f32>, Vec<f32>) {
    let wanted = (seconds * RATE) as usize;
    let (mut left, mut right) = (Vec::new(), Vec::new());
    let (mut l, mut r) = (vec![0f32; 480], vec![0f32; 480]);
    let deadline = Instant::now() + Duration::from_secs(10);
    while left.len() < wanted && Instant::now() < deadline {
        output.render(&mut l, &mut r);
        if left.is_empty() && l.iter().chain(&r).all(|&s| s == 0.0) {
            std::thread::sleep(Duration::from_millis(2));
            continue;
        }
        left.extend_from_slice(&l);
        right.extend_from_slice(&r);
        std::thread::sleep(Duration::from_micros(600));
    }
    (left, right)
}

const BODY: std::ops::Range<usize> = 9_600..86_400;

#[test]
fn off_means_untouched_and_on_but_flat_too() {
    let _serial = serial();
    let plain = render(&loaded(&SoundParams::default()), 2.0);
    let off = render(
        &loaded(&SoundParams {
            enabled: false,
            boost_db: 12.0,
            width: 2.0,
            band_gain_db: [0.0, 0.0, 0.0, 0.0, 12.0, 0.0, 0.0, 0.0, 0.0, 0.0],
            ..SoundParams::default()
        }),
        2.0,
    );
    let flat = render(&loaded(&on(|_| {})), 2.0);
    // Bit for bit the same samples as with nothing set.
    assert_eq!(&off.0[..48_000], &plain.0[..48_000]);
    assert_eq!(&off.1[..48_000], &plain.1[..48_000]);
    assert_eq!(&flat.0[..48_000], &plain.0[..48_000]);
    assert_eq!(&flat.1[..48_000], &plain.1[..48_000]);
    let level = rms(&plain.0[BODY]);
    assert!((level - 0.125 / 2f64.sqrt()).abs() < 0.01);
}

#[test]
fn a_band_lifts_the_tones_exactly_as_the_curve_says() {
    let _serial = serial();
    let plain = render(&loaded(&SoundParams::default()), 2.0);
    let params = on(|p| {
        p.limiter = false;
        p.band_gain_db[4] = 12.0;
    });
    let shaped = render(&loaded(&params), 2.0);
    // 440 Hz is on the left, 660 Hz on the right.
    let curve = ffi::response(&params, RATE, &[440.0, 660.0]);
    let left_lift = decibels(rms(&shaped.0[BODY]) / rms(&plain.0[BODY]));
    let right_lift = decibels(rms(&shaped.1[BODY]) / rms(&plain.1[BODY]));
    println!("whole chain: 440 Hz {left_lift:+.3} dB (curve {:+.3}), 660 Hz {right_lift:+.3} dB (curve {:+.3})", curve[0], curve[1]);
    assert!((left_lift - curve[0]).abs() < 0.1);
    assert!((right_lift - curve[1]).abs() < 0.1);
}

#[test]
fn the_limiter_holds_the_tones_under_full_scale() {
    let _serial = serial();
    let plain = render(&loaded(&SoundParams::default()), 2.0);
    // Twenty-four decibels on a tone at one eighth of full scale: twice full scale without the limiter.
    let output = loaded(&on(|p| {
        p.preamp_db = 12.0;
        p.boost_db = 12.0;
    }));
    let loud = render(&output, 2.0);
    let peak = loud.0[BODY].iter().fold(0f32, |m, s| m.max(s.abs()));
    assert!(peak <= 0.96606);
    // The tone now sits at the ceiling: 17.8 dB above where it started, held 6.3 dB under what was asked.
    let lift = decibels(rms(&loud.0[BODY]) / rms(&plain.0[BODY]));
    let reduction = output.dsp.take_levels().gain_reduction_db;
    println!("limiter on the tones: peak {peak:.5}, lift {lift:.2} dB, reduction {reduction:.2} dB");
    assert!((lift - 17.76).abs() < 0.3);
    assert!(reduction > 5.5 && reduction < 7.5);
}

#[test]
fn meters_and_spectrum_report_the_output() {
    let _serial = serial();
    let output = loaded(&SoundParams::default());
    render(&output, 1.0);
    let levels = output.dsp.take_levels();
    println!("meters: peak {:.4}/{:.4}, rms {:.4}/{:.4}", levels.peak_left, levels.peak_right, levels.rms_left, levels.rms_right);
    assert!((levels.peak_left - 0.125).abs() < 0.005);
    assert!((levels.peak_right - 0.125).abs() < 0.005);
    assert!((levels.rms_left - 0.125 / 2f32.sqrt()).abs() < 0.004);
    assert_eq!(levels.gain_reduction_db, 0.0);
    // Peaks are "since the last reading": nothing was rendered in between.
    assert_eq!(output.dsp.take_levels().peak_left, 0.0);

    let bands = 48;
    let spectrum = output.dsp.spectrum(bands, 20.0, 20_000.0);
    let band = |hertz: f64| (bands as f64 * (hertz / 20.0).ln() / 1000f64.ln()) as usize;
    // Each tone is at one eighth of full scale on one side, so one sixteenth (-24 dB) in the mono mix.
    assert!((spectrum[band(440.0)] + 24.1).abs() < 2.0);
    assert!((spectrum[band(660.0)] + 24.1).abs() < 2.0);
    assert!(spectrum[band(5_000.0)] < -70.0);
    assert!(spectrum[band(60.0)] < -70.0);
}

#[test]
fn width_and_balance() {
    let _serial = serial();
    let mono = render(&loaded(&on(|p| p.width = 0.0)), 2.0);
    // Mono: both sides carry the same mix of the two tones.
    assert!(mono.0[BODY].iter().zip(&mono.1[BODY]).all(|(a, b)| (a - b).abs() < 1e-6));
    assert!((rms(&mono.0[BODY]) - 0.0625).abs() < 0.002);
    let right_only = render(&loaded(&on(|p| p.balance = 1.0)), 2.0);
    assert!(right_only.0[BODY].iter().fold(0f32, |m, s| m.max(s.abs())) < 1e-5);
    assert!((rms(&right_only.1[BODY]) - 0.125 / 2f64.sqrt()).abs() < 0.002);
}
