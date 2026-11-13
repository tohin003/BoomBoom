#ifndef BOOM_RENDERER_H
#define BOOM_RENDERER_H

#include <stdbool.h>
#include <stdint.h>

#include "BoomRing.h"

#ifdef __cplusplus
extern "C" {
#endif

/// The audio render thread's side of playback: pulls stereo frames from the current ring, applies the gain, and
/// fades in and out so starting, pausing and jumping never click. `boom_renderer_render` does not lock, allocate
/// or wait. Everything else is called from control threads.
typedef struct BoomRenderer BoomRenderer;

BoomRenderer *_Nullable boom_renderer_create(void);
void boom_renderer_destroy(BoomRenderer *_Nullable renderer);

/// Installs the ring to play from (NULL for none) and returns the one it replaces. The render thread may still be
/// inside the old ring for one more render call: destroy it only after `boom_renderer_render_count` has moved on,
/// or after a generous delay.
BoomRing *_Nullable boom_renderer_swap_ring(BoomRenderer *_Nonnull renderer, BoomRing *_Nullable ring);

/// Play or hold. Holding fades out over one render call and then stops taking frames, so the place is kept.
void boom_renderer_set_playing(BoomRenderer *_Nonnull renderer, bool playing);
bool boom_renderer_is_playing(const BoomRenderer *_Nonnull renderer);

/// The level, as a linear factor (1 = as recorded). Changes glide over one render call.
void boom_renderer_set_gain(BoomRenderer *_Nonnull renderer, float gain);

/// How many render calls have finished.
uint64_t boom_renderer_render_count(const BoomRenderer *_Nonnull renderer);

/// True once the renderer has faded to silence after `set_playing(false)` (or has nothing to play).
bool boom_renderer_is_idle(const BoomRenderer *_Nonnull renderer);

/// The render thread. Fills `left` and `right` with `frames` frames. Returns true when the output is all silence.
bool boom_renderer_render(
    BoomRenderer *_Nonnull renderer, float *_Nonnull left, float *_Nonnull right, uint32_t frames);

#ifdef __cplusplus
}
#endif

#endif
