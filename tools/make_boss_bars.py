#!/usr/bin/env python3
"""Per-boss boss bar sprites (182x5), drawn by the client mod's BossHealthOverlayMixin.

Each boss is four colours: frame, fill start, fill end, accent. The background is the empty
frame; the progress sprite is the filled bar the game crops to the boss's health.

Run from the repo root:  PYTHONPATH=tools python3 tools/make_boss_bars.py
"""
from make_scarlet_devil_textures import write_png

OUT = "resourcepack/assets/fortuneandfavors/textures/gui/sprites/boss_bar"
W, H = 182, 5

BOSSES = {
    #                     frame            fill start        fill end          accent
    "time_lord":          ((34, 14, 52),   (110, 46, 190),   (196, 120, 255),  (255, 220, 120)),
    "slime_king":         ((14, 46, 18),   (60, 170, 60),    (140, 230, 90),   (210, 255, 170)),
    "scarlet_devil":      ((40, 6, 16),    (150, 18, 34),    (226, 60, 70),    (255, 214, 120)),
    "emerald_sovereign":  ((8, 40, 24),    (20, 150, 80),    (90, 230, 140),   (240, 210, 90)),
    "puppeteer":          ((30, 12, 40),   (110, 40, 140),   (200, 90, 200),   (240, 220, 255)),
    "clockwork_king":     ((40, 28, 10),   (150, 100, 30),   (230, 180, 80),   (255, 240, 190)),
    "starbound_magister": ((10, 14, 44),   (40, 60, 170),    (120, 170, 255),  (255, 255, 255)),
    "drowned_sovereign":  ((6, 34, 40),    (20, 120, 130),   (80, 210, 200),   (200, 255, 240)),
    "void_shaper":        ((10, 4, 16),    (60, 20, 100),    (150, 60, 220),   (220, 170, 255)),
    "gale_warden":        ((20, 40, 56),   (110, 170, 210),  (210, 240, 255),  (255, 255, 255)),
    "stone_golem":        ((30, 30, 32),   (100, 100, 104),  (170, 168, 160),  (230, 226, 210)),
    "snow_queen":         ((22, 46, 92),   (110, 170, 230),  (220, 245, 255),  (255, 255, 255)),
    "elder_warden":       ((10, 40, 44),   (60, 140, 130),   (120, 210, 190),  (220, 250, 230)),
    "wither_king":        ((14, 14, 16),   (70, 70, 74),     (200, 196, 180),  (240, 236, 220)),
    "mindbinder":         ((40, 10, 34),   (170, 50, 140),   (250, 120, 200),  (255, 220, 240)),
    "ender_dragon":       ((24, 6, 30),    (150, 40, 180),   (230, 110, 240),  (255, 220, 255)),
    "wither":             ((12, 10, 14),   (60, 50, 70),     (140, 120, 160),  (220, 210, 230)),
}


def mix(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c)


def bar(frame, start, end, accent, filled):
    rows = []
    for y in range(H):
        row = []
        for x in range(W):
            edge = y in (0, H - 1) or x in (0, W - 1)
            if edge:
                c = shade(frame, 0.8) if (x + y) % 2 else frame
            elif not filled:
                # Empty track: the frame colour lifted a little, with a notch every 10% so the
                # bar still reads as a scale when it is empty.
                c = shade(frame, 1.6) if x % 18 == 0 else shade(frame, 1.25)
            else:
                c = mix(start, end, x / (W - 1))
                if y == 1:
                    c = mix(c, accent, 0.45)
                elif y == 3:
                    c = shade(c, 0.72)
                if y == 1 and x % 12 == 6:
                    c = accent
            row.append(c + (255,))
        rows.append(row)
    return rows


# ---------------------------------------------------------------- signature bars
# The dragon, the Time Lord and the Slime King get a patterned fill, a phase variant where their
# bar changes colour, and a frame (FW x FH) with end caps, drawn around the bar by the mixin.

FW, FH = 198, 11   # frame: 8 px caps either side, drawn at (x - 8, y - 3)


