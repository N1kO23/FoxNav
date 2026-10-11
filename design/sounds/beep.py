"""Generates "Beep", the modern over-limit sound: three quick, even beeps.

Like the overspeed warning of a modern dashboard: one pitch, square-ish (a sine with odd
harmonics) so it cuts through road noise, and lower and steadier than the camera alert's rising
pips (alert.py), so the two never get confused. Writes a 16-bit mono WAV:

    python3 design/sounds/beep.py android/app/src/main/res/raw/beep.wav
"""
import math
import struct
import sys
import wave

RATE = 44_100
PITCH = 1318.5
BEEPS = [0.0, 0.16, 0.32]
BEEP = 0.1
ATTACK = 0.004
RELEASE = 0.012
LENGTH = 0.46


def sample(t: float) -> float:
    value = 0.0
    for start in BEEPS:
        local = t - start
        if local < 0 or local > BEEP:
            continue
        envelope = min(1.0, local / ATTACK, (BEEP - local) / RELEASE)
        phase = 2 * math.pi * PITCH * local
        value += envelope * (math.sin(phase) + math.sin(3 * phase) / 3 * 0.5 + math.sin(5 * phase) / 5 * 0.25)
    return value


def main(path: str) -> None:
    samples = [sample(i / RATE) for i in range(int(LENGTH * RATE))]
    peak = max(abs(s) for s in samples)
    scale = 0.6 / peak
    with wave.open(path, "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(RATE)
        out.writeframes(b"".join(struct.pack("<h", int(s * scale * 32767)) for s in samples))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "beep.wav")
