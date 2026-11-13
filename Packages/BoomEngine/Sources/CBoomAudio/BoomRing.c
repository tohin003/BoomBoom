#include "BoomRing.h"

#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>

// Indices count frames ever written and ever read; they never wrap in practice (2^64 frames) and their difference
// is the fill level. The slot of a frame is its count masked by the power-of-two capacity.
struct BoomRing {
    uint32_t channels;
    uint32_t capacity;
    uint32_t mask;
    float **planes;
    _Atomic uint64_t written;
    _Atomic uint64_t read;
    _Atomic bool finished;
    _Atomic uint32_t underruns;
};

static uint32_t round_up_power_of_two(uint32_t value) {
    uint32_t result = 1;
    while (result < value && result < (1u << 30)) result <<= 1;
    return result;
}

BoomRing *boom_ring_create(uint32_t capacityFrames, uint32_t channels) {
    if (channels == 0 || channels > 8 || capacityFrames == 0) return NULL;
    BoomRing *ring = calloc(1, sizeof(BoomRing));
    if (!ring) return NULL;
    ring->channels = channels;
    ring->capacity = round_up_power_of_two(capacityFrames);
    ring->mask = ring->capacity - 1;
    ring->planes = calloc(channels, sizeof(float *));
    if (!ring->planes) { free(ring); return NULL; }
    for (uint32_t c = 0; c < channels; c++) {
        ring->planes[c] = calloc(ring->capacity, sizeof(float));
        if (!ring->planes[c]) { boom_ring_destroy(ring); return NULL; }
    }
    atomic_init(&ring->written, 0);
    atomic_init(&ring->read, 0);
    atomic_init(&ring->finished, false);
    atomic_init(&ring->underruns, 0);
    return ring;
}

void boom_ring_destroy(BoomRing *ring) {
    if (!ring) return;
    if (ring->planes) {
        for (uint32_t c = 0; c < ring->channels; c++) free(ring->planes[c]);
        free(ring->planes);
    }
    free(ring);
}

uint32_t boom_ring_channels(const BoomRing *ring) { return ring->channels; }
uint32_t boom_ring_capacity(const BoomRing *ring) { return ring->capacity; }

uint32_t boom_ring_available_read(const BoomRing *ring) {
    uint64_t written = atomic_load_explicit(&ring->written, memory_order_acquire);
    uint64_t read = atomic_load_explicit(&ring->read, memory_order_acquire);
    return (uint32_t)(written - read);
}

uint32_t boom_ring_available_write(const BoomRing *ring) {
    return ring->capacity - boom_ring_available_read(ring);
}

uint32_t boom_ring_write(BoomRing *ring, const float *const *planes, uint32_t frames) {
    uint64_t written = atomic_load_explicit(&ring->written, memory_order_relaxed);
    uint64_t read = atomic_load_explicit(&ring->read, memory_order_acquire);
    uint32_t space = ring->capacity - (uint32_t)(written - read);
    uint32_t count = frames < space ? frames : space;
    if (count == 0) return 0;
    uint32_t start = (uint32_t)written & ring->mask;
    uint32_t first = ring->capacity - start;
    if (first > count) first = count;
    for (uint32_t c = 0; c < ring->channels; c++) {
        memcpy(ring->planes[c] + start, planes[c], first * sizeof(float));
        if (count > first) memcpy(ring->planes[c], planes[c] + first, (count - first) * sizeof(float));
    }
    atomic_store_explicit(&ring->written, written + count, memory_order_release);
    return count;
}

uint32_t boom_ring_read(BoomRing *ring, float *const *planes, uint32_t frames) {
    uint64_t read = atomic_load_explicit(&ring->read, memory_order_relaxed);
    uint64_t written = atomic_load_explicit(&ring->written, memory_order_acquire);
    uint32_t available = (uint32_t)(written - read);
    uint32_t count = frames < available ? frames : available;
    if (count < frames && !atomic_load_explicit(&ring->finished, memory_order_relaxed)) {
        atomic_fetch_add_explicit(&ring->underruns, 1, memory_order_relaxed);
    }
    if (count == 0) return 0;
    uint32_t start = (uint32_t)read & ring->mask;
    uint32_t first = ring->capacity - start;
    if (first > count) first = count;
    for (uint32_t c = 0; c < ring->channels; c++) {
        memcpy(planes[c], ring->planes[c] + start, first * sizeof(float));
        if (count > first) memcpy(planes[c] + first, ring->planes[c], (count - first) * sizeof(float));
    }
    atomic_store_explicit(&ring->read, read + count, memory_order_release);
    return count;
}

uint64_t boom_ring_frames_read(const BoomRing *ring) {
    return atomic_load_explicit(&ring->read, memory_order_acquire);
}

uint64_t boom_ring_frames_written(const BoomRing *ring) {
    return atomic_load_explicit(&ring->written, memory_order_acquire);
}

void boom_ring_set_finished(BoomRing *ring, bool finished) {
    atomic_store_explicit(&ring->finished, finished, memory_order_release);
}

bool boom_ring_is_finished(const BoomRing *ring) {
    return atomic_load_explicit(&ring->finished, memory_order_acquire);
}

uint32_t boom_ring_underruns(const BoomRing *ring) {
    return atomic_load_explicit(&ring->underruns, memory_order_relaxed);
}
