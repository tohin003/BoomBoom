//! Fragmented MP4 audio (ISO BMFF: `ftyp moov` then `moof mdat` pairs), the form YouTube's AAC streams take when the
//! player appends them to a media source buffer.

use std::collections::HashMap;

use super::cursor::{BitReader, ByteCursor};
use super::{append_in_place, Codec, DemuxError, DemuxEventRef, PacketRef, TrackInfo};

/// No audio box comes near this; a larger size means the bytes are not what they claim.
const LARGEST_BOX: usize = 256 << 20;

#[derive(Debug)]
struct Run {
    /// Where the run's first sample starts, counted from the start of its `moof`. None: right after the last run.
    data_offset: Option<i64>,
    samples: Vec<(u32, u32)>,
}

#[derive(Debug)]
struct Fragment {
    moof_start: i64,
    base_decode_time: i64,
    runs: Vec<Run>,
}

#[derive(Debug)]
pub struct FragmentedMp4Demuxer {
    track: Option<TrackInfo>,
    /// Bytes received but not yet parsed (an incomplete box at the end).
    pending: Vec<u8>,
    /// Where `pending` starts, counted from the last `reset()`. Fragment offsets are relative, so any origin works.
    stream_offset: i64,
    track_id: Option<u32>,
    default_duration: Option<u32>,
    default_size: Option<u32>,
    fragment: Option<Fragment>,
}

impl Default for FragmentedMp4Demuxer {
    fn default() -> Self {
        Self::new()
    }
}

fn type_name(kind: [u8; 4]) -> String {
    String::from_utf8_lossy(&kind).into_owned()
}

impl FragmentedMp4Demuxer {
    pub fn new() -> Self {
        FragmentedMp4Demuxer {
            track: None,
            pending: Vec::new(),
            stream_offset: 0,
            track_id: None,
            default_duration: None,
            default_size: None,
            fragment: None,
        }
    }

    pub fn track(&self) -> Option<&TrackInfo> {
        self.track.as_ref()
    }

