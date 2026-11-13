//! Decodes compressed audio packets to 32-bit float stereo: Opus with libopus (the reference decoder, built from
//! source), AAC-LC with Symphonia's pure-Rust decoder. A port of the iPhone app's `PacketDecoder`; `To48k` stands in
//! for the AVAudioConverter the iPhone pipeline uses to bring 44.1 kHz AAC to the pipeline's 48 kHz.
//!
//! The decoder keeps state between calls, so packets must be given in stream order. After a jump, call `reset()` and
//! start a few packets early (`preroll_packets`), throwing away what those produce: the first packets after a reset do
//! not decode cleanly on their own.

use std::fmt;

use audioadapter_buffers::direct::SequentialSliceOfVecs;
use opusic_sys as opus;
use rubato::{Async, FixedAsync, Indexing, Resampler, SincInterpolationParameters, SincInterpolationType, WindowFunction};
use symphonia_codec_aac::AacDecoder;
use symphonia_core::audio::{Audio, GenericAudioBufferRef};
use symphonia_core::codecs::audio::well_known::CODEC_ID_AAC;
use symphonia_core::codecs::audio::{AudioCodecParameters, AudioDecoder, AudioDecoderOptions};
use symphonia_core::packet::PacketRef as SymphoniaPacket;
use symphonia_core::units::{Duration, Timestamp};

use crate::demux::{mp4, Codec, TrackInfo};

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum DecodeError {
    /// There is no decoder for this stream as described.
    Unsupported(String),
    /// A packet could not be decoded.
    Failed(String),
}

impl fmt::Display for DecodeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            DecodeError::Unsupported(why) => write!(f, "unsupported: {why}"),
            DecodeError::Failed(why) => write!(f, "failed: {why}"),
        }
    }
}

impl std::error::Error for DecodeError {}

/// Planar stereo samples. A mono stream comes out on both sides.
#[derive(Default, Debug, Clone)]
pub struct Pcm {
    pub left: Vec<f32>,
    pub right: Vec<f32>,
}

impl Pcm {
    pub fn len(&self) -> usize {
        self.left.len()
    }
    pub fn is_empty(&self) -> bool {
        self.left.is_empty()
    }
    pub fn clear(&mut self) {
        self.left.clear();
        self.right.clear();
    }
}

/// The largest Opus packet: 120 ms at 48 kHz.
const OPUS_MOST_FRAMES: usize = 5760;

enum Inner {
    Opus { state: *mut opus::OpusDecoder, scratch: Vec<f32> },
    Aac(Box<AacDecoder>),
}

// The libopus state is only touched through `&mut self`.
unsafe impl Send for Inner {}

pub struct PacketDecoder {
    inner: Inner,
    /// The rate the decoded samples come out at.
    pub sample_rate: u32,
    /// PCM frames one packet decodes to.
    pub frames_per_packet: usize,
    /// Packets to decode and discard before the first wanted one after `reset()`.
    pub preroll_packets: usize,
}

impl PacketDecoder {
    pub fn new(track: &TrackInfo) -> Result<PacketDecoder, DecodeError> {
        match track.codec {
            Codec::Opus => Self::opus(track),
            Codec::Aac => Self::aac(track),
        }
    }

    fn opus(track: &TrackInfo) -> Result<PacketDecoder, DecodeError> {
        let head = &track.magic_cookie;
        if head.len() < 19 || !head.starts_with(b"OpusHead") {
            return Err(DecodeError::Unsupported("Opus without its identification header".into()));
        }
        let channels = head[9];
        let family = head[18];
        // Family 0 (mono or stereo) is what every stream this app meets uses; more channels would need the
        // multistream decoder.
        if channels == 0 || channels > 2 || family > 1 {
            return Err(DecodeError::Unsupported(format!("Opus with {channels} channels, mapping family {family}")));
        }
        let gain = i16::from_le_bytes([head[16], head[17]]);
        let mut error: i32 = 0;
        // Always two channels out: libopus spreads a mono stream over both.
        let state = unsafe { opus::opus_decoder_create(48_000, 2, &mut error) };
        if state.is_null() || error != opus::OPUS_OK {
            return Err(DecodeError::Unsupported(format!("libopus refused the stream ({error})")));
        }
        if gain != 0 {
            // The header's output gain (Q7.8 dB), which players are to apply.
            unsafe { opus::opus_decoder_ctl(state, opus::OPUS_SET_GAIN_REQUEST, gain as i32) };
        }
        let frames_per_packet = track.default_packet_duration.unwrap_or(960).max(1) as usize;
        Ok(PacketDecoder {
            inner: Inner::Opus { state, scratch: vec![0.0; OPUS_MOST_FRAMES * 2] },
            sample_rate: 48_000,
            frames_per_packet,
            // Opus needs 80 ms to settle (its SeekPreRoll).
            preroll_packets: (3840 / frames_per_packet).max(4),
        })
    }

