#!/usr/bin/env python3
"""The client mod's own particle sprites (white, tinted in code by FfParticle).

They live in textures/particle/, which vanilla's particle atlas stitches for every namespace,
so they need no registered particle type and vanilla clients never see a difference.

Run from the repo root:  PYTHONPATH=tools python3 tools/make_vfx_particles.py
"""
import math

from make_scarlet_devil_textures import write_png

OUT = "resourcepack/assets/fortuneandfavors/textures/particle"
N = 16
C = (N - 1) / 2.0


def sprite(alpha_at):
    rows = []
    for y in range(N):
        row = []
        for x in range(N):
            a = max(0.0, min(1.0, alpha_at(x - C, y - C)))
            row.append((255, 255, 255, int(a * 255)))
        rows.append(row)
    return rows


def glow(dx, dy):
    d = math.hypot(dx, dy) / 7.5
    return (1.0 - d) ** 2.2 if d < 1 else 0.0


def spark(dx, dy):
    arms = max(1.0 - abs(dy) / 1.7 - abs(dx) / 8.0, 1.0 - abs(dx) / 1.7 - abs(dy) / 8.0, 0.0)
    return min(1.0, max(arms * 1.6, glow(dx * 1.6, dy * 1.6) * 1.4))


def mote(dx, dy):
    d = math.hypot(dx, dy) / 3.5
    return (1.0 - d) ** 1.5 if d < 1 else 0.0


def ring(dx, dy):
    return max(0.0, 1.0 - abs(math.hypot(dx, dy) - 6.0) / 1.3)


def shard(dx, dy):
    return max(0.0, 1.0 - abs(dx) / 2.0) * max(0.0, 1.0 - abs(dy) / 7.5) ** 0.6


def rune(dx, dy):
    diamond = max(0.0, 1.0 - abs(abs(dx) + abs(dy) - 6.0) / 1.1)
    cross = max(0.0, 1.0 - min(abs(dx), abs(dy)) / 0.8) * (1.0 if 2.0 < max(abs(dx), abs(dy)) < 4.0 else 0.0)
    return max(diamond, cross, mote(dx * 1.4, dy * 1.4))


def smoke(dx, dy):
    # Lumpy: a soft disc broken up by a few fixed bumps, so a cloud of them never tiles.
    d = math.hypot(dx, dy) / 7.5
    n = 0.5 + 0.25 * math.sin(dx * 1.7 + dy * 0.9) + 0.25 * math.sin(dy * 2.3 - dx * 1.1)
    return ((1.0 - d) ** 1.4) * (0.55 + 0.45 * n) if d < 1 else 0.0


def blob(dx, dy):
    # A glossy gel drop: a solid body, a darker rim and a bright gloss spot top-left. Tinted green
    # in code, so the gloss stays near-white.
    d = math.hypot(dx, dy * 1.1) / 6.8
    if d >= 1.0:
        return 0.0
    gloss = math.hypot(dx + 2.4, dy + 2.6) < 1.6
    return 1.0 if gloss else 0.9 if d < 0.8 else 0.65


def rock(dx, dy):
    # A faceted chunk: an irregular hexagon, lit on its upper-left faces, dark below.
    a = math.atan2(dy, dx)
    r = 6.2 + 1.1 * math.sin(a * 3.0 + 0.7) + 0.6 * math.sin(a * 5.0)
    d = math.hypot(dx, dy)
    if d > r:
        return 0.0
    return 1.0 if dx + dy < -2.0 else 0.82 if dx + dy < 3.0 else 0.62


def ember(dx, dy):
    # A flame tongue: wide and bright at the bottom, licking up to a point.
    t = (dy + 7.5) / 15.0              # 0 at the top, 1 at the bottom
    half = 0.5 + 5.5 * t ** 1.4
    if abs(dx) > half:
        return 0.0
    return (1.0 - abs(dx) / half) ** 0.7 * min(1.0, t * 1.6)


def crystal(dx, dy):
    # A cut gem shard: a tall diamond with one bright facet.
    if abs(dx) / 4.5 + abs(dy) / 7.5 > 1.0:
        return 0.0
    return 1.0 if dx < 0 and dy < 0 else 0.8 if dx < 0 else 0.6


def streak(dx, dy):
    # A speed line: long, thin, brightest in the middle.
    return max(0.0, 1.0 - abs(dy) / 1.2) * max(0.0, 1.0 - abs(dx) / 7.5) ** 0.5


def bubble(dx, dy):
    # A glossy bubble: a thin bright rim, a faint inside, a gloss spot.
    d = math.hypot(dx, dy)
    if d > 7.0:
        return 0.0
    if math.hypot(dx + 2.4, dy + 2.4) < 1.4:
        return 1.0
    return 0.95 if d > 5.6 else 0.18


