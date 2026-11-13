//! The JNI surface: the native side of `io.github.boomboom.engine.BoomCore` (Kotlin). `API.md` describes it.
//!
//! Handles are pointers handed to Kotlin as `long`s: a track is an `Arc<TrackBuffer>` (the pipeline holds its own
//! references, so releasing a track that is playing is safe), a demuxer a boxed `Mutex`. The Kotlin wrappers own
//! them and never pass one after releasing it. Every function catches panics and turns them into a Java
//! `RuntimeException`, so nothing unwinds into the Java VM.

use std::path::Path;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use jni::errors::{Result as JniResult, ThrowRuntimeExAndDefault};
use jni::objects::{JByteArray, JClass, JDoubleArray, JFloatArray, JLongArray, JString};
use jni::sys::{jboolean, jdouble, jfloat, jint, jlong};
use jni::{Env, EnvUnowned};

use crate::demux::{AudioDemuxer, Codec, DemuxError, DemuxEventRef};
use crate::engine::engine;
use crate::ffi::{self, SoundParams, BOOM_DSP_BANDS};
use crate::pipeline::{FailReason, PipelineEvent};
use crate::track::TrackBuffer;

fn run<'local, T: Default>(
    env: &mut EnvUnowned<'local>,
    work: impl FnOnce(&mut Env<'local>) -> JniResult<T>,
) -> T {
    env.with_env(work).resolve::<ThrowRuntimeExAndDefault>()
}

// Handles

fn track_ref<'a>(handle: jlong) -> Option<&'a TrackBuffer> {
    (handle != 0).then(|| unsafe { &*(handle as *const TrackBuffer) })
}

fn track_arc(handle: jlong) -> Option<Arc<TrackBuffer>> {
    (handle != 0).then(|| unsafe {
        Arc::increment_strong_count(handle as *const TrackBuffer);
        Arc::from_raw(handle as *const TrackBuffer)
    })
}

fn track_handle(buffer: TrackBuffer) -> jlong {
    Arc::into_raw(Arc::new(buffer)) as jlong
}

struct DemuxerBox {
    demuxer: AudioDemuxer,
    last_error: String,
}

fn demuxer_ref<'a>(handle: jlong) -> Option<&'a Mutex<DemuxerBox>> {
    (handle != 0).then(|| unsafe { &*(handle as *const Mutex<DemuxerBox>) })
}

fn double_array<'local>(env: &mut Env<'local>, values: &[f64]) -> JniResult<JDoubleArray<'local>> {
    let array = JDoubleArray::new(env, values.len())?;
    array.set_region(env, 0, values)?;
    Ok(array)
}

fn float_array<'local>(env: &mut Env<'local>, values: &[f32]) -> JniResult<JFloatArray<'local>> {
    let array = JFloatArray::new(env, values.len())?;
    array.set_region(env, 0, values)?;
    Ok(array)
}

fn long_array<'local>(env: &mut Env<'local>, values: &[i64]) -> JniResult<JLongArray<'local>> {
    let array = JLongArray::new(env, values.len())?;
    array.set_region(env, 0, values)?;
    Ok(array)
}

fn read_doubles(env: &Env<'_>, array: &JDoubleArray<'_>) -> JniResult<Vec<f64>> {
    let mut values = vec![0.0; array.len(env)?];
    array.get_region(env, 0, &mut values)?;
    Ok(values)
}

/// `SoundParams` from the flat form Kotlin sends (see `API.md`): enabled, ten bands, preamp, headroom, bass, treble,
/// boost, limiter, balance, width, deep bass, air, crossfeed, spatial.
pub const SOUND_PARAMS_LENGTH: usize = 1 + BOOM_DSP_BANDS + 12;

