#!/usr/bin/env python3
"""Reworked icons, batch A: the Puppeteer's marionette, the sculk mage staff, the Raid Banner,
and every Clockwork King and Starbound Magister item.

All of it is placed pixel by pixel through tools/pixelkit.py, lit from the upper left like vanilla,
and finished with pixelkit's selective outline. Each boss set shares a palette so the pieces read as
one family, but every icon has its own silhouette so no two are confused in a hotbar:

    Puppeteer          wooden_marionette          a jointed doll hanging from its cross-bar
    Warden             sculk_mage_staff           a sculk-wood staff with a shrieker head
    Player raids       raid_banner                a crimson ominous banner on a pike
    Clockwork King     clockwork_core             gear + winding key (the summon)
                       clockwork_gauntlet         brass fist, soul-fire gauge and cog on the cuff
                       mechanical_heart           brass heart, exposed gear, core in the chamber
                       automaton_armor            brass greaves with cog knee-caps (it is leggings)
                       clockwork_trophy           brass cup with a cog emblem, soul-fire inside
                       clockwork_loot_box         brass chest with a cog lock
                       mech_scrap                 a loose heap of plate, cog and spring
    Starbound Magister astral_compass             gold compass, star needle (the summon)
                       starpiercer                star-tipped rapier
                       astral_mantle              hooded night-blue mantle sewn with stars
                       magisters_codex            night-leather book, gold corners, star sigil
                       starbound_trophy           a star under a glass bell-jar
                       starbound_loot_box         night-blue chest with a star latch
                       magical_essence            violet crystal cluster

The Clockwork King's soul-fire core is the same cyan in every one of his pieces, which ties his set
together the way his boss bar and VFX do.

These replace the versions drawn by make_new_boss_textures.py, make_boss_gear_textures.py,
make_fix_textures.py, make_puppeteer_textures.py, make_warden_textures.py and
make_missing_item_textures.py, which no longer write these names. Item models are unchanged
(they already point at these texture names), and the Bedrock half of the pack is built from the
same textures folder by tools/build_resourcepack.py, so there is no second copy to update.

Run:  python3 tools/make_reworked_textures_a.py [--out DIR] [--preview PNG]
"""
import argparse
import math
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from pixelkit import Canvas, diag, ramp, rgba, shade  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "resourcepack" / "assets" / "fortuneandfavors" / "textures" / "item"

# ---------------------------------------------------------------- shared palettes
# Brass gets five tones, not pixelkit's four: the extra hot highlight is what makes it read as
# polished metal rather than yellow paint.
BR_D = rgba("#4a2d0c")
BR_S = rgba("#8a5a1c")
BR = rgba("#c88e2e")
BR_L = rgba("#eec05a")
BR_H = rgba("#fff1b0")
COP = rgba("#b4622e")
COP_S = rgba("#7a3c1a")
ST_D, ST_S, ST, ST_L = ramp("#8f98a6")
SOUL_D = rgba("#0d4a63")
SOUL_S = rgba("#1a9cc8")
SOUL = rgba("#4fdcff")
SOUL_L = rgba("#d4fbff")

NAVY_D, NAVY_S, NAVY, NAVY_L = ramp("#2c3a8c")
VIO_D, VIO_S, VIO, VIO_L = ramp("#8a5ad8")
STAR = rgba("#a9dcff")
STAR_L = rgba("#e8f7ff")
WHITE = rgba("#ffffff")
GOLD_D = rgba("#5e3e08")
GOLD_S = rgba("#a8761a")
GOLD = rgba("#e8b838")
GOLD_L = rgba("#ffe58a")


def save(c, name, out):
    out.mkdir(parents=True, exist_ok=True)
    c.image().save(out / f"{name}.png")
    print("wrote", name)


def gear(c, cx, cy, rb, rt, teeth, body, light, dark, tooth=None, hole=None, phase=0.0):
    """A cog centred on (cx, cy): a disc of radius rb with `teeth` square teeth out to rt.

    Shading follows the light: the upper-left half of the disc takes `light`, the lower-right
    `dark`, a band in between `body`. `hole` fills the hub (None leaves it see-through).
    """
    tooth = tooth or body
    for y in range(c.size):
        for x in range(c.size):
            dx, dy = x - cx, y - cy
            d = math.hypot(dx, dy)
            if d > rt + 0.01:
                continue
            a = (math.atan2(dy, dx) / (2 * math.pi) * teeth + phase) % 1.0
            if d > rb + 0.01:
                if 0.25 <= a < 0.75:
                    c.set(x, y, tooth)
                continue
            if hole is not None and d < 1.0:
                c.set(x, y, hole)
                continue
            lit = -dx - dy
            c.set(x, y, light if lit > 1.2 else (dark if lit < -1.2 else body))