    pub fn reset(&mut self) {
        self.pending.clear();
        self.stream_offset = 0;
        self.fragment = None;
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
                self.stream_offset += consumed as i64;
                Ok(())
            }
            Err(error) => {
                // What is pending cannot be trusted any more. The next segment starts clean.
                self.reset();
                Err(error)
            }
        }
    }

    fn parse<S: FnMut(DemuxEventRef<'_, '_>)>(&mut self, raw: &[u8], sink: &mut S) -> Result<usize, DemuxError> {
        let mut consumed = 0;
        let mut packets: Vec<PacketRef<'_>> = Vec::new();
        while raw.len() - consumed >= 8 {
            let mut header = ByteCursor::at(raw, consumed, raw.len());
            let mut size = header.u32()? as usize;
            let kind = header.four_cc()?;
            let mut header_length = 8;
            if size == 1 {
                if header.remaining() < 8 {
                    break;
                }
                let large = header.u64()?;
                if large > LARGEST_BOX as u64 {
                    return Err(DemuxError::Corrupt(format!("box '{}' claims {large} bytes", type_name(kind))));
                }
                size = large as usize;
                header_length = 16;
            } else if size == 0 {
                return Err(DemuxError::Unsupported(format!("box '{}' runs to the end of the stream", type_name(kind))));
            }
            if size < header_length || size > LARGEST_BOX {
                return Err(DemuxError::Corrupt(format!("box '{}' claims {size} bytes", type_name(kind))));
            }
            if raw.len() - consumed < size {
                break;
            }

            let mut body = ByteCursor::at(raw, consumed + header_length, consumed + size);
            let box_start = self.stream_offset + consumed as i64;
            match &kind {
                b"moov" => {
                    let info = self.parse_movie(&mut body)?;
                    self.track = Some(info);
                    sink(DemuxEventRef::Track(self.track.as_ref().unwrap()));
                }
                b"moof" => self.fragment = self.parse_fragment(&mut body, box_start)?,
                b"mdat" => {
                    packets.clear();
                    self.extract_packets(body, box_start + header_length as i64, &mut packets)?;
                    if !packets.is_empty() {
                        sink(DemuxEventRef::Packets(&packets));
                    }
                }
                _ => {}
            }
            consumed += size;
        }
        Ok(consumed)
    }

    // The header (moov)

    fn parse_movie(&mut self, moov: &mut ByteCursor<'_>) -> Result<TrackInfo, DemuxError> {
        let mut audio: Option<TrackHeader> = None;
        let mut extends_defaults: HashMap<u32, (u32, u32)> = HashMap::new();
        let mut movie_timescale: u32 = 0;
        let mut movie_duration: u64 = 0;
        for_each_box(moov, &mut |kind, body| {
            match &kind {
                b"mvhd" => {
                    let version = body.u8()?;
                    body.skip(3)?;
                    body.skip(if version == 1 { 16 } else { 8 })?;
                    movie_timescale = body.u32()?;
                    movie_duration = if version == 1 { body.u64()? } else { body.u32()? as u64 };
                }
                b"trak" => {
                    let header = parse_track(body)?;
                    if audio.is_none() && header.is_audio && header.entry.is_some() {
                        audio = Some(header);
                    }
                }
                b"mvex" => for_each_box(body, &mut |kind, trex| {
                    if &kind == b"mehd" {
                        // A fragmented movie states its whole length here; mvhd's is then zero.
                        let version = trex.u8()?;
                        trex.skip(3)?;
                        movie_duration = if version == 1 { trex.u64()? } else { trex.u32()? as u64 };
                        return Ok(());
                    }
                    if &kind != b"trex" {
                        return Ok(());
                    }
                    trex.skip(4)?;
                    let id = trex.u32()?;
                    trex.skip(4)?;
                    let duration = trex.u32()?;
                    let size = trex.u32()?;
                    extends_defaults.insert(id, (duration, size));
                    Ok(())
                })?,
                _ => {}
            }
            Ok(())
        })?;
        let Some(audio) = audio.filter(|a| a.timescale > 0) else {
            return Err(DemuxError::Unsupported("no playable audio track in the header".into()));
        };
        let entry = audio.entry.expect("checked above");
        self.track_id = Some(audio.id);
        let defaults = extends_defaults.get(&audio.id).copied();
        self.default_duration = defaults.and_then(|(duration, _)| (duration > 0).then_some(duration));
        self.default_size = defaults.and_then(|(_, size)| (size > 0).then_some(size));
        let duration = (movie_timescale > 0 && movie_duration > 0 && movie_duration != u32::MAX as u64)
            .then(|| movie_duration as f64 / movie_timescale as f64);
        Ok(TrackInfo {
            codec: entry.codec,
            timescale: audio.timescale,
            sample_rate: if entry.sample_rate > 0.0 { entry.sample_rate } else { audio.timescale as f64 },
            channels: entry.channels,
            magic_cookie: entry.magic_cookie,
            audio_specific_config: entry.audio_specific_config,
            lead_in: audio.lead_in,
            default_packet_duration: self.default_duration,
            duration,
        })
    }

    // Fragments (moof + mdat)

    fn parse_fragment(&self, moof: &mut ByteCursor<'_>, moof_start: i64) -> Result<Option<Fragment>, DemuxError> {
        let Some(track_id) = self.track_id else { return Err(DemuxError::MissingHeader) };
        let mut result: Option<Fragment> = None;
        for_each_box(moof, &mut |kind, traf| {
            if &kind != b"traf" || result.is_some() {
                return Ok(());
            }
            let mut id: u32 = 0;
            let mut duration = self.default_duration;
            let mut size = self.default_size;
            let mut base_decode_time: i64 = 0;
            let mut has_absolute_base = false;
            let mut runs: Vec<Run> = Vec::new();
            for_each_box(traf, &mut |kind, body| {
                match &kind {
                    b"tfhd" => {
                        let flags = body.u32()? & 0xFF_FFFF;
                        id = body.u32()?;
                        if flags & 0x1 != 0 {
                            // A file offset: meaningless in a stream. The samples then start where the mdat does.
                            body.skip(8)?;
                            has_absolute_base = true;
                        }
                        if flags & 0x2 != 0 {
                            body.skip(4)?;
                        }
                        if flags & 0x8 != 0 {
                            duration = Some(body.u32()?);
                        }
                        if flags & 0x10 != 0 {
                            size = Some(body.u32()?);
                        }
                    }
                    b"tfdt" => {
                        let version = body.u8()?;
                        body.skip(3)?;
                        base_decode_time =
                            if version == 1 { body.u64()?.min(i64::MAX as u64) as i64 } else { body.u32()? as i64 };
                    }
                    b"trun" => {
                        let flags = body.u32()? & 0xFF_FFFF;
                        let count = body.u32()? as usize;
                        if count > 1_000_000 {
                            return Err(DemuxError::Corrupt(format!("a run of {count} samples")));
                        }
                        let mut data_offset: Option<i64> = None;
                        if flags & 0x1 != 0 {
                            data_offset = Some(body.i32()? as i64);
                        }
                        if flags & 0x4 != 0 {
                            body.skip(4)?;
                        }
                        let mut samples = Vec::with_capacity(count.min(body.remaining() / 4 + 1));
                        for _ in 0..count {
                            let mut sample_duration = duration;
                            let mut sample_size = size;
                            if flags & 0x100 != 0 {
                                sample_duration = Some(body.u32()?);
                            }
                            if flags & 0x200 != 0 {
                                sample_size = Some(body.u32()?);
                            }
                            if flags & 0x400 != 0 {
                                body.skip(4)?;
                            }
                            if flags & 0x800 != 0 {
                                body.skip(4)?;
                            }
                            let Some(sample_size) = sample_size else {
                                return Err(DemuxError::Corrupt("a sample without a size".into()));
                            };
                            samples.push((sample_duration.unwrap_or(0), sample_size));
                        }
                        runs.push(Run { data_offset: if has_absolute_base { None } else { data_offset }, samples });
                    }
                    _ => {}
                }
                Ok(())
            })?;
            if id == track_id {
                result = Some(Fragment { moof_start, base_decode_time, runs: std::mem::take(&mut runs) });
            }
            Ok(())
        })?;
        Ok(result)
    }

    fn extract_packets<'p>(
        &mut self,
        payload: ByteCursor<'p>,
        payload_start: i64,
        packets: &mut Vec<PacketRef<'p>>,
    ) -> Result<(), DemuxError> {
        let Some(fragment) = self.fragment.take() else { return Ok(()) };
        let fallback_duration = self.track.as_ref().and_then(|t| t.default_packet_duration).unwrap_or(1024);
        let mut time = fragment.base_decode_time;
        let mut position: usize = 0;
        for run in &fragment.runs {
            if let Some(data_offset) = run.data_offset {
                let start = fragment.moof_start + data_offset - payload_start;
                if start < 0 || start > payload.remaining() as i64 {
                    return Err(DemuxError::Corrupt("a run starts outside its mdat".into()));
                }
                position = start as usize;
            }
            packets.reserve(run.samples.len());
            for &(sample_duration, sample_size) in &run.samples {
                let sample_size = sample_size as usize;
                if position > payload.remaining() || sample_size > payload.remaining() - position {
                    return Err(DemuxError::Corrupt("a sample overruns its mdat".into()));
                }
                let duration = if sample_duration > 0 { sample_duration } else { fallback_duration };
                if sample_size > 0 {
                    let mut cursor = payload;
                    cursor.skip(position)?;
                    packets.push(PacketRef { decode_time: time, duration, data: cursor.bytes(sample_size)? });
                }
                position += sample_size;
                time += duration as i64;
            }
        }
        Ok(())
    }
}

