#!/usr/bin/env python3
"""
Generates the Christmas landscapes of the launcher as data:

  app/src/main/assets/winter/palettes.json   colours per time of day (dawn, day, dusk, night)
  app/src/main/assets/winter/<scene>.json    the shapes of one scene

The launcher draws them with WinterScene (WinterScene.kt); this script can also draw them,
so they can be looked at on a PC:  python3 tools/winter/gen.py --preview out/

Coordinates are in a 1920x1080 design space, y pointing down. Every shape names a colour
role (looked up in the palette of the time of day) and may name a factor ("lights", "stars",
"aurora", "bulbs") that its opacity is multiplied by, so windows glow at night only.

Shape records:
  {"p": path, "c": role, "a": alpha, "k": factor}                 filled path
  {"p": path, "s": width, "c": role, ...}                         stroked path (round caps)
  {"o": [cx, cy, r], "c": role, ...}                              filled circle
  {"g": [cx, cy, r], "c": role, ...}                              soft glow (radial, fades out)
  {"p": path, "v": [y0, y1], "c": role, "c2": role2, ...}         vertical gradient fill
Paths use absolute M, L, Q, C and Z only.
"""
import json
import math
import os
import random
import sys

from shapely.geometry import Polygon, MultiPolygon, box
from shapely.ops import unary_union

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "winter")

W, H = 1920, 1080


def r1(v):
    return round(v, 1)


# ---------------------------------------------------------------- palettes

def hexc(s):
    return s


PALETTES = {
    # warm pink dawn over cold blue snow
    "hajnal": {
        "sky1": "#2E3A6B", "sky2": "#F0A98C", "sun": "#FFE2B8",
        "mtnFar": "#8C8AB0", "mtnFarSnow": "#E9D6E0", "mtnFarShade": "#6F6C96",
        "mtnMid": "#5E6290", "mtnMidSnow": "#D9CBDD", "mtnMidShade": "#4B4E7A",
        "hillFar": "#B9B4D2", "hillNear": "#D7D0E4", "snow": "#EDE4EE", "snowShade": "#B8B2D2",
        "pineFar": "#3C4566", "pineMid": "#2E3756", "pineNear": "#1F2740", "pineSnow": "#E6DCEA",
        "fir": "#2E5A4A", "trunk": "#3A2C2A",
        "wallA": "#8E6E6A", "wallB": "#A88C74", "wallC": "#7A7C98", "wood": "#6B4A3A", "woodDark": "#4A3229",
        "roof": "#5A3E44", "roofSnow": "#F1E6EE", "church": "#C9C0D2", "churchShade": "#9E95B4",
        "window": "#FFD08A", "windowDay": "#4E5A7C", "glow": "#FFB766", "smoke": "#E8DCE6",
        "ice": "#B7B6D6", "iceShine": "#F4D2C8",
        "red": "#C8333A", "gold": "#F2C14E", "green": "#2F6B45", "blue": "#3E7BD6", "white": "#FFF6EE",
        "bulbR": "#FF5A5A", "bulbG": "#5BE38A", "bulbB": "#5AA8FF", "bulbY": "#FFD45A",
        "carrot": "#F08A24", "dark": "#2A2430", "aurora1": "#55E6B0", "aurora2": "#7A8CFF",
        "f": {"lights": 0.75, "stars": 0.25, "aurora": 0.0, "bulbs": 0.9},
        "sunY": 760, "sunR": 70,
    },
    # crisp blue winter day
    "nappal": {
        "sky1": "#3D7BC8", "sky2": "#CFE6F6", "sun": "#FFF8E6",
        "mtnFar": "#9DB3CF", "mtnFarSnow": "#F6FAFD", "mtnFarShade": "#7F97B8",
        "mtnMid": "#6F8AAE", "mtnMidSnow": "#E9F1F8", "mtnMidShade": "#57729A",
        "hillFar": "#DCE8F3", "hillNear": "#EEF4FA", "snow": "#F7FAFD", "snowShade": "#C9D8E8",
        "pineFar": "#4E6B78", "pineMid": "#355463", "pineNear": "#23404D", "pineSnow": "#F4F8FC",
        "fir": "#2F6B45", "trunk": "#4A3528",
        "wallA": "#B5523F", "wallB": "#D8B98A", "wallC": "#6C8CB5", "wood": "#8A5A3C", "woodDark": "#5E3B27",
        "roof": "#6D3B32", "roofSnow": "#FBFDFF", "church": "#EDEAE4", "churchShade": "#C4C6D0",
        "window": "#FFE2A0", "windowDay": "#3C5878", "glow": "#FFC878", "smoke": "#F2F6FA",
        "ice": "#BFD6EA", "iceShine": "#F4FAFF",
        "red": "#C8333A", "gold": "#E8B33E", "green": "#2F6B45", "blue": "#3E7BD6", "white": "#FFFFFF",
        "bulbR": "#FF6B6B", "bulbG": "#62D88E", "bulbB": "#62A8F5", "bulbY": "#FFD45A",
        "carrot": "#F08A24", "dark": "#26282E", "aurora1": "#55E6B0", "aurora2": "#7A8CFF",
        "f": {"lights": 0.12, "stars": 0.0, "aurora": 0.0, "bulbs": 0.55},
        "sunY": 200, "sunR": 56,
    },
    # golden-violet dusk, the first windows lit
    "alkony": {
        "sky1": "#2A2357", "sky2": "#F09A6A", "sun": "#FFC07A",
        "mtnFar": "#8A6C8E", "mtnFarSnow": "#F2C7B8", "mtnFarShade": "#69527A",
        "mtnMid": "#5B4670", "mtnMidSnow": "#DDB4B6", "mtnMidShade": "#47375C",
        "hillFar": "#B6A2C2", "hillNear": "#D3C1D6", "snow": "#E9DCE6", "snowShade": "#A99BBE",
        "pineFar": "#3D3456", "pineMid": "#2F2846", "pineNear": "#211C34", "pineSnow": "#E2D2E0",
        "fir": "#2C4A40", "trunk": "#33242A",
        "wallA": "#8B5552", "wallB": "#A07C6E", "wallC": "#6B6688", "wood": "#5E3E36", "woodDark": "#3F2A27",
        "roof": "#4E3340", "roofSnow": "#EEDFE8", "church": "#C4B2C8", "churchShade": "#93809F",
        "window": "#FFCB7E", "windowDay": "#4A4A6E", "glow": "#FFA857", "smoke": "#DCCAD8",
        "ice": "#A796BC", "iceShine": "#F6B99A",
        "red": "#C8333A", "gold": "#F2C14E", "green": "#2F6B45", "blue": "#3E7BD6", "white": "#FFF4EC",
        "bulbR": "#FF5A5A", "bulbG": "#5BE38A", "bulbB": "#5AA8FF", "bulbY": "#FFD45A",
        "carrot": "#F08A24", "dark": "#261E2C", "aurora1": "#55E6B0", "aurora2": "#7A8CFF",
        "f": {"lights": 0.85, "stars": 0.3, "aurora": 0.15, "bulbs": 0.95},
        "sunY": 700, "sunR": 82,
    },
    # deep blue night, moonlit snow, warm windows, northern lights
    "ejszaka": {
        "sky1": "#050916", "sky2": "#1B2A52", "sun": "#EEF0F4",
        "mtnFar": "#2A3A5E", "mtnFarSnow": "#8E9FC4", "mtnFarShade": "#1E2B4A",
        "mtnMid": "#1E2B48", "mtnMidSnow": "#7383A8", "mtnMidShade": "#16203A",
        "hillFar": "#5D6E96", "hillNear": "#7484AA", "snow": "#8F9DC0", "snowShade": "#56648C",
        "pineFar": "#16213A", "pineMid": "#111A30", "pineNear": "#0B1222", "pineSnow": "#8796BA",
        "fir": "#1E4434", "trunk": "#1A1418",
        "wallA": "#4A3540", "wallB": "#55485A", "wallC": "#36405E", "wood": "#3A2A2C", "woodDark": "#271C1F",
        "roof": "#2A2232", "roofSnow": "#9AA8C8", "church": "#6E7898", "churchShade": "#4E5776",
        "window": "#FFC873", "windowDay": "#2C3550", "glow": "#FFAA4D", "smoke": "#5A6888",
        "ice": "#33476F", "iceShine": "#9DB0D8",
        "red": "#C8333A", "gold": "#F2C14E", "green": "#2F6B45", "blue": "#3E7BD6", "white": "#F4F6FF",
        "bulbR": "#FF4F4F", "bulbG": "#4CF08A", "bulbB": "#4FA6FF", "bulbY": "#FFD34A",
        "carrot": "#E0802A", "dark": "#151824", "aurora1": "#43F0B0", "aurora2": "#6F7BFF",
        "f": {"lights": 1.0, "stars": 1.0, "aurora": 1.0, "bulbs": 1.0},
        "sunY": 190, "sunR": 40,
    },
}


