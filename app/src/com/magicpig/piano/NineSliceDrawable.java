package com.magicpig.piano;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * 代码版九宫格 Drawable。
 *
 * 原 APK 是 Flutter 应用，它的 UI 图（如曲库左侧分类木牌
 * selector_library_item_*、左栏木质底板 bg_library_sidebar）都是
 * 普通 PNG，靠 Flutter 的 centerSlice 做九宫格拉伸——素材中间是
 * 可无限拉伸的素色区，两端/四角是必须保持原样的装饰。
 *
 * 本工程没有 res/ 资源管线（aapt2 只编 manifest），不能走 .9.png，
 * 所以在运行时用 9 片 drawBitmap 复现同样的拉伸行为。
 */
public class NineSliceDrawable extends Drawable {

    private final Bitmap mBmp;
    /** 源图四边不做拉伸的像素宽（源图像素坐标） */
    private final int mFixedL, mFixedR, mFixedT, mFixedB;
    private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Rect mSrc = new Rect();
    private final Rect mDst = new Rect();

    public NineSliceDrawable(Bitmap bmp, int fixedL, int fixedR, int fixedT, int fixedB) {
        mBmp = bmp;
        mFixedL = fixedL;
        mFixedR = fixedR;
        mFixedT = fixedT;
        mFixedB = fixedB;
    }

    @Override
    public void draw(Canvas c) {
        if (mBmp == null || mBmp.isRecycled()) return;
        Rect bd = getBounds();
        int w = mBmp.getWidth(), h = mBmp.getHeight();
        int dw = bd.width(), dh = bd.height();
        if (dw <= 0 || dh <= 0) return;

        int L = Math.min(mFixedL, dw / 2), R = Math.min(mFixedR, dw / 2);
        int T = Math.min(mFixedT, dh / 2), B = Math.min(mFixedB, dh / 2);
        int mw = w - mFixedL - mFixedR, mh = h - mFixedT - mFixedB;
        if (mw <= 0 || mh <= 0) {          // 源图太小无法切九片，退化为整体拉伸
            c.drawBitmap(mBmp, null, bd, mPaint);
            return;
        }
        int dmw = dw - L - R, dmh = dh - T - B;

        // 9 片：四角原样，四边单向拉伸，中心双向拉伸
        piece(c, 0, 0, mFixedL, mFixedT, bd.left, bd.top, bd.left + L, bd.top + T);
        piece(c, mFixedL, 0, mw, mFixedT, bd.left + L, bd.top, bd.left + L + dmw, bd.top + T);
        piece(c, w - mFixedR, 0, mFixedR, mFixedT, bd.right - R, bd.top, bd.right, bd.top + T);

        piece(c, 0, mFixedT, mFixedL, mh, bd.left, bd.top + T, bd.left + L, bd.top + T + dmh);
        piece(c, mFixedL, mFixedT, mw, mh, bd.left + L, bd.top + T, bd.left + L + dmw, bd.top + T + dmh);
        piece(c, w - mFixedR, mFixedT, mFixedR, mh, bd.right - R, bd.top + T, bd.right, bd.top + T + dmh);

        piece(c, 0, h - mFixedB, mFixedL, mFixedB, bd.left, bd.bottom - B, bd.left + L, bd.bottom);
        piece(c, mFixedL, h - mFixedB, mw, mFixedB, bd.left + L, bd.bottom - B, bd.left + L + dmw, bd.bottom);
        piece(c, w - mFixedR, h - mFixedB, mFixedR, mFixedB, bd.right - R, bd.bottom - B, bd.right, bd.bottom);
    }

    private void piece(Canvas c, int sx, int sy, int sw, int sh, int dx, int dy, int dr, int db) {
        if (sw <= 0 || sh <= 0 || dr <= dx || db <= dy) return;
        mSrc.set(sx, sy, sx + sw, sy + sh);
        mDst.set(dx, dy, dr, db);
        c.drawBitmap(mBmp, mSrc, mDst, mPaint);
    }

    @Override
    public void setAlpha(int alpha) {
        mPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        mPaint.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