// Boxes

/// Visits each child box of `parent` with its type and a cursor over its body.
fn for_each_box<'a>(
    parent: &mut ByteCursor<'a>,
    visit: &mut dyn FnMut([u8; 4], &mut ByteCursor<'a>) -> Result<(), DemuxError>,
) -> Result<(), DemuxError> {
    while parent.remaining() >= 8 {
        let size = parent.u32()? as usize;
        let kind = parent.four_cc()?;
        let mut header_length = 8;
        let mut total = size;
        if size == 1 {
            total = parent.u64()?.min(usize::MAX as u64) as usize;
            header_length = 16;
        } else if size == 0 {
            total = parent.remaining() + 8;
        }
        if total < header_length || total - header_length > parent.remaining() {
            return Err(DemuxError::Corrupt(format!("box '{}' overruns its parent", type_name(kind))));
        }
        let mut body = parent.sub(total - header_length)?;
        visit(kind, &mut body)?;
    }
    Ok(())
}

struct SampleEntry {
    codec: Codec,
    channels: u32,
    sample_rate: f64,
    magic_cookie: Vec<u8>,
    audio_specific_config: Vec<u8>,
}

#[derive(Default)]
struct TrackHeader {
    id: u32,
    timescale: u32,
    lead_in: i64,
    is_audio: bool,
    entry: Option<SampleEntry>,
}