# ================================================================== Puppeteer

def wooden_marionette(out):
    """A jointed wooden doll hanging from its cross-bar control by three strings."""
    c = Canvas()
    c.grid([
        "......kWWk......",
        ".kWWWWWWWWWWWWk.",
        "..s...kWWk...s..",
        "..s...LHHh...s..",
        "..s...eHHe...s..",
        "..s...rHHr...s..",
        "..s....oo....s..",
        "..HhohLHRhohH...",
        "......LHRh......",
        "......HHRh......",
        "......o..o......",
        "......H..h......",
        "......o..o......",
        "......H..h......",
        ".....Hh..Hh.....",
        "................",
    ], {"k": rgba("#3e2414"), "W": rgba("#7a4a28"),
        "s": rgba("#e6ddc8"),
        "L": rgba("#f2c88c"), "H": rgba("#d49a5a"), "h": rgba("#9c6634"),
        "o": rgba("#4e2c16"), "e": rgba("#2a160c"), "r": rgba("#c0483a"),
        "R": rgba("#b03030")})
    # the string from the cross to the crown of the head
    c.set(7, 2, rgba("#e6ddc8"))
    c.set(8, 2, rgba("#e6ddc8"))
    c.outline(skip=[rgba("#e6ddc8")])
    save(c, "wooden_marionette", out)


# ================================================================== Warden

def sculk_mage_staff(out):
    """A sculk-wood staff veined with soul-light, a shrieker's bone jaws round a glowing core."""
    c = Canvas()
    WOOD_D = rgba("#0b1a20")
    WOOD = rgba("#173440")
    WOOD_L = rgba("#24505e")
    VEIN = rgba("#2fd6d0")
    WRAP = rgba("#3b2a4a")
    WRAP_L = rgba("#5a4470")

    def shaft(x, y):
        s, t = diag(x, y)
        return s in (15, 16) and -12 <= t <= 1

    def col(x, y):
        s, t = diag(x, y)
        upper = s == 15
        if t <= -11:
            return VEIN if upper else WOOD_L
        if -10 <= t <= -6:
            return (WRAP_L if upper else WRAP) if t % 2 == 0 else (WRAP if upper else WOOD_D)
        if t in (-3, 0) and upper:
            return VEIN
        return WOOD_L if upper else WOOD

    c.fill_where(shaft, col)
    BONE = rgba("#e4e0c8")
    BONE_S = rgba("#a8a488")
    SC = rgba("#0f3a44")
    c.grid([
        ".B...B",
        "BbC.Bb",
        "BCWCcB",
        "bcCCcb",
        "Ssvsss",
        ".sSvS.",
    ], {"B": BONE, "b": BONE_S, "C": SOUL, "c": SOUL_S, "W": SOUL_L,
        "S": SC, "s": rgba("#082830"), "v": VEIN}, ox=9, oy=0)
    c.outline(skip=[SOUL_L, SOUL])
    save(c, "sculk_mage_staff", out)


# ================================================================== Raid

def raid_banner(out):
    """An ominous banner dyed blood-red, the illager face stamped in bone, on a gold-capped pike."""
    c = Canvas()
    c.grid([
        "..Y.............",
        ".gPPPPPPPPPPPPd.",
        "..PRRRRRRRRRRRd.",
        "..PRrrrrrrrrrRd.",
        "..PRrKKKKKKKrRd.",
        "..PRrFFFFFFFrRd.",
        "..PRrKKKKKKKrRd.",
        "..PRrFeFNFeFrRd.",
        "..PRrFFNNNFFrRd.",
        "..PRrrFFNFFrrRd.",
        "..PRRrFKKKFrRRd.",
        "..PRYRrFFFrRYRd.",
        "..PRRRRrrRRRRRd.",
        "..PRR.RRR.RRR.d.",
        "..PR...R...R....",
        "..P.............",
    ], {"Y": GOLD_L, "g": GOLD, "P": rgba("#6b4a2e"),
        "R": rgba("#a8182a"), "r": rgba("#741020"), "d": rgba("#520a14"),
        "K": rgba("#202020"), "F": rgba("#d8d2c0"), "e": rgba("#202020"),
        "N": rgba("#8a8478")})
    # pole lit on its left edge
    for y in range(2, 16):
        c.set(2, y, rgba("#8c6440") if y % 3 else rgba("#6b4a2e"))
    c.outline()
    save(c, "raid_banner", out)


