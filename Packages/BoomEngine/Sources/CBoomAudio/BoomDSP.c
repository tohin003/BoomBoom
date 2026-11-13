#include "BoomDSP.h"
#include "BoomHRIR.h"

#include <math.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>

// ---- The design ------------------------------------------------------------------------------------------------

#define FILTERS (BOOM_DSP_BANDS + 2)   // the ten bands, then the bass shelf, then the treble shelf
#define BASS BOOM_DSP_BANDS
#define TREBLE (BOOM_DSP_BANDS + 1)
#define SUB 32                         // frames between steps of a gliding setting
#define FFT_SIZE 4096
#define ANALYSIS_RING 8192             // a power of two, at least FFT_SIZE

static const double kPi = 3.14159265358979323846;
static const double kLn10Over20 = 0.11512925464970228;
static const double kBandFrequency[BOOM_DSP_BANDS] = {32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
/// How wide each band is, in octaves. At 1.4, ten bands set alike give a level response (within a quarter of a
/// decibel between 32 Hz and 16 kHz) while one band alone still stands clear of its neighbours.
static const double kBandOctaves = 1.4;
static const double kBassFrequency = 100;
static const double kTrebleFrequency = 10000;

/// The limiter never lets a sample past this: 0.3 dB under full scale.
static const double kCeiling = 0.9660508789898133;
/// How far ahead the limiter looks, which is also how long it takes to turn down: no peak arrives unannounced.
static const double kLookAheadSeconds = 0.005;
/// After a peak the level stays down this long before it starts back: long enough to span a low bass note's
/// cycle, so a sustained bass is turned down steadily instead of being reshaped wave by wave.
static const double kHoldSeconds = 0.012;
/// Then it comes back quickly from a single peak...
static const double kFastReleaseSeconds = 0.060;
/// ...but while the limiter is kept busy, part of the reduction is held much longer, so the level does not pump.
static const double kSlowAttackSeconds = 0.080;
static const double kSlowReleaseSeconds = 0.700;
static const double kSustainedShare = 0.5;

/// A setting glides to a new value with this time constant.
static const double kGlideSeconds = 0.030;

// The limiter measures the true peak: the height of the wave between the samples as well as at them. Seven points
// are worked out between each pair of samples from the eight samples either side (within about a tenth of a
// decibel up to 20 kHz); the measurement therefore refers to audio PEAK_BACK frames back.
#define PEAK_TAPS 16
#define PEAK_BACK 8
#define PEAK_POINTS 7
/// Below this no wave between samples can reach the ceiling, and the sums are skipped.
static const double kPeakWatch = 0.2;

// Deep bass: the lowest notes (under kBassCut) are followed, their second and third harmonics made, and what lies
// between kBassFloor and kBassTop of that is added. A small loudspeaker cannot sound a 50 Hz note, but it sounds
// 100 and 150 Hz, and the ear supplies the note they belong to.
static const double kBassCut = 110, kBassFloor = 70, kBassTop = 400;
static const double kBassSecond = 0.55, kBassThird = 0.33;
// Air: the band between kAirFrom and kAirTo is squared (which makes exactly its second harmonics and nothing
// above twice kAirTo, so nothing folds back), and what lies above kAirKeep of that is added.
static const double kAirFrom = 3500, kAirTo = 9000, kAirKeep = 7500;
static const double kAirShare = 0.5;
// Crossfeed: this share of the difference between the sides, below kFeedCut, moves across at full strength.
static const double kFeedCut = 700, kFeedShare = 0.35;
// Spatial: where the two loudspeakers stand, in degrees either side of straight ahead (the classic stereo triangle).
static const double kSpeakerAngle = 30;
#define SPATIAL_PAST 256               // a power of two, more than BOOM_HRIR_TAPS

typedef struct { double b0, b1, b2, a1, a2; } Coef;
typedef struct { double s1, s2; } State;

static void design_peaking(double frequency, double octaves, double decibels, double sampleRate, Coef *c) {
    double amplitude = pow(10, decibels / 40);
    double omega = 2 * kPi * frequency / sampleRate;
    double cosine = cos(omega);
    // The width is corrected for how a digital filter squeezes frequencies together toward the top of its range,
    // so the 16 kHz band is as wide as the others.
    double alpha = sin(omega) * sinh(0.34657359027997264 * octaves * omega / sin(omega));
    double a0 = 1 + alpha / amplitude;
    c->b0 = (1 + alpha * amplitude) / a0;
    c->b1 = (-2 * cosine) / a0;
    c->b2 = (1 - alpha * amplitude) / a0;
    c->a1 = (-2 * cosine) / a0;
    c->a2 = (1 - alpha / amplitude) / a0;
}

static void design_shelf(bool high, double frequency, double decibels, double sampleRate, Coef *c) {
    double amplitude = pow(10, decibels / 40);
    double omega = 2 * kPi * frequency / sampleRate;
    double cosine = cos(omega);
    double root = 2 * sqrt(amplitude) * (sin(omega) / 2 * 1.4142135623730951);
    double sign = high ? -1 : 1;   // the two shelves mirror each other
    double a0 = (amplitude + 1) + sign * (amplitude - 1) * cosine + root;
    c->b0 = amplitude * ((amplitude + 1) - sign * (amplitude - 1) * cosine + root) / a0;
    c->b1 = sign * 2 * amplitude * ((amplitude - 1) - sign * (amplitude + 1) * cosine) / a0;
    c->b2 = amplitude * ((amplitude + 1) - sign * (amplitude - 1) * cosine - root) / a0;
    c->a1 = -sign * 2 * ((amplitude - 1) + sign * (amplitude + 1) * cosine) / a0;
    c->a2 = ((amplitude + 1) + sign * (amplitude - 1) * cosine - root) / a0;
}

/// A second-order low-pass or high-pass. Q of 0.7071 is the maximally flat (Butterworth) shape.
static void design_pass(bool high, double frequency, double q, double sampleRate, Coef *c) {
    double omega = 2 * kPi * frequency / sampleRate;
    double cosine = cos(omega);
    double alpha = sin(omega) / (2 * q);
    double a0 = 1 + alpha;
    double edge = high ? (1 + cosine) / 2 : (1 - cosine) / 2;
    c->b0 = edge / a0;
    c->b1 = (high ? -(1 + cosine) : (1 - cosine)) / a0;
    c->b2 = edge / a0;
    c->a1 = (-2 * cosine) / a0;
    c->a2 = (1 - alpha) / a0;
}

static inline double run(const Coef *c, State *s, double x) {
    double y = c->b0 * x + s->s1;
    s->s1 = c->b1 * x - c->a1 * y + s->s2;
    s->s2 = c->b2 * x - c->a2 * y;
    return y;
}

static void design_filter(int index, double decibels, double sampleRate, Coef *c) {
    if (index < BOOM_DSP_BANDS) design_peaking(kBandFrequency[index], kBandOctaves, decibels, sampleRate, c);
    else design_shelf(index == TREBLE, index == BASS ? kBassFrequency : kTrebleFrequency, decibels, sampleRate, c);
}

/// A filter's gain in decibels at a frequency.
static double gain_at(const Coef *c, double frequency, double sampleRate) {
    double limit = sampleRate / 2 - 1;
    double omega = 2 * kPi * (frequency < limit ? frequency : limit) / sampleRate;
    double cos1 = cos(omega), cos2 = cos(2 * omega);
    double numerator = c->b0 * c->b0 + c->b1 * c->b1 + c->b2 * c->b2
        + 2 * (c->b0 * c->b1 + c->b1 * c->b2) * cos1 + 2 * c->b0 * c->b2 * cos2;
    double denominator = 1 + c->a1 * c->a1 + c->a2 * c->a2
        + 2 * (c->a1 + c->a1 * c->a2) * cos1 + 2 * c->a2 * cos2;
    if (numerator <= 0 || denominator <= 0) return 0;
    return 10 * log10(numerator / denominator);
}

/// Works out each band filter's own gain so that the ten together give exactly `wanted[i]` at band i's centre.
/// A band's filter also lifts its neighbours a little; left alone, ten sliders at +6 would give nearly +9.
static void solve_bands(const float *wanted, double sampleRate, double *filterDb) {
    bool flat = true;
    for (int i = 0; i < BOOM_DSP_BANDS; i++) {
        filterDb[i] = wanted[i];
        if (wanted[i] != 0) flat = false;
    }
    if (flat) return;
    for (int pass = 0; pass < 48; pass++) {
        Coef coefs[BOOM_DSP_BANDS];
        for (int j = 0; j < BOOM_DSP_BANDS; j++) design_peaking(kBandFrequency[j], kBandOctaves, filterDb[j], sampleRate, &coefs[j]);
        double worst = 0;
        double next[BOOM_DSP_BANDS];
        for (int i = 0; i < BOOM_DSP_BANDS; i++) {
            double response = 0;
            for (int j = 0; j < BOOM_DSP_BANDS; j++) response += gain_at(&coefs[j], kBandFrequency[i], sampleRate);
            double error = wanted[i] - response;
            if (fabs(error) > worst) worst = fabs(error);
            double value = filterDb[i] + 0.7 * error;
            next[i] = value > 36 ? 36 : (value < -36 ? -36 : value);
        }
        if (worst < 0.002) break;
        memcpy(filterDb, next, sizeof next);
    }
}

static double clampd(double value, double low, double high) {
    return value < low ? low : (value > high ? high : value);
}

/// The twelve filter gains a set of parameters comes to. All zero with the master switch off.
static void filter_gains(const BoomSoundParams *params, double sampleRate, double *filterDb) {
    memset(filterDb, 0, sizeof(double) * FILTERS);
    if (!params->enabled) return;
    solve_bands(params->band_gain_db, sampleRate, filterDb);
    filterDb[BASS] = params->bass_db;
    filterDb[TREBLE] = params->treble_db;
}

void boom_dsp_response(
    const BoomSoundParams *params, double sampleRate, const double *frequencies, uint32_t count, double *decibels) {
    double filterDb[FILTERS];
    filter_gains(params, sampleRate, filterDb);
    Coef coefs[FILTERS];
    bool used[FILTERS];
    for (int j = 0; j < FILTERS; j++) {
        used[j] = filterDb[j] != 0;
        if (used[j]) design_filter(j, filterDb[j], sampleRate, &coefs[j]);
    }
    for (uint32_t i = 0; i < count; i++) {
        double total = 0;
        for (int j = 0; j < FILTERS; j++) if (used[j]) total += gain_at(&coefs[j], frequencies[i], sampleRate);
        decibels[i] = total;
    }
}

double boom_dsp_peak_response(const BoomSoundParams *params, double sampleRate) {
    enum { POINTS = 121 };   // 20 Hz to 20 kHz, twelve to the octave
    double frequencies[POINTS], decibels[POINTS];
    for (int i = 0; i < POINTS; i++) frequencies[i] = 20 * pow(1000, (double)i / (POINTS - 1));
    boom_dsp_response(params, sampleRate, frequencies, POINTS, decibels);
    double peak = 0;
    for (int i = 0; i < POINTS; i++) if (decibels[i] > peak) peak = decibels[i];
    return peak;
}

double boom_dsp_band_frequency(uint32_t band) {
    return band < BOOM_DSP_BANDS ? kBandFrequency[band] : 0;
}

// ---- The processor ---------------------------------------------------------------------------------------------

struct BoomDSP {
    double sampleRate;

    // What the control thread asked for. Each value is its own atom; `version` says that something changed.
    _Atomic double wantedFilterDb[FILTERS];
    _Atomic double wantedGain;
    _Atomic double wantedLeft, wantedRight, wantedWidth;
    _Atomic double wantedBass, wantedAir, wantedFeed, wantedSpatial;
    _Atomic float headTurn;            // radians to the right
    _Atomic int wantedLimiter;
    _Atomic uint32_t version;

    // The audio thread's side: where each setting is now and where it is going.
    uint32_t seenVersion;
    double targetFilterDb[FILTERS], filterDb[FILTERS];
    Coef coefs[FILTERS];
    State states[FILTERS][2];
    double targetGain, gain;
    double targetLeft, left, targetRight, right, targetWidth, width;
    double targetBass, bass, targetAir, air, targetFeed, feed;

    // Deep bass (one channel: the lowest notes are in the middle).
    Coef bassCutA, bassCutB, bassFloor, bassTop;
    State bassCutStateA, bassCutStateB, bassFloorState, bassTopState;
    double bassEnvelope, bassAttack, bassRelease;
    // Air (per side).
    Coef airFrom, airToA, airToB, airKeepA, airKeepB;
    State airState[5][2];
    double airEnvelope[2], airAttack, airRelease;
    // Crossfeed.
    double feedCoef, feedState;
    // Spatial: the last samples of each side (kept twice over, so the newest stretch is always in one piece), and
    // the four paths from the two loudspeakers to the two ears for where the head is turned now.
    double targetSpatial, spatial;
    float pastLeft[2 * SPATIAL_PAST], pastRight[2 * SPATIAL_PAST];
    uint32_t pastAt;
    float paths[4][BOOM_HRIR_TAPS];    // left speaker to left ear, to right ear; right speaker to left ear, to right ear
    double turn, pathsTurn;            // the head's turn in degrees, smoothed; and the turn `paths` were made for
    bool pathsReady;
    double spatialScale;

    // The true-peak measurement: the last PEAK_TAPS samples per side and the three sets of weights.
    float peakLeftPast[16], peakRightPast[16];
    uint32_t peakAt;
    int peakWatch;
    double peakWeights[PEAK_POINTS][PEAK_TAPS];
    bool limiterEngaged;
    bool gliding;
    bool started;   // the audio thread has run at least once
    double glide;   // the share of the remaining way a setting goes per SUB frames

    // The limiter.
    uint32_t lookAhead;      // N: frames of look-ahead, also the length of the smoothing window
    uint32_t window;         // W: look-ahead plus hold, the span the lowest gain is taken over
    uint32_t delayMask, delayAt;
    float *delayLeft, *delayRight;
    double envelopeFast, envelopeSlow;
    double fastCoef, slowAttack, slowRelease;
    // The lowest gain over the last `window` frames: a queue kept in rising order, so its head is the lowest.
    uint32_t queueMask, queueHead, queueCount;
    double *queueValue;
    uint64_t *queueFrame;
    uint64_t frame;
    // The average over the last `lookAhead` frames of that lowest gain, kept as what is missing from 1.
    double *deficit;
    uint32_t deficitAt, deficitNonZero;
    double deficitSum;
    /// Frames in a row that asked for no reduction; once past both windows, the limiter has nothing to do.
    uint64_t quietRun;

    // Silence in, silence out.
    uint64_t silentFrames;
    bool drained;

    // Levels.
    _Atomic float peakLeft, peakRight, rmsLeft, rmsRight, reduction;
    double squaresLeft, squaresRight;
    uint32_t squaresCount;

    // The spectrum display's feed: the newest output, mono.
    float analysis[ANALYSIS_RING];
    _Atomic uint32_t analysisAt;
    // The reading thread's workspace.
    float fftReal[FFT_SIZE], fftImaginary[FFT_SIZE], fftWindow[FFT_SIZE];
    float fftCos[FFT_SIZE / 2], fftSin[FFT_SIZE / 2];
};

/// The modified Bessel function of order zero, for the Kaiser window.
static double bessel0(double x) {
    double sum = 1, term = 1;
    for (int k = 1; k < 40; k++) {
        term *= (x / (2 * k)) * (x / (2 * k));
        sum += term;
        if (term < 1e-15 * sum) break;
    }
    return sum;
}

static uint32_t power_of_two_above(uint32_t value) {
    uint32_t result = 1;
    while (result <= value) result <<= 1;
    return result;
}

BoomDSP *boom_dsp_create(double sampleRate) {
    if (!(sampleRate >= 8000)) return NULL;
    BoomDSP *d = calloc(1, sizeof(BoomDSP));
    if (!d) return NULL;
    d->sampleRate = sampleRate;
    d->lookAhead = (uint32_t)lround(kLookAheadSeconds * sampleRate);
    if (d->lookAhead < 8) d->lookAhead = 8;
    d->window = d->lookAhead + (uint32_t)lround(kHoldSeconds * sampleRate);
    uint32_t delaySize = power_of_two_above(d->lookAhead + PEAK_BACK + 1);
    uint32_t queueSize = power_of_two_above(d->window);
    d->delayMask = delaySize - 1;
    d->queueMask = queueSize - 1;
    d->delayLeft = calloc(delaySize, sizeof(float));
    d->delayRight = calloc(delaySize, sizeof(float));
    d->queueValue = calloc(queueSize, sizeof(double));
    d->queueFrame = calloc(queueSize, sizeof(uint64_t));
    d->deficit = calloc(d->lookAhead, sizeof(double));
    if (!d->delayLeft || !d->delayRight || !d->queueValue || !d->queueFrame || !d->deficit) {
        boom_dsp_destroy(d);
        return NULL;
    }
    d->fastCoef = exp(-1 / (kFastReleaseSeconds * sampleRate));
    d->slowAttack = 1 - exp(-1 / (kSlowAttackSeconds * sampleRate));
    d->slowRelease = 1 - exp(-1 / (kSlowReleaseSeconds * sampleRate));
    d->glide = 1 - exp(-(double)SUB / (kGlideSeconds * sampleRate));
    d->quietRun = (uint64_t)d->window + d->lookAhead;

    // Fourth order, so that nothing of the midrange gets in and grows harmonics of its own.
    design_pass(false, kBassCut, 0.5411961001461969, sampleRate, &d->bassCutA);
    design_pass(false, kBassCut, 1.3065629648763766, sampleRate, &d->bassCutB);
    design_pass(true, kBassFloor, 0.7071067811865476, sampleRate, &d->bassFloor);
    design_pass(false, kBassTop, 0.7071067811865476, sampleRate, &d->bassTop);
    d->bassAttack = 1 - exp(-1 / (0.003 * sampleRate));
    d->bassRelease = 1 - exp(-1 / (0.080 * sampleRate));
    design_pass(true, kAirFrom, 0.7071067811865476, sampleRate, &d->airFrom);
    // Two sections make a fourth-order low-pass: steep enough that nothing squared lands above half the rate.
    design_pass(false, kAirTo, 0.5411961001461969, sampleRate, &d->airToA);
    design_pass(false, kAirTo, 1.3065629648763766, sampleRate, &d->airToB);
    // And a fourth-order high-pass after the squaring: only the new top stays, none of the difference tones.
    design_pass(true, kAirKeep, 0.5411961001461969, sampleRate, &d->airKeepA);
    design_pass(true, kAirKeep, 1.3065629648763766, sampleRate, &d->airKeepB);
    d->airAttack = 1 - exp(-1 / (0.0005 * sampleRate));
    d->airRelease = 1 - exp(-1 / (0.015 * sampleRate));
    d->feedCoef = 1 - exp(-2 * kPi * kFeedCut / sampleRate);
    // A voice in the middle comes from both loudspeakers alike; the scale keeps it at the level it had.
    {
        int index = (int)(kSpeakerAngle / BOOM_HRIR_STEP_DEGREES);
        double energy = 0;
        for (int tap = 0; tap < BOOM_HRIR_TAPS; tap++) {
            double both = (double)boom_hrir[index][0][tap] + boom_hrir[index][1][tap];
            energy += both * both;
        }
        d->spatialScale = energy > 0 ? 1 / sqrt(energy) : 1;
    }

    // Weights for the wave's height at seven evenly spaced points between two samples: the ideal interpolation,
    // cut to sixteen samples by a window (Kaiser, 4: the one that keeps the answer right to the top of the audible
    // range, erring on the high side if at all), each set made to sum to one.
    for (int point = 0; point < PEAK_POINTS; point++) {
        double offset = (point + 1) / (double)(PEAK_POINTS + 1), sum = 0;
        for (int tap = 0; tap < PEAK_TAPS; tap++) {
            double t = (tap - (PEAK_TAPS / 2 - 1)) - offset;          // samples from the point wanted
            double sinc = fabs(t) < 1e-12 ? 1 : sin(kPi * t) / (kPi * t);
            double span = t / (PEAK_TAPS / 2);                         // -1 ... 1 across the window
            double window = fabs(span) < 1 ? bessel0(4 * sqrt(1 - span * span)) / bessel0(4) : 0;
            d->peakWeights[point][tap] = sinc * window;
            sum += d->peakWeights[point][tap];
        }
        for (int tap = 0; tap < PEAK_TAPS; tap++) d->peakWeights[point][tap] /= sum;
    }

    d->gain = d->targetGain = 1;
    d->left = d->targetLeft = 1;
    d->right = d->targetRight = 1;
    d->width = d->targetWidth = 1;
    atomic_store(&d->wantedGain, 1);
    atomic_store(&d->wantedLeft, 1);
    atomic_store(&d->wantedRight, 1);
    atomic_store(&d->wantedWidth, 1);

    for (int i = 0; i < FFT_SIZE; i++) d->fftWindow[i] = (float)(0.5 - 0.5 * cos(2 * kPi * i / FFT_SIZE));
    for (int i = 0; i < FFT_SIZE / 2; i++) {
        d->fftCos[i] = (float)cos(2 * kPi * i / FFT_SIZE);
        d->fftSin[i] = (float)sin(2 * kPi * i / FFT_SIZE);
    }
    return d;
}

void boom_dsp_destroy(BoomDSP *d) {
    if (!d) return;
    free(d->delayLeft);
    free(d->delayRight);
    free(d->queueValue);
    free(d->queueFrame);
    free(d->deficit);
    free(d);
}

uint32_t boom_dsp_latency(const BoomDSP *d) {
    return d->lookAhead - 1 + PEAK_BACK;
}

void boom_dsp_set_params(BoomDSP *d, const BoomSoundParams *params) {
    double filterDb[FILTERS];
    filter_gains(params, d->sampleRate, filterDb);
    double gainDb = 0, left = 1, right = 1, width = 1, bass = 0, air = 0, feed = 0, spatial = 0;
    int limiter = 0;
    if (params->enabled) {
        bass = clampd(params->deep_bass, 0, 1);
        air = clampd(params->air, 0, 1);
        feed = clampd(params->crossfeed, 0, 1);
        gainDb = (double)params->preamp_db - fmax(0, (double)params->headroom_db) + (double)params->boost_db;
        double balance = clampd(params->balance, -1, 1);
        if (balance > 0) left = 1 - balance;
        if (balance < 0) right = 1 + balance;
        width = clampd(params->width, 0, 2);
        spatial = clampd(params->spatial, 0, 1);
        bool changesNothing = gainDb == 0 && left == 1 && right == 1 && width == 1 && bass == 0 && air == 0 && feed == 0
            && spatial == 0;
        for (int i = 0; i < FILTERS; i++) if (filterDb[i] != 0) changesNothing = false;
        limiter = params->limiter && !changesNothing;
    }
    for (int i = 0; i < FILTERS; i++) atomic_store_explicit(&d->wantedFilterDb[i], filterDb[i], memory_order_relaxed);
    atomic_store_explicit(&d->wantedGain, gainDb == 0 ? 1 : exp(gainDb * kLn10Over20), memory_order_relaxed);
    atomic_store_explicit(&d->wantedLeft, left, memory_order_relaxed);
    atomic_store_explicit(&d->wantedRight, right, memory_order_relaxed);
    atomic_store_explicit(&d->wantedWidth, width, memory_order_relaxed);
    atomic_store_explicit(&d->wantedBass, bass, memory_order_relaxed);
    atomic_store_explicit(&d->wantedAir, air, memory_order_relaxed);
    atomic_store_explicit(&d->wantedFeed, feed, memory_order_relaxed);
    atomic_store_explicit(&d->wantedSpatial, spatial, memory_order_relaxed);
    atomic_store_explicit(&d->wantedLimiter, limiter, memory_order_relaxed);
    atomic_fetch_add_explicit(&d->version, 1, memory_order_release);
}

/// Picks up what the control thread asked for.
static void take_wanted(BoomDSP *d) {
    uint32_t version = atomic_load_explicit(&d->version, memory_order_acquire);
    if (version == d->seenVersion) return;
    d->seenVersion = version;
    for (int i = 0; i < FILTERS; i++) d->targetFilterDb[i] = atomic_load_explicit(&d->wantedFilterDb[i], memory_order_relaxed);
    d->targetGain = atomic_load_explicit(&d->wantedGain, memory_order_relaxed);
    d->targetLeft = atomic_load_explicit(&d->wantedLeft, memory_order_relaxed);
    d->targetRight = atomic_load_explicit(&d->wantedRight, memory_order_relaxed);
    d->targetWidth = atomic_load_explicit(&d->wantedWidth, memory_order_relaxed);
    d->targetBass = atomic_load_explicit(&d->wantedBass, memory_order_relaxed);
    d->targetAir = atomic_load_explicit(&d->wantedAir, memory_order_relaxed);
    d->targetFeed = atomic_load_explicit(&d->wantedFeed, memory_order_relaxed);
    d->targetSpatial = atomic_load_explicit(&d->wantedSpatial, memory_order_relaxed);
    d->limiterEngaged = atomic_load_explicit(&d->wantedLimiter, memory_order_relaxed) != 0;
    d->gliding = true;
    if (!d->started) {
        // Nothing has been heard yet, so there is nothing to glide from: the settings simply are what was asked.
        for (int i = 0; i < FILTERS; i++) {
            d->filterDb[i] = d->targetFilterDb[i];
            if (d->filterDb[i] != 0) design_filter(i, d->filterDb[i], d->sampleRate, &d->coefs[i]);
        }
        d->gain = d->targetGain;
        d->left = d->targetLeft;
        d->right = d->targetRight;
        d->width = d->targetWidth;
        d->bass = d->targetBass;
        d->air = d->targetAir;
        d->feed = d->targetFeed;
        d->spatial = d->targetSpatial;
        d->gliding = false;
    }
}

/// One step of a value on its way to `target`; lands on it exactly once it is within `near`.
static double step_toward(double value, double target, double share, double near) {
    if (value == target) return value;
    double next = value + (target - value) * share;
    return fabs(target - next) < near ? target : next;
}

/// Moves the filters one step toward where they were asked to be.
static void glide_filters(BoomDSP *d) {
    bool moving = false;
    for (int i = 0; i < FILTERS; i++) {
        if (d->filterDb[i] == d->targetFilterDb[i]) continue;
        d->filterDb[i] = step_toward(d->filterDb[i], d->targetFilterDb[i], d->glide, 0.002);
        if (d->filterDb[i] == 0) {
            // A filter at 0 dB is left out altogether, and it rests with nothing stored.
            memset(d->states[i], 0, sizeof d->states[i]);
        } else {
            design_filter(i, d->filterDb[i], d->sampleRate, &d->coefs[i]);
        }
        if (d->filterDb[i] != d->targetFilterDb[i]) moving = true;
    }
    if (d->gain != d->targetGain || d->left != d->targetLeft || d->right != d->targetRight || d->width != d->targetWidth
        || d->bass != d->targetBass || d->air != d->targetAir || d->feed != d->targetFeed
        || d->spatial != d->targetSpatial) {
        moving = true;
    }
    d->gliding = moving;
}

/// The sum of BOOM_HRIR_TAPS products, in four strands so that it can be done four at a time.
static inline float dot(const float *a, const float *b) {
    float s0 = 0, s1 = 0, s2 = 0, s3 = 0;
    for (int i = 0; i < BOOM_HRIR_TAPS; i += 4) {
        s0 += a[i] * b[i];
        s1 += a[i + 1] * b[i + 1];
        s2 += a[i + 2] * b[i + 2];
        s3 += a[i + 3] * b[i + 3];
    }
    return (s0 + s1) + (s2 + s3);
}

/// The paths from a loudspeaker to the two ears, for a loudspeaker `degrees` to the right of where the head points
/// (negative: to the left): read between the two nearest measured directions, and stored oldest sample first.
static void make_path(float *toLeftEar, float *toRightEar, double degrees) {
    while (degrees > 180) degrees -= 360;
    while (degrees <= -180) degrees += 360;
    bool onTheLeft = degrees < 0;
    double place = fabs(degrees) / BOOM_HRIR_STEP_DEGREES;
    int first = (int)place;
    if (first > BOOM_HRIR_DIRECTIONS - 1) first = BOOM_HRIR_DIRECTIONS - 1;
    int second = first + 1 < BOOM_HRIR_DIRECTIONS ? first + 1 : first;
    float share = (float)(place - first);
    // The table is for sources on the right: [0] the far (left) ear, [1] the near (right) ear.
    float *far = onTheLeft ? toRightEar : toLeftEar, *near = onTheLeft ? toLeftEar : toRightEar;
    for (int tap = 0; tap < BOOM_HRIR_TAPS; tap++) {
        int back = BOOM_HRIR_TAPS - 1 - tap;
        far[back] = boom_hrir[first][0][tap] + (boom_hrir[second][0][tap] - boom_hrir[first][0][tap]) * share;
        near[back] = boom_hrir[first][1][tap] + (boom_hrir[second][1][tap] - boom_hrir[first][1][tap]) * share;
    }
}

/// The equaliser, the stereo stage and the level, for up to SUB frames. Returns false when none of it applies
/// (and the samples were not touched).
static bool shape(BoomDSP *d, float *left, float *right, uint32_t frames) {
    double gain0 = d->gain, left0 = d->left, right0 = d->right, width0 = d->width;
    double bass0 = d->bass, air0 = d->air, feed0 = d->feed, spatial0 = d->spatial;
    if (d->gliding) {
        glide_filters(d);
        d->gain = step_toward(d->gain, d->targetGain, d->glide, 1e-6);
        d->left = step_toward(d->left, d->targetLeft, d->glide, 1e-6);
        d->right = step_toward(d->right, d->targetRight, d->glide, 1e-6);
        d->width = step_toward(d->width, d->targetWidth, d->glide, 1e-6);
        d->bass = step_toward(d->bass, d->targetBass, d->glide, 1e-5);
        d->air = step_toward(d->air, d->targetAir, d->glide, 1e-5);
        d->feed = step_toward(d->feed, d->targetFeed, d->glide, 1e-5);
        d->spatial = step_toward(d->spatial, d->targetSpatial, d->glide, 1e-5);
    }
    bool filters = false;
    for (int i = 0; i < FILTERS; i++) if (d->filterDb[i] != 0) { filters = true; break; }
    bool stereo = width0 != 1 || d->width != 1;
    bool level = gain0 != 1 || d->gain != 1 || left0 != 1 || d->left != 1 || right0 != 1 || d->right != 1;
    bool deepBass = bass0 != 0 || d->bass != 0;
    bool air = air0 != 0 || d->air != 0;
    bool feed = feed0 != 0 || d->feed != 0;
    bool spatial = spatial0 != 0 || d->spatial != 0;
    // A stage that is off rests with nothing stored, so it starts clean the next time.
    if (!deepBass && d->bassEnvelope != 0) {
        memset(&d->bassCutStateA, 0, sizeof(State));
        memset(&d->bassCutStateB, 0, sizeof(State));
        memset(&d->bassFloorState, 0, sizeof(State));
        memset(&d->bassTopState, 0, sizeof(State));
        d->bassEnvelope = 0;
    }
    if (!air && (d->airEnvelope[0] != 0 || d->airEnvelope[1] != 0)) {
        memset(d->airState, 0, sizeof d->airState);
        d->airEnvelope[0] = d->airEnvelope[1] = 0;
    }
    if (!feed) d->feedState = 0;
    if (!spatial && d->pathsReady) {
        memset(d->pastLeft, 0, sizeof d->pastLeft);
        memset(d->pastRight, 0, sizeof d->pastRight);
        d->pathsReady = false;
    }
    if (!filters && !stereo && !level && !deepBass && !air && !feed && !spatial) return false;

    double l[SUB], r[SUB];
    for (uint32_t n = 0; n < frames; n++) { l[n] = left[n]; r[n] = right[n]; }

    if (filters) {
        for (int i = 0; i < FILTERS; i++) {
            if (d->filterDb[i] == 0) continue;
            const Coef c = d->coefs[i];
            State a = d->states[i][0], b = d->states[i][1];
            for (uint32_t n = 0; n < frames; n++) {
                double x = l[n];
                double y = c.b0 * x + a.s1;
                a.s1 = c.b1 * x - c.a1 * y + a.s2;
                a.s2 = c.b2 * x - c.a2 * y;
                l[n] = y;
                x = r[n];
                y = c.b0 * x + b.s1;
                b.s1 = c.b1 * x - c.a1 * y + b.s2;
                b.s2 = c.b2 * x - c.a2 * y;
                r[n] = y;
            }
            // Numbers too small to matter are dropped, so a long silence does not leave the filter grinding on them.
            if (fabs(a.s1) < 1e-25) a.s1 = 0;
            if (fabs(a.s2) < 1e-25) a.s2 = 0;
            if (fabs(b.s1) < 1e-25) b.s1 = 0;
            if (fabs(b.s2) < 1e-25) b.s2 = 0;
            d->states[i][0] = a;
            d->states[i][1] = b;
        }
    }
    if (deepBass) {
        double step = (d->bass - bass0) / frames, amount = bass0;
        State cutA = d->bassCutStateA, cutB = d->bassCutStateB, floor = d->bassFloorState, top = d->bassTopState;
        double envelope = d->bassEnvelope;
        for (uint32_t n = 0; n < frames; n++) {
            amount += step;
            double low = run(&d->bassCutB, &cutB, run(&d->bassCutA, &cutA, (l[n] + r[n]) * 0.5));
            double size = fabs(low);
            envelope += (size - envelope) * (size > envelope ? d->bassAttack : d->bassRelease);
            double harmonics = 0;
            if (envelope > 1e-6) {
                // Scaled to its own size, a note runs between -1 and 1, where these two polynomials turn it into
                // exactly its second and its third harmonic. So the harmonics keep their proportion at any level.
                double unit = low / envelope;
                if (unit > 1.25) unit = 1.25;
                if (unit < -1.25) unit = -1.25;
                harmonics = envelope * (kBassSecond * (2 * unit * unit - 1) + kBassThird * (4 * unit * unit * unit - 3 * unit));
            }
            harmonics = run(&d->bassTop, &top, run(&d->bassFloor, &floor, harmonics)) * amount;
            l[n] += harmonics;
            r[n] += harmonics;
        }
        if (envelope < 1e-12) envelope = 1e-12;
        d->bassCutStateA = cutA;
        d->bassCutStateB = cutB;
        d->bassFloorState = floor;
        d->bassTopState = top;
        d->bassEnvelope = envelope;
    }
    if (air) {
        double step = (d->air - air0) / frames;
        for (int side = 0; side < 2; side++) {
            double *x = side == 0 ? l : r;
            State from = d->airState[0][side], toA = d->airState[1][side], toB = d->airState[2][side];
            State keepA = d->airState[3][side], keepB = d->airState[4][side];
            double envelope = d->airEnvelope[side], amount = air0;
            for (uint32_t n = 0; n < frames; n++) {
                amount += step;
                double band = run(&d->airToB, &toB, run(&d->airToA, &toA, run(&d->airFrom, &from, x[n])));
                double size = fabs(band);
                envelope += (size - envelope) * (size > envelope ? d->airAttack : d->airRelease);
                double harmonics = 0;
                if (envelope > 1e-7) {
                    // The band squared, over its own size: its second harmonics at a level that follows the band.
                    double unit = size / envelope;
                    if (unit > 2) unit = 2;
                    harmonics = size * unit;
                }
                x[n] += run(&d->airKeepB, &keepB, run(&d->airKeepA, &keepA, harmonics)) * amount * kAirShare;
            }
            if (envelope < 1e-12) envelope = 1e-12;
            d->airState[0][side] = from;
            d->airState[1][side] = toA;
            d->airState[2][side] = toB;
            d->airState[3][side] = keepA;
            d->airState[4][side] = keepB;
            d->airEnvelope[side] = envelope;
        }
    }
    if (stereo) {
        double step = (d->width - width0) / frames;
        double width = width0;
        for (uint32_t n = 0; n < frames; n++) {
            width += step;
            double mid = (l[n] + r[n]) * 0.5, side = (l[n] - r[n]) * 0.5 * width;
            l[n] = mid + side;
            r[n] = mid - side;
        }
    }
    if (feed) {
        // What the two sides do not share, below 700 Hz, is partly moved across. What they share (a voice in the
        // middle) is not touched at all.
        double step = (d->feed - feed0) / frames, amount = feed0, state = d->feedState;
        for (uint32_t n = 0; n < frames; n++) {
            amount += step;
            state += d->feedCoef * ((l[n] - r[n]) - state);
            double across = state * amount * kFeedShare;
            l[n] -= across;
            r[n] += across;
        }
        d->feedState = fabs(state) < 1e-25 ? 0 : state;
    }
    if (spatial) {
        // Where the head is turned, followed smoothly; the paths are made anew when it has moved.
        double wanted = atomic_load_explicit(&d->headTurn, memory_order_relaxed) * (180 / kPi);
        if (!d->pathsReady) d->turn = wanted;
        d->turn = step_toward(d->turn, wanted, d->glide, 0.01);
        if (!d->pathsReady || fabs(d->turn - d->pathsTurn) > 0.05) {
            make_path(d->paths[0], d->paths[1], -kSpeakerAngle - d->turn);
            make_path(d->paths[2], d->paths[3], kSpeakerAngle - d->turn);
            d->pathsTurn = d->turn;
            d->pathsReady = true;
        }
        double step = (d->spatial - spatial0) / frames, amount = spatial0;
        const float scale = (float)d->spatialScale;
        for (uint32_t n = 0; n < frames; n++) {
            amount += step;
            uint32_t at = (d->pastAt + 1) & (SPATIAL_PAST - 1);
            d->pastAt = at;
            d->pastLeft[at] = d->pastLeft[at + SPATIAL_PAST] = (float)l[n];
            d->pastRight[at] = d->pastRight[at + SPATIAL_PAST] = (float)r[n];
            // The newest BOOM_HRIR_TAPS samples of each side, oldest first; the paths are stored the same way round.
            const float *fromLeft = &d->pastLeft[at + SPATIAL_PAST - (BOOM_HRIR_TAPS - 1)];
            const float *fromRight = &d->pastRight[at + SPATIAL_PAST - (BOOM_HRIR_TAPS - 1)];
            double leftEar = (double)(dot(d->paths[0], fromLeft) + dot(d->paths[2], fromRight)) * scale;
            double rightEar = (double)(dot(d->paths[1], fromLeft) + dot(d->paths[3], fromRight)) * scale;
            l[n] += (leftEar - l[n]) * amount;
            r[n] += (rightEar - r[n]) * amount;
        }
    }
    if (level) {
        double from = gain0 * left0, to = d->gain * d->left;
        double step = (to - from) / frames, value = from;
        for (uint32_t n = 0; n < frames; n++) { value += step; l[n] *= value; }
        from = gain0 * right0;
        to = d->gain * d->right;
        step = (to - from) / frames;
        value = from;
        for (uint32_t n = 0; n < frames; n++) { value += step; r[n] *= value; }
    }
    for (uint32_t n = 0; n < frames; n++) { left[n] = (float)l[n]; right[n] = (float)r[n]; }
    return true;
}

/// The limiter, in place: every sample comes out `lookAhead - 1 + PEAK_BACK` frames after it went in. Returns the lowest gain
/// it applied.
static double limit(BoomDSP *d, float *left, float *right, uint32_t frames) {
    const uint32_t lookAhead = d->lookAhead, window = d->window;
    const uint64_t idleAfter = (uint64_t)window + lookAhead;
    double lowest = 1;
    for (uint32_t n = 0; n < frames; n++) {
        float inLeft = left[n], inRight = right[n];

        // The wave's true height around the sample PEAK_BACK frames ago: at that sample and at seven points on the
        // way to the next one.
        uint32_t newest = d->peakAt;
        d->peakLeftPast[newest] = inLeft;
        d->peakRightPast[newest] = inRight;
        d->peakAt = (newest + 1) & 15;
        double needed = 0;
        if (d->limiterEngaged) {
            if (fabsf(inLeft) > kPeakWatch || fabsf(inRight) > kPeakWatch) d->peakWatch = PEAK_TAPS + 1;
            double peak = fmax(fabsf(d->peakLeftPast[(newest - PEAK_BACK) & 15]), fabsf(d->peakRightPast[(newest - PEAK_BACK) & 15]));
            if (d->peakWatch > 0) {
                d->peakWatch -= 1;
                for (int point = 0; point < PEAK_POINTS; point++) {
                    const double *weights = d->peakWeights[point];
                    double sumLeft = 0, sumRight = 0;
                    // weights[tap] belongs to the sample (tap - 7) frames after the one PEAK_BACK ago.
                    for (int tap = 0; tap < PEAK_TAPS; tap++) {
                        uint32_t at = (newest - (PEAK_TAPS - 1) + tap) & 15;
                        sumLeft += weights[tap] * d->peakLeftPast[at];
                        sumRight += weights[tap] * d->peakRightPast[at];
                    }
                    peak = fmax(peak, fmax(fabs(sumLeft), fabs(sumRight)));
                }
            }
            if (peak > kCeiling) needed = 20 * log10(peak / kCeiling);
        }
        double fast = d->envelopeFast * d->fastCoef;
        if (fast < 1e-4) fast = 0;
        if (needed > fast) fast = needed;
        d->envelopeFast = fast;
        double slow = d->envelopeSlow;
        slow += (fast - slow) * (fast > slow ? d->slowAttack : d->slowRelease);
        if (fast == 0 && slow < 1e-3) slow = 0;
        d->envelopeSlow = slow;
        double reduction = fmax(fast, kSustainedShare * slow);
        double gain = reduction > 0 ? exp(-reduction * kLn10Over20) : 1;

        // The delay: this sample goes in, the one from lookAhead - 1 + PEAK_BACK frames ago comes out. By then the
        // level has finished sliding down for whatever was measured around it.
        uint32_t at = d->delayAt;
        uint32_t from = (at + 1 - lookAhead - PEAK_BACK) & d->delayMask;
        d->delayLeft[at] = inLeft;
        d->delayRight[at] = inRight;
        float outLeft = d->delayLeft[from], outRight = d->delayRight[from];
        d->delayAt = (at + 1) & d->delayMask;
        d->frame += 1;

        if (gain == 1 && d->quietRun >= idleAfter) {
            // Nothing in either window asks for anything: the samples pass as they are.
            left[n] = outLeft;
            right[n] = outRight;
            continue;
        }
        if (d->quietRun >= idleAfter) {
            // Waking up: both windows hold nothing but "no reduction".
            d->queueHead = 0;
            d->queueCount = 0;
            memset(d->deficit, 0, sizeof(double) * lookAhead);
            d->deficitSum = 0;
            d->deficitNonZero = 0;
            d->deficitAt = 0;
        }
        d->quietRun = gain == 1 ? d->quietRun + 1 : 0;

        // The lowest gain asked for over the last `window` frames.
        while (d->queueCount > 0) {
            uint32_t back = (d->queueHead + d->queueCount - 1) & d->queueMask;
            if (d->queueValue[back] < gain) break;
            d->queueCount -= 1;
        }
        uint32_t slot = (d->queueHead + d->queueCount) & d->queueMask;
        d->queueValue[slot] = gain;
        d->queueFrame[slot] = d->frame;
        d->queueCount += 1;
        if (d->queueFrame[d->queueHead] + window <= d->frame) {
            d->queueHead = (d->queueHead + 1) & d->queueMask;
            d->queueCount -= 1;
        }
        double held = d->queueValue[d->queueHead];

        // Its average over the last `lookAhead` frames: the level slides down ahead of a peak instead of jumping.
        double missing = 1 - held;
        double leaving = d->deficit[d->deficitAt];
        d->deficit[d->deficitAt] = missing;
        d->deficitAt = d->deficitAt + 1 == lookAhead ? 0 : d->deficitAt + 1;
        if (leaving != 0) d->deficitNonZero -= 1;
        if (missing != 0) d->deficitNonZero += 1;
        d->deficitSum = d->deficitNonZero == 0 ? 0 : d->deficitSum + missing - leaving;
        double applied = 1 - d->deficitSum / lookAhead;
        if (applied > 1) applied = 1;
        if (applied < 0) applied = 0;

        if (applied < 1) {
            outLeft = (float)(outLeft * applied);
            outRight = (float)(outRight * applied);
            if (applied < lowest) lowest = applied;
        }
        left[n] = outLeft;
        right[n] = outRight;
    }
    return lowest;
}

static void keep_highest(_Atomic float *slot, float value) {
    if (value > atomic_load_explicit(slot, memory_order_relaxed)) atomic_store_explicit(slot, value, memory_order_relaxed);
}

bool boom_dsp_process(BoomDSP *d, float *left, float *right, uint32_t frames, bool inputSilent) {
    take_wanted(d);
    d->started = true;

    // After a long silence everything has run out: nothing to compute until sound returns.
    if (inputSilent) {
        d->silentFrames += frames;
        if (d->silentFrames > (uint64_t)(2 * d->sampleRate) && !d->gliding) {
            if (!d->drained) {
                memset(d->states, 0, sizeof d->states);
                memset(d->delayLeft, 0, sizeof(float) * (d->delayMask + 1));
                memset(d->delayRight, 0, sizeof(float) * (d->delayMask + 1));
                d->envelopeFast = d->envelopeSlow = 0;
                d->quietRun = (uint64_t)d->window + d->lookAhead;
                atomic_store_explicit(&d->rmsLeft, 0, memory_order_relaxed);
                atomic_store_explicit(&d->rmsRight, 0, memory_order_relaxed);
                d->squaresLeft = d->squaresRight = 0;
                d->squaresCount = 0;
                d->drained = true;
            }
            uint32_t at = atomic_load_explicit(&d->analysisAt, memory_order_relaxed);
            for (uint32_t n = 0; n < frames; n++) d->analysis[(at + n) & (ANALYSIS_RING - 1)] = 0;
            atomic_store_explicit(&d->analysisAt, (at + frames) & (ANALYSIS_RING - 1), memory_order_release);
            return true;
        }
    } else {
        d->silentFrames = 0;
        d->drained = false;
    }

    float peakLeft = 0, peakRight = 0;
    double lowest = 1;
    uint32_t analysisAt = atomic_load_explicit(&d->analysisAt, memory_order_relaxed);
    for (uint32_t done = 0; done < frames; done += SUB) {
        uint32_t count = frames - done < SUB ? frames - done : SUB;
        float *l = left + done, *r = right + done;
        shape(d, l, r, count);
        double gain = limit(d, l, r, count);
        if (gain < lowest) lowest = gain;
        for (uint32_t n = 0; n < count; n++) {
            float a = fabsf(l[n]), b = fabsf(r[n]);
            if (a > peakLeft) peakLeft = a;
            if (b > peakRight) peakRight = b;
            d->squaresLeft += (double)l[n] * l[n];
            d->squaresRight += (double)r[n] * r[n];
            d->analysis[(analysisAt + n) & (ANALYSIS_RING - 1)] = 0.5f * (l[n] + r[n]);
        }
        analysisAt = (analysisAt + count) & (ANALYSIS_RING - 1);
        d->squaresCount += count;
        if (d->squaresCount >= 960) {
            atomic_store_explicit(&d->rmsLeft, (float)sqrt(d->squaresLeft / d->squaresCount), memory_order_relaxed);
            atomic_store_explicit(&d->rmsRight, (float)sqrt(d->squaresRight / d->squaresCount), memory_order_relaxed);
            d->squaresLeft = d->squaresRight = 0;
            d->squaresCount = 0;
        }
    }
    atomic_store_explicit(&d->analysisAt, analysisAt, memory_order_release);
    keep_highest(&d->peakLeft, peakLeft);
    keep_highest(&d->peakRight, peakRight);
    if (lowest < 1) keep_highest(&d->reduction, (float)(-20 * log10(lowest > 1e-6 ? lowest : 1e-6)));
    return false;
}

void boom_dsp_set_head_turn(BoomDSP *d, float radians) {
    atomic_store_explicit(&d->headTurn, isfinite(radians) ? radians : 0, memory_order_relaxed);
}

void boom_dsp_take_levels(BoomDSP *d, BoomLevels *levels) {
    levels->peak_left = atomic_exchange_explicit(&d->peakLeft, 0, memory_order_relaxed);
    levels->peak_right = atomic_exchange_explicit(&d->peakRight, 0, memory_order_relaxed);
    levels->rms_left = atomic_load_explicit(&d->rmsLeft, memory_order_relaxed);
    levels->rms_right = atomic_load_explicit(&d->rmsRight, memory_order_relaxed);
    levels->gain_reduction_db = atomic_exchange_explicit(&d->reduction, 0, memory_order_relaxed);
}

// ---- The spectrum ----------------------------------------------------------------------------------------------

/// An in-place fast Fourier transform of FFT_SIZE points.
static void transform(BoomDSP *d) {
    float *real = d->fftReal, *imaginary = d->fftImaginary;
    for (uint32_t i = 1, j = 0; i < FFT_SIZE; i++) {
        uint32_t bit = FFT_SIZE >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) {
            float t = real[i]; real[i] = real[j]; real[j] = t;
            t = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = t;
        }
    }
    for (uint32_t length = 2; length <= FFT_SIZE; length <<= 1) {
        uint32_t half = length >> 1, stride = FFT_SIZE / length;
        for (uint32_t start = 0; start < FFT_SIZE; start += length) {
            for (uint32_t k = 0; k < half; k++) {
                float c = d->fftCos[k * stride], s = -d->fftSin[k * stride];
                uint32_t a = start + k, b = a + half;
                float tr = real[b] * c - imaginary[b] * s;
                float ti = real[b] * s + imaginary[b] * c;
                real[b] = real[a] - tr;
                imaginary[b] = imaginary[a] - ti;
                real[a] += tr;
                imaginary[a] += ti;
            }
        }
    }
}

