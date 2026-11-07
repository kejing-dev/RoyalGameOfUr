"""Builds every model of the Royal Game of Ur volume as GLB, in Python + Pillow (no Blender on this machine).

  python3 tools/build_models.py [--test-f]      # --test-f: draw an "F↑" test glyph on every tile (D10 orientation check)

Writes app/src/main/assets/models/: board.glb, piece_near.glb, piece_far.glb, tower.glb, tray.glb, cup.glb, die.glb.
Numbers match domain/Layout.kt (design-package gameSpec / spatialLayout):
  board  box 0.53 x 0.03 x 0.23 m, origin on the box top centre (+X right, +Z toward the user); 20 tiles 5.6 cm,
         2 mm proud (rosettes 3 mm), pitch 6 cm, rows A z=-0.06, B 0, C +0.06, the A5-A6 / C5-C6 gaps left for the towers
  piece  disc dia 4.5 cm x 1.2 cm, domed top, origin at its centre; near = obsidian + white dots / ring, far = red jasper + cream
  tower  dark leather-wood base 5.5 x 2 x 5.5 cm, origin at its bottom centre
  tray   leather tray 20 x 1.5 x 12 cm, 8 mm rims, floor top at y = 5 mm, origin at its bottom centre
  cup    leather frustum dia 8 -> 7 cm, 7 cm tall, open top, 4 mm wall, origin at its bottom centre
  die    regular tetrahedron, edge 2.4 cm, centroid origin, vertices as domain/Dice.kt Tetra.VERTICES; v0 / v1 carry 5 mm white tips
All materials OPAQUE (profile D24 / B12).
"""
import io
import json
import math
import os
import random
import struct
import sys

from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "models")
TEST_F = "--test-f" in sys.argv

SHELL = (0xEF, 0xE4, 0xCC)
LAPIS = (0x27, 0x43, 0x7A)
JASPER = (0xB5, 0x41, 0x2F)
BITUMEN = (0x1C, 0x1A, 0x17)
LEATHER = (0x4A, 0x35, 0x24)
OBSIDIAN = (0x17, 0x16, 0x1A)
TIP = (0xF5, 0xF0, 0xE6)

W, D, T = 0.53, 0.23, 0.03
PITCH, TILE, TILE_H, ROS_H = 0.06, 0.056, 0.002, 0.001


def srgb_lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


class Glb:
    def __init__(self):
        self.bin = bytearray()
        self.views, self.accessors = [], []

    def _view(self, data, target=None):
        while len(self.bin) % 4:
            self.bin.append(0)
        off = len(self.bin)
        self.bin += data
        v = {"buffer": 0, "byteOffset": off, "byteLength": len(data)}
        if target:
            v["target"] = target
        self.views.append(v)
        return len(self.views) - 1

    def floats(self, items, n, minmax=False):
        flat = [c for it in items for c in it]
        view = self._view(struct.pack("<%df" % len(flat), *flat), 34962)
        acc = {"bufferView": view, "componentType": 5126, "count": len(items), "type": {2: "VEC2", 3: "VEC3"}[n]}
        if minmax:
            acc["min"] = [min(it[k] for it in items) for k in range(n)]
            acc["max"] = [max(it[k] for it in items) for k in range(n)]
        self.accessors.append(acc)
        return len(self.accessors) - 1

    def indices(self, idx):
        view = self._view(struct.pack("<%dI" % len(idx), *idx), 34963)
        self.accessors.append({"bufferView": view, "componentType": 5125, "count": len(idx), "type": "SCALAR"})
        return len(self.accessors) - 1

    def image(self, im):
        b = io.BytesIO()
        im.convert("RGB").save(b, "PNG", optimize=True)
        return self._view(b.getvalue())

    def write(self, path, gltf):
        gltf["asset"] = {"version": "2.0", "generator": "royal-ur tools/build_models.py"}
        gltf["buffers"] = [{"byteLength": len(self.bin)}]
        gltf["bufferViews"] = self.views
        gltf["accessors"] = self.accessors
        js = json.dumps(gltf, separators=(",", ":")).encode()
        while len(js) % 4:
            js += b" "
        while len(self.bin) % 4:
            self.bin.append(0)
        total = 12 + 8 + len(js) + 8 + len(self.bin)
        with open(path, "wb") as f:
            f.write(struct.pack("<III", 0x46546C67, 2, total))
            f.write(struct.pack("<II", len(js), 0x4E4F534A) + js)
            f.write(struct.pack("<II", len(self.bin), 0x004E4942) + bytes(self.bin))


