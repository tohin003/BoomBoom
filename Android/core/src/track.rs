//! One song's compressed audio, as it arrives. Packets come in any order (the capture jumps around); the buffer keeps
//! each once, knows which stretches it has, and hands out runs for decoding. Safe to use from any thread.
//! A port of the iPhone app's `TrackBuffer`.

use std::collections::BTreeMap;
use std::io::Write;
use std::path::Path;
use std::sync::Mutex;

use crate::demux::{Codec, PacketRef, TrackInfo};

/// No song has this many packets (about 23 hours of 20 ms packets): a packet numbered past it is not believed.
const MOST_PACKETS: usize = 1 << 22;

#[derive(Default)]
struct State {
    info: Option<TrackInfo>,
    /// Timeline units per packet (1024 for AAC, 960 for Opus).
    packet_duration: u32,
    /// How many packets the whole song has, once known.
    expected_count: Option<usize>,
    storage: Vec<u8>,
    /// Packet number to its place in `storage` (start, length). The keys are the packets held.
    places: BTreeMap<usize, (usize, u32)>,
    /// The capture reached the end of the stream.
    is_sealed: bool,
    stated_duration: Option<f64>,
}

impl State {
    fn update_expected_count(&mut self) {
        let (Some(info), Some(duration)) = (&self.info, self.stated_duration) else { return };
        if self.packet_duration == 0 || !(duration > 0.0) {
            return;
        }
        let units = duration * info.timescale as f64;
        self.expected_count = Some(((units / self.packet_duration as f64).round() as usize).max(1));
    }

    fn holds_all(&self, range: std::ops::Range<usize>) -> bool {
        range.is_empty() || self.places.range(range.clone()).count() == range.len()
    }

    /// Packets the song needs before it counts as whole: the stated length may round one packet past what the
    /// stream really has.
    fn needed(&self, count: usize) -> usize {
        if self.is_sealed { count } else { (count - 1).max(1) }
    }

    fn first_missing(&self, range: std::ops::Range<usize>) -> Option<usize> {
        let mut expected = range.start;
        for (&index, _) in self.places.range(range.clone()) {
            if index != expected {
                return Some(expected);
            }
            expected += 1;
        }
        (expected < range.end).then_some(expected)
    }
}

/// Packets copied out for decoding: their bytes back to back and where each ends.
#[derive(Default, Debug)]
pub struct PacketRun {
    pub bytes: Vec<u8>,
    pub ends: Vec<usize>,
}

impl PacketRun {
    pub fn len(&self) -> usize {
        self.ends.len()
    }
    pub fn is_empty(&self) -> bool {
        self.ends.is_empty()
    }
    pub fn packet(&self, index: usize) -> &[u8] {
        let start = if index == 0 { 0 } else { self.ends[index - 1] };
        &self.bytes[start..self.ends[index]]
    }
    pub fn iter(&self) -> impl Iterator<Item = &[u8]> {
        (0..self.len()).map(|index| self.packet(index))
    }
}

#[derive(Default)]
pub struct TrackBuffer {
    state: Mutex<State>,
}

