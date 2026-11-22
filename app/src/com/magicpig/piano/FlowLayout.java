package com.magicpig.piano;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/**
 * 流式布局：子视图一行排不下就换到下一行。
 *
 * 原版的分类标签页是「横向滚动」——名家有 19 个标签（拜厄/哈农/肖邦/巴赫…），
 * 一排排到屏幕外，得来回滚才能看到后面几个。这里改成换行，一屏看全。
 *
 * 只做「测量 + 摆放」，不画背景；间距由 {@link #setGaps} 统一给，
 * 子视图自己不要再设 margin（会叠加）。
 */
public class FlowLayout extends ViewGroup {

    private int mGapH;
    private int mGapV;

    public FlowLayout(Context c) {
        this(c, null);
    }

    public FlowLayout(Context c, AttributeSet a) {
        super(c, a);
    }

    public void setGaps(int horizDp, int vertDp) {
        mGapH = horizDp;
        mGapV = vertDp;
        requestLayout();
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet a) {
        return new MarginLayoutParams(getContext(), a);
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        final int maxW = MeasureSpec.getSize(widthSpec)
                - getPaddingLeft() - getPaddingRight();
        int lineW = 0;      // 当前行已占用宽度（不含与下一项的间距）
        int lineH = 0;      // 当前行最高子视图
        int totalW = 0;
        int totalH = 0;
        final int n = getChildCount();

        for (int i = 0; i < n; i++) {
            View ch = getChildAt(i);
            if (ch.getVisibility() == GONE) continue;
            measureChild(ch, widthSpec, heightSpec);
            MarginLayoutParams lp = (MarginLayoutParams) ch.getLayoutParams();
            int w = ch.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
            int h = ch.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
            // 本行放不下且本行已有内容 -> 换行
            if (lineW > 0 && lineW + mGapH + w > maxW) {
                totalW = Math.max(totalW, lineW);
                totalH += lineH + mGapV;
                lineW = w;
                lineH = h;
            } else {
                lineW += (lineW > 0 ? mGapH : 0) + w;
                lineH = Math.max(lineH, h);
            }
        }
        totalW = Math.max(totalW, lineW);
        totalH += lineH;

        setMeasuredDimension(
                resolveSizeAndState(totalW + getPaddingLeft() + getPaddingRight(),
                        widthSpec, 0),
                resolveSizeAndState(totalH + getPaddingTop() + getPaddingBottom(),
                        heightSpec, 0));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        final int maxRight = r - l - getPaddingRight();
        int left = getPaddingLeft();
        int top = getPaddingTop();
        int lineH = 0;
        final int n = getChildCount();

        for (int i = 0; i < n; i++) {
            View ch = getChildAt(i);
            if (ch.getVisibility() == GONE) continue;
            MarginLayoutParams lp = (MarginLayoutParams) ch.getLayoutParams();
            int w = ch.getMeasuredWidth();
            int h = ch.getMeasuredHeight();
            if (left > getPaddingLeft() && left + lp.leftMargin + w > maxRight) {
                left = getPaddingLeft();
                top += lineH + mGapV;
                lineH = 0;
            }
            int cl = left + lp.leftMargin;
            int ct = top + lp.topMargin;
            ch.layout(cl, ct, cl + w, ct + h);
            left = cl + w + lp.rightMargin + mGapH;
            lineH = Math.max(lineH, h + lp.topMargin + lp.bottomMargin);
        }
    }
}
