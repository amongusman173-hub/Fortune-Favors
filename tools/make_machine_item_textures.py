#!/usr/bin/env python3
"""Draws the held icon for every machine this mod sells.

A machine is a vanilla block wearing a name, and until now it also wore the vanilla block's
*picture*: a Super Hopper in the hand was the same sprite as the hopper in a farm chest, and a
player with both in one inventory had to read the tooltip to tell them apart. These icons are the
answer to that, one per machine, drawn as a 16x16 item sprite rather than a block model - so what
the player holds is a little machine badge, not a photograph of a hopper.

Every icon is the same casing with a different glyph and a different accent colour, because that is
what they are: one family of machines. The casing says "this mod's machine", the glyph says which
one, and the colour is what a player actually recognises at a glance across a hotbar.

Run:  python3 tools/make_machine_item_textures.py
"""

import json
import pathlib

from PIL import Image, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parent.parent
ITEMS = ROOT / "resourcepack" / "assets" / "fortuneandfavors" / "textures" / "item"
MODELS = ROOT / "resourcepack" / "assets" / "fortuneandfavors" / "models" / "item"
DEFS = ROOT / "resourcepack" / "assets" / "minecraft" / "items"

CLEAR = (0, 0, 0, 0)
# The casing every machine icon is built on: a dark rim, a body, and a lighter top edge so the
# badge reads as a solid object at inventory scale and not as a flat square.
RIM = (48, 50, 58, 255)
BODY = (118, 121, 132, 255)
BODY_TOP = (156, 160, 172, 255)

# Glyph pixels are drawn in the machine's own accent, with a dark outline under them so a pale
# accent on a pale casing still reads.
# Java's own item definitions dispatch on this property - the same name the sorter tag and every
# other custom item in this pack already use, and the one Geyser's legacy definition type reads.
MODEL_PROPERTY = "minecraft:custom_model_data"

GLYPHS = {
    "coin": [
        "..####..",
        ".#oooo#.",
        "#o####o#",
        "#o#..#o#",
        "#o#..#o#",
        "#o####o#",
        ".#oooo#.",
        "..####..",
    ],
    "up": [
        "...##...",
        "..####..",
        ".######.",
        "###..###",
        "..#..#..",
        "..#..#..",
        "..#..#..",
        "..####..",
    ],
    "updown": [
        "...##...",
        "..####..",
        ".######.",
        "...##...",
        "...##...",
        ".######.",
        "..####..",
        "...##...",
    ],
    "ring": [
        "..####..",
        ".#....#.",
        "#......#",
        "#......#",
        "#......#",
        "#......#",
        ".#....#.",
        "..####..",
    ],
    "star": [
        "...##...",
        "...##...",
        ".#.##.#.",
        "########",
        "########",
        ".#.##.#.",
        "...##...",
        "...##...",
    ],
    "hammer": [
        "..####..",
        ".######.",
        "########",
        "..####..",
        "...##...",
        "...##...",
        "...##...",
        "...##...",
    ],
    "link": [
        ".####...",
        "#....#..",
        "#....#..",
        ".####...",
        "...####.",
        "..#....#",
        "..#....#",
        "...####.",
    ],
    "wrench": [
        ".....###",
        "....#..#",
        "...##.##",
        "..###.#.",
        ".###....",
        "###.....",
        "##......",
        "#.......",
    ],
    "bars": [
        "........",
        "########",
        "........",
        "######..",
        "........",
        "####....",
        "........",
        "##......",
    ],
    "funnel": [
        "########",
        "########",
        "..####..",
        "..####..",
        "...##...",
        "...##...",
        "...##...",
        "...##...",
    ],
    "two_arrows": [
        "........",
        "..#####.",
        "..#####.",
        "........",
        "..#..#..",
        "..#..#..",
        ".######.",
        ".######.",
    ],
    "cross": [
        "##....##",
        "###..###",
        ".######.",
        "..####..",
        "..####..",
        ".######.",
        "###..###",
        "##....##",
    ],
    "split": [
        "##....##",
        "###..###",
        ".##..##.",
        "..####..",
        "...##...",
        "...##...",
        "...##...",
        "...##...",
    ],
    "flame": [
        "...##...",
        "..###...",
        ".####.#.",
        "#####.##",
        "####..##",
        "#####.##",
        ".######.",
        "..####..",
    ],
    "campfire": [
        "...##...",
        "..####..",
        ".######.",
        "########",
        "#......#",
        ".######.",
        ".#.##.#.",
        ".#.##.#.",
    ],
    "sprout": [
        "...##...",
        ".#####..",
        "..###...",
        "...##...",
        "...##...",
        "..####..",
        ".######.",
        "########",
    ],
    "wheat": [
        "...##...",
        "..####..",
        "..####..",
        ".######.",
        ".######.",
        "..####..",
        "...##...",
        "...##...",
    ],
    "drops": [
        "...##...",
        "..####..",
        "..####..",
        "..####..",
        ".##..##.",
        "##....##",
        "##....##",
        ".##..##.",
    ],
    # A crate filled to the brim: the rim is solid, then a dark gap, then a solid block of
    # contents - which is the one thing this machine is about. It has to read at sixteen pixels
    # next to the Transfer Hopper's two arrows and the Checker Hopper's cross, so the middle is
    # filled rather than outlined.
    "brim": [
        "########",
        "#oooooo#",
        "#o####o#",
        "#o####o#",
        "#o####o#",
        "#o####o#",
        "#oooooo#",
        "########",
    ],
}