impl TrackBuffer {
    pub fn new() -> Self {
        Self::default()
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, State> {
        // A panic while holding the lock leaves the state consistent enough to read on.
        self.state.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    // Filling

    /// Sets the stream's description. `duration` (seconds) is what the page says the song lasts; the stream's own
    /// header wins when it states one.
    pub fn set_track(&self, info: &TrackInfo, duration: Option<f64>) {
        let mut state = self.lock();
        if let Some(packet) = info.default_packet_duration.filter(|&p| p > 0) {
            state.packet_duration = packet;
        }
        state.stated_duration = info.duration.or(duration).or(state.stated_duration);
        state.info = Some(info.clone());
        state.update_expected_count();
    }

    pub fn set_duration(&self, duration: f64) {
        let mut state = self.lock();
        if state.info.as_ref().is_some_and(|info| info.duration.is_some()) {
            return;
        }
        state.stated_duration = Some(duration);
        state.update_expected_count();
    }

    /// Adds packets. Ones already held are skipped. Returns how many were new.
    pub fn append(&self, packets: &[PacketRef<'_>]) -> usize {
        if packets.is_empty() {
            return 0;
        }
        let mut state = self.lock();
        if state.packet_duration == 0 {
            if let Some(first) = packets.iter().find(|p| p.duration > 0) {
                state.packet_duration = first.duration;
                state.update_expected_count();
            }
        }
        if state.packet_duration == 0 {
            return 0;
        }
        let length = state.packet_duration as i64;
        let mut added = 0;
        for packet in packets.iter().filter(|p| !p.data.is_empty()) {
            let index = (packet.decode_time + length / 2) / length;
            if index < 0 || index as usize >= MOST_PACKETS || state.places.contains_key(&(index as usize)) {
                continue;
            }
            let start = state.storage.len();
            state.storage.extend_from_slice(packet.data);
            state.places.insert(index as usize, (start, packet.data.len() as u32));
            added += 1;
        }
        added
    }

    /// The capture reached the end of the stream: the last packet held is the song's last. (Earlier stretches may
    /// still be missing if the capture jumped ahead.)
    pub fn seal(&self) {
        let mut state = self.lock();
        let Some((&last, _)) = state.places.last_key_value() else { return };
        state.is_sealed = true;
        state.expected_count = Some(last + 1);
    }

    // Reading

    pub fn info(&self) -> Option<TrackInfo> {
        self.lock().info.clone()
    }

    /// Timeline units per packet, 0 until the first packet is in.
    pub fn packet_duration(&self) -> u32 {
        self.lock().packet_duration
    }

    /// Packets in the whole song, once known.
    pub fn expected_packet_count(&self) -> Option<usize> {
        self.lock().expected_count
    }

    /// The song's length in seconds, once known.
    pub fn duration(&self) -> Option<f64> {
        let state = self.lock();
        if let Some(stated) = state.stated_duration {
            return Some(stated);
        }
        let (Some(info), Some(count)) = (&state.info, state.expected_count) else { return None };
        if state.packet_duration == 0 {
            return None;
        }
        Some((count as f64 * state.packet_duration as f64) / info.timescale as f64)
    }

    /// Copies up to `limit` packets in a row starting exactly at `index` into `run` (emptied first). Returns how
    /// many; none when that packet has not arrived.
    pub fn packets(&self, index: usize, limit: usize, run: &mut PacketRun) -> usize {
        run.bytes.clear();
        run.ends.clear();
        let state = self.lock();
        let mut next = index;
        while run.ends.len() < limit {
            let Some(&(start, length)) = state.places.get(&next) else { break };
            run.bytes.extend_from_slice(&state.storage[start..start + length as usize]);
            run.ends.push(run.bytes.len());
            next += 1;
        }
        run.ends.len()
    }

    /// The same, as separate copies (for tests and simple callers).
    pub fn packet_data(&self, index: usize, limit: usize) -> Vec<Vec<u8>> {
        let mut run = PacketRun::default();
        self.packets(index, limit, &mut run);
        run.iter().map(<[u8]>::to_vec).collect()
    }

    /// The first packet number at or after `index` that is missing.
    pub fn contiguous_end(&self, index: usize) -> usize {
        let state = self.lock();
        state.first_missing(index..usize::MAX).unwrap_or(usize::MAX)
    }

    /// True when `index` lies past the song's last packet: nothing will ever come there. This is about the song's
    /// end only; earlier stretches may still be missing (the listener jumped ahead).
    pub fn has_reached_end(&self, index: usize) -> bool {
        let state = self.lock();
        let Some(count) = state.expected_count.filter(|&c| c > 0) else { return false };
        if state.is_sealed {
            return index >= count;
        }
        // Not sealed yet, so `count` comes from the stated length, which may round one packet past the real end. If
        // everything up to that doubtful last packet is in hand, the end is where the audio in hand stops.
        if count >= 2 && index == count - 1 {
            if let Some((&last, _)) = state.places.last_key_value() {
                if last == count - 2 && state.holds_all(0..count - 1) {
                    return true;
                }
            }
        }
        index >= count
    }

    /// True when every packet of the song is held.
    pub fn is_complete(&self) -> bool {
        let state = self.lock();
        let Some(count) = state.expected_count.filter(|&c| c > 0) else { return false };
        state.holds_all(0..state.needed(count))
    }

    /// The first missing packet at or after `index`, wrapping round to the start. None when nothing is missing.
    pub fn first_gap(&self, index: usize) -> Option<usize> {
        let state = self.lock();
        let Some(count) = state.expected_count.filter(|&c| c > 0) else {
            return if state.places.contains_key(&0) { None } else { Some(0) };
        };
        let needed = state.needed(count);
        let start = index.min(needed);
        state.first_missing(start..needed).or_else(|| state.first_missing(0..start))
    }

    /// How much of the song is held, 0...1.
    pub fn covered_fraction(&self) -> f64 {
        let state = self.lock();
        let Some(count) = state.expected_count.filter(|&c| c > 0) else { return 0.0 };
        (state.places.len() as f64 / count as f64).min(1.0)
    }

    pub fn held_packet_count(&self) -> usize {
        self.lock().places.len()
    }

    pub fn held_bytes(&self) -> usize {
        self.lock().storage.len()
    }

    /// Seconds from the start of the song to the start of a packet.
    pub fn time_of_packet(&self, index: usize) -> f64 {
        let state = self.lock();
        let Some(info) = &state.info else { return 0.0 };
        if state.packet_duration == 0 {
            return 0.0;
        }
        (index as f64 * state.packet_duration as f64) / info.timescale as f64
    }

    /// The packet that holds a moment of the song.
    pub fn packet_index_at(&self, seconds: f64) -> usize {
        let state = self.lock();
        let Some(info) = &state.info else { return 0 };
        if state.packet_duration == 0 || !(seconds > 0.0) {
            return 0;
        }
        (seconds * info.timescale as f64 / state.packet_duration as f64) as usize
    }

    // Keeping on disk
    //
    // The file: "BOOMTRKA", then a little-endian u32 header length, the header, and the packets back to back.
    // The header: codec (u8: 0 AAC, 1 Opus), timescale (u32), sample rate (f64), channels (u32), lead-in (i64),
    // packet duration (u32), the header's own packet duration (u32, 0 = none), duration (f64, NaN = none), the magic
    // cookie and the AudioSpecificConfig (each a u32 length and the bytes), and the packet count (u32) followed by
    // each packet's size (u32).

    const MAGIC: &'static [u8; 8] = b"BOOMTRKA";

    /// The whole song as one file. None unless every packet is held.
    pub fn serialized(&self) -> Option<Vec<u8>> {
        if !self.is_complete() {
            return None;
        }
        let state = self.lock();
        let info = state.info.as_ref()?;
        let (&last, _) = state.places.last_key_value()?;
        if !state.holds_all(0..last + 1) {
            return None;
        }
        let mut header = Vec::with_capacity(64 + info.magic_cookie.len() + 4 * (last + 1));
        header.push(match info.codec {
            Codec::Aac => 0,
            Codec::Opus => 1,
        });
        header.extend_from_slice(&info.timescale.to_le_bytes());
        header.extend_from_slice(&info.sample_rate.to_le_bytes());
        header.extend_from_slice(&info.channels.to_le_bytes());
        header.extend_from_slice(&info.lead_in.to_le_bytes());
        header.extend_from_slice(&state.packet_duration.to_le_bytes());
        header.extend_from_slice(&info.default_packet_duration.unwrap_or(0).to_le_bytes());
        header.extend_from_slice(&info.duration.unwrap_or(f64::NAN).to_le_bytes());
        for bytes in [&info.magic_cookie, &info.audio_specific_config] {
            header.extend_from_slice(&(bytes.len() as u32).to_le_bytes());
            header.extend_from_slice(bytes);
        }
        header.extend_from_slice(&((last + 1) as u32).to_le_bytes());
        let mut body_length = 0usize;
        for index in 0..=last {
            let (_, length) = state.places[&index];
            header.extend_from_slice(&length.to_le_bytes());
            body_length += length as usize;
        }
        let mut file = Vec::with_capacity(12 + header.len() + body_length);
        file.extend_from_slice(Self::MAGIC);
        file.extend_from_slice(&(header.len() as u32).to_le_bytes());
        file.extend_from_slice(&header);
        for index in 0..=last {
            let (start, length) = state.places[&index];
            file.extend_from_slice(&state.storage[start..start + length as usize]);
        }
        Some(file)
    }

    /// Reads a file written by `serialized()`. None when it is not one or is cut short.
    pub fn from_serialized(file: &[u8]) -> Option<TrackBuffer> {
        struct Reader<'a> {
            bytes: &'a [u8],
            at: usize,
        }
        impl<'a> Reader<'a> {
            fn take(&mut self, count: usize) -> Option<&'a [u8]> {
                let end = self.at.checked_add(count)?;
                let slice = self.bytes.get(self.at..end)?;
                self.at = end;
                Some(slice)
            }
            fn u32(&mut self) -> Option<u32> {
                Some(u32::from_le_bytes(self.take(4)?.try_into().ok()?))
            }
            fn u64(&mut self) -> Option<u64> {
                Some(u64::from_le_bytes(self.take(8)?.try_into().ok()?))
            }
        }
        if file.len() < 12 || &file[..8] != Self::MAGIC {
            return None;
        }
        let header_length = u32::from_le_bytes(file[8..12].try_into().ok()?) as usize;
        let header_bytes = file.get(12..12usize.checked_add(header_length)?)?;
        let mut header = Reader { bytes: header_bytes, at: 0 };
        let codec = match header.take(1)?[0] {
            0 => Codec::Aac,
            1 => Codec::Opus,
            _ => return None,
        };
        let timescale = header.u32()?;
        let sample_rate = f64::from_bits(header.u64()?);
        let channels = header.u32()?;
        let lead_in = header.u64()? as i64;
        let packet_duration = header.u32()?;
        let stated_packet_duration = header.u32()?;
        let duration = f64::from_bits(header.u64()?);
        let cookie_length = header.u32()? as usize;
        let magic_cookie = header.take(cookie_length)?.to_vec();
        let config_length = header.u32()? as usize;
        let audio_specific_config = header.take(config_length)?.to_vec();
        let count = header.u32()? as usize;
        if packet_duration == 0 || timescale == 0 || count == 0 || count >= MOST_PACKETS {
            return None;
        }
        let mut sizes = Vec::with_capacity(count);
        for _ in 0..count {
            sizes.push(header.u32()?);
        }
        let body = &file[12 + header_length..];
        if body.len() != sizes.iter().map(|&s| s as usize).sum::<usize>() {
            return None;
        }
        let info = TrackInfo {
            codec,
            timescale,
            sample_rate,
            channels,
            magic_cookie,
            audio_specific_config,
            lead_in,
            default_packet_duration: (stated_packet_duration > 0).then_some(stated_packet_duration),
            duration: (!duration.is_nan()).then_some(duration),
        };
        let mut places = BTreeMap::new();
        let mut offset = 0usize;
        for (index, &size) in sizes.iter().enumerate() {
            places.insert(index, (offset, size));
            offset += size as usize;
        }
        let state = State {
            stated_duration: Some(info.duration.unwrap_or(count as f64 * packet_duration as f64 / timescale as f64)),
            info: Some(info),
            packet_duration,
            expected_count: Some(count),
            storage: body.to_vec(),
            places,
            is_sealed: true,
        };
        Some(TrackBuffer { state: Mutex::new(state) })
    }

    /// Writes the whole song to `path` (through a temporary file beside it, so a reader never sees half a file).
    /// False unless every packet is held and the file was written.
    pub fn write_to(&self, path: &Path) -> bool {
        let Some(file) = self.serialized() else { return false };
        let mut temporary = path.as_os_str().to_owned();
        temporary.push(".partial");
        let temporary = std::path::PathBuf::from(temporary);
        let written = std::fs::File::create(&temporary)
            .and_then(|mut handle| handle.write_all(&file).and_then(|_| handle.sync_all()))
            .and_then(|_| std::fs::rename(&temporary, path));
        if written.is_err() {
            let _ = std::fs::remove_file(&temporary);
        }
        written.is_ok()
    }

    /// Reads a file written by `write_to`.
    pub fn read_from(path: &Path) -> Option<TrackBuffer> {
        Self::from_serialized(&std::fs::read(path).ok()?)
    }
}