def patterned(frame, start, end, accent, pattern):
    rows = bar(frame, start, end, accent, True)
    for y in range(1, H - 1):
        for x in range(1, W - 1):
            k = pattern(x, y)
            if k:
                c = rows[y][x][:3]
                rows[y][x] = (shade(c, k) if k < 1.0 else mix(c, accent, k - 1.0)) + (255,)
    return rows


def scales(x, y):
    # Overlapping scales: a darker crescent every 6 px, offset each row.
    u = (x + (3 if y % 2 else 0)) % 6
    return 0.7 if u == 0 else 0.85 if u == 5 and y == 2 else 0


def ticks(x, y):
    # Clock ticks: a gold mark every 9 px, a taller one every 45.
    if x % 45 == 0:
        return 1.9
    return 1.7 if x % 9 == 0 and y >= 2 else 0


def bubbles(x, y):
    for bx, by in ((x % 14, y), ((x + 7) % 14, y - 1)):
        if (bx - 4) ** 2 + (by - 2) ** 2 * 3 <= 3:
            return 1.6 if bx == 3 and by <= 2 else 1.25
    return 0


def frame_png(border, light, cap):
    rows = [[(0, 0, 0, 0)] * FW for _ in range(FH)]
    # A one-pixel rule hugging the bar, top and bottom.
    for x in range(7, FW - 7):
        rows[2][x] = border + (255,)
        rows[8][x] = border + (255,)
        if x % 2 == 0:
            rows[2][x] = light + (255,)
    for side in (0, 1):
        for y in range(FH):
            for x in range(8):
                v = cap(x, y)
                if v:
                    xx = x if side == 0 else FW - 1 - x
                    rows[y][xx] = (border if v == 1 else light if v == 2 else (255, 255, 255)) + (255,)
    return rows


def horn(x, y):
    # A swept dragon horn opening toward the bar.
    shape = ["....11..", "...121..", "..1221..", ".12211..", "1222111.", ".12211..", "..1221..", "...121..", "....11..", "........", "........"]
    return {"1": 1, "2": 2}.get(shape[y][x], 0)


def dial(x, y):
    # A little clock face: rim, hands, a bright centre.
    d = (x - 3.5) ** 2 + (y - 4.5) ** 2
    if 9.0 <= d <= 15.5:
        return 1
    if d < 9.0:
        if (x == 3 and 2 <= y <= 4) or (y == 4 and 3 <= x <= 5):
            return 3
        return 2
    return 0


def drop(x, y):
    # A slime drop wearing a three-point crown.
    shape = ["3.3.3...", "33333...", "........", "..11....", ".1221...", "122221..", "122321..", "122221..", ".1221...", "..11....", "........"]
    return {"1": 1, "2": 2, "3": 3}.get(shape[y][x], 0)