def draw_icon(accent, glyph):
    """One 16x16 badge: the shared casing, then the glyph centred on it."""
    img = Image.new("RGBA", (16, 16), CLEAR)
    d = ImageDraw.Draw(img)
    # The casing: rim, body, and a lit top edge inside it.
    d.rectangle([1, 1, 14, 14], fill=BODY, outline=RIM)
    d.line([(2, 2), (13, 2)], fill=BODY_TOP)
    d.line([(2, 3), (13, 3)], fill=BODY_TOP)

    dark = tuple(min(255, int(c * 0.45)) for c in accent[:3]) + (255,)
    rows = GLYPHS[glyph]
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch == ".":
                continue
            d.point((4 + x, 4 + y), fill=accent if ch == "#" else dark)
    return img


# name -> (accent colour, glyph, model name). The model name is what the item definition and the
# Java model code point at, so it is the third column and every row has one.
MACHINES = [
    ("auto_sell_hopper", (255, 200, 60, 255), "coin"),
    ("upwards_hopper", (120, 200, 255, 255), "up"),
    ("elevator", (190, 205, 220, 255), "updown"),
    ("token_redeemer", (255, 172, 48, 255), "ring"),
    ("spawner_infuser", (190, 120, 255, 255), "star"),
    ("item_forge", (255, 140, 60, 255), "hammer"),
    ("chunk_anchor", (80, 220, 200, 255), "link"),
    ("repair_station", (120, 230, 120, 255), "wrench"),
    ("item_sorter", (90, 220, 230, 255), "bars"),
    ("super_hopper", (110, 255, 230, 255), "funnel"),
    ("transfer_hopper", (255, 140, 220, 255), "two_arrows"),
    ("overflow_hopper", (255, 108, 92, 255), "brim"),
    ("checker_hopper", (205, 205, 215, 255), "cross"),
    ("two_way_splitter", (170, 140, 255, 255), "split"),
    ("super_smelter", (255, 120, 50, 255), "flame"),
    ("portable_furnace", (255, 158, 74, 255), "flame"),
    ("portable_campfire", (235, 150, 80, 255), "campfire"),
    ("auto_planter", (130, 220, 110, 255), "sprout"),
    ("auto_harvester", (235, 205, 110, 255), "wheat"),
    ("irrigation_sprinkler", (110, 180, 255, 255), "drops"),
]

# The vanilla item each machine is built on, and the custom_model_data the Java side hands out.
# Both live here because the item definition file is keyed by the base: a base with several
# machines gets one file with several entries, and the two halves have to agree.
BASE_AND_CODE = {
    "auto_sell_hopper": ("hopper", 4550340),
    "upwards_hopper": ("hopper", 4550341),
    "elevator": ("iron_block", 4550342),
    "token_redeemer": ("gold_block", 4550343),
    "spawner_infuser": ("crafting_table", 4550344),
    "item_forge": ("smithing_table", 4550345),
    "chunk_anchor": ("lodestone", 4550346),
    "repair_station": ("grindstone", 4550347),
    "item_sorter": ("hopper", 4550348),
    "super_hopper": ("hopper", 4550349),
    "transfer_hopper": ("hopper", 4550350),
    "overflow_hopper": ("hopper", 4550359),
    "checker_hopper": ("hopper", 4550351),
    "two_way_splitter": ("hopper", 4550352),
    "super_smelter": ("furnace", 4550353),
    "portable_furnace": ("blast_furnace", 4550354),
    "portable_campfire": ("smoker", 4550355),
    "auto_planter": ("composter", 4550356),
    "auto_harvester": ("observer", 4550357),
    "irrigation_sprinkler": ("cauldron", 4550358),
}


# The model vanilla's own item definition for each base item is, i.e. what a *plain* hopper or
# furnace draws as. This is not the same thing as `minecraft:item/<base>`: most block items are
# `minecraft:block/<base>`, and a definition that points at a model which does not exist renders as
# the missing-texture checkerboard.
#
# This table exists because the first version of this script guessed `minecraft:item/<base>` for all
# of them and shipped eleven broken fallbacks - every ordinary furnace, iron block, gold block,
# crafting table, smithing table, lodestone, grindstone, blast furnace, smoker, composter and
# observer rendered as the missing-texture checkerboard, and so did every GUI icon built on one
# (starting with the Super Smelter's own tier window, whose header is a furnace). The values below
# are read off the vanilla client jar's `assets/minecraft/items/<base>.json`, and
# `verify_vanilla_fallbacks` re-reads them there on every run so this cannot drift again.
VANILLA_FALLBACK = {
    "hopper": "minecraft:item/hopper",
    "furnace": "minecraft:block/furnace",
    "iron_block": "minecraft:block/iron_block",
    "gold_block": "minecraft:block/gold_block",
    "crafting_table": "minecraft:block/crafting_table",
    "smithing_table": "minecraft:block/smithing_table",
    "lodestone": "minecraft:block/lodestone",
    "grindstone": "minecraft:block/grindstone",
    "blast_furnace": "minecraft:block/blast_furnace",
    "smoker": "minecraft:block/smoker",
    "composter": "minecraft:block/composter",
    "observer": "minecraft:block/observer",
    "cauldron": "minecraft:item/cauldron",
}

