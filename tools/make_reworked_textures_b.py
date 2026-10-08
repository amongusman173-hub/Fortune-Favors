#!/usr/bin/env python3
"""Redraws every item icon of four bosses so each one reads at hotbar size.

Twenty-six textures, placed pixel by pixel through tools/pixelkit.py (the same approach as
tools/make_boss_gear_textures.py):

    The Void Shaper      void_anchor, voidshaper_loot_box, colossus_trophy, voidsteel_scrap,
                         void_reaver, colossus_plate, shaping_sigil
    The Emerald Sovereign sovereigns_crown, sovereign_loot_box, sovereign_trophy, royal_tribute,
                         royal_contract, sovereigns_bell, emerald_seal
    The Drowned Sovereign abyssal_pearl, sovereigns_heart, drowned_loot_box, leviathans_grasp,
                         tidecaller, abyssal_chain
    The Gale Warden      gale_core, gale_sigil, gale_loot_box, skybreaker, gale_chakram,
                         wardens_mantle

Each boss keeps one palette so its loot reads as a set (void violet on obsidian and deepslate;
emerald on royal gold; abyss teal on sunken iron and bone; sky white on cyan), and inside a set
every icon has its own silhouette - an anchor, a chest, a floating block, a torn shard, a cleaver,
a chestplate of masonry and a claw - so two of them are never confused in a hotbar. Held weapons
keep vanilla's diagonal, grip bottom-left.

The art follows what each item does (see ModItems and economy/SeaAndSkyGear.java): the Void Reaver
"has a hunger for masonry", so its edge is a row of teeth; the Shaping Sigil is "his grip, cast in
miniature", so it is a claw holding a block; the Sovereign's Trophy is "his throne, one block of
it"; the Leviathan's Grasp is "a claw that closed once and never opened again".

These replace the versions drawn by tools/make_new_boss_textures.py,
tools/make_boss_gear_textures.py and tools/make_sea_and_sky_textures.py, none of which writes
these names any more.

Run:  python3 tools/make_reworked_textures_b.py [--out DIR] [--preview PNG]
"""
import argparse
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from pixelkit import Canvas, diag, ramp, rgba  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "resourcepack" / "assets" / "fortuneandfavors" / "textures" / "item"

# Void Shaper
VOID_D, VOID_S, VOID, VOID_L = ramp("#7a36e0")
OBSID_D, OBSID_S, OBSID, OBSID_L = ramp("#3d3052")
SLATE_D, SLATE_S, SLATE, SLATE_L = ramp("#6b6b78")
VGLOW = rgba("#ead6ff")
# Emerald Sovereign
EM_D, EM_S, EM, EM_L = ramp("#28c05e")
GOLD_D, GOLD_S, GOLD, GOLD_L = ramp("#f2c440")
VEL_D, VEL_S, VEL, VEL_L = ramp("#b02a3e")
PAPER_D, PAPER_S, PAPER, PAPER_L = ramp("#e9dcb4")
WOOD_D, WOOD_S, WOOD, WOOD_L = ramp("#8a5a34")
INK = rgba("#3a2e26")
SHINE = rgba("#ffffff")
# Drowned Sovereign
ABY_D, ABY_S, ABY, ABY_L = ramp("#1f6390")
TEAL_D, TEAL_S, TEAL, TEAL_L = ramp("#3cc8c4")
TGLOW = rgba("#b4fff4")
BONE_D, BONE_S, BONE, BONE_L = ramp("#cfe2d6")
IRON_D, IRON_S, IRON, IRON_L = ramp("#56677a")
SUNK_D, SUNK_S, SUNK, SUNK_L = ramp("#6e6250")
VERD_D, VERD_S, VERD, VERD_L = ramp("#58b49a")
KELP_D, KELP_S, KELP, KELP_L = ramp("#3f8a46")
# Gale Warden
SKY_D, SKY_S, SKY, SKY_L = ramp("#c8dceb")
CYAN_D, CYAN_S, CYAN, CYAN_L = ramp("#4cc8ea")
SILV_D, SILV_S, SILV, SILV_L = ramp("#9aabbd")
WGLOW = rgba("#f4ffff")


def save(c, name, out):
    out.mkdir(parents=True, exist_ok=True)
    c.image().save(out / f"{name}.png")
    print("wrote", name)


def draw(c, rows, pal):
    """Canvas.grid with short rows padded on the right, so a grid can stop at its last pixel."""
    padded = []
    for r in rows:
        if len(r) > 16:
            raise ValueError(f"row too wide: {r!r}")
        padded.append(r.ljust(16, "."))
    c.grid(padded, pal)


def put(c, pts, colour):
    for x, y in pts:
        c.set(x, y, colour)


# ====================================================================== The Void Shaper

