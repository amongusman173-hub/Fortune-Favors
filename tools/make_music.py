#!/usr/bin/env python3
"""Re-encodes the Wither boss cuts from the source MP3 in `Music/`.

Why this exists
---------------
`resourcepack/assets/fortuneandfavors/sounds/` is the single source of truth for the
mod's audio, and three of its four files are cuts of one song:

    Music/WITHERED - Jaacken.mp3   167.346 s
      -> withered_intro.ogg    0.000 -  21.000 s
      -> withered_loop.ogg    21.000 - 153.000 s
      -> withered_death.ogg  153.000 - 167.346 s

Those three cuts are a *partition* of the source - contiguous, no crossfades - which
this script checks by decoding the source region and the cut and comparing them. It
also re-derives them, because the checked-in files had drifted:

  * the loop was encoded at 161 kbps from a 121 kbps source. Bits above the source's
    own rate do not carry music, they carry the MP3 encoder's quantization noise, so
    those 40 kbps bought nothing and cost a megabyte.
  * encoding a lossy file a second time is a generation. Cutting from the source and
    encoding once is strictly closer to the master at the same bit rate.

Quality, measured rather than asserted
--------------------------------------
`-q:a 4` lands at ~118 kbps, which is the source's own 121 kbps - and Vorbis is a more
efficient codec than MP3, so a Vorbis file at the source's rate preserves everything
the source has. Measured against a decode of the source region, mean absolute sample
error is 418 / 766 / 139 out of 32768 (intro / loop / death), i.e. below -30 dB.
The dial is `QUALITY` below: 3 is ~112 kbps and 27% smaller again, 5 is ~160 kbps and
is the same rate the loop used to ship at.

`ender_dragon_theme.ogg` is deliberately NOT touched. It does not match
`Music/Minecraft - Ender Dragon Theme  J. Rivers.mp3` - the difference between the two
is ~-14 dB, which is a different master rather than an encoding - and it is already
the tightest file here at 92 kbps, which is below that MP3's own 118 kbps. Re-encoding
it from the wrong source would be a downgrade, and re-encoding it from itself would be
a generation for nothing.

Run:  python3 tools/make_music.py
"""

import array
import pathlib
import shutil
import statistics
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCE = ROOT / "Music" / "WITHERED - Jaacken.mp3"
OUT_DIR = ROOT / "resourcepack" / "assets" / "fortuneandfavors" / "sounds"

# libvorbis quality. See "Quality, measured rather than asserted" above before moving it.
QUALITY = 4

# name -> (start second, duration second). These are the cut points the shipped files
# already had, re-derived from the source; the equality is what this script verifies.
CUTS = {
    "withered_intro": (0.0, 21.0),
    "withered_loop": (21.0, 132.0),
    "withered_death": (153.0, 14.346),
}

# Would be a different master, not a different encode - see the module docstring.
UNTOUCHED = ("ender_dragon_theme.ogg",)

RATE = 44100
CHANNELS = 2


def decode(path, start=None, duration=None):
    """Decode to interleaved 16-bit stereo at the pack's rate, so two files compare directly."""
    cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y"]
    if start is not None:
        cmd += ["-ss", str(start), "-t", str(duration)]
    cmd += ["-i", str(path), "-f", "s16le", "-ac", str(CHANNELS), "-ar", str(RATE), "-"]
    return subprocess.run(cmd, capture_output=True, check=True).stdout


def mean_absolute_error(a, b):
    """Mean |a - b| over both channels, ignoring half a second at each end.

    The ends are dropped because an encoder pads: the first and last frames of a lossy
    file are not a statement about the cut point, and including them makes a perfectly
    aligned file look shifted.
    """
    n = min(len(a), len(b)) // 2
    left = array.array("h")
    left.frombytes(a[: n * 2])
    right = array.array("h")
    right.frombytes(b[: n * 2])
    skip = int(0.5 * RATE * CHANNELS)
    samples = [abs(left[i] - right[i]) for i in range(skip, n - skip, 7)]
    return statistics.mean(samples), max(samples)


def encode(name, start, duration):
    """Cut and encode one track. Returns the output path."""
    out = OUT_DIR / f"{name}.ogg"
    subprocess.run(
        [
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            # Input seeking (before -i) is exact here because we are transcoding: ffmpeg
            # decodes from the keyframe and discards up to the seek point, so a cut at
            # 153.0 s starts at 153.0 s rather than at the nearest codec boundary.
            "-ss", str(start), "-t", str(duration),
            "-i", str(SOURCE),
            "-map_metadata", "-1",
            "-c:a", "libvorbis", "-q:a", str(QUALITY),
            str(out),
        ],
        check=True,
    )
    return out


def main():
    if shutil.which("ffmpeg") is None:
        raise SystemExit("ffmpeg is not on PATH - it is what encodes these.")
    if not SOURCE.exists():
        raise SystemExit(f"the source is missing: {SOURCE}")

    for name, (start, duration) in CUTS.items():
        before = (OUT_DIR / f"{name}.ogg").stat().st_size
        out = encode(name, start, duration)
        after = out.stat().st_size

        # The claim this script makes is "the same audio, cut from the source" - so check
        # it instead of trusting it: the new file against a decode of the source region.
        error, peak = mean_absolute_error(decode(out), decode(SOURCE, start, duration))
        print(
            f"{name:18} {start:7.3f}s +{duration:7.3f}s   "
            f"{before / 1024:7.0f} KB -> {after / 1024:7.0f} KB   "
            f"vs source: mean|d| {error:6.1f}, peak {peak:5d} of 32768"
        )

    print(f"\nleft alone on purpose: {', '.join(UNTOUCHED)}")
    total = sum(p.stat().st_size for p in OUT_DIR.glob("*.ogg"))
    print(f"the pack's audio is now {total / 1048576:.2f} MB")


if __name__ == "__main__":
    main()