# ================================================================== Clockwork King

def clockwork_core(out):
    """The King's mainspring: a big brass cog, soul-fire in the hub, a steel winding key in it."""
    c = Canvas()
    gear(c, 6.5, 9, 4.4, 6.2, 8, BR, BR_L, BR_S, tooth=BR_S, phase=0.5)
    c.grid([
        ".dddd.",
        "dcSSsd",
        "dSWSsd",
        "dSSssd",
        ".dddd.",
    ], {"d": BR_D, "S": SOUL, "s": SOUL_S, "W": SOUL_L, "c": SOUL_L}, ox=4, oy=7)
    # highlight glints on the lit rim
    c.set(4, 5, BR_H)
    c.set(2, 7, BR_H)
    # the steel winding key, its bow up in the corner and its shank driven into the hub
    c.grid([
        ".KKk",
        "KK.k",
        "K..k",
        "kkks",
        "s...",
    ], {"K": ST_L, "k": ST, "s": ST_S}, ox=11, oy=0)
    c.set(10, 5, ST)
    c.set(9, 6, ST_S)
    c.outline(skip=[SOUL_L])
    save(c, "clockwork_core", out)


def clockwork_gauntlet(out):
    """A clenched brass fist; soul-fire burns in a gauge on the steel cuff and a cog turns beside it."""
    c = Canvas()
    c.grid([
        "................",
        "..HLL.HLL.HLL...",
        "..LBBdLBBdLBBS..",
        "..LBBdLBBdLBBS..",
        "..LBBdLBBdLBBS..",
        "..SBSdSBSdSBSS..",
        ".HLLLLLLBBBBBS..",
        ".LBBBBBBSBBBBS..",
        "..SSSSSSSBBBS...",
        "...IIIIIIIIi....",
        "...TcWOOcTTt....",
        "...TcOOscTTt....",
        "...tTTTTTTTt....",
        "....ttttttt.....",
        "................",
    ], {"H": BR_H, "L": BR_L, "B": BR, "S": BR_S, "d": BR_D, "I": ST_L, "i": ST,
        "c": SOUL_D, "W": SOUL_L, "O": SOUL, "s": SOUL_S, "T": ST, "t": ST_S})
    c.outline(skip=[SOUL_L])
    # a small cog on the cuff, riding the lower-right corner
    gear(c, 12.5, 11.5, 1.6, 2.6, 6, COP, rgba("#de8a4a"), COP_S, tooth=COP_S, hole=BR_D)
    save(c, "clockwork_gauntlet", out)


def mechanical_heart(out):
    """A brass heart cut open on a turning cog, soul-fire in the chamber, steel pipes on top."""
    c = Canvas()
    c.grid([
        "...ii....ii.....",
        "...Ii....Ii.....",
        "..LIHL..LIiS....",
        ".LHBBBLLBBBBS...",
        ".LBBBBBBBBBBBS..",
        ".BBB.......BBS..",
        ".BBB.......BBS..",
        ".SBB.......BSS..",
        "..SB.......SS...",
        "...SB.....SS....",
        "....SBBBBSS.....",
        ".....SBBSS......",
        "......SS........",
        "................",
        "................",
        "................",
    ], {"L": BR_L, "H": BR_H, "B": BR, "S": BR_S, "I": ST_L, "i": ST})
    # the exposed chamber: a copper cog with a soul-fire hub
    gear(c, 7, 7.5, 2.7, 3.6, 8, COP, rgba("#de8a4a"), COP_S, tooth=COP_S, phase=0.5)
    for x, y in ((6, 7), (7, 7), (8, 7), (6, 8), (7, 8), (8, 8)):
        c.set(x, y, SOUL)
    c.set(6, 7, SOUL_L)
    c.set(8, 8, SOUL_S)
    c.set(7, 6, SOUL_S)
    c.set(7, 9, SOUL_S)
    # rivets
    for x, y in ((2, 4), (11, 4), (4, 10)):
        c.set(x, y, BR_H)
    c.outline(skip=[SOUL_L])
    save(c, "mechanical_heart", out)