fn read_sound_params(env: &Env<'_>, array: &JFloatArray<'_>) -> JniResult<SoundParams> {
    let mut values = [0.0f32; SOUND_PARAMS_LENGTH];
    if array.len(env)? != SOUND_PARAMS_LENGTH {
        return Err(jni::errors::Error::JniCall(jni::errors::JniError::InvalidArguments));
    }
    array.get_region(env, 0, &mut values)?;
    Ok(sound_params_from(&values))
}

pub fn sound_params_from(values: &[f32; SOUND_PARAMS_LENGTH]) -> SoundParams {
    let at = 1 + BOOM_DSP_BANDS;
    SoundParams {
        enabled: values[0] != 0.0,
        band_gain_db: values[1..at].try_into().unwrap(),
        preamp_db: values[at],
        headroom_db: values[at + 1],
        bass_db: values[at + 2],
        treble_db: values[at + 3],
        boost_db: values[at + 4],
        limiter: values[at + 5] != 0.0,
        balance: values[at + 6],
        width: values[at + 7],
        deep_bass: values[at + 8],
        air: values[at + 9],
        crossfeed: values[at + 10],
        spatial: values[at + 11],
    }
}

// Tracks

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_trackCreate<'l>(mut env: EnvUnowned<'l>, _: JClass<'l>) -> jlong {
    run(&mut env, |_| Ok(track_handle(TrackBuffer::new())))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_trackRelease<'l>(mut env: EnvUnowned<'l>, _: JClass<'l>, track: jlong) {
    run(&mut env, |_| {
        if track != 0 {
            unsafe { drop(Arc::from_raw(track as *const TrackBuffer)) };
        }
        Ok(())
    })
}

/// [codec (0 AAC, 1 Opus), timescale, sample rate, channels, lead-in, stated packet duration (0: none), duration in
/// seconds (NaN: unknown), packet duration (0 until the first packet), expected packet count (-1: unknown)], or null
/// before the header has arrived.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_trackInfo<'l>(
    mut env: EnvUnowned<'l>,
    _: JClass<'l>,
    track: jlong,
) -> JDoubleArray<'l> {
    run(&mut env, |env| {
        let Some(buffer) = track_ref(track) else { return Ok(JDoubleArray::default()) };
        let Some(info) = buffer.info() else { return Ok(JDoubleArray::default()) };
        let values = [
            match info.codec {
                Codec::Aac => 0.0,
                Codec::Opus => 1.0,
            },
            info.timescale as f64,
            info.sample_rate,
            info.channels as f64,
            info.lead_in as f64,
            info.default_packet_duration.unwrap_or(0) as f64,
            buffer.duration().unwrap_or(f64::NAN),
            buffer.packet_duration() as f64,
            buffer.expected_packet_count().map(|c| c as f64).unwrap_or(-1.0),
        ];
        double_array(env, &values)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_trackHeldPackets<'l>(mut env: EnvUnowned<'l>, _: JClass<'l>, track: jlong) -> jint {
    run(&mut env, |_| Ok(track_ref(track).map(|b| b.held_packet_count() as jint).unwrap_or(0)))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_trackHeldBytes<'l>(mut env: EnvUnowned<'l>, _: JClass<'l>, track: jlong) -> jlong {
    run(&mut env, |_| Ok(track_ref(track).map(|b| b.held_bytes() as jlong).unwrap_or(0)))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_trackIsComplete<'l>(mut env: EnvUnowned<'l>, _: JClass<'l>, track: jlong) -> jboolean {
    run(&mut env, |_| Ok(track_ref(track).is_some_and(|b| b.is_complete())))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_trackCoveredFraction<'l>(mut env: EnvUnowned<'l>, _: JClass<'l>, track: jlong) -> jdouble {
    run(&mut env, |_| Ok(track_ref(track).map(|b| b.covered_fraction()).unwrap_or(0.0)))
}

/// The first missing packet at or after `index`, wrapping round; -1 when nothing is missing.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_trackFirstGap<'l>(mut env: EnvUnowned<'l>, _: JClass<'l>, track: jlong, index: jint) -> jint {
    run(&mut env, |_| {
        Ok(track_ref(track).and_then(|b| b.first_gap(index.max(0) as usize)).map(|g| g as jint).unwrap_or(-1))
    })
}

