package com.magicpig.piano;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 曲集详情页：一个曲集（原版叫 album）下面的全部乐曲。
 *
 * 版式照原版曲集详情页（当年的参照截图已清理，只留结论）：
 *   左边一栏（羊皮纸底）：返回箭头浮在左上 → 曲集封面（正方形）→ 曲集名
 *   → UP 徽标 → 「♡ 收藏专辑」胶囊 → 底部「最近更新」；
 *   右边是一张占满的圆角米白卡片：表头「共 N 首乐曲 / 页码」加一条分隔线，
 *   下面每行是 曲名 + 作曲家 | 页码 | ♡收藏。
 *
 * 点行 = 打开这首乐曲（曲谱必须已拷进外部目录，没有就 toast 提示）。
 */
public class AlbumDetailActivity extends Activity {

    public static final String EXTRA_ALBUM_UUID = "album_uuid";
    public static final String EXTRA_CATEGORY_NAME = "category_name";

    private SheetCatalog.Album mAlbum;
    /**
     * 是否显示「页码」这一列。
     *
     * 服务端对很多曲集（尤其流行类，全库 43.6% 的条目）根本不给页码，
     * 而原版是固定留 34dp 的空列 —— 整本都没页码时就是一列空白加一个「页码」表头。
     * 所以进曲集时先扫一遍：**一本里连一个页码都没有就整列隐藏**（表头也去掉）。
     * 混合的（哪怕只有 1 首有，全库 42 本）仍然显示，否则那几首的页码就没地方放了。
     */
    private boolean mShowPageCol = true;
    private String mCategoryName = "";
    private LinearLayout mList;
    private ImageView mFavHeart;
    private TextView mFavLabel;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        goFullscreen();

