//! BoomBoom's audio engine for Android, a port of the iPhone app's `BoomAudio`: stream parsing (WebM/Opus,
//! fragmented MP4/AAC), the packet store, decoding to 48 kHz stereo float, the playback pipeline, the sound processor
//! shared with the iPhone app (C, compiled in), AAudio output and the JNI surface (`API.md`).

#[cfg(target_os = "android")]
pub mod aaudio;
pub mod decode;
pub mod demux;
pub mod engine;
pub mod ffi;
pub mod jni_api;
pub mod output;
pub mod pipeline;
pub mod track;
