#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成左上角牌匾 bg_library_title.png（新版：羊皮纸横幅 + 棕字 + 金色四角星）。

设计来源：用户嫌原来的"木牌 + 紫色丝带"太花，要求重做一个符合整体配色的。
配色沿用 App 的既有色板（色值与适用场景见 AGENTS.md）：
    曲谱底 #FFFCE8 / 主棕 #774B04 / 深棕 #4D2D00 / 金色 #F0C03C→#D89A30 / 羊皮纸 #EBC6A5

新版构成（924×399 画布不变，Java 侧的 319×138dp 视图不用动）：
  1. 羊皮纸横幅：圆角、上浅下深纵向渐变、金色双细边、外柔影；
  2. 「魔术钢琴房」：沿用 AlimamaDaoLiTi 字体与文案，主棕色 + 深棕细描边
     （浅底上金色对比度不够，所以由原版的金字改为棕字）；
  3. 文字左右各一枚金色四角星（尖角星，与原版那两枚同形但更精致）。

用法： python tools/gen_library_title.py
"""
import math
import os

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, 'app', 'assets', 'ui', 'images', 'bg_library_title.png')
FONT = os.path.join(ROOT, 'app', 'assets', 'fonts', 'AlimamaDaoLiTi.ttf')

W, H = 924, 399
TEXT = '魔术钢琴房'

CREAM_TOP = (255, 253, 240)      # 横幅顶部（比曲谱底亮一档，分出层次）
CREAM_BOT = (246, 228, 181)      # 横幅底部（羊皮纸偏深的那个色）
BROWN = (163, 108, 22)           # 主棕提亮一档 #A36C16 —— 浅底上原 #774B04 太沉，
                                 # 「魔」字笔画多，糊成一团
BROWN_DK = (119, 75, 4)          # 描边 #774B04（比文字深一档即可）
GOLD_L = (240, 192, 60)          # 金 #F0C03C
GOLD_D = (216, 154, 48)          # 深金 #D89A30


def star(cx, cy, r, waist=0.34):
    """四角星（胖腰、直线边、尖角），与 App 图标上那颗魔法闪点同款。

    就是 tools/gen_icon.py 里 star() 的 8 点直线多边形：外四尖在正上下左右，
    内四点落在 45° 对角线上，腰身 = waist*r。图标的腰比是 0.33，这里 0.34。
    试过用参数化曲线把尖角磨圆（r(θ)=r*(w+(1-w)|cos2θ|^soft)），soft 一小于 1
    就圆成四叶花，不像星，所以用直线边。
    """
    w = r * waist
    return [(cx, cy - r), (cx + w, cy - w), (cx + r, cy), (cx + w, cy + w),
            (cx, cy + r), (cx - w, cy + w), (cx - r, cy), (cx - w, cy - w)]


def vgrad(size, top, bot):
    """纵向渐变图。"""
    w, h = size
    g = Image.new('RGB', (1, h))
    px = g.load()
    for y in range(h):
        t = y / max(1, h - 1)
        px[0, y] = tuple(int(top[i] + (bot[i] - top[i]) * t) for i in range(3))
    return g.resize((w, h))


def rounded_mask(size, radius):
    m = Image.new('L', size, 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size[0] - 1, size[1] - 1],
                                        radius=radius, fill=255)
    return m


def main():
    im = Image.new('RGBA', (W, H), (0, 0, 0, 0))

    # ---------- 1. 羊皮纸横幅 ----------
    bx0, by0, bx1, by1 = 118, 96, 806, 304        # 横幅范围（画布内居中）
    bw, bh = bx1 - bx0, by1 - by0
    rad = 46                                       # 圆角

    # 外柔影：同形深色块下移几像素再高斯模糊
    sh = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle([bx0, by0 + 7, bx1, by1 + 7],
                                         radius=rad, fill=(60, 30, 0, 70))
    sh = sh.filter(ImageFilter.GaussianBlur(10))
    im.alpha_composite(sh)

    # 横幅本体
    band = vgrad((bw, bh), CREAM_TOP, CREAM_BOT).convert('RGBA')
    band.putalpha(rounded_mask((bw, bh), rad))
    im.alpha_composite(band, (bx0, by0))

    # 金色边：外圈深金 + 内侧一圈亮金，出层次
    edge = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    ed = ImageDraw.Draw(edge)
    ed.rounded_rectangle([bx0, by0, bx1, by1], radius=rad,
                         outline=GOLD_D + (255,), width=4)
    ed.rounded_rectangle([bx0 + 6, by0 + 6, bx1 - 6, by1 - 6], radius=rad - 6,
                         outline=GOLD_L + (255,), width=2)
    im.alpha_composite(edge)

    # ---------- 2. 文字（沿用字体，改棕色） ----------
    font = ImageFont.truetype(FONT, 150)
    tmp = Image.new('RGBA', (W * 2, H * 2), (0, 0, 0, 0))
    ImageDraw.Draw(tmp).text((60, 60), TEXT, font=font, fill=(255, 255, 255, 255))
    glyph = tmp.crop(tmp.split()[3].getbbox())
    tw = 420                                        # 目标宽（横幅 688 内，两侧留给星）
    th = int(glyph.size[1] * tw / glyph.size[0])
    glyph = glyph.resize((tw, th), Image.LANCZOS)
    tx = bx0 + (bw - tw) // 2
    ty = by0 + (bh - th) // 2 - 2
    print(f'  文字 {glyph.size} 位于 x[{tx},{tx + tw}] y[{ty},{ty + th}]')

    def stamp(layer, dx, dy, color):
        """把 glyph 以指定颜色/偏移盖一层（描边用八个方向各盖一圈实现）。"""
        l2 = Image.new('RGBA', (W, H), (0, 0, 0, 0))
        l2.paste(layer, (tx + dx, ty + dy), layer)
        p = l2.load()
        for yy in range(H):
            for xx in range(W):
                r, g, b, a = p[xx, yy]
                if a:
                    p[xx, yy] = color + (a,)
        im.alpha_composite(l2)

    # 主棕细描边（1px，八向）+ 提亮棕本体。
    # 描边不能粗：刀隶体笔画本身粗，「魔」字 15+ 笔画，2px 描边就把字糊成一团。
    for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1),
                   (-1, -1), (1, -1), (-1, 1), (1, 1)):
        stamp(glyph, dx, dy, BROWN_DK)
    stamp(glyph, 0, 0, BROWN)

    # ---------- 3. 左右金色四角星 ----------
    # 间距账（横幅宽 688）：外空隙 28 + 星 84 + 空隙 22 + 字 420 + 空隙 22 + 星 84
    # + 外空隙 28。星心 = bx0+70 / bx1-70，外尖端离横幅圆角 28px —— 早先是
    # bx0+46，星尖正好顶在圆角上，一点空隙都没有，用户反馈难看。
    cy = (by0 + by1) // 2
    for cx in (bx0 + 70, bx1 - 70):
        outer = Image.new('RGBA', (W, H), (0, 0, 0, 0))
        ImageDraw.Draw(outer).polygon(star(cx, cy, 42), fill=GOLD_D + (255,))
        im.alpha_composite(outer)
        mid = Image.new('RGBA', (W, H), (0, 0, 0, 0))
        ImageDraw.Draw(mid).polygon(star(cx, cy, 31), fill=GOLD_L + (255,))
        im.alpha_composite(mid)

    im.save(OUT)
    print(f'  saved {OUT} {im.size}')


if __name__ == '__main__':
    main()
