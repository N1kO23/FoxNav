"""Generates "Ding", the quieter over-limit sound: a single bell strike.

The kin-kon's bell (kinkon.py), struck once at the "kon" pitch, for drivers who want to know
they're over without the full chime. Writes a 16-bit mono WAV:

    python3 design/sounds/ding.py android/app/src/main/res/raw/ding.wav
"""
import math
import struct
import sys
import wave

from kinkon import ATTACK, PARTIALS, RATE

PITCH = 1046.5
LENGTH = 1.5


def sample(t: float) -> float:
    value = 0.0
    for ratio, level, decay in PARTIALS:
        if t > decay:
            continue
        envelope = t / ATTACK if t < ATTACK else math.exp(math.log(0.0001) * (t - ATTACK) / (decay - ATTACK))
        value += level * envelope * math.sin(2 * math.pi * PITCH * ratio * t)
    return value


def main(path: str) -> None:
    samples = [sample(i / RATE) for i in range(int(LENGTH * RATE))]
    peak = max(abs(s) for s in samples)
    scale = 0.6 / peak  # a little under the kin-kon
    with wave.open(path, "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(RATE)
        out.writeframes(b"".join(struct.pack("<h", int(s * scale * 32767)) for s in samples))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "ding.wav")
