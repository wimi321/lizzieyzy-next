#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / 'packaging' / 'icons'
RESOURCE_LOGO = ROOT / 'src' / 'main' / 'resources' / 'assets' / 'logo.png'
APP_ICON_PNG = OUT_DIR / 'app-icon-1024.png'
APP_ICON_ICO = OUT_DIR / 'app-icon.ico'
APP_ICON_ICNS = OUT_DIR / 'app-icon.icns'
ICONSET = OUT_DIR / 'app.iconset'

SIZE = 1024


def vertical_gradient(size: int, top: tuple[int, int, int], bottom: tuple[int, int, int]) -> Image.Image:
    img = Image.new('RGBA', (size, size))
    px = img.load()
    for y in range(size):
        t = y / float(size - 1)
        r = int(top[0] * (1 - t) + bottom[0] * t)
        g = int(top[1] * (1 - t) + bottom[1] * t)
        b = int(top[2] * (1 - t) + bottom[2] * t)
        for x in range(size):
            px[x, y] = (r, g, b, 255)
    return img


def add_shadow(base: Image.Image, mask: Image.Image, offset: tuple[int, int], blur: int, color: tuple[int, int, int, int]) -> None:
    shadow = Image.new('RGBA', base.size, (0, 0, 0, 0))
    layer = Image.new('RGBA', base.size, color)
    shadow.paste(layer, offset, mask)
    shadow = shadow.filter(ImageFilter.GaussianBlur(blur))
    base.alpha_composite(shadow)


def draw_stone(base: Image.Image, center: tuple[int, int], radius: int, fill: tuple[int, int, int], rim: tuple[int, int, int], highlight: tuple[int, int, int, int], shadow_alpha: int) -> None:
    cx, cy = center
    mask = Image.new('L', base.size, 0)
    md = ImageDraw.Draw(mask)
    md.ellipse((cx - radius, cy - radius, cx + radius, cy + radius), fill=255)
    add_shadow(base, mask, (0, 24), 28, (20, 16, 12, shadow_alpha))

    stone = Image.new('RGBA', base.size, (0, 0, 0, 0))
    sd = ImageDraw.Draw(stone)
    sd.ellipse((cx - radius, cy - radius, cx + radius, cy + radius), fill=fill + (255,), outline=rim + (255,), width=8)

    gloss = Image.new('RGBA', base.size, (0, 0, 0, 0))
    gd = ImageDraw.Draw(gloss)
    gd.ellipse((cx - radius + 24, cy - radius + 18, cx + radius - 70, cy - 8), fill=highlight)
    gloss = gloss.filter(ImageFilter.GaussianBlur(18))

    base.alpha_composite(stone)
    base.alpha_composite(gloss)


