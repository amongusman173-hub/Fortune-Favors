#!/usr/bin/env python3
"""Builds `fortuneandfavors-resourcepack.zip` - one pack that Java and Bedrock both load.

One archive, two layouts
------------------------
A Java pack is `pack.mcmeta` plus `assets/<namespace>/`; a Bedrock pack is `manifest.json` plus
`textures/` and `sounds/`. Neither loader looks at the other's files, so a single zip can be both
and each side reads the half it understands. That is what this script writes: the pack a player
downloads works whether they drop it in `.minecraft/resourcepacks`, in Bedrock's `resource_packs`
(a `.zip` there is a pack; rename it `.mcpack` if you would rather import it by tapping), or hand it
to Geyser.

The Java half is `resourcepack/`, zipped as-is - `assets/`, `pack.png`, and a `pack.mcmeta` written
fresh here, because since 1.21.9 the pack format lives in that file as a `min_format`/`max_format`
range and a pack without it is rejected outright, silently, with the client keeping the vanilla
models.

The Bedrock half is generated from that same folder, so a new item cannot appear in one half and
not the other. It is three things:

  1. `textures/` and `textures/item_texture.json` - Bedrock has no `custom_model_data`, so the art
     is reachable only through an icon atlas, and Geyser looks an icon up by the shorthand in it.
  2. `sounds/` and `sounds/sound_definitions.json` - the four tracks, under the mod's own names.
  3. The **aliases** below, which are the part that makes the music actually play (see below).

The Geyser half
---------------
`fortuneandfavors-geyser-mappings.json`, written beside the zip, tells Geyser that a Java `paper`
carrying custom model data 4550273 is meant to look like `fortuneandfavors.paper_royal_contract`
rather than a sheet of paper. Drop it in the Geyser plugin's `custom_mappings/` folder. Without it
the pack installs and every custom item still renders as the vanilla item it is built on.

Two downloads, because they are two different installs
------------------------------------------------------
`fortuneandfavors-resourcepack.zip` is one archive that both editions load, and is what a person
hands to a Java client or keeps as the single download. `fortuneandfavors-bedrock.mcpack` is the
Bedrock half alone - no `assets/`, no `pack.mcmeta` - and is the file Bedrock imports and the file
Geyser's `packs/` folder is given. A Bedrock client that never receives the pack sees the vanilla
item and hears no music whatever the mappings say, so the pack has to be delivered to it: either
enabled on the client itself, or placed in Geyser's `packs/` folder on the server. Geyser does not
forward a Java server resource pack to a Bedrock client.

How the music reaches a Bedrock client
--------------------------------------
Geyser translates *vanilla* sound events into Bedrock names and drops the ones it has never heard
of, so `fortuneandfavors:withered_loop` - the id the mod's own fallback sends - arrives as nothing.
The way through is to hand a Bedrock client a vanilla id Geyser does know, and then redefine that
vanilla sound here so it plays our file instead of a disc. `BEDROCK_ALIASES` is that table, and
`BedrockMusic` on the Java side is the half that sends it; `ffAuditSources` fails the build if the
two ever disagree, because a rename on one side alone is silent music.

The cost: a Bedrock client running this pack hears our tracks where those four discs would be. They
are discs a server using this pack should not be handing out. The failure mode is the quiet one - an
alias Geyser does not translate leaves the player in the same silence they were in before - so
nothing here can make a Bedrock soundtrack worse than it already is.

Run:  python3 tools/build_resourcepack.py
"""

import json
import pathlib
import re
import uuid
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
PACK_DIR = ROOT / "resourcepack"
ASSETS = PACK_DIR / "assets"
OUT = ROOT / "fortuneandfavors-resourcepack.zip"
# The Bedrock half on its own. This is the file that goes into a Bedrock client's
# `resource_packs` or into Geyser's `packs/` folder; the combined zip above is for Java clients and
# for a human who wants one download. A Bedrock pack loader given the combined archive has to walk
# past a Java half it will never use, and Geyser's own pack folder expects nothing but the pack.
OUT_BEDROCK = ROOT / "fortuneandfavors-bedrock.mcpack"
GEYSER_MAPPINGS = ROOT / "fortuneandfavors-geyser-mappings.json"

