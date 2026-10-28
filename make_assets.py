"""Generate the launcher's visual assets: wallpapers, weather icons, app icon, banner."""
import math
import os
import random

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

APP = r"C:\Users\perfe\OneDrive\Documents\deepseek-harness\default-workspace\tv-launcher\app"
DRAWABLE = os.path.join(APP, "res", "drawable-nodpi")
MIPMAP = os.path.join(APP, "res", "mipmap-xxxhdpi")
ASSETS_WP = os.path.join(APP, "assets", "wallpapers")
for d in (DRAWABLE, MIPMAP, ASSETS_WP):
    os.makedirs(d, exist_ok=True)

W, H = 1920, 1080

PALETTES = [
    ((6, 16, 24), (10, 42, 46), (20, 224, 160)),
    ((8, 8, 20), (27, 20, 64), (108, 92, 231)),
    ((16, 11, 8), (42, 17, 8), (255, 122, 24)),
    ((5, 16, 10), (14, 42, 22), (47, 208, 122)),
    ((7, 12, 22), (16, 32, 58), (62, 140, 255)),
    ((16, 8, 20), (42, 14, 44), (224, 74, 192)),
]


def diagonal_gradient(w, h, c0, c1):
    xs = np.linspace(0.0, 1.0, w)[None, :]
    ys = np.linspace(0.0, 1.0, h)[:, None]
    t = np.clip((xs * 0.75 + ys * 0.45 - 0.15) / 1.05, 0.0, 1.0)[..., None]
    a = np.array(c0, dtype=np.float32)
    b = np.array(c1, dtype=np.float32)
    return a + (b - a) * t


def mesh(rng, w, h, tint, strength):
    small = np.zeros((3, 4, 3), dtype=np.uint8)
    for y in range(3):
        for x in range(4):
            c = np.array(tint, dtype=np.float32) * rng.uniform(0.15, 1.0)
            base = np.array([10, 14, 20], dtype=np.float32)
            small[y, x] = np.clip(base + c, 0, 255).astype(np.uint8)
    im = Image.fromarray(small, "RGB").resize((w, h), Image.Resampling.BICUBIC)
    return np.asarray(im, dtype=np.float32) * strength


def glow(canvas, w, h, rng, tint, count=4):
    for _ in range(count):
        cx = rng.uniform(0.05, 0.95) * w
        cy = rng.uniform(0.05, 0.95) * h
        rad = rng.uniform(0.25, 0.6) * w
        inten = rng.uniform(0.10, 0.26)
        ys, xs = np.mgrid[0:h, 0:w]
        d = np.sqrt((xs - cx) ** 2 + (ys - cy) ** 2) / rad
        falloff = np.clip(1.0 - d, 0.0, 1.0) ** 2.2
        canvas += (falloff * inten)[..., None] * np.array(tint, dtype=np.float32)


def compose(idx):
    rng = random.Random(1000 + idx)
    c0, c1, tint = PALETTES[idx]
    canvas = diagonal_gradient(W, H, c0, c1)
    canvas += mesh(rng, W, H, tint, 0.22)
    glow(canvas, W, H, rng, tint)

    ys, xs = np.mgrid[0:H, 0:W]
    nx = (xs - W / 2) / (W / 2)
    ny = (ys - H / 2) / (H / 2)
    r2 = np.clip(nx ** 2 * 0.85 + ny ** 2 * 0.85, 0, 1)
    canvas *= (1.0 - 0.55 * r2)[..., None]

    canvas += np.random.normal(0, 2.4, (H, W, 1))
    canvas = np.clip(canvas, 0, 255).astype(np.uint8)
    return Image.fromarray(canvas, "RGB")


def save_wallpapers():
    for i in range(len(PALETTES)):
        img = compose(i)
        full = os.path.join(ASSETS_WP, f"wp{i}.jpg")
        img.save(full, "JPEG", quality=86, optimize=True, progressive=True)
        thumb = img.resize((480, 270), Image.Resampling.LANCZOS).convert("RGBA")
        mask = Image.new("L", (480, 270), 0)
        ImageDraw.Draw(mask).rounded_rectangle([0, 0, 479, 269], radius=26, fill=255)
        thumb.putalpha(mask)
        thumb.save(os.path.join(ASSETS_WP, f"tw{i}.png"), "PNG", optimize=True)
        print(f"wallpaper {i}: {os.path.getsize(full) // 1024} KB full + thumb")


# ---------------------------------------------------------------- weather icons
S = 3  # supersample factor
SZ = 256


