use super::DemuxError;

fn past_the_end() -> DemuxError {
    DemuxError::Corrupt("read past the end".into())
}

/// Reads big-endian fields from a byte range, with every read bounds-checked.
#[derive(Clone, Copy, Debug)]
pub struct ByteCursor<'a> {
    base: &'a [u8],
    pub offset: usize,
    pub end: usize,
}

impl<'a> ByteCursor<'a> {
    pub fn new(base: &'a [u8]) -> Self {
        ByteCursor { base, offset: 0, end: base.len() }
    }

    pub fn at(base: &'a [u8], offset: usize, end: usize) -> Self {
        let end = end.min(base.len());
        ByteCursor { base, offset: offset.min(end), end }
    }

    pub fn remaining(&self) -> usize {
        self.end - self.offset
    }

    pub fn is_at_end(&self) -> bool {
        self.offset >= self.end
    }

    fn take(&mut self, count: usize) -> Result<usize, DemuxError> {
        if self.remaining() < count {
            return Err(past_the_end());
        }
        let at = self.offset;
        self.offset += count;
        Ok(at)
    }

    pub fn u8(&mut self) -> Result<u8, DemuxError> {
        Ok(self.base[self.take(1)?])
    }

    pub fn u16(&mut self) -> Result<u16, DemuxError> {
        let at = self.take(2)?;
        Ok(u16::from_be_bytes([self.base[at], self.base[at + 1]]))
    }

    pub fn u32(&mut self) -> Result<u32, DemuxError> {
        let at = self.take(4)?;
        Ok(u32::from_be_bytes(self.base[at..at + 4].try_into().unwrap()))
    }

    pub fn u64(&mut self) -> Result<u64, DemuxError> {
        let at = self.take(8)?;
        Ok(u64::from_be_bytes(self.base[at..at + 8].try_into().unwrap()))
    }

    pub fn i32(&mut self) -> Result<i32, DemuxError> {
        Ok(self.u32()? as i32)
    }

    pub fn i64(&mut self) -> Result<i64, DemuxError> {
        Ok(self.u64()? as i64)
    }

    pub fn skip(&mut self, count: usize) -> Result<(), DemuxError> {
        self.take(count).map(|_| ())
    }

    pub fn four_cc(&mut self) -> Result<[u8; 4], DemuxError> {
        let at = self.take(4)?;
        Ok(self.base[at..at + 4].try_into().unwrap())
    }

    /// A cursor over the next `count` bytes; this cursor moves past them.
    pub fn sub(&mut self, count: usize) -> Result<ByteCursor<'a>, DemuxError> {
        let at = self.take(count)?;
        Ok(ByteCursor { base: self.base, offset: at, end: at + count })
    }

    /// The next `count` bytes, borrowed.
    pub fn bytes(&mut self, count: usize) -> Result<&'a [u8], DemuxError> {
        let at = self.take(count)?;
        Ok(&self.base[at..at + count])
    }

    /// Everything left, borrowed.
    pub fn rest(&mut self) -> &'a [u8] {
        let at = self.offset;
        self.offset = self.end;
        &self.base[at..self.end]
    }
}

/// Reads bit fields (most significant bit first), for codec setup records.
pub struct BitReader<'a> {
    bytes: &'a [u8],
    position: usize,
}

impl<'a> BitReader<'a> {
    pub fn new(bytes: &'a [u8]) -> Self {
        BitReader { bytes, position: 0 }
    }

    pub fn bits_left(&self) -> usize {
        self.bytes.len() * 8 - self.position
    }

    pub fn read(&mut self, count: usize) -> Option<u32> {
        if count > 32 || self.bits_left() < count {
            return None;
        }
        let mut value: u32 = 0;
        for _ in 0..count {
            let byte = self.bytes[self.position >> 3];
            let bit = (byte >> (7 - (self.position & 7))) & 1;
            value = (value << 1) | bit as u32;
            self.position += 1;
        }
        Some(value)
    }
}