def automaton_armor(out):
    """Brass greaves - it is a pair of leggings - with cog knee-caps and a soul-fire buckle."""
    c = Canvas()
    c.grid([
        "................",
        "..HLLLLccLLLLB..",
        "..LBBBBcWBBBBS..",
        "..IIIIIssIIIIi..",
        "..LBBBBSLBBBBS..",
        "..LBBBS..LBBBS..",
        "..LBBBS..LBBBS..",
        "..LBBBS..LBBBS..",
        "..LBBBS..LBBBS..",
        "..LBBBS..LBBBS..",
        "..LBBBS..LBBBS..",
        "..LBBBS..LBBBS..",
        "..LBBBS..LBBBS..",
        "..ISSSi..ISSSi..",
        "................",
        "................",
    ], {"H": BR_H, "L": BR_L, "B": BR, "S": BR_S, "I": ST_L, "i": ST,
        "c": SOUL, "W": SOUL_L, "s": SOUL_S})
    c.outline(skip=[SOUL_L])
    gear(c, 4.5, 8.5, 1.5, 2.5, 6, COP, rgba("#de8a4a"), COP_S, tooth=COP_S, hole=SOUL)
    gear(c, 10.5, 8.5, 1.5, 2.5, 6, COP, rgba("#de8a4a"), COP_S, tooth=COP_S, hole=SOUL)
    save(c, "automaton_armor", out)


def clockwork_trophy(out):
    """A brass cup on a steel plinth, a cog on its belly and soul-fire burning in the bowl."""
    c = Canvas()
    c.grid([
        ".....W.c........",
        "....cSWSc.......",
        "...cSSSSSc......",
        ".LLHLLLLLLLBS...",
        "L.LBBBBBBBBBS.S.",
        "L.LBBBBBBBBBS.S.",
        ".LLBBBBBBBBBSS..",
        "...LBBBBBBBS....",
        "....SBBBBBS.....",
        "......LBS.......",
        "......LBS.......",
        ".....LBBBS......",
        "....IIIIIIi.....",
        "...ItttttttT....",
        "...TTTTTTTTT....",
        "................",
    ], {"H": BR_H, "L": BR_L, "B": BR, "S": BR_S,
        "W": SOUL_L, "c": SOUL_S, "I": ST_L, "i": ST, "t": ST_S, "T": ST_D})
    for x, y in ((5, 1), (7, 1), (4, 2), (5, 2), (6, 2), (7, 2), (8, 2)):
        if (x, y) in ((6, 2),):
            c.set(x, y, SOUL_L)
        else:
            c.set(x, y, SOUL)
    gear(c, 7, 5.5, 1.6, 2.6, 6, ST, ST_L, ST_S, tooth=ST_S, hole=SOUL)
    c.outline(skip=[SOUL_L, SOUL, SOUL_S])
    save(c, "clockwork_trophy", out)


def clockwork_loot_box(out):
    """A brass-plated chest, steel-banded, locked with a cog that shows a soul-fire keyhole."""
    c = Canvas()
    c.grid([
        "................",
        "................",
        ".HLLLLLLLLLLLLB.",
        ".LBBIBBBBBBIBBS.",
        ".LBBIBBBBBBIBBS.",
        ".SSSiSSSSSSiSSS.",
        ".DDDDDDDDDDDDDD.",
        ".LBBIBBBBBBIBBS.",
        ".LBBIBBBBBBIBBS.",
        ".LBBIBBBBBBIBBS.",
        ".LBBIBBBBBBIBBS.",
        ".LBBIBBBBBBIBBS.",
        ".SSSiSSSSSSiSSS.",
        "................",
        "................",
        "................",
    ], {"H": BR_H, "L": BR_L, "B": BR, "S": BR_S, "D": BR_D, "I": ST_L, "i": ST})
    c.outline()
    gear(c, 7.5, 6.5, 2.0, 3.0, 8, ST, ST_L, ST_S, tooth=ST_S, phase=0.5)
    c.set(7, 6, SOUL_L)
    c.set(8, 6, SOUL)
    c.set(7, 7, SOUL)
    c.set(8, 7, SOUL_S)
    save(c, "clockwork_loot_box", out)


