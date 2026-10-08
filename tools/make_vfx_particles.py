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
    """Rasterises one sprite. A shape returns its alpha, or (brightness, alpha) when part of it
    should stay darker than the tint - a spore's dark heart, a void mote's black centre. Grey,
    never colour: the colour is the tint, so one sprite serves every theme."""
    rows = []
    for y in range(N):
        row = []
        for x in range(N):
            v = alpha_at(x - C, y - C)
            lum, a = (v if isinstance(v, tuple) else (1.0, v))
            a = max(0.0, min(1.0, a))
            g = int(max(0.0, min(1.0, lum)) * 255)
            row.append((g, g, g, int(a * 255)))
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


# ---------------------------------------------------------------- themed sprites (sculk, soul,
# wind, water, clockwork, gems, the void). Before these, the Warden's sculk fell back on the ice
# sprites - snowflakes and icicles in teal - and water and wind borrowed from frost too.

def _seg_dist(px, py, ax, ay, bx, by):
    vx, vy = bx - ax, by - ay
    t = max(0.0, min(1.0, ((px - ax) * vx + (py - ay) * vy) / max(1e-9, vx * vx + vy * vy)))
    return math.hypot(px - ax - vx * t, py - ay - vy * t), t


def _polyline(points):
    """Distance from a point to a sampled curve, and how far along it (0..1) the nearest bit is."""
    def at(px, py):
        best, where = 99.0, 0.0
        n = len(points) - 1
        for i in range(n):
            d, t = _seg_dist(px, py, *points[i], *points[i + 1])
            if d < best:
                best, where = d, (i + t) / n
        return best, where
    return at


def _curl(x, y, heading, steps, step, turn):
    pts = [(x, y)]
    for i in range(steps):
        heading += turn(i / steps)
        x += math.cos(heading) * step
        y += math.sin(heading) * step
        pts.append((x, y))
    return pts


# A vein that creeps up from the bottom-left corner and winds into a tight inward curl, with one
# short offshoot from its stem - the shape sculk veins take as they spread across a floor.
def _tendril_points():
    pts = []
    for i in range(10):                          # the stem, bowing slightly
        t = i / 10.0
        pts.append((-6.9 + 4.6 * t + 1.2 * math.sin(t * math.pi), 6.9 - 7.4 * t))
    k = math.log(4.5 / 0.9) / (2.4 * math.pi)
    for i in range(41):                          # the curl: a spiral tightening inward
        th = math.pi + 2.4 * math.pi * i / 40.0
        r = 4.5 * math.exp(-k * (th - math.pi))
        pts.append((2.2 + math.cos(th) * r, -0.5 + math.sin(th) * r))
    return pts


_TENDRIL = _polyline(_tendril_points())
_TENDRIL_BRANCH = _polyline([(-4.6, 3.6), (-2.6, 4.6), (-0.8, 4.4), (0.4, 5.6)])


def sculk_tendril(dx, dy):
    d, s = _TENDRIL(dx, dy)
    width = 1.45 - 0.85 * s                      # thick at the root, a hair at the curl
    vein = max(0.0, 1.0 - d / width)
    bd, bs = _TENDRIL_BRANCH(dx, dy)
    branch = max(0.0, 1.0 - bd / (0.85 - 0.4 * bs)) * 0.85
    node = glow((dx + 4.6) * 3.4, (dy - 3.6) * 3.4) * 1.4      # a pulse bead where it forks
    a = min(1.0, max(vein, branch, node))
    # A bright core down the middle of the vein, dimmer flesh at its edges.
    return (0.6 + 0.4 * max(vein, node), a)


def sculk_spore(dx, dy):
    # A soft orb that glows at its rim and is dark at its heart, like a sculk sensor's bulb.
    d = math.hypot(dx, dy)
    if d > 7.5:
        return 0.0
    rim = max(0.0, 1.0 - abs(d - 4.4) / 1.9)
    halo = (1.0 - d / 7.5) ** 2.0 * 0.6
    core = d < 3.0
    gloss = math.hypot(dx + 1.6, dy + 1.7) < 1.0
    if gloss:
        return (1.0, 0.95)
    if core:
        return (0.18 + 0.1 * d / 3.0, 0.92)
    return (0.55 + 0.45 * rim, max(rim, halo))


