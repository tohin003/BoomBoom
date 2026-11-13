//! Turns the bytes a web player appends to a media source buffer into audio packets: WebM/Opus and fragmented
//! MP4/AAC, fed in pieces of any size. A port of the iPhone app's `BoomAudio/Demux`.

mod cursor;
pub mod mp4;
pub mod webm;

use std::fmt;

pub use mp4::FragmentedMp4Demuxer;
pub use webm::WebmDemuxer;

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum DemuxError {
    /// A box or element claims more bytes than it has, or a size that makes no sense.
    Corrupt(String),
    /// Media arrived before the stream's header (the init segment).
    MissingHeader,
    /// The stream is something this app does not play (encrypted, or an unknown codec).
    Unsupported(String),
}

impl fmt::Display for DemuxError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            DemuxError::Corrupt(why) => write!(f, "corrupt: {why}"),
            DemuxError::MissingHeader => write!(f, "media before the header"),
            DemuxError::Unsupported(why) => write!(f, "unsupported: {why}"),
        }
    }
}

impl std::error::Error for DemuxError {}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Codec {
    Aac,
    Opus,
}

impl Codec {
    pub fn name(self) -> &'static str {
        match self {
            Codec::Aac => "aac",
            Codec::Opus => "opus",
        }
    }
}

/// What the decoder needs to know about a captured audio stream.
#[derive(Clone, Debug, PartialEq)]
pub struct TrackInfo {
    pub codec: Codec,
    /// Units per second of `Packet::decode_time` and `duration`.
    pub timescale: u32,
    /// The rate the container states. The decoder trusts the codec setup over this.
    pub sample_rate: f64,
    pub channels: u32,
    /// AAC: the MPEG-4 elementary stream descriptor (the `esds` box less its 4-byte header fields).
    /// Opus: the `OpusHead` identification header.
    pub magic_cookie: Vec<u8>,
    /// AAC only: the raw AudioSpecificConfig.
    pub audio_specific_config: Vec<u8>,
    /// Timeline units to drop at the very start of the stream (the encoder's lead-in), from the container.
    pub lead_in: i64,
    /// Packet length in timeline units when the stream states a constant one (1024 for AAC-LC).
    pub default_packet_duration: Option<u32>,
    /// The stream's whole length in seconds, when its header says.
    pub duration: Option<f64>,
}

/// One compressed audio packet and where it sits on the stream's timeline, borrowed from the demuxer's buffer.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PacketRef<'a> {
    /// In `TrackInfo::timescale` units from the start of the stream.
    pub decode_time: i64,
    pub duration: u32,
    pub data: &'a [u8],
}

/// The same, owning its bytes.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Packet {
    pub decode_time: i64,
    pub duration: u32,
    pub data: Vec<u8>,
}

impl PacketRef<'_> {
    pub fn to_owned(&self) -> Packet {
        Packet { decode_time: self.decode_time, duration: self.duration, data: self.data.to_vec() }
    }
}

impl Packet {
    pub fn as_ref(&self) -> PacketRef<'_> {
        PacketRef { decode_time: self.decode_time, duration: self.duration, data: &self.data }
    }
}

/// What feeding produces, in stream order. The packets are borrowed: a sink copies what it keeps.
#[derive(Debug)]
pub enum DemuxEventRef<'a, 'b> {
    /// The stream's header was read. Always comes before the first packets.
    Track(&'a TrackInfo),
    Packets(&'a [PacketRef<'b>]),
}

/// The owned form, for callers that collect.
#[derive(Clone, Debug, PartialEq)]
pub enum DemuxEvent {
    Track(TrackInfo),
    Packets(Vec<Packet>),
}

/// One demuxer per media source buffer. Bytes may arrive in pieces of any size; `reset()` mirrors the buffer's
/// `abort()` (the player jumps elsewhere and starts a new segment).
#[derive(Debug)]
pub enum AudioDemuxer {
    Webm(WebmDemuxer),
    Mp4(FragmentedMp4Demuxer),
}

impl AudioDemuxer {
    /// Picks the demuxer for a source buffer's MIME type (`audio/mp4; codecs="mp4a.40.2"`, `audio/webm; codecs="opus"`).
    pub fn for_mime_type(mime_type: &str) -> Option<AudioDemuxer> {
        let lower = mime_type.trim().to_ascii_lowercase();
        if !lower.starts_with("audio/") {
            return None;
        }
        if lower.starts_with("audio/mp4") {
            return Some(AudioDemuxer::Mp4(FragmentedMp4Demuxer::new()));
        }
        if lower.starts_with("audio/webm") {
            return Some(AudioDemuxer::Webm(WebmDemuxer::new()));
        }
        None
    }

    pub fn track(&self) -> Option<&TrackInfo> {
        match self {
            AudioDemuxer::Webm(d) => d.track(),
            AudioDemuxer::Mp4(d) => d.track(),
        }
    }

    /// Forgets partly received data. The header read so far stays valid.
    pub fn reset(&mut self) {
        match self {
            AudioDemuxer::Webm(d) => d.reset(),
            AudioDemuxer::Mp4(d) => d.reset(),
        }
    }

    /// Adds `len` bytes written by `fill` straight into the demuxer's own buffer (the one copy a JNI caller makes),
    /// then parses. On an error the demuxer starts clean, as after `reset()`.
    pub fn feed_with<F, S>(&mut self, len: usize, fill: F, sink: S) -> Result<(), DemuxError>
    where
        F: FnOnce(&mut [u8]) -> bool,
        S: FnMut(DemuxEventRef<'_, '_>),
    {
        match self {
            AudioDemuxer::Webm(d) => d.feed_with(len, fill, sink),
            AudioDemuxer::Mp4(d) => d.feed_with(len, fill, sink),
        }
    }

    pub fn feed_into<S: FnMut(DemuxEventRef<'_, '_>)>(&mut self, data: &[u8], sink: S) -> Result<(), DemuxError> {
        self.feed_with(
            data.len(),
            |space| {
                space.copy_from_slice(data);
                true
            },
            sink,
        )
    }

    /// Feeds and collects what comes out.
    pub fn feed(&mut self, data: &[u8]) -> Result<Vec<DemuxEvent>, DemuxError> {
        let mut events = Vec::new();
        self.feed_into(data, |event| collect(&mut events, event))?;
        Ok(events)
    }
}

pub(crate) fn collect(events: &mut Vec<DemuxEvent>, event: DemuxEventRef<'_, '_>) {
    match event {
        DemuxEventRef::Track(info) => events.push(DemuxEvent::Track(info.clone())),
        DemuxEventRef::Packets(packets) => {
            events.push(DemuxEvent::Packets(packets.iter().map(PacketRef::to_owned).collect()))
        }
    }
}

/// Appends `len` bytes to `pending` by letting `fill` write them in place. False when `fill` failed (nothing added).
pub(crate) fn append_in_place<F: FnOnce(&mut [u8]) -> bool>(pending: &mut Vec<u8>, len: usize, fill: F) -> bool {
    let start = pending.len();
    pending.resize(start + len, 0);
    if fill(&mut pending[start..]) {
        true
    } else {
        pending.truncate(start);
        false
    }
}
