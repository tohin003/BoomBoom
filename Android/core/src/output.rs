//! The last steps before the hardware, shared by the AAudio callback and the offline renderer the tests use:
//!
//! ```text
//! pipeline (48 kHz float) → level, ten bands, bass, treble → deep bass, air → width, balance, crossfeed,
//! spatial → limiter → out
//! ```
//!
//! With the sound processor off (or on but flat) the samples leave exactly as decoded, `boom_dsp_latency` frames
//! (5 ms) later.

use std::sync::Arc;

use crate::ffi::{self, BoomDSP, BoomRenderer, Dsp};
use crate::pipeline::PlaybackPipeline;

/// One block through the chain, in place. Real-time safe: no locks, no allocation. Returns whether the renderer had
/// nothing to give (holding, or no audio in hand) and whether the block that leaves is all zeros. (The processor still
/// rings out its last few milliseconds, and calls its output silent only after a while of silent input.)
///
/// # Safety
/// `renderer` and `dsp` must be alive, and only one thread may render through them at a time.
#[inline]
pub unsafe fn render_block(
    renderer: *mut BoomRenderer,
    dsp: *mut BoomDSP,
    left: &mut [f32],
    right: &mut [f32],
) -> (bool, bool) {
    let frames = left.len().min(right.len()) as u32;
    unsafe {
        let holding = ffi::boom_renderer_render(renderer, left.as_mut_ptr(), right.as_mut_ptr(), frames);
        (holding, ffi::boom_dsp_process(dsp, left.as_mut_ptr(), right.as_mut_ptr(), frames, holding))
    }
}

/// Renders without hardware, pulled by hand: the tests' stand-in for the AAudio stream.
pub struct OfflineOutput {
    pub pipeline: Arc<PlaybackPipeline>,
    pub dsp: Dsp,
}

impl OfflineOutput {
    pub fn new(pipeline: Arc<PlaybackPipeline>) -> Self {
        OfflineOutput { pipeline, dsp: Dsp::new(crate::pipeline::SAMPLE_RATE) }
    }

    /// Pulls one block. Returns true when it was all zeros.
    pub fn render(&self, left: &mut [f32], right: &mut [f32]) -> bool {
        unsafe { render_block(self.pipeline_renderer(), self.dsp.raw(), left, right).1 }
    }

    fn pipeline_renderer(&self) -> *mut BoomRenderer {
        self.pipeline.renderer_raw()
    }
}