def void_anchor(out):
    """A ship's anchor in void-iron, its flukes still packed with the stone it was dragged through."""
    c = Canvas()
    draw(c, [
        "................",
        "......aAa.......",
        ".....a...o......",
        "......aOo.......",
        "..LLLLLOLLLLs...",
        "..ssssOGOssss...",
        "......OGo.......",
        "......OGo.......",
        "......OGo.......",
        ".V....OGo....V..",
        "VVo...OGo...oVv.",
        ".OO...OGo...oo..",
        "..OOo.OGo.ooo...",
        "...oOOOGOOoo....",
        ".....oOOOo......",
        "................",
    ], {"a": OBSID_L, "A": VOID_L, "o": OBSID_S, "O": OBSID, "L": OBSID_L, "s": OBSID_S,
        "G": VOID, "V": VOID_L, "v": VOID})
    # rubble wedged on the fluke tips and a chip falling from the crown
    put(c, [(0, 9), (14, 9), (1, 12)], SLATE)
    put(c, [(0, 8)], SLATE_L)
    put(c, [(15, 10)], SLATE_S)
    put(c, [(7, 6), (7, 10)], VGLOW)
    c.outline(skip=[VGLOW])
    save(c, "void_anchor", out)


def voidshaper_loot_box(out):
    """An obsidian chest split by a violet rift, bound in deepslate, the lock an open eye."""
    c = Canvas()
    draw(c, [
        "................",
        "................",
        ".lLLLLLLLLLLLLs.",
        ".LOOOOOOOOOOOOs.",
        ".LOOOVOOOOOOOOs.",
        ".LOOOOVOOOOOVOs.",
        ".ssssssKKKssssd.",
        ".lLLLLKwWkLLLLs.",
        ".LOOOOKWWkOOOOs.",
        ".LOOOVOKkkOOOOs.",
        ".LOOOOVOOOOVOOs.",
        ".LOOOOOVOOVOOOs.",
        ".LOOOOOOOOOOOOs.",
        ".dddddddddddddd.",
        "................",
        "................",
    ], {"l": SLATE_L, "L": SLATE, "s": SLATE_S, "d": SLATE_D, "O": OBSID, "V": VOID_L,
        "K": VOID_S, "k": VOID_D, "w": VGLOW, "W": VOID_L})
    # deepslate straps down the front
    for y in (3, 4, 5, 8, 9, 10, 11, 12):
        c.set(3, y, SLATE_S)
        c.set(12, y, SLATE_S)
    c.outline(skip=[VGLOW])
    save(c, "voidshaper_loot_box", out)


def colossus_trophy(out):
    """A deepslate block held off a plinth by violet light - the one he never got to throw."""
    c = Canvas()
    top, left, right = rgba("#aeaebc"), rgba("#74747f"), rgba("#4a4a55")
    draw(c, [
        ".......TT.......",
        ".....TTTTTT.....",
        "...TTTTTTTTTT...",
        "..ETTTTTTTTTTT..",
        "..LLLTTTTTTRRR..",
        "..LLLLLTTRRRRR..",
        "..LLLLLLRRRRRR..",
        "..LLLLLLRRRRRR..",
        "..LLLLLLRRRRRR..",
        "..LLLLLLRRRRRR..",
        "....LLLLRRRR....",
        "......LLRR......",
        "................",
        "....v.vVVv.v....",
        "..GOOOOOOOOOOo..",
        "...oooooooooo...",
    ], {"T": top, "E": SLATE_L, "L": left, "R": right, "v": VOID, "V": VGLOW,
        "G": VOID_L, "O": OBSID_L, "o": OBSID_S})
    # a lit top-left edge, and one masonry course across each side face
    put(c, [(7, 0), (5, 1), (6, 1), (3, 2), (4, 2), (2, 3)], rgba("#d2d2de"))
    put(c, [(2, 7), (3, 7), (4, 7), (5, 7), (6, 7), (7, 7)], SLATE_S)
    put(c, [(8, 7), (9, 7), (10, 7), (11, 7), (12, 7), (13, 7)], SLATE_D)
    # the violet crack it is coming apart along, down the front edge
    put(c, [(8, 2), (7, 3), (8, 4), (7, 5), (8, 6), (7, 8), (8, 9), (8, 10)], VOID_L)
    put(c, [(7, 6), (7, 4)], VGLOW)
    c.outline(skip=[VGLOW, VOID])
    save(c, "colossus_trophy", out)


def voidsteel_scrap(out):
    """A riveted offcut of void-steel plate, bent in the middle, torn and glowing down one side."""
    c = Canvas()
    vs_d, vs_s, vs, vs_l = ramp("#6e5c96")
    draw(c, [
        "................",
        "................",
        "...LLLLLLLL.....",
        "..LMMMMMMMMv....",
        "..LMMMMMMMMMv...",
        "..LMMMMMMMMMMg..",
        "..LMMMMMMMMMv...",
        "...dddddddddddv.",
        "....LMMMMMMMMMg.",
        "....LMMMMMMMMv..",
        "....LMMMMMMMMMg.",
        ".....dMMMMMMMv..",
        "......dddMMMg...",
        "..........v.....",
        "................",
        "................",
    ], {"M": vs, "L": vs_l, "d": vs_s, "o": SHINE, "v": VOID_L, "g": VGLOW})
    # a row of rivets along each half, and a scored line where it was cut from a sheet
    put(c, [(4, 3), (7, 3), (10, 3), (6, 8), (9, 8), (12, 8)], vs_l)
    put(c, [(4, 4), (7, 4), (10, 4), (6, 9), (9, 9), (12, 9)], vs_d)
    put(c, [(6, 11), (7, 11), (8, 11)], VOID)
    c.outline(skip=[VGLOW])
    save(c, "voidsteel_scrap", out)


