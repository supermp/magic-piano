package com.magicpig.piano;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 「某钢琴APP」的视觉规范，色值/字体取自其界面截图。
 *
 * 取样来源（原版界面截图，图片文件已清理，只留色值结论）：
 *   - 羊皮纸背景 bg_library.png                = #EBC6A5
 *   - 曲库左侧面板                              = #F1CFB0
 *   - 曲库右侧内容区                            = #FDF4DC
 *   - 曲谱预览页背景                            = #FFFCE8
 *   - 右上角圆形按钮（收藏/试听）               = #774B04
 *   - 底部「✦ 去练习 ✦」金色胶囊按钮            = 渐变 #F0C03C → #D89A30，文字 #4D2D00
 *   - 标题字体：AlimamaDaoLiTi（阿里妈妈刀隶体，原版 FontManifest.json 里的 Fattern）
 *   - 正文字体：InterVariable
 */
public final class Ui {

    // ---- 配色 ----
    public static final int PARCHMENT = 0xFFEBC6A5;   // 羊皮纸底
    public static final int PANEL = 0xFFF1CFB0;       // 曲库左面板
    public static final int CONTENT = 0xFFFDF4DC;     // 曲库右内容区
    public static final int SHEET_BG = 0xFFFFFCE8;    // 曲谱页底
    public static final int BROWN = 0xFF774B04;       // 主棕（圆形按钮、图标）
    public static final int BROWN_DARK = 0xFF4D2D00;  // 深棕（金色按钮上的文字）
    public static final int GOLD_LIGHT = 0xFFF0C03C;  // 金色渐变亮端
    public static final int GOLD_DARK = 0xFFD89A30;   // 金色渐变暗端
    public static final int INK = 0xFF3A2A18;         // 正文深褐
    public static final int INK_SOFT = 0xFF8A7358;    // 次要文字

    private static final Map<String, Typeface> FONTS = new HashMap<>();

    private Ui() {
    }

    /** 原版标题用的刀隶体；加载失败时退回系统粗体。 */
    public static Typeface titleFont(Activity a) {
        return font(a, "AlimamaDaoLiTi.ttf", Typeface.create("sans-serif", Typeface.BOLD));
    }

    /** 原版正文用的 Inter 可变字体。 */
    public static Typeface bodyFont(Activity a) {
        return font(a, "InterVariable.ttf", Typeface.create("sans-serif", Typeface.NORMAL));
    }


    private static Typeface font(Activity a, String asset, Typeface fallback) {
        synchronized (FONTS) {
            Typeface tf = FONTS.get(asset);
            if (tf == null) {
                try {
                    InputStream in = a.getAssets().open("fonts/" + asset);
                    java.io.File f = cacheFile(a, asset, in);
                    tf = Typeface.createFromFile(f);
                } catch (Throwable t) {
                    tf = fallback;
                }
                FONTS.put(asset, tf);
            }
            return tf;
        }
    }

    /** 字体文件从 assets 落一份到缓存，Typeface.createFromAsset 在 API 26 也支持，
     *  但可变字体走文件路径更稳。 */
    private static java.io.File cacheFile(Activity a, String name, InputStream in)
            throws IOException {
        java.io.File out = new java.io.File(a.getCacheDir(), name);
        if (!out.exists()) {
            java.io.OutputStream os = new java.io.FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.close();
        }
        in.close();
        return out;
    }

    // ---- 控件工厂 ----

    /**
     * 收藏心形图标（参照 App 素材）。
     *
     * 一定要用图而不是 ♡ / ♥ 字符：这两个字符在不同字体里基线不一样，
     * 和中文混排时会一高一低（「已收藏」状态换成了另一个字符，所以反而看着对齐）。
     *
     * @param big 专辑用的大号（24dp），乐曲行用小号（20dp）
     */
    public static Bitmap favBitmap(Activity a, boolean on, boolean big) {
        if (big) return assetBitmap(a, on ? "ic_fav_album_on.png" : "ic_fav_album_off.png");
        return assetBitmap(a, on ? "ic_fav_sheet_on.png" : "ic_fav_sheet_off.png");
    }

