"""Generates the warning tone for cameras and reports ahead, for "a tone" instead of speech.

Two quick rising pips, clean sines with a soft attack and decay: shorter and higher than the
kin-kon, so a camera ahead never sounds like a speeding warning. Writes a 16-bit mono WAV:

    python3 design/sounds/alert.py android/app/src/main/res/raw/alert.wav
"""
import math
import struct
import sys
import wave

RATE = 44_100
# (start seconds, Hz, length seconds)
PIPS = [(0.0, 1567.98, 0.11), (0.14, 2093.0, 0.16)]
ATTACK = 0.006
LENGTH = 0.36


def sample(t: float) -> float:
    value = 0.0
    for start, pitch, length in PIPS:
        local = t - start
        if local < 0 or local > length:
            continue
        envelope = local / ATTACK if local < ATTACK else math.exp(-5 * (local - ATTACK) / length)
        value += 0.3 * envelope * (math.sin(2 * math.pi * pitch * local) + 0.15 * math.sin(4 * math.pi * pitch * local))
    return value


def main(path: str) -> None:
    samples = [sample(i / RATE) for i in range(int(LENGTH * RATE))]
    peak = max(abs(s) for s in samples)
    scale = 0.7 / peak  # about -3 dBFS
    with wave.open(path, "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(RATE)
        out.writeframes(b"".join(struct.pack("<h", int(s * scale * 32767)) for s in samples))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "alert.wav")