# 26.2's resource pack format. See minecraft.wiki/w/Pack_format.
RESOURCE_FORMAT = 88

# Identity for the Bedrock half. Derived from fixed names rather than drawn at random, so a rebuild
# produces the same pack: Bedrock keys a pack off its UUID, and a UUID that moved every build would
# make every client re-download for nothing. The version comes from gradle.properties, and moving
# *that* is what should ask a client to update.
PACK_NAME = "Fortune & Favors"
PACK_DESCRIPTION = "Fortune & Favors - custom item art and boss music"
UUID_NAMESPACE = uuid.UUID("6f2b9d1e-6f0a-4c3f-9a5e-1d2c3b4a5e6f")

# The Bedrock version this pack declares as its floor. The one number to raise if you want older
# clients to refuse the pack outright, and to lower if you want to serve them - Geyser's custom-item
# components are why it is not lower.
MIN_ENGINE_VERSION = [1, 21, 20]

# Java's own item definitions dispatch on this property, and Geyser's *legacy* definition type is
# the one that matches a raw custom model data number. Both have to hold for a mapping to be read.
MODEL_PROPERTY = "minecraft:custom_model_data"
MODEL_NS = "fortuneandfavors:item/"

# Bedrock sound name -> the track in this pack that replaces it.
#
# Must match BedrockMusic.standIns() in src/main/java/com/fortuneandfavors/economy/BedrockMusic.java.
# That table holds the Java event each cut is played as; this one redefines the name it arrives
# under; ffAuditSources compares the two so a cut cannot be renamed on one side alone.
BEDROCK_ALIASES = {
    "record.13": "withered_intro",
    "record.cat": "withered_loop",
    "record.blocks": "withered_death",
    "record.chirp": "ender_dragon_theme",
    "record.relic": "aria_math_epic",
}

# `pack.mcmeta` is rewritten above and `pack.png` is the Java pack's own icon: both belong in the
# archive (Java needs the first or it keeps the vanilla models, and reads the second as the pack's
# picture), so neither is skipped here.
SKIP_NAMES = {".DS_Store"}
SKIP_SUFFIXES = (".pyc",)
SKIP_DIRS = {"__pycache__"}

# iCloud, when it cannot merge two versions of a file, leaves both and renames the loser -
# `bow.json`, `bow 2.json`, `bow 3.json`. That is not hypothetical here: this working copy lives in
# iCloud Drive, and the project's own runtime logs scrubbing dozens of them out of its data folder.
#
# They have to be skipped rather than tolerated, because the failure they cause is not the one they
# look like. A conflict copy of an *item definition* is read as a second definition of the same
# item, so `custom_items()` returns the same custom model data twice and `identifiers()` refuses to
# write - correctly, two Bedrock items may not share a name - and the pack simply cannot be built on
# a machine that is mid-sync, with an error about identifiers rather than about the copy. Skipping
# them keeps the build reproducible; `scrubICloudConflicts` in build.gradle deletes them for the
# Gradle half of the build, and this is the same rule on this side of it.
ICLOUD_CONFLICT = re.compile(r".* \d+(\..+)?$")


def conflicted(name):
    """True for an iCloud "<name> <n>.<ext>" conflict copy - see ICLOUD_CONFLICT above."""
    return bool(ICLOUD_CONFLICT.match(name))


def mod_version():
    """`1.8` -> `[1, 8, 0]`, read from the one place the version is written down."""
    text = (ROOT / "gradle.properties").read_text()
    match = re.search(r"^mod_version\s*=\s*(\S+)", text, re.M)
    if not match:
        raise SystemExit("gradle.properties has no mod_version - the pack has no version")
    parts = [int(p) for p in re.findall(r"\d+", match.group(1))][:3]
    while len(parts) < 3:
        parts.append(0)
    return parts


