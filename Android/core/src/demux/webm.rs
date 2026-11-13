//! WebM (Matroska) Opus audio, the form YouTube's best audio streams take: a header (`EBML`, `Segment` with `Info`
//! and `Tracks`) followed by `Cluster`s of `SimpleBlock`s, one Opus packet per block.
//!
//! The player may append a cluster in several pieces, and after a jump it starts again at a cluster boundary, so the
//! parser walks elements flat: it steps into `Segment` and `Cluster` as soon as their headers are in, and handles
//! every other element once it has arrived whole.

use super::cursor::ByteCursor;
use super::{append_in_place, Codec, DemuxError, DemuxEventRef, PacketRef, TrackInfo};

mod id {
    pub const SEGMENT: u32 = 0x1853_8067;
    pub const INFO: u32 = 0x1549_A966;
    pub const TIMESTAMP_SCALE: u32 = 0x2A_D7B1;
    pub const DURATION: u32 = 0x4489;
    pub const TRACKS: u32 = 0x1654_AE6B;
    pub const TRACK_ENTRY: u32 = 0xAE;
    pub const TRACK_NUMBER: u32 = 0xD7;
    pub const TRACK_TYPE: u32 = 0x83;
    pub const CODEC_ID: u32 = 0x86;
    pub const CODEC_PRIVATE: u32 = 0x63A2;
    pub const CODEC_DELAY: u32 = 0x56AA;
    pub const AUDIO: u32 = 0xE1;
    pub const CHANNELS: u32 = 0x9F;
    pub const CLUSTER: u32 = 0x1F43_B675;
    pub const TIMESTAMP: u32 = 0xE7;
    pub const SIMPLE_BLOCK: u32 = 0xA3;
    pub const BLOCK_GROUP: u32 = 0xA0;
    pub const BLOCK: u32 = 0xA1;
}

enum ElementSize {
    Known(usize),
    Unknown,
}

/// Nothing but a cluster comes near this, and clusters are stepped into, not held whole.
const LARGEST_ELEMENT: usize = 64 << 20;
/// Opus always decodes at 48 kHz; packet times are counted in its samples.
const TIMESCALE: u32 = 48_000;

#[derive(Debug)]
pub struct WebmDemuxer {
    track: Option<TrackInfo>,
    pending: Vec<u8>,
    /// Nanoseconds per timestamp tick (`TimestampScale`).
    timestamp_scale: u64,
    /// The stream's length in ticks (`Info.Duration`).
    duration_ticks: Option<f64>,
    track_number: Option<u64>,
    /// The current cluster's timestamp in ticks. None until a cluster's `Timestamp` has been read.
    cluster_time: Option<i64>,
}

impl Default for WebmDemuxer {
    fn default() -> Self {
        Self::new()
    }
}

impl WebmDemuxer {
    pub fn new() -> Self {
        WebmDemuxer {
            track: None,
            pending: Vec::new(),
            timestamp_scale: 1_000_000,
            duration_ticks: None,
            track_number: None,
            cluster_time: None,
        }
    }

    pub fn track(&self) -> Option<&TrackInfo> {
        self.track.as_ref()
    }

    pub fn reset(&mut self) {
        self.pending.clear();
        self.cluster_time = None;
    }

    pub fn feed_with<F, S>(&mut self, len: usize, fill: F, mut sink: S) -> Result<(), DemuxError>
    where
        F: FnOnce(&mut [u8]) -> bool,
        S: FnMut(DemuxEventRef<'_, '_>),
    {
        let mut pending = std::mem::take(&mut self.pending);
        if !append_in_place(&mut pending, len, fill) {
            self.pending = pending;
            return Err(DemuxError::Corrupt("the bytes could not be read".into()));
        }
        let result = self.parse(&pending, &mut sink);
        self.pending = pending;
        match result {
            Ok(consumed) => {
                self.pending.drain(..consumed);
                Ok(())
            }
            Err(error) => {
                self.reset();
                Err(error)
            }
        }
    }

