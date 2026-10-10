"""Generates the over-limit chime, an original take on the JDM "kin-kon" speed chime.

Two bell strikes, high then low, each made of three slightly inharmonic sine partials that
decay exponentially: the same synthesis as the design mockup. Writes a 16-bit mono WAV:

    python3 design/sounds/kinkon.py android/app/src/main/res/raw/kinkon.wav
"""
import math
import struct
import sys
import wave

RATE = 44_100
# (frequency ratio, level, decay seconds) for one bell strike
PARTIALS = [(1.0, 0.22, 1.4), (2.76, 0.08, 0.8), (5.4, 0.04, 0.4)]
# (start seconds, fundamental Hz): "kin", then "kon"
STRIKES = [(0.0, 1318.5), (0.34, 1046.5)]
ATTACK = 0.004
LENGTH = 1.9


def sample(t: float) -> float:
    value = 0.0
    for start, pitch in STRIKES:
        local = t - start
        if local < 0:
            continue
        for ratio, level, decay in PARTIALS:
            if local > decay:
                continue
            envelope = local / ATTACK if local < ATTACK else math.exp(math.log(0.0001) * (local - ATTACK) / (decay - ATTACK))
            value += level * envelope * math.sin(2 * math.pi * pitch * ratio * local)
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
    main(sys.argv[1] if len(sys.argv) > 1 else "kinkon.wav")