# Loom's cache, where the vanilla client jar (which IS a resource pack) lives. Used only to check
# the table above against the real thing; a run without it still writes the pack.
CLIENT_JAR_CANDIDATES = ["minecraft-client.jar", "minecraft-client-only.jar", "minecraft-merged.jar"]


def vanilla_item_definitions():
    """The vanilla jar's own item definitions, or None when the jar cannot be found."""
    import os
    import zipfile

    cache = pathlib.Path.home() / ".gradle" / "caches" / "fabric-loom" / "26.2"
    for name in CLIENT_JAR_CANDIDATES:
        jar = cache / name
        if not jar.exists():
            continue
        with zipfile.ZipFile(jar) as z:
            found = {}
            for entry in z.namelist():
                if entry.startswith("assets/minecraft/items/") and entry.endswith(".json"):
                    found[pathlib.PurePosixPath(entry).stem] = json.loads(z.read(entry))
            return found
    return None


def verify_vanilla_fallbacks():
    """Refuses to write a fallback that disagrees with vanilla's own definition for that item."""
    vanilla = vanilla_item_definitions()
    if vanilla is None:
        print("note: no vanilla client jar in the loom cache - the fallback table was not re-checked")
        return
    for base, expected in VANILLA_FALLBACK.items():
        real = vanilla.get(base, {}).get("model", {}).get("model")
        if real != expected:
            raise SystemExit(
                f"VANILLA_FALLBACK[{base}] says {expected}, but vanilla's own definition says {real} - "
                "a fallback that points at a model which does not exist renders as the missing-texture "
                "checkerboard on every plain {base}".format(base=base)
            )


def write_json(path, payload):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2) + "\n")


def main():
    verify_vanilla_fallbacks()
    ITEMS.mkdir(parents=True, exist_ok=True)
    MODELS.mkdir(parents=True, exist_ok=True)

    for name, accent, glyph in MACHINES:
        draw_icon(accent, glyph).save(ITEMS / f"{name}.png")
        write_json(
            MODELS / f"{name}.json",
            {
                "parent": "minecraft:item/generated",
                "textures": {"layer0": f"fortuneandfavors:item/{name}"},
            },
        )

    # One item definition per base item, with an entry per machine minted on it. The fallback is
    # the vanilla model, so an ordinary hopper is still an ordinary hopper - only an item carrying
    # one of these custom_model_data numbers is diverted to a machine badge.
    #
    # The file is *merged* into rather than replaced, because a base item can already carry art
    # from somewhere else: gold_block is the Trophy shelf, and an earlier version of this script
    # overwrote it and silently un-backed three items that had nothing to do with machines. Entries
    # this script owns are rewritten in place; anything else in the file is carried through as it
    # was.
    by_base = {}
    for name, (base, code) in BASE_AND_CODE.items():
        by_base.setdefault(base, []).append((code, name))

    untouched = 0
    for base, rows in by_base.items():
        path = DEFS / f"{base}.json"
        entries = {}
        if path.exists():
            existing = json.loads(path.read_text()).get("model", {})
            if existing.get("type") != "minecraft:range_dispatch" or existing.get("property") != MODEL_PROPERTY:
                raise SystemExit(f"{path} is not a {MODEL_PROPERTY} dispatcher - refusing to rewrite it")
            for entry in existing.get("entries", []):
                entries[int(entry["threshold"])] = entry
                untouched += 1
        # The fallback is vanilla's own model for that item, always, from the verified table - it is
        # never carried over from the file, because carrying it over is how a wrong one survives a
        # fix. See VANILLA_FALLBACK for what a wrong fallback looks like in game.
        fallback = {"type": "minecraft:model", "model": VANILLA_FALLBACK[base]}
        for code, name in rows:
            entries[int(code)] = {
                "threshold": float(code),
                "model": {"type": "minecraft:model", "model": f"fortuneandfavors:item/{name}"},
            }
        write_json(
            path,
            {
                "model": {
                    "type": "minecraft:range_dispatch",
                    "property": MODEL_PROPERTY,
                    "entries": [entries[code] for code in sorted(entries)],
                    "fallback": fallback,
                }
            },
        )

    print(
        f"wrote {len(MACHINES)} machine icon(s), {len(MACHINES)} model(s), "
        f"{len(by_base)} item definition(s) ({untouched} pre-existing entr(y/ies) carried through)"
    )


if __name__ == "__main__":
    main()
