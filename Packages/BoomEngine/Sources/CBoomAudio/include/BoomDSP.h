#ifndef BOOM_DSP_H
#define BOOM_DSP_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/// The equaliser and amplifier: everything done to the sound between the decoder and the loudspeaker.
///
///     in → level (preamp, headroom, boost) → ten bands, bass, treble → balance, width → limiter → out
///
/// Plain C with no platform calls, so the same code can run on any system. `boom_dsp_process` does not lock,
/// allocate or wait: it is safe on a real-time audio thread. Everything else is called from control threads.
///
/// With nothing set (or the master switch off) the samples leave exactly as they came, bit for bit, only later by
/// `boom_dsp_latency` frames: the limiter's look-ahead is always in the path, so switching things on and off never
/// makes the audio jump.
typedef struct BoomDSP BoomDSP;

#define BOOM_DSP_BANDS 10

/// What the sound should be, in the listener's terms.
typedef struct {
    /// The master switch. Off: nothing below applies.
    bool enabled;
    /// Decibels per band: the response wanted AT each band's centre frequency. The filters' own gains are worked
    /// out from these so that the curve passes through every value, whatever the neighbouring bands are set to.
    float band_gain_db[BOOM_DSP_BANDS];
    /// Decibels applied to the whole signal.
    float preamp_db;
    /// Decibels (0 or more) the level is lowered to leave the bands room.
    float headroom_db;
    /// A low shelf, in decibels.
    float bass_db;
    /// A high shelf, in decibels.
    float treble_db;
    /// The amplifier: decibels added ahead of the limiter.
    float boost_db;
    /// Hold peaks under the ceiling (just below full scale). Off: boosts are plain gain and can clip.
    bool limiter;
    /// -1 (left only) ... 0 ... +1 (right only). The other side is turned down; neither is turned up.
    float balance;
    /// 0 (mono) ... 1 (as recorded) ... 2 (wide).
    float width;
    /// 0 (off) ... 1: harmonics of the lowest notes are added, so that small loudspeakers and earphones, which
    /// cannot move enough air for the notes themselves, still let the ear hear them.
    float deep_bass;
    /// 0 (off) ... 1: a little harmonic content is added to the top octave, which heavily compressed or dull
    /// recordings lack.
    float air;
    /// 0 (off) ... 1: for headphones. A little of each side is fed to the other, later and duller, as happens in
    /// front of two loudspeakers.
    float crossfeed;
    /// 0 (off) or 1 (on): for headphones. The two sides are heard as two loudspeakers standing in front of the
    /// listener, 30 degrees to either side: each reaches both ears, the far ear later, quieter and duller, as
    /// measured on a dummy head. Values in between are passed through only while it fades in and out.
    float spatial;
} BoomSoundParams;

/// Output levels since they were last taken.
typedef struct {
    /// The largest sample per channel (1 = full scale) since the last call.
    float peak_left, peak_right;
    /// The level of the last moment (about 20 ms) per channel, as a root mean square.
    float rms_left, rms_right;
    /// The most the limiter turned the level down since the last call, in decibels (0 = it did nothing).
    float gain_reduction_db;
} BoomLevels;

BoomDSP *_Nullable boom_dsp_create(double sampleRate);
void boom_dsp_destroy(BoomDSP *_Nullable dsp);

/// The centre frequency of a band in hertz.
double boom_dsp_band_frequency(uint32_t band);

/// Frames between a sample going in and coming out. Constant.
uint32_t boom_dsp_latency(const BoomDSP *_Nonnull dsp);

/// Sets the sound. One control thread at a time. The change glides in over a few hundredths of a second.
void boom_dsp_set_params(BoomDSP *_Nonnull dsp, const BoomSoundParams *_Nonnull params);

/// The audio thread. Processes `frames` frames in place. `inputSilent` says the input is all zeros (the player is
/// holding); returns true when the output is all zeros too.
bool boom_dsp_process(
    BoomDSP *_Nonnull dsp, float *_Nonnull left, float *_Nonnull right, uint32_t frames, bool inputSilent);

/// For the spatial stage with head tracking: how far the listener's head is turned to the right of where the two
/// loudspeakers stand, in radians (to the left: negative). The loudspeakers then stay where they are while the
/// head turns. Any thread; leave at 0 without head tracking.
void boom_dsp_set_head_turn(BoomDSP *_Nonnull dsp, float radians);

/// Takes the output levels gathered since the last call. One reading thread.
void boom_dsp_take_levels(BoomDSP *_Nonnull dsp, BoomLevels *_Nonnull levels);

/// The output's spectrum over the last moment (about 85 ms at 48 kHz), for a display: `bands` values spaced evenly
/// on a logarithmic scale from `lowHz` to `highHz`, in decibels where 0 is a full-scale sine. One reading thread.
void boom_dsp_spectrum(
    BoomDSP *_Nonnull dsp, float *_Nonnull decibels, uint32_t bands, double lowHz, double highHz);

/// The gain in decibels that the bands, bass and treble of `params` give at each frequency: the curve a screen
/// draws. It is the same arithmetic the audio goes through. All zeros with the master switch off.
void boom_dsp_response(
    const BoomSoundParams *_Nonnull params, double sampleRate,
    const double *_Nonnull frequencies, uint32_t count, double *_Nonnull decibels);

/// The largest gain of that curve between 20 Hz and 20 kHz, in decibels, and never below 0.
double boom_dsp_peak_response(const BoomSoundParams *_Nonnull params, double sampleRate);

#ifdef __cplusplus
}
#endif

#endif
