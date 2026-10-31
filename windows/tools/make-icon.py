"""Builds the app icon from the rainbow-glass cloud, app/src/main/resources/dream_mark.png:
  app/icons/dream_os.ico                  the installer, Dream OS.exe and its shortcuts
  app/src/main/resources/dream_icon.png   the window and taskbar icon (256 px square), for Window(icon = ...)

The mark is wider than tall: it is trimmed to its visible pixels, centered on a transparent square, and each
size is resized on its own from the full-size art (better small sizes than letting the ICO writer shrink one image).
Run again only when the mark changes:  py -3 tools/make-icon.py
"""
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/resources/dream_mark.png"
OUT = ROOT / "app/icons/dream_os.ico"
PNG = ROOT / "app/src/main/resources/dream_icon.png"
SIZES = [16, 20, 24, 32, 40, 48, 64, 96, 128, 256]
MARGIN = 0.04  # share of the square left clear on each side, so the cloud doesn't touch the icon's edge


def square(art: Image.Image) -> Image.Image:
    art = art.crop(art.getchannel("A").getbbox())
    side = round(max(art.size) / (1 - 2 * MARGIN))
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.paste(art, ((side - art.width) // 2, (side - art.height) // 2), art)
    return canvas


def main() -> None:
    big = square(Image.open(SRC).convert("RGBA"))
    frames = [big.resize((s, s), Image.Resampling.LANCZOS) for s in SIZES]
    OUT.parent.mkdir(parents=True, exist_ok=True)
    frames[-1].save(OUT, format="ICO", sizes=[(s, s) for s in SIZES], append_images=frames[:-1])
    frames[-1].save(PNG, format="PNG", optimize=True)
    print(f"{PNG.relative_to(ROOT)}: 256 px, {PNG.stat().st_size // 1024} KB")
    print(f"{OUT.relative_to(ROOT)}: {', '.join(map(str, SIZES))} px, {OUT.stat().st_size // 1024} KB")


if __name__ == "__main__":
    main()