def soul_wisp(dx, dy):
    # A soul flame streaming upward: a round bright head low down, and above it a tongue that
    # sways side to side and thins to a wisp - a flame with somewhere to go.
    head_d = math.hypot(dx, dy - 3.3)
    head = max(0.0, 1.0 - max(0.0, head_d - 2.4) / 1.6)
    span = (dy + 7.5) / 10.8                     # 0 at the top, 1 at the head's middle
    tail = 0.0
    if 0.0 <= span <= 1.0:
        sway = 1.9 * math.sin(span * 5.2 + 0.6) * (1.0 - span) ** 1.2
        half = 0.35 + 2.9 * span ** 1.3
        x = dx - sway
        if abs(x) < half:
            tail = (1.0 - abs(x) / half) ** 0.5 * min(1.0, 0.2 + span * 1.3)
    core = head_d < 1.6
    return (1.0 if core else 0.78 + 0.22 * head, min(1.0, max(head, tail)))


def petal(dx, dy):
    # A petal: an almond pointed at the top, a notch at the round end, a faint middle vein.
    span = (dy + 7.0) / 14.0
    if not 0.0 <= span <= 1.0:
        return 0.0
    half = 4.3 * math.sin(math.pi * span ** 0.8) ** 0.9
    x = dx + 0.9 * math.sin(span * 3.0)          # a gentle curl
    if abs(x) > half or (span > 0.9 and abs(x) < 0.9):
        return 0.0
    vein = abs(x) < 0.45 and span < 0.85
    return (0.72 if vein else 0.95 if x < 0 else 0.8, 1.0)


def feather(dx, dy):
    # A feather lying on the diagonal: a quill from bottom-left to top-right, vanes either side
    # combed into barbs, and a split near the tip.
    u = (dx - dy) * 0.7071                       # along the quill
    v = (dx + dy) * 0.7071                       # across it
    if not -7.0 <= u <= 7.0:
        return 0.0
    shaft = max(0.0, 1.0 - abs(v) / 0.55) if u > -7.0 else 0.0
    t = (u + 7.0) / 14.0
    width = 3.4 * math.sin(math.pi * min(1.0, max(0.0, (t - 0.12) / 0.88))) ** 0.8
    if abs(v) < width and t > 0.12:
        barb = 0.55 + 0.45 * (0.5 + 0.5 * math.sin((u - abs(v) * 0.9) * 2.6))
        split = 0.15 if abs(u - 3.0 - abs(v) * 0.6) < 0.4 else 1.0
        vane = barb * split * (1.0 - abs(v) / width * 0.35)
        return (0.85 + 0.15 * shaft, max(shaft, vane))
    return shaft


def droplet(dx, dy):
    # A drop of water (or blood): round at the bottom, pulled to a point at the top, a gloss
    # spot on the round and a darker belly.
    cy = 2.0
    d = math.hypot(dx, dy - cy)
    r = 4.6
    if dy >= cy:
        inside = d <= r
    else:
        top = (cy - dy) / 9.0                    # 0 at the round, 1 at the tip
        inside = top <= 1.0 and abs(dx) <= r * (1.0 - top) ** 1.3
    if not inside:
        return 0.0
    if math.hypot(dx + 1.6, dy - 0.6) < 1.2:
        return (1.0, 1.0)
    return (0.95 if dx < 0 else 0.72, 0.95 if d < r - 1.0 or dy < cy else 1.0)


def cog(dx, dy):
    # A small gear: eight square teeth round a solid rim, an axle hole in the middle, lit from
    # the top-left so it reads as metal when it turns.
    d = math.hypot(dx, dy)
    a = math.atan2(dy, dx)
    tooth = (a * 8.0 / (2.0 * math.pi)) % 1.0
    outer = 7.4 if 0.22 < tooth < 0.72 else 5.5
    if d > outer or d < 1.7:
        return 0.0
    if 2.9 < d < 3.7:
        return (0.55, 1.0)                       # the groove between hub and rim
    lit = 1.0 if dx + dy < -1.0 else 0.8 if dx + dy < 2.5 else 0.66
    return (lit, 1.0)


def star(dx, dy):
    # A four-point twinkle: long concave rays on the axes, short faint diagonals, a hot core.
    def ray(along, across, length, fat):
        f = abs(along) / length
        if f >= 1.0:
            return 0.0
        width = 0.35 + fat * (1.0 - f) ** 2.2
        return max(0.0, 1.0 - abs(across) / width) * (1.0 - f) ** 0.5
    u, v = (dx + dy) * 0.7071, (dx - dy) * 0.7071
    rays = max(ray(dx, dy, 7.6, 1.9), ray(dy, dx, 7.6, 1.9), ray(u, v, 3.6, 0.8) * 0.55, ray(v, u, 3.6, 0.8) * 0.55)
    return min(1.0, max(rays * 1.2, glow(dx * 2.4, dy * 2.4) * 1.6))