# ---------------------------------------------------------------- shape helpers

class Scene:
    def __init__(self, name, sun_x):
        self.name = name
        self.sun_x = sun_x
        self.items = []

    def add(self, item):
        self.items.append(item)
        return item

    def fill(self, path, c, a=1.0, k=None):
        it = {"p": path, "c": c}
        if a != 1.0:
            it["a"] = r1(a * 100) / 100
        if k:
            it["k"] = k
        return self.add(it)

    def stroke(self, path, w, c, a=1.0, k=None):
        it = {"p": path, "s": r1(w), "c": c}
        if a != 1.0:
            it["a"] = round(a, 2)
        if k:
            it["k"] = k
        return self.add(it)

    def circle(self, x, y, r, c, a=1.0, k=None):
        it = {"o": [r1(x), r1(y), r1(r)], "c": c}
        if a != 1.0:
            it["a"] = round(a, 2)
        if k:
            it["k"] = k
        return self.add(it)

    def glow(self, x, y, r, c, a=1.0, k=None):
        it = {"g": [r1(x), r1(y), r1(r)], "c": c}
        if a != 1.0:
            it["a"] = round(a, 2)
        if k:
            it["k"] = k
        return self.add(it)

    def grad(self, path, y0, y1, c, c2, a=1.0, k=None):
        it = {"p": path, "v": [r1(y0), r1(y1)], "c": c, "c2": c2}
        if a != 1.0:
            it["a"] = round(a, 2)
        if k:
            it["k"] = k
        return self.add(it)

    def geom(self, g, c, a=1.0, k=None):
        """A shapely polygon or multipolygon as filled paths."""
        if g.is_empty:
            return
        polys = list(g.geoms) if hasattr(g, "geoms") else [g]
        for pg in polys:
            if not isinstance(pg, Polygon) or pg.area < 4:
                continue
            self.fill(poly_path(list(pg.exterior.coords)[:-1]), c, a, k)

    def dump(self):
        return {"sunX": self.sun_x, "items": self.items}


def poly_path(pts):
    s = "M%s,%s" % (r1(pts[0][0]), r1(pts[0][1]))
    for x, y in pts[1:]:
        s += "L%s,%s" % (r1(x), r1(y))
    return s + "Z"