def render_icon() -> Image.Image:
    canvas = Image.new('RGBA', (SIZE, SIZE), (0, 0, 0, 0))
    bg = vertical_gradient(SIZE, (245, 236, 219), (224, 194, 144))

    bg_mask = Image.new('L', (SIZE, SIZE), 0)
    md = ImageDraw.Draw(bg_mask)
    md.rounded_rectangle((44, 44, SIZE - 44, SIZE - 44), radius=236, fill=255)
    add_shadow(canvas, bg_mask, (0, 24), 42, (65, 43, 22, 64))
    canvas.paste(bg, (0, 0), bg_mask)

    surface = Image.new('RGBA', (SIZE, SIZE), (0, 0, 0, 0))
    sd = ImageDraw.Draw(surface)
    board_box = (154, 154, SIZE - 154, SIZE - 154)
    sd.rounded_rectangle(board_box, radius=122, fill=(249, 237, 214, 255), outline=(72, 50, 31, 255), width=20)
    inner_box = (188, 188, SIZE - 188, SIZE - 188)
    sd.rounded_rectangle(inner_box, radius=94, fill=(247, 231, 201, 255))
    canvas.alpha_composite(surface)

    light = Image.new('RGBA', (SIZE, SIZE), (0, 0, 0, 0))
    ld = ImageDraw.Draw(light)
    ld.ellipse((140, 72, 792, 560), fill=(255, 249, 238, 90))
    light = light.filter(ImageFilter.GaussianBlur(55))
    canvas.alpha_composite(light)

    scale = 4
    ribbon_shadow_mask = Image.new('L', (SIZE * scale, SIZE * scale), 0)
    rsd = ImageDraw.Draw(ribbon_shadow_mask)
    arc_box = (8 * scale, 44 * scale, 780 * scale, 612 * scale)
    rsd.arc(arc_box, start=204, end=316, fill=255, width=150 * scale)
    ribbon_shadow_mask = ribbon_shadow_mask.resize((SIZE, SIZE), Image.LANCZOS)
    add_shadow(canvas, ribbon_shadow_mask, (0, 18), 24, (92, 41, 18, 52))

    ribbon_hi = Image.new('RGBA', (SIZE * scale, SIZE * scale), (0, 0, 0, 0))
    rd = ImageDraw.Draw(ribbon_hi)
    rd.arc(arc_box, start=204, end=316, fill=(188, 84, 46, 255), width=124 * scale)
    rd.arc(arc_box, start=204, end=316, fill=(240, 156, 108, 255), width=64 * scale)
    rd.ellipse((26 * scale, 214 * scale, 178 * scale, 366 * scale), fill=(255, 244, 233, 255))
    ribbon = ribbon_hi.resize((SIZE, SIZE), Image.LANCZOS).filter(ImageFilter.GaussianBlur(0.5))
    canvas.alpha_composite(ribbon)

    grid = Image.new('RGBA', (SIZE, SIZE), (0, 0, 0, 0))
    gd = ImageDraw.Draw(grid)
    left, top, right, bottom = 246, 246, SIZE - 246, SIZE - 246
    line = (44, 30, 20, 230)
    width = 22
    for step in (0.0, 0.33, 0.66, 1.0):
        x = int(left + (right - left) * step)
        gd.line((x, top, x, bottom), fill=line, width=width)
        y = int(top + (bottom - top) * step)
        gd.line((left, y, right, y), fill=line, width=width)
    for sx, sy in ((left + 150, top + 150), (right - 150, bottom - 150), (left + 150, bottom - 150)):
        gd.ellipse((sx - 10, sy - 10, sx + 10, sy + 10), fill=(44, 30, 20, 255))
    canvas.alpha_composite(grid)

    draw_stone(canvas, (628, 640), 116, (26, 24, 28), (66, 61, 68), (255, 255, 255, 42), 72)
    draw_stone(canvas, (558, 390), 116, (250, 248, 244), (181, 171, 162), (255, 255, 255, 160), 48)

    focus = Image.new('RGBA', (SIZE, SIZE), (0, 0, 0, 0))
    fd = ImageDraw.Draw(focus)
    fd.rounded_rectangle((178, 178, SIZE - 178, SIZE - 178), radius=102, outline=(255, 252, 246, 92), width=8)
    focus = focus.filter(ImageFilter.GaussianBlur(1.2))
    canvas.alpha_composite(focus)

    return canvas


def save_pngs(icon: Image.Image) -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    icon.save(APP_ICON_PNG)
    icon.resize((512, 512), Image.LANCZOS).save(RESOURCE_LOGO)


def save_ico(icon: Image.Image) -> None:
    sizes = [(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]
    icon.save(APP_ICON_ICO, format='ICO', sizes=sizes)


def save_icns(icon: Image.Image) -> None:
    if shutil.which('iconutil') is None:
        print('iconutil not found, keeping existing app-icon.icns')
        return

    if ICONSET.exists():
        for path in ICONSET.iterdir():
            path.unlink()
    else:
        ICONSET.mkdir(parents=True)

    sizes = {
        'icon_16x16.png': 16,
        'icon_16x16@2x.png': 32,
        'icon_32x32.png': 32,
        'icon_32x32@2x.png': 64,
        'icon_128x128.png': 128,
        'icon_128x128@2x.png': 256,
        'icon_256x256.png': 256,
        'icon_256x256@2x.png': 512,
        'icon_512x512.png': 512,
        'icon_512x512@2x.png': 1024,
    }
    for name, size in sizes.items():
        icon.resize((size, size), Image.LANCZOS).save(ICONSET / name)
    subprocess.run(['iconutil', '-c', 'icns', str(ICONSET), '-o', str(APP_ICON_ICNS)], check=True)
    for path in ICONSET.iterdir():
        path.unlink()
    ICONSET.rmdir()


if __name__ == '__main__':
    icon = render_icon()
    save_pngs(icon)
    save_ico(icon)
    save_icns(icon)
    print(APP_ICON_PNG)
    print(APP_ICON_ICO)
    print(APP_ICON_ICNS)
    print(RESOURCE_LOGO)