fn parse_track(trak: &mut ByteCursor<'_>) -> Result<TrackHeader, DemuxError> {
    let mut header = TrackHeader::default();
    for_each_box(trak, &mut |kind, body| {
        match &kind {
            b"tkhd" => {
                let version = body.u8()?;
                body.skip(3)?;
                body.skip(if version == 1 { 16 } else { 8 })?;
                header.id = body.u32()?;
            }
            b"edts" => for_each_box(body, &mut |kind, list| {
                if &kind != b"elst" {
                    return Ok(());
                }
                let version = list.u8()?;
                list.skip(3)?;
                let count = list.u32()?;
                for _ in 0..count.min(8) {
                    let media_time = if version == 1 {
                        list.skip(8)?;
                        list.i64()?
                    } else {
                        list.skip(4)?;
                        list.i32()? as i64
                    };
                    list.skip(4)?;
                    // A media time of -1 is an empty edit (silence before the track); the first real one counts.
                    if media_time >= 0 {
                        header.lead_in = media_time;
                        break;
                    }
                }
                Ok(())
            })?,
            b"mdia" => for_each_box(body, &mut |kind, media| {
                match &kind {
                    b"mdhd" => {
                        let version = media.u8()?;
                        media.skip(3)?;
                        media.skip(if version == 1 { 16 } else { 8 })?;
                        header.timescale = media.u32()?;
                    }
                    b"hdlr" => {
                        media.skip(8)?;
                        header.is_audio = &media.four_cc()? == b"soun";
                    }
                    b"minf" => for_each_box(media, &mut |kind, info| {
                        if &kind != b"stbl" {
                            return Ok(());
                        }
                        for_each_box(info, &mut |kind, table| {
                            if &kind == b"stsd" {
                                header.entry = parse_sample_descriptions(table)?;
                            }
                            Ok(())
                        })
                    })?,
                    _ => {}
                }
                Ok(())
            })?,
            _ => {}
        }
        Ok(())
    })?;
    Ok(header)
}

fn parse_sample_descriptions(stsd: &mut ByteCursor<'_>) -> Result<Option<SampleEntry>, DemuxError> {
    stsd.skip(8)?;
    let mut entry: Option<SampleEntry> = None;
    for_each_box(stsd, &mut |kind, body| {
        if entry.is_some() {
            return Ok(());
        }
        match &kind {
            b"mp4a" | b"Opus" => entry = Some(parse_audio_entry(kind, body)?),
            b"enca" => return Err(DemuxError::Unsupported("the audio is encrypted".into())),
            _ => {}
        }
        Ok(())
    })?;
    Ok(entry)
}

fn parse_audio_entry(kind: [u8; 4], body: &mut ByteCursor<'_>) -> Result<SampleEntry, DemuxError> {
    body.skip(8)?;
    let version = body.u16()?;
    body.skip(6)?;
    let mut channels = body.u16()? as u32;
    body.skip(6)?;
    let mut sample_rate = body.u32()? as f64 / 65536.0;
    // QuickTime's longer sound descriptions, in case a file uses them.
    if version == 1 {
        body.skip(16)?;
    } else if version == 2 {
        body.skip(36)?;
    }

    let mut entry: Option<SampleEntry> = None;
    fn visit(
        kind: [u8; 4],
        found: &mut ByteCursor<'_>,
        entry: &mut Option<SampleEntry>,
        channels: &mut u32,
        sample_rate: &mut f64,
    ) -> Result<(), DemuxError> {
        match &kind {
            b"esds" => {
                found.skip(4)?;
                let cookie = found.rest();
                let Some(config) = audio_specific_config_in_descriptor(cookie) else {
                    return Err(DemuxError::Unsupported("unreadable AAC setup".into()));
                };
                if let Some((rate, count)) = parse_audio_specific_config(&config) {
                    *sample_rate = rate;
                    if count > 0 {
                        *channels = count;
                    }
                }
                *entry = Some(SampleEntry {
                    codec: Codec::Aac,
                    channels: *channels,
                    sample_rate: *sample_rate,
                    magic_cookie: cookie.to_vec(),
                    audio_specific_config: config,
                });
            }
            b"dOps" => {
                let head = opus_head_from_specific_box(found)?;
                *entry = Some(SampleEntry {
                    codec: Codec::Opus,
                    channels: head[9] as u32,
                    sample_rate: 48_000.0,
                    magic_cookie: head,
                    audio_specific_config: Vec::new(),
                });
            }
            b"wave" => for_each_box(found, &mut |kind, inner| visit(kind, inner, entry, channels, sample_rate))?,
            _ => {}
        }
        Ok(())
    }
    for_each_box(body, &mut |kind, found| visit(kind, found, &mut entry, &mut channels, &mut sample_rate))?;
    entry.ok_or_else(|| DemuxError::Unsupported(format!("'{}' audio without codec setup", type_name(kind))))
}

