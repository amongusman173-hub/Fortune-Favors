#!/usr/bin/env python3
"""Generates the item art for the Ender Dragon's own set.

Two materials (the Heart of the End and the Dragon Scale) and the three
legendary weapons forged from them (Voidfang, Starfall, Enderheart), plus the
five matching `models/item/*.json` files.

Like the Scarlet Devil, Time Lord and four-raid-boss generators this is checked
in, so the art is reproducible and reviewable rather than a binary blob with no
provenance.

How the art is made
-------------------
Every sprite is an explicit 16x16 character grid mapped through one shared
palette. The first draft of this file drew the sprites out of `ImageDraw`
primitives - ellipses, polygons, one-pixel lines - and the result was the thing
the ask was complaining about: a heart that read as a lumpy blob, a "bow" that
was a thin vertical scratch, a scale that was two overlapping boxes. Pixel art
is decided a pixel at a time, so it is written a pixel at a time here, and every
row is length-checked on the way in so a mis-typed row is a loud failure instead
of a silently shifted sprite.

The three held weapons share one silhouette each - a diagonal blade, a drawn bow,
a heavy head on a shaft - so the set reads as one family in the inventory rather
than five unrelated sprites.

Each item also needs a dispatch entry in `tools/make_item_definitions.py`; this
script only draws the pixels and writes the models.

Run:  python3 tools/make_ender_dragon_textures.py
"""
import json
import pathlib

from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "resourcepack" / "assets" / "fortuneandfavors" / "textures" / "item"
MODELS = ROOT / "resourcepack" / "assets" / "fortuneandfavors" / "models" / "item"

# ---------------------------------------------------------------- the End palette
#
# One palette for the whole set, so five sprites read as one family: the violet
# ramp is the End's own stone and portal, the teal is the dragon's eye, and the
# gold is the only warm colour anywhere in it - it marks the one accent on each
# sprite (a pommel, a ring, a nocked arrowhead) and nothing else.
PALETTE = {
    ".": (0, 0, 0, 0),          # transparent
    "o": (14, 10, 22, 255),     # outline, near-black
    "k": (46, 24, 72, 255),     # void dark
    "v": (78, 42, 126, 255),    # void mid
    "V": (116, 70, 186, 255),   # void light
    "m": (104, 74, 158, 255),   # scale mid
    "h": (178, 152, 228, 255),  # scale highlight
    "p": (186, 98, 250, 255),   # portal
    "P": (222, 176, 255, 255),  # pale magenta
    "w": (244, 236, 255, 255),  # white-hot
    "e": (110, 226, 214, 255),  # ender teal
    "E": (44, 138, 132, 255),   # ender deep
    "g": (234, 198, 98, 255),   # gold
    "G": (152, 116, 40, 255),   # gold dark
    "#": (36, 24, 50, 255),     # obsidian / haft
}

HANDHELD = {
    "voidfang",
    "starfall",
    "enderheart",
    # The awakened tier is held the same way, so it takes the same handheld parent.
    "voidfang_awakened",
    "starfall_awakened",
    "enderheart_awakened",
    # Starfall's draw frames. A bow is the one weapon whose icon is not one sprite:
    # vanilla swaps between `bow` and `bow_pulling_0/1/2` on a use_duration property, and
    # our custom bows have to ship the same three frames or they stand perfectly still
    # while the player draws them.
    "starfall_pulling_0",
    "starfall_pulling_1",
    "starfall_pulling_2",
    "starfall_awakened_pulling_0",
    "starfall_awakened_pulling_1",
    "starfall_awakened_pulling_2",
}
SIZE = 16


def grid(rows):
    """An explicit character grid -> an image. Rows are validated, not trusted."""
    assert len(rows) == SIZE, f"expected {SIZE} rows, got {len(rows)}"
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    px = img.load()
    for y, row in enumerate(rows):
        assert len(row) == SIZE, f"row {y} is {len(row)} wide, not {SIZE}: {row!r}"
        for x, ch in enumerate(row):
            assert ch in PALETTE, f"row {y} uses unknown palette character {ch!r}"
            px[x, y] = PALETTE[ch]
    return img