class Mesh:
    """Positions / normals / uvs / indices, grouped in primitives by material index."""

    def __init__(self):
        self.pos, self.nor, self.uv = [], [], []
        self.prims = {}

    def tri(self, mat, a, b, c, n=None, uvs=((0, 0), (0, 0), (0, 0))):
        if n is None:
            u = [b[k] - a[k] for k in range(3)]; v = [c[k] - a[k] for k in range(3)]
            n = (u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0])
            l = math.sqrt(sum(x * x for x in n)) or 1.0
            n = tuple(x / l for x in n)
        base = len(self.pos)
        for p, t in zip((a, b, c), uvs):
            self.pos.append(tuple(p)); self.nor.append(tuple(n)); self.uv.append(tuple(t))
        self.prims.setdefault(mat, []).extend([base, base + 1, base + 2])

    def quad(self, mat, a, b, c, d, n=None, uvs=((0, 0), (1, 0), (1, 1), (0, 1))):
        """Counter-clockwise a-b-c-d seen from the front (normal side)."""
        self.tri(mat, a, b, c, n, (uvs[0], uvs[1], uvs[2]))
        self.tri(mat, a, c, d, n, (uvs[0], uvs[2], uvs[3]))

    def smooth(self, mat, verts, norms, uvs, idx):
        base = len(self.pos)
        self.pos += [tuple(v) for v in verts]; self.nor += [tuple(n) for n in norms]; self.uv += [tuple(t) for t in uvs]
        self.prims.setdefault(mat, []).extend(base + i for i in idx)

    def write(self, name, materials, images=()):
        g = Glb()
        pa = g.floats(self.pos, 3, minmax=True); na = g.floats(self.nor, 3); ta = g.floats(self.uv, 2)
        prims = [{"attributes": {"POSITION": pa, "NORMAL": na, "TEXCOORD_0": ta}, "indices": g.indices(idx), "material": m} for m, idx in sorted(self.prims.items())]
        gltf = {"scene": 0, "scenes": [{"nodes": [0]}], "nodes": [{"name": name, "mesh": 0}], "meshes": [{"name": name, "primitives": prims}], "materials": materials}
        if images:
            gltf["images"] = [{"bufferView": g.image(im), "mimeType": "image/png"} for im in images]
            gltf["textures"] = [{"source": i, "sampler": 0} for i in range(len(images))]
            gltf["samplers"] = [{"magFilter": 9729, "minFilter": 9987, "wrapS": 33071, "wrapT": 33071}]
        path = os.path.join(OUT, name + ".glb")
        g.write(path, gltf)
        tris = sum(len(i) for i in self.prims.values()) // 3
        print("%-16s %6d triangles %8d bytes" % (name + ".glb", tris, os.path.getsize(path)))


def mat(name, color=None, rough=0.6, tex=None, metal=0.0):
    pbr = {"metallicFactor": metal, "roughnessFactor": rough}
    if tex is not None:
        pbr["baseColorTexture"] = {"index": tex}
    if color is not None:
        pbr["baseColorFactor"] = [srgb_lin(c) for c in color] + [1.0]
    return {"name": name, "pbrMetallicRoughness": pbr, "alphaMode": "OPAQUE", "doubleSided": False}