def gem(dx, dy):
    # An emerald cut: an octagon, a flat bright table in the middle, facets lit by a light
    # from the top-left - each face its own shade so it glints as it turns.
    if abs(dx) > 6.0 or abs(dy) > 7.2 or abs(dx) + abs(dy) > 10.4:
        return 0.0
    if abs(dx) < 3.0 and abs(dy) < 4.2:
        return (1.0 if dx + dy < -1.5 else 0.88, 1.0)            # the table
    if abs(dx) + abs(dy) > 8.8:
        return (0.55, 1.0)                                      # the girdle
    if dy < -4.2:
        return (0.95, 1.0)
    if dx < -3.0:
        return (0.82, 1.0)
    if dx > 3.0:
        return (0.6, 1.0)
    return (0.5, 1.0)


def thread(dx, dy):
    # A thin wavy line running top to bottom, a bead of light where it catches.
    path = 1.6 * math.sin(dy * 0.55)
    line = max(0.0, 1.0 - abs(dx - path) / 0.75)
    return min(1.0, max(line, glow((dx - path) * 3.5, (dy - 3.0) * 3.5) * 1.3))


def void_mote(dx, dy):
    # A dark-centred ring: a black hole with a thin bright event horizon and a faint halo.
    d = math.hypot(dx, dy)
    if d > 7.5:
        return 0.0
    rim = max(0.0, 1.0 - abs(d - 4.8) / 1.2)
    if d < 4.0:
        return (0.05, 0.9)
    halo = (1.0 - d / 7.5) ** 1.6 * 0.5
    return (0.4 + 0.6 * rim, max(rim, halo))


def rune2(dx, dy):
    # A second glyph set: a circle round an upturned triangle, a bar through its foot and a dot
    # over the apex - older and stranger than the diamond rune.
    d = math.hypot(dx, dy)
    circle = max(0.0, 1.0 - abs(d - 6.6) / 0.85)
    # Triangle apex (0,-4.6), base from (-4,2.6) to (4,2.6).
    tri = 99.0
    pts = [(0.0, -4.6), (4.0, 2.6), (-4.0, 2.6)]
    for i in range(3):
        tri = min(tri, _seg_dist(dx, dy, *pts[i], *pts[(i + 1) % 3])[0])
    triangle = max(0.0, 1.0 - tri / 0.8)
    bar = max(0.0, 1.0 - abs(dy - 4.3) / 0.7) * (1.0 if abs(dx) < 2.6 else 0.0)
    dot = glow(dx * 4.5, (dy + 1.0) * 4.5) * 1.4
    return min(1.0, max(circle * 0.85, triangle, bar, dot))


def shock(dx, dy):
    # A sonic crescent: a thick bow bulging to the right (the way it travels), a bright leading
    # edge, a fainter echo arc trailing inside it.
    def band(cx, r, w):
        return max(0.0, 1.0 - abs(math.hypot(dx - cx, dy) - r) / w)
    reach = max(0.0, 1.0 - (abs(dy) / 7.3) ** 2.4)
    lead = band(-6.0, 11.0, 1.4) * (1.0 if dx > -4.0 else 0.0)
    echo = band(-6.0, 7.5, 1.0) * 0.55 * (1.0 if dx > -5.5 else 0.0)
    return min(1.0, max(lead, echo) * reach * 1.15)


def main():
    for name, fn in [("glow", glow), ("spark", spark), ("mote", mote), ("ring", ring),
                     ("shard", shard), ("rune", rune), ("smoke", smoke), ("blob", blob), ("rock", rock),
                     ("ember", ember), ("crystal", crystal), ("streak", streak), ("bubble", bubble),
                     ("arc", arc), ("flare", flare), ("wisp", wisp), ("crack", crack),
                     ("flake", flake), ("icicle", icicle),
                     ("flame", flame), ("hexrune", hexrune), ("bolt", bolt),
                     ("sculk_tendril", sculk_tendril), ("sculk_spore", sculk_spore), ("soul_wisp", soul_wisp),
                     ("petal", petal), ("feather", feather), ("droplet", droplet), ("cog", cog),
                     ("star", star), ("gem", gem), ("thread", thread), ("void_mote", void_mote),
                     ("rune2", rune2), ("shock", shock)]:
        write_png(f"{OUT}/{name}.png", sprite(fn))


if __name__ == "__main__":
    main()
