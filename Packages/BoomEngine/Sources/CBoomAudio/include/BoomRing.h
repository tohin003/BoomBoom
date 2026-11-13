#ifndef BOOM_RING_H
#define BOOM_RING_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/// A single-producer, single-consumer ring of audio frames (32-bit float, one plane per channel).
///
/// One thread writes (the decoder), one thread reads (the audio render thread). Neither side locks, allocates or
/// waits, so `boom_ring_read` is safe on the real-time thread. Capacity is rounded up to a power of two.
typedef struct BoomRing BoomRing;

BoomRing *_Nullable boom_ring_create(uint32_t capacityFrames, uint32_t channels);
void boom_ring_destroy(BoomRing *_Nullable ring);

uint32_t boom_ring_channels(const BoomRing *_Nonnull ring);
uint32_t boom_ring_capacity(const BoomRing *_Nonnull ring);

/// Frames waiting to be read. Safe from either thread (a snapshot).
uint32_t boom_ring_available_read(const BoomRing *_Nonnull ring);
/// Frames that can be written now. Safe from either thread (a snapshot).
uint32_t boom_ring_available_write(const BoomRing *_Nonnull ring);

/// Writer thread. Copies up to `frames` frames from `planes` (one pointer per channel) and returns how many fit.
uint32_t boom_ring_write(BoomRing *_Nonnull ring, const float *_Nonnull const *_Nonnull planes, uint32_t frames);

/// Reader thread. Copies up to `frames` frames into `planes` and returns how many were there. Frames beyond the
/// returned count are left untouched (the caller fills them with silence).
uint32_t boom_ring_read(BoomRing *_Nonnull ring, float *_Nonnull const *_Nonnull planes, uint32_t frames);

/// Total frames ever read. The render thread advances it; any thread may look.
uint64_t boom_ring_frames_read(const BoomRing *_Nonnull ring);
/// Total frames ever written.
uint64_t boom_ring_frames_written(const BoomRing *_Nonnull ring);

/// The writer marks that no more frames will come (the end of the queue). The reader sees it once the ring is empty.
void boom_ring_set_finished(BoomRing *_Nonnull ring, bool finished);
bool boom_ring_is_finished(const BoomRing *_Nonnull ring);

/// How many times the reader asked for more frames than were there while not finished (underruns).
uint32_t boom_ring_underruns(const BoomRing *_Nonnull ring);

#ifdef __cplusplus
}
#endif

#endif
