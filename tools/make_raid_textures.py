#!/usr/bin/env python3
"""Regenerates the 16x16 art for the whole Raid Loot Box pool.

Ten textures, one palette, one light direction (upper left), and one silhouette each so
the drops tell themselves apart in a hotbar:

    raid_loot_box, warlord_axe, captain_horn, warlord_cloak, warlord_trophy,
    raiders_upgrader, evoker_spellbook, evoker_cloak, illusioner_spellbook,
    illusioner_cloak

Every shape is placed through tools/pixelkit.py and finished with its selective outline,
so a gold edge is rimmed in dark brown and a blue one in navy rather than in flat black.
The evoker and illusioner pieces used to be drawn by tools/make_fix_textures.py; they live
here now so the whole pool is redrawn in one pass and shares a palette.

Run:  python3 tools/make_raid_textures.py [--out DIR]
"""
import argparse
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from pixelkit import Canvas, diag, ramp, rgba, shade  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "resourcepack" / "assets" / "fortuneandfavors" / "textures" / "item"

# ------------------------------------------------------------------ palette
IRON_D, IRON_S, IRON, IRON_L = ramp("#8a919c")
EDGE = rgba("#eef3f8")
GOLD_D, GOLD_S, GOLD, GOLD_L = ramp("#e0ad3c")
RED_D, RED_S, RED, RED_L = ramp("#b3222f")
WOOD_D, WOOD_S, WOOD, WOOD_L = ramp("#7a4e2c")
BONE_D, BONE_S, BONE, BONE_L = ramp("#ddd3bb")
STEEL_D, STEEL_S, STEEL, STEEL_L = ramp("#5d6470")
EMERALD_D, EMERALD_S, EMERALD, EMERALD_L = ramp("#2ecf78")
ROBE_D, ROBE_S, ROBE, ROBE_L = ramp("#3a3d48")
BLUE_D, BLUE_S, BLUE, BLUE_L = ramp("#2f63b8")
VIOLET_D, VIOLET_S, VIOLET, VIOLET_L = ramp("#9a6cff")
SILVER_D, SILVER_S, SILVER, SILVER_L = ramp("#c4ccd8")
EMBER_D, EMBER_S, EMBER, EMBER_L = ramp("#ff8a2a")
FUR_D, FUR_S, FUR, FUR_L = ramp("#cfc4b0")
PAPER = rgba("#efe6cf")
PAPER_S = rgba("#c9bd9f")
INK = rgba("#1a1416")
GLOW = rgba("#fff2b0")


def save(c, name, out):
    out.mkdir(parents=True, exist_ok=True)
    c.image().save(out / f"{name}.png")
    print("wrote", name)


# ------------------------------------------------------------------ the war chest

def raid_loot_box(out):
    """An iron-banded war chest with the ominous banner nailed to its front."""
    c = Canvas()
    p = {
        "W": WOOD, "w": WOOD_S, "V": WOOD_L, "d": WOOD_D,
        "H": IRON_L, "I": IRON, "i": IRON_S,
        "G": GOLD, "g": GOLD_S, "Y": GOLD_L,
        "b": BONE_L, "s": BONE_S, "k": INK, "R": RED, "r": RED_S, "L": RED_L,
    }
    c.grid([
        "................",
        "................",
        "..VVVVVVVVVVVV..",
        "..VWWWWWWWWWWw..",
        "..VLRRRRRRRRrw..",
        "..WRbRRbbRRbrw..",
        "..dRRRRRRRRRrd..",
        "..dddddYYddddd..",
        "..VWWWWGYWWWWw..",
        "..VWWWWrRWWWWw..",
        "..VwwwwgGwwwwW..",
        "..VWWWWWWWWWWw..",
        "..VwwwwwwwwwwW..",
        "..WWWWWWWWWWWw..",
        "..dddddddddddd..",
        "................",
    ], p)
    # iron corner brackets, lit on the upper-left
    for (x, y), col in {
        (2, 2): IRON_L, (3, 2): IRON_L, (2, 3): IRON_L,
        (13, 2): IRON, (12, 2): IRON, (13, 3): IRON_S,
        (2, 14): IRON_S, (3, 14): IRON_S, (2, 13): IRON,
        (13, 14): IRON_D, (12, 14): IRON_S, (13, 13): IRON_S,
        (2, 8): IRON_L, (13, 8): IRON_S,
    }.items():
        c.set(x, y, col)
    # the cloth's fringe hanging over the seam
    for x in (4, 6, 9, 11):
        c.set(x, 7, RED_S)
    c.outline()
    save(c, "raid_loot_box", out)