/// Where the first missing stretch at or after `seconds` starts, in seconds, wrapping round; -1 when nothing is
/// missing. The capture broker steers the page's playhead there.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_trackFirstGapAt<'l>(mut env: EnvUnowned<'l>, _: JClass<'l>, track: jlong, seconds: jdouble) -> jdouble {
    run(&mut env, |_| {
        let Some(buffer) = track_ref(track) else { return Ok(-1.0) };
        Ok(buffer.first_gap(buffer.packet_index_at(seconds)).map(|g| buffer.time_of_packet(g)).unwrap_or(-1.0))
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_trackContiguousEnd<'l>(mut env: EnvUnowned<'l>, _: JClass<'l>, track: jlong, index: jint) -> jint {
    run(&mut env, |_| {
        Ok(track_ref(track).map(|b| b.contiguous_end(index.max(0) as usize).min(i32::MAX as usize) as jint).unwrap_or(0))
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_trackHasReachedEnd<'l>(mut env: EnvUnowned<'l>, _: JClass<'l>, track: jlong, index: jint) -> jboolean {
    run(&mut env, |_| Ok(track_ref(track).is_some_and(|b| b.has_reached_end(index.max(0) as usize))))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_trackPacketIndexAt<'l>(mut env: EnvUnowned<'l>, _: JClass<'l>, track: jlong, seconds: jdouble) -> jint {
    run(&mut env, |_| Ok(track_ref(track).map(|b| b.packet_index_at(seconds).min(i32::MAX as usize) as jint).unwrap_or(0)))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_trackTimeOfPacket<'l>(mut env: EnvUnowned<'l>, _: JClass<'l>, track: jlong, index: jint) -> jdouble {
    run(&mut env, |_| Ok(track_ref(track).map(|b| b.time_of_packet(index.max(0) as usize)).unwrap_or(0.0)))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_trackSeal<'l>(mut env: EnvUnowned<'l>, _: JClass<'l>, track: jlong) {
    run(&mut env, |_| {
        if let Some(buffer) = track_ref(track) {
            buffer.seal();
        }
        Ok(())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_trackSetDuration<'l>(mut env: EnvUnowned<'l>, _: JClass<'l>, track: jlong, seconds: jdouble) {
    run(&mut env, |_| {
        if let Some(buffer) = track_ref(track) {
            buffer.set_duration(seconds);
        }
        Ok(())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_trackWrite<'l>(
    mut env: EnvUnowned<'l>,
    _: JClass<'l>,
    track: jlong,
    path: JString<'l>,
) -> jboolean {
    run(&mut env, |env| {
        let path = path.try_to_string(env)?;
        Ok(track_ref(track).is_some_and(|b| b.write_to(Path::new(&path))))
    })
}

/// A new track holding a song read from a file written by `trackWrite`; 0 when the file is not one.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_trackLoad<'l>(mut env: EnvUnowned<'l>, _: JClass<'l>, path: JString<'l>) -> jlong {
    run(&mut env, |env| {
        let path = path.try_to_string(env)?;
        Ok(TrackBuffer::read_from(Path::new(&path)).map(track_handle).unwrap_or(0))
    })
}

// Demuxers

/// A demuxer for a source buffer's MIME type; 0 when it is not a type this app plays.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_demuxerCreate<'l>(mut env: EnvUnowned<'l>, _: JClass<'l>, mime: JString<'l>) -> jlong {
    run(&mut env, |env| {
        let mime = mime.try_to_string(env)?;
        Ok(AudioDemuxer::for_mime_type(&mime)
            .map(|demuxer| Box::into_raw(Box::new(Mutex::new(DemuxerBox { demuxer, last_error: String::new() }))) as jlong)
            .unwrap_or(0))
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_demuxerRelease<'l>(mut env: EnvUnowned<'l>, _: JClass<'l>, demuxer: jlong) {
    run(&mut env, |_| {
        if demuxer != 0 {
            unsafe { drop(Box::from_raw(demuxer as *mut Mutex<DemuxerBox>)) };
        }
        Ok(())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_demuxerReset<'l>(mut env: EnvUnowned<'l>, _: JClass<'l>, demuxer: jlong) {
    run(&mut env, |_| {
        if let Some(demuxer) = demuxer_ref(demuxer) {
            demuxer.lock().unwrap_or_else(|p| p.into_inner()).demuxer.reset();
        }
        Ok(())
    })
}

pub const FEED_CORRUPT: jint = -1;
pub const FEED_MISSING_HEADER: jint = -2;
pub const FEED_UNSUPPORTED: jint = -3;
pub const FEED_BAD_ARGUMENTS: jint = -4;

/// Feeds `length` bytes of `bytes` from `offset` into the demuxer, adding what comes out to the track. The bytes are
/// copied once, straight from the Java array into the demuxer's own buffer. Returns how many new packets the track
/// took, or a negative code (`FEED_*`) with the reason in `demuxerLastError`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_demuxerFeed<'l>(
    mut env: EnvUnowned<'l>,
    _: JClass<'l>,
    demuxer: jlong,
    track: jlong,
    bytes: JByteArray<'l>,
    offset: jint,
    length: jint,
) -> jint {
    run(&mut env, |env| {
        let (Some(demuxer), Some(buffer)) = (demuxer_ref(demuxer), track_ref(track)) else { return Ok(FEED_BAD_ARGUMENTS) };
        let total = bytes.len(env)?;
        if offset < 0 || length < 0 || offset as usize + length as usize > total {
            return Ok(FEED_BAD_ARGUMENTS);
        }
        let mut guard = demuxer.lock().unwrap_or_else(|p| p.into_inner());
        let mut added = 0usize;
        let mut copy_failed = false;
        let result = guard.demuxer.feed_with(
            length as usize,
            |space| {
                // The one copy: the Java array straight into the demuxer's buffer.
                let space = unsafe { std::slice::from_raw_parts_mut(space.as_mut_ptr() as *mut i8, space.len()) };
                let copied = bytes.get_region(env, offset, space).is_ok();
                copy_failed = !copied;
                copied
            },
            |event| match event {
                DemuxEventRef::Track(info) => buffer.set_track(info, None),
                DemuxEventRef::Packets(packets) => added += buffer.append(packets),
            },
        );
        if copy_failed {
            return Err(jni::errors::Error::JavaException);
        }
        Ok(match result {
            Ok(()) => added.min(i32::MAX as usize) as jint,
            Err(error) => {
                guard.last_error = error.to_string();
                match error {
                    DemuxError::Corrupt(_) => FEED_CORRUPT,
                    DemuxError::MissingHeader => FEED_MISSING_HEADER,
                    DemuxError::Unsupported(_) => FEED_UNSUPPORTED,
                }
            }
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_demuxerLastError<'l>(mut env: EnvUnowned<'l>, _: JClass<'l>, demuxer: jlong) -> JString<'l> {
    run(&mut env, |env| {
        let text = demuxer_ref(demuxer).map(|d| d.lock().unwrap_or_else(|p| p.into_inner()).last_error.clone()).unwrap_or_default();
        JString::from_str(env, text)
    })
}

// The engine

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_engineStartOutput<'l>(mut env: EnvUnowned<'l>, _: JClass<'l>) -> jboolean {
    run(&mut env, |_| Ok(engine().start_output()))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_engineStopOutput<'l>(mut env: EnvUnowned<'l>, _: JClass<'l>) {
    run(&mut env, |_| {
        engine().stop_output();
        Ok(())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_engineLoad<'l>(
    mut env: EnvUnowned<'l>,
    _: JClass<'l>,
    track: jlong,
    item: jlong,
    seconds: jdouble,
    autoplay: jboolean,
    gain: jfloat,
) {
    run(&mut env, |_| {
        if let Some(buffer) = track_arc(track) {
            engine().load(buffer, item as u64, seconds, autoplay, gain);
        }
        Ok(())
    })
}

/// The song to follow the current one without a gap; `track` 0: nothing follows.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_engineSetNext<'l>(
    mut env: EnvUnowned<'l>,
    _: JClass<'l>,
    track: jlong,
    item: jlong,
    gain: jfloat,
) {
    run(&mut env, |_| {
        engine().set_next(track_arc(track).map(|buffer| (buffer, item as u64, gain)));
        Ok(())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_enginePlay<'l>(mut env: EnvUnowned<'l>, _: JClass<'l>) {
    run(&mut env, |_| {
        engine().pipeline.play();
        Ok(())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_enginePause<'l>(mut env: EnvUnowned<'l>, _: JClass<'l>) {
    run(&mut env, |_| {
        engine().pipeline.pause();
        Ok(())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_engineSeek<'l>(mut env: EnvUnowned<'l>, _: JClass<'l>, seconds: jdouble) {
    run(&mut env, |_| {
        engine().pipeline.seek(seconds);
        Ok(())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_engineStop<'l>(mut env: EnvUnowned<'l>, _: JClass<'l>) {
    run(&mut env, |_| {
        engine().pipeline.stop();
        Ok(())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_engineSetItemGain<'l>(mut env: EnvUnowned<'l>, _: JClass<'l>, item: jlong, gain: jfloat) {
    run(&mut env, |_| {
        engine().pipeline.set_item_gain(item as u64, gain);
        Ok(())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_engineSetLevel<'l>(mut env: EnvUnowned<'l>, _: JClass<'l>, gain: jfloat) {
    run(&mut env, |_| {
        engine().pipeline.set_gain(gain);
        Ok(())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_enginePosition<'l>(mut env: EnvUnowned<'l>, _: JClass<'l>) -> jdouble {
    run(&mut env, |_| Ok(engine().position()))
}

/// The song being heard; 0 when none.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_engineCurrentItem<'l>(mut env: EnvUnowned<'l>, _: JClass<'l>) -> jlong {
    run(&mut env, |_| Ok(engine().pipeline.current_item_id().map(|id| id as jlong).unwrap_or(0)))
}

/// NaN while unknown.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_engineDuration<'l>(mut env: EnvUnowned<'l>, _: JClass<'l>) -> jdouble {
    run(&mut env, |_| Ok(engine().duration().unwrap_or(f64::NAN)))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_engineBufferedFraction<'l>(mut env: EnvUnowned<'l>, _: JClass<'l>) -> jdouble {
    run(&mut env, |_| Ok(engine().buffered_fraction()))
}

/// [running, sample rate, the device's own rate, resampled (0/1), channels, frames per burst, buffer frames, xruns,
/// callbacks, frames pulled, frames for which the pipeline had audio (not paused or waiting), reopens after route changes].
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_engineOutputInfo<'l>(mut env: EnvUnowned<'l>, _: JClass<'l>) -> JLongArray<'l> {
    run(&mut env, |env| {
        let i = engine().output_info();
        let values = [
            i.running as i64,
            i.sample_rate as i64,
            i.native_rate as i64,
            i.resampled as i64,
            i.channels as i64,
            i.frames_per_burst as i64,
            i.buffer_frames as i64,
            i.xruns as i64,
            i.callbacks as i64,
            i.frames as i64,
            i.sounding_frames as i64,
            i.reopens as i64,
        ];
        long_array(env, &values)
    })
}

pub const EVENT_PLAYING: i64 = 1;
pub const EVENT_ADVANCED: i64 = 2;
pub const EVENT_WAITING: i64 = 3;
pub const EVENT_ENDED: i64 = 4;
pub const EVENT_FAILED_UNSUPPORTED: i64 = 5;
pub const EVENT_FAILED_UNDECODABLE: i64 = 6;

pub fn encode_event(event: PipelineEvent) -> [i64; 2] {
    match event {
        PipelineEvent::Playing(id) => [EVENT_PLAYING, id as i64],
        PipelineEvent::Advanced(id) => [EVENT_ADVANCED, id as i64],
        PipelineEvent::Waiting(id) => [EVENT_WAITING, id as i64],
        PipelineEvent::Ended(id) => [EVENT_ENDED, id as i64],
        PipelineEvent::Failed(id, FailReason::Unsupported) => [EVENT_FAILED_UNSUPPORTED, id as i64],
        PipelineEvent::Failed(id, FailReason::Undecodable) => [EVENT_FAILED_UNDECODABLE, id as i64],
    }
}

/// The pipeline's events since the last call, as pairs [kind, item, kind, item, ...], waiting up to `timeoutMs` for
/// the first when there are none (an empty array then).
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_enginePollEvents<'l>(mut env: EnvUnowned<'l>, _: JClass<'l>, timeout_ms: jint) -> JLongArray<'l> {
    run(&mut env, |env| {
        let events = engine().poll_events(Duration::from_millis(timeout_ms.max(0) as u64));
        let flat: Vec<i64> = events.into_iter().flat_map(encode_event).collect();
        long_array(env, &flat)
    })
}

// Sound

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_soundApply<'l>(mut env: EnvUnowned<'l>, _: JClass<'l>, params: JFloatArray<'l>) {
    run(&mut env, |env| {
        let params = read_sound_params(env, &params)?;
        engine().apply_sound(&params);
        Ok(())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_soundSetHeadTurn<'l>(mut env: EnvUnowned<'l>, _: JClass<'l>, radians: jfloat) {
    run(&mut env, |_| {
        engine().set_head_turn(radians);
        Ok(())
    })
}

/// [peak left, peak right, rms left, rms right, gain reduction in dB] since the last call.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_soundTakeLevels<'l>(mut env: EnvUnowned<'l>, _: JClass<'l>) -> JFloatArray<'l> {
    run(&mut env, |env| {
        let l = engine().take_levels();
        float_array(env, &[l.peak_left, l.peak_right, l.rms_left, l.rms_right, l.gain_reduction_db])
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_soundSpectrum<'l>(
    mut env: EnvUnowned<'l>,
    _: JClass<'l>,
    bands: jint,
    low_hz: jdouble,
    high_hz: jdouble,
) -> JFloatArray<'l> {
    run(&mut env, |env| {
        let values = engine().spectrum(bands.clamp(0, 4096) as usize, low_hz, high_hz);
        float_array(env, &values)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_soundResponse<'l>(
    mut env: EnvUnowned<'l>,
    _: JClass<'l>,
    params: JFloatArray<'l>,
    frequencies: JDoubleArray<'l>,
    sample_rate: jdouble,
) -> JDoubleArray<'l> {
    run(&mut env, |env| {
        let params = read_sound_params(env, &params)?;
        let frequencies = read_doubles(env, &frequencies)?;
        let values = ffi::response(&params, sample_rate, &frequencies);
        double_array(env, &values)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_soundPeakResponse<'l>(
    mut env: EnvUnowned<'l>,
    _: JClass<'l>,
    params: JFloatArray<'l>,
    sample_rate: jdouble,
) -> jdouble {
    run(&mut env, |env| Ok(ffi::peak_response(&read_sound_params(env, &params)?, sample_rate)))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_soundBandFrequencies<'l>(mut env: EnvUnowned<'l>, _: JClass<'l>) -> JDoubleArray<'l> {
    run(&mut env, |env| double_array(env, &ffi::band_frequencies()))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_boomboom_engine_BoomCore_soundLatency<'l>(mut env: EnvUnowned<'l>, _: JClass<'l>) -> jint {
    run(&mut env, |_| Ok(engine().processing_latency() as jint))
}