def void_reaver(out):
    """A square void-stone cleaver whose edge is a row of glowing teeth - it eats masonry."""
    c = Canvas()

    def piece(x, y):
        s, t = diag(x, y)
        if -1 <= t <= 10 and 12 <= s <= 20:
            return True
        # teeth standing off the cutting edge, one every other pixel along it
        if 0 <= t <= 9 and s == 21 and t % 4 == 1:
            return True
        # the grip runs off the spine end, as a cleaver's does
        return -12 <= t <= -2 and s in (13, 14)

    def colour(x, y):
        s, t = diag(x, y)
        if t < -1:
            if t <= -11:
                return VOID_L if s == 13 else VOID
            if t == -2:
                return VOID_L if s == 13 else VOID_S
            return OBSID_L if (t % 2 == 0) == (s == 13) else OBSID_S
        if s == 21:
            return VGLOW
        if s == 20:
            return VOID_L
        if s == 19:
            return VOID
        if s == 12:
            return SLATE_L
        if s == 13:
            return SLATE
        # the hanging hole by the spine, and a rune scored down the flat
        if (s, t) in ((14, 8), (15, 9)):
            return OBSID_D
        if s == 16 and 0 <= t <= 6:
            return VOID_S
        return OBSID if (s + t) % 6 else OBSID_L

    c.fill_where(piece, colour)
    c.outline(skip=[VGLOW])
    save(c, "void_reaver", out)


def colossus_plate(out):
    """A chestplate laid up out of deepslate blocks, the seams between them lit violet."""
    c = Canvas()
    shape = [
        "................",
        "..XXXX....XXXX..",
        ".XXXXXX..XXXXXX.",
        ".XXXXXXXXXXXXXX.",
        ".XXXXXXXXXXXXXX.",
        ".XXXXXXXXXXXXXX.",
        "..XXXXXXXXXXXX..",
        "...XXXXXXXXXX...",
        "...XXXXXXXXXX...",
        "...XXXXXXXXXX...",
        "...XXXXXXXXXX...",
        "...XXXXXXXXXX...",
        "...XXXXXXXXXX...",
        "...XXXXXXXXXX...",
        "................",
        "................",
    ]

    def piece(x, y):
        return shape[y][x] == "X"

    def colour(x, y):
        row = (y - 1) // 3
        off = 0 if row % 2 == 0 else 2
        seam_h = (y - 1) % 3 == 2
        seam_v = (x + off) % 4 == 3
        if seam_h or seam_v:
            return VOID_L if (x * 3 + y * 5) % 7 in (0, 3) else OBSID_D
        # each block lit on its upper-left
        if (y - 1) % 3 == 0 and (x + off) % 4 == 0:
            return SLATE_L
        if x >= 9 or y >= 12:
            return SLATE_S
        return SLATE

    c.fill_where(piece, colour)
    # shoulder rims and a darker centre ridge
    put(c, [(2, 1), (3, 1), (4, 1), (10, 1), (11, 1), (12, 1)], SLATE_L)
    put(c, [(7, 4), (8, 4)], VGLOW)
    c.outline(skip=[VGLOW])
    save(c, "colossus_plate", out)


def shaping_sigil(out):
    """His grip in miniature: a void-iron claw closed round a block it is about to throw."""
    c = Canvas()
    draw(c, [
        "................",
        "..k..........k..",
        "..K....TT....K..",
        ".KK..TTTTTT..KK.",
        ".K...LLTTRR...K.",
        ".K...LLLRRR...K.",
        ".KK..LLLRRR..KK.",
        "..KK...LR...KK..",
        "...KK.v..v.KK...",
        "...HKKKKKKKKH...",
        "....HHHHHHHH....",
        ".....HHHHHH.....",
        "......HhhH......",
        "......hVVh......",
        "......hhhh......",
        "................",
    ], {"k": VOID_L, "K": OBSID_L, "H": OBSID, "h": OBSID_S, "T": SLATE_L, "L": SLATE,
        "R": SLATE_S, "v": VOID_L, "V": VOID_L})
    # claw tips burn violet; the inside of each finger catches the block's glow
    put(c, [(2, 1), (13, 1)], VGLOW)
    put(c, [(2, 5), (13, 5), (4, 8), (11, 8)], VOID)
    put(c, [(7, 13), (8, 13)], VGLOW)
    c.outline(skip=[VGLOW])
    save(c, "shaping_sigil", out)


# ====================================================================== The Emerald Sovereign