def save(img, name):
    OUT.mkdir(parents=True, exist_ok=True)
    img.save(OUT / f"{name}.png")
    model = {
        "parent": "minecraft:item/handheld" if name in HANDHELD else "minecraft:item/generated",
        "textures": {"layer0": f"fortuneandfavors:item/{name}"},
    }
    MODELS.mkdir(parents=True, exist_ok=True)
    (MODELS / f"{name}.json").write_text(json.dumps(model, indent=2) + "\n")
    print("wrote", name)


def paint(img, x, y, ch):
    """Single-pixel touch-up on a grid, for the glints that follow the shape."""
    img.putpixel((x, y), PALETTE[ch])


# ---------------------------------------------------------------- the two materials

def heart_of_the_end():
    """The island's last beat: a violet heart with the end portal still burning in it.

    Two lobes, a notch between them, and a body that tapers to a point - the
    shape is deliberately the vanilla heart's, because this is the thing every
    player who finished the fight is handed and it should be recognisable at
    inventory size. The portal is set into the middle of it and the veins are
    two single pixels, not a spray, so the silhouette stays clean.
    """
    img = grid([
        "................",
        "................",
        "....oo....oo....",
        "...ovVo..oVvo...",
        "..ovVvo..ovVvo..",
        "..ovVpppppppVVo.",
        "..ovVppwwwppVVo.",
        "..ovVpppppppVVo.",
        "...oVVpppVVVo...",
        "....oVVppVVo....",
        ".....oVppVo.....",
        "......oppo......",
        ".......oo.......",
        "................",
        "................",
        "................",
    ])
    # A vein of the End running out of the core into the left lobe, and a spark
    # on the right - two pixels, so the body is not a flat slab.
    paint(img, 4, 4, "e")
    paint(img, 11, 5, "P")
    return img


def dragon_scale():
    """One shed scale: a broad plate, ridged down the middle, tapering to a point.

    Drawn as a single scale rather than two stacked ones. The width steps
    6/8/10/12 and then tapers back down, which is what makes it read as a plate
    instead of a box, and the highlight sits on the left of every row so the
    light source is consistent with the heart and the weapons.
    """
    img = grid([
        "................",
        "................",
        ".....oooooo.....",
        "....ommmmmmo....",
        "...ohhmmppkko...",
        "..ohhmmppppkko..",
        "..ohhmmppppkko..",
        "..ohhmmppppkko..",
        "...ohhmmppkko...",
        "....ohhmpkko....",
        ".....ohmpko.....",
        "......ohpo......",
        ".......oo.......",
        "................",
        "................",
        "................",
    ])
    # The dragon's own colour on the crown of the scale.
    paint(img, 8, 4, "e")
    return img


# ---------------------------------------------------------------- the three weapons

def voidfang():
    """A sword: a long bright blade, a cross guard, a wrapped grip, a gold pommel.

    Redrawn to read as a *sword* at inventory size. The first version had a
    three-pixel blade with the outline on its lower edge and a guard that was
    barely wider than the blade, so at 16x16 it read as a thin violet stick with
    a lump on the end. This one is the shape every Minecraft sword has:

    the blade runs the full diagonal, corner to corner, **four** pixels wide, and
    the row that faces up-left is the bright edge (``P``) - a sword is recognised
    by its edge, not by its body. Inside it the portal runs down the middle
    (``p``) as the rift it is named for, and the last body pixel on the lower
    side stays dark violet so the blade still has a lit side and a shaded one.

    Then the three things that make it a sword rather than a knife: a guard that
    is visibly *wider than the blade* (seven pixels, in two rows, spanning past
    both sides), a grip of dark gold four rows long instead of a single stub, and
    a gold pommel at the end. The guard is horizontal rather than perpendicular
    to the blade - that is how vanilla's own swords are drawn, and a perpendicular
    bar on a forty-five degree blade cannot be more than one pixel wide without
    covering the blade it is guarding.
    """
    img = grid([
        "................",
        ".............PVo",
        "............PVvo",
        "...........PVvo.",
        "..........PVpo..",
        ".........PVpo...",
        "........PVpo....",
        ".......PVpo.....",
        "......PVvo......",
        "..ogGGGgo.......",
        "...ogGgo........",
        "...oGg..........",
        "..oGg...........",
        ".oGg............",
        ".gg.............",
        "................",
    ])
    # One ender spark where the blade leaves the guard, so the rift reads as
    # something leaking out of the steel rather than as a painted stripe.
    paint(img, 10, 4, "e")
    return img