    /** 单个圆形按钮的两块结构，播放页要动态换图标/文字（试听↔停止）所以得留句柄。 */
    public static final class CircleButton {
        public final LinearLayout root;
        public final ImageView icon;
        public final TextView label;

        CircleButton(LinearLayout root, ImageView icon, TextView label) {
            this.root = root;
            this.icon = icon;
            this.label = label;
        }

        public void setState(String pngName, String text) {
            Bitmap b = assetBitmap((Activity) root.getContext(), pngName);
            if (b != null) icon.setImageBitmap(b);
            label.setText(text);
        }

        /**
         * 置灰 = 真的不可用。灰色在本 App 里的语义是「现在这个用不了」，
         * 所以除了降透明度，还必须断掉点击 —— 只调 alpha 的话点上去仍会
         * 触发监听，用户看到的是「灰了但点了还有反应」，前后矛盾。
         *
         * 三个子视图都要断：点击监听分别绑在容器 / 图标 / 文字上（见
         * PlayerActivity.bindClick），漏一个就有条缝还能点。
         * View 在 enabled=false 时不会再走 performClick，事件被吞掉，
         * 因此连按下的水波/变色都不会有，纯静默。
         */
        public void setUiEnabled(boolean on) {
            root.setEnabled(on);
            icon.setEnabled(on);
            label.setEnabled(on);
            root.setAlpha(on ? 1f : DISABLED_ALPHA);
            // alpha 只在容器上设一次：父子都设会相乘，0.35*0.35 淡到看不见
            icon.setAlpha(1f);
            label.setAlpha(1f);
        }
    }

    /**
     * 原版曲谱预览页右上角的按钮：**直接用原 APK 的整张按钮素材**。
     *
     * 素材在 `assets/flutter_assets/sheetImages/3.0x/`：
     *   ic_sheet_preview_collect.png    收藏（空心心）
     *   ic_sheet_preview_collected.png  已收藏（实心粉心）
     *   ic_sheet_preview_play.png       试听（耳机 + 中间四角星）
     *   ic_sheet_preview_stop.png       停止（圆角方块）
     * 注意这几张图**棕圆和图标是画在一起的**（84×84 = 3.0x 下的 28dp，
     * 圆正好铺满整张），所以不需要再自己画圆和图标，直接当图片摆上去。
     *
     * 圆下方那行文字才是原生控件：原版截图量出来墨高 38px=16.9dp、宽 92px=40.9dp，
     * 刀隶体 21sp 出来的墨高/宽正好对上；圆到文字间距 34px=15dp。
     */
    public static CircleButton circleButton(Activity a, String label, String pngName) {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        pressHighlight(a, col);   // 与底栏 / 浮层按钮一样有按下反馈

        ImageView icon = new ImageView(a);
        Bitmap b = assetBitmap(a, pngName);
        if (b != null) icon.setImageBitmap(b);
        icon.setScaleType(ImageView.ScaleType.FIT_XY);
        // 图标 28dp → 21dp：顶栏要压扁给谱面腾高度
        col.addView(icon, new LinearLayout.LayoutParams(dp(a, 21), dp(a, 21)));

        TextView tv = new TextView(a);
        tv.setText(label);
        tv.setTypeface(titleFont(a));
        // 字号 16sp：与图标一起缩小，保持"图标略大于字"的比例
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp(a, 16f));
        tv.setTextColor(BROWN);
        tv.setIncludeFontPadding(false);
        // 必须居中：标签宽度会被 PlayerActivity.equalLabelWidth 钉成三字宽，
        // TextView 默认左对齐的话，两个字的标签就从格子左缘起笔，
        // 整体左移半个字，跟上面的图标对不齐。
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(a, 7);
        col.addView(tv, tlp);

