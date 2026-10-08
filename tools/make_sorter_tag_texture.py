#!/usr/bin/env python3
"""Draws the Sorter Tag's icon.

The Sorter Tag is a sign, and it has to read as one - but it must also not read
as an *ordinary* sign, because a player holds it next to the signs they use for
decoration and needs to see at a glance which one tags. So it is a wooden sign
board with a teal tag badge pinned to it: the board says "sign", the badge says
"tag".

Run:  python3 tools/make_sorter_tag_texture.py
"""
import pathlib

from PIL import Image, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parent.parent
TEX = ROOT / "resourcepack" / "assets" / "fortuneandfavors" / "textures" / "item"
MODELS = ROOT / "resourcepack" / "assets" / "fortuneandfavors" / "models" / "item"

CLEAR = (0, 0, 0, 0)
WOOD = (150, 110, 70, 255)
WOOD_DARK = (96, 66, 40, 255)
WOOD_LIGHT = (190, 150, 104, 255)
TAG = (58, 214, 196, 255)
TAG_DARK = (24, 118, 114, 255)
INK = (42, 46, 54, 255)


def main():
    img = Image.new("RGBA", (16, 16), CLEAR)
    d = ImageDraw.Draw(img)

    # The board, hung by two nails at its top corners.
    d.rectangle([2, 4, 13, 10], fill=WOOD, outline=WOOD_DARK)
    d.line([(3, 5), (12, 5)], fill=WOOD_LIGHT)
    d.line([(3, 3), (3, 4)], fill=WOOD_DARK)
    d.line([(12, 3), (12, 4)], fill=WOOD_DARK)
    d.point((3, 3), fill=WOOD_DARK)
    d.point((12, 3), fill=WOOD_DARK)

    # Two lines of writing on the board.
    d.line([(4, 6), (11, 6)], fill=INK)
    d.line([(4, 8), (9, 8)], fill=INK)

    # The tag badge pinned over the bottom-right corner: a teal label with a hole.
    d.rectangle([9, 10, 13, 14], fill=TAG, outline=TAG_DARK)
    d.point((10, 11), fill=TAG_DARK)
    d.point((11, 11), fill=TAG_DARK)

    TEX.mkdir(parents=True, exist_ok=True)
    img.save(TEX / "sorter_tag.png")

    MODELS.mkdir(parents=True, exist_ok=True)
    (MODELS / "sorter_tag.json").write_text(
        "{\n"
        '  "parent": "minecraft:item/generated",\n'
        '  "textures": {\n'
        '    "layer0": "fortuneandfavors:item/sorter_tag"\n'
        "  }\n"
        "}\n"
    )
    print("wrote sorter_tag.png + sorter_tag.json")


if __name__ == "__main__":
    main()
