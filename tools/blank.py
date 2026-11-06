"""Largest continuous blank band of the control window in a screenshot (QA q1-3: <= 25 % of the window height).

  python3 tools/blank.py SHOT.png [...]      prints window box, blank band (px and % of window height) per file

The window is found as the big light, low-saturation rounded rectangle; a row is blank when its luminance spread
across the inner 90 % of the window width is tiny (no text, no control edge)."""
import sys
from PIL import Image
import numpy as np


def analyse(path):
    """Frontal shot: window width from the glass columns, height = width x 620 / 560, bottom = where the left padding
    strip turns dark (floor / bed below the window)."""
    im = np.asarray(Image.open(path).convert("RGB")).astype(float)
    lum = 0.2126 * im[..., 0] + 0.7152 * im[..., 1] + 0.0722 * im[..., 2]
    sat = im.max(axis=2) - im.min(axis=2)
    glass = (lum > 170) & (sat < 22)
    cols = glass.mean(axis=0)
    xs = np.where(cols > 0.3)[0]
    if len(xs) == 0:
        return None
    best = (xs[0], xs[0]); start = prev = xs[0]
    for v in xs[1:]:
        if v != prev + 1:
            if prev - start > best[1] - best[0]: best = (start, prev)
            start = v
        prev = v
    if prev - start > best[1] - best[0]: best = (start, prev)
    x0, x1 = int(best[0]), int(best[1])
    h = int((x1 - x0) * 620 / 560)
    strip = np.median(lum[:, x0 + 8:x0 + 24], axis=1)
    y = lum.shape[0] // 2
    dark = 0
    while y < lum.shape[0] - 1 and dark < 8:
        y += 1
        dark = dark + 1 if strip[y] < 150 else 0
    y1 = y - 8
    y0 = max(0, y1 - h)
    return fixed(path, (x0, y0, x1, y1))


def fixed(path, box):
    """Blank band inside a known window rectangle (frontal pose of tools/shots.py: same rect for every shot)."""
    x0, y0, x1, y1 = box
    im = np.asarray(Image.open(path).convert("RGB")).astype(float)
    lum = 0.2126 * im[..., 0] + 0.7152 * im[..., 1] + 0.0722 * im[..., 2]
    h = y1 - y0; w = x1 - x0
    inner = lum[y0 + 40:y1 - 40, x0 + int(w * 0.06):x1 - int(w * 0.06)]
    spread = inner.max(axis=1) - inner.min(axis=1)
    run = best = 0
    for b in spread < 22:
        run = run + 1 if b else 0
        best = max(best, run)
    return box, best, best / h


BOX = None
args = sys.argv[1:]
if args and args[0] == "--box":
    BOX = tuple(int(v) for v in args[1].split(",")); args = args[2:]
for p in args:
    r = fixed(p, BOX) if BOX else analyse(p)
    if r is None:
        print(p, "no window found"); continue
    box, px, frac = r
    print("%s  window=%s  blank=%dpx  %.0f%%" % (p.split('/')[-1], box, px, frac * 100))