def canvas():
    img = Image.new("RGBA", (SZ * S, SZ * S), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def finish(img, name):
    img = img.resize((SZ, SZ), Image.Resampling.LANCZOS)
    img.save(os.path.join(DRAWABLE, f"w_{name}.png"), "PNG", optimize=True)


SUN = (255, 201, 60, 255)
SUN_CORE = (255, 226, 128, 255)
CLOUD = (232, 240, 248, 255)
CLOUD_SHADE = (176, 190, 206, 255)
RAIN = (79, 195, 247, 255)
BOLT = (255, 213, 79, 255)
SNOW = (224, 247, 250, 255)
FOG = (176, 190, 197, 255)


def draw_sun(d, cx, cy, r):
    for k in range(8):
        a = math.radians(k * 45)
        x1, y1 = cx + math.cos(a) * r * 1.35, cy + math.sin(a) * r * 1.35
        x2, y2 = cx + math.cos(a) * r * 1.75, cy + math.sin(a) * r * 1.75
        d.line([(x1, y1), (x2, y2)], fill=SUN, width=int(r * 0.30))
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=SUN)
    d.ellipse([cx - r * 0.72, cy - r * 0.72, cx + r * 0.72, cy + r * 0.72], fill=SUN_CORE)


def draw_cloud(d, cx, cy, w, shade=True):
    h = w * 0.62
    d.ellipse([cx - w * 0.50, cy - h * 0.10, cx + w * 0.50, cy + h * 0.72], fill=CLOUD)
    d.ellipse([cx - w * 0.40, cy - h * 0.52, cx + w * 0.10, cy + h * 0.30], fill=CLOUD)
    d.ellipse([cx - w * 0.02, cy - h * 0.68, cx + w * 0.42, cy + h * 0.22], fill=CLOUD)
    if shade:
        d.rectangle([cx - w * 0.50, cy + h * 0.34, cx + w * 0.50, cy + h * 0.52], fill=CLOUD_SHADE)


def icon_sunny():
    img, d = canvas()
    draw_sun(d, SZ * S / 2, SZ * S / 2, SZ * S * 0.20)
    finish(img, "sunny")


def icon_partly():
    img, d = canvas()
    draw_sun(d, SZ * S * 0.62, SZ * S * 0.30, SZ * S * 0.145)
    draw_cloud(d, SZ * S * 0.44, SZ * S * 0.62, SZ * S * 0.62)
    finish(img, "partly")


def icon_cloudy():
    img, d = canvas()
    draw_cloud(d, SZ * S * 0.54, SZ * S * 0.42, SZ * S * 0.60)
    draw_cloud(d, SZ * S * 0.38, SZ * S * 0.62, SZ * S * 0.52, shade=False)
    finish(img, "cloudy")


def icon_rain():
    img, d = canvas()
    draw_cloud(d, SZ * S * 0.5, SZ * S * 0.40, SZ * S * 0.72)
    for i in range(4):
        x = SZ * S * (0.26 + i * 0.17)
        d.line([(x, SZ * S * 0.63), (x - SZ * S * 0.05, SZ * S * 0.80)], fill=RAIN, width=int(SZ * S * 0.045))
    finish(img, "rain")


def icon_drizzle():
    img, d = canvas()
    draw_cloud(d, SZ * S * 0.5, SZ * S * 0.40, SZ * S * 0.70)
    for i in range(5):
        for j in range(2):
            x = SZ * S * (0.24 + i * 0.13)
            y = SZ * S * (0.66 + j * 0.12)
            r = SZ * S * 0.022
            d.ellipse([x - r, y - r, x + r, y + r], fill=RAIN)
    finish(img, "drizzle")


def icon_snow():
    img, d = canvas()
    draw_cloud(d, SZ * S * 0.5, SZ * S * 0.38, SZ * S * 0.70)
    for i in range(3):
        cx = SZ * S * (0.28 + i * 0.22)
        cy = SZ * S * 0.70
        r = SZ * S * 0.055
        for k in range(3):
            a = math.radians(k * 60)
            d.line([(cx - math.cos(a) * r, cy - math.sin(a) * r),
                    (cx + math.cos(a) * r, cy + math.sin(a) * r)],
                   fill=SNOW, width=int(SZ * S * 0.022))
    finish(img, "snow")


def icon_thunder():
    img, d = canvas()
    draw_cloud(d, SZ * S * 0.5, SZ * S * 0.36, SZ * S * 0.72)
    pts = [(0.52, 0.55), (0.40, 0.75), (0.50, 0.75), (0.44, 0.92), (0.62, 0.68), (0.51, 0.68), (0.58, 0.55)]
    d.polygon([(SZ * S * x, SZ * S * y) for x, y in pts], fill=BOLT)
    finish(img, "thunder")


def icon_fog():
    img, d = canvas()
    draw_cloud(d, SZ * S * 0.5, SZ * S * 0.36, SZ * S * 0.68, shade=False)
    for i in range(3):
        y = SZ * S * (0.62 + i * 0.11)
        x0 = SZ * S * (0.20 + (i % 2) * 0.06)
        x1 = SZ * S * (0.80 - (i % 2) * 0.06)
        d.rounded_rectangle([x0, y, x1, y + SZ * S * 0.045], radius=SZ * S * 0.02, fill=FOG)
    finish(img, "fog")