        String uuid = getIntent().getStringExtra(EXTRA_ALBUM_UUID);
        mCategoryName = getIntent().getStringExtra(EXTRA_CATEGORY_NAME);
        if (mCategoryName == null) mCategoryName = "曲集";
        mAlbum = SheetCatalog.albumByUuid(this, uuid);
        if (mAlbum == null) {
            // 正常路径下 uuid 来自当前列表里的曲集对象，这里几乎不可能为 null
            // （只有外部 intent 不带/带错 extra，或运行中 catalog 被换掉才会走到）。
            // 删掉"找不到这个曲集"toast：紧接着就 finish，页面还没渲染
            // 就关了，提示一闪而过没意义。守卫本身必须留，否则下面 mAlbum.sheets 会 NPE。
            finish();
            return;
        }
        mShowPageCol = false;
        for (SheetCatalog.Sheet sh : mAlbum.sheets) {
            if (sh.page != null && sh.page.trim().length() > 0) {
                mShowPageCol = true;
                break;
            }
        }
        buildUi();
    }

    private void goFullscreen() {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goFullscreen();
    }

    private void buildUi() {
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(Ui.PARCHMENT);

        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(LinearLayout.HORIZONTAL);

        // ================= 左栏：封面 + 曲集信息 =================
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_HORIZONTAL);
        // 56dp 是给原来那个文字「←」留的高度；换成原版手绘箭头（墨迹到 67dp）
        // 后会压住封面，所以顶距加到 88dp（箭头下沿 67 + 21dp 间距）
        left.setPadding(Ui.dp(this, 20), Ui.dp(this, 88), Ui.dp(this, 20), Ui.dp(this, 16));
        final int leftW = Ui.dp(this, 300);
        // 封面框必须是正方形：父容器左右各有 20dp 内边距，直接给 leftW 会被
        // 挤成"585 宽 × 675 高"的长方形，方图放进去无论 CENTER_CROP 还是
        // 居中都会裁掉两侧（标题就被切了）。按内容宽度算才稳。
        final int coverSize = leftW - Ui.dp(this, 40);

        ImageView cover = new ImageView(this);
        Bitmap cb = null;
        if (mAlbum.cover != null && !mAlbum.cover.isEmpty()) {
            cb = Ui.coverBitmap(this, mAlbum.cover, coverSize);
        }
        if (cb == null) cb = Ui.assetBitmap(this, "default_sheet_list_cover.png");
        cover.setImageBitmap(cb);
        // FIT_CENTER：详情页要看到封面全貌，少数封面不是正方形（如 3:4）也不能裁
        cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
        left.addView(cover, new LinearLayout.LayoutParams(coverSize, coverSize));

        TextView name = new TextView(this);
        name.setTypeface(Ui.titleFont(this));
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(this, 19f));
        name.setTextColor(Ui.BROWN_DARK);
        name.setGravity(Gravity.CENTER);
        name.setLineSpacing(0, 1.12f);
        name.setMaxLines(3);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setText(mAlbum.name);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = Ui.dp(this, 16);
        left.addView(name, nlp);

        // 「共 N 首」徽标已去掉：右侧列表表头本来就写着「共 N 首乐曲」，重复了。

        if (!mAlbum.publisher.isEmpty()) {
            TextView pub = new TextView(this);
            pub.setTypeface(Ui.bodyFont(this));
            pub.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(this, 12f));
            pub.setTextColor(Ui.INK_SOFT);
            pub.setGravity(Gravity.CENTER);
            pub.setText(mAlbum.publisher);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            plp.topMargin = Ui.dp(this, 10);
            left.addView(pub, plp);
        }

        // 「♡ 收藏本谱」：切整本曲集的收藏状态。
        // 心形用原版图标而不是 ♡/♥ 字符——字符和中文混排在 baseline 上对不齐
        // （空心/实心两个字形的高度还不一样，所以「已收藏」反而看着是正的）。
        LinearLayout favBtn = new LinearLayout(this);
        favBtn.setOrientation(LinearLayout.HORIZONTAL);
        favBtn.setGravity(Gravity.CENTER);
        favBtn.setPadding(Ui.dp(this, 18), Ui.dp(this, 8), Ui.dp(this, 18), Ui.dp(this, 8));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0xFFFDF9F0);   // 原版是浅奶白填充，不是透明
        pill.setCornerRadius(Ui.dp(this, 22));
        pill.setStroke(Ui.dp(this, 2), Ui.alpha(Ui.BROWN, 0.45f));
        favBtn.setBackground(pill);
        favBtn.setOnClickListener(v -> toggleFavAlbum());

        mFavHeart = new ImageView(this);
        mFavHeart.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                Ui.dp(this, 28), Ui.dp(this, 28));
        hlp.rightMargin = Ui.dp(this, 8);
        favBtn.addView(mFavHeart, hlp);

        mFavLabel = new TextView(this);
        mFavLabel.setTypeface(Ui.titleFont(this));
        mFavLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(this, 14f));
        mFavLabel.setTextColor(Ui.BROWN_DARK);
        mFavLabel.setGravity(Gravity.CENTER);
        mFavLabel.setIncludeFontPadding(false);   // 去掉字体自带上下留白，视觉更居中
        mFavLabel.setText("收藏");
        favBtn.addView(mFavLabel);

        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        flp.topMargin = Ui.dp(this, 18);
        left.addView(favBtn, flp);

        // 弹性空白把收藏按钮顶上去（原版左栏下部留白）
        View spacer = new View(this);
        left.addView(spacer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        cols.addView(left, new LinearLayout.LayoutParams(leftW,
                LinearLayout.LayoutParams.MATCH_PARENT));

        // ================= 右栏：圆角卡片里的乐曲表 =================
        final int pad = Ui.dp(this, 14);
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(pad, pad, pad, pad);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 14));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFFFFFCE8);           // 曲谱底同色
        cardBg.setCornerRadius(Ui.dp(this, 16));
        cardBg.setStroke(Ui.dp(this, 1), Ui.alpha(Ui.BROWN, 0.18f));
        card.setBackground(cardBg);

        // 表头
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        // 表头右留白必须等于「行内容」被挤掉的宽度，否则「页码」列与下面的数字错开。
        // 行内容一共损失三段：
        //   ScrollView 的 7dp（滚动条轨道）+ mList 的 12dp + 滚动条本身 4dp
        // 最后那段容易被漏掉 —— 非 OVERLAY 样式的滚动条不叠在内容上，而是直接从
        // 内容宽度里扣掉（实测 mList 比 ScrollView 内容区又窄了 4dp）。
        // 心形放大到 42dp 后把 mList 右边距从 6 加到 12dp（滚动条离心远点）
        // 这里也得同步 +6，否则表头「页码」会与下面的数字错开 6dp。
        head.setPadding(0, 0, Ui.dp(this, 7 + 12 + THUMB_DP), 0);
        TextView mHeadCount = new TextView(this);
        mHeadCount.setTypeface(Ui.titleFont(this));
        mHeadCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(this, 17f));
        mHeadCount.setTextColor(Ui.BROWN_DARK);
        mHeadCount.setText("共 " + mAlbum.sheets.size() + " 首乐曲");
        head.addView(mHeadCount, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView pg = new TextView(this);
        pg.setTypeface(Ui.bodyFont(this));
        pg.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(this, 12f));
        pg.setTextColor(Ui.INK_SOFT);
        pg.setGravity(Gravity.CENTER);
        pg.setText("页码");
        if (mShowPageCol) {
            // 「页码」要正对下面的页码列：列宽 34dp 与行内一致，后面再让出
            // 42dp 给心形列，否则标题会压在
            // 心形上方、看着像没对齐。
            head.addView(pg, new LinearLayout.LayoutParams(Ui.dp(this, 34),
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        head.addView(new View(this), new LinearLayout.LayoutParams(Ui.dp(this, 42), 1));
        card.addView(head);
        View hline = new View(this);
        hline.setBackgroundColor(Ui.alpha(Ui.BROWN, 0.22f));
        card.addView(hline, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 1)));

        mList = new LinearLayout(this);
        mList.setOrientation(LinearLayout.VERTICAL);
        // 右侧留白 12dp：心形放大到 42dp 后，原来的 6dp 让滚动条几乎贴着心形。
        // 这里加的 6dp 必须同步加到上面的表头右留白，否则页码列会错位。
        mList.setPadding(0, 0, Ui.dp(this, 12), 0);
        ScrollView sv = new ScrollView(this);
        // 滚动条要，但不能压在心形上（会误点）。两个手段配合：
        //   1) SCROLLBARS_INSIDE_INSET —— 滚动条画在 ScrollView 的右内边距里，
        //      不叠在内容上；
        //   2) ScrollView 右侧再给 7dp 内边距当轨道宽，加上 mList 自己的 6dp，
        //      心形离滚动条有 19dp，点不到它。
        sv.setVerticalScrollBarEnabled(true);
        sv.setHorizontalScrollBarEnabled(false);
        sv.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
        // 不淡出：列表一屏放不下时始终能看出"还有内容"，也免得为了看一眼滚动条
        // 去戳屏幕。滑块/轨道染成主棕半透明，跟羊皮纸卡片协调。
        sv.setScrollbarFadingEnabled(false);
        sv.setVerticalScrollbarThumbDrawable(scrollThumb(0x8C774B04));
        sv.setVerticalScrollbarTrackDrawable(scrollThumb(0x1A774B04));
        sv.setPadding(0, 0, Ui.dp(this, 7), 0);
        sv.addView(mList);
        card.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        right.addView(card, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

        cols.addView(right, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        root.addView(cols, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 左上返回：用原版那张手绘棕箭头（btn_library_back_arrow.png），
        // 和播放页、曲谱页是同一个素材。之前这里是一个文字「←」，
        // 字号/笔触都跟这套手绘界面不搭，显得像系统控件。
        ImageView back = new ImageView(this);
        Bitmap arrow = Ui.assetBitmap(this, "btn_library_back_arrow.png");
        if (arrow != null) {
            back.setImageBitmap(arrow);
            back.setScaleType(ImageView.ScaleType.FIT_CENTER);
        }
        back.setOnClickListener(v -> finish());
        // 热区比图形大一圈，点着不费劲。尺寸/位置照原版曲集页标定：
        // 原版箭头墨迹 43.5×26dp、左上角在 (31.5, 41)dp（本机 1dp=2.25px 量得）
        back.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
        android.widget.FrameLayout.LayoutParams blp =
                new android.widget.FrameLayout.LayoutParams(
                        Ui.dp(this, 64), Ui.dp(this, 56), Gravity.TOP | Gravity.START);
        blp.leftMargin = Ui.dp(this, 23);
        blp.topMargin = Ui.dp(this, 26);
        root.addView(back, blp);

        setContentView(root);
        refreshFavBtn();
        renderList();
    }

    /** 滚动条滑块宽度（dp）。改这里要同步表头右留白，见 buildUi 里 head 的 padding。 */
    private static final int THUMB_DP = 4;

    /** 滚动条滑块/轨道：主棕半透明的圆角细条。 */
    private android.graphics.drawable.Drawable scrollThumb(int argb) {
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        d.setCornerRadius(Ui.dp(this, 2));
        d.setColor(argb);
        d.setSize(Ui.dp(this, THUMB_DP), Ui.dp(this, 28));   // 轨道只是底，size 由系统拉伸
        return d;
    }

    private void refreshFavBtn() {
        boolean fav = LibraryStore.get(this).isFavAlbum(mAlbum.uuid);
        mFavHeart.setImageBitmap(Ui.favBitmap(this, fav, true));
        mFavLabel.setText(fav ? "已收藏" : "收藏");
    }

    private void toggleFavAlbum() {
        boolean fav = LibraryStore.get(this).toggleAlbum(mAlbum.uuid);
        refreshFavBtn();
    }
    private void renderList() {
        mList.removeAllViews();
        List<SheetCatalog.Sheet> sheets = mAlbum.sheets;
        if (sheets.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("这个曲集下面还没有乐曲");
            t.setTypeface(Ui.bodyFont(this));
            t.setTextColor(Ui.INK_SOFT);
            t.setPadding(0, Ui.dp(this, 30), 0, 0);
            mList.addView(t);
            return;
        }
        for (int i = 0; i < sheets.size(); i++) {
            mList.addView(sheetRow(sheets.get(i)));
        }
    }

    /** 一行乐曲：曲名 + 作曲家 | 页码 | ♡。 */
    private View sheetRow(final SheetCatalog.Sheet sheet) {
        final String enc = SheetFetcher.localEnc(this, sheet.uuid);

        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setTypeface(Ui.titleFont(this));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(this, 15f));
        t.setTextColor(Ui.BROWN_DARK);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setText(sheet.displayName());
        texts.addView(t);

        // 第二行只放作曲家。曲谱状态不写进来——本地有没有是瞬间的事，
        // 中间态文字反而让行高跳动、看着很乱。
        final TextView sub = new TextView(this);
        sub.setTypeface(Ui.bodyFont(this));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(this, 11f));
        sub.setTextColor(Ui.INK_SOFT);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setText(sheet.composer);
        texts.addView(sub);

        r.addView(texts, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // 页码列（原版右侧那一列）。固定 34dp，和表头「页码」对齐
        TextView pg = new TextView(this);
        pg.setTypeface(Ui.bodyFont(this));
        pg.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(this, 12f));
        pg.setTextColor(Ui.INK_SOFT);
        pg.setGravity(Gravity.CENTER);
        pg.setText(sheet.page);
        if (mShowPageCol) {
            r.addView(pg, new LinearLayout.LayoutParams(Ui.dp(this, 34),
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        // 收藏心（原版图标；用图片而不是 ♥/♡ 字符，避免和中文的基线错位）
        final ImageView heart = new ImageView(this);
        heart.setScaleType(ImageView.ScaleType.FIT_CENTER);
        heart.setImageBitmap(Ui.favBitmap(this, LibraryStore.get(this).isFavSheet(sheet.uuid), false));
        heart.setPadding(Ui.dp(this, 6), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));
        heart.setOnClickListener(v -> {
            boolean fav = LibraryStore.get(this).toggleSheet(sheet.uuid);
            heart.setImageBitmap(Ui.favBitmap(this, fav, false));
        });
        r.addView(heart, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 38)));

        r.setOnClickListener(v -> openSheet(sheet, sub));
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(r, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        View line = new View(this);
        line.setBackgroundColor(Ui.alpha(Ui.BROWN, 0.14f));
        wrap.addView(line, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 1)));
        return wrap;
    }

    /**
     * 打开一首乐曲：曲谱只能来自外部拷贝，本地没有就 toast 提示，不联网。
     */
    private void openSheet(final SheetCatalog.Sheet sheet, final TextView sub) {
        SheetFetcher.open(this, sheet.uuid, new SheetFetcher.Callback() {
            @Override
            public void onReady(String encPath) {
                startPlayer(encPath, sheet.displayName());
            }

            @Override
            public void onError(String msg) {
                Ui.toast(AlbumDetailActivity.this, msg);
            }
        });
    }

    private void startPlayer(String enc, String title) {
        Intent it = new Intent(this, PlayerActivity.class);
        it.putExtra(PlayerActivity.EXTRA_ENC, enc);
        it.putExtra(PlayerActivity.EXTRA_TITLE, title);
        // 调试 extra 原样透传（--ez selffeed true 自喂演奏），与 HomeActivity 一致
        if (getIntent().getBooleanExtra("selffeed", false)) {
            it.putExtra("selffeed", true);
        }
        startActivity(it);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从播放页回来：收藏心可能变了
        if (mList != null) renderList();
    }
}