def write_json(path, payload):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2) + "\n")


def leaf_models(model):
    """Every `minecraft:model` a model tree ends at.

    Not every custom item is a single model: an entry can be a dispatcher of its own -
    `minecraft:select` on an item property, `minecraft:condition` on damage - and the leaf that
    actually draws is one or more levels in. Walking the tree instead of reading `entry["model"]`
    keeps those items from silently getting no icon on Bedrock.
    """
    found = []
    if isinstance(model, dict):
        if model.get("type") == "minecraft:model" and isinstance(model.get("model"), str):
            found.append(model["model"])
        for value in model.values():
            found.extend(leaf_models(value))
    elif isinstance(model, list):
        for value in model:
            found.extend(leaf_models(value))
    return found


def custom_items():
    """Every (base item, custom model data, model name) this pack ships art for."""
    rows = []
    for file in sorted((ASSETS / "minecraft" / "items").glob("*.json")):
        if conflicted(file.name):
            continue
        root = json.loads(file.read_text()).get("model", {})
        if root.get("type") != "minecraft:range_dispatch" or root.get("property") != MODEL_PROPERTY:
            raise SystemExit(
                f"{file.name} does not dispatch on {MODEL_PROPERTY} - this script reads the custom "
                "item list out of those definitions, so it would miss every item in it"
            )
        for entry in root.get("entries", []):
            for model in leaf_models(entry.get("model", {})):
                rows.append((file.stem, entry["threshold"], model))
    return rows


def texture_of(model_name):
    """The texture a model draws, as a `textures/<subpath>/<name>` reference inside the pack."""
    short = model_name[len(MODEL_NS):] if model_name.startswith(MODEL_NS) else model_name
    path = ASSETS / "fortuneandfavors" / "models" / "item" / f"{short}.json"
    if not path.exists():
        raise SystemExit(f"no model file for {model_name} ({path})")
    textures = json.loads(path.read_text()).get("textures", {})
    wanted = textures.get("layer0")
    if wanted is None:
        for value in textures.values():
            if isinstance(value, str) and ":" in value:
                wanted = value
                break
    if not isinstance(wanted, str) or ":" not in wanted:
        raise SystemExit(f"{path.name} names no texture with a namespace, so it has no icon")
    _, ref = wanted.split(":", 1)
    subpath, _, name = ref.rpartition("/")
    source = ASSETS / "fortuneandfavors" / "textures" / subpath / f"{name}.png"
    if not source.exists():
        raise SystemExit(f"{path.name} draws {wanted}, but {source} does not exist")
    return f"{subpath}/{name}" if subpath else name


def identifiers(rows):
    """A unique Bedrock identifier per custom item, scoped by base item.

    Scoped by base rather than by model alone, because the same model can legitimately be used on
    two different vanilla bases and a Bedrock identifier may not be shared between two definitions.
    """
    seen = {}
    for base, code, model in rows:
        seen.setdefault((base, model), []).append(code)
    out = []
    for base, code, model in rows:
        short = model[len(MODEL_NS):] if model.startswith(MODEL_NS) else model
        name = f"{base}_{short}"
        if len(seen[(base, model)]) > 1:
            # One model reached by more than one code on the same base: both definitions exist and
            # only one may own the name, so the identifier says which.
            name = f"{name}_{int(code)}"
        out.append((base, code, model, f"fortuneandfavors:{name}"))
    if len({r[3] for r in out}) != len(out):
        raise SystemExit("two custom items would share a Bedrock identifier - refusing to write")
    return out


def shorthand(identifier):
    """Geyser looks an icon up by the identifier with the two characters Bedrock cannot have in a
    name replaced - the rule Geyser documents, and the only transformation applied to it."""
    return identifier.replace(":", ".").replace("/", "_")