    fn aac(track: &TrackInfo) -> Result<PacketDecoder, DecodeError> {
        let config = &track.audio_specific_config;
        let Some((rate, _)) = mp4::parse_audio_specific_config(config) else {
            return Err(DecodeError::Unsupported("AAC without its setup record".into()));
        };
        let mut params = AudioCodecParameters::new();
        params.for_codec(CODEC_ID_AAC).with_sample_rate(rate as u32).with_extra_data(config.clone().into_boxed_slice());
        let decoder = AacDecoder::try_new(&params, &AudioDecoderOptions::default())
            .map_err(|error| DecodeError::Unsupported(format!("AAC: {error}")))?;
        Ok(PacketDecoder { inner: Inner::Aac(Box::new(decoder)), sample_rate: rate as u32, frames_per_packet: 1024, preroll_packets: 2 })
    }

    /// Forgets everything decoded so far. Call before decoding from a different place in the stream.
    pub fn reset(&mut self) {
        match &mut self.inner {
            Inner::Opus { state, .. } => unsafe {
                opus::opus_decoder_ctl(*state, opus::OPUS_RESET_STATE);
            },
            Inner::Aac(decoder) => decoder.reset(),
        }
    }

    /// Decodes packets that follow on from the previous call, appending the samples to `out`.
    pub fn decode<'a>(&mut self, packets: impl IntoIterator<Item = &'a [u8]>, out: &mut Pcm) -> Result<(), DecodeError> {
        for packet in packets {
            if packet.is_empty() {
                continue;
            }
            match &mut self.inner {
                Inner::Opus { state, scratch } => {
                    let frames = unsafe {
                        opus::opus_decode_float(
                            *state,
                            packet.as_ptr(),
                            packet.len() as i32,
                            scratch.as_mut_ptr(),
                            OPUS_MOST_FRAMES as i32,
                            0,
                        )
                    };
                    if frames < 0 {
                        return Err(DecodeError::Failed(format!("libopus error {frames}")));
                    }
                    let frames = frames as usize;
                    out.left.extend(scratch[..2 * frames].iter().step_by(2));
                    out.right.extend(scratch[1..2 * frames].iter().step_by(2));
                }
                Inner::Aac(decoder) => {
                    let decoded = decoder
                        .decode_ref(&SymphoniaPacket::new(0, Timestamp::ZERO, Duration::new(1024), packet))
                        .map_err(|error| DecodeError::Failed(format!("AAC: {error}")))?;
                    let GenericAudioBufferRef::F32(buffer) = decoded else {
                        return Err(DecodeError::Failed("AAC: unexpected sample format".into()));
                    };
                    let left = buffer.plane(0).unwrap_or(&[]);
                    let right = buffer.plane(1).unwrap_or(left);
                    out.left.extend_from_slice(left);
                    out.right.extend_from_slice(right);
                }
            }
        }
        Ok(())
    }
}

impl Drop for PacketDecoder {
    fn drop(&mut self) {
        if let Inner::Opus { state, .. } = &self.inner {
            unsafe { opus::opus_decoder_destroy(*state) };
        }
    }
}

/// The libopus version linked in, for the record.
pub fn opus_version() -> String {
    unsafe { std::ffi::CStr::from_ptr(opus::opus_get_version_string()) }.to_string_lossy().into_owned()
}

/// Brings decoded audio at another rate to the pipeline's 48 kHz with a band-limited (windowed sinc) resampler,
/// continuously across packets: one song is resampled as one stream, so packet joins leave no trace.
///
/// When the two rates have a small common ground (44.1 kHz to 48 kHz is 147 to 160), every output sample falls exactly
/// on one of the filter's precomputed phases, and the filter is used as an exact polyphase filter rather than
/// interpolated between phases.
pub struct To48k {
    resampler: Async<f32>,
    input: Vec<Vec<f32>>,
    filled: usize,
    output: Vec<Vec<f32>>,
    /// Output frames still to drop: the filter's own delay, so the output lines up with the input.
    to_drop: usize,
    frames_in: u64,
    frames_out: u64,
    ratio: f64,
}

const CHUNK: usize = 1024;