        return new CircleButton(col, icon, tv);
    }

    /** 给 ImageView 套原版封面的圆角边框（clipToOutline 裁切）。 */
    public static void frame(Activity a, ImageView iv) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFFF3E2C6);
        g.setCornerRadius(dp(a, 12));
        g.setStroke(dp(a, 2), 0xFFC9A87C);
        iv.setBackground(g);
        iv.setClipToOutline(true);
    }

    public static Bitmap assetBitmap(Activity a, String name) {
        return assetBitmapPath(a, "ui/images/" + name);
    }

    /**
     * 直接按 assets 路径取图（不自动拼 ui/images/ 前缀）。
     * 曲集封面在 assets/covers/&lt;uuid&gt;.jpg，数量有 774 张，跟界面图标
     * 放在一起会把目录撑爆，所以单独一层。
     */
    public static Bitmap assetBitmapPath(Activity a, String path) {
        InputStream in = null;
        try {
            in = a.getAssets().open(path);
            return BitmapFactory.decodeStream(in);
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 曲集封面：**外部资源目录优先**（files/covers/&lt;uuid&gt;.jpg），assets 兜底。
     *
     * 封面和目录、乐谱同处一个存储位置，跟着资源包一起更新 —— 新增曲集的封面
     * 只要更新资源包即可，不用重打 APK。
     */

    /**
     * 按**显示尺寸**取封面：{@code reqPx} 是控件边长（像素）。
     *
     * 为什么必须传尺寸：外部资源包里的封面是原版 CDN 原图（常见 1080×1080），
     * 而列表里只显示 56dp。按原尺寸解一张 = 4.6MB 堆，一屏十几个封面就直接
     * OOM（华为/小米这类机型列表几十行更是必崩）。这里先读尺寸算 inSampleSize
     * （只解到 ≥reqPx 的那一档，2 的幂），再按统一 key 进 LruCache ——
     * 同一张封面在「最近打开 / 收藏 / 搜索 / 曲集卡片」里反复出现，只解一次。
     *
     * 缓存只放内存、只按 LRU 淘汰，**绝不 recycle**：同一个 Bitmap 可能正被
     * 好几个 ImageView 持有，recycle 会让它们变透明/崩。
     */
    public static Bitmap coverBitmap(Activity a, String coverUuid, int reqPx) {
        if (coverUuid == null || coverUuid.isEmpty()) return null;
        final int req = Math.max(48, reqPx);
        // 按 2 的幂分档，避免 56dp / 58dp 各自解一份
        final int bucket = Integer.highestOneBit(req * 2 - 1);   // 48→64, 168→256, 640→1024
        final String key = coverUuid + "@" + bucket;
        Bitmap hit = COVERS.get(key);
        if (hit != null && !hit.isRecycled()) return hit;

        // 外部专属目录（与乐谱同位置）优先，内部存储作为兼容位置
        File extDir = a.getExternalFilesDir(null);
        File f = null;
        if (extDir != null) f = new java.io.File(new java.io.File(extDir, "covers"),
                coverUuid + ".jpg");
        if (f == null || !f.isFile()) {
            f = new java.io.File(new java.io.File(a.getFilesDir(), "covers"),
                    coverUuid + ".jpg");
        }
        Bitmap b = null;
        if (f.isFile()) b = decodeCoverSample(f.getAbsolutePath(), bucket);
        // assets 里的封面打包时已缩到 216px 宽，不需要再采样
        if (b == null) b = assetBitmapPath(a, "covers/" + coverUuid + ".jpg");
        if (b != null) COVERS.put(key, b);
        return b;
    }

    /**
     * 封面内存缓存，上限取堆的 1/8（夹在 4~16MB）。
     *
     * ⚠ LruCache 是**静态常驻**的，但它只装 Bitmap、不含任何 Activity/View 引用，
     * 所以不会泄漏页面；上限固定，不会"只增不减"。
     */
    private static final android.util.LruCache<String, Bitmap> COVERS =
            new android.util.LruCache<String, Bitmap>(coverCacheKb()) {
                @Override
                protected int sizeOf(String k, Bitmap b) {
                    return Math.max(1, b.getByteCount() / 1024);
                }
            };

    private static int coverCacheKb() {
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
        return Math.max(4 * 1024, Math.min(maxKb, 16 * 1024));
    }

    /** 只解到"短边 ≥ reqPx"的那一档；解不出来返回 null（调用方还有默认封面兜底）。 */
    private static Bitmap decodeCoverSample(String path, int reqPx) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            final int w = bounds.outWidth, h = bounds.outHeight;
            if (w <= 0 || h <= 0) return null;
            int sample = 1;
            while (Math.min(w, h) / (sample * 2) >= reqPx) sample *= 2;
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = sample;
            return BitmapFactory.decodeFile(path, opt);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 每个 Activity 当前那条 toast（WeakHashMap，Activity 销毁不会泄漏） */
    private static final java.util.WeakHashMap<Activity, TextView> TOASTS = new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<Activity, Runnable> TOAST_HIDE = new java.util.WeakHashMap<>();

    /**
     * 中部浮层的下边距比例（Gravity.BOTTOM 基准，bottomMargin = 屏高 × 此值）。
     * 值越大越靠上。这里取 0.40，让 toast 落在中部区域，与 Gravity.CENTER
     * 的载入卡片接近（具体中心百分比随浮层高度/机型变化，见 AGENTS.md 的真机实测，
     * 别在代码里写死数字）。
     * toast（新建/复用两条路径）与恢复询问条 showRestorePrompt 共用这一个值，别写死多处。
     */
    public static final float TOAST_BOTTOM_RATIO = 0.40f;

    /**
     * 反色浮层（toast / 载入卡片 / 恢复条）的底色。
     * BROWN 纯色太深太实，用半透明让浅色谱面透出一点 → 视觉上变浅、也轻透，
     * 一个参数同时满足"浅一点 + 透明一点"。
     * alpha 取 0.78：让浅色谱面透出一点，同时保住奶油字的对比。
     * 别再往下压太多：奶油字靠不透明底色衬托，太透会和谱线糊在一起看不清。
     * 金边 GOLD_LIGHT 与奶油字 SHEET_BG 仍不透明，保证在浅底/谱线上对比清晰。
     */
    public static final int FLOAT_BG = alpha(BROWN, 0.78f);

    /**
     * 自绘轻量提示。
     *
     * 不用系统 Toast 的两个原因：
     *   1. 系统 Toast 会显示**应用图标**（每一条提示前面都顶个钢琴，纯属噪音）；
     *   2. 系统 Toast 是**排队**播放的 —— 上一条没消失时新的要等，
     *      表现就是"动作做完了，提示才慢半拍出来"。
     * 这里就是一个浮在页面里的圆角小条：新消息直接替换旧消息（立刻显示），1.6 秒后淡出。
     */
    public static void toast(final Activity a, final CharSequence msg) {
        if (a == null || a.isFinishing() || a.isDestroyed()) return;
        a.runOnUiThread(() -> {
            try {
                TextView existing = TOASTS.get(a);
                final int hpx = a.getResources().getDisplayMetrics().heightPixels;
                final TextView tv;
                if (existing == null) {
                    tv = new TextView(a);
                    // 配色：**反色浮层** —— 半透明棕底 + 奶油字 + 金色细边。
                    // 底色用 FLOAT_BG（BROWN@0.78 半透明），浅色谱面能透出一点；
                    // 不用接近谱面底色的实色，否则只靠描边区分，浮层感不足。
                    // 浅一点、透一点；字与金边仍不透明，对比不丢。色值都在现有色板内。
                    tv.setTextColor(SHEET_BG);
                    tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, floatSp(a, 21f));
                    tv.setTypeface(titleFont(a));
                    tv.setGravity(Gravity.CENTER);
                    tv.setIncludeFontPadding(false);
                    int ph = floatDp(a, 20), pv = floatDp(a, 12);
                    tv.setPadding(ph, pv, ph, pv);
                    GradientDrawable bg = new GradientDrawable();
                    bg.setShape(GradientDrawable.RECTANGLE);
                    bg.setCornerRadius(floatDp(a, 24));
                    bg.setColor(FLOAT_BG);
                    // 金色细边呼应按钮/滑块的 GOLD，半透明棕底上不至于糊成一团
                    bg.setStroke(floatDp(a, 2), GOLD_LIGHT);
                    tv.setBackground(bg);
                    tv.setElevation(floatDp(a, 6));
                    android.widget.FrameLayout.LayoutParams lp =
                            new android.widget.FrameLayout.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                    // 提示落在**屏幕中部**（bottomMargin = 屏高 × TOAST_BOTTOM_RATIO）：
                    // 与居中的载入卡片同一视觉区域。
                    // 用"底部对齐 + 相对屏高的下边距"定位，任何分辨率/比例下都落在同一相对位置；
                    // 具体中心百分比随浮层高度/机型变化，别在这里写死数字。
                    lp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
                    lp.bottomMargin = Math.round(hpx * TOAST_BOTTOM_RATIO);
                    android.view.ViewGroup root =
                            a.findViewById(android.R.id.content);
                    root.addView(tv, lp);
                    TOASTS.put(a, tv);
                } else {
                    tv = existing;
                    android.view.ViewGroup.LayoutParams p0 = tv.getLayoutParams();
                    if (p0 instanceof android.widget.FrameLayout.LayoutParams) {
                        ((android.widget.FrameLayout.LayoutParams) p0).bottomMargin =
                                Math.round(hpx * TOAST_BOTTOM_RATIO);
                    }
                }
                tv.setText(msg);
                tv.animate().cancel();
                tv.setAlpha(1f);
                tv.setTranslationY(0f);
                tv.setVisibility(View.VISIBLE);
                // 新的来了就取消上一条的隐藏计时 —— 立即显示最新内容
                Runnable old = TOAST_HIDE.remove(a);
                if (old != null) tv.removeCallbacks(old);
                Runnable hide = () -> tv.animate().alpha(0f).setDuration(180)
                        .withEndAction(() -> tv.setVisibility(View.GONE)).start();
                TOAST_HIDE.put(a, hide);
                tv.postDelayed(hide, 1600);
            } catch (Throwable ignored) {
            }
        });
    }

    /**
     * 界面缩放：以 **1440p 横屏（711 × 640 dp）** 为设计基准，
     * 取「宽比 / 高比」里较小的那个，让整套界面等比缩放。
     *
     * 为什么需要它：dp 只跟屏幕密度有关，跟屏幕多大无关。同样是 16:9，
     * 720p 是 640×360dp、1080p 是 533×480dp、1440p 是 711×640dp ——
     * 用固定 dp 的话，720p 上左栏卡片会挤成一团、21:9 超宽屏上又空一大片。
     * 乘上这个系数后：**同比例不同分辨率观感一致**，不同比例也都能完整排下。
     *
     * 取 min 是为了两个方向都不溢出（21:9 高度只有 300dp，必须跟着缩）；
     * 上下限 0.5~1.6 是防止极端比例下缩得看不见或放得过大。
     *
     * 布局尺寸走本方法；字号另走 {@link #fontScale}（不低于 {@link #FONT_MIN}），
     * 免得矮屏上文字跟着布局缩成"蚂蚁字"。
     */
    private static final float BASE_W_DP = 711f;   // 2560×1440 @360dpi 的宽
    private static final float BASE_H_DP = 640f;   // 2560×1440 @360dpi 的高
    private static int sScaleKey = 0;
    private static float sScale = 1f;

    /** 当前界面缩放系数（布局尺寸用）。 */
    public static float scale(Activity a) {
        android.util.DisplayMetrics dm = a.getResources().getDisplayMetrics();
        int wDp = Math.round(dm.widthPixels * 160f / dm.densityDpi);
        int hDp = Math.round(dm.heightPixels * 160f / dm.densityDpi);
        int key = wDp * 10000 + hDp;
        if (key != sScaleKey) {
            sScaleKey = key;
            float byW = wDp / BASE_W_DP;
            float byH = hDp / BASE_H_DP;
            float s = Math.min(byW, byH);
            // 高度是瓶颈时（21:9、3:2 这类矮/方屏）额外留 5% 余量：
            // 否则左栏最后一项会正好贴在屏幕底边被切掉（3:2 实测）。
            // 宽度是瓶颈或两者相等时不打折，保证 1440p 基准仍是 1.0。
            if (byH < byW - 0.001f) s *= 0.95f;
            sScale = clampF(s, 0.5f, 1.6f);
        }
        return sScale;
    }

    private static float clampF(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** 带缩放的 dp：所有布局尺寸都走这里，界面就整体等比。 */
    public static int dp(Activity a, int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density * scale(a) + 0.5f);
    }

    /** 字号缩放下限：布局可以继续等比缩，但字小到看不清就没意义了 */
    private static final float FONT_MIN = 0.68f;

    /**
     * 字号专用缩放：布局系数与 {@link #FONT_MIN} 取大。
     *
     * 为什么要单独设下限：高度受限的屏（21:9 手机 CSS 高只有 ~359）布局系数会掉到
     * 0.53 左右，字也按 0.53 缩就成了"蚂蚁字"（17sp 标题只剩 9sp）。
     * 布局该多紧还是多紧（木牌、边距继续等比），只有**文字**保底 0.68。
     * 代价是字相对容器会偏大，靠各控件自己的 autosize / 单行省略兜住
     * （左栏分类名就是这么处理的）。
     */
    public static float fontScale(Activity a) {
        return Math.max(scale(a), FONT_MIN);
    }

    /** 带缩放的字号（sp 值，配合 TypedValue.COMPLEX_UNIT_SP 使用）。 */
    public static float sp(Activity a, float v) {
        return v * fontScale(a);
    }

    public static int alpha(int color, float a) {
        int al = (int) (255 * a) & 0xFF;
        return (al << 24) | (color & 0x00FFFFFF);
    }

    // ---------------- 浮层统一件 ----------------

    /**
     * 「不可用」的**唯一**透明度。顶栏、进度条/下一曲、成绩卡结算期、返回箭头
     * 全部走这一套，避免同一套界面出现多种灰。
     */
    public static final float DISABLED_ALPHA = 0.40f;

    /** 置灰 = 变淡 + 断点击（enabled=false 的 View 不会走 performClick）。 */
    public static void setDisabled(View v, boolean disabled) {
        if (v == null) return;
        v.setEnabled(!disabled);
        v.setAlpha(disabled ? DISABLED_ALPHA : 1f);
    }

    /**
     * 浮层专用缩放：布局系数低于 FLOAT_MIN 时按 FLOAT_MIN 算。
     *
     * 21:9 手机上 scale≈0.53，普通 dp()/sp() 会把弹框和按钮压得过小。
     * 浮层是临时盖在
     * 谱面上的独立物件，不必跟常驻布局一起缩那么狠；字号另设更高的下限，
     * 手机上按钮文字从 14.3sp 回到 17.9sp。
     */
    private static final float FLOAT_MIN = 0.80f;
    private static final float FLOAT_FONT_MIN = 0.85f;

    public static float floatScale(Activity a) {
        return Math.max(scale(a), FLOAT_MIN);
    }

    /** 浮层专用 dp：padding / 圆角 / 边框 / 最小高度 / 卡片宽度都走这里。 */
    public static int floatDp(Activity a, int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density * floatScale(a) + 0.5f);
    }

    /** 浮层专用 sp：下限比常驻文字更高，保证手机上仍读得清。 */
    public static float floatSp(Activity a, float v) {
        return v * Math.max(fontScale(a), FLOAT_FONT_MIN);
    }

    /** 浮层按钮的底（normal / pressed 两态），主次只差配色。 */
    private static GradientDrawable floatButtonBg(Activity a, boolean primary, boolean pressed) {
        int[] colors = primary
                ? (pressed ? new int[]{GOLD_DARK, GOLD_LIGHT}
                           : new int[]{GOLD_LIGHT, GOLD_DARK})
                : new int[]{alpha(SHEET_BG, pressed ? 0.28f : 0.12f),
                            alpha(SHEET_BG, pressed ? 0.28f : 0.12f)};
        GradientDrawable g = new GradientDrawable(
                primary ? GradientDrawable.Orientation.TL_BR
                        : GradientDrawable.Orientation.TOP_BOTTOM, colors);
        g.setCornerRadius(floatDp(a, 24));
        g.setStroke(Math.max(1, floatDp(a, 2)), GOLD_LIGHT);
        return g;
    }

    /**
     * 无底色按钮（顶栏圆钮、返回箭头）的按下反馈：按下时浮一层很淡的棕圆角底，
     * 松开立刻消失。底栏 4 个自绘按钮自带 isPressed() 反馈；其余无底色按钮
     * 也要有这一层按下反馈，否则点上去没有动静。
     */
    public static void pressHighlight(Activity a, View v) {
        GradientDrawable on = new GradientDrawable();
        on.setColor(alpha(BROWN, 0.12f));
        on.setCornerRadius(dp(a, 12));
        GradientDrawable off = new GradientDrawable();
        off.setColor(0x00000000);
        off.setCornerRadius(dp(a, 12));
        android.graphics.drawable.StateListDrawable sld =
                new android.graphics.drawable.StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_pressed}, on);
        sld.addState(new int[]{}, off);
        v.setBackground(sld);
        v.setClickable(true);
    }

    /** {@link #floatButton(Activity, String, boolean, boolean)} 的尺寸档：标准（成绩卡） */
    public static final boolean SIZE_STANDARD = false;
    /** 尺寸档：紧凑（只有一行正文的浮层：恢复询问条、载入卡「取消」） */
    public static final boolean SIZE_COMPACT = true;

    public static TextView floatButton(Activity a, String text, boolean primary) {
        return floatButton(a, text, primary, SIZE_STANDARD);
    }

    /**
     * 所有弹出层按钮的**唯一**构造器：成绩卡四颗、载入卡「取消」、
     * 恢复条「恢复 / 不用」都走这里。主次只差底色，圆角 / 金边 / 字号 /
     * 内边距 / 最小高度 / 按下态一致 —— 不再各画一套。
     *
     * 尺寸分两档：标准档（成绩卡 / 错误卡，21sp / 56dp，卡片里有大标题和分数兜着）、
     * 紧凑档（只有一行正文的浮层，18sp / 48dp —— 否则按钮比正文还大）。
     *
     * @param compact true = 紧凑档
     */
    public static TextView floatButton(Activity a, String text, boolean primary, boolean compact) {
        TextView b = new TextView(a);
        b.setText(text);
        b.setTypeface(titleFont(a));
        b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                floatSp(a, compact ? 18f : 21f));
        b.setTextColor(primary ? BROWN_DARK : SHEET_BG);
        b.setGravity(Gravity.CENTER);
        b.setIncludeFontPadding(false);
        b.setSingleLine(true);
        android.graphics.drawable.StateListDrawable sld =
                new android.graphics.drawable.StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_pressed}, floatButtonBg(a, primary, true));
        sld.addState(new int[]{}, floatButtonBg(a, primary, false));
        b.setBackground(sld);
        int p = floatDp(a, compact ? 10 : 12);
        b.setPadding(p, p, p, p);
        // 最小可点高度：手机上 ~24dp 物理高太难点中，这里统一抬到 ~45dp
        b.setMinHeight(floatDp(a, compact ? 48 : 56));
        // 最小宽度：两字按钮（「取消」「恢复」）如果按内容收窄，24dp 圆角会把它
        // 画成一颗圆豆，跟成绩卡那三颗药丸不像一套。给了下限就是同一形状。
        b.setMinWidth(floatDp(a, compact ? 84 : 96));
        b.setClickable(true);
        return b;
    }
}