void boom_dsp_spectrum(BoomDSP *d, float *decibels, uint32_t bands, double lowHz, double highHz) {
    if (bands == 0) return;
    uint32_t end = atomic_load_explicit(&d->analysisAt, memory_order_acquire);
    for (uint32_t i = 0; i < FFT_SIZE; i++) {
        d->fftReal[i] = d->analysis[(end + ANALYSIS_RING - FFT_SIZE + i) & (ANALYSIS_RING - 1)] * d->fftWindow[i];
        d->fftImaginary[i] = 0;
    }
    transform(d);
    // A full-scale sine reads 1 (the window halves it; the transform spreads it over two mirrored bins).
    const float scale = 4.0f / FFT_SIZE;
    const uint32_t bins = FFT_SIZE / 2;
    for (uint32_t i = 0; i < bins; i++) {
        d->fftReal[i] = sqrtf(d->fftReal[i] * d->fftReal[i] + d->fftImaginary[i] * d->fftImaginary[i]) * scale;
    }
    const double binHz = d->sampleRate / FFT_SIZE;
    if (!(lowHz > 0)) lowHz = 20;
    if (!(highHz > lowHz)) highHz = lowHz * 1000;
    const double ratio = highHz / lowHz;
    for (uint32_t band = 0; band < bands; band++) {
        double from = lowHz * pow(ratio, (double)band / bands) / binHz;
        double to = lowHz * pow(ratio, (double)(band + 1) / bands) / binHz;
        uint32_t first = (uint32_t)ceil(from), last = (uint32_t)floor(to);
        float level = 0;
        if (first <= last && first < bins) {
            // The loudest line inside the band.
            for (uint32_t i = first; i <= last && i < bins; i++) if (d->fftReal[i] > level) level = d->fftReal[i];
        } else {
            // The band is narrower than one line: read between the two nearest.
            double centre = (from + to) / 2;
            uint32_t below = (uint32_t)floor(centre);
            if (below + 1 < bins) {
                double share = centre - below;
                level = (float)(d->fftReal[below] * (1 - share) + d->fftReal[below + 1] * share);
            }
        }
        decibels[band] = level > 1e-6f ? 20 * log10f(level) : -120;
    }
}