def mech_scrap(out):
    """Salvage: a bent steel plate, a loose brass cog and a sprung coil, one soul spark left in it."""
    c = Canvas()
    c.grid([
        "................",
        "................",
        "................",
        "................",
        "................",
        "................",
        "................",
        "........IIi.....",
        "......IIttti....",
        "....IIttottiT...",
        "..IItttttttTT...",
        "..ItttottttT....",
        "...TttttttT.....",
        "....TTTTTT......",
        "................",
        "................",
    ], {"I": ST_L, "i": ST, "t": ST, "T": ST_S, "o": ST_D})
    gear(c, 5.5, 5.5, 2.2, 3.4, 8, BR, BR_L, BR_S, tooth=BR_S, hole=BR_D, phase=0.5)
    # spring coil, upper right
    c.grid([
        "LS.",
        ".LS",
        "LS.",
        ".LS",
    ], {"L": ST_L, "S": ST_S}, ox=11, oy=2)
    c.outline()
    c.set(13, 7, SOUL)
    c.set(14, 6, SOUL_L)
    save(c, "mech_scrap", out)


# ================================================================== Starbound Magister

def astral_compass(out):
    """A gold compass with the night sky for a face and a star for its needle."""
    c = Canvas()

    def disc(x, y):
        return math.hypot(x - 7.5, y - 7.5) <= 6.9

    def col(x, y):
        d = math.hypot(x - 7.5, y - 7.5)
        if d > 5.6:
            return GOLD_L if (x + y) < 13 else (GOLD if (x + y) < 18 else GOLD_S)
        if d > 4.8:
            return GOLD_D
        return NAVY_S if (x + y) > 15 else NAVY

    c.fill_where(disc, col)
    # specks of sky
    for x, y in ((5, 4), (10, 5), (4, 10), (10, 11)):
        c.set(x, y, STAR)
    # the needle: north half star-white, south half violet, a bright pivot
    c.grid([
        "...W..",
        "..WW..",
        ".SWWS.",
        ".VWV..",
        "..VV..",
        "..V...",
    ], {"W": STAR_L, "S": STAR, "V": VIO}, ox=5, oy=3)
    c.grid([
        ".S",
        "WS",
    ], {"W": STAR_L, "S": STAR}, ox=8, oy=3)
    c.set(8, 2, WHITE)
    c.set(7, 7, WHITE)
    c.set(5, 11, VIO_S)
    c.set(6, 10, VIO)
    c.outline()
    save(c, "astral_compass", out)


def starpiercer(out):
    """A thin star-steel rapier on a gold swept hilt, a star caught on its point."""
    c = Canvas()

    def piece(x, y):
        s, t = diag(x, y)
        if s in (15, 16) and -11 <= t <= 8:
            return True
        if t in (-6, -5) and 11 <= s <= 20:
            return True
        return False

    def colour(x, y):
        s, t = diag(x, y)
        upper = s <= 15
        if t in (-6, -5) and 11 <= s <= 20:
            if s in (15, 16):
                return VIO_L if upper else VIO
            return GOLD_L if s <= 13 else (GOLD if s <= 18 else GOLD_S)
        if t <= -10:
            return GOLD_L if upper else GOLD
        if t <= -7:
            return NAVY_L if (t % 2 == 0) == upper else NAVY_D
        if t >= 6:
            return WHITE if upper else STAR_L
        return STAR_L if upper else STAR

    c.fill_where(piece, colour)
    c.outline(skip=[WHITE])
    for x, y in ((13, 0), (12, 1), (13, 1), (14, 1), (13, 2)):
        c.set(x, y, WHITE)
    c.set(15, 1, STAR)
    c.set(11, 1, STAR)
    c.set(13, 3, STAR) if not c.filled(13, 3) else None
    save(c, "starpiercer", out)


