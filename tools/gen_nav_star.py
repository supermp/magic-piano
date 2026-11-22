"""
生成左栏分组用的金色四角星（分隔饰件）。

几何与牌匾（tools/gen_library_title.py）里的星星同源：
8 点直线多边形，腰 = 0.33r。颜色也一致（#F0C03C→#D89A30 渐变 + 深金描边），
这样左栏的分隔饰件和顶部牌匾是同一套语言。

输出：app/assets/ui/images/ic_nav_sep_star.png（48×48，对应 16dp@3x）
"""
import math
import os

from PIL import Image, ImageDraw

OUT = os.path.join(os.path.dirname(__file__), "..",
                   "app", "assets", "ui", "images", "ic_nav_sep_star.png")
SIZE = 48          # 3x 下即 16dp
SS = 8             # 超采样倍数（先画大图再缩小，边缘才不锯齿）
GOLD_L = (240, 192, 60)
GOLD_D = (216, 154, 48)
EDGE = (155, 106, 24)


def star(cx, cy, r, waist=0.33):
    w = r * waist
    return [(cx, cy - r), (cx + w, cy - w), (cx + r, cy), (cx + w, cy + w),
            (cx, cy + r), (cx - w, cy + w), (cx - r, cy), (cx - w, cy - w)]


def main():
    big = SIZE * SS
    im = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)

    c = big / 2
    r = big * 0.46
    pts = star(c, c, r)

    # 渐变：按 y 逐行填色，再用多边形做遮罩
    grad = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    gd = ImageDraw.Draw(grad)
    for y in range(big):
        t = y / (big - 1)
        col = tuple(int(GOLD_L[i] + (GOLD_D[i] - GOLD_L[i]) * t) for i in range(3))
        gd.line([(0, y), (big, y)], fill=col + (255,))

    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).polygon(pts, fill=255)
    im.paste(grad, (0, 0), mask)

    # 描边：多边形轮廓（线宽随尺寸）
    d.line(pts + [pts[0]], fill=EDGE + (255,), width=max(2, big // 24), joint="curve")

    im = im.resize((SIZE, SIZE), Image.LANCZOS)
    im.save(OUT)
    print("saved", os.path.normpath(OUT), im.size)


if __name__ == "__main__":
    main()