def box(m, mi, x0, x1, y0, y1, z0, z1, uvf=None, sides=True, bottom=True):
    """Axis-aligned box faces; uvf(x, z) gives top uvs; sides take the uv of their top edge (streaks of the edge colour)."""
    uv = uvf or (lambda x, z: (0.5, 0.5))
    m.quad(mi, (x0, y1, z1), (x1, y1, z1), (x1, y1, z0), (x0, y1, z0), (0, 1, 0), (uv(x0, z1), uv(x1, z1), uv(x1, z0), uv(x0, z0)))
    if sides:
        m.quad(mi, (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1), (0, 0, 1), (uv(x0, z1), uv(x1, z1), uv(x1, z1), uv(x0, z1)))
        m.quad(mi, (x1, y0, z0), (x0, y0, z0), (x0, y1, z0), (x1, y1, z0), (0, 0, -1), (uv(x1, z0), uv(x0, z0), uv(x0, z0), uv(x1, z0)))
        m.quad(mi, (x1, y0, z1), (x1, y0, z0), (x1, y1, z0), (x1, y1, z1), (1, 0, 0), (uv(x1, z1), uv(x1, z0), uv(x1, z0), uv(x1, z1)))
        m.quad(mi, (x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0), (-1, 0, 0), (uv(x0, z0), uv(x0, z1), uv(x0, z1), uv(x0, z0)))
    if bottom:
        m.quad(mi, (x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1), (0, -1, 0), (uv(x0, z0), uv(x1, z0), uv(x1, z1), uv(x0, z1)))


# ---------------------------------------------------------------- board

ROSETTES = {(0, 1), (2, 1), (1, 4), (0, 7), (2, 7)}


def squares():
    for r in range(3):
        for c in range(1, 9):
            if r == 1 or c not in (5, 6):
                yield r, c


def pattern_of(r, c):
    if (r, c) in ROSETTES:
        return "rosette"
    if r == 1:
        return {1: "eyes", 2: "dots5", 3: "dots4", 5: "dots5", 6: "eyes", 7: "dots4", 8: "zig"}[c]
    return {2: "dots5", 3: "eyes", 4: "dots5", 8: "zig"}[c]


def draw_tile(dr, cx, cy, s, kind):
    """One tile face, s = side in px, centred at (cx, cy); up in the image = toward the far edge (-Z)."""
    h = s / 2
    dr.rectangle((cx - h, cy - h, cx + h, cy + h), fill=SHELL)
    k = s / 100.0
    if kind == "rosette":
        dr.ellipse((cx - 44 * k, cy - 44 * k, cx + 44 * k, cy + 44 * k), outline=LAPIS, width=max(2, int(4 * k)))
        for i in range(8):
            a = i * math.pi / 4
            pts = []
            for t in range(13):
                u = t / 12.0
                rr = 38 * k * u
                w = math.sin(u * math.pi) * 11 * k
                px, py = math.cos(a) * rr - math.sin(a) * w, math.sin(a) * rr + math.cos(a) * w
                pts.append((cx + px, cy + py))
            for t in range(12, -1, -1):
                u = t / 12.0
                rr = 38 * k * u
                w = math.sin(u * math.pi) * 11 * k
                px, py = math.cos(a) * rr + math.sin(a) * w, math.sin(a) * rr - math.cos(a) * w
                pts.append((cx + px, cy + py))
            dr.polygon(pts, fill=LAPIS)
        dr.ellipse((cx - 11 * k, cy - 11 * k, cx + 11 * k, cy + 11 * k), fill=JASPER, outline=SHELL, width=max(1, int(2 * k)))
    elif kind == "eyes":
        # four eyes, pupils toward the far edge: asymmetric on purpose (D10 orientation check)
        for ex, ey in ((-22, -22), (22, -22), (-22, 22), (22, 22)):
            x, y = cx + ex * k, cy + ey * k
            dr.ellipse((x - 15 * k, y - 15 * k, x + 15 * k, y + 15 * k), fill=SHELL, outline=BITUMEN, width=max(1, int(2 * k)))
            dr.ellipse((x - 8 * k, y - 13 * k, x + 8 * k, y + 3 * k), fill=LAPIS)
    elif kind == "dots5":
        for ex, ey in ((0, 0), (-26, -26), (26, -26), (-26, 26), (26, 26)):
            x, y = cx + ex * k, cy + ey * k
            dr.ellipse((x - 11 * k, y - 11 * k, x + 11 * k, y + 11 * k), fill=JASPER if (ex, ey) == (0, 0) else LAPIS)
    elif kind == "dots4":
        dr.rectangle((cx - 40 * k, cy - 40 * k, cx + 40 * k, cy + 40 * k), outline=LAPIS, width=max(2, int(3 * k)))
        for ex, ey in ((-20, -20), (20, -20), (-20, 20), (20, 20)):
            x, y = cx + ex * k, cy + ey * k
            dr.ellipse((x - 10 * k, y - 10 * k, x + 10 * k, y + 10 * k), fill=JASPER)
    elif kind == "zig":
        # the "twelve dots" squares at the ends: a lattice of small lapis dots in red frames
        dr.rectangle((cx - 42 * k, cy - 42 * k, cx + 42 * k, cy + 42 * k), outline=JASPER, width=max(2, int(3 * k)))
        for ix in range(4):
            for iy in range(3):
                x, y = cx + (-27 + ix * 18) * k, cy + (-20 + iy * 20) * k
                dr.ellipse((x - 5 * k, y - 5 * k, x + 5 * k, y + 5 * k), fill=LAPIS)
    if TEST_F:
        # "F" with an up arrow: up = far edge (-Z), the vertical bar on the left (+X to the right)
        lw = max(2, int(5 * k))
        dr.rectangle((cx - 30 * k, cy - 35 * k, cx + 30 * k, cy + 35 * k), fill=SHELL)
        dr.line((cx - 20 * k, cy + 30 * k, cx - 20 * k, cy - 30 * k), fill=JASPER, width=lw)
        dr.line((cx - 20 * k, cy - 30 * k, cx + 15 * k, cy - 30 * k), fill=JASPER, width=lw)
        dr.line((cx - 20 * k, cy, cx + 8 * k, cy), fill=JASPER, width=lw)
        dr.line((cx + 22 * k, cy + 30 * k, cx + 22 * k, cy - 20 * k), fill=LAPIS, width=lw)
        dr.polygon([(cx + 22 * k, cy - 34 * k), (cx + 15 * k, cy - 20 * k), (cx + 29 * k, cy - 20 * k)], fill=LAPIS)