def rect_path(l, t, r, b):
    return poly_path([(l, t), (r, t), (r, b), (l, b)])


def ridge(rng, x0, x1, peaks, base, jag=10, step=22):
    """A mountain ridge through the given peaks [(x, y)], with small jags; returns points."""
    pts = [(x0, base)]
    anchors = [(x0, base - 30)] + peaks + [(x1, base - 30)]
    for (ax, ay), (bx, by) in zip(anchors, anchors[1:]):
        n = max(2, int(abs(bx - ax) / step))
        for i in range(n):
            t = i / n
            x = ax + (bx - ax) * t
            y = ay + (by - ay) * t
            if 0 < i:
                y += rng.uniform(-jag, jag) * (1 - abs(0.5 - t))
            pts.append((x, y))
    pts.append((x1, base - 30))
    pts.append((x1, base))
    return pts


def mountains(sc, rng, peaks, base, role, snow_role, shade_role, snowline, light_from=-1):
    pts = ridge(rng, -20, W + 20, peaks, base)
    m = Polygon(pts).buffer(0)
    sc.geom(m, role)
    # shaded faces: the side of each peak away from the light
    shade = []
    for (px, py) in peaks:
        d = 1 if light_from < 0 else -1
        tri = Polygon([(px, py - 2), (px + d * 520, base + 40), (px + d * 120, base + 40)])
        shade.append(tri)
    sh = m.intersection(unary_union(shade))
    sc.geom(sh, shade_role, 0.85)
    # snow caps: everything above a ragged snow line, with tongues running down the gullies
    zone = []
    x = -20
    line = []
    while x <= W + 20:
        y = snowline + rng.uniform(-18, 18) + 26 * math.sin(x / 37.0) * rng.uniform(0.3, 1)
        line.append((x, y))
        x += 18
    zone = Polygon([(-20, -10)] + line + [(W + 20, -10)])
    snow = m.intersection(zone)
    sc.geom(snow, snow_role)
    # the snow on the shaded side is a little darker
    sc.geom(snow.intersection(unary_union(shade)), shade_role, 0.35)
    return m


def soft_hill(sc, y_left, ctrl, y_right, role, bottom=H + 10):
    (c1x, c1y), (c2x, c2y) = ctrl
    p = "M-10,%s C%s,%s %s,%s %s,%s L%s,%s L-10,%sZ" % (
        y_left, c1x, c1y, c2x, c2y, W + 10, y_right, W + 10, bottom, bottom)
    sc.fill(p, role)
    return p


def pine(sc, x, base, h, role, snow_role, rng, tiers=None, snow=0.9, trunk=True, lean=0.0):
    """A fir: stacked drooping tiers, snow on the upper side of each."""
    tiers = tiers or max(3, min(6, int(h / 26)))
    w = h * rng.uniform(0.36, 0.44)
    if trunk:
        tw = max(2, h * 0.05)
        sc.fill(rect_path(x - tw, base - h * 0.12, x + tw, base + 1), "trunk")
    top = base - h
    body = base - h * 0.08
    for i in range(tiers):
        t0 = i / tiers
        t1 = (i + 1) / tiers
        yt = top + (body - top) * (t0 * 0.82)
        yb = top + (body - top) * t1
        hw = w * (0.22 + 0.78 * t1) / 2 * rng.uniform(0.92, 1.08)
        cx = x + lean * (1 - t1) * h * 0.1
        pts = [(cx, yt)]
        # right side out, then a ragged drooping hem back to the left
        pts.append((cx + hw * 0.55, yt + (yb - yt) * 0.6))
        pts.append((cx + hw, yb + 3))
        n = 5
        for j in range(1, n):
            u = 1 - j / n
            hx = cx - hw + 2 * hw * u
            hy = yb - (5 if j % 2 else 0) * (h / 120)
            pts.append((hx, hy + 2))
        pts.append((cx - hw, yb + 3))
        pts.append((cx - hw * 0.55, yt + (yb - yt) * 0.6))
        sc.fill(poly_path(pts), role)
        if snow > 0:
            sy = yt + (yb - yt) * 0.62
            spts = [(cx, yt + 1),
                    (cx + hw * 0.45, yt + (yb - yt) * 0.5),
                    (cx + hw * 0.8, yb - 1),
                    (cx + hw * 0.35, sy + 3),
                    (cx, sy - 1),
                    (cx - hw * 0.4, sy + 4),
                    (cx - hw * 0.86, yb - 1),
                    (cx - hw * 0.45, yt + (yb - yt) * 0.5)]
            sc.fill(poly_path(spts), snow_role, snow)


def forest(sc, rng, x0, x1, base_fn, hmin, hmax, role, snow_role, density=1.0, snow=0.8, trunk=False):
    x = x0
    trees = []
    while x < x1:
        h = rng.uniform(hmin, hmax)
        trees.append((x, base_fn(x) + rng.uniform(-4, 6), h))
        x += h * rng.uniform(0.18, 0.34) / density
    # back to front: taller trees last so they overlap the small ones
    for (tx, ty, th) in sorted(trees, key=lambda t: t[1]):
        pine(sc, tx, ty, th, role, snow_role, rng, snow=snow, trunk=trunk)


def bulbs_along(sc, pts, rng, size=4.2, glow=18, roles=("bulbR", "bulbG", "bulbB", "bulbY")):
    for i, (x, y) in enumerate(pts):
        c = roles[i % len(roles)]
        sc.glow(x, y, glow, c, 0.55, "bulbs")
        sc.circle(x, y, size, c, 1.0, "bulbs")


