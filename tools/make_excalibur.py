#!/usr/bin/env python3
"""Excalibur at 32x32: a holy longsword - mirror-white blade with a gold fuller, winged gold guard
round a sapphire, a blue-wrapped grip, a gold pommel, starlight around the edge. Stdlib only.

Run from the repo root:  PYTHONPATH=tools python3 tools/make_excalibur.py
"""
import math

from make_scarlet_devil_textures import OUT, write_png

N = 32
T = (0, 0, 0, 0)
K = (30, 22, 40, 255)          # outline
EDGE = (255, 255, 255, 255)
STEEL = (214, 224, 240, 255)
SHADE = (150, 166, 196, 255)
FULLER = (255, 214, 110, 255)
G0, G1, G2, G3 = (96, 60, 14, 255), (176, 122, 34, 255), (236, 186, 70, 255), (255, 240, 170, 255)
S0, S1, S2 = (20, 40, 120, 255), (40, 100, 220, 255), (150, 210, 255, 255)
WRAP, WRAP2 = (28, 44, 110, 255), (50, 78, 170, 255)


def seg(px, py, ax, ay, bx, by):
    vx, vy = bx - ax, by - ay
    t = max(0.0, min(1.0, ((px - ax) * vx + (py - ay) * vy) / (vx * vx + vy * vy)))
    qx, qy = ax + vx * t, ay + vy * t
    return math.hypot(px - qx, py - qy), t, (px - qx) * vy - (py - qy) * vx


def paint_line(rows, pts, w0, w1, fill):
    """fill(d, w, side, t) -> colour or None, along a polyline whose width runs w0 -> w1."""
    total = sum(math.hypot(pts[k + 1][0] - pts[k][0], pts[k + 1][1] - pts[k][1]) for k in range(len(pts) - 1))
    for y in range(N):
        for x in range(N):
            best, run = None, 0.0
            for k in range(len(pts) - 1):
                (ax, ay), (bx, by) = pts[k], pts[k + 1]
                d, t, side = seg(x, y, ax, ay, bx, by)
                L = math.hypot(bx - ax, by - ay)
                u = (run + t * L) / total
                w = w0 + (w1 - w0) * u
                if best is None or d - w < best[0] - best[1]:
                    best = (d, w, side, u)
                run += L
            d, w, side, u = best
            if d <= w + 0.6:
                c = fill(d, w, side, u)
                if c is not None:
                    rows[y][x] = c


def disc(rows, cx, cy, r, inner):
    for y in range(N):
        for x in range(N):
            d = math.hypot(x - cx, y - cy)
            if d <= r + 0.5:
                rows[y][x] = K if d > r - 0.5 else inner(x - cx, y - cy, d)


def excalibur():
    rows = [[T] * N for _ in range(N)]
    # Blade: guard at (11,20), tip top-right.
    def blade(d, w, side, u):
        if d > w - 0.45:
            return K
        if d < w * 0.28 and u < 0.82:
            return EDGE if int(u * 30) % 5 == 0 else FULLER     # gold fuller with white runes
        return EDGE if side > 0 and d > w * 0.6 else STEEL if side > 0 else SHADE if d > w * 0.7 else STEEL
    paint_line(rows, [(11.0, 20.0), (22.0, 9.0), (29.6, 1.6)], 3.0, 0.4, blade)
    # Grip and wrap.
    def grip(d, w, side, u):
        return K if d > w - 0.45 else (G2 if int(u * 10) % 3 == 0 else WRAP2 if side > 0 else WRAP)
    paint_line(rows, [(9.0, 22.0), (4.6, 26.4)], 1.5, 1.5, grip)
    # Winged guard, sweeping toward the tip at both ends.
    def gold(d, w, side, u):
        return K if d > w - 0.45 else G3 if side > 0.3 else G2 if side > -0.6 else G1
    paint_line(rows, [(5.6, 11.6), (6.6, 15.4), (8.4, 18.8), (11.0, 21.2), (13.2, 23.6), (16.6, 25.4), (20.4, 25.8)], 1.5, 1.5, gold)
    for x, y in ((5, 10), (6, 9), (21, 25), (22, 24)):        # curled wing tips
        rows[y][x] = G3
    # Sapphire in the guard, gold pommel round a smaller one.
    disc(rows, 10.0, 21.0, 2.6, lambda dx, dy, d: S2 if dx + dy < -1.6 else S1 if d < 1.4 else S0)
    rows[20][9] = EDGE
    disc(rows, 3.4, 27.8, 2.4, lambda dx, dy, d: S1 if d < 1.1 else G3 if dx + dy < -0.8 else G2 if dx + dy < 0.8 else G1)
    rows[27][3] = S2
    # Starlight: little four-point glints round the blade.
    for cx, cy, big in ((27, 7, True), (16, 6, False), (25, 15, False), (30, 11, False), (19, 2, True)):
        for dx, dy in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)) + (((2, 0), (-2, 0), (0, 2), (0, -2)) if big else ()):
            x, y = cx + dx, cy + dy
            if 0 <= x < N and 0 <= y < N and rows[y][x] == T:
                rows[y][x] = EDGE if (dx, dy) == (0, 0) else G3
    return rows


def main():
    img = excalibur()
    assert len(img) == N and all(len(r) == N for r in img)
    assert img[20][9] == EDGE and img[27][3] == S2, "gem glints moved"
    write_png(f"{OUT}/excalibur.png", img)


if __name__ == "__main__":
    main()