def arc(dx, dy):
    # A crescent slash: a thin moon, brightest at its belly, fading to points.
    if math.hypot(dx, dy) > 7.3 or math.hypot(dx, dy + 2.6) < 6.6:
        return 0.0
    return max(0.0, 1.0 - abs(dx) / 7.5) ** 0.5


def flare(dx, dy):
    # A lens star: four long rays, four short diagonals, a hot core.
    def ray(a, b, length):
        return max(0.0, 1.0 - abs(b) / 0.8) * max(0.0, 1.0 - abs(a) / length)
    u, v = (dx + dy) * 0.7071, (dx - dy) * 0.7071
    return min(1.0, max(ray(dx, dy, 7.5), ray(dy, dx, 7.5), ray(u, v, 4.2) * 0.7, ray(v, u, 4.2) * 0.7, glow(dx * 2.2, dy * 2.2) * 1.5))


def wisp(dx, dy):
    # A comet: a round head on the right, a soft tail thinning to the left.
    head = glow(dx * 2.0 - 7.0, dy * 2.0) * 1.4
    span = (dx + 7.5) / 11.0
    tail = 0.0 if not 0.0 < span < 1.0 else span * max(0.0, 1.0 - abs(dy) / (0.6 + 1.6 * span))
    return min(1.0, max(head, tail))


def crack(dx, dy):
    # A jagged crack of light with one fork.
    path = 1.6 * math.sin(dx * 1.1) + 0.8 * math.sin(dx * 2.9)
    main = max(0.0, 1.0 - abs(dy - path) / 1.4) * max(0.0, 1.0 - abs(dx) / 7.5) ** 0.3
    fork = 0.0 if dx < 0.0 else max(0.0, 1.0 - abs(dy - path + dx * 0.7) / 1.1) * max(0.0, 1.0 - dx / 5.0)
    return max(main, fork)


def flake(dx, dy):
    # A six-armed snowflake with little side barbs.
    best = 0.0
    for k in range(6):
        a = k * math.pi / 3.0
        along = dx * math.cos(a) + dy * math.sin(a)
        perp = -dx * math.sin(a) + dy * math.cos(a)
        if 0.0 <= along <= 7.0:
            best = max(best, max(0.0, 1.0 - abs(perp) / 0.8))
            for b in (3.5, 5.5):          # barbs, angled outward
                d = abs(abs(perp) - (along - b) * 0.9) if along > b else 9.0
                if along < b + 1.6:
                    best = max(best, max(0.0, 1.0 - d / 0.7) * 0.85)
    return min(1.0, max(best, glow(dx * 3.0, dy * 3.0)))


def icicle(dx, dy):
    # A tall spike, point down: wide and bright at the top, tapering to a needle.
    span = (dy + 7.5) / 15.0          # 0 at the top, 1 at the tip
    half = 3.2 * (1.0 - span) + 0.2
    if abs(dx) > half:
        return 0.0
    return 1.0 if dx < -half * 0.2 else 0.7


def flame(dx, dy):
    # A soul flame: a teardrop tongue, round at the bottom, licking to a point at the top.
    span = (dy + 7.5) / 15.0                       # 0 top, 1 bottom
    half = 4.2 * math.sin(math.pi * min(1.0, span ** 0.7)) * (0.6 + 0.4 * span)
    if abs(dx + 0.8 * math.sin(span * 4.0) * (1.0 - span)) > half:
        return 0.0
    return 1.0 if span > 0.55 and abs(dx) < half * 0.5 else 0.75


def hexrune(dx, dy):
    # A hexagon ring with a dot at its heart: a sigil, a ward, a charm.
    a = math.atan2(dy, dx) % (math.pi / 3.0) - math.pi / 6.0
    r = math.hypot(dx, dy) * math.cos(a)
    return max(max(0.0, 1.0 - abs(r - 5.6) / 0.9), glow(dx * 3.5, dy * 3.5))


def bolt(dx, dy):
    # A lightning bolt: a hard zigzag top to bottom.
    path = 1.4 * (abs((dy + 7.5) % 6.0 - 3.0) - 1.5)
    return max(0.0, 1.0 - abs(dx - path) / 1.7) * max(0.0, 1.0 - abs(dy) / 7.6) ** 0.3


def main():
    for name, fn in [("glow", glow), ("spark", spark), ("mote", mote), ("ring", ring),
                     ("shard", shard), ("rune", rune), ("smoke", smoke), ("blob", blob), ("rock", rock),
                     ("ember", ember), ("crystal", crystal), ("streak", streak), ("bubble", bubble),
                     ("arc", arc), ("flare", flare), ("wisp", wisp), ("crack", crack),
                     ("flake", flake), ("icicle", icicle),
                     ("flame", flame), ("hexrune", hexrune), ("bolt", bolt)]:
        write_png(f"{OUT}/{name}.png", sprite(fn))


if __name__ == "__main__":
    main()