def sovereigns_crown(out):
    """A gold crown with three emerald-tipped points and a band of cut stones - far too small."""
    c = Canvas()
    draw(c, [
        "................",
        "................",
        "................",
        "..e....e....e...",
        ".eEe..eEe..eEe..",
        "..Y....Y....Y...",
        ".YYY..YYY..YYY..",
        ".YYYY.YYY.YYYY..",
        ".YYYYYYYYYYYYY..",
        ".LLLLLLLLLLLLL..",
        ".YeYYRYeYRYYeY..",
        ".YEyYRYEYRYyEy..",
        ".LLLLLLLLLLLLL..",
        ".sssssssssssss..",
        "................",
        "................",
    ], {"e": EM_L, "E": EM, "Y": GOLD, "y": GOLD_S, "L": GOLD_L, "s": GOLD_S, "R": VEL})
    # left points lit, right points shadowed
    put(c, [(1, 6), (1, 7), (6, 6), (6, 7), (11, 6), (10, 7)], GOLD_L)
    put(c, [(3, 6), (4, 7), (8, 6), (8, 7), (13, 6), (13, 7), (13, 8)], GOLD_S)
    # velvet showing between the points
    put(c, [(5, 7), (9, 7)], VEL_S)
    put(c, [(2, 4), (7, 4), (12, 4)], SHINE)
    c.outline(skip=[SHINE])
    save(c, "sovereigns_crown", out)


def sovereign_loot_box(out):
    """An emerald-lacquered chest in gold, a cut emerald for its lock."""
    c = Canvas()
    draw(c, [
        "................",
        "................",
        ".YYYYYYYYYYYYYy.",
        ".YeeEEEEEEEEEgy.",
        ".YeEEEEEEEEEEgy.",
        ".YgggggggggggGy.",
        ".ssssssLLsssssd.",
        ".YYYYYLwELYYYYy.",
        ".YeEEEYEEgYEEgy.",
        ".YEEEEsYYsEEEgy.",
        ".YEEEEEEEEEEEgy.",
        ".YEEEEEEEEEEEgy.",
        ".YgggggggggggGy.",
        ".dddddddddddddd.",
        "................",
        "................",
    ], {"Y": GOLD, "y": GOLD_S, "s": GOLD_S, "d": GOLD_D, "L": GOLD_L, "e": EM_L, "E": EM,
        "g": EM_S, "G": EM_D, "w": SHINE})
    put(c, [(1, 2), (2, 2), (1, 3), (1, 7)], GOLD_L)
    c.outline(skip=[SHINE])
    save(c, "sovereign_loot_box", out)


def sovereign_trophy(out):
    """His throne, one block of it: a gold-framed emerald throne with a velvet cushion."""
    c = Canvas()
    draw(c, [
        "......L.L.......",
        ".....LYYYy......",
        "....LYeYeYy.....",
        "....YYYYYYy.....",
        "....YeEEEgy.....",
        "....YEEwEgy.....",
        "....YEEEEgy.....",
        "....YEEEEgy.....",
        "..LYYrrrrrYYy...",
        "..YyYRRRRRYyy...",
        "..Y.yyyyyyy.y...",
        "....eEEEEEg.....",
        "....EgEgEgG.....",
        "....Y.....y.....",
        "....y.....d.....",
        "................",
    ], {"L": GOLD_L, "Y": GOLD, "y": GOLD_S, "d": GOLD_D, "e": EM_L, "E": EM, "g": EM_S,
        "G": EM_D, "w": SHINE, "r": VEL_L, "R": VEL})
    put(c, [(7, 0)], EM_L)
    c.outline(skip=[SHINE])
    save(c, "sovereign_trophy", out)


def royal_tribute(out):
    """Tribute paid in: cut emeralds heaped on a gold offering dish."""
    c = Canvas()
    draw(c, [
        "................",
        "................",
        "......eE........",
        ".....eEEg.......",
        ".....EEEg.......",
        "...eE.Eg.eEE....",
        "..eEEg..eEEEg...",
        "..EEEgeEgEEEg...",
        "...Eg.eEEgEg....",
        "..LLLLLLLLLLLL..",
        ".LYYYYYYYYYYYYy.",
        "..YYYYYYYYYYYy..",
        "...ssssssssss...",
        ".....sYYYYs.....",
        "....LYYYYYYy....",
        "................",
    ], {"e": EM_L, "E": EM, "g": EM_S, "L": GOLD_L, "Y": GOLD, "y": GOLD_S, "s": GOLD_S})
    put(c, [(6, 2), (3, 6), (9, 6)], SHINE)
    c.outline(skip=[SHINE])
    save(c, "royal_tribute", out)


def royal_contract(out):
    """A parchment scroll of terms, signed in a hand not yours, under an emerald wax seal."""
    c = Canvas()
    draw(c, [
        "................",
        "..wWWWWWWWWWw...",
        ".wPPPPPPPPPPPw..",
        "..WWWWWWWWWWW...",
        "..PkkkkkkkkpP...",
        "..PPPPPPPPPPP...",
        "..PkkkkkkpPPP...",
        "..PPPPPPPPPPP...",
        "..PkkkkkkkkPP...",
        "..PPPPPPPPPPP...",
        "..PPPPPPEEPPP...",
        "..PvvvPEeEEPp...",
        "..wWWWWEEEgWw...",
        ".wPPPPPPgRRPPw..",
        "..WWWWWWWRWRw...",
        "................",
    ], {"P": PAPER, "p": PAPER_S, "W": PAPER_S, "w": WOOD_S, "k": INK, "E": EM, "e": EM_L,
        "g": EM_S, "R": VEL, "v": VEL_S})
    put(c, [(2, 2), (3, 2), (3, 13)], PAPER_L)
    c.outline()
    save(c, "royal_contract", out)