    /// Parses what is pending; returns how many bytes were used up.
    fn parse<S: FnMut(DemuxEventRef<'_, '_>)>(&mut self, raw: &[u8], sink: &mut S) -> Result<usize, DemuxError> {
        let mut packets: Vec<PacketRef<'_>> = Vec::new();
        let mut consumed = 0;
        loop {
            let mut cursor = ByteCursor::at(raw, consumed, raw.len());
            let Some(element) = read_id(&mut cursor)? else { break };
            let Some(size) = read_size(&mut cursor)? else { break };
            let header_length = cursor.offset - consumed;

            if element == id::SEGMENT || element == id::CLUSTER {
                // Step inside: the children follow as if they were at the top level.
                if element == id::CLUSTER {
                    self.cluster_time = None;
                }
                consumed += header_length;
                continue;
            }
            let ElementSize::Known(length) = size else {
                return Err(DemuxError::Corrupt(format!("element {element:x} of unknown size")));
            };
            if length > LARGEST_ELEMENT {
                return Err(DemuxError::Corrupt(format!("element {element:x} claims {length} bytes")));
            }
            if cursor.remaining() < length {
                break;
            }
            let mut body = cursor.sub(length)?;

            match element {
                id::INFO => self.parse_info(&mut body)?,
                id::TRACKS => {
                    let info = self.parse_tracks(&mut body)?;
                    self.track = Some(info);
                    sink(DemuxEventRef::Track(self.track.as_ref().unwrap()));
                }
                id::TIMESTAMP => self.cluster_time = Some(read_unsigned(&mut body)?.min(i64::MAX as u64) as i64),
                id::SIMPLE_BLOCK => self.read_block(&mut body, &mut packets)?,
                id::BLOCK_GROUP => {
                    for_each_element(&mut body, |child_id, child| {
                        if child_id == id::BLOCK {
                            self.read_block(child, &mut packets)?;
                        }
                        Ok(())
                    })?;
                }
                _ => {}
            }
            consumed += header_length + length;
        }
        if !packets.is_empty() {
            sink(DemuxEventRef::Packets(&packets));
        }
        Ok(consumed)
    }

    // Header

    fn parse_info(&mut self, info: &mut ByteCursor<'_>) -> Result<(), DemuxError> {
        for_each_element(info, |element, body| {
            match element {
                id::TIMESTAMP_SCALE => {
                    let scale = read_unsigned(body)?;
                    if scale > 0 {
                        self.timestamp_scale = scale;
                    }
                }
                id::DURATION => self.duration_ticks = Some(read_float(body)?),
                _ => {}
            }
            Ok(())
        })
    }

    fn parse_tracks(&mut self, tracks: &mut ByteCursor<'_>) -> Result<TrackInfo, DemuxError> {
        let mut found: Option<(TrackInfo, u64)> = None;
        let mut codecs_seen: Vec<String> = Vec::new();
        let duration =
            self.duration_ticks.map(|ticks| ticks * self.timestamp_scale as f64 / 1_000_000_000.0);
        for_each_element(tracks, |element, entry| {
            if element != id::TRACK_ENTRY || found.is_some() {
                return Ok(());
            }
            let mut number: u64 = 0;
            let mut kind: u64 = 0;
            let mut codec = String::new();
            let mut head: &[u8] = &[];
            let mut codec_delay: Option<u64> = None;
            let mut channels: u32 = 2;
            for_each_element(entry, |element, body| {
                match element {
                    id::TRACK_NUMBER => number = read_unsigned(body)?,
                    id::TRACK_TYPE => kind = read_unsigned(body)?,
                    id::CODEC_ID => codec = String::from_utf8_lossy(body.rest()).into_owned(),
                    id::CODEC_PRIVATE => head = body.rest(),
                    id::CODEC_DELAY => codec_delay = Some(read_unsigned(body)?),
                    id::AUDIO => for_each_element(body, |element, audio| {
                        if element == id::CHANNELS {
                            channels = read_unsigned(audio)?.min(u32::MAX as u64) as u32;
                        }
                        Ok(())
                    })?,
                    _ => {}
                }
                Ok(())
            })?;
            if kind != 2 {
                return Ok(());
            }
            codecs_seen.push(codec.clone());
            // "OpusHead", version, channel count, pre-skip (little-endian), ...
            if codec != "A_OPUS" || head.len() < 19 || !head.starts_with(b"OpusHead") {
                return Ok(());
            }
            let pre_skip = head[10] as i64 | (head[11] as i64) << 8;
            let lead_in = codec_delay
                .map(|delay| ((delay as u128 * TIMESCALE as u128 + 500_000_000) / 1_000_000_000) as i64)
                .unwrap_or(pre_skip);
            found = Some((
                TrackInfo {
                    codec: Codec::Opus,
                    timescale: TIMESCALE,
                    sample_rate: TIMESCALE as f64,
                    channels: if head[9] > 0 { head[9] as u32 } else { channels },
                    magic_cookie: head.to_vec(),
                    audio_specific_config: Vec::new(),
                    lead_in,
                    default_packet_duration: None,
                    duration,
                },
                number,
            ));
            Ok(())
        })?;
        match found {
            Some((info, number)) => {
                self.track_number = Some(number);
                Ok(info)
            }
            None => Err(DemuxError::Unsupported(format!("no Opus track (found: {})", codecs_seen.join(", ")))),
        }
    }

    // Blocks

    fn read_block<'p>(&self, body: &mut ByteCursor<'p>, packets: &mut Vec<PacketRef<'p>>) -> Result<(), DemuxError> {
        let Some(number) = read_vint(body)? else {
            return Err(DemuxError::Corrupt("a block without a track".into()));
        };
        let relative = body.u16()? as i16;
        let flags = body.u8()?;
        let Some(track_number) = self.track_number else { return Err(DemuxError::MissingHeader) };
        let Some(cluster_time) = self.cluster_time else { return Ok(()) };
        if number != track_number {
            return Ok(());
        }
        if flags & 0x06 != 0 {
            return Err(DemuxError::Unsupported("laced WebM blocks".into()));
        }
        let payload = body.rest();
        let Some(&first) = payload.first() else { return Ok(()) };