# ------------------------------------------------------------------ warlord's axe

def warlord_axe(out):
    """A bearded war axe on a crimson-wrapped haft, held like vanilla's axes."""
    c = Canvas()

    def haft(x, y):
        s, t = diag(x, y)
        return s in (15, 16) and -11 <= t <= 8

    def haft_colour(x, y):
        s, t = diag(x, y)
        upper = s == 15
        if t <= -10:
            return GOLD_L if upper else GOLD_S
        if -8 <= t <= -1:
            # the grip: a spiral of crimson cord
            band = ((t + s) // 2) % 2 == 0
            return (RED_L if upper else RED) if band else (RED_S if upper else RED_D)
        if t in (2, 3):
            return GOLD_L if upper else GOLD
        if t >= 5:
            return IRON_L if upper else IRON_S
        return WOOD_L if upper else WOOD_S

    c.fill_where(haft, haft_colour)

    # the blade: a crescent on the upper-left of the haft, bearded towards the grip
    span = {14: (2, 8), 13: (1, 9), 12: (0, 10), 11: (-1, 9), 10: (-1, 8), 9: (0, 7), 8: (1, 6), 7: (2, 5)}

    def blade(x, y):
        s, t = diag(x, y)
        lo_hi = span.get(s)
        return lo_hi is not None and lo_hi[0] <= t <= lo_hi[1]

    def blade_colour(x, y):
        s, t = diag(x, y)
        if s <= 8 or (t == span[s][1] and s <= 10):
            return EDGE
        if s <= 10:
            return IRON_L
        if s == 12 and t in (3, 5, 7):
            return GOLD
        if s <= 12:
            return IRON
        return IRON_S

    c.fill_where(blade, blade_colour)
    # the back spike, which is what makes it a war axe and not a woodsman's
    for (x, y), col in {(12, 6): IRON, (13, 6): IRON_S, (13, 5): IRON_L, (14, 5): IRON_S}.items():
        c.set(x, y, col)
    c.outline()
    save(c, "warlord_axe", out)


# ------------------------------------------------------------------ captain's horn

def captain_horn(out):
    """A goat horn curling up from a gold mouthpiece to a gold-rimmed bell, cord-bound."""
    c = Canvas()
    c.grid([
        "................",
        "................",
        "...........gG...",
        ".........UBBBG..",
        ".......UUBBBDkG.",
        "......UBBBBSDkG.",
        ".....UBBBRSSDg..",
        "....UBBBRrSS....",
        "....UBBRrSS.....",
        "...UBBBSSS......",
        "...UBBSSS.......",
        "...YGGgS........",
        "...UBSS.........",
        "...UBS..........",
        "...YGg..........",
        "................",
    ], {"U": BONE_L, "B": BONE, "S": BONE_S, "D": BONE_D, "k": INK,
        "G": GOLD, "Y": GOLD_L, "g": GOLD_S, "R": RED, "r": RED_S})
    c.outline()
    save(c, "captain_horn", out)


# ------------------------------------------------------------------ warlord's cloak

def warlord_cloak(out):
    """A crimson war-cloak: gold pauldrons, a fur mantle, the banner sewn down its back."""
    c = Canvas()
    p = {
        "R": RED, "r": RED_S, "d": RED_D, "L": RED_L,
        "G": GOLD, "g": GOLD_S, "Y": GOLD_L,
        "F": FUR, "f": FUR_S, "U": FUR_L,
        "b": BONE_L, "k": INK,
    }
    c.grid([
        "................",
        ".....UUFFFf.....",
        "..YGgUFFFFfYGg..",
        ".YGGGgUFFfgGGGg.",
        ".gGgLLRRRRrrggd.",
        "...LLRRRRRRrr...",
        "...LRRYRRYRrr...",
        "...LRRRYYRRrr...",
        "..LLRRRYYRRrrr..",
        "..LRRRYRRYRrrr..",
        "..LRRRRRRRRrrr..",
        ".LLRRrRRRrRRrrd.",
        ".LRRRrRRRrRRrrd.",
        ".LRRdRRRRdRRrdd.",
        ".GYGgGYGGgGYGgg.",
        "................",
    ], p)
    c.outline()
    save(c, "warlord_cloak", out)


# ------------------------------------------------------------------ warlord's trophy

def warlord_trophy(out):
    """The Warlord's horned helm, set on a stand: the bragging-rights drop."""
    c = Canvas()
    p = {
        "G": GOLD, "g": GOLD_S, "Y": GOLD_L, "D": GOLD_D,
        "B": BONE, "b": BONE_S, "U": BONE_L,
        "R": RED, "r": RED_S, "L": RED_L,
        "k": INK, "W": WOOD, "w": WOOD_S, "V": WOOD_L,
    }
    c.grid([
        "................",
        ".......LR.......",
        ".U.....LR.....b.",
        ".UB...YGGg...Bb.",
        "..UB.YGGGGg.Bb..",
        "...BYYGGGGggb...",
        "....YGGGGGggg...",
        "....GgggggggD...",
        "....GkkGgkkgD...",
        "....GGgkkkgDD...",
        ".....GgggggD....",
        "......DggDD.....",
        ".......ww.......",
        ".....VWWWWw.....",
        "....VWWWWWWw....",
        "................",
    ], p)
    c.outline()
    save(c, "warlord_trophy", out)


# ------------------------------------------------------------------ raiders upgrader

def raiders_upgrader(out):
    """A forge tablet with the raid's mark burning in it, ready to be spent."""
    c = Canvas()
    p = {
        "S": STEEL, "s": STEEL_S, "L": STEEL_L, "d": STEEL_D,
        "G": GOLD, "g": GOLD_S, "Y": GOLD_L,
        "E": EMBER, "e": EMBER_S, "H": EMBER_L, "w": GLOW,
    }
    c.grid([
        "................",
        "..LLLLLLLLLLLs..",
        "..LYSSSSSSSYds..",
        "..LSSSSwSSSSds..",
        "..LSSSHwHSSSds..",
        "..LSSHEHEHSSds..",
        "..LSHEe.eEHSds..",
        "..LSEe...eESds..",
        "..LSSSSwSSSSds..",
        "..LSSSHEHSSSds..",
        "..LSSHe.eHSSds..",
        "..LSSe...eSSds..",
        "..LgSSSSSSSgds..",
        "..sddddddddddd..",
        "................",
        "................",
    ], p)
    # the hollows inside each chevron are part of the stone, not holes in it
    for y in range(1, 14):
        for x in range(2, 15):
            if not c.filled(x, y) and 3 <= x <= 12 and 2 <= y <= 12:
                c.set(x, y, STEEL_S)
    for x, y in ((1, 5), (14, 3), (14, 10), (1, 11)):
        c.set(x, y, EMBER_L)
    c.outline(skip=[EMBER_L])
    save(c, "raiders_upgrader", out)


# ------------------------------------------------------------------ the spellbooks

BOOK = [
    "................",
    "..sLLLLLLLLLLp..",
    "..SLCCCCCCCCcP..",
    "..SCCCCCCCCCcP..",
    "..SCCCCCCCCCcP..",
    "..SCCCCCCCCCcP..",
    "..SCCCCCCCCCcP..",
    "..SCCCCCCCCCcP..",
    "..SCCCCCCCCCcP..",
    "..SCCCCCCCCCcP..",
    "..SCCCCCCCCCcP..",
    "..SCCCCCCCCCcP..",
    "..ScccccccccdP..",
    "..sdddddddddPP..",
    "...pPPPPPPPPPp..",
    "................",
]


def spellbook(out, name, cover, corner, emblem, emblem_palette, glow=None):
    deep, shadow, base, light = cover
    c = Canvas()
    c.grid(BOOK, {
        "C": base, "c": shadow, "d": deep, "L": light,
        "S": shade(deep, 0.8), "s": deep,
        "P": PAPER, "p": PAPER_S,
    })
    # page edges, ruled
    for y in range(2, 14, 2):
        c.set(13, y, PAPER_S)
    # metal corner caps
    cd, cs, cb, cl = corner
    for x, y, col in ((3, 1, cl), (4, 1, cb), (3, 2, cb), (11, 1, cb), (12, 1, cs), (12, 2, cs),
                      (3, 12, cb), (3, 11, cs), (4, 12, cs), (12, 12, cd), (11, 12, cs), (12, 11, cs)):
        c.set(x, y, col)
    # the clasp across the fore-edge
    for x, y, col in ((12, 6, cl), (13, 6, cb), (12, 7, cb), (13, 7, cs)):
        c.set(x, y, col)
    c.grid(emblem, emblem_palette, ox=4, oy=3)
    if glow:
        for x, y in glow:
            c.set(x, y, GLOW)
    c.outline(skip=[GLOW])
    save(c, name, out)


def evoker_spellbook(out):
    """Charcoal and gold, with the fangs the book calls up set under an emerald."""
    spellbook(out, "evoker_spellbook", ramp("#3a3d48"), ramp("#e0ad3c"), [
        "..YGGg..",
        ".Y.EE.g.",
        ".G.Ee.g.",
        "..gGGD..",
        "........",
        "B.B..B.B",
        "BbBbbBbB",
        "b.b..b.b",
    ], {"G": GOLD, "g": GOLD_S, "Y": GOLD_L, "D": GOLD_D, "E": EMERALD_L, "e": EMERALD,
        "B": BONE_L, "b": BONE_S}, glow=[(1, 3), (14, 9)])


def illusioner_spellbook(out):
    """Royal blue and silver, an open eye on the cover that is not looking at you."""
    spellbook(out, "illusioner_spellbook", ramp("#2f5fb0"), ramp("#c9d2df"), [
        "...vv...",
        "..vVVv..",
        ".UUUUUU.",
        "UUbBBbUU",
        "UUbkkbUU",
        ".UUbbUU.",
        "..vVVv..",
        "...vv...",
    ], {"U": rgba("#f4f6ff"), "b": BLUE_L, "B": BLUE, "k": INK, "v": VIOLET_S, "V": VIOLET_L},
        glow=[(2, 1), (14, 4), (1, 13)])


# ------------------------------------------------------------------ the robes

def evoker_cloak(out):
    """The evoker's robe: charcoal, gold-trimmed, an emerald at the collar."""
    c = Canvas()
    p = {
        "R": ROBE, "r": ROBE_S, "d": ROBE_D, "L": ROBE_L,
        "G": GOLD, "g": GOLD_S, "Y": GOLD_L,
        "E": EMERALD_L, "e": EMERALD,
    }
    c.grid([
        "................",
        "....LLRRRRrr....",
        "..LLRRYEeGRrrr..",
        ".LLRRRRGgRRRrrd.",
        ".LRRRRRYgRRRRrd.",
        "..dLRRRGgRRRrd..",
        "...LRRRYgRRRr...",
        "...LRRRGgRRRr...",
        "...LRRRYgRRRr...",
        "...LRRRGgRRRr...",
        "..LLRRRYgRRRrr..",
        "..LRRRRGgRRRRr..",
        "..LRRRRYgRRRRr..",
        ".LRRRRRGgRRRRrr.",
        ".YGGGGGYgGGGGGg.",
        "................",
    ], p)
    c.outline()
    save(c, "evoker_cloak", out)


def illusioner_cloak(out):
    """A deep-blue hooded cloak; inside the hood, only the eyes."""
    c = Canvas()
    p = {
        "B": BLUE, "b": BLUE_S, "d": BLUE_D, "L": BLUE_L,
        "k": INK, "V": VIOLET_L, "v": VIOLET,
        "S": SILVER, "s": SILVER_S, "U": SILVER_L,
    }
    c.grid([
        "................",
        "......LBBb......",
        ".....LBBBBb.....",
        "....LBkkkkbd....",
        "....LBkVkVbd....",
        "....LBkkkkbd....",
        "...LLBBkkBbbd...",
        "..LLBBBUsBBbbd..",
        "..LBBBBBBBBBbd..",
        "..LBBBBbBBBBbd..",
        "..LBBBBbBBBbbd..",
        ".LLBBBBbBBBBbbd.",
        ".LBBBBBbBBBBbbd.",
        ".LBBBBBbBBBBbbd.",
        ".UsSsSsSsSsSsSs.",
        "................",
    ], p)
    c.outline(skip=[VIOLET_L])
    save(c, "illusioner_cloak", out)


ALL = [raid_loot_box, warlord_axe, captain_horn, warlord_cloak, warlord_trophy, raiders_upgrader,
       evoker_spellbook, illusioner_spellbook, evoker_cloak, illusioner_cloak]

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", type=pathlib.Path, default=OUT)
    args = ap.parse_args()
    for fn in ALL:
        fn(args.out)