def starfall():
    """A bow strung and drawn, an arrow already on the string.

    The first draft drew the limbs as six separate one-pixel `ImageDraw` lines
    and they came out as a vertical scratch. The second draft fixed the thickness
    but put the string *on* the limbs' inner column, so the two merged into one
    busy vertical line. This one separates them the way a real bow does: the limbs
    are a two-pixel arc from tip to tip that bulges four columns away from the
    string, the string is its own straight column with a genuine gap behind it,
    the middle of the arc is the dark grip, and a nocked arrow lies across both.
    Four things, each distinguishable at 16x16 - which is the difference between a
    bow and a stick.
    """
    img = grid([
        "................",
        "..........oo....",
        "...........oVo..",
        "............oVo.",
        "............oVo.",
        ".............oVo",
        ".............oVo",
        ".............o#o",
        ".............o#o",
        ".............oVo",
        ".............oVo",
        "............oVo.",
        "............oVo.",
        "...........oVo..",
        "..........oo....",
        "................",
    ])
    # The string is drawn as its own column rather than as part of the grid: one
    # straight pale line from tip to tip, which is the whole difference between a
    # bow and a bent stick. The limbs arc away from it and never touch it, so the
    # middle of the bow is a real gap with the string across it.
    for y in range(1, 15):
        paint(img, 9, y, "w")
    # The arrow, nocked on that string: a dark shaft pointing left, a gold head,
    # and two pale fletches at the nock. Drawn last so it lies over the string.
    for x in range(3, 9):
        paint(img, x, 7, "#")
        paint(img, x, 8, "#")
    paint(img, 2, 7, "g")
    paint(img, 2, 8, "g")
    paint(img, 1, 7, "w")
    paint(img, 1, 8, "g")
    paint(img, 7, 6, "P")
    paint(img, 8, 6, "P")
    paint(img, 7, 9, "P")
    paint(img, 8, 9, "P")
    return img


# ---------------------------------------------------------------- Starfall's draw frames
#
# A bow is the one weapon in Minecraft whose icon is not a single sprite: vanilla swaps the
# model on a `use_duration` property between `bow` and `bow_pulling_0/1/2`, so the icon has to
# play the draw. Our custom bows used a plain model node, which is why they stood perfectly
# still while the player pulled them - the shape was there and the animation was not. These
# frames are the animation half, and they are generated rather than hand-drawn so all six stay
# in step with the two idle sprites they come from.

STARFALL_LIMBS = [
    "................",
    "..........oo....",
    "...........oVo..",
    "............oVo.",
    "............oVo.",
    ".............oVo",
    ".............oVo",
    ".............o#o",
    ".............o#o",
    ".............oVo",
    ".............oVo",
    "............oVo.",
    "............oVo.",
    "...........oVo..",
    "..........oo....",
    "................",
]

STARFALL_AWAKENED_LIMBS = [
    "................",
    "..........oVo...",
    "...........oVo..",
    "............oVo.",
    "............oVVo",
    ".............oVo",
    ".............ogo",
    ".............ogo",
    ".............oVo",
    "............oVVo",
    "............oVo.",
    "...........oVo..",
    "..........oVo...",
    "................",
    "................",
    "................",
]