        let samples = opus_packet_samples(first, payload.get(1).copied().unwrap_or(0));
        let ticks = cluster_time + relative as i64;
        // Ticks to 48 kHz samples. With the usual millisecond ticks this is exact to 48 samples only, so the time is
        // snapped to a whole number of packets: YouTube stamps packet k at about 20k + 1 ms.
        let nanos = ticks as f64 * self.timestamp_scale as f64;
        let raw = (nanos * TIMESCALE as f64 / 1_000_000_000.0).round() as i64;
        let length = samples as i64;
        let time = ((raw + length / 4) / length).max(0) * length;
        packets.push(PacketRef { decode_time: time, duration: samples, data: payload });
        Ok(())
    }
}

/// How many 48 kHz samples an Opus packet decodes to, from its first byte (RFC 6716, section 3.1).
pub fn opus_packet_samples(toc: u8, second: u8) -> u32 {
    let config = (toc >> 3) as usize;
    let frame = match config {
        0..=11 => [480, 960, 1920, 2880][config % 4],
        12..=15 => [480, 960][config % 2],
        _ => [120, 240, 480, 960][config % 4],
    };
    let count = match toc & 0x03 {
        0 => 1,
        1 | 2 => 2,
        _ => ((second & 0x3F) as u32).max(1),
    };
    frame * count
}

// EBML

fn for_each_element<'a, F>(parent: &mut ByteCursor<'a>, mut visit: F) -> Result<(), DemuxError>
where
    F: FnMut(u32, &mut ByteCursor<'a>) -> Result<(), DemuxError>,
{
    while !parent.is_at_end() {
        let (Some(element), Some(size)) = (read_id(parent)?, read_size(parent)?) else {
            return Err(DemuxError::Corrupt("a cut-off element".into()));
        };
        let length = match size {
            ElementSize::Known(length) if length <= parent.remaining() => length,
            _ => return Err(DemuxError::Corrupt(format!("element {element:x} overruns its parent"))),
        };
        let mut body = parent.sub(length)?;
        visit(element, &mut body)?;
    }
    Ok(())
}

/// An element id: 1 to 4 bytes, the length marker kept. None when more bytes are needed.
fn read_id(cursor: &mut ByteCursor<'_>) -> Result<Option<u32>, DemuxError> {
    if cursor.remaining() < 1 {
        return Ok(None);
    }
    let first = cursor.clone().u8()?;
    let length = first.leading_zeros() as usize + 1;
    if length > 4 {
        return Err(DemuxError::Corrupt(format!("not an element id: {first}")));
    }
    if cursor.remaining() < length {
        return Ok(None);
    }
    let mut value: u32 = 0;
    for _ in 0..length {
        value = (value << 8) | cursor.u8()? as u32;
    }
    Ok(Some(value))
}

/// An element size: 1 to 8 bytes, the length marker removed. None when more bytes are needed.
fn read_size(cursor: &mut ByteCursor<'_>) -> Result<Option<ElementSize>, DemuxError> {
    if cursor.remaining() < 1 {
        return Ok(None);
    }
    let first = cursor.clone().u8()?;
    let length = first.leading_zeros() as usize + 1;
    if length > 8 {
        return Err(DemuxError::Corrupt("not an element size".into()));
    }
    if cursor.remaining() < length {
        return Ok(None);
    }
    let mut value = (cursor.u8()? as u64) & (0xFFu64 >> length);
    for _ in 1..length {
        value = (value << 8) | cursor.u8()? as u64;
    }
    let all_ones = (1u64 << (7 * length)) - 1;
    Ok(Some(if value == all_ones {
        ElementSize::Unknown
    } else {
        ElementSize::Known(value.min(usize::MAX as u64) as usize)
    }))
}

/// A variable-length number inside a block (the track number).
fn read_vint(cursor: &mut ByteCursor<'_>) -> Result<Option<u64>, DemuxError> {
    Ok(match read_size(cursor)? {
        Some(ElementSize::Known(value)) => Some(value as u64),
        _ => None,
    })
}

fn read_unsigned(body: &mut ByteCursor<'_>) -> Result<u64, DemuxError> {
    if body.remaining() > 8 {
        return Err(DemuxError::Corrupt(format!("an integer of {} bytes", body.remaining())));
    }
    let mut value: u64 = 0;
    while !body.is_at_end() {
        value = (value << 8) | body.u8()? as u64;
    }
    Ok(value)
}

fn read_float(body: &mut ByteCursor<'_>) -> Result<f64, DemuxError> {
    match body.remaining() {
        4 => Ok(f32::from_bits(body.u32()?) as f64),
        8 => Ok(f64::from_bits(body.u64()?)),
        n => Err(DemuxError::Corrupt(format!("a float of {n} bytes"))),
    }
}