def icon_na():
    img, d = canvas()
    d.ellipse([SZ * S * 0.22, SZ * S * 0.22, SZ * S * 0.78, SZ * S * 0.78], outline=CLOUD, width=int(SZ * S * 0.05))
    d.line([(SZ * S * 0.30, SZ * S * 0.30), (SZ * S * 0.70, SZ * S * 0.70)], fill=CLOUD, width=int(SZ * S * 0.05))
    finish(img, "na")


# ---------------------------------------------------------------- app icon/banner
def app_icon():
    n = 192 * 4
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, n - 1, n - 1], radius=int(n * 0.22), fill=(11, 16, 22, 255))
    d.rounded_rectangle([int(n * 0.035)] * 2 + [n - int(n * 0.035)] * 2,
                        radius=int(n * 0.20), outline=(0, 230, 118, 255), width=int(n * 0.022))
    cell = n * 0.155
    gap = n * 0.045
    total_w = cell * 3 + gap * 2
    total_h = cell * 2 + gap
    ox = (n - total_w) / 2
    oy = (n - total_h) / 2
    colors = [(0, 230, 118, 255), (236, 244, 240, 255), (236, 244, 240, 255),
              (236, 244, 240, 255), (236, 244, 240, 255), (0, 230, 118, 255)]
    for i in range(6):
        r, c = divmod(i, 3)
        x = ox + c * (cell + gap)
        y = oy + r * (cell + gap)
        d.rounded_rectangle([x, y, x + cell, y + cell], radius=int(cell * 0.28), fill=colors[i])
    img = img.resize((192, 192), Image.Resampling.LANCZOS)
    img.save(os.path.join(MIPMAP, "ic_launcher.png"), "PNG", optimize=True)
    print("app icon written")


def banner():
    bw, bh = 320, 180
    img = Image.new("RGB", (bw, bh), (9, 13, 19))
    d = ImageDraw.Draw(img)
    for i in range(bh):
        t = i / bh
        d.line([(0, i), (bw, i)], fill=(int(9 + 6 * t), int(13 + 12 * t), int(19 + 14 * t)))
    d.rounded_rectangle([18, 46, 106, 134], radius=14, outline=(0, 230, 118), width=4)
    cell, gap = 20, 6
    ox, oy = 18 + (88 - (cell * 3 + gap * 2)) // 2, 46 + (88 - (cell * 2 + gap)) // 2
    for i in range(6):
        r, c = divmod(i, 3)
        x = ox + c * (cell + gap)
        y = oy + r * (cell + gap)
        d.rounded_rectangle([x, y, x + cell, y + cell], radius=6,
                            fill=(0, 230, 118) if i in (0, 5) else (232, 240, 238))
    try:
        font = ImageFont.truetype(r"C:\Windows\Fonts\segoeuib.ttf", 34)
        small = ImageFont.truetype(r"C:\Windows\Fonts\segoeui.ttf", 15)
    except OSError:
        font = small = ImageFont.load_default()
    d.text((124, 62), "TV Home", font=font, fill=(240, 246, 244))
    d.text((126, 106), "custom launcher", font=small, fill=(120, 200, 165))
    img.save(os.path.join(DRAWABLE, "banner.png"), "PNG", optimize=True)
    print("banner written")


def icon_ui(name, painter):
    n = 96 * 4
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    painter(ImageDraw.Draw(img), n)
    img = img.resize((96, 96), Image.Resampling.LANCZOS)
    img.save(os.path.join(DRAWABLE, f"{name}.png"), "PNG", optimize=True)
    print(f"{name} written")


def paint_settings(d, n):
    white = (240, 246, 244, 255)
    bar = int(n * 0.062)
    for y, knob in ((0.28, 0.36), (0.50, 0.64), (0.72, 0.42)):
        yy = n * y
        d.rounded_rectangle([n * 0.14, yy - bar / 2, n * 0.86, yy + bar / 2],
                            radius=bar / 2, fill=white)
        r = n * 0.095
        cx = n * knob
        d.ellipse([cx - r, yy - r, cx + r, yy + r], fill=white)
        rr = r * 0.52
        d.ellipse([cx - rr, yy - rr, cx + rr, yy + rr], fill=(0, 0, 0, 0))


def paint_apps(d, n):
    white = (240, 246, 244, 255)
    cell = n * 0.19
    gap = n * 0.065
    total = cell * 3 + gap * 2
    ox = (n - total) / 2
    oy = (n - total) / 2
    for r in range(3):
        for c in range(3):
            x = ox + c * (cell + gap)
            y = oy + r * (cell + gap)
            d.rounded_rectangle([x, y, x + cell, y + cell], radius=cell * 0.30, fill=white)


if __name__ == "__main__":
    save_wallpapers()
    for fn in (icon_sunny, icon_partly, icon_cloudy, icon_rain, icon_drizzle,
               icon_snow, icon_thunder, icon_fog, icon_na):
        fn()
    icon_ui("ic_settings", paint_settings)
    icon_ui("ic_apps", paint_apps)
    app_icon()
    banner()
    print("assets done")