def java_sounds():
    """The pack's sound events, from the Java `sounds.json` - the one place they are declared."""
    java = json.loads((ASSETS / "fortuneandfavors" / "sounds.json").read_text())
    out = {}
    for event, entry in sorted(java.items()):
        sounds = entry.get("sounds", [])
        if not sounds:
            continue
        name = sounds[0]["name"]
        out[event] = name.split(":", 1)[1] if ":" in name else name
    return out


def bed_nodes():
    """Every file the Bedrock half needs, as `path -> bytes`, plus the report it checks."""
    version = mod_version()
    rows = identifiers(custom_items())

    header_uuid = uuid.uuid5(UUID_NAMESPACE, "fortuneandfavors/resourcepack/header")
    module_uuid = uuid.uuid5(UUID_NAMESPACE, "fortuneandfavors/resourcepack/module")
    nodes = {
        "manifest.json": (json.dumps({
            "format_version": 2,
            "header": {
                "name": PACK_NAME,
                "description": PACK_DESCRIPTION,
                "uuid": str(header_uuid),
                "version": version,
                "min_engine_version": MIN_ENGINE_VERSION,
            },
            "modules": [{
                "type": "resources",
                "description": PACK_DESCRIPTION,
                "uuid": str(module_uuid),
                "version": version,
            }],
        }, indent=2) + "\n").encode(),
    }

    # -- textures, and the atlas that names them ---------------------------------------
    texture_data = {}
    for base, code, model, identifier in rows:
        relative = texture_of(model)
        nodes[f"textures/{relative}.png"] = (
            ASSETS / "fortuneandfavors" / "textures" / f"{relative}.png"
        ).read_bytes()
        texture_data[shorthand(identifier)] = {"textures": f"textures/{relative}"}
    nodes["textures/item_texture.json"] = (json.dumps({
        "resource_pack_name": "fortuneandfavors",
        "texture_name": "atlas.items",
        "texture_data": texture_data,
    }, indent=2) + "\n").encode()

    # -- the music: the mod's own names, plus the aliases Geyser can actually send -------------
    events = java_sounds()
    definitions = {}
    for event, name in events.items():
        nodes[f"sounds/{name}.ogg"] = (ASSETS / "fortuneandfavors" / "sounds" / f"{name}.ogg").read_bytes()
        definitions[f"fortuneandfavors.{event}"] = {
            "category": "music",
            "sounds": [{"name": f"sounds/{name}", "stream": True}],
        }
    for bedrock_name, track in sorted(BEDROCK_ALIASES.items()):
        if track not in events:
            raise SystemExit(
                f"the alias {bedrock_name} points at {track}, which is not a sound this pack declares"
            )
        definitions[bedrock_name] = {
            "category": "record",
            "sounds": [{"name": f"sounds/{track}", "stream": True}],
        }
    nodes["sounds/sound_definitions.json"] = (json.dumps({
        "format_version": "1.14.0",
        "sound_definitions": definitions,
    }, indent=2) + "\n").encode()

    if not (PACK_DIR / "pack.png").exists():
        raise SystemExit(f"the pack has no icon: {PACK_DIR / 'pack.png'}")
    nodes["pack_icon.png"] = (PACK_DIR / "pack.png").read_bytes()

    # -- the Geyser half, written beside the zip rather than inside it ------------------------
    mappings = {"format_version": 2, "items": {}}
    for base, code, model, identifier in rows:
        mappings["items"].setdefault(f"minecraft:{base}", []).append({
            "type": "legacy",
            # An integer literal, not the Java threshold's float: Geyser's own example writes `42`,
            # and while the docs call the value a float, a JSON `42` parses into either an int or a
            # float field whereas `42.0` is refused by every int reader. One shape is accepted by
            # both, and every value this mod hands out is a whole number.
            "custom_model_data": int(code),
            "bedrock_identifier": identifier,
            "bedrock_options": {"icon": shorthand(identifier), "creative_category": "items"},
        })
    write_json(GEYSER_MAPPINGS, mappings)

    return nodes, {
        "items": len(rows),
        "bases": len(mappings["items"]),
        "textures": sum(1 for k in nodes if k.startswith("textures/") and k.endswith(".png")),
        "sounds": len(events),
        "aliases": len(BEDROCK_ALIASES),
    }