def board_texture(px_per_m=3200):
    tw, th = int(W * px_per_m), int(D * px_per_m)
    im = Image.new("RGB", (tw, th), BITUMEN)
    dr = ImageDraw.Draw(im)
    rnd = random.Random(4)

    def to_px(x, z):
        return (x + W / 2) * px_per_m, (z + D / 2) * px_per_m

    # inlay frame: shell / lapis mosaic band between the box edge (5 mm in) and the playing field
    gx0, gz0 = to_px(-0.245, -0.095)
    gx1, gz1 = to_px(0.245, 0.095)
    ox0, oz0 = to_px(-W / 2 + 0.006, -D / 2 + 0.006)
    ox1, oz1 = to_px(W / 2 - 0.006, D / 2 - 0.006)
    step = 0.006 * px_per_m
    y = oz0
    i = 0
    while y < oz1:
        x = ox0
        j = 0
        while x < ox1:
            inside = gx0 - 2 < x < gx1 + 2 and gz0 - 2 < y < gz1 + 2
            if not inside:
                col = SHELL if (i + j) % 2 == 0 else LAPIS
                jitter = rnd.randint(-8, 8)
                col = tuple(max(0, min(255, c + jitter)) for c in col)
                dr.rectangle((x + 1, y + 1, x + step - 1, y + step - 1), fill=col)
            x += step
            j += 1
        y += step
        i += 1
    # the playing field: bitumen grout with the 20 tiles
    dr.rectangle((gx0, gz0, gx1, gz1), fill=BITUMEN)
    s = TILE * px_per_m
    for r, c in squares():
        x, z = (c - 4.5) * PITCH, (r - 1) * PITCH
        cx, cy = to_px(x, z)
        draw_tile(dr, cx, cy, s, pattern_of(r, c))
    # the tower gaps: a plain leather-brown pad so the tower bases sit on something
    for r in (0, 2):
        for c in (5, 6):
            cx, cy = to_px((c - 4.5) * PITCH, (r - 1) * PITCH)
            dr.rectangle((cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2), fill=(0x2C, 0x22, 0x19))
    # a darker 3 px rim line for the box sides
    dr.rectangle((0, 0, tw - 1, th - 1), outline=BITUMEN, width=3)
    return im.filter(ImageFilter.SMOOTH)


