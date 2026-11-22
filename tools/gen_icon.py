#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 App 启动图标：三白键 + 两黑键 + 金色魔法闪点。

设计要点（对应反馈）：
  · 只要钢琴键盘最左边那组「C-D-E」—— 3 个白键、2 个黑键；
  · 上下不要留大片棕色：键盘块做到画面宽度的 84%、高度的 68%，且上下留白对称；
  · 键型做"方"一些（白键高宽比 1.9，比真钢琴的 1:6 敦实得多）；
  · 金色四角星点出"魔法"，呼应「魔术钢琴房」；
  · 配色沿用 App 的棕金体系（主棕 #774B04 / 深棕 #4D2D00 / 金 #F0C03C）。

只画一张 1024×1024，再等比降采样到各 mipmap 密度，保证边缘干净。

用法： python tools/gen_icon.py
"""
import os

from PIL import Image, ImageDraw, ImageFilter

S = 1024
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, 'app', 'res')
PREVIEW = os.path.join(ROOT, 'tmp')

DENSITIES = {
    'mipmap-mdpi': 48,
    'mipmap-hdpi': 72,
    'mipmap-xhdpi': 96,
    'mipmap-xxhdpi': 144,
    'mipmap-xxxhdpi': 192,
}

BROWN_TOP = (128, 82, 10)     # #80520A
BROWN_BOT = (66, 38, 0)       # #422600
KEY_TOP = (255, 253, 245)
KEY_BOT = (232, 216, 184)
KEY_LINE = (150, 110, 56)
BLACK_TOP = (62, 38, 16)
BLACK_BOT = (26, 15, 4)
GOLD = (246, 196, 68)


def vgrad(size, top, bot):
    w, h = size
    img = Image.new('RGB', (1, max(1, h)))
    px = img.load()
    for y in range(img.height):
        t = y / max(1, img.height - 1)
        px[0, y] = tuple(round(a + (b - a) * t) for a, b in zip(top, bot))
    return img.resize((max(1, w), max(1, h)), Image.BILINEAR)


def rmask(size, radius, corners=(True, True, True, True)):
    m = Image.new('L', size, 0)
    ImageDraw.Draw(m).rounded_rectangle(
        [0, 0, size[0] - 1, size[1] - 1], radius=radius, fill=255, corners=corners)
    return m


def star(draw, cx, cy, r, ratio=0.30):
    """四角星（细长十字星）；ratio 控制腰身粗细。"""
    w = r * ratio
    draw.polygon([(cx, cy - r), (cx + w, cy - w), (cx + r, cy), (cx + w, cy + w),
                  (cx, cy + r), (cx - w, cy + w), (cx - r, cy), (cx - w, cy - w)],
                 fill=GOLD)


def build():
    # ---------- 底 ----------
    canvas = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    radius = int(S * 0.225)
    canvas.paste(vgrad((S, S), BROWN_TOP, BROWN_BOT).convert('RGBA'),
                 (0, 0), rmask((S, S), radius))

    # 键盘后方一团极淡的金色光晕，制造"魔术房"的氛围感（不抢主体）
    glow = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gd.ellipse([int(S * 0.10), int(S * 0.08), int(S * 0.90), int(S * 0.88)],
               fill=(246, 196, 68, 46))
    glow = glow.filter(ImageFilter.GaussianBlur(S * 0.075))
    canvas.alpha_composite(Image.composite(glow, Image.new('RGBA', (S, S), (0, 0, 0, 0)),
                                          rmask((S, S), radius)))

    d = ImageDraw.Draw(canvas)

    # ---------- 键盘块（宽度 84%、高度 68%，上下留白对称）----------
    kw = int(S * 0.840)
    kh = int(S * 0.680)
    kx = (S - kw) // 2
    ky = (S - kh) // 2
    key_w = kw / 3.0
    r_key = int(S * 0.030)

    # 键盘整体投影，让它"坐"在底上
    sh = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle(
        [kx + 4, ky + 16, kx + kw + 4, ky + kh + 16],
        radius=r_key, fill=(0, 0, 0, 130))
    canvas.alpha_composite(sh.filter(ImageFilter.GaussianBlur(S * 0.013)))

    # ---- 白键 ----
    for i in range(3):
        x0 = int(kx + i * key_w)
        x1 = int(kx + (i + 1) * key_w)
        w = x1 - x0
        grad = vgrad((w, kh), KEY_TOP, KEY_BOT).convert('RGBA')
        canvas.paste(grad, (x0, ky), rmask((w, kh), r_key, (False, False, True, True)))
        # 键缝：左侧一条细线（第 3 个键右侧由底色留出）
        if i > 0:
            d.line([(x0, ky), (x0, ky + kh)], fill=KEY_LINE, width=max(1, int(S * 0.006)))
        # 顶端一条暗线，强调键盘的上沿
        d.line([(x0, ky), (x1, ky)], fill=(120, 84, 34), width=max(1, int(S * 0.005)))

    # ---- 黑键（两个，压在 1|2、2|3 的缝上，宽 50%、高 52%）----
    bw = int(key_w * 0.500)
    bh = int(kh * 0.520)
    r_b = int(S * 0.018)
    for i in (1, 2):
        cx = int(kx + i * key_w)
        x0 = cx - bw // 2
        grad = vgrad((bw, bh), BLACK_TOP, BLACK_BOT).convert('RGBA')
        canvas.paste(grad, (x0, ky), rmask((bw, bh), r_b, (False, False, True, True)))
        # 左上细高光，黑键才不显得死板
        d.line([(x0 + int(S * 0.012), ky + int(S * 0.030)),
                (x0 + int(S * 0.012), ky + bh - int(S * 0.040))],
               fill=(150, 122, 84, 120), width=max(1, int(S * 0.008)))
        # 黑键底部投影：落在白键上更立体
        bsh = Image.new('RGBA', (S, S), (0, 0, 0, 0))
        ImageDraw.Draw(bsh).rounded_rectangle(
            [x0 - 2, ky + bh - int(S * 0.008), x0 + bw + 2, ky + bh + int(S * 0.020)],
            radius=r_b, fill=(60, 40, 10, 110))
        canvas.alpha_composite(bsh.filter(ImageFilter.GaussianBlur(S * 0.006)))

    # ---------- 金色魔法闪点（全部留在画面内）----------
    star(d, int(S * 0.850), int(S * 0.148), int(S * 0.078), ratio=0.33)
    star(d, int(S * 0.925), int(S * 0.252), int(S * 0.036), ratio=0.33)
    star(d, int(S * 0.788), int(S * 0.070), int(S * 0.024), ratio=0.33)
    return canvas


def main():
    icon = build()
    os.makedirs(PREVIEW, exist_ok=True)
    icon.save(os.path.join(PREVIEW, 'icon_1024.png'))
    for folder, px in DENSITIES.items():
        p = os.path.join(OUT, folder)
        os.makedirs(p, exist_ok=True)
        icon.resize((px, px), Image.LANCZOS).save(os.path.join(p, 'ic_launcher.png'))
        print(f'{folder}/ic_launcher.png  {px}x{px}')

    # 桌面尺寸预览：128 / 72 / 48 三档并排，肉眼判断小尺寸是否还认得出
    prev = Image.new('RGB', (460, 210), (240, 236, 230))
    x = 24
    for px, y in ((128, 41), (72, 69), (48, 81)):
        ic = icon.resize((px, px), Image.LANCZOS)
        prev.paste(ic, (x, y), ic)
        x += px + 36
    prev.save(os.path.join(PREVIEW, 'icon_preview.png'))
    print('预览 -> tmp/icon_preview.png')


if __name__ == '__main__':
    main()
