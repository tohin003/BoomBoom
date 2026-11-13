#!/usr/bin/env python3
"""Makes Packages/BoomEngine/Sources/CBoomAudio/BoomHRIR.c from MIT's KEMAR measurements.

    python3 -I Tools/HRIR/make_hrir.py <folder with the unpacked "diffuse" set> <output .c file>

The measurements: Bill Gardner and Keith Martin, "HRTF Measurements of a KEMAR Dummy-Head Microphone", MIT Media Lab,
1994 (https://sound.media.mit.edu/resources/KEMAR.html), the diffuse-field equalised set, free to use provided the
authors are cited. Only the horizontal plane is taken (elevation 0, azimuth 0 to 180 degrees in steps of 5), each
response resampled from 44.1 kHz to the engine's 48 kHz.
"""
import math, os, struct, sys

SOURCE_RATE, RATE, TAPS, STEP = 44100, 48000, 144, 5

def read(path):
    data = open(path, "rb").read()
    at = data.index(b"data")
    size = struct.unpack("<I", data[at + 4:at + 8])[0]
    samples = struct.unpack("<%dh" % (size // 2), data[at + 8:at + 8 + size])
    return [s / 32768 for s in samples[0::2]], [s / 32768 for s in samples[1::2]]

def bessel0(x):
    total, term, k = 1.0, 1.0, 1
    while True:
        term *= (x / (2 * k)) ** 2
        total += term
        k += 1
        if term < 1e-16 * total:
            return total

def resample(x):
    """The band-limited wave through the samples of x, read off at the new rate (a windowed sinc, 24 samples a side)."""
    half, beta, out = 24, 9.0, []
    for m in range(TAPS):
        t = m * SOURCE_RATE / RATE
        total = 0.0
        for n in range(max(0, int(t) - half), min(len(x), int(t) + half + 1)):
            d = t - n
            if abs(d) >= half:
                continue
            sinc = 1.0 if abs(d) < 1e-12 else math.sin(math.pi * d) / (math.pi * d)
            total += x[n] * sinc * bessel0(beta * math.sqrt(1 - (d / half) ** 2)) / bessel0(beta)
        out.append(total)
    return out

def main():
    folder, target = sys.argv[1], sys.argv[2]
    rows = []
    for azimuth in range(0, 181, STEP):
        left, right = read(os.path.join(folder, "H0e%03da.wav" % azimuth))
        rows.append((azimuth, resample(left), resample(right)))
    # One scale for the whole set: a sound straight ahead keeps its level.
    front = rows[0]
    scale = 1 / math.sqrt(sum(v * v for v in front[1]))
    lines = [
        "// Made by Tools/HRIR/make_hrir.py. Do not edit.",
        "//",
        "// Head-related impulse responses of a KEMAR dummy head in the horizontal plane: how a sound from a given",
        "// direction reaches the near and the far ear. From Bill Gardner and Keith Martin, \"HRTF Measurements of a",
        "// KEMAR Dummy-Head Microphone\", MIT Media Lab, 1994 (the diffuse-field equalised set), which is free to use",
        "// provided the authors are cited; resampled here from 44.1 kHz to 48 kHz.",
        "//",
        "// boom_hrir[i] is for a source i * 5 degrees to the RIGHT of straight ahead (0 = ahead, 18 = to the right,",
        "// 36 = behind): [0] is the left (far) ear, [1] the right (near) ear. For a source to the left, swap the ears.",
        '#include "BoomHRIR.h"',
        "",
        "const float boom_hrir[BOOM_HRIR_DIRECTIONS][2][BOOM_HRIR_TAPS] = {",
    ]
    for azimuth, left, right in rows:
        lines.append("    {   // %d degrees" % azimuth)
        for ear in (left, right):
            values = ["%.8ef" % (v * scale) for v in ear]
            lines.append("        {")
            for i in range(0, len(values), 6):
                lines.append("            " + ", ".join(values[i:i + 6]) + ",")
            lines.append("        },")
        lines.append("    },")
    lines.append("};")
    open(target, "w").write("\n".join(lines) + "\n")
    print("wrote", target, "with", len(rows), "directions of", TAPS, "taps")

main()