def sovereigns_bell(out):
    """A gold bell with an emerald band; the lines either side are the ring that stuns."""
    c = Canvas()
    draw(c, [
        "................",
        "......LYy.......",
        "......Y.y.......",
        ".....LYYYy......",
        "....LYYYYYy.....",
        "....LYYYYYy.....",
        "....LYYYYYy.....",
        "...LYYYYYYYy....",
        "...eEEEEEEEg....",
        "...LYYYYYYYy....",
        "..LYYYYYYYYYy...",
        "..ssssssssssd...",
        ".......Ew.......",
        ".......g........",
        "................",
        "................",
    ], {"L": GOLD_L, "Y": GOLD, "y": GOLD_S, "s": GOLD_S, "d": GOLD_D, "e": EM_L, "E": EM,
        "g": EM_S, "w": SHINE})
    # the ring going out
    put(c, [(0, 5), (0, 6), (1, 4), (1, 7), (14, 5), (14, 6), (13, 4), (13, 7),
            (15, 3), (15, 8)], EM_L)
    put(c, [(5, 4), (5, 5)], GOLD_L)
    c.outline(skip=[EM_L, SHINE])
    save(c, "sovereigns_bell", out)


def emerald_seal(out):
    """A treasury stamp: a wooden grip, a gold collar, and a cut-emerald face."""
    c = Canvas()
    draw(c, [
        "................",
        "......VWw.......",
        ".....VWWWw......",
        ".....WWWWw......",
        "......Www.......",
        "......Wwd.......",
        "......Wwd.......",
        ".....LYYYy......",
        "....LYYYYYy.....",
        "...LYYYYYYYy....",
        "..LYYYYYYYYYy...",
        "..eEEEEEEEEEg...",
        "..EEgEEwEEgEg...",
        "..ggggggggggG...",
        "................",
        "................",
    ], {"V": WOOD_L, "W": WOOD, "w": WOOD_S, "d": WOOD_D, "L": GOLD_L, "Y": GOLD, "y": GOLD_S,
        "e": EM_L, "E": EM, "g": EM_S, "G": EM_D})
    # a little crown engraved in the collar
    put(c, [(5, 9), (7, 9), (9, 9)], GOLD_S)
    c.outline(skip=[])
    save(c, "emerald_seal", out)


# ====================================================================== The Drowned Sovereign

def abyssal_pearl(out):
    """A black pearl with a teal sheen, sitting in the open half of a deep-sea shell."""
    c = Canvas()
    draw(c, [
        "................",
        "................",
        "................",
        ".....aAAAa......",
        "....aAWAAAa.....",
        "...aAwAAAAAs....",
        "...AAAAAAAAs....",
        "...aAAAAAAts....",
        "....aAAAAtts....",
        ".BB..sssss..bB..",
        ".BLBbBBBbBBbBb..",
        "..BLBBbBBbBBb...",
        "...bBBBbBBBb....",
        "....bbbbbbb.....",
        "................",
        "................",
    ], {"a": ABY_L, "A": ABY, "s": ABY_S, "W": SHINE, "w": TGLOW, "t": TEAL,
        "B": BONE, "b": BONE_S, "L": BONE_L})
    # the iridescent sheen
    put(c, [(4, 6), (4, 7)], TEAL_L)
    put(c, [(9, 6), (10, 6)], TEAL_S)
    c.outline(skip=[SHINE])
    save(c, "abyssal_pearl", out)


def sovereigns_heart(out):
    """A sea-glass heart, still beating: a glowing core, barnacles, a kelp vein."""
    c = Canvas()
    draw(c, [
        "................",
        "................",
        "..aAAa...aAAs...",
        ".aLLAAa.aAAAAs..",
        ".aLAAAAaAAAAAs..",
        ".AAAAAttAAAAAs..",
        ".AAAAtGGtAAAAs..",
        ".AAAAtGWGtAAAs..",
        "..AAAAtGtAAAs...",
        "...AAAAtAAAs....",
        "....AAAAAAs.....",
        ".....AAAAs......",
        "......AAs.......",
        ".......s........",
        "................",
        "................",
    ], {"a": TEAL_L, "A": TEAL, "s": TEAL_S, "L": TGLOW, "t": TEAL_L, "G": TGLOW, "W": SHINE})
    # deep abyss shading low on the right
    put(c, [(10, 8), (9, 9), (9, 10), (8, 11), (11, 7), (12, 6), (12, 5)], ABY_L)
    # barnacles
    put(c, [(3, 9), (11, 3)], BONE)
    put(c, [(4, 10)], BONE_S)
    # pulse lines either side
    put(c, [(0, 9), (15, 1)], TGLOW)
    c.outline(skip=[TGLOW, SHINE])
    save(c, "sovereigns_heart", out)