/// Finds the AudioSpecificConfig inside an MPEG-4 elementary stream descriptor.
pub fn audio_specific_config_in_descriptor(descriptor: &[u8]) -> Option<Vec<u8>> {
    let mut cursor = ByteCursor::new(descriptor);
    fn length(cursor: &mut ByteCursor<'_>) -> Result<usize, DemuxError> {
        let mut value = 0usize;
        for _ in 0..4 {
            let byte = cursor.u8()?;
            value = (value << 7) | (byte & 0x7F) as usize;
            if byte & 0x80 == 0 {
                break;
            }
        }
        Ok(value)
    }
    let mut read = || -> Result<Option<Vec<u8>>, DemuxError> {
        if cursor.u8()? != 0x03 {
            return Ok(None);
        }
        length(&mut cursor)?;
        cursor.skip(2)?;
        let flags = cursor.u8()?;
        if flags & 0x80 != 0 {
            cursor.skip(2)?;
        }
        if flags & 0x40 != 0 {
            let url = cursor.u8()? as usize;
            cursor.skip(url)?;
        }
        if flags & 0x20 != 0 {
            cursor.skip(2)?;
        }
        if cursor.u8()? != 0x04 {
            return Ok(None);
        }
        length(&mut cursor)?;
        // Object type, stream type, buffer size, maximum and average bit rate.
        cursor.skip(13)?;
        if cursor.u8()? != 0x05 {
            return Ok(None);
        }
        let count = length(&mut cursor)?;
        Ok(Some(cursor.bytes(count)?.to_vec()))
    };
    read().ok().flatten()
}

/// The output sample rate and channel count an AudioSpecificConfig states.
pub fn parse_audio_specific_config(config: &[u8]) -> Option<(f64, u32)> {
    const RATES: [f64; 13] =
        [96000.0, 88200.0, 64000.0, 48000.0, 44100.0, 32000.0, 24000.0, 22050.0, 16000.0, 12000.0, 11025.0, 8000.0, 7350.0];
    let mut bits = BitReader::new(config);
    fn object_type(bits: &mut BitReader<'_>) -> Option<u32> {
        let short = bits.read(5)?;
        if short != 31 {
            return Some(short);
        }
        bits.read(6).map(|value| value + 32)
    }
    fn rate(bits: &mut BitReader<'_>) -> Option<f64> {
        let index = bits.read(4)?;
        if index == 15 {
            return bits.read(24).map(|value| value as f64);
        }
        RATES.get(index as usize).copied()
    }
    let kind = object_type(&mut bits)?;
    let mut sample_rate = rate(&mut bits)?;
    let channel_config = bits.read(4)?;
    // Explicit SBR / PS (HE-AAC): the rate that follows is the one the decoder puts out.
    if kind == 5 || kind == 29 {
        if let Some(extended) = rate(&mut bits) {
            sample_rate = extended;
        }
    }
    let channels = if channel_config == 7 { 8 } else { channel_config };
    Some((sample_rate, channels))
}

/// Builds the `OpusHead` record from an MP4 `dOps` box (same fields, big-endian there, little-endian here).
pub fn opus_head_from_specific_box(found: &mut ByteCursor<'_>) -> Result<Vec<u8>, DemuxError> {
    found.skip(1)?;
    let channels = found.u8()?;
    let pre_skip = found.u16()?;
    let input_rate = found.u32()?;
    let gain = found.u16()?;
    let family = found.u8()?;
    let mut head = b"OpusHead".to_vec();
    head.push(1);
    head.push(channels);
    head.extend_from_slice(&pre_skip.to_le_bytes());
    head.extend_from_slice(&input_rate.to_le_bytes());
    head.extend_from_slice(&gain.to_le_bytes());
    head.push(family);
    if family != 0 {
        head.extend_from_slice(found.rest());
    }
    Ok(head)
}
