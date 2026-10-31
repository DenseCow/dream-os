"""Builds the setup wizard's images from the Dream OS logo (installer/images/, used by installer/DreamOS.iss):
  wizard-<w>x<h>.png   the tall panel on the Welcome and Finish pages: the full logo (cloud and DREAM OS) on black
                       with a soft glow of its own colors under it
  small-<n>.png        the square in the top corner of the other pages: the cloud alone, transparent around it

One file per size Inno Setup 6.7 picks from at 100% to 250% display scaling (its WizardImageFile and
WizardSmallImageFile help pages list them), each resized from the full-size art, so no page shows a stretched image.
Run again only when the logo changes:  py -3 tools/make-wizard-images.py
"""
from pathlib import Path

from PIL import Image, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
LOGO = ROOT / "app/src/main/resources/dream_logo.png"
MARK = ROOT / "app/src/main/resources/dream_mark.png"
OUT = ROOT / "installer/images"

WIZARD = [(202, 386), (269, 515), (336, 643), (403, 772), (430, 824), (498, 953), (534, 1022)]
SMALL = [58, 77, 97, 116, 124, 143, 159]

BLACK = (10, 10, 11)       # the panel: Dream OS black, a step darker than the wizard's dark gray page
LOGO_WIDTH = 0.80          # share of the panel's width the logo spans
LOGO_CENTER_Y = 0.44       # a little above the middle, where the eye lands first
GLOW_BLUR = 0.10           # glow softness, as a share of the panel's width
GLOW_STRENGTH = 0.45       # glow opacity: present, never brighter than the logo itself
SMALL_MARGIN = 0.06        # clear space around the cloud in the corner square


def trimmed(path: Path) -> Image.Image:
    art = Image.open(path).convert("RGBA")
    return art.crop(art.getchannel("A").getbbox())


def wizard(logo: Image.Image, w: int, h: int) -> Image.Image:
    lw = round(w * LOGO_WIDTH)
    lh = round(logo.height * lw / logo.width)
    art = logo.resize((lw, lh), Image.Resampling.LANCZOS)
    x, y = (w - lw) // 2, round(h * LOGO_CENTER_Y - lh / 2)

    # The glow: the logo's own colors, blurred wide and laid under it. The only color on the panel is the logo's.
    glow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    glow.paste(art, (x, y), art)
    glow = glow.filter(ImageFilter.GaussianBlur(w * GLOW_BLUR))
    glow.putalpha(glow.getchannel("A").point(lambda a: round(a * GLOW_STRENGTH)))

    panel = Image.new("RGBA", (w, h), BLACK + (255,))
    panel.alpha_composite(glow)
    panel.alpha_composite(art, (x, y))
    return panel.convert("RGB")


def small(mark: Image.Image, n: int) -> Image.Image:
    inner = round(n * (1 - 2 * SMALL_MARGIN))
    scale = inner / max(mark.size)
    art = mark.resize((round(mark.width * scale), round(mark.height * scale)), Image.Resampling.LANCZOS)
    square = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    square.alpha_composite(art, ((n - art.width) // 2, (n - art.height) // 2))
    return square


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    for old in OUT.glob("*.png"):
        old.unlink()
    logo, mark = trimmed(LOGO), trimmed(MARK)
    for w, h in WIZARD:
        wizard(logo, w, h).save(OUT / f"wizard-{w}x{h}.png", optimize=True)
    for n in SMALL:
        small(mark, n).save(OUT / f"small-{n}.png", optimize=True)
    total = sum(f.stat().st_size for f in OUT.glob("*.png")) // 1024
    print(f"{OUT.relative_to(ROOT)}: {len(WIZARD)} wizard + {len(SMALL)} small images, {total} KB")


if __name__ == "__main__":
    main()