impl To48k {
    pub fn new(input_rate: u32) -> Result<To48k, DecodeError> {
        if input_rate == 0 {
            return Err(DecodeError::Unsupported("a sample rate of 0".into()));
        }
        let ratio = 48_000.0 / input_rate as f64;
        let common = gcd(48_000, input_rate);
        let phases = (48_000 / common) as usize;
        let parameters = if phases <= 1024 {
            SincInterpolationParameters::new(256, WindowFunction::BlackmanHarris2)
                .oversampling_factor(phases)
                .interpolation(SincInterpolationType::Nearest)
        } else {
            SincInterpolationParameters::new(256, WindowFunction::BlackmanHarris2)
                .oversampling_factor(256)
                .interpolation(SincInterpolationType::Cubic)
        };
        let resampler = Async::<f32>::new_sinc(ratio, 1.0, &parameters, CHUNK, 2, FixedAsync::Input)
            .map_err(|error| DecodeError::Unsupported(format!("no resampler from {input_rate} Hz: {error}")))?;
        let most = resampler.output_frames_max();
        let to_drop = resampler.output_delay();
        Ok(To48k {
            resampler,
            input: vec![vec![0.0; CHUNK]; 2],
            filled: 0,
            output: vec![vec![0.0; most]; 2],
            to_drop,
            frames_in: 0,
            frames_out: 0,
            ratio,
        })
    }

    pub fn reset(&mut self) {
        self.resampler.reset();
        self.filled = 0;
        self.to_drop = self.resampler.output_delay();
        self.frames_in = 0;
        self.frames_out = 0;
    }

    /// The most frames `flush` can still put out.
    pub fn tail_bound(&self) -> usize {
        self.resampler.output_frames_max() * 2 + self.resampler.output_delay()
    }

    /// Resamples `input`, appending what is ready to `out`. Up to a chunk plus the filter's delay stays inside until
    /// more comes or `flush`.
    pub fn push(&mut self, input: &Pcm, out: &mut Pcm) -> Result<(), DecodeError> {
        let mut at = 0;
        while at < input.len() {
            let take = (CHUNK - self.filled).min(input.len() - at);
            self.input[0][self.filled..self.filled + take].copy_from_slice(&input.left[at..at + take]);
            self.input[1][self.filled..self.filled + take].copy_from_slice(&input.right[at..at + take]);
            self.filled += take;
            self.frames_in += take as u64;
            at += take;
            if self.filled == CHUNK {
                self.run(None, out)?;
                self.filled = 0;
            }
        }
        Ok(())
    }

    /// The song's end: puts out everything still inside, exactly as many frames as the input's length calls for.
    pub fn flush(&mut self, out: &mut Pcm) -> Result<(), DecodeError> {
        let wanted = (self.frames_in as f64 * self.ratio).round() as u64;
        let mut partial = Some(self.filled);
        let mut rounds = 0;
        while self.frames_out < wanted && rounds < 64 {
            let before = out.len();
            self.run(partial, out)?;
            partial = Some(0);
            let excess = self.frames_out.saturating_sub(wanted) as usize;
            if excess > 0 {
                let keep = out.len().saturating_sub(excess).max(before);
                out.left.truncate(keep);
                out.right.truncate(keep);
                self.frames_out = wanted;
            }
            rounds += 1;
        }
        self.filled = 0;
        Ok(())
    }

    fn run(&mut self, partial: Option<usize>, out: &mut Pcm) -> Result<(), DecodeError> {
        let failed = |error: &dyn fmt::Display| DecodeError::Failed(format!("resampling: {error}"));
        let input = SequentialSliceOfVecs::new(&self.input, 2, CHUNK).map_err(|e| failed(&e))?;
        let most = self.output[0].len();
        let mut output = SequentialSliceOfVecs::new_mut(&mut self.output, 2, most).map_err(|e| failed(&e))?;
        let indexing = partial.map(|frames| Indexing::new().partial_len(frames));
        let (_, produced) =
            self.resampler.process_into_buffer(&input, &mut output, indexing.as_ref()).map_err(|e| failed(&e))?;
        let drop = self.to_drop.min(produced);
        self.to_drop -= drop;
        out.left.extend_from_slice(&self.output[0][drop..produced]);
        out.right.extend_from_slice(&self.output[1][drop..produced]);
        self.frames_out += (produced - drop) as u64;
        Ok(())
    }
}

fn gcd(mut a: u32, mut b: u32) -> u32 {
    while b != 0 {
        (a, b) = (b, a % b);
    }
    a
}