def drowned_loot_box(out):
    """A sunken chest: waterlogged wood, verdigris bands, barnacles and a trailing kelp strand."""
    c = Canvas()
    draw(c, [
        "................",
        "................",
        ".VVVVVVVVVVVVVv.",
        ".VWWWWWWWWWWWWs.",
        ".VWwWWWWWWwWWWs.",
        ".VwwwwwwwwwwwwS.",
        ".vvvvvvGGvvvvvd.",
        ".VVVVVGtTGVVVVv.",
        ".VWWWWGTTgWWWWs.",
        ".VWWWWWggWWWWWs.",
        ".VWwWWWWWWWwWWs.",
        ".VWWWWWWWWWWWWs.",
        ".VwwwwwwwwwwwwS.",
        ".dddddddddddddd.",
        "................",
        "................",
    ], {"V": VERD, "v": VERD_S, "d": VERD_D, "W": SUNK, "w": SUNK_S,
        "s": SUNK_S, "S": SUNK_D, "G": GOLD, "g": GOLD_S, "t": TGLOW, "T": TEAL})
    put(c, [(1, 2), (2, 2), (1, 3)], VERD_L)
    # barnacles
    put(c, [(3, 11), (4, 10), (11, 4)], BONE)
    put(c, [(12, 10)], BONE_S)
    # kelp hanging off the lid
    put(c, [(10, 6), (10, 7), (11, 8), (11, 9), (10, 10), (10, 11)], KELP)
    put(c, [(11, 7), (12, 9)], KELP_L)
    c.outline(skip=[TGLOW])
    save(c, "drowned_loot_box", out)


def leviathans_grasp(out):
    """A mace whose head is a leviathan's claw, clenched for good round a glowing pearl."""
    c = Canvas()

    def piece(x, y):
        s, t = diag(x, y)
        return -12 <= t <= -3 and s in (15, 16)

    def colour(x, y):
        s, t = diag(x, y)
        if t <= -11:
            return TEAL if s == 15 else TEAL_S
        if t >= -4:
            return GOLD if s == 15 else GOLD_S
        return ABY_L if (s == 15 and t % 2) else (ABY if s == 15 else ABY_S)

    c.fill_where(piece, colour)
    # the pearl it will not let go of
    draw(c, [
        "................",
        "..........pPP...",
        ".........pWPPP..",
        ".........PPPPPs.",
        ".........PPPPPs.",
        "..........PPss..",
    ], {"p": TEAL_L, "P": TEAL, "s": TEAL_S, "W": TGLOW})
    # the scaled wrist the talons grow from
    put(c, [(7, 7), (8, 6), (8, 7), (9, 7), (9, 6), (8, 8)], ABY)
    put(c, [(7, 6), (8, 5)], ABY_L)
    put(c, [(10, 7), (9, 8)], ABY_S)
    # three talons closed over it, tips turned in
    put(c, [(7, 5), (7, 4), (7, 3), (8, 2), (8, 1), (9, 0), (10, 0)], BONE_L)
    put(c, [(11, 0)], BONE)
    put(c, [(9, 5), (10, 4), (11, 3)], BONE)
    put(c, [(10, 5), (11, 4)], BONE_S)
    put(c, [(10, 8), (11, 8), (12, 7), (13, 7), (14, 6), (15, 5), (15, 4), (15, 3)], BONE)
    put(c, [(15, 2), (14, 1)], BONE_L)
    put(c, [(12, 6), (13, 6), (14, 5)], BONE_S)
    c.outline(skip=[TGLOW])
    save(c, "leviathans_grasp", out)


def tidecaller(out):
    """A gold-and-abyss trident with a wave curling up its shaft toward the prongs."""
    c = Canvas()
    # the shaft, banded every third pixel
    for x in range(2, 10):
        c.set(x, 14 - x, ABY_L if x % 3 else TEAL)
        c.set(x, 15 - x, ABY_S)
    c.set(0, 15, GOLD_S)
    c.set(1, 14, GOLD)
    # the head: a crossbar and three prongs
    put(c, [(8, 3), (9, 4), (11, 6), (12, 7)], GOLD)
    put(c, [(10, 5)], GOLD_L)
    put(c, [(9, 2), (10, 1)], GOLD_L)
    put(c, [(11, 0)], SHINE)
    put(c, [(11, 4), (12, 3), (13, 2)], GOLD_L)
    put(c, [(14, 1)], SHINE)
    put(c, [(13, 6), (14, 5)], GOLD)
    put(c, [(15, 4)], GOLD_L)
    put(c, [(9, 5), (10, 6)], GOLD_S)
    # the wave crest wrapped round the shaft, curling over toward the head
    put(c, [(1, 10), (2, 9), (3, 8), (4, 8)], TEAL)
    put(c, [(5, 8), (6, 9)], TEAL_L)
    put(c, [(2, 10), (3, 9), (4, 9)], TEAL_S)
    put(c, [(6, 7), (5, 7)], TGLOW)
    c.outline(skip=[SHINE, TGLOW])
    save(c, "tidecaller", out)