def build_board():
    m = Mesh()

    def uv(x, z):
        return ((x + W / 2) / W, (z + D / 2) / D)

    # box body: top at y = 0, sides take the outermost (dark) texture line
    box(m, 0, -W / 2, W / 2, -T, 0.0, -D / 2, D / 2, uvf=lambda x, z: uv(max(-W / 2 + 0.0005, min(W / 2 - 0.0005, x)), max(-D / 2 + 0.0005, min(D / 2 - 0.0005, z))))
    # tiles
    for r, c in squares():
        x, z = (c - 4.5) * PITCH, (r - 1) * PITCH
        h = TILE_H + (ROS_H if (r, c) in ROSETTES else 0.0)
        e = TILE / 2
        # side uvs use a point 1 mm inside the tile edge (shell colour)
        def tuv(px, pz, x=x, z=z):
            return uv(x + max(-e + 0.0012, min(e - 0.0012, px - x)), z + max(-e + 0.0012, min(e - 0.0012, pz - z)))
        box(m, 0, x - e, x + e, 0.0, h, z - e, z + e, uvf=tuv, bottom=False)
    m.write("board", [mat("Board", tex=0, rough=0.55)], [board_texture()])


# ---------------------------------------------------------------- pieces

def piece_texture(base, dots, ring):
    """256 x 320: the top view in the upper 256 x 256, the side band (base / ring / base) in the lower 64 rows."""
    im = Image.new("RGB", (256, 320), base)
    dr = ImageDraw.Draw(im)
    rnd = random.Random(sum(base))
    for _ in range(900):
        x, y = rnd.randint(0, 255), rnd.randint(0, 255)
        d = rnd.randint(-10, 10)
        dr.point((x, y), fill=tuple(max(0, min(255, c + d)) for c in base))
    c = 128
    for ex, ey in ((0, 0), (-50, -50), (50, -50), (-50, 50), (50, 50)):
        dr.ellipse((c + ex - 17, c + ey - 17, c + ex + 17, c + ey + 17), fill=dots)
    dr.ellipse((c - 118, c - 118, c + 118, c + 118), outline=ring, width=6)
    dr.rectangle((0, 256 + 22, 255, 256 + 42), fill=ring)
    return im.filter(ImageFilter.SMOOTH)


def build_piece(name, base, dots, ring, rough):
    R, H, DOME, SEG, RINGS = 0.0225, 0.012, 0.0015, 40, 6
    m = Mesh()
    y0, y1 = -H / 2, H / 2 - DOME
    # side band
    for i in range(SEG):
        a0, a1 = 2 * math.pi * i / SEG, 2 * math.pi * (i + 1) / SEG
        p = [(R * math.cos(a0), y0, R * math.sin(a0)), (R * math.cos(a1), y0, R * math.sin(a1))]
        n0, n1 = (math.cos(a0), 0, math.sin(a0)), (math.cos(a1), 0, math.sin(a1))
        u0, u1 = i / SEG, (i + 1) / SEG
        verts = [p[0], p[1], (p[1][0], y1, p[1][2]), (p[0][0], y1, p[0][2])]
        m.smooth(0, verts, [n0, n1, n1, n0], [(u0, 319 / 320), (u1, 319 / 320), (u1, 257 / 320), (u0, 257 / 320)], [0, 2, 1, 0, 3, 2])
    # domed top: rings from the rim to the centre
    verts, norms, uvs, idx = [], [], [], []
    for j in range(RINGS + 1):
        rr = R * (1 - j / RINGS)
        yy = y1 + DOME * (1 - (rr / R) ** 2)
        for i in range(SEG):
            a = 2 * math.pi * i / SEG
            x, z = rr * math.cos(a), rr * math.sin(a)
            slope = 2 * DOME * rr / (R * R)
            nx, ny, nz = slope * math.cos(a), 1.0, slope * math.sin(a)
            l = math.sqrt(nx * nx + ny * ny + nz * nz)
            verts.append((x, yy, z)); norms.append((nx / l, ny / l, nz / l))
            uvs.append((0.5 + x / R * 0.47, (0.5 + z / R * 0.47) * 256 / 320))
    for j in range(RINGS):
        for i in range(SEG):
            a = j * SEG + i; b = j * SEG + (i + 1) % SEG
            c = (j + 1) * SEG + i; d = (j + 1) * SEG + (i + 1) % SEG
            idx += [a, c, b, b, c, d]
    m.smooth(0, verts, norms, uvs, idx)
    # bottom
    for i in range(SEG):
        a0, a1 = 2 * math.pi * i / SEG, 2 * math.pi * (i + 1) / SEG
        m.tri(0, (0, y0, 0), (R * math.cos(a0), y0, R * math.sin(a0)), (R * math.cos(a1), y0, R * math.sin(a1)), (0, -1, 0), ((0.5, 0.4), (0.5, 0.4), (0.5, 0.4)))
    m.write(name, [mat(name, tex=0, rough=rough)], [piece_texture(base, dots, ring)])