def main():
    write_json(PACK_DIR / "pack.mcmeta", {
        "pack": {
            "description": PACK_DESCRIPTION,
            "min_format": [RESOURCE_FORMAT, 0],
            "max_format": [RESOURCE_FORMAT, 0],
        }
    })

    # The Java half is the folder, zipped as it stands.
    java_files = sorted(
        p for p in PACK_DIR.rglob("*")
        if p.is_file()
        and p.name not in SKIP_NAMES
        and not p.name.startswith(".")
        and not conflicted(p.name)
        and p.suffix not in SKIP_SUFFIXES
        and not SKIP_DIRS & set(p.parts)
    )
    nodes, report = bed_nodes()

    # And the Bedrock half goes in beside it. Nothing collides: the Java half lives under
    # `assets/` and the Bedrock one under `textures/`, `sounds/` and two root files, and each
    # loader ignores the other's. A fixed timestamp on every entry keeps the archive
    # reproducible, so a rebuild of unchanged inputs is reviewable as a diff instead of churn.
    stamp = (1980, 1, 1, 0, 0, 0)
    OUT.unlink(missing_ok=True)
    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in java_files:
            info = zipfile.ZipInfo(path.relative_to(PACK_DIR).as_posix(), date_time=stamp)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, path.read_bytes())
        for name, payload in sorted(nodes.items()):
            info = zipfile.ZipInfo(name, date_time=stamp)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, payload)

    # Every icon has to land on a file that is actually in the archive, because a Bedrock texture
    # that resolves to nothing is a checkerboard rather than an error.
    for shorthand_name, entry in json.loads(nodes["textures/item_texture.json"])["texture_data"].items():
        if entry["textures"] + ".png" not in nodes:
            raise SystemExit(f"icon {shorthand_name} points at {entry['textures']}, which is not in the pack")

    # A pack that is meant to be loaded twice over is worth one last look at the two files that do
    # the loading: without `pack.mcmeta` Java silently ignores the whole thing, and without
    # `manifest.json` Bedrock does.
    with zipfile.ZipFile(OUT) as archive:
        names = set(archive.namelist())
        for required in ("pack.mcmeta", "manifest.json", "pack.png", "pack_icon.png"):
            if required not in names:
                raise SystemExit(f"{required} is not in the archive - one of the two editions would load nothing")

    # A Bedrock-only archive, beside the combined one. Same bytes, no Java half - this is the file a
    # Bedrock client imports and the file Geyser's `packs/` folder is given.
    OUT_BEDROCK.unlink(missing_ok=True)
    with zipfile.ZipFile(OUT_BEDROCK, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, payload in sorted(nodes.items()):
            info = zipfile.ZipInfo(name, date_time=stamp)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, payload)

    print(
        f"wrote {OUT.relative_to(ROOT)} ({OUT.stat().st_size / 1048576:.2f} MB) - "
        f"{len(java_files) + len(nodes)} entries: the Java pack, and a Bedrock pack carrying "
        f"{report['items']} custom item(s) on {report['bases']} base item(s), "
        f"{report['textures']} texture(s), {report['sounds']} sound(s) and {report['aliases']} alias(es)"
    )
    print(
        f"wrote {OUT_BEDROCK.relative_to(ROOT)} ({OUT_BEDROCK.stat().st_size / 1048576:.2f} MB) - "
        "the Bedrock-only pack: import it in Bedrock, or drop it in Geyser's packs/ folder"
    )
    print(f"wrote {GEYSER_MAPPINGS.relative_to(ROOT)} - drop it in Geyser's custom_mappings/")


if __name__ == "__main__":
    main()