def quad_points(x0, y0, cx, cy, x1, y1, n):
    out = []
    for i in range(n):
        t = (i + 0.5) / n
        x = (1 - t) ** 2 * x0 + 2 * (1 - t) * t * cx + t * t * x1
        y = (1 - t) ** 2 * y0 + 2 * (1 - t) * t * cy + t * t * y1
        out.append((x, y))
    return out


def xmas_tree(sc, x, base, h, rng, star=True):
    """A decorated fir: garlands, coloured lights, a gold star."""
    pine(sc, x, base, h, "fir", "pineSnow", rng, tiers=5, snow=0.35)
    w = h * 0.4
    top = base - h
    body = base - h * 0.08
    roles = ["bulbR", "bulbY", "bulbB", "bulbG"]
    k = 0
    for i in range(1, 5):
        t = i / 5
        y = top + (body - top) * t
        hw = w * (0.22 + 0.78 * t) / 2
        # a gold garland swinging across this tier
        g = "M%s,%s Q%s,%s %s,%s" % (r1(x - hw * 0.85), r1(y - h * 0.06), r1(x), r1(y + h * 0.06), r1(x + hw * 0.85), r1(y - h * 0.1))
        sc.stroke(g, max(1.5, h * 0.012), "gold", 0.85)
        pts = quad_points(x - hw * 0.85, y - h * 0.06 + 2, x, y + h * 0.06 + 2, x + hw * 0.85, y - h * 0.1 + 2, 3 + i)
        for (px, py) in pts:
            c = roles[k % 4]
            k += 1
            sc.glow(px, py, h * 0.07, c, 0.5, "bulbs")
            sc.circle(px, py, max(2.2, h * 0.018), c, 1.0, "bulbs")
    if star:
        sx, sy = x, top - h * 0.02
        sc.glow(sx, sy, h * 0.22, "gold", 0.55, "bulbs")
        r_out, r_in = h * 0.06, h * 0.025
        pts = []
        for i in range(10):
            ang = -math.pi / 2 + i * math.pi / 5
            rr = r_out if i % 2 == 0 else r_in
            pts.append((sx + rr * math.cos(ang), sy + rr * math.sin(ang)))
        sc.fill(poly_path(pts), "gold")


def window(sc, l, t, r, b, lit=True):
    cx, cy = (l + r) / 2, (t + b) / 2
    sc.fill(rect_path(l, t, r, b), "windowDay")
    if lit:
        sc.glow(cx, cy, (r - l) * 2.6, "glow", 0.45, "lights")
        sc.fill(rect_path(l, t, r, b), "window", 1.0, "lights")
        # mullions
        sc.fill(rect_path(cx - 0.8, t, cx + 0.8, b), "woodDark", 0.8)
        sc.fill(rect_path(l, cy - 0.8, r, cy + 0.8), "woodDark", 0.8)


def smoke(sc, x, y, h, rng):
    p = "M%s,%s" % (r1(x), r1(y))
    cx, cy = x, y
    for i in range(3):
        nx = cx + rng.uniform(10, 26)
        ny = cy - h / 3
        p += " Q%s,%s %s,%s" % (r1(cx - 14 + 28 * (i % 2)), r1((cy + ny) / 2), r1(nx), r1(ny))
        cx, cy = nx, ny
    sc.stroke(p, 10, "smoke", 0.12)
    sc.stroke(p, 4, "smoke", 0.16)


def house(sc, x, base, w, h, roof_h, wall, rng, windows=2, chimney=True, lit=True, door=False):
    """A gable-front cottage: wall, snowy roof with overhang, lit windows."""
    l, r = x - w / 2, x + w / 2
    top = base - h
    sc.fill(rect_path(l, top, r, base), wall)
    # shade on the right third
    sc.fill(rect_path(x + w * 0.18, top, r, base), "dark", 0.18)
    if chimney:
        cxp = x + w * 0.24
        cy = top - roof_h * 0.55
        sc.fill(rect_path(cxp - w * 0.06, cy - roof_h * 0.35, cxp + w * 0.06, top - roof_h * 0.2), "woodDark")
        sc.fill(rect_path(cxp - w * 0.08, cy - roof_h * 0.42, cxp + w * 0.08, cy - roof_h * 0.32), "roofSnow")
        smoke(sc, cxp, cy - roof_h * 0.45, roof_h * 2.2, rng)
    # roof
    ov = w * 0.1
    sc.fill(poly_path([(l - ov, top + 3), (x, top - roof_h), (r + ov, top + 3)]), "roof")
    # thick snow on the roof, drooping over the eaves
    sn = [(l - ov - 3, top + 4), (x, top - roof_h - 6), (r + ov + 3, top + 4)]
    drip = [(r + ov + 3, top + 4), (r + ov - 4, top + 10), (r + ov - 12, top + 5),
            (x + 4, top - roof_h + 10), (x - 4, top - roof_h + 10),
            (l - ov + 12, top + 6), (l - ov + 3, top + 11)]
    sc.fill(poly_path(sn + drip[1:]), "roofSnow")
    # windows
    ww = w * 0.2
    wh = h * 0.32
    wy = top + h * 0.22
    if windows == 1:
        window(sc, x - ww / 2, wy, x + ww / 2, wy + wh, lit)
    else:
        window(sc, x - w * 0.32, wy, x - w * 0.32 + ww, wy + wh, lit)
        window(sc, x + w * 0.12, wy, x + w * 0.12 + ww, wy + wh, lit and rng.random() < 0.85)
    if door:
        sc.fill(rect_path(x - w * 0.09, base - h * 0.42, x + w * 0.09, base), "woodDark")
    # an attic window in the gable
    if roof_h > 26:
        gx, gy = x, top - roof_h * 0.38
        window(sc, gx - w * 0.06, gy - roof_h * 0.12, gx + w * 0.06, gy + roof_h * 0.12, lit and rng.random() < 0.6)
    # snow drift at the base
    sc.fill("M%s,%s Q%s,%s %s,%s Z" % (r1(l - 18), r1(base + 2), r1(x), r1(base - 16), r1(r + 18), r1(base + 2)), "snow")


