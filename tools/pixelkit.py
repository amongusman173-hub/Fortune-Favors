"""Small helpers shared by the item-art generators.

The older generators draw with PIL primitives (rectangles, ellipses, polygons), which is
fine at 64px and unreliable at 16: an ellipse four pixels wide comes out as whatever PIL's
rasteriser decides, and an outline drawn round a two-pixel rectangle leaves no fill. The
art drawn through this module is placed pixel by pixel instead, from either:

* a character grid with a palette (``grid``), for anything with a hand-placed silhouette, or
* a predicate over coordinates (``fill_where``), for shapes that are easier to describe than
  to draw - a diagonal haft, a ring, a crescent.

and then finished with ``outline``, which wraps every shape in a one-pixel border made from
a darkened copy of the colour it borders. That is the selective outline vanilla item art
uses: a gold edge gets a dark-brown rim, a blue one a navy rim, never a flat black line.

Light always comes from the upper left, as in vanilla.
"""
from PIL import Image

CLEAR = (0, 0, 0, 0)


def rgba(hex_or_tuple, a=255):
    if isinstance(hex_or_tuple, tuple):
        return hex_or_tuple if len(hex_or_tuple) == 4 else (*hex_or_tuple, a)
    h = hex_or_tuple.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def shade(colour, factor):
    """Darken (factor < 1) or lighten (factor > 1) towards white, keeping alpha."""
    r, g, b, a = colour
    if factor <= 1.0:
        return (int(r * factor), int(g * factor), int(b * factor), a)
    t = factor - 1.0
    return (int(r + (255 - r) * t), int(g + (255 - g) * t), int(b + (255 - b) * t), a)


def ramp(base):
    """Four tones from one base colour: deep, shadow, base, light."""
    c = rgba(base)
    return shade(c, 0.45), shade(c, 0.72), c, shade(c, 1.38)


class Canvas:
    def __init__(self, size=16):
        self.size = size
        self.px = {}

    def get(self, x, y):
        return self.px.get((x, y))

    def set(self, x, y, colour):
        if 0 <= x < self.size and 0 <= y < self.size and colour is not None:
            self.px[(x, y)] = rgba(colour)

    def clear(self, x, y):
        self.px.pop((x, y), None)

    def filled(self, x, y):
        return (x, y) in self.px

    def grid(self, rows, palette, ox=0, oy=0):
        """Paint a character grid. '.' and ' ' are transparent and leave what is there."""
        for y, row in enumerate(rows):
            if ox == 0 and len(row) != self.size:
                raise ValueError(f"grid row {y} is {len(row)} wide, not {self.size}: {row!r}")
            for x, ch in enumerate(row):
                if ch in ". ":
                    continue
                if ch == "_":
                    self.clear(ox + x, oy + y)
                    continue
                self.set(ox + x, oy + y, palette[ch])

    def fill_where(self, predicate, colour_fn):
        """Set every pixel where predicate(x, y) holds, coloured by colour_fn(x, y)."""
        for y in range(self.size):
            for x in range(self.size):
                if predicate(x, y):
                    c = colour_fn(x, y)
                    if c is not None:
                        self.set(x, y, c)

    def outline(self, factor=0.38, diagonal=False, skip=()):
        """Wrap everything in a border made from a darkened copy of what it borders.

        Each empty pixel next to a filled one takes the darkest neighbour, darkened by
        ``factor``. ``skip`` lists colours that do not earn an outline (glows, sparks).
        """
        skip = {rgba(c) for c in skip}
        add = {}
        offsets = [(1, 0), (-1, 0), (0, 1), (0, -1)]
        if diagonal:
            offsets += [(1, 1), (-1, 1), (1, -1), (-1, -1)]
        for y in range(self.size):
            for x in range(self.size):
                if self.filled(x, y):
                    continue
                best = None
                for dx, dy in offsets:
                    c = self.get(x + dx, y + dy)
                    if c is None or c in skip:
                        continue
                    if best is None or sum(c[:3]) < sum(best[:3]):
                        best = c
                if best is not None:
                    add[(x, y)] = shade(best, factor)
        self.px.update(add)

    def image(self):
        img = Image.new("RGBA", (self.size, self.size), CLEAR)
        for (x, y), c in self.px.items():
            img.putpixel((x, y), c)
        return img


def diag(x, y):
    """Coordinates along and across the bottom-left to top-right diagonal.

    ``s = x + y`` runs across the diagonal (small = upper left), ``t = x - y`` runs along it
    (large = upper right). A haft from the bottom-left corner to the top-right is a band of
    constant ``s``.
    """
    return x + y, x - y
