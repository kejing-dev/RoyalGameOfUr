"""Physical sounds from Kenney CC0 packs (casino-audio / impact-sounds), profile B20 + ui_gates sound-source.

  python3 tools/import_cc0_sounds.py [~/Downloads/cc0-sounds]

Decodes the .ogg files with macOS afconvert (22.05 kHz mono), trims silence, low-passes the harsh top (6.5 kHz,
two passes), fades the tail, cuts long ones, normalises to -1 dBFS peak and writes app/src/main/assets/sounds/<name>[_n].wav.
Variants: 2-4 recordings per event; the app adds a ±5 % random pitch per play (SoundPlayer). The UI cues (illegal,
nomove, rosette, win, lose) stay synthesised by tools/make_sounds.py.
"""
import math, os, subprocess, sys, tempfile, wave
import numpy as np

SRC = os.path.expanduser(sys.argv[1] if len(sys.argv) > 1 else "~/Downloads/cc0-sounds")
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app", "src", "main", "assets", "sounds")
RATE = 22050

# event -> (max seconds, [pack/file, ...])
MAP = {
    "shake": (0.55, ["kenney_casino-audio/Audio/dice-shake-1.ogg", "kenney_casino-audio/Audio/dice-shake-2.ogg", "kenney_casino-audio/Audio/dice-shake-3.ogg"]),
    "throw": (1.0, ["kenney_casino-audio/Audio/dice-throw-1.ogg", "kenney_casino-audio/Audio/dice-throw-2.ogg", "kenney_casino-audio/Audio/dice-throw-3.ogg"]),
    "land": (0.45, ["kenney_casino-audio/Audio/die-throw-1.ogg", "kenney_casino-audio/Audio/die-throw-2.ogg", "kenney_casino-audio/Audio/die-throw-3.ogg", "kenney_casino-audio/Audio/die-throw-4.ogg"]),
    "select": (0.3, ["kenney_casino-audio/Audio/chips-handle-1.ogg", "kenney_casino-audio/Audio/chips-handle-2.ogg", "kenney_casino-audio/Audio/chips-handle-3.ogg"]),
    "place": (0.4, ["kenney_casino-audio/Audio/chip-lay-1.ogg", "kenney_casino-audio/Audio/chip-lay-2.ogg", "kenney_casino-audio/Audio/chip-lay-3.ogg"]),
    "capture": (0.6, ["kenney_casino-audio/Audio/chips-collide-1.ogg", "kenney_casino-audio/Audio/chips-collide-2.ogg", "kenney_casino-audio/Audio/chips-collide-3.ogg"]),
    "off": (0.6, ["kenney_casino-audio/Audio/chips-stack-1.ogg", "kenney_casino-audio/Audio/chips-stack-2.ogg", "kenney_casino-audio/Audio/chips-stack-3.ogg"]),
}


def decode(path):
    with tempfile.TemporaryDirectory() as d:
        out = os.path.join(d, "x.wav")
        subprocess.run(["afconvert", "-f", "WAVE", "-d", "LEI16@%d" % RATE, "-c", "1", path, out], check=True)
        with wave.open(out) as w:
            return np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(float) / 32768


def lowpass(x, f, passes=2):
    w = 2 * math.pi * f / RATE; cw, sw = math.cos(w), math.sin(w); alpha = sw / (2 * 0.707)
    b = [(1 - cw) / 2, 1 - cw, (1 - cw) / 2]; a = [1 + alpha, -2 * cw, 1 - alpha]
    b = [v / a[0] for v in b]; a1, a2 = a[1] / a[0], a[2] / a[0]
    for _ in range(passes):
        y = np.zeros_like(x); x1 = x2 = y1 = y2 = 0.0
        for i, v in enumerate(x):
            o = b[0] * v + b[1] * x1 + b[2] * x2 - a1 * y1 - a2 * y2
            x2, x1, y2, y1 = x1, v, y1, o; y[i] = o
        x = y
    return x


def process(x, max_s):
    thr = 10 ** (-45 / 20)
    idx = np.where(np.abs(x) > thr)[0]
    if len(idx): x = x[max(0, idx[0] - 40):idx[-1] + 1]
    x = x[:int(max_s * RATE)]
    x = lowpass(x, 6500)
    k = min(len(x), int(0.04 * RATE)); x[-k:] *= np.linspace(1, 0, k)
    k = min(len(x), int(0.003 * RATE)); x[:k] *= np.linspace(0, 1, k)
    return x * (10 ** (-1 / 20) / max(1e-9, np.abs(x).max()))


def write(name, x):
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())


for event, (max_s, files) in MAP.items():
    for old in [f for f in os.listdir(OUT) if f.startswith(event) and f.endswith(".wav")]:
        os.remove(os.path.join(OUT, old))
    for i, rel in enumerate(files):
        name = event if i == 0 else f"{event}_{i + 1}"
        write(name, process(decode(os.path.join(SRC, rel)), max_s))
        print(f"{name}.wav <- Kenney {rel}")
