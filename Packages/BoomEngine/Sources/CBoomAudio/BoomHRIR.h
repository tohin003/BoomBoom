#ifndef BOOM_HRIR_H
#define BOOM_HRIR_H

// The head-related impulse responses the spatial stage uses. See BoomHRIR.c for where they come from.
// (Not in include/: nothing outside this module needs them.)

#define BOOM_HRIR_DIRECTIONS 37   // 0 to 180 degrees to the right of straight ahead, in steps of 5
#define BOOM_HRIR_STEP_DEGREES 5
#define BOOM_HRIR_TAPS 144        // 3 ms at 48 kHz
#define BOOM_HRIR_RATE 48000

extern const float boom_hrir[BOOM_HRIR_DIRECTIONS][2][BOOM_HRIR_TAPS];

#endif