def cracks(x, y):
    # Glowing fissures wandering along the bar through near-black.
    lane = 1 + ((x * 5) // 7) % 3
    return 1.9 if y == lane and x % 13 < 9 else 0.55 if (x * 13 + y * 7) % 11 == 0 else 0


def ash(x, y):
    # Soot flecks.
    return 0.55 if (x * 13 + y * 7) % 9 == 0 else 0


def bolt(x, y):
    # A zigzag of lightning running the length of the bar.
    step = (x // 3) % 4
    lane = (1, 2, 3, 2)[step]
    return 1.9 if y == lane else 0


def cracked_horn(x, y):
    shape = ["....11..", "...131..", "..1221..", ".12311..", "1222111.", ".13211..", "..1231..", "...121..", "....11..", "........", "........"]
    return {"1": 1, "2": 2, "3": 3}.get(shape[y][x], 0)


def skull(x, y):
    shape = ["........", ".11111..", "1222221.", "1232321.", "1222221.", ".12221..", ".1.1.1..", "........", "........", "........", "........"]
    return {"1": 1, "2": 2, "3": 3}.get(shape[y][x], 0)


def cobble(x, y):
    # Cobbles: a dark joint every few pixels, staggered by row, and an amethyst seam through the middle.
    if y == 2 and x % 23 < 14:
        return 1.8
    return 0.65 if (x + (y * 3)) % 5 == 0 else 0


def ribs(x, y):
    # Bone ribs crossing a soul-lit marrow.
    return 1.7 if x % 8 in (0, 1) else 0.7 if y == 3 else 0


def chunk(x, y):
    shape = ["........", "..111...", ".12221..", "1222221.", "1223221.", "1222211.", ".12211..", "..111...", "........", "........", "........"]
    return {"1": 1, "2": 2, "3": 3}.get(shape[y][x], 0)


def crowned_skull(x, y):
    shape = ["3.3.3...", "33333...", ".11111..", "1222221.", "1212121.", "1222221.", ".12221..", ".1.1.1..", "........", "........", "........"]
    return {"1": 1, "2": 2, "3": 3}.get(shape[y][x], 0)


def brainwaves(x, y):
    # A psychic trace: a bright sine line wandering through the bar, an eye-spark every 30 px.
    import math
    if x % 30 == 15 and y == 2:
        return 1.9
    return 1.6 if round(2 + 1.4 * math.sin(x * 0.35)) == y else 0


def eye(x, y):
    shape = ["........", "..1111..", ".122221.", "12233221", "12344321", "12233221", ".122221.", "..1111..", "........", "........", "........"]
    return {"1": 1, "2": 2, "3": 1, "4": 3}.get(shape[y][x], 0)


def frost(x, y):
    # Frost ferns: a bright flake every 16 px, and thin rime lines leaning off it.
    u = x % 16
    if u == 8 and y == 2 or u in (7, 9) and y in (1, 3):
        return 1.9
    return 1.4 if (u + y) % 5 == 0 and y != 2 else 0


def flake_cap(x, y):
    shape = ["...3....", ".1.2.1..", "..121...", "3222223.", "..121...", ".1.2.1..", "...3....", "........", "........", "........", "........"]
    return {"1": 1, "2": 2, "3": 3}.get(shape[y][x], 0)


def soul_pulse(x, y):
    # A heartbeat trace: flat, then a sharp spike every 32 px.
    u = x % 32
    spike = {14: 3, 15: 1, 16: 0, 17: 4, 18: 2}.get(u)
    if spike is not None:
        return 1.9 if y == spike else 0
    return 1.5 if y == 2 else 0


def horns(x, y):
    shape = ["1.......", "21......", ".21.....", "..2111..", "..1331..", "..1331..", "..2111..", ".21.....", "21......", "1.......", "........"]
    return {"1": 1, "2": 2, "3": 3}.get(shape[y][x], 0)


def banner_stripes(x, y):
    # Illager banner: a gold chevron every 20 px over a dark stripe.
    u = x % 20
    if abs(u - 10) == 3 - abs(y - 2):
        return 1.9
    return 0.7 if u in (0, 1) else 0


def axe_cap(x, y):
    shape = ["..11....", ".1221...", "123321..", "1233211.", "123321..", ".1221...", "..11....", "...1....", "...1....", "........", "........"]
    return {"1": 1, "2": 2, "3": 3}.get(shape[y][x], 0)


SIGNATURE = {
    # key: (frame, start, end, accent, pattern, (frame border, frame light, cap))
    "ender_dragon":        ((24, 6, 30), (150, 40, 180), (230, 110, 240), (255, 220, 255), scales, ((40, 10, 52), (200, 120, 230), horn)),
    "ender_dragon_red":    ((40, 6, 12), (170, 24, 40), (255, 90, 90), (255, 220, 200), scales, ((60, 10, 16), (255, 120, 120), horn)),
    "ender_dragon_purple": ((8, 2, 14), (30, 8, 48), (70, 20, 100), (255, 120, 255), cracks, ((20, 4, 32), (255, 110, 255), cracked_horn)),
    "time_lord":           ((34, 14, 52), (110, 46, 190), (196, 120, 255), (255, 220, 120), ticks, ((70, 46, 14), (240, 196, 90), dial)),
    "time_lord_red":       ((44, 10, 20), (170, 30, 60), (255, 110, 120), (255, 220, 120), ticks, ((70, 46, 14), (240, 196, 90), dial)),
    "wither":              ((12, 10, 14), (54, 46, 60), (120, 104, 128), (230, 220, 235), ash, ((30, 26, 34), (200, 190, 210), skull)),
    "wither_supercharged": ((14, 6, 30), (70, 30, 150), (120, 80, 240), (190, 240, 255), bolt, ((30, 14, 60), (150, 210, 255), skull)),
    "stone_golem":         ((26, 24, 28), (100, 96, 92), (160, 154, 144), (200, 140, 255), cobble, ((40, 38, 42), (170, 166, 156), chunk)),
    "wither_king":         ((10, 10, 14), (40, 70, 96), (90, 200, 240), (232, 224, 204), ribs, ((24, 22, 28), (226, 218, 196), crowned_skull)),
    "mindbinder":          ((26, 8, 40), (90, 30, 150), (176, 76, 255), (120, 230, 255), brainwaves, ((40, 14, 60), (190, 130, 255), eye)),
    "mindbinder_red":      ((40, 6, 24), (150, 20, 90), (255, 70, 170), (120, 230, 255), brainwaves, ((60, 10, 40), (255, 110, 200), eye)),
    "snow_queen":          ((20, 40, 70), (130, 190, 230), (220, 245, 255), (255, 255, 255), frost, ((30, 60, 100), (200, 236, 255), flake_cap)),
    "snow_queen_blue":     ((10, 24, 70), (40, 110, 210), (120, 200, 255), (230, 250, 255), frost, ((20, 40, 90), (150, 210, 255), flake_cap)),
    "snow_queen_red":      ((40, 14, 40), (150, 60, 140), (230, 140, 220), (230, 250, 255), frost, ((60, 20, 60), (200, 236, 255), flake_cap)),
    "elder_warden":        ((4, 16, 20), (14, 70, 80), (30, 170, 170), (150, 255, 245), soul_pulse, ((8, 30, 36), (60, 220, 210), horns)),
    "elder_warden_red":    ((20, 6, 10), (90, 20, 40), (40, 200, 190), (150, 255, 245), soul_pulse, ((40, 10, 16), (60, 220, 210), horns)),
    "raid":                ((30, 10, 10), (120, 20, 24), (210, 50, 40), (255, 210, 90), banner_stripes, ((50, 30, 20), (226, 176, 66), axe_cap)),
    "slime_king":          ((14, 46, 18), (60, 170, 60), (140, 230, 90), (220, 255, 190), bubbles, ((20, 70, 24), (120, 220, 90), drop)),
}


# ---------------------------------------------------------------- the eight reworked bosses
# Each gets a fill pattern drawn from its own material, an end cap of its emblem, and a red
# phase-two variant the client switches to when the server turns the bar red.

def blood_drips(x, y):
    # Blood running down from the top edge, a long drip every 11 px and a short one between.
    u = x % 11
    if u == 3:
        return 0.55 if y >= 1 else 0
    if u == 8:
        return 0.6 if y <= 2 else 0
    return 1.5 if y == 1 and u in (2, 4, 7, 9) else 0


def bat_cap(x, y):
    shape = ["........", "1.....1.", "21...12.", "221.122.", "2223322.", ".22332..", "..2222..", "...11...", "........", "........", "........"]
    return {"1": 1, "2": 2, "3": 3}.get(shape[y][x], 0)


def facets(x, y):
    # A cut-gem lattice: diagonals crossing every 8 px, a bright facet where they meet.
    u = (x + y) % 8
    v = (x - y) % 8
    if u == 0 and v == 0:
        return 1.9
    return 0.75 if u == 0 or v == 0 else 0


def crown_cap(x, y):
    shape = ["........", "3..3..3.", "2..2..2.", "22222222", "21313121", "22222222", "11111111", "........", "........", "........", "........"]
    return {"1": 1, "2": 2, "3": 3}.get(shape[y][x], 0)


def strings(x, y):
    # Marionette strings running across the bar, a knot on each every 10 px.
    u = x % 10
    if u == 5:
        return 1.8
    return 1.35 if y == 1 or y == 3 and u in (2, 8) else 0


def mask_cap(x, y):
    shape = ["........", "..2222..", ".222222.", "21122112", "22222222", "22222222", ".23333..", "..2222..", "........", "........", "........"]
    return {"1": 1, "2": 2, "3": 3}.get(shape[y][x], 0)


def cogs(x, y):
    # Cog teeth along both edges, a rivet every 15 px down the middle.
    if y in (1, 3) and x % 4 in (0, 1):
        return 0.7
    return 1.8 if y == 2 and x % 15 == 7 else 0


def cog_cap(x, y):
    shape = ["..1.1...", ".122221.", "1222222.", ".213312.", "1231132.", ".213312.", "1222222.", ".122221.", "..1.1...", "........", "........"]
    return {"1": 1, "2": 2, "3": 3}.get(shape[y][x], 0)


def constellation(x, y):
    # Sparse stars, and a faint line joining them into a constellation.
    stars = {7: 1, 23: 3, 41: 2, 58: 1, 77: 3, 96: 2, 113: 1, 131: 3, 150: 2, 168: 1}
    if stars.get(x) == y:
        return 1.95
    for sx, sy in stars.items():
        if 0 < x - sx < 10 and y == 2:
            return 1.3
    return 0


def star_cap(x, y):
    shape = ["...3....", "...2....", "...2....", "..121...", "3222223.", "..121...", "...2....", "...2....", "...3....", "........", "........"]
    return {"1": 1, "2": 2, "3": 3}.get(shape[y][x], 0)


def waves(x, y):
    # A rolling crest, white where the wave breaks.
    import math
    crest = round(2 + 1.2 * math.sin(x * 0.28))
    if y == crest:
        return 1.85 if x % 22 < 4 else 1.4
    return 0.7 if y > crest else 0


def trident_cap(x, y):
    shape = ["1.1.1...", "2.2.2...", "2.2.2...", "22222...", "..2.....", "..2.....", "..3.....", "..2.....", "..1.....", "........", "........"]
    return {"1": 1, "2": 2, "3": 3}.get(shape[y][x], 0)


def rifts(x, y):
    # Tears in the bar: near-black voids every 17 px with bright edges.
    u = x % 17
    if 6 <= u <= 10:
        return 1.9 if u in (6, 10) else 0.3
    return 0


def rune_cap(x, y):
    shape = ["........", ".111111.", ".122221.", ".123321.", ".133331.", ".123321.", ".122221.", ".111111.", "........", "........", "........"]
    return {"1": 1, "2": 2, "3": 3}.get(shape[y][x], 0)


def gusts(x, y):
    # Wind streaks leaning across the bar.
    return 1.75 if (x - y * 3) % 13 in (0, 1) else 0


def gust_cap(x, y):
    shape = ["........", ".2222...", "2....2..", "..22..2.", ".2..2.2.", ".2.3.2..", ".2..22..", "..2.....", "...2222.", "........", "........"]
    return {"1": 1, "2": 2, "3": 3}.get(shape[y][x], 0)


SIGNATURE.update({
    "scarlet_devil":          ((40, 6, 16), (150, 18, 34), (226, 60, 70), (255, 214, 120), blood_drips, ((60, 10, 20), (230, 70, 80), bat_cap)),
    "scarlet_devil_red":      ((30, 2, 8), (110, 6, 20), (255, 40, 60), (255, 230, 200), blood_drips, ((50, 4, 12), (255, 90, 100), bat_cap)),
    "emerald_sovereign":      ((8, 40, 24), (20, 150, 80), (90, 230, 140), (240, 210, 90), facets, ((20, 60, 34), (240, 210, 90), crown_cap)),
    "emerald_sovereign_red":  ((40, 20, 8), (150, 60, 20), (230, 170, 60), (255, 240, 160), facets, ((60, 30, 12), (240, 210, 90), crown_cap)),
    "puppeteer":              ((30, 12, 40), (110, 40, 140), (200, 90, 200), (240, 220, 255), strings, ((44, 18, 58), (220, 190, 250), mask_cap)),
    "puppeteer_red":          ((40, 6, 24), (150, 20, 70), (230, 60, 120), (240, 220, 255), strings, ((60, 10, 36), (255, 150, 190), mask_cap)),
    "clockwork_king":         ((40, 28, 10), (150, 100, 30), (230, 180, 80), (255, 240, 190), cogs, ((60, 42, 16), (240, 200, 110), cog_cap)),
    "clockwork_king_red":     ((44, 14, 8), (170, 60, 20), (255, 130, 60), (130, 230, 255), cogs, ((60, 22, 12), (255, 160, 90), cog_cap)),
    "starbound_magister":     ((10, 14, 44), (40, 60, 170), (120, 170, 255), (255, 255, 255), constellation, ((20, 26, 70), (190, 220, 255), star_cap)),
    "starbound_magister_red": ((30, 8, 44), (120, 40, 170), (240, 120, 255), (255, 255, 255), constellation, ((44, 14, 70), (240, 190, 255), star_cap)),
    "drowned_sovereign":      ((6, 34, 40), (20, 120, 130), (80, 210, 200), (200, 255, 240), waves, ((10, 50, 60), (150, 240, 230), trident_cap)),
    "drowned_sovereign_red":  ((4, 14, 30), (14, 50, 110), (60, 140, 230), (200, 255, 240), waves, ((8, 24, 50), (120, 190, 255), trident_cap)),
    "void_shaper":            ((10, 4, 16), (60, 20, 100), (150, 60, 220), (220, 170, 255), rifts, ((24, 10, 36), (200, 150, 255), rune_cap)),
    "void_shaper_red":        ((20, 2, 14), (100, 10, 80), (230, 50, 200), (255, 190, 250), rifts, ((40, 6, 30), (255, 140, 240), rune_cap)),
    "gale_warden":            ((20, 40, 56), (110, 170, 210), (210, 240, 255), (255, 255, 255), gusts, ((30, 60, 80), (220, 245, 255), gust_cap)),
    "gale_warden_red":        ((40, 30, 50), (140, 120, 190), (230, 210, 255), (255, 255, 255), gusts, ((60, 44, 80), (240, 230, 255), gust_cap)),
})


def shatter(rows):
    """Knocks slanted gaps out of a bar (fully transparent) and runs white-hot cracks through what is left."""
    for y in range(H):
        for x in range(W):
            lean = x + y          # gaps lean, so they read as breaks rather than as a ruler
            if lean % 23 < 4 + (x // 23) % 2 or (40 < x < 52 and y < 2) or (110 < x < 121 and y > 2):
                rows[y][x] = (0, 0, 0, 0)
            elif 0 < y < H - 1 and (x * 7 + y * 13) % 29 == 0 and rows[y][x][3]:
                rows[y][x] = (255, 220, 200, 255)
    return rows


def main():
    for key, (frame, start, end, accent) in BOSSES.items():
        write_png(f"{OUT}/{key}_background.png", bar(frame, start, end, accent, False))
        write_png(f"{OUT}/{key}_progress.png", bar(frame, start, end, accent, True))
    # The dragon's last stand: its crimson bar, shattered.
    red = SIGNATURE["ender_dragon_red"]
    write_png(f"{OUT}/ender_dragon_shattered_background.png", shatter(bar(red[0], red[1], red[2], red[3], False)))
    write_png(f"{OUT}/ender_dragon_shattered_progress.png", shatter(patterned(red[0], (120, 10, 24), (255, 60, 70), (255, 230, 210), cracks)))
    write_png(f"{OUT}/ender_dragon_shattered_frame.png", frame_png((60, 10, 16), (255, 90, 90), cracked_horn))
    for key, (frame, start, end, accent, pattern, (border, light, cap)) in SIGNATURE.items():
        write_png(f"{OUT}/{key}_background.png", bar(frame, start, end, accent, False))
        write_png(f"{OUT}/{key}_progress.png", patterned(frame, start, end, accent, pattern))
        write_png(f"{OUT}/{key}_frame.png", frame_png(border, light, cap))


if __name__ == "__main__":
    main()