def church(sc, x, base, rng):
    w, h = 74, 70
    l, r = x - w / 2, x + w / 2
    top = base - h
    # nave
    sc.fill(rect_path(l, top, r, base), "church")
    sc.fill(rect_path(x + 8, top, r, base), "churchShade", 0.7)
    sc.fill(poly_path([(l - 6, top + 2), (x, top - 30), (r + 6, top + 2)]), "roof")
    sc.fill(poly_path([(l - 8, top + 3), (x, top - 34), (r + 8, top + 3), (r - 2, top + 8), (x, top - 24), (l + 2, top + 9)]), "roofSnow")
    # tower in front
    tw = 34
    tl, tr = x - tw / 2, x + tw / 2
    tt = base - 130
    sc.fill(rect_path(tl, tt, tr, base), "church")
    sc.fill(rect_path(x + 4, tt, tr, base), "churchShade", 0.7)
    # onion-ish spire
    sc.fill("M%s,%s Q%s,%s %s,%s Q%s,%s %s,%s Z" % (tl - 4, tt, x - 22, tt - 30, x, tt - 70, x + 22, tt - 30, tr + 4, tt), "roof")
    sc.fill("M%s,%s Q%s,%s %s,%s Q%s,%s %s,%s Q%s,%s %s,%s Z" % (
        tl - 5, tt + 1, x - 24, tt - 30, x, tt - 72, x + 6, tt - 58, x - 6, tt - 30, x - 2, tt - 6, tl + 6, tt + 4), "roofSnow", 0.9)
    sc.fill(rect_path(x - 1.2, tt - 92, x + 1.2, tt - 68), "gold")
    sc.fill(rect_path(x - 7, tt - 84, x + 7, tt - 81.5), "gold")
    # clock and windows
    sc.circle(x, tt + 22, 8, "white", 0.85)
    sc.circle(x, tt + 22, 8, "window", 0.6, "lights")
    window(sc, x - 5, tt + 46, x + 5, tt + 66)
    for i in range(3):
        wx = r - 8 - i * 0
    window(sc, r - 22, top + 18, r - 12, top + 42)
    sc.fill("M%s,%s Q%s,%s %s,%s Z" % (l - 30, base + 2, x, base - 14, r + 30, base + 2), "snow")


def snowman(sc, x, base, s, rng):
    sc.fill("M%s,%s Q%s,%s %s,%s Z" % (r1(x - 40 * s), r1(base + 2), r1(x), r1(base - 12 * s), r1(x + 40 * s), r1(base + 2)), "snowShade", 0.6)
    for (dy, rr) in ((-22, 24), (-58, 17), (-84, 12)):
        sc.circle(x, base + dy * s, rr * s, "white")
        sc.circle(x + rr * s * 0.35, base + dy * s + rr * s * 0.2, rr * s * 0.75, "snowShade", 0.35)
    # hat
    sc.fill(rect_path(x - 13 * s, base - 95 * s, x + 13 * s, base - 92 * s), "dark")
    sc.fill(rect_path(x - 8 * s, base - 113 * s, x + 8 * s, base - 94 * s), "dark")
    sc.fill(rect_path(x - 8 * s, base - 99 * s, x + 8 * s, base - 96 * s), "red")
    # eyes, nose, buttons
    sc.circle(x - 4 * s, base - 87 * s, 1.6 * s, "dark")
    sc.circle(x + 4 * s, base - 87 * s, 1.6 * s, "dark")
    sc.fill(poly_path([(x, base - 84 * s), (x + 15 * s, base - 81 * s), (x, base - 80 * s)]), "carrot")
    for dy in (-62, -54, -46):
        sc.circle(x, base + dy * s, 1.8 * s, "dark")
    # scarf
    sc.fill(rect_path(x - 12 * s, base - 75 * s, x + 12 * s, base - 70 * s), "red")
    sc.fill(poly_path([(x + 5 * s, base - 72 * s), (x + 11 * s, base - 72 * s), (x + 13 * s, base - 56 * s), (x + 7 * s, base - 57 * s)]), "red")
    # stick arms
    sc.stroke("M%s,%s L%s,%s L%s,%s" % (r1(x - 15 * s), r1(base - 62 * s), r1(x - 38 * s), r1(base - 78 * s), r1(x - 44 * s), r1(base - 86 * s)), 2.2 * s, "woodDark")
    sc.stroke("M%s,%s L%s,%s" % (r1(x + 15 * s), r1(base - 62 * s), r1(x + 40 * s), r1(base - 70 * s)), 2.2 * s, "woodDark")


def lantern(sc, x, base, h):
    sc.fill(rect_path(x - 2.5, base - h, x + 2.5, base), "dark")
    sc.glow(x, base - h - 8, 70, "glow", 0.5, "lights")
    sc.fill(rect_path(x - 8, base - h - 18, x + 8, base - h), "dark")
    sc.fill(rect_path(x - 5.5, base - h - 15, x + 5.5, base - h - 3), "window", 1.0, "lights")
    sc.fill(poly_path([(x - 11, base - h - 18), (x, base - h - 27), (x + 11, base - h - 18)]), "dark")
    sc.fill(poly_path([(x - 12, base - h - 18), (x, base - h - 29), (x + 12, base - h - 18), (x, base - h - 23)]), "roofSnow", 0.9)


