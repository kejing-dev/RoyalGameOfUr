"""D51 evidence: render what the app plays when the user taps pieces fast (select / switch x5 + cancel), with the
SoundPlayer rules: one instance per event (a new select stops the previous one), ±5 % pitch, variant rotation, and the
mix headroom (summed peaks of sounding streams <= -1 dBFS). Rendered from the app's own WAVs at the default volume,
not recorded (no audio loopback from the emulator on this Mac).

  python3 tools/select_burst.py OUT.wav [--with-place]   # --with-place: the burst starts while a piece lands (worst case mix)
"""
import math, os, random, struct, sys, wave
import numpy as np

SND = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app", "src", "main", "assets", "sounds")
RATE = 22050
CEILING = 0.891


def load(n):
    with wave.open(os.path.join(SND, n + ".wav")) as w:
        return np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(float) / 32768


def resample(x, r):
    idx = np.arange(0, len(x) - 1, r)
    return np.interp(idx, np.arange(len(x)), x)


rnd = random.Random(11)
buf = np.zeros(int(RATE * 1.6))
active = []  # (start, end, peak, event, slice)
events = []
if "--with-place" in sys.argv:
    events.append((0.00, "place", ["place", "place_2", "place_3"]))
    events.append((0.02, "land", ["land", "land_2", "land_3", "land_4"]))
for i, t in enumerate([0.05, 0.17, 0.29, 0.41, 0.53]):
    events.append((t, "select", ["select", "select_2"]))
events.append((0.65, "select", ["select", "select_2"]))  # cancel = the same sound
playing = {}
last_v = {}
for t, ev, variants in events:
    v = rnd.randrange(len(variants))
    if v == last_v.get(ev) and len(variants) > 1: v = (v + 1) % len(variants)
    last_v[ev] = v
    x = resample(load(variants[v]), 0.95 + rnd.random() * 0.10)
    s0 = int(t * RATE)
    if ev in playing:  # stop the previous instance of the same event (5 ms fade, like SoundPool.stop)
        ps, pe = playing.pop(ev)
        cut = min(pe, s0)
        k = int(0.005 * RATE)
        if cut < pe:
            seg = buf[cut:cut + k]  # nothing to do: the stopped stream is simply not added past the cut
        active = [a for a in active if not (a[3] == ev)]
    others = sum(a[2] for a in active if a[1] > s0)
    peak = np.abs(x).max()
    gain = 1.0 if peak <= 0 else max(0.15, min(1.0, (CEILING - others) / peak))
    y = x * gain
    end = min(len(buf), s0 + len(y))
    nxt = [tt for tt, ee, _ in events if ee == ev and tt > t]
    stop = int(nxt[0] * RATE) if nxt else end
    stop = min(stop, end)
    seg = y[:stop - s0].copy()
    if stop < end:
        k = min(len(seg), int(0.005 * RATE)); seg[-k:] *= np.linspace(1, 0, k)
    buf[s0:s0 + len(seg)] += seg
    playing[ev] = (s0, stop)
    active.append((s0, stop, peak * gain, ev, None))
with wave.open(sys.argv[1], "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
    w.writeframes((np.clip(buf, -1, 1) * 32767).astype("<i2").tobytes())
pk = np.abs(buf).max()
print("peak %.1f dBFS (%s)" % (20 * math.log10(max(pk, 1e-9)), "with a landing piece" if "--with-place" in sys.argv else "select / switch x5 + cancel"))