# ---------------------------------------------------------------- tower, tray, cup

def leather_texture(base, size=256, stitch=None, seed=1):
    im = Image.new("RGB", (size, size), base)
    dr = ImageDraw.Draw(im)
    rnd = random.Random(seed)
    for _ in range(size * 12):
        x, y = rnd.randint(0, size - 1), rnd.randint(0, size - 1)
        d = rnd.randint(-14, 10)
        dr.point((x, y), fill=tuple(max(0, min(255, c + d)) for c in base))
    im = im.filter(ImageFilter.GaussianBlur(0.8))
    if stitch:
        dr = ImageDraw.Draw(im)
        for y in range(4, size, 18):
            dr.line((size // 2, y, size // 2, y + 9), fill=stitch, width=3)
    return im


def build_tower():
    m = Mesh()
    s, h = 0.055, 0.02
    box(m, 0, -s / 2, s / 2, 0.0, h, -s / 2, s / 2, uvf=lambda x, z: (x / s + 0.5, z / s + 0.5))
    m.write("tower", [mat("TowerWood", tex=0, rough=0.7)], [leather_texture((0x3E, 0x2C, 0x1E), seed=3)])


def build_tray():
    m = Mesh()
    w, d, h, rim, floor = 0.20, 0.12, 0.015, 0.008, 0.005
    uvf = lambda x, z: (x / w + 0.5, z / d + 0.5)
    # floor slab
    box(m, 1, -w / 2 + rim, w / 2 - rim, 0.0, floor, -d / 2 + rim, d / 2 - rim, uvf=uvf, sides=False)
    # four rims
    box(m, 0, -w / 2, w / 2, 0.0, h, -d / 2, -d / 2 + rim, uvf=uvf)
    box(m, 0, -w / 2, w / 2, 0.0, h, d / 2 - rim, d / 2, uvf=uvf)
    box(m, 0, -w / 2, -w / 2 + rim, 0.0, h, -d / 2 + rim, d / 2 - rim, uvf=uvf)
    box(m, 0, w / 2 - rim, w / 2, 0.0, h, -d / 2 + rim, d / 2 - rim, uvf=uvf)
    m.write("tray", [mat("TrayRim", tex=0, rough=0.75), mat("TrayFloor", tex=1, rough=0.85)],
            [leather_texture(LEATHER, seed=5), leather_texture((0x6B, 0x4E, 0x36), seed=6)])


def build_cup():
    m = Mesh()
    r0, r1, H, wall, SEG = 0.036, 0.031, 0.05, 0.004, 40  # v0.1.4: lower + slimmer (Layout.CUP_R / CUP_H)
    def ring(r, y):
        return [(r * math.cos(2 * math.pi * i / SEG), y, r * math.sin(2 * math.pi * i / SEG)) for i in range(SEG)]
    slope = (r0 - r1) / H
    for (ra, rb, ya, yb, out) in ((r0, r1, 0.0, H, True), (r0 - wall, r1 - wall, wall, H, False)):
        a, b = ring(ra, ya), ring(rb, yb)
        verts, norms, uvs, idx = [], [], [], []
        for i in range(SEG + 1):
            k = i % SEG
            ang = 2 * math.pi * i / SEG
            n = (math.cos(ang), slope, math.sin(ang))
            l = math.sqrt(sum(c * c for c in n)); n = tuple(c / l for c in n)
            if not out:
                n = tuple(-c for c in n)
            verts += [a[k], b[k]]; norms += [n, n]; uvs += [(i / SEG, 1.0), (i / SEG, 0.0)]
        for i in range(SEG):
            p = 2 * i
            idx += ([p, p + 1, p + 2, p + 2, p + 1, p + 3] if not out else [p, p + 2, p + 1, p + 2, p + 3, p + 1])
        m.smooth(0 if out else 1, verts, norms, uvs, idx)
    # lip (top annulus) and the two bottoms
    outer, inner = ring(r1, H), ring(r1 - wall, H)
    for i in range(SEG):
        j = (i + 1) % SEG
        m.quad(0, inner[i], inner[j], outer[j], outer[i], (0, 1, 0), ((0.5, 0.02),) * 4)
    ob, ib = ring(r0, 0.0), ring(r0 - wall, wall)
    for i in range(SEG):
        j = (i + 1) % SEG
        m.tri(0, (0, 0, 0), ob[i], ob[j], (0, -1, 0), ((0.5, 0.5),) * 3)
        m.tri(1, (0, wall, 0), ib[j], ib[i], (0, 1, 0), ((0.5, 0.5),) * 3)
    m.write("cup", [mat("CupLeather", tex=0, rough=0.7), mat("CupInside", tex=1, rough=0.9)],
            [leather_texture(LEATHER, stitch=(0xC9, 0xA8, 0x77), seed=7), leather_texture((0x24, 0x1A, 0x12), seed=8)])


# ---------------------------------------------------------------- die

def build_die():
    edge, tip = 0.024, 0.005
    s = edge / (2 * math.sqrt(2))
    V = [(s, s, s), (s, -s, -s), (-s, s, -s), (-s, -s, s)]
    white = {0, 1}
    m = Mesh()
    faces = [(0, 1, 2), (0, 3, 1), (0, 2, 3), (1, 3, 2)]
    for f in faces:
        a, b, c = (V[i] for i in f)
        # outward winding
        n = [(b[1] - a[1]) * (c[2] - a[2]) - (b[2] - a[2]) * (c[1] - a[1]), (b[2] - a[2]) * (c[0] - a[0]) - (b[0] - a[0]) * (c[2] - a[2]), (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])]
        cen = [(a[k] + b[k] + c[k]) / 3 for k in range(3)]
        if sum(n[k] * cen[k] for k in range(3)) < 0:
            f = (f[0], f[2], f[1])
        ids = list(f)
        t = tip / edge
        def lerp(p, q, u):
            return tuple(p[k] + (q[k] - p[k]) * u for k in range(3))
        corners = {}
        for i in range(3):
            v, nxt, prv = ids[i], ids[(i + 1) % 3], ids[(i + 2) % 3]
            corners[i] = (lerp(V[v], V[nxt], t), lerp(V[v], V[prv], t))
        # three tip triangles
        for i in range(3):
            v = ids[i]
            m.tri(1 if v in white else 0, V[v], corners[i][0], corners[i][1])
        # the hexagon in the middle (black)
        hexagon = [corners[0][0], corners[1][1], corners[1][0], corners[2][1], corners[2][0], corners[0][1]]
        for i in range(1, 5):
            m.tri(0, hexagon[0], hexagon[i], hexagon[i + 1])
    m.write("die", [mat("DieBlack", color=BITUMEN, rough=0.35), mat("DieTip", color=TIP, rough=0.3)])


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    build_board()
    build_piece("piece_near", OBSIDIAN, TIP, TIP, 0.25)
    build_piece("piece_far", JASPER, SHELL, SHELL, 0.45)
    build_tower()
    build_tray()
    build_cup()
    build_die()