def bow_frame(limbs, span, pull, awakened):
    """One draw frame: the same limbs, with the string pulled back by ``pull`` columns.

    The string is anchored at the tips and bows toward the archer's side, deepest at the
    middle - which is exactly how a real string behaves and exactly what the vanilla sprites
    draw. The arrow rides the nock, so it recedes with the string and its head stays at the
    left edge, where the shot is aimed.
    """
    img = grid(limbs)
    y0, y1 = span
    centre = (y0 + y1) / 2.0
    reach = max(1.0, (y1 - y0) / 2.0)
    string_cols = {}
    for y in range(y0, y1 + 1):
        t = 1.0 - abs(y - centre) / reach
        depth = int(pull * max(0.0, t) + 0.5)
        col = 9 - depth
        string_cols[y] = col
        paint(img, col, y, "w")
    for y in (int(round(centre)), int(round(centre)) + 1):
        sc = string_cols.get(y, 9)
        for x in range(1, sc - 1):
            paint(img, x, y, "#")
        paint(img, sc - 1, y, "P")
        paint(img, 0, y, "g")
    if awakened:
        paint(img, 11, 1, "e")
        paint(img, 11, 12, "e")
    return img


def enderheart():
    """A mace whose head is the Heart of the End: a beating heart on a haft.

    Retextured again, this time from the material it is forged from rather than from the
    boss's skull. The read the ask wanted is "the dragon heart is a mace": the head is the
    **Heart of the End** - the same two lobes, notch and taper as the ingredient sprite, so
    the item in your hand and the item you crafted it from are recognisably the same thing -
    mounted on a real haft with a gold collar between them. Two deep ender sockets where the
    heart's veins are, and the portal still burning inside it.

    It is deliberately head-heavy - ten rows of heart to five of handle - because a mace
    should look like it could only ever be swung once, which is also how it plays.
    """
    img = grid([
        "................",
        "....oo....oo....",
        "...oPPo..oPPo...",
        "..oPPPPooPPPPo..",
        "..oPPPPPPPPPPo..",
        "..oPPpPPPPpPPo..",
        "..oPppPPPPppPo..",
        "...oPPPPPPPPo...",
        "....oPPPPPPo....",
        ".....oPPPPo.....",
        "......ogGo......",
        "......o##o......",
        ".....o##........",
        "....o##.........",
        "...o##..........",
        "..o##...........",
    ])
    # Deep ender sockets in the lobes, and the portal still burning in the middle.
    paint(img, 5, 5, "E")
    paint(img, 10, 5, "E")
    paint(img, 7, 7, "e")
    paint(img, 8, 4, "p")
    return img


def voidfang_awakened():
    """Voidfang reforged: the same blade, with the rift running *inside* it.

    The tier-one sword is recognised by its single bright edge and the portal seam down the
    middle. The awakened one keeps both and adds the two things that read as "more" at
    inventory size without thickening the silhouette into a plank:

    a **fourth** column of blade, so the edge and the body are separate on every row rather
    than only on the upper ones, and the seam on the *inside* of it - the rift stops being
    painted on the steel and starts being held in it. The guard is a row longer and carries a
    single teal gem, and the grip is a real three-wide wrapped handle instead of a two-pixel
    stub, because the upgrade has to be visible on the sprite and not only on the tooltip.

    The gold stays gold. It is the one warm note in the whole set, so it is the only place an
    upgrade is allowed to shout.
    """
    img = grid([
        "................",
        ".............PVo",
        "............PVVo",
        "...........PVVVo",
        "..........PVpVo.",
        ".........PVpVVo.",
        "........PVpVVo..",
        ".......PVpVVo...",
        "......PVpVVo....",
        ".....PVpVVo.....",
        "...ogGGGGGgo....",
        "....ogGgo.......",
        "...ogGgo........",
        "..ogGgo.........",
        ".oggg...........",
        "................",
    ])
    # The gem in the guard, and a spark where the rift leaves the steel.
    paint(img, 7, 10, "e")
    paint(img, 12, 4, "e")
    return img


