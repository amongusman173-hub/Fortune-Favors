#!/usr/bin/env python3
"""Redraws the boss-gear icons that did not read at hotbar size.

Eight textures across three loot pools, placed pixel by pixel through tools/pixelkit.py:

    Clockwork King     clockwork_gauntlet, mechanical_heart, automaton_armor
    Starbound Magister starpiercer, astral_mantle
    Voidshaper         void_reaver, colossus_plate, shaping_sigil

The three weapons are held items, so they keep vanilla's diagonal (grip bottom-left, business end
top-right). These replace the versions drawn by tools/make_new_boss_textures.py, which no longer
writes them.

The Clockwork King and Starbound Magister pieces have since been redrawn again by
tools/make_reworked_textures_a.py, and the Voidshaper three by tools/make_reworked_textures_b.py
(which is where their functions now live); this script no longer writes anything.

Run:  python3 tools/make_boss_gear_textures.py [--out DIR]
"""
import argparse
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from pixelkit import Canvas, diag, ramp, rgba  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "resourcepack" / "assets" / "fortuneandfavors" / "textures" / "item"

BRASS_D, BRASS_S, BRASS, BRASS_L = ramp("#d9a441")
STEEL_D, STEEL_S, STEEL, STEEL_L = ramp("#8d96a4")
SOUL_D, SOUL_S, SOUL, SOUL_L = ramp("#3fd8ff")
NAVY_D, NAVY_S, NAVY, NAVY_L = ramp("#2d3f8f")
STAR_D, STAR_S, STAR, STAR_L = ramp("#b8e8ff")
GOLD_D, GOLD_S, GOLD, GOLD_L = ramp("#f0c24a")
VOID_D, VOID_S, VOID, VOID_L = ramp("#6a2bc8")
OBSID_D, OBSID_S, OBSID, OBSID_L = ramp("#3b2f52")
GLOW = rgba("#ffffff")
VGLOW = rgba("#e2c8ff")


def save(c, name, out):
    out.mkdir(parents=True, exist_ok=True)
    c.image().save(out / f"{name}.png")
    print("wrote", name)


# ------------------------------------------------------------------ Clockwork King

def clockwork_gauntlet(out):
    """A brass fist, knuckles up, a piston bar across them and a soul-fire core in the palm."""
    c = Canvas()
    c.grid([
        "................",
        "...LBdLBdLBdLb..",
        "..LLBdLBdLBdLBb.",
        "..LBBdBBdBBdBBb.",
        "..BiIIIIIIIIIIi.",
        ".LBBBBBBBBBBBb..",
        ".BBBBsSSsBBBBb..",
        ".BBBBSwwSBBBb...",
        ".LLLBsSSsBBBb...",
        "..LLLLBBBBBb....",
        "...dBBBBBBd.....",
        "...dTTTTTTd.....",
        "...dTtTtTtd.....",
        "...dddddddd.....",
        "................",
        "................",
    ], {"L": BRASS_L, "B": BRASS, "b": BRASS_S, "d": BRASS_D,
        "I": STEEL_L, "i": STEEL,
        "S": SOUL, "s": SOUL_S, "w": SOUL_L,
        "T": STEEL, "t": STEEL_S})
    c.outline(skip=[SOUL_L])
    save(c, "clockwork_gauntlet", out)


def mechanical_heart(out):
    """A brass heart with a soul-fire core, two pipes out of the top and a gear on its flank."""
    c = Canvas()
    c.grid([
        "....ii..ii......",
        "....Ii..Ii......",
        "..LLBBL.LBBb....",
        ".LBBBBBBBBBBb...",
        ".LBBBsSSsBBBbg..",
        ".BBBsSwwSsBBbgg.",
        ".BBBsSwwSsBBbg..",
        ".BBBBsSSsBBBb...",
        "..BBBBBBBBBb....",
        "...BBBBBBBb.....",
        "....BBBBBb......",
        ".....BBBb.......",
        "......Bb........",
        "................",
        "................",
        "................",
    ], {"L": BRASS_L, "B": BRASS, "b": BRASS_S,
        "S": SOUL, "s": SOUL_S, "w": SOUL_L,
        "I": STEEL_L, "i": STEEL, "g": STEEL_S})
    # rivets
    for x, y in ((3, 3), (9, 3), (3, 8), (8, 9)):
        c.set(x, y, BRASS_L)
    c.outline(skip=[SOUL_L])
    save(c, "mechanical_heart", out)


