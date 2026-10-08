#!/usr/bin/env python3
"""Item textures for the Elder Warden's loot: deep-dark black and teal, sculk-soul cyan, bone.
Stdlib only.

Not generated here: distant_memory_sword (kept as it is).

Run from the repo root:  PYTHONPATH=tools python3 tools/make_warden_textures.py
"""
import math

from make_golem_king_textures import blank, staff
from make_scarlet_devil_textures import OUT, write_png

T = (0, 0, 0, 0)
PAL = {
    ".": T,
    "K": (6, 16, 20, 255),       # outline
    "d": (10, 40, 48, 255),      # deep dark
    "t": (16, 72, 80, 255),      # sculk teal
    "T": (28, 110, 116, 255),    # light sculk
    "c": (40, 190, 200, 255),    # soul
    "C": (110, 245, 240, 255),   # soul glow
    "W": (220, 255, 250, 255),   # hot core
    "b": (170, 160, 140, 255),   # bone shade
    "B": (226, 218, 196, 255),   # bone
    "k": (80, 60, 30, 255),      # cork
}

GRIDS = {
    "sculk_essence": [
        "......kk........",
        ".....kbbk.......",
        ".....KbbK.......",
        "......KK........",
        ".....KTTK.......",
        "....KtCCtK......",
        "...KtCWCctK.....",
        "...KtcCcCtK.....",
        "..KtcCWCcCtK....",
        "..KtccCCcctK....",
        "..KdtccCcctK....",
        "..KddtcctddK....",
        "...KddtttdK.....",
        "....KKKKKK......",
        "................",
        "................",
    ],
    "sculk_loot_box": [
        "................",
        "..KKKKKKKKKKKK..",
        ".KTttTttTttTtTK.",
        ".KtcdddcddddctK.",
        ".KdddcdddcdddtK.",
        ".KttttttttttttK.",
        ".KKKKKKBBKKKKKK.",
        ".KddtdKBCBKdtdK.",
        ".KdtddBCWCBddtK.",
        ".KdddcKBCBKcddK.",
        ".KdtcddKBKddctK.",
        ".KdcdddddddcddK.",
        ".KddddtdddtdddK.",
        ".KbBbBbBbBbBbBK.",
        "..KKKKKKKKKKKK..",
        "................",
    ],
    "sculk_sensor_leggings": [
        "................",
        "..KKKKKKKKKKKK..",
        "..KbBcBBBBcBbK..",
        "..KtTTTTTTTTtK..",
        "..KtTttttttTtK..",
        "..KtTtKKKKtTtK..",
        "..KtTtK..KtTtK..",
        "..KtctK..KtctK..",
        "..KtTtK..KtTtK..",
        "..KtTtK..KtTtK..",
        "..KtctK..KtctK..",
        "..KtTtK..KtTtK..",
        "..KdddK..KdddK..",
        ".CKbBbK..KbBbKC.",
        "..KKKKK..KKKKK..",
        "................",
    ],
}


def disc(rows, cx, cy, r, paint):
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - cx, y - cy)
            if d <= r + 0.5:
                rows[y][x] = PAL["K"] if d > r - 0.5 else paint(x - cx, y - cy, d)


def sculk_orb():
    """A shell of deep-dark rock split by glowing soul cracks, the light leaking out of it."""
    rows = blank()
    cracks = (0.4, 1.9, 3.3, 4.8)

    def paint(dx, dy, d):
        a = math.atan2(dy, dx) % (2 * math.pi)
        if d < 1.5:
            return PAL["W"]
        if any(min(abs(a - c), 2 * math.pi - abs(a - c)) * d < 0.6 for c in cracks):
            return PAL["C"] if d < 4.0 else PAL["c"]
        lit = dx + dy
        return PAL["T"] if lit < -3 else PAL["t"] if lit < 2 else PAL["d"]

    disc(rows, 7.5, 7.5, 6.2, paint)
    for x, y in ((1, 2), (14, 3), (13, 14)):
        rows[y][x] = PAL["c"]
    return rows


