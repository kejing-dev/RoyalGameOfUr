"""q1-2 ring colour check: in a board screenshot, find the pixels of each highlight colour family and report their hue
(and L* for cream). A ring passes when its pixels sit within 15° of the design hue (cream: L* >= 85) and the ring is
not dark (median L* of the ring pixels >= 45).

  python3 tools/ringhue.py SHOT.png [...]"""
import colorsys, sys
import numpy as np
from PIL import Image

DESIGN = {"blue #3F7FD9": (0x3F, 0x7F, 0xD9), "orange #D8842A": (0xD8, 0x84, 0x2A), "gold #E0B040": (0xE0, 0xB0, 0x40), "amber #E0A040": (0xE0, 0xA0, 0x40)}


def hue(rgb):
    return colorsys.rgb_to_hsv(*[c / 255 for c in rgb])[0] * 360


def lstar(rgb):
    def f(c):
        c /= 255
        return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
    y = 0.2126 * f(rgb[0]) + 0.7152 * f(rgb[1]) + 0.0722 * f(rgb[2])
    return 116 * (y ** (1 / 3) if y > 0.008856 else 7.787 * y + 16 / 116) - 16


for path in sys.argv[1:]:
    im = np.asarray(Image.open(path).convert("RGB")).reshape(-1, 3).astype(float)
    mx, mn = im.max(1), im.min(1)
    sat = (mx - mn) / np.maximum(mx, 1)
    sat = np.where(mx > 165, sat, 0)  # bright colours only: the lapis tile patterns (V 0.48) are not rings
    hsv_h = np.array([hue(p) for p in im[sat > 0.45][:200000]]) if (sat > 0.45).any() else np.array([])
    sel = im[sat > 0.45][:200000]
    out = []
    for name, rgb in DESIGN.items():
        h0 = hue(rgb)
        d = np.abs((hsv_h - h0 + 180) % 360 - 180)
        m = d <= 15
        if m.sum() < 80:
            out.append(f"{name}: none"); continue
        px = sel[m]
        med = np.median(px, axis=0)
        out.append(f"{name}: {int(m.sum())} px, median hue {hue(med):.0f}° (design {h0:.0f}°), L* {lstar(med):.0f}")
    print(path.split('/')[-1] + "\n  " + "\n  ".join(out))