def automaton_armor(out):
    """A riveted brass breastplate with the soul-fire core set where a heart would be."""
    c = Canvas()
    c.grid([
        "................",
        "..LLLb....LLLb..",
        ".LBBBBb..LBBBBd.",
        ".LBBBBBLLBBBBBd.",
        ".LBBBBBBBBBBBBd.",
        "..dBBBsSSsBBBd..",
        "...BBBSwwSBBd...",
        "...BBBsSSsBBd...",
        "...BBBBBBBBBd...",
        "...LTTTTTTTTd...",
        "...BBBBBBBBBd...",
        "...BBBBBBBBBd...",
        "...BBBBBBBBBd...",
        "...dddddddddd...",
        "................",
        "................",
    ], {"L": BRASS_L, "B": BRASS, "d": BRASS_S, "b": BRASS,
        "S": SOUL, "s": SOUL_S, "w": SOUL_L, "T": STEEL})
    for x, y in ((4, 3), (11, 3), (4, 11), (11, 11)):
        c.set(x, y, BRASS_L)
    c.outline(skip=[SOUL_L])
    save(c, "automaton_armor", out)


# ------------------------------------------------------------------ Starbound Magister

def starpiercer(out):
    """A needle of starlight on a gold guard, a star caught on its point."""
    c = Canvas()

    def piece(x, y):
        s, t = diag(x, y)
        if s in (15, 16) and -10 <= t <= 9:
            return True
        if t in (-4, -3) and 12 <= s <= 19:
            return True
        return False

    def colour(x, y):
        s, t = diag(x, y)
        upper = s <= 15
        if t in (-4, -3) and (s < 15 or s > 16 or True):
            if 12 <= s <= 19 and t in (-4, -3):
                return GOLD_L if s <= 14 else (GOLD if s <= 17 else GOLD_S)
        if t <= -10:
            return GOLD_L if upper else GOLD
        if t <= -5:
            return NAVY_L if (t % 2 == 0) == upper else NAVY_S
        if t >= 8:
            return GLOW if upper else STAR_L
        return STAR_L if upper else STAR

    c.fill_where(piece, colour)
    c.outline(skip=[GLOW])
    # the star on the point, after the outline so it glows rather than sits in a frame
    for x, y in ((13, 0), (12, 1), (13, 1), (14, 1), (13, 2)):
        c.set(x, y, GLOW)
    c.set(15, 1, STAR_L)
    c.set(11, 1, STAR_L)
    save(c, "starpiercer", out)


def astral_mantle(out):
    """A night-blue mantle sewn with stars, clasped in gold at the throat."""
    c = Canvas()
    c.grid([
        "................",
        "....LLNNNNnn....",
        "..LLNNYGgNNnnn..",
        ".LLNNNNNNNNNnnd.",
        ".LNNNNNNNNNNNnd.",
        "..dLNNNNNNNNnd..",
        "...LNNNNNNNNn...",
        "...LNNNNNNNNn...",
        "...LNNNNNNNNn...",
        "...LNNNNNNNNn...",
        "..LLNNNNNNNNnn..",
        "..LNNNNNNNNNNn..",
        "..LNNNNNNNNNNn..",
        ".LNNNNNNNNNNNnn.",
        ".YGgYGgYGgYGgGg.",
        "................",
    ], {"L": NAVY_L, "N": NAVY, "n": NAVY_S, "d": NAVY_D,
        "G": GOLD, "g": GOLD_S, "Y": GOLD_L})
    for x, y in ((5, 5), (9, 4), (7, 8), (10, 9), (4, 11), (8, 12), (11, 12), (6, 10)):
        c.set(x, y, STAR_L if (x + y) % 2 else GLOW)
    c.outline(skip=[GLOW])
    save(c, "astral_mantle", out)


# The Clockwork King and Starbound Magister pieces above are drawn by
# tools/make_reworked_textures_a.py now; they stay here for reference but no longer run.
# The Voidshaper three (void_reaver, colossus_plate, shaping_sigil) moved to
# tools/make_reworked_textures_b.py, so nothing here runs any more.
ALL = []

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", type=pathlib.Path, default=OUT)
    args = ap.parse_args()
    for fn in ALL:
        fn(args.out)