def abyssal_chain(out):
    """A run of sunken iron links ending in a barbed bone hook - for deciding where people stand."""
    c = Canvas()
    holes = []

    def ring(cx, cy):
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                if dx or dy:
                    lit = dx + dy < 0
                    c.set(cx + dx, cy + dy, IRON_L if lit else (IRON if dx + dy == 0 else IRON_S))
        holes.append((cx, cy))

    def bar(cx, cy):
        put(c, [(cx - 1, cy + 1), (cx, cy), (cx + 1, cy - 1)], IRON_S)
        put(c, [(cx, cy + 1), (cx + 1, cy)], IRON_D)
        c.set(cx, cy, TEAL)

    ring(2, 13)
    bar(4, 10)
    ring(6, 9)
    bar(8, 6)
    # the hook: shank up the diagonal, a broad bend, and a barb turned back in
    put(c, [(9, 5), (10, 4), (11, 3)], BONE)
    put(c, [(12, 2), (13, 1), (14, 1)], BONE_L)
    put(c, [(15, 2), (15, 3), (15, 4)], BONE)
    put(c, [(14, 2)], BONE_L)
    put(c, [(14, 5), (13, 6)], BONE_S)
    put(c, [(12, 5), (12, 4)], BONE_L)
    put(c, [(10, 5)], BONE_S)
    put(c, [(11, 2)], TGLOW)
    c.outline(skip=[TGLOW])
    for x, y in holes:
        c.clear(x, y)
    c.clear(13, 4)
    save(c, "abyssal_chain", out)


# ====================================================================== The Gale Warden

def gale_core(out):
    """A knot of moving air folded small: a bright spiral that would rather unwind."""
    c = Canvas()
    draw(c, [
        "................",
        "................",
        ".....sSSSc......",
        "...sSWWWWSSc....",
        "..sSWccccWSSc...",
        "..SWcSSSScWSc...",
        ".sSWcSwWwScWSc..",
        ".SWcSwccWSScWS..",
        ".SWcSWcSScWcWS..",
        ".sSWcSScccWcWs..",
        "..SWWcccccWWS...",
        "..sSWWWWWWWSs...",
        "...ssSSSSSss....",
        "................",
        "................",
        "................",
    ], {"s": CYAN_S, "S": CYAN, "c": CYAN_L, "W": WGLOW, "w": SKY})
    # streamers peeling off
    put(c, [(12, 1), (13, 1), (14, 2)], CYAN_L)
    put(c, [(1, 13), (2, 14), (0, 12)], CYAN)
    c.outline(skip=[WGLOW])
    save(c, "gale_core", out)


def gale_sigil(out):
    """A diamond pendant of storm silver with a wind-rune gem; the air is already wrapping it."""
    c = Canvas()
    draw(c, [
        "......kkk.......",
        "......k.k.......",
        ".......L........",
        "......LSs.......",
        ".....LSSSs......",
        "....LSSCSSs.....",
        "...LSSCWCSSs....",
        "..LSSCcCCCSSs...",
        "...SSSCCCSSs....",
        "....SSSCSSs.....",
        ".....SSSSs......",
        "......SSs.......",
        ".......s........",
    ], {"k": SILV_S, "L": SILV_L, "S": SILV, "s": SILV_S, "C": CYAN, "c": CYAN_L,
        "W": WGLOW})
    # two gusts curling round it, one from each side
    put(c, [(1, 4), (0, 5), (0, 6), (0, 7), (1, 8), (2, 9)], CYAN_L)
    put(c, [(2, 3)], WGLOW)
    put(c, [(14, 11), (15, 10), (15, 9), (15, 8), (14, 7), (13, 6)], CYAN_L)
    put(c, [(13, 12)], WGLOW)
    put(c, [(3, 13), (4, 14), (5, 14)], CYAN)
    put(c, [(12, 2), (11, 1), (10, 1)], CYAN)
    c.outline(skip=[WGLOW, CYAN_L, CYAN])
    save(c, "gale_sigil", out)


def gale_loot_box(out):
    """A pale storm-wood chest in silver bands, a wind-curl worked into its lock."""
    c = Canvas()
    draw(c, [
        "................",
        "................",
        ".LLLLLLLLLLLLLs.",
        ".LKKKKKKKKKKKks.",
        ".LKcCCCKKKKKKks.",
        ".LkkkkkkkkkkkSs.",
        ".ssssssLLsssssd.",
        ".LLLLLLWcLLLLLs.",
        ".LKKKKLcClKKKks.",
        ".LKKKKKllKKKKks.",
        ".LKKKKKKKKKKKks.",
        ".LKKKKKKKKKKKks.",
        ".LkkkkkkkkkkkSs.",
        ".dddddddddddddd.",
        "................",
        "................",
    ], {"L": SILV_L, "s": SILV_S, "d": SILV_D, "l": SILV, "K": SKY, "k": SKY_S, "S": SKY_D,
        "c": CYAN_L, "C": CYAN, "W": WGLOW})
    # silver straps down the front, as the other boss chests have
    for y in (3, 4, 5, 8, 9, 10, 11, 12):
        c.set(3, y, SILV)
        c.set(12, y, SILV_S)
    # a gust curling across the front
    put(c, [(4, 10), (5, 11), (6, 11), (7, 11), (8, 10)], CYAN)
    put(c, [(9, 11), (10, 11)], CYAN_L)
    c.outline(skip=[WGLOW])
    save(c, "gale_loot_box", out)