def fence(sc, x0, x1, base, rng):
    x = x0
    sc.fill(rect_path(x0, base - 30, x1, base - 26), "woodDark")
    sc.fill(rect_path(x0, base - 16, x1, base - 12), "woodDark")
    sc.fill(rect_path(x0, base - 33, x1, base - 30), "roofSnow", 0.9)
    while x <= x1:
        sc.fill(rect_path(x - 3, base - 40, x + 3, base + 2), "wood")
        sc.fill("M%s,%s Q%s,%s %s,%s Z" % (r1(x - 5), r1(base - 39), r1(x), r1(base - 47), r1(x + 5), r1(base - 39)), "roofSnow")
        x += 34


def aurora(sc, rng):
    for (y0, amp, role, a) in ((150, 60, "aurora1", 0.10), (200, 70, "aurora2", 0.07), (120, 40, "aurora1", 0.07)):
        p = "M-50,%s" % y0
        x = -50
        while x < W + 50:
            nx = x + 320
            p += " Q%s,%s %s,%s" % (x + 160, y0 + rng.uniform(-amp, amp), nx, y0 + rng.uniform(-amp * 0.6, amp * 0.6))
            x = nx
        for (w, aa) in ((140, a * 0.35), (80, a * 0.6), (34, a * 0.9)):
            sc.stroke(p, w, role, aa, "aurora")


def foreground(sc, rng, y_left, y_right, ctrl):
    soft_hill(sc, y_left, ctrl, y_right, "snow")
    # a few shadows of drifts
    for i in range(7):
        x = rng.uniform(0, W)
        y = y_left + (y_right - y_left) * x / W + rng.uniform(30, 180)
        sc.fill("M%s,%s Q%s,%s %s,%s Q%s,%s %s,%s Z" % (
            r1(x - 140), r1(y), r1(x - 40), r1(y - 14), r1(x + 60), r1(y - 4), r1(x + 140), r1(y + 8), r1(x - 140), r1(y)), "snowShade", 0.35)


# ---------------------------------------------------------------- the scenes

def village():
    rng = random.Random(7)
    sc = Scene("village", 1380)
    mountains(sc, rng, [(140, 420), (430, 330), (690, 410), (980, 300), (1260, 390), (1540, 320), (1820, 400)],
              660, "mtnFar", "mtnFarSnow", "mtnFarShade", 420)
    soft_hill(sc, 600, ((500, 540), (1200, 620)), 590, "hillFar")
    forest(sc, rng, -20, W + 20, lambda x: 600 + 30 * math.sin(x / 260), 40, 80, "pineFar", "pineSnow", density=1.5, snow=0.55)
    soft_hill(sc, 660, ((700, 630), (1300, 700)), 650, "hillNear")
    # the village on the slope, back row first
    back = [(250, 668, 70, 52, 34, "wallC"), (420, 676, 86, 58, 38, "wallA"), (930, 684, 80, 54, 36, "wallB"),
            (1490, 676, 92, 60, 40, "wallA"), (1680, 670, 70, 50, 32, "wallC")]
    for (x, b, w, h, rh, wall) in back:
        house(sc, x, b, w, h, rh, wall, rng)
    church(sc, 640, 700, rng)
    # pines between the houses
    for x in (330, 520, 790, 1040, 1390, 1600, 1790):
        pine(sc, x, 706 + rng.uniform(-6, 6), rng.uniform(60, 90), "pineMid", "pineSnow", rng, snow=0.8)
    front = [(160, 742, 110, 72, 46, "wallB"), (470, 752, 120, 78, 50, "wallC"), (1300, 750, 126, 80, 52, "wallB"),
             (1560, 746, 104, 70, 44, "wallC"), (1790, 754, 118, 76, 48, "wallA")]
    for (x, b, w, h, rh, wall) in front:
        house(sc, x, b, w, h, rh, wall, rng, door=True)
    # the big tree on the square
    xmas_tree(sc, 1040, 770, 230, rng)
    # lights strung across the square
    for (x0, y0, x1, y1) in ((790, 690, 1010, 700), (1080, 700, 1240, 690)):
        sc.stroke("M%s,%s Q%s,%s %s,%s" % (x0, y0, (x0 + x1) / 2, y0 + 26, x1, y1), 1.2, "dark", 0.7)
        bulbs_along(sc, quad_points(x0, y0, (x0 + x1) / 2, y0 + 26, x1, y1, 9), rng, size=3.2, glow=13)
    foreground(sc, rng, 790, 800, ((600, 760), (1300, 830)))
    lantern(sc, 860, 820, 70)
    lantern(sc, 1230, 826, 70)
    # framing firs at the edges
    for (x, b, h) in ((40, 1010, 420), (150, 1040, 330), (1870, 1020, 440), (1750, 1050, 300)):
        pine(sc, x, b, h, "pineNear", "pineSnow", rng, snow=0.9)
    return sc


