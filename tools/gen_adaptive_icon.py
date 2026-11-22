#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 Android 8+ 自适应图标（background 铺满 + foreground 缩进安全区）。

背景：沿用图标的棕色渐变（铺满 108dp，无边）
前景：透明底 + 金色光晕 + 三白两黑键 + 三颗星，缩到 72/108 安全区并居中
（否则启动器会把这套图标当 legacy 塞进白底遮罩 —— 平板上一圈白的根因）
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))        # tools
ROOT = os.path.dirname(HERE)                             # 项目根
sys.path.insert(0, HERE)                                 # gen_icon 与本脚本同目录
import gen_icon as G   # 复用配色与绘制小工具

S = G.S
ADAPT = {   # 自适应图标 108dp 对应的各密度像素
    'mipmap-mdpi': 108,
    'mipmap-hdpi': 162,
    'mipmap-xhdpi': 216,
    'mipmap-xxhdpi': 324,
    'mipmap-xxxhdpi': 432,
}
SAFE = 72.0 / 108.0     # 安全区占比：内容必须落在这个范围里才不会被遮罩裁掉


def build_foreground():
    """透明底，只画图案（不含棕色底板），坐标沿用原图标的构图。"""
    fg = Image.new('RGBA', (S, S), (0, 0, 0, 0))

    # 键盘后方极淡的金色光晕
    glow = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse(
        [int(S * 0.10), int(S * 0.08), int(S * 0.90), int(S * 0.88)],
        fill=(246, 196, 68, 46))
    fg.alpha_composite(glow.filter(ImageFilter.GaussianBlur(S * 0.075)))

    d = ImageDraw.Draw(fg)
    kw, kh = int(S * 0.840), int(S * 0.680)
    kx, ky = (S - kw) // 2, (S - kh) // 2
    key_w = kw / 3.0
    r_key = int(S * 0.030)

    # 键盘投影
    sh = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle(
        [kx + 4, ky + 16, kx + kw + 4, ky + kh + 16], radius=r_key, fill=(0, 0, 0, 130))
    fg.alpha_composite(sh.filter(ImageFilter.GaussianBlur(S * 0.013)))

    # 白键
    for i in range(3):
        x0 = int(kx + i * key_w)
        x1 = int(kx + (i + 1) * key_w)
        w = x1 - x0
        grad = G.vgrad((w, kh), G.KEY_TOP, G.KEY_BOT).convert('RGBA')
        fg.paste(grad, (x0, ky), G.rmask((w, kh), r_key, (False, False, True, True)))
        if i > 0:
            d.line([(x0, ky), (x0, ky + kh)], fill=G.KEY_LINE, width=max(1, int(S * 0.006)))
        d.line([(x0, ky), (x1, ky)], fill=(120, 84, 34), width=max(1, int(S * 0.005)))

    # 黑键
    bw, bh, r_b = int(key_w * 0.500), int(kh * 0.520), int(S * 0.018)
    for i in (1, 2):
        cx = int(kx + i * key_w)
        x0 = cx - bw // 2
        grad = G.vgrad((bw, bh), G.BLACK_TOP, G.BLACK_BOT).convert('RGBA')
        fg.paste(grad, (x0, ky), G.rmask((bw, bh), r_b, (False, False, True, True)))
        d.line([(x0 + int(S * 0.012), ky + int(S * 0.030)),
                (x0 + int(S * 0.012), ky + bh - int(S * 0.040))],
               fill=(150, 122, 84, 120), width=max(1, int(S * 0.008)))
        bsh = Image.new('RGBA', (S, S), (0, 0, 0, 0))
        ImageDraw.Draw(bsh).rounded_rectangle(
            [x0 - 2, ky + bh - int(S * 0.008), x0 + bw + 2, ky + bh + int(S * 0.020)],
            radius=r_b, fill=(60, 40, 10, 110))
        fg.alpha_composite(bsh.filter(ImageFilter.GaussianBlur(S * 0.006)))

    # 星星
    G.star(d, int(S * 0.850), int(S * 0.148), int(S * 0.078), ratio=0.33)
    G.star(d, int(S * 0.925), int(S * 0.252), int(S * 0.036), ratio=0.33)
    G.star(d, int(S * 0.788), int(S * 0.070), int(S * 0.024), ratio=0.33)
    return fg


def main():
    bg = G.vgrad((S, S), G.BROWN_TOP, G.BROWN_BOT).convert('RGBA')
    fg = build_foreground()
    for folder, px in ADAPT.items():
        out = os.path.join(ROOT, 'app/res', folder)
        os.makedirs(out, exist_ok=True)
        bg.resize((px, px), Image.LANCZOS).save(
            os.path.join(out, 'ic_launcher_background.png'))
        side = int(px * SAFE)
        inner = fg.resize((side, side), Image.LANCZOS)
        canvas = Image.new('RGBA', (px, px), (0, 0, 0, 0))
        off = (px - side) // 2
        canvas.alpha_composite(inner, (off, off))
        canvas.save(os.path.join(out, 'ic_launcher_foreground.png'))
        print('%s  %dx%d（前景内容 %d）' % (folder, px, px, side))


if __name__ == '__main__':
    main()