def astral_mantle(out):
    """A hooded night-blue mantle, violet-lined, sewn with a constellation, clasped in gold."""
    c = Canvas()
    c.grid([
        ".....LNNNn......",
        "....LNnddNn.....",
        "...LNnd..dNn....",
        "..LNNvd..dvNn...",
        ".LNNNNvYGvNNNn..",
        ".LNNNNNvgNNNNn..",
        "..LNNNNNNNNNn...",
        "..LNNNNNNNNNn...",
        "..LNNNNNNNNNn...",
        "..LNNNNNNNNNNn..",
        ".LNNNNNNNNNNNn..",
        ".LNNNNNNNNNNNNn.",
        ".LNNNNNNNNNNNNn.",
        "LvvvvvvvvvvvvvvV",
        "................",
        "................",
    ], {"L": NAVY_L, "N": NAVY, "n": NAVY_S, "d": NAVY_D,
        "v": VIO, "V": VIO_S, "Y": GOLD_L, "G": GOLD, "g": GOLD_S})
    # a constellation stitched in: stars joined by faint thread
    thread = rgba("#5a6ed0")
    for x, y in ((5, 8), (6, 9), (8, 10), (9, 10)):
        c.set(x, y, thread)
    for x, y, col in ((4, 7, WHITE), (7, 9, STAR_L), (10, 11, WHITE), (11, 7, STAR), (4, 11, STAR)):
        c.set(x, y, col)
    c.outline(skip=[WHITE])
    save(c, "astral_mantle", out)


def magisters_codex(out):
    """Her spellbook: night leather, gold corners, a star sigil, a violet ribbon out the bottom."""
    c = Canvas()
    c.grid([
        "..YGNNNNNNNNYGp.",
        "..GNNNNNNNNNNGp.",
        "..dNNNNNNNNNNnp.",
        "..dNNNNNNNNNNnp.",
        "..dNNNNNNNNNNnp.",
        "..dNNNNNNNNNNnp.",
        "..dNNNNNNNNNNnp.",
        "..dNNNNNNNNNNnp.",
        "..dNNNNNNNNNNnp.",
        "..dNNNNNNNNNNnp.",
        "..dNNNNNNNNNNnp.",
        "..dNNNNNNNNNNnp.",
        "..GNNNNNNNNNNGp.",
        "..YGnnnnnnnnGgP.",
        ".......vV.......",
        ".......V........",
    ], {"N": NAVY, "n": NAVY_S, "d": NAVY_D, "Y": GOLD_L, "G": GOLD, "g": GOLD_S,
        "p": rgba("#ece2c4"), "P": rgba("#bfae86"), "v": VIO_L, "V": VIO})
    # lit leather on the upper left
    for y in range(1, 12):
        c.set(3, y, NAVY_L if y > 1 else NAVY)
    # the star sigil, inside a thin gold ring
    c.grid([
        "..GGG..",
        ".G.W.G.",
        "G.SWS.G",
        "GWWWWWG",
        "G.SWS.G",
        ".G.W.G.",
        "..GGG..",
    ], {"G": GOLD_S, "W": STAR_L, "S": STAR}, ox=5, oy=3)
    c.set(8, 6, WHITE)
    c.outline(skip=[WHITE])
    save(c, "magisters_codex", out)


def starbound_trophy(out):
    """A star, still warm, under a glass bell-jar on a gold-trimmed night-blue plinth."""
    c = Canvas()
    GL = rgba("#bfe8ff")
    GLS = rgba("#7ab0d8")
    c.grid([
        ".....GGGGG......",
        "....G.....g.....",
        "...G.......g....",
        "...G.......g....",
        "..G.........g...",
        "..G.........g...",
        "..G.........g...",
        "..G.........g...",
        "..G.........g...",
        "..G.........g...",
        ".YYYYYYYYYYYYy..",
        ".LNNNNNNNNNNNn..",
        ".LNNNNNNNNNNNn..",
        ".ggggggggggggd..",
        "................",
        "................",
    ], {"G": GL, "g": GLS, "Y": GOLD_L, "y": GOLD_S, "L": NAVY_L, "N": NAVY,
        "n": NAVY_S, "d": GOLD_D})
    # the star itself
    c.grid([
        "....S....",
        "....W....",
        "...SWS...",
        "SWWWOWWWS",
        "...SWS...",
        "....W....",
        "....S....",
    ], {"S": STAR, "W": STAR_L, "O": WHITE}, ox=3, oy=2)
    c.set(4, 3, WHITE)  # glint on the glass
    c.set(4, 4, GL)
    c.outline(skip=[GL, GLS, WHITE, STAR, STAR_L])
    save(c, "starbound_trophy", out)