def cabin():
    rng = random.Random(21)
    sc = Scene("cabin", 1320)
    mountains(sc, rng, [(200, 380), (520, 300), (860, 420), (1150, 330), (1500, 280), (1800, 380)],
              640, "mtnFar", "mtnFarSnow", "mtnFarShade", 400)
    soft_hill(sc, 600, ((600, 560), (1300, 620)), 590, "hillFar")
    forest(sc, rng, -20, W + 20, lambda x: 560 + 40 * math.sin(x / 300 + 1), 60, 110, "pineFar", "pineSnow", density=1.6, snow=0.5)
    soft_hill(sc, 650, ((700, 620), (1300, 680)), 640, "hillNear")
    forest(sc, rng, -20, W + 20, lambda x: 640 + 30 * math.sin(x / 220), 80, 150, "pineMid", "pineSnow", density=1.3, snow=0.7)
    foreground(sc, rng, 720, 700, ((500, 690), (1200, 740)))
    # the log cabin
    x, base, w, h = 1270, 752, 330, 140
    l, r, top = x - w / 2, x + w / 2, base - h
    sc.fill(rect_path(l, top, r, base), "wood")
    for i in range(1, 9):
        y = top + h * i / 9
        sc.fill(rect_path(l, y - 1.6, r, y + 1.6), "woodDark", 0.7)
    sc.fill(rect_path(x + w * 0.2, top, r, base), "dark", 0.2)
    # chimney, roof, snow
    sc.fill(rect_path(l + 40, top - 110, l + 72, top - 30), "woodDark")
    sc.fill(rect_path(l + 34, top - 118, l + 78, top - 106), "roofSnow")
    smoke(sc, l + 56, top - 120, 220, rng)
    sc.fill(poly_path([(l - 40, top + 6), (l + 30, top - 96), (r - 30, top - 96), (r + 40, top + 6)]), "roof")
    sc.fill(poly_path([(l - 46, top + 7), (l + 26, top - 102), (r - 26, top - 102), (r + 46, top + 7),
                       (r + 30, top + 18), (r + 14, top + 10), (x + 60, top + 14), (x - 20, top + 9),
                       (l + 40, top + 16), (l - 28, top + 18)]), "roofSnow")
    # lights along the eave
    sc.stroke("M%s,%s Q%s,%s %s,%s" % (l - 30, top + 16, x, top + 34, r + 30, top + 16), 1.4, "dark", 0.8)
    bulbs_along(sc, quad_points(l - 30, top + 16, x, top + 34, r + 30, top + 16, 16), rng, size=3.8, glow=15)
    # windows, door with wreath
    window(sc, l + 34, top + 46, l + 104, top + 100)
    window(sc, r - 104, top + 46, r - 34, top + 100)
    sc.fill(rect_path(x - 26, top + 40, x + 26, base), "woodDark")
    sc.circle(x, top + 64, 15, "green")
    sc.circle(x, top + 64, 8.5, "woodDark")
    sc.fill(poly_path([(x - 4, top + 76), (x + 4, top + 76), (x + 7, top + 88), (x, top + 82), (x - 7, top + 88)]), "red")
    for a in range(0, 360, 45):
        sc.circle(x + 12 * math.cos(math.radians(a)), top + 64 + 12 * math.sin(math.radians(a)), 2, "red", 1.0)
    sc.glow(x, top + 40, 40, "glow", 0.4, "lights")
    sc.fill("M%s,%s Q%s,%s %s,%s Z" % (l - 60, base + 3, x, base - 22, r + 60, base + 3), "snow")
    # a little decorated tree by the cabin, snowman, fence, lantern
    xmas_tree(sc, 1540, 770, 150, rng)
    snowman(sc, 900, 790, 1.05, rng)
    fence(sc, 560, 800, 800, rng)
    lantern(sc, 1050, 800, 80)
    # framing firs on the left
    for (xx, b, hh) in ((60, 1030, 460), (220, 1060, 360), (380, 1080, 260), (1880, 1050, 380)):
        pine(sc, xx, b, hh, "pineNear", "pineSnow", rng, snow=0.9)
    return sc


def lake():
    rng = random.Random(42)
    sc = Scene("lake", 1460)
    aurora(sc, rng)
    mountains(sc, rng, [(160, 520), (300, 290), (450, 500), (830, 250), (1060, 480), (1360, 240), (1560, 470), (1700, 320), (1860, 520)], 640,
              "mtnFar", "mtnFarSnow", "mtnFarShade", 330, light_from=1)
    peaks = [(160, 380), (420, 300), (700, 400), (960, 290), (1220, 390), (1500, 320), (1760, 400)]
    m = mountains(sc, rng, peaks, 640, "mtnMid", "mtnMidSnow", "mtnMidShade", 360)
    # far shore forest
    forest(sc, rng, -20, W + 20, lambda x: 632 + 6 * math.sin(x / 90), 30, 56, "pineFar", "pineSnow", density=1.8, snow=0.5)
    # the frozen lake with the mountains mirrored in it
    sc.grad(rect_path(-10, 636, W + 10, 910), 636, 900, "ice", "snowShade")
    refl = Polygon([(x, 636 + (636 - y) * 0.55) for (x, y) in m.exterior.coords]).buffer(0)
    sc.geom(refl.intersection(box(-20, 636, W + 20, 860)), "mtnMid", 0.28)
    for i in range(14):
        y = rng.uniform(650, 850)
        x = rng.uniform(0, W)
        ww = rng.uniform(120, 420)
        sc.fill(rect_path(x, y, x + ww, y + rng.uniform(1.5, 3)), "iceShine", rng.uniform(0.2, 0.45))
    # a chapel with warm windows on the far shore
    house(sc, 1210, 640, 46, 30, 22, "wallA", rng, windows=1, chimney=True)
    house(sc, 1290, 642, 54, 34, 24, "wallC", rng, windows=1, chimney=False)
    # near shore: snowy bank with firs on both sides
    sc.fill("M-10,840 C300,800 640,880 900,870 C1200,860 1500,820 1930,850 L1930,1090 L-10,1090Z", "snow")
    forest(sc, rng, -20, 520, lambda x: 860 - x * 0.05, 120, 220, "pineMid", "pineSnow", density=1.2, snow=0.85, trunk=True)
    forest(sc, rng, 1380, W + 20, lambda x: 850 + (x - 1380) * 0.02, 120, 220, "pineMid", "pineSnow", density=1.2, snow=0.85, trunk=True)
    xmas_tree(sc, 1180, 880, 200, rng)
    lantern(sc, 960, 880, 64)
    for (xx, b, hh) in ((90, 1070, 420), (1840, 1080, 440)):
        pine(sc, xx, b, hh, "pineNear", "pineSnow", rng, snow=0.9)
    return sc