def skybreaker(out):
    """A heavy greatsword of storm steel, a wind-bright edge and swept wing-guard."""
    c = Canvas()

    def piece(x, y):
        s, t = diag(x, y)
        if -11 <= t <= -5 and s in (15, 16):
            return True
        if t in (-4, -3) and 11 <= s <= 20:
            return True
        return -2 <= t <= 12 and 13 <= s <= 18 and not (t >= 10 and (s <= 13 + (t - 10) or s >= 18 - (t - 10)))

    def colour(x, y):
        s, t = diag(x, y)
        if t <= -10:
            return CYAN if s == 15 else CYAN_S
        if t <= -5:
            return SILV_D if (t % 2) else SILV_S
        if t in (-4, -3):
            return SILV_L if s <= 13 else (SILV if s <= 17 else CYAN)
        if s == 13:
            return WGLOW
        if s == 14:
            return SKY_L
        if s in (15, 16):
            return CYAN_S if 0 <= t <= 8 and s == 15 else SKY
        if s == 17:
            return SKY_S
        return SILV_S

    c.fill_where(piece, colour)
    # wind streaks trailing the swing
    put(c, [(2, 4), (3, 4), (4, 3), (1, 7), (2, 7)], CYAN_L)
    c.outline(skip=[WGLOW, CYAN_L])
    save(c, "skybreaker", out)


def gale_chakram(out):
    """A silver ring-blade with four swept hooks and a wind-lit inner edge - mid-spin."""
    import math
    c = Canvas()
    cx = cy = 7.5

    def polar(x, y):
        dx, dy = x - cx, y - cy
        return math.hypot(dx, dy), math.degrees(math.atan2(dy, dx)) % 360

    def piece(x, y):
        r, a = polar(x, y)
        if 2.6 <= r <= 5.0:
            return True
        if 5.0 < r <= 8.1:
            phase = (a - 10) % 90
            return phase < 52 * (8.1 - r) / 3.1
        return False

    def colour(x, y):
        r, a = polar(x, y)
        lit = 135 <= a <= 315  # upper left
        if r < 3.6:
            return CYAN_L if lit else CYAN
        if r <= 5.0:
            return SILV_L if lit else SILV
        phase = (a - 10) % 90
        return WGLOW if phase < 9 and r < 7 else (SILV_L if lit else SILV_S)

    c.fill_where(piece, colour)
    c.outline(skip=[WGLOW])
    # the hole stays a hole
    for x in range(16):
        for y in range(16):
            if polar(x, y)[0] < 2.3:
                c.clear(x, y)
    save(c, "gale_chakram", out)


def wardens_mantle(out):
    """A pale cuirass under a mantle of overlapping feathers, a wind-blue sash across it."""
    c = Canvas()
    draw(c, [
        "................",
        "..FFFF....FFFF..",
        ".FfFFFF..FFFFfF.",
        ".FFfFFFCCFFFfFF.",
        ".fFFfFSWcSFfFFf.",
        ".f.FFSSSSSSFF.f.",
        "...FFfFFfFFfs...",
        "...SSsSSsSSss...",
        "...fFFfFFfFFs...",
        "...CcSsSSsSSs...",
        "...FFCcFfFFfs...",
        "...SsSSCcSSss...",
        "...FFfFFfCcFs...",
        "...ssssssssCs...",
        "................",
        "................",
    ], {"F": SKY_L, "f": SKY_S, "S": SKY, "s": SKY_S, "v": SKY_D, "C": CYAN, "c": CYAN_L,
        "W": WGLOW})
    c.outline(skip=[WGLOW])
    save(c, "wardens_mantle", out)


ALL = [
    void_anchor, voidshaper_loot_box, colossus_trophy, voidsteel_scrap, void_reaver,
    colossus_plate, shaping_sigil,
    sovereigns_crown, sovereign_loot_box, sovereign_trophy, royal_tribute, royal_contract,
    sovereigns_bell, emerald_seal,
    abyssal_pearl, sovereigns_heart, drowned_loot_box, leviathans_grasp, tidecaller,
    abyssal_chain,
    gale_core, gale_sigil, gale_loot_box, skybreaker, gale_chakram, wardens_mantle,
]


def preview(src, dest, scale=8):
    """One sheet of every icon here, scaled up, with each at 2x beside it for a hotbar check."""
    from PIL import Image, ImageDraw
    cols = 7
    cell = 16 * scale + 16
    rows = (len(ALL) + cols - 1) // cols
    sheet = Image.new("RGBA", (cols * cell + 16, rows * (cell + 24) + 16), (58, 58, 64, 255))
    d = ImageDraw.Draw(sheet)
    for i, fn in enumerate(ALL):
        x = 16 + (i % cols) * cell
        y = 16 + (i // cols) * (cell + 24)
        d.rectangle([x, y, x + 16 * scale - 1, y + 16 * scale - 1], fill=(139, 139, 139, 255))
        icon = Image.open(src / f"{fn.__name__}.png").convert("RGBA")
        sheet.alpha_composite(icon.resize((16 * scale, 16 * scale), Image.NEAREST), (x, y))
        sheet.alpha_composite(icon.resize((32, 32), Image.NEAREST),
                              (x + 16 * scale - 32, y + 16 * scale + 4))
        d.text((x, y + 16 * scale + 4), fn.__name__[:17], fill=(255, 255, 255, 255))
    sheet.save(dest)
    print("preview", dest)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", type=pathlib.Path, default=OUT)
    ap.add_argument("--preview", type=pathlib.Path, default=None)
    args = ap.parse_args()
    for fn in ALL:
        fn(args.out)
    if args.preview:
        preview(args.out, args.preview)
