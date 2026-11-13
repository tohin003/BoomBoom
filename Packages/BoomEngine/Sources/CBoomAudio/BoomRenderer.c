#include "BoomRenderer.h"

#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>

struct BoomRenderer {
    _Atomic(BoomRing *) ring;
    _Atomic bool playing;
    _Atomic float gain;
    _Atomic uint64_t renders;
    _Atomic bool idle;
    // Render thread only: the level reached at the end of the last render call.
    float level;
};

BoomRenderer *boom_renderer_create(void) {
    BoomRenderer *renderer = calloc(1, sizeof(BoomRenderer));
    if (!renderer) return NULL;
    atomic_init(&renderer->ring, NULL);
    atomic_init(&renderer->playing, false);
    atomic_init(&renderer->gain, 1.0f);
    atomic_init(&renderer->renders, 0);
    atomic_init(&renderer->idle, true);
    renderer->level = 0;
    return renderer;
}

void boom_renderer_destroy(BoomRenderer *renderer) {
    free(renderer);
}

BoomRing *boom_renderer_swap_ring(BoomRenderer *renderer, BoomRing *ring) {
    return atomic_exchange_explicit(&renderer->ring, ring, memory_order_acq_rel);
}

void boom_renderer_set_playing(BoomRenderer *renderer, bool playing) {
    atomic_store_explicit(&renderer->playing, playing, memory_order_release);
    if (playing) atomic_store_explicit(&renderer->idle, false, memory_order_release);
}

bool boom_renderer_is_playing(const BoomRenderer *renderer) {
    return atomic_load_explicit(&renderer->playing, memory_order_acquire);
}

void boom_renderer_set_gain(BoomRenderer *renderer, float gain) {
    if (!(gain >= 0)) gain = 0;
    if (gain > 64) gain = 64;
    atomic_store_explicit(&renderer->gain, gain, memory_order_release);
}

uint64_t boom_renderer_render_count(const BoomRenderer *renderer) {
    return atomic_load_explicit(&renderer->renders, memory_order_acquire);
}

bool boom_renderer_is_idle(const BoomRenderer *renderer) {
    return atomic_load_explicit(&renderer->idle, memory_order_acquire);
}

bool boom_renderer_render(BoomRenderer *renderer, float *left, float *right, uint32_t frames) {
    BoomRing *ring = atomic_load_explicit(&renderer->ring, memory_order_acquire);
    bool playing = atomic_load_explicit(&renderer->playing, memory_order_acquire);
    float target = playing ? atomic_load_explicit(&renderer->gain, memory_order_acquire) : 0;
    float level = renderer->level;
    bool silent = true;
    uint32_t got = 0;

    // Holding and already faded out: take nothing, so the place in the ring is kept.
    if (ring && boom_ring_channels(ring) == 2 && (playing || level > 0)) {
        float *planes[2] = { left, right };
        got = boom_ring_read(ring, planes, frames);
    }

    if (got > 0) {
        if (level == target) {
            if (target != 1.0f) {
                for (uint32_t i = 0; i < got; i++) { left[i] *= target; right[i] *= target; }
            }
        } else {
            // Glide from the last level to the wanted one across this call.
            float step = (target - level) / (float)got;
            float current = level;
            for (uint32_t i = 0; i < got; i++) {
                current += step;
                left[i] *= current;
                right[i] *= current;
            }
        }
        level = target;
        silent = target == 0 && renderer->level == 0;
    }
    if (got < frames) {
        memset(left + got, 0, (frames - got) * sizeof(float));
        memset(right + got, 0, (frames - got) * sizeof(float));
        // The audio ran out mid-call (or there was none): come back in with a fade, not a step.
        level = 0;
    }

    renderer->level = level;
    if (!playing && level == 0) atomic_store_explicit(&renderer->idle, true, memory_order_release);
    atomic_fetch_add_explicit(&renderer->renders, 1, memory_order_release);
    (void)silent;
    return got == 0;
}