def starbound_loot_box(out):
    """A night-blue chest, gold-banded and star-flecked, with a four-point star for its latch."""
    c = Canvas()
    c.grid([
        "................",
        "................",
        ".LLLLLLLLLLLLLN.",
        ".LNNYNNNNNNYNNn.",
        ".LNNYNNNNNNYNNn.",
        ".nnnGnnnnnnGnnn.",
        ".dddddddddddddd.",
        ".LNNYNNNNNNYNNn.",
        ".LNNYNNNNNNYNNn.",
        ".LNNYNNNNNNYNNn.",
        ".LNNYNNNNNNYNNn.",
        ".LNNYNNNNNNYNNn.",
        ".nnnGnnnnnnGnnn.",
        "................",
        "................",
        "................",
    ], {"L": NAVY_L, "N": NAVY, "n": NAVY_S, "d": NAVY_D, "Y": GOLD_L, "G": GOLD})
    for x, y in ((2, 9), (13, 3), (9, 11), (6, 4)):
        c.set(x, y, STAR)
    c.outline()
    c.grid([
        "..W..",
        ".SWS.",
        "WWOWW",
        ".SWS.",
        "..W..",
    ], {"W": STAR_L, "S": STAR, "O": WHITE}, ox=5, oy=4)
    save(c, "starbound_loot_box", out)


def magical_essence(out):
    """A cluster of the Magister's ceiling crystal: violet shards with starlight in their hearts."""
    c = Canvas()
    c.grid([
        "........W.......",
        ".......LV.......",
        "......LVVs......",
        "..L...LVSs......",
        "..LV..LSSs.Lv...",
        "..LVs.LSSs.LVs..",
        "..LVs.LVSsLVVs..",
        "..LSs.LVSsLVSs..",
        "...Ss.LVSsLSs...",
        "...SsLVVSsSs....",
        "....sLVSSss.....",
        ".....sdddd......",
        "................",
        "................",
        "................",
        "................",
    ], {"W": WHITE, "L": VIO_L, "V": VIO, "v": VIO, "S": STAR, "s": VIO_S, "d": VIO_D})
    c.set(8, 5, STAR_L)
    c.set(8, 6, WHITE)
    c.outline(skip=[WHITE])
    for x, y in ((13, 1), (2, 1), (14, 10)):
        c.set(x, y, STAR_L)
    save(c, "magical_essence", out)


ALL = [wooden_marionette, sculk_mage_staff, raid_banner,
       clockwork_core, clockwork_gauntlet, mechanical_heart, automaton_armor,
       clockwork_trophy, clockwork_loot_box, mech_scrap,
       astral_compass, starpiercer, astral_mantle, magisters_codex,
       starbound_trophy, starbound_loot_box, magical_essence]
NAMES = [fn.__name__ for fn in ALL]


def preview(out_dir, path, scale=16, per_row=6):
    """Every icon at `scale`x on a hotbar-grey ground, each with a true-size 2x copy beneath."""
    from PIL import Image
    cell = 16 * scale + 12
    rows = (len(NAMES) + per_row - 1) // per_row
    sheet = Image.new("RGBA", (per_row * cell, rows * (cell + 40)), (139, 139, 139, 255))
    for i, name in enumerate(NAMES):
        x0, y0 = (i % per_row) * cell, (i // per_row) * (cell + 40)
        im = Image.open(out_dir / f"{name}.png").convert("RGBA")
        sheet.alpha_composite(im.resize((16 * scale, 16 * scale), Image.NEAREST), (x0 + 6, y0 + 6))
        sheet.alpha_composite(im.resize((32, 32), Image.NEAREST), (x0 + 6, y0 + cell + 2))
    sheet.save(path)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", type=pathlib.Path, default=OUT)
    ap.add_argument("--preview", type=pathlib.Path)
    args = ap.parse_args()
    for fn in ALL:
        fn(args.out)
    if args.preview:
        preview(args.out, args.preview)