def starfall_awakened():
    """Starfall reforged: the same bow, strung tighter and already drawing.

    The tier-one bow separates its limbs from its string, and that separation is what makes it
    a bow at all, so this one keeps it and adds the two cues of a stronger bow: the limbs are
    **two columns** through the belly instead of one, and both tips glow ender-teal, which is
    the read of "this one pulls harder". The arrow sits nocked one row higher, on the grip, so
    the silhouette reads as *drawn* rather than as an arrow lying beside a stick.

    Like the base sprite, the string and the arrow are painted on after the grid, because a
    straight string is one line and a grid of one-pixel columns is not where that belongs.
    """
    img = grid([
        "................",
        "..........oVo...",
        "...........oVo..",
        "............oVo.",
        "............oVVo",
        ".............oVo",
        ".............ogo",
        ".............ogo",
        ".............oVo",
        "............oVVo",
        "............oVo.",
        "...........oVo..",
        "..........oVo...",
        "................",
        "................",
        "................",
    ])
    # The string, one pale column from tip to tip, clear of the limbs.
    for y in range(1, 13):
        paint(img, 9, y, "w")
    # The arrow: dark shaft pointing left, gold head, pale fletches at the nock.
    for x in range(2, 9):
        paint(img, x, 6, "#")
        paint(img, x, 7, "#")
    paint(img, 1, 6, "g")
    paint(img, 1, 7, "g")
    paint(img, 7, 5, "P")
    paint(img, 8, 5, "P")
    paint(img, 7, 8, "P")
    paint(img, 8, 8, "P")
    # Both limb tips answer: the bow is awake, not merely strung.
    paint(img, 11, 1, "e")
    paint(img, 11, 12, "e")
    return img


def enderheart_awakened():
    """Enderheart reforged: the same heart, crowned with gold and beating harder.

    The tier-one mace is the Heart of the End on a haft, and that shape is what makes it a
    mace, so this one keeps it and spends the upgrade on the three things a bigger heart has:
    a **gold band across the lobes**, a pair of ender-bright eyes where the sockets were, and
    a **second gold band on the haft**, which is the only way a handle can look heavier. The
    portal in the middle is twice the size, because the thing is closer to waking up.
    """
    img = grid([
        "................",
        "....oo....oo....",
        "...oPPo..oPPo...",
        "..oPPPPooPPPPo..",
        "..oggggggggggo..",
        "..oPPePPPPePPo..",
        "..oPppPPPPppPo..",
        "...oPPPPPPPPo...",
        "....oPPPPPPo....",
        ".....oPPPPo.....",
        "......ogGo......",
        "......o##o......",
        ".....og##o......",
        "....o##.........",
        "...o##..........",
        "..o##...........",
    ])
    # Eyes, and a portal that has grown into the whole middle of the heart.
    paint(img, 5, 5, "e")
    paint(img, 10, 5, "e")
    paint(img, 7, 7, "p")
    paint(img, 8, 7, "p")
    paint(img, 8, 4, "w")
    return img


def main():
    save(heart_of_the_end(), "heart_of_the_end")
    save(dragon_scale(), "dragon_scale")
    save(voidfang(), "voidfang")
    save(starfall(), "starfall")
    save(enderheart(), "enderheart")
    save(voidfang_awakened(), "voidfang_awakened")
    save(starfall_awakened(), "starfall_awakened")
    save(enderheart_awakened(), "enderheart_awakened")
    # The draw frames: three per bow, in the vanilla order, at increasing pull.
    for i, pull in enumerate((0.6, 1.1, 1.7)):
        save(bow_frame(STARFALL_LIMBS, (1, 14), pull, False), f"starfall_pulling_{i}")
        save(bow_frame(STARFALL_AWAKENED_LIMBS, (1, 12), pull, True), f"starfall_awakened_pulling_{i}")


if __name__ == "__main__":
    main()