SCENES = [village, cabin, lake]


# ---------------------------------------------------------------- preview (cairo)

def preview(scene, phase, path):
    import cairo

    pal = PALETTES[phase]
    f = pal["f"]

    def rgb(role):
        h = pal[role].lstrip("#")
        return tuple(int(h[i:i + 2], 16) / 255 for i in (0, 2, 4))

    surf = cairo.ImageSurface(cairo.FORMAT_ARGB32, W, H)
    cr = cairo.Context(surf)
    g = cairo.LinearGradient(0, 0, 0, 760)
    g.add_color_stop_rgb(0, *rgb("sky1"))
    g.add_color_stop_rgb(1, *rgb("sky2"))
    cr.set_source(g)
    cr.paint()
    if f["stars"] > 0:
        r = random.Random(3)
        for i in range(140):
            cr.set_source_rgba(1, 1, 1, f["stars"] * r.uniform(0.3, 0.9))
            cr.arc(r.uniform(0, W), r.uniform(0, 560), r.uniform(0.8, 2.2), 0, 2 * math.pi)
            cr.fill()
    sx, sy, sr = scene["sunX"], pal["sunY"], pal["sunR"]
    rg = cairo.RadialGradient(sx, sy, sr * 0.8, sx, sy, sr * 4)
    rg.add_color_stop_rgba(0, *rgb("sun"), 0.35)
    rg.add_color_stop_rgba(1, *rgb("sun"), 0)
    cr.set_source(rg)
    cr.arc(sx, sy, sr * 4, 0, 2 * math.pi)
    cr.fill()
    cr.set_source_rgb(*rgb("sun"))
    cr.arc(sx, sy, sr, 0, 2 * math.pi)
    cr.fill()

    def trace(p):
        import re
        toks = re.findall(r"[MLQCZ]|-?\d+(?:\.\d+)?", p)
        i = 0
        cmd = None
        while i < len(toks):
            t = toks[i]
            if t in "MLQCZ":
                cmd = t
                i += 1
                if cmd == "Z":
                    cr.close_path()
                continue
            n = {"M": 2, "L": 2, "Q": 4, "C": 6}[cmd]
            v = [float(x) for x in toks[i:i + n]]
            i += n
            if cmd == "M":
                cr.move_to(*v)
            elif cmd == "L":
                cr.line_to(*v)
            elif cmd == "C":
                cr.curve_to(*v)
            else:
                x0, y0 = cr.get_current_point()
                qx, qy, x, y = v
                cr.curve_to(x0 + 2 / 3 * (qx - x0), y0 + 2 / 3 * (qy - y0), x + 2 / 3 * (qx - x), y + 2 / 3 * (qy - y), x, y)

    for it in scene["items"]:
        a = it.get("a", 1.0) * (f[it["k"]] if "k" in it else 1.0)
        if a <= 0.003:
            continue
        c = rgb(it["c"])
        if "g" in it:
            x, y, r = it["g"]
            rg = cairo.RadialGradient(x, y, 0, x, y, r)
            rg.add_color_stop_rgba(0, *c, a)
            rg.add_color_stop_rgba(1, *c, 0)
            cr.set_source(rg)
            cr.arc(x, y, r, 0, 2 * math.pi)
            cr.fill()
        elif "o" in it:
            x, y, r = it["o"]
            cr.set_source_rgba(*c, a)
            cr.arc(x, y, r, 0, 2 * math.pi)
            cr.fill()
        elif "s" in it:
            cr.new_path()
            trace(it["p"])
            cr.set_source_rgba(*c, a)
            cr.set_line_width(it["s"])
            cr.set_line_cap(cairo.LINE_CAP_ROUND)
            cr.set_line_join(cairo.LINE_JOIN_ROUND)
            cr.stroke()
        elif "v" in it:
            y0, y1 = it["v"]
            lg = cairo.LinearGradient(0, y0, 0, y1)
            lg.add_color_stop_rgba(0, *c, a)
            lg.add_color_stop_rgba(1, *rgb(it["c2"]), a)
            cr.new_path()
            trace(it["p"])
            cr.set_source(lg)
            cr.fill()
        else:
            cr.new_path()
            trace(it["p"])
            cr.set_source_rgba(*c, a)
            cr.fill()
    surf.write_to_png(path)


def main():
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "palettes.json"), "w") as fh:
        json.dump(PALETTES, fh, separators=(",", ":"))
    names = []
    built = {}
    for fn in SCENES:
        sc = fn()
        data = sc.dump()
        built[sc.name] = data
        names.append(sc.name)
        with open(os.path.join(OUT, sc.name + ".json"), "w") as fh:
            json.dump(data, fh, separators=(",", ":"))
        print("%-8s %4d shapes %6d bytes" % (sc.name, len(data["items"]), len(json.dumps(data, separators=(",", ":")))))
    with open(os.path.join(OUT, "scenes.json"), "w") as fh:
        json.dump(names, fh)
    if len(sys.argv) > 2 and sys.argv[1] == "--preview":
        out = sys.argv[2]
        os.makedirs(out, exist_ok=True)
        for n, data in built.items():
            for ph in PALETTES:
                preview(data, ph, os.path.join(out, "%s_%s.png" % (n, ph)))


if __name__ == "__main__":
    main()
