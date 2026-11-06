"""Royal Game of Ur icon (design-package appIcon): shell-cream #F1E6CF disc, a lapis #27437A eight-petal rosette with a
red-jasper #B5412F heart, a black pyramid die with its white tip up at the lower right. No text.

  python3 tools/build_icon.py [--icon512 out.png] [--preview out.png]

Writes res/drawable/icon_3d_layer_{0,1,2}.png + icon_3d_sdf_{0,1,2}.png (PICO layered icon), mipmap-xxxhdpi/
ic_spatial_launcher.png and the adaptive foreground vector res/drawable/ic_launcher_fg.xml (same polygons).
"""
import math
import os
import sys
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
S, SS = 1024, 4
CREAM = (0xF1, 0xE6, 0xCF)
LAPIS = (0x27, 0x43, 0x7A)
JASPER = (0xB5, 0x41, 0x2F)
BLACK = (0x1C, 0x1A, 0x17)
DARK = (0x3A, 0x35, 0x30)
TIP = (0xF5, 0xF0, 0xE6)
C, R, PW = (47.0, 47.0), 25.0, 7.6


def petals():
    out = []
    for i in range(8):
        a = i * math.pi / 4 + math.pi / 8
        pts = []
        for t in list(range(13)) + list(range(12, -1, -1)):
            pass
        side = []
        for t in range(13):
            u = t / 12.0
            rr = R * u; w = math.sin(u * math.pi) * PW
            side.append((C[0] + math.cos(a) * rr - math.sin(a) * w, C[1] + math.sin(a) * rr + math.cos(a) * w))
        back = []
        for t in range(12, -1, -1):
            u = t / 12.0
            rr = R * u; w = math.sin(u * math.pi) * PW
            back.append((C[0] + math.cos(a) * rr + math.sin(a) * w, C[1] + math.sin(a) * rr - math.cos(a) * w))
        out.append(side + back)
    return out


def circle(cx, cy, r, n=48):
    return [(cx + r * math.cos(2 * math.pi * i / n), cy + r * math.sin(2 * math.pi * i / n)) for i in range(n)]


TOP, BL, BR, MID = (76.0, 58.0), (58.0, 90.0), (94.0, 90.0), (80.0, 90.0)
DIE_LEFT = [TOP, BL, MID]
DIE_RIGHT = [TOP, MID, BR]
K = 0.3
DIE_TIP = [TOP, (TOP[0] + (BL[0] - TOP[0]) * K, TOP[1] + (BL[1] - TOP[1]) * K), (TOP[0] + (MID[0] - TOP[0]) * K, TOP[1] + (MID[1] - TOP[1]) * K),
           (TOP[0] + (BR[0] - TOP[0]) * K, TOP[1] + (BR[1] - TOP[1]) * K)]

RING_OUT, RING_IN = circle(C[0], C[1], R + 4.5), circle(C[0], C[1], R + 2.5)
HEART = circle(C[0], C[1], 6.0)


def big(draw_fn):
    im = Image.new("RGBA", (S * SS, S * SS), (0, 0, 0, 0))
    draw_fn(ImageDraw.Draw(im), S * SS / 108.0)
    return im.resize((S, S), Image.LANCZOS)


def sc(pts, k):
    return [(x * k, y * k) for x, y in pts]


def layer_bg():
    return big(lambda dr, k: dr.ellipse((0, 0, 108 * k - 1, 108 * k - 1), fill=CREAM + (255,)))


def layer_rosette():
    def d(dr, k):
        dr.polygon(sc(RING_OUT, k), fill=LAPIS + (255,))
        dr.polygon(sc(RING_IN, k), fill=(0, 0, 0, 0))
        for p in petals():
            dr.polygon(sc(p, k), fill=LAPIS + (255,))
        dr.polygon(sc(HEART, k), fill=JASPER + (255,))
    return big(d)


def layer_die():
    def d(dr, k):
        dr.polygon(sc(DIE_LEFT, k), fill=DARK + (255,))
        dr.polygon(sc(DIE_RIGHT, k), fill=BLACK + (255,))
        dr.polygon(sc(DIE_TIP, k), fill=TIP + (255,))
    return big(d)


def sdf(layer):
    inside = layer.getchannel("A").point(lambda v: 255 if v >= 128 else 0)
    dist = Image.new("L", inside.size, 0)
    cur = inside
    for i in range(1, 33):
        dist.paste(min(255, i * 8), mask=cur)
        cur = cur.filter(ImageFilter.MinFilter(3) if i % 2 else ImageFilter.Kernel((3, 3), (0, 1, 0, 1, 1, 1, 0, 1, 0), 5))
        cur = cur.point(lambda v: 255 if v >= 255 else 0)
    dist = Image.composite(dist, Image.new("L", inside.size, 0), inside)
    return Image.merge("RGBA", (dist, dist, dist, dist))


def path(pts):
    return "M" + "L".join("%.2f,%.2f" % p for p in pts) + "Z"


def vector():
    hexc = lambda c: "#FF%02X%02X%02X" % c
    ring = path(RING_OUT) + path(list(reversed(RING_IN)))
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<!-- Royal Game of Ur launcher foreground (tools/build_icon.py draws the same artwork for the PICO layered icon). -->',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">',
        '    <path android:fillColor="%s" android:fillType="evenOdd" android:pathData="%s" />' % (hexc(LAPIS), ring),
        '    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(LAPIS), "".join(path(p) for p in petals())),
        '    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(JASPER), path(HEART)),
        '    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(DARK), path(DIE_LEFT)),
        '    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(BLACK), path(DIE_RIGHT)),
        '    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(TIP), path(DIE_TIP)),
        '</vector>',
    ]
    open(os.path.join(RES, "drawable", "ic_launcher_fg.xml"), "w").write("\n".join(lines) + "\n")


def main():
    layers = [layer_bg(), layer_rosette(), layer_die()]
    for i, layer in enumerate(layers):
        layer.save(os.path.join(RES, "drawable", f"icon_3d_layer_{i}.png"))
        sdf(layer).save(os.path.join(RES, "drawable", f"icon_3d_sdf_{i}.png"))
    flat = Image.new("RGBA", layers[0].size, (0, 0, 0, 0))
    for l in layers:
        flat.alpha_composite(l)
    flat.save(os.path.join(RES, "mipmap-xxxhdpi", "ic_spatial_launcher.png"))
    vector()
    if "--icon512" in sys.argv:
        flat.resize((512, 512), Image.LANCZOS).save(sys.argv[sys.argv.index("--icon512") + 1])
    if "--preview" in sys.argv:
        w = 256
        sheet = Image.new("RGB", (w * 4, w), (60, 64, 70))
        for i, t in enumerate(layers + [flat]):
            r = t.resize((w, w), Image.LANCZOS)
            sheet.paste(r, (i * w, 0), r)
        sheet.save(sys.argv[sys.argv.index("--preview") + 1])
    print("ICON ok")


main()