def sculk_medallion():
    """A bone ring set with teeth, a sculk catalyst eye at its heart."""
    rows = blank()

    def paint(dx, dy, d):
        if d > 5.0:
            a = math.atan2(dy, dx)
            return PAL["B"] if int((a + math.pi) / (2 * math.pi) * 12) % 2 == 0 else PAL["b"]
        if d > 4.0:
            return PAL["K"]
        if d < 1.2:
            return PAL["W"]
        if d < 2.2:
            return PAL["C"]
        return PAL["t"] if (dx * dy) > 0 else PAL["d"]

    disc(rows, 7.5, 7.5, 6.6, paint)
    rows[0][7] = rows[0][8] = PAL["K"]
    return rows


def distant_memory_shard():
    """A jagged echo shard with a faint soul burning inside it."""
    rows = blank()
    pts = [(7.5, 0.5), (11.5, 5.0), (10.0, 9.0), (12.5, 13.5), (7.0, 15.0), (3.5, 11.0), (5.0, 6.5), (3.0, 3.0)]

    def inside(x, y):
        hit = False
        for i in range(len(pts)):
            (x1, y1), (x2, y2) = pts[i], pts[(i + 1) % len(pts)]
            if (y1 > y) != (y2 > y) and x < x1 + (y - y1) * (x2 - x1) / (y2 - y1):
                hit = not hit
        return hit

    for y in range(16):
        for x in range(16):
            if inside(x + 0.5, y + 0.5):
                edge = not all(inside(x + 0.5 + ox, y + 0.5 + oy) for ox, oy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
                d = math.hypot(x - 7.5, y - 8.0)
                rows[y][x] = PAL["K"] if edge else PAL["W"] if d < 1.2 else PAL["C"] if d < 2.4 else PAL["T"] if x < 7 else PAL["t"]
    return rows


def sculk_mage_staff():
    """A black bone staff ending in a shrieker's open jaw, a soul flame held between its teeth."""
    rows = blank()
    staff(rows, 2.5, 13.5, 9.0, 7.0, PAL["t"], PAL["d"], PAL["K"], PAL["K"])
    for t in (0.3, 0.65):
        x, y = int(round(2.5 + 6.5 * t)), int(round(13.5 - 6.5 * t))
        rows[y][x] = PAL["B"]
    for x, y, ch in ((9, 7, "b"), (10, 6, "B"), (8, 5, "B"), (8, 4, "b"), (12, 7, "B"), (13, 6, "b"), (13, 5, "B"), (9, 2, "B"), (14, 4, "B")):
        rows[y][x] = PAL[ch]
    disc(rows, 11.0, 4.0, 2.2, lambda dx, dy, d: PAL["W"] if d < 0.9 else PAL["C"] if d < 1.6 else PAL["c"])
    for x, y in ((11, 0), (15, 2), (14, 8)):
        rows[y][x] = PAL["C"]
    return rows


def wardens_call():
    """A curled horn of black sculk ribbed with bone, its bell glowing with soul light."""
    rows = blank()
    spine = []
    for i in range(40):
        t = i / 39.0
        a = math.pi * (0.15 + 1.25 * t)
        r = 5.6 - 2.8 * t
        spine.append((8.0 + math.cos(a) * r, 8.5 - math.sin(a) * r, 2.3 - 1.7 * t, i))
    for yy in range(16):
        for xx in range(16):
            x, y, w, i = min(spine, key=lambda q: math.hypot(xx - q[0], yy - q[1]) - q[2])
            d = math.hypot(xx - x, yy - y)
            if d <= w + 0.5:
                rows[yy][xx] = PAL["K"] if d > w - 0.4 else PAL["B"] if i % 7 == 0 else PAL["T"] if (xx - x) + (yy - y) < 0 else PAL["t"]
    disc(rows, 13.0, 9.6, 1.6, lambda dx, dy, d: PAL["W"] if d < 0.7 else PAL["C"])
    return rows


def main():
    for name, grid in GRIDS.items():
        assert len(grid) == 16 and all(len(r) == 16 for r in grid), (name, [len(r) for r in grid])
        write_png(f"{OUT}/{name}.png", [[PAL[ch] for ch in r] for r in grid])
    # sculk_mage_staff is drawn by tools/make_reworked_textures_a.py now.
    for name, fn in (("sculk_orb", sculk_orb), ("sculk_medallion", sculk_medallion), ("distant_memory_shard", distant_memory_shard),
                     ("wardens_call", wardens_call)):
        write_png(f"{OUT}/{name}.png", fn())


if __name__ == "__main__":
    main()
