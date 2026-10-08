# Bedrock (Geyser) setup

A Bedrock client connected through Geyser sees the **vanilla** item and hears **no** mod music
unless two separate things are installed. They are separate on purpose: the pack is client art and
audio, and the mappings are what Geyser needs to *send* a custom item at all. Installing one and not
the other is the common failure, and it looks exactly like the pack not working.

Build all three files with:

```bash
python3 tools/build_resourcepack.py
```

It writes:

| File | Where it goes |
| --- | --- |
| `fortuneandfavors-bedrock.mcpack` | Bedrock clients - import it, or drop it in Geyser's `packs/` |
| `fortuneandfavors-geyser-mappings.json` | Geyser's `custom_mappings/` folder |
| `fortuneandfavors-resourcepack.zip` | Java clients (both halves in one archive, for a human download) |

## 1. Give the client the Bedrock pack

Bedrock never receives the Java server's resource pack through Geyser. The pack has to arrive one of
two ways:

- **Server-side:** copy `fortuneandfavors-bedrock.mcpack` into Geyser's `packs/` folder and restart
  Geyser. Every Bedrock player is then handed it on join. This is the one to use on a server.
- **Client-side:** import the `.mcpack` on the device (tap it, or place it in the client's
  `resource_packs` folder) and make sure it is **activated** in the pack list, not merely listed.

If the pack is present but not activated, nothing in it takes effect - custom item art stays vanilla
and every boss track is silent.

## 2. Give Geyser the custom item mappings

Copy `fortuneandfavors-geyser-mappings.json` into Geyser's `custom_mappings/` folder and restart
Geyser. The folder is created the first time Geyser starts; the mappings are read once, at boot, so
adding the file to a running Geyser changes nothing until it restarts.

Without this file the pack still loads and the music still plays, but each custom item renders as the
vanilla item it is built on (a `paper`-based contract looks like paper).

## What each half covers

- **Custom item art** needs **both** the pack (the icon, via `textures/item_texture.json`) and the
  mappings (Geyser sets the item's `minecraft:icon` component from the shorthand in that file).
- **Music** needs only the pack. Geyser translates the mod's boss tracks to the vanilla `record.*`
  sound names it knows how to send, and the Bedrock half of the pack redefines those names to point
  at our files. See `BedrockMusic` and `tools/build_resourcepack.py` - `ffAuditSources` fails the
  build if the two ever disagree.

## Checking it

If the art is still vanilla after both steps, the usual causes, in order, are: the mappings file was
added while Geyser was running (restart it); the pack is listed on the client but not activated; or
the client is older than the pack's `min_engine_version` in `tools/build_resourcepack.py`.
