package com.magicpig.piano;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.UnderlineSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 曲库主页，照原版「魔法馆」的版式做。
 *
 * 原版这一页的结构：
 *   羊皮纸满铺 → 左上角木质牌匾「魔法馆」→ 左侧一列竖排分类导航
 *   （最近打开 / 我的收藏 / 考级 / 音阶练习曲 / 名家 / 流行 / 重奏&协奏曲）
 *   → 右侧内容区放该分类下的卡片或列表。
 *
 * 单机版与原版的差异：
 *   - 去掉 VIP、客服、老师认证、更多（全都要联网或登录态）；
 *   - 搜索保留，但改成纯本地过滤（同时匹配曲集名与乐曲名，跨所有大类别）；
 *   - 分类与曲集全部来自随包附带的 catalog.json（原版那些内容走服务端），
 *     本 App 不联网，数据没铺到设备的分类显示空态。
 */
public class HomeActivity extends Activity {

    private static final String TAG = "Home";
    private static final String DEFAULT_COVER = "default_sheet_list_cover.png";

    /**
     * 原版侧边栏的分类名；顺序与截图一致。
     * 「内置曲目」已删：那一栏实际是把本地所有曲谱包都列出来，
     * 跟「最近打开」重复。随包内置的示范曲改挂在「名家 → 示范曲」下。
     *
     * 分类名**不写死**：前两个是本地入口，后面直接取目录数据里的分类
     * （catalog.json 的 categories，源头是接口 library/categories）。
     * 这样接口加分类/改名字，我们打包时跟着更新就行，不用动代码。
     */
    private static final String[] LOCAL_NAV_NAMES = {"最近打开", "我的收藏"};

    private static final int NAV_RECENT = 0;      // 「最近打开」
    private static final int NAV_FAV = 1;         // 「我的收藏」
    /** 曲集分类在左栏里的起始下标 */
    private static final int NAV_CATALOG = 2;
    /** 收藏页的两个页签（**曲集在前**，与搜索结果页一致） */
    private static final int FAV_ALBUMS = 0;

    /** 资产是否已解包、可以渲染。onResume 早于解包回来时不能渲染 */
    private volatile boolean mReady;

    private LinearLayout mNavBox;
    private LinearLayout mContent;
    /** 右侧滚动容器：搜索结果按"一屏"裁剪时要用它的视口高度 */
    private android.widget.ScrollView mScroll;
    /** 右侧**吸顶区**（搜索栏 + 分类标签）：放在 ScrollView 外面，滚动时不跟着走 */
    private LinearLayout mTopArea;
    /** 当前搜索词（空 = 不搜索）。同时匹配**曲集名与乐曲名**，跨所有大类别。 */
    private String mQuery = "";
    /** 搜索结果命中的总表（分页追加时从这里取） */
    private java.util.List<SheetCatalog.Album> mSearchHitAlbums = java.util.Collections.emptyList();
    private java.util.List<SheetCatalog.Sheet> mSearchHitSheets = java.util.Collections.emptyList();

    // ---------------- 分页（搜索结果 / 我的收藏共用） ----------------
    /** 每批追加多少行 */
    private static final int PAGE_ROWS_LIST = 12;
    private static final int PAGE_ROWS_GRID = 3;
    /** 正在往里追加的容器 */
    private LinearLayout mPageBox;
    /** 已渲染条目数（网格按卡片数算） */
    private int mPageShown;
    private int mPageTotal;
    /** 网格一行几本（列表为 1） */
    private int mPagePerRow = 1;
    /** true = 曲集网格；false = 列表 */
    private boolean mPageIsGrid;
    /** 网格数据（isGrid 时有效） */
    private java.util.List<SheetCatalog.Album> mPageAlbums;
    /** 列表行构造器（!isGrid 时有效） */
    private RowMaker mPageMaker;
    /** 滚到接近底部时延后追加：fling 过程中改内容高度会让 ScrollView 回弹 */
    private final Runnable mLoadMore = new Runnable() {
        @Override
        public void run() {
            appendPage();
        }
    };

    /** 造第 index 行（收藏的 uuid 列表、搜索命中的 Sheet 都用它） */
    private interface RowMaker {
        View make(int index);
    }
    /** 搜索输入防抖：停手多久才真正重建结果（见 buildSearchBar 的 onTextChanged）。 */
    private static final long SEARCH_DEBOUNCE_MS = 250;
    private final android.os.Handler mSearchDelay =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable mSearchTask;
    /** true = 右侧正在显示「搜索结果」页（独立页面，不是曲集分类页的一部分） */
    private boolean mSearchMode = false;
    /** 左栏搜索框（切导航/清空时要同步它的文本） */
    private android.widget.EditText mSearchInput;
    /** 程序改搜索框文本时置 true，避免 TextWatcher 又触发一次 renderContent */
    private boolean mSuppressSearch = false;
    private TextView mHeadTitle;
    private TextView mHeadSub;
    // 默认落「最近打开」：开 App 先看到自己刚练过的曲子
    // 而不是固定的「名家」。用户在左栏切过之后 mNavIndex 会一直保持到页面销毁。
    private int mNavIndex = NAV_RECENT;
    /** 曲集分类下当前选中的标签下标（每个分类各自记，切回来不重置） */
    private final java.util.HashMap<String, Integer> mTagIndex = new java.util.HashMap<>();
    /** 收藏页当前页签（乐曲 / 曲集；默认曲集，页签顺序曲集在前） */
    private int mFavTab = FAV_ALBUMS;
    /** 搜索结果页当前页签：0 = 曲集，1 = 乐曲 */
    private int mSearchTab = 0;
    /** 本次渲染是否由"用户点页签"触发（点了 0 条的那个页签就别自动跳走） */
    private boolean mTabPicked;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // 资源包还没导入（files/catalog.json 不存在）→ 先去导入页。
        // APK 只带程序和引擎资产，目录/封面/乐谱全在资源包里，没有它整个曲库是空的。
        if (!SheetCatalog.hasExternalCatalog(this)) {
            android.content.Intent it = new android.content.Intent(this, ImportActivity.class);
            // 调试/自动化：把 HomeActivity 收到的资源包路径转给导入页
            String rp = getIntent().getStringExtra("res_path");
            if (rp != null) it.putExtra("res_path", rp);
            startActivity(it);
            finish();
            return;
        }
        buildUi();
        goFullscreen();
        prepare();
    }

    /** 与 PlayerActivity 一致：沉浸式全屏，隐藏 Android 状态栏/导航栏，
     *  使曲库页占用整屏（原版即全屏，否则左栏会被状态栏压下去、底部牌显示不全）。 */
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

    // ---------------- 界面 ----------------

    private void buildUi() {
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(Ui.PARCHMENT);

        Bitmap bg = Ui.assetBitmap(this, "bg_library.png");
        if (bg != null) {
            ImageView bgv = new ImageView(this);
            bgv.setImageBitmap(bg);
            bgv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            root.addView(bgv, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(LinearLayout.HORIZONTAL);

        // ---- 左栏：羊皮纸侧栏底板 + 分类导航 ----
        // 尺寸以"原版 App 跑在本机 2560×1440/360dpi 的实拍截图"为准（1dp=2.25px）：
        //   侧栏右边界 622px -> 276dp
        //   导航牌 185..548px -> 左缩进 82.2dp、宽 161.8dp、高 42.2dp
        //   牌间距 44px -> 19.6dp；首牌顶边 402px -> 178.7dp
        //   选中牌右移 16dp；文字墨高 43px -> 19.1dp
        // 注意：960×540 的原版截图在另一逻辑尺寸下拍摄（1 截图 px≈0.667dp，
        // 不是 1.185dp），不能直接换算成本机 dp。
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        // 原 APK 的 bg_library_navbar.png（552×1125，3.0x）：浅羊皮纸纹理、
        // 四边自带轻微暗色收边，四边各留 30px 固定边保住收边，中段拉伸。
        left.setBackground(new NineSliceDrawable(
                Ui.assetBitmap(this, "bg_library_navbar.png"), 30, 30, 30, 30));

        mNavBox = new LinearLayout(this);
        mNavBox.setOrientation(LinearLayout.VERTICAL);
        // 176dp 是原版首牌位置；加上分组饰件后整列多了约 52dp，底牌离下边框只剩
        // ~32dp。这里上移 18dp（176→158）：牌匾下方留 23dp、底牌下方留 ~50dp，
        // 上下都不挤。
        // 牌匾浮在 y=40..178dp，所以导航从下面开始；搜索框插在牌匾与导航之间。
        // 导航整体从 158dp 下移到 ~232dp（搜索框 36 + 间距），牌列能被 navScroll 滚。
        mNavBox.setPadding(0, Ui.dp(this, 20), 0, Ui.dp(this, 10));

        // 宽 200dp（左栏 276 减左右各 38）：再宽（如 248dp）会几乎顶满左栏、
        // 又紧贴下面的导航牌；当前宽度让中心与导航牌对齐，上下也留出呼吸。
        // 32dp 的胶囊在真机上"太矮、不好点"：
        // 高度提到 40dp，触控面积和多出来的 8dp 上边距抵消，导航牌位置基本不动。
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 40));
        swLp.leftMargin = Ui.dp(this, 38);
        swLp.rightMargin = Ui.dp(this, 38);
        swLp.topMargin = Ui.dp(this, 120);
        // 与下面 8 张导航木牌（renderNav 里 Ui.dp(this, 42)）等高；
        // 低 2dp（如 40dp）时横屏下两列并排会看出不齐。
        left.addView(buildSearchBar(42), swLp);

        ScrollView navScroll = new ScrollView(this);
        navScroll.addView(mNavBox);
        left.addView(navScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        cols.addView(left, new LinearLayout.LayoutParams(
                Ui.dp(this, 276), ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- 右栏：标题 + 内容 ----
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        // 右栏：内容左缘对齐原版 720px -> 320dp（侧栏 276dp + 44dp 内边距）
        right.setPadding(Ui.dp(this, 44), Ui.dp(this, 20), Ui.dp(this, 22), Ui.dp(this, 18));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        mHeadTitle = new TextView(this);
        mHeadTitle.setTypeface(Ui.titleFont(this));
        mHeadTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(this, 24f));
        mHeadTitle.setTextColor(Ui.BROWN_DARK);
        head.addView(mHeadTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mHeadSub = new TextView(this);
        mHeadSub.setTypeface(Ui.bodyFont(this));
        mHeadSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(this, 12f));
        mHeadSub.setTextColor(Ui.INK_SOFT);
        mHeadSub.setGravity(Gravity.RIGHT);
        head.addView(mHeadSub);

        right.addView(head);

        // 吸顶区：搜索栏 + 曲集分类标签。挂在 ScrollView **外面**，
        // 所以往下翻曲集时标签一直留在顶部（用户反馈"滚下去就没了"）。
        mTopArea = new LinearLayout(this);
        mTopArea.setOrientation(LinearLayout.VERTICAL);
        right.addView(mTopArea, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mContent = new LinearLayout(this);
        mContent.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        mScroll = sv;
        // 搜索结果的分页：快滚到底就追加下一批（"还有 N 条未显示"已取消）
        sv.setOnScrollChangeListener((v, sx, sy, osx, osy) -> {
            if (mPageBox == null) return;
            View inner = mScroll.getChildAt(0);
            if (inner == null) return;
            int remain = inner.getHeight() - (mScroll.getHeight() + mScroll.getScrollY());
            if (remain >= Ui.dp(this, 420)) return;
            // **等滚动停下来再追加**：fling 过程中往 ScrollView 里塞视图会改变内容高度，
            // 滚动器按旧边界结算，表现就是"拉到底又弹回去"。120ms 后用户手指/惯性都停了。
            mScroll.removeCallbacks(mLoadMore);
            mScroll.postDelayed(mLoadMore, 120);
        });
        sv.addView(mContent);
        right.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        cols.addView(right, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        root.addView(cols, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 「魔术钢琴房」横幅：浮在左上角，盖住侧栏与内容区的分界（原版即如此）。
        // 新版是羊皮纸横幅（见 tools/gen_library_title.py），图片内容在 924×399
        // 画布内水平居中，所以只要把**视图**居中对准侧栏（276dp）即可：
        //   leftMargin = 276/2 - 319/2 = -21.5dp
        // 这样横幅视觉中心与侧栏中心重合，左右留空一致（-8dp 会让横幅偏右，
        // 左侧空出一大块）。
        ImageView sign = new ImageView(this);
        sign.setImageBitmap(Ui.assetBitmap(this, "bg_library_title.png"));
        sign.setScaleType(ImageView.ScaleType.FIT_XY);
        android.widget.FrameLayout.LayoutParams slp = new android.widget.FrameLayout.LayoutParams(
                Ui.dp(this, 319), Ui.dp(this, 138), Gravity.TOP | Gravity.START);
        slp.leftMargin = Ui.dp(this, 276) / 2 - Ui.dp(this, 319) / 2;
        slp.topMargin = -Ui.dp(this, 3);
        root.addView(sign, slp);

        setContentView(root);
    }

    /**
     * 侧边导航项：直接用原 APK 的木牌素材做底（selector_library_item_inactive /
     * _active，110×28dp@1x / 330×84@3x，中段素色可拉伸、两端带圆形+菱形装饰）。
     *
     * 尺寸全部按"原版 App 跑在本机 2560×1440/360dpi 上"的实拍截图逐像素标定
     * （1dp = 2.25px，按原版在本机 2560×1440/360dpi 的实拍截图标定）：
     *   牌 364×95px      -> 实测 161.8×42.2dp（视图给 165×42，边缘柔化后正好）
     *   左缩进 185px     -> 82.2dp（视图给 80，柔化边 1.8dp）；选中再右移 16dp
     *   首牌顶边 402px   -> 178.7dp；牌间距 44px -> 19.6dp
     *   文字墨高 43px    -> 19.1dp；原版 28sp 时我们墨高 22.7dp，故 23.5sp
     * 注意：17dp 间距按当前固定 7 项定的——42dp 牌高 + 20dp 间距会溢到
     * 654dp、底牌被裁。「内置曲目」不参与导航，当前是 7 项（2 个本地入口 +
     * 5 个目录分类），179+7*42+6*17=575dp；17dp 是既定视觉，别只为凑整再改。
     */
    private void renderNav() {
        mNavBox.removeAllViews();
        for (int i = 0; i < navCount(); i++) {
            final int idx = i;
            // 搜索结果页不属于任何类别，所以搜索状态下**一个都不选中**
            // （用户："搜索结果不应该再属于某个类别了"）。
            final boolean sel = (i == mNavIndex) && !mSearchMode;

            // 本地入口（最近打开 / 我的收藏）与曲集分类之间插一条分组饰件。
            // 两组都用同一块木牌；不要给前两项单独换成"纸签"，实机看着单薄、
            // 跟木牌不搭，只用这条分隔表达"这是两组东西"。
            if (i == NAV_CATALOG) mNavBox.addView(navDivider(), dividerParams());

            TextView t = new TextView(this);
            t.setText(navName(i));
            t.setTypeface(Ui.titleFont(this));
            // 字号带 0.68 下限（见 Ui.fontScale），会明显大于木牌按布局算出的宽度，
            // 所以这里必须靠下面的 autosize 把「重奏&协奏曲」这类长名缩回去 ——
            // 别改成固定小字号：小屏上会看不清。
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(this, 23.5f));
            // 兜底：放不下自动缩到 13sp（同样带缩放），实在放不下再省略号
            t.setAutoSizeTextTypeUniformWithConfiguration(
                    Math.max(1, Math.round(Ui.sp(this, 13f))),
                    Math.max(1, Math.round(Ui.sp(this, 23.5f))),
                    1, TypedValue.COMPLEX_UNIT_SP);
            // 再留一点固定呼吸：文字盒不顶到九宫格两端的装饰片
            int navGap = Ui.dp(this, 9);
            t.setPadding(navGap, 0, navGap, 0);
            t.setTextColor(Ui.BROWN);
            t.setGravity(Gravity.CENTER);
            t.setMaxLines(1);
            t.setEllipsize(TextUtils.TruncateAt.END);   // 兜底：实在放不下也不硬切
            Bitmap plate = Ui.assetBitmap(this, sel
                    ? "selector_library_item_active.png"
                    : "selector_library_item_inactive.png");
            // 两端各 60px 固定（保住圆形/菱形装饰的形状，又不把中间挤没：
            // 90px 时中间只剩 97dp，5 字放不下会被裁掉），上下 18px 固定保住描边
            t.setBackground(new NineSliceDrawable(plate, 60, 60, 18, 18));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    Ui.dp(this, 165), Ui.dp(this, 42));
            lp.leftMargin = Ui.dp(this, sel ? 96 : 80);
            // 第一块分类牌紧接分隔线（上间距由 dividerParams 的 bottomMargin 给），
            // 其余每隔 17dp
            if (i > 0 && i != NAV_CATALOG) lp.topMargin = Ui.dp(this, 16);
            t.setLayoutParams(lp);
            t.setOnClickListener(v -> {
                if (mNavIndex == idx && !mSearchMode) return;
                resetSearch();
                mNavIndex = idx;
                renderNav();
                renderContent();
            });
            mNavBox.addView(t);
        }
    }

    /**
     * 分组饰件：细金线 — 金色四角星 — 细金线。
     * 星形素材 tools/gen_nav_star.py 生成，几何/配色与顶部牌匾的两颗星同源，
     * 所以它读起来是"这家的东西"，而不是随手画的横线。
     */
    private View navDivider() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        int[] fade = {0x00C08A4A, 0x8CC08A4A};
        View l = new View(this);
        l.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, fade));
        row.addView(l, new LinearLayout.LayoutParams(0, Ui.dp(this, 1), 1f));

        ImageView star = new ImageView(this);
        Bitmap s = Ui.assetBitmap(this, "ic_nav_sep_star.png");
        if (s != null) star.setImageBitmap(s);
        star.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                Ui.dp(this, 16), Ui.dp(this, 16));
        slp.leftMargin = Ui.dp(this, 6);
        slp.rightMargin = Ui.dp(this, 6);
        row.addView(star, slp);

        View r = new View(this);
        r.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.RIGHT_LEFT, fade));
        row.addView(r, new LinearLayout.LayoutParams(0, Ui.dp(this, 1), 1f));
        return row;
    }

    private LinearLayout.LayoutParams dividerParams() {
        // 高度给 WRAP_CONTENT：里面那颗星 16dp，写死 1dp 会把星裁没
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Ui.dp(this, 120), LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(this, 100);
        // 两边现在都是木牌，上下各 20/16dp：比牌与牌之间的 17dp 松一点，
        // 但不要放大到 30/18，否则中间会空出一大块。
        lp.topMargin = Ui.dp(this, 16);
        lp.bottomMargin = Ui.dp(this, 14);
        return lp;
    }

    private void renderContent() {
        mContent.removeAllViews();
        if (mTopArea != null) mTopArea.removeAllViews();
        // 重渲染：断开旧的分页容器 + 取消排队中的"加载下一批"，
        // 否则滚动回调可能往已经不在屏幕上的旧容器里追加
        mPageBox = null;
        if (mScroll != null) mScroll.removeCallbacks(mLoadMore);

        // ---- 搜索结果：**独立页面**（用户要求"搜索结果单独用自己的页面展示"）----
        // 不复用曲集分类页的吸顶标签，整块内容区只放结果：
        // 上半是曲集（封面网格），下半是乐曲（列表行）。
        // **不显示顶部「搜索「xxx」」和右上数量**（用户：冗余）——分区标题里已经有数量了。
        if (mSearchMode && !mQuery.isEmpty()) {
            java.util.List<SheetCatalog.Album> albumHits = searchAllAlbums(mQuery);
            java.util.List<SheetCatalog.Sheet> sheetHits = searchAllSheets(mQuery);
            mHeadTitle.setVisibility(View.GONE);
            mHeadSub.setVisibility(View.GONE);
            renderSearchResults(albumHits, sheetHits);
            return;
        }

        mQuery = "";
        // 这里不再 setText(navName(...))：下面三行立刻把标题行整体 GONE，
        // 赋进去的字永远不会显示（左栏已经选中对应入口，本来也不该再报一遍）。

        final boolean recent = mNavIndex == NAV_RECENT;
        final boolean fav = mNavIndex == NAV_FAV;
        final SheetCatalog.Category cat = catalogCategory();

        // 「最近打开 / 我的收藏 / 曲集分类」三种页面都不显示标题行 ——
        // 左栏已经选中了对应入口；数量也不在顶上报，一律放进页签/标签的括号里。
        mHeadTitle.setVisibility(View.GONE);
        mHeadSub.setVisibility(View.GONE);

        if (cat != null) {
            renderAlbumView(cat);
            return;
        }

        if (recent) {
            // LibraryStore 已经按 played 倒序、并滤掉本地没有文件的，这里直接渲染
            final List<String> list = LibraryStore.get(this).recent();
            if (list.isEmpty()) {
                mContent.addView(emptyState());
                return;
            }
            for (String uuid : list) mContent.addView(recentRow(uuid));
            return;
        }
        if (fav) {
            renderFavView();
            return;
        }
        // 标题行此时已经是 GONE（见上），不必再往里写字，直接给空态
        mContent.addView(emptyState());
    }

    /**
     * 我的收藏：上面一页签切「乐曲 / 曲集」，两边分别列。
     * 原版这一页列的是曲集卡片（含一个特殊的「我喜欢的乐曲」），用户要求把
     * 收藏的乐曲和曲集区分开，所以做成两个页签。
     */
    private void renderFavView() {
        // ---- 页签（数量直接写在页签上，顶上不再单独报一行）----
        // **曲集在前、乐曲在后**：和搜索结果页保持同一顺序。
        final String[] names = {
                "曲集(" + LibraryStore.get(this).favAlbums().size() + ")",
                "乐曲(" + LibraryStore.get(this).favSheets().size() + ")",
        };
        addStickyTabs(tabsRow(names, mFavTab, new TabPick() {
            @Override
            public void onPick(int idx) {
                if (mFavTab == idx) return;
                mFavTab = idx;
                renderContent();
            }
        }));

        if (mFavTab == FAV_ALBUMS) {
            renderFavAlbums();
        } else {
            renderFavSheets();
        }
    }

    /**
     * 把页签放进**吸顶区**（mTopArea，在 ScrollView 之外）——和曲集分类标签同一层，
     * 往下滚时页签不跟着走，随时能切「曲集 / 乐曲」。
     */
    private void addStickyTabs(View tabs) {
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.leftMargin = Ui.dp(this, 20);
        hlp.rightMargin = Ui.dp(this, 20);
        hlp.topMargin = Ui.dp(this, 6);
        hlp.bottomMargin = Ui.dp(this, 4);
        mTopArea.addView(tabs, hlp);
    }

    /** 页签回调（避免为了一个 int 参数引入 java.util.function）。 */
    private interface TabPick {
        void onPick(int index);
    }

    /**
     * 页签行（收藏页 / 搜索结果页共用）：选中 16sp + 下划线，未选中 14sp。
     * 数量由调用方写进文案里（「曲集(12)」），不再单独占一行。
     */
    private View tabsRow(String[] names, int selected, final TabPick pick) {
        FlowLayout tabs = new FlowLayout(this);
        tabs.setGaps(Ui.dp(this, 4), Ui.dp(this, 6));
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            final boolean sel = (i == selected);
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setTypeface(Ui.titleFont(this));
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(this, sel ? 16f : 14f));
            t.setTextColor(sel ? Ui.BROWN_DARK : Ui.INK_SOFT);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
            t.setBackground(sel ? tabUnderline() : null);
            t.setOnClickListener(v -> pick.onPick(idx));
            tabs.addView(t, new android.view.ViewGroup.MarginLayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return tabs;
    }

    /** 收藏的乐曲：按收藏先后列成行，没拷曲谱的也能收（点了会提示没拷进来）。 */
    private void renderFavSheets() {
        List<String> uuids = LibraryStore.get(this).favSheets();
        if (uuids.isEmpty()) {
            mContent.addView(favEmpty("在乐曲列表里点心形\n这里就会多一首"));
            return;
        }
        // 收藏可能很多，和搜索结果用同一套分页：先铺一批，滚到底再追加
        final List<String> list = uuids;
        startPagedList(list.size(), new RowMaker() {
            @Override
            public View make(int index) {
                return favSheetRow(list.get(index));
            }
        }, 8);
    }

    /** 一行收藏的乐曲：名字 + 作曲家/来源，点开（曲谱包没铺到设备就提示「乐曲不存在」）。 */
    private View favSheetRow(final String uuid) {
        final SheetCatalog.Sheet sheet = SheetCatalog.sheetByUuid(this, uuid);
        final SheetCatalog.Album album = SheetCatalog.albumOfSheet(this, uuid);
        // 收藏只存 uuid，名字一律现查目录；查不到（理论上不会有）就显示 uuid 前 8 位
        final String title = sheet != null ? sheet.displayName()
                : uuid.substring(0, Math.min(8, uuid.length()));
        final int[] albumSpan = new int[2];
        final String sub = buildSubtitle(
                sheet != null ? sheet.composer : null, album, albumSpan);

        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));

        ImageView thumb = new ImageView(this);
        Bitmap cb = null;
        if (album != null && album.cover != null && !album.cover.isEmpty()) {
            cb = Ui.coverBitmap(this, album.cover, Ui.dp(this, 56));
        }
        if (cb == null) cb = Ui.assetBitmap(this, DEFAULT_COVER);
        thumb.setImageBitmap(cb);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Ui.frame(this, thumb);
        r.addView(thumb, new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(Ui.dp(this, 14), 0, 0, 0);
        TextView t = new TextView(this);
        t.setTypeface(Ui.bodyFont(this));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(this, 16f));
        t.setTextColor(Ui.INK);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setText(title);
        texts.addView(t);
        TextView a = new TextView(this);
        a.setTypeface(Ui.bodyFont(this));
        a.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(this, 12f));
        a.setTextColor(Ui.INK_SOFT);
        a.setSingleLine(true);
        a.setEllipsize(TextUtils.TruncateAt.END);
        a.setText(sub);
        linkAlbumName(a, albumSpan, album, r);
        texts.addView(a);
        r.addView(texts, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // 取消收藏（原版图标，不用 ♥ 字符）
        ImageView heart = new ImageView(this);
        heart.setScaleType(ImageView.ScaleType.FIT_CENTER);
        heart.setImageBitmap(Ui.favBitmap(this, true, false));
        heart.setPadding(Ui.dp(this, 6), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));
        heart.setOnClickListener(v -> {
            LibraryStore.get(this).toggleSheet(uuid);
            renderContent();
        });
        r.addView(heart, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));

        r.setOnClickListener(v -> openFavSheet(uuid, title));
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(r, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        View line = new View(this);
        line.setBackgroundColor(Ui.alpha(Ui.INK_SOFT, 0.35f));
        wrap.addView(line, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        return wrap;
    }

    /** 打开收藏的乐曲：曲谱只能来自外部拷贝，本地没有就 toast 提示，不联网。 */
    private void openFavSheet(final String uuid, final String title) {
        String enc = SheetFetcher.localEnc(this, uuid);
        if (enc == null) {
            Ui.toast(this, "乐曲不存在");
            return;
        }
        startActivity(new Intent(this, PlayerActivity.class)
                .putExtra(PlayerActivity.EXTRA_ENC, enc)
                .putExtra(PlayerActivity.EXTRA_TITLE, title));
    }

    /** 收藏的曲集：卡片网格，点卡片进乐曲列表。 */
    private void renderFavAlbums() {
        List<String> uuids = LibraryStore.get(this).favAlbums();
        List<SheetCatalog.Album> albs = new ArrayList<>();
        for (String u : uuids) {
            SheetCatalog.Album a = SheetCatalog.albumByUuid(this, u);
            if (a != null) albs.add(a);
        }
        if (albs.isEmpty()) {
            // ⚠ 文案要跟页签对上：这里列的是**曲集**，收藏入口是曲集卡片（或详情页）
            // 那个心形，不是"乐曲列表页的收藏按钮"。
            mContent.addView(favEmpty("在曲集卡片上点心形\n这里就会多一本"));
            return;
        }
        startPagedGrid(albs, true);
    }

    private View favEmpty(String tip) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(0, Ui.dp(this, 60), 0, 0);
        TextView face = new TextView(this);
        face.setText("♡");
        face.setTypeface(Ui.titleFont(this));
        face.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(this, 46f));
        face.setTextColor(Ui.alpha(Ui.INK_SOFT, 0.5f));
        face.setGravity(Gravity.CENTER);
        box.addView(face);
        TextView t = new TextView(this);
        t.setText("还没有收藏");
        t.setTypeface(Ui.titleFont(this));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(this, 17f));
        t.setTextColor(Ui.INK_SOFT);
        t.setGravity(Gravity.CENTER);
        box.addView(t);
        TextView s = new TextView(this);
        s.setText(tip);
        s.setTypeface(Ui.bodyFont(this));
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(this, 13f));
        s.setTextColor(Ui.INK_SOFT);
        s.setGravity(Gravity.CENTER);
        s.setPadding(0, Ui.dp(this, 8), 0, 0);
        box.addView(s);
        return box;
    }

    /** 左栏总项数 = 2 个本地入口 + 目录里的分类数 */
    private int navCount() {
        return NAV_CATALOG + SheetCatalog.all(this).size();
    }

    private String navName(int i) {
        if (i < NAV_CATALOG) return LOCAL_NAV_NAMES[i];
        List<SheetCatalog.Category> cats = SheetCatalog.all(this);
        int j = i - NAV_CATALOG;
        return j < cats.size() ? cats.get(j).name : "";
    }

    /** 当前左栏选中项对应的曲集分类；不是曲集分类就返回 null。 */
    private SheetCatalog.Category catalogCategory() {
        if (mNavIndex < NAV_CATALOG) return null;
        List<SheetCatalog.Category> cats = SheetCatalog.all(this);
        int i = mNavIndex - NAV_CATALOG;
        return i < cats.size() ? cats.get(i) : null;
    }

    /**
     * 曲集分类页：**吸顶区**（搜索栏 + 分类标签）+ 可滚动的曲集网格。
     *
     * 吸顶区挂在 mTopArea（ScrollView 之外），往下滚动时标签不会跟着消失
     * （用户反馈"滚下去就没了"）。只有网格在 mContent 里滚。
     */
    private void renderAlbumView(SheetCatalog.Category cat) {
        if (cat.tags.isEmpty()) {
            mContent.addView(emptyState());
            return;
        }
        Integer si = mTagIndex.get(cat.uuid);
        int tagIdx = (si == null || si >= cat.tags.size()) ? 0 : si;
        mTagIndex.put(cat.uuid, tagIdx);
        final SheetCatalog.Tag tag = cat.tags.get(tagIdx);

        buildAlbumTopBar(cat, tagIdx);
        renderAlbumGrid(tag.albums);
    }

    /**
     * 吸顶区 = 搜索栏 + 分类标签行。
     *
     * 每次重建都清空搜索词：切分类/重新进页面时还带着上一轮的词会莫名其妙。
     */
    private void buildAlbumTopBar(final SheetCatalog.Category cat, final int tagIdx) {
        mTopArea.removeAllViews();

        // 标签用**流式换行**（名家有 19 个，一行放不下就该换行，横向滚动没必要）。
        // 但"流行"这一类正好 8 个，14sp + 左右 12dp 内边距会差一点点，
        // 把「网络流行(14)」挤到第二行。这里只收尺寸、不改结构：
        //   字号 14→13（选中 16→15）、左右内边距 12→6dp、项间距 4→10dp。
        // 实测 8 个标签在一行内还留了余量。
        FlowLayout tabs = new FlowLayout(this);
        tabs.setGaps(Ui.dp(this, 10), Ui.dp(this, 6));
        for (int i = 0; i < cat.tags.size(); i++) {
            final int idx = i;
            final boolean sel = (i == tagIdx);
            TextView t = new TextView(this);
            // 曲集数直接写在标签上（如「曲集(12)」），顶栏不另报曲库总量
            t.setText(cat.tags.get(i).name + "(" + cat.tags.get(i).albums.size() + ")");
            t.setTypeface(Ui.titleFont(this));
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(this, sel ? 15f : 13f));
            t.setTextColor(sel ? Ui.BROWN_DARK : Ui.INK_SOFT);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setPadding(Ui.dp(this, 6), Ui.dp(this, 5), Ui.dp(this, 6), Ui.dp(this, 5));
            // 选中态：原版是标签底下压一条金色粗线
            t.setBackground(sel ? tabUnderline() : null);
            t.setOnClickListener(v -> {
                Integer cur = mTagIndex.get(cat.uuid);
                if (cur != null && cur == idx) return;
                mTagIndex.put(cat.uuid, idx);
                renderContent();
            });
            tabs.addView(t, new android.view.ViewGroup.MarginLayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.leftMargin = Ui.dp(this, 20);
        hlp.rightMargin = Ui.dp(this, 20);
        hlp.topMargin = Ui.dp(this, 6);
        hlp.bottomMargin = Ui.dp(this, 4);
        mTopArea.addView(tabs, hlp);
    }

    /**
     * 左栏搜索框（牌匾下方）：放大镜 + 输入框 + ✕。
     * 输入即切到「搜索结果」页；清空立刻回到分类页。
     */
    private View buildSearchBar(int heightDp) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        android.graphics.drawable.GradientDrawable barBg =
                new android.graphics.drawable.GradientDrawable();
        barBg.setColor(0x66FFFFFF);                       // 半透明白，压在羊皮纸上像浅凹槽
        barBg.setCornerRadius(Ui.dp(this, heightDp / 2));
        barBg.setStroke(Ui.dp(this, 1), Ui.alpha(Ui.BROWN, 0.22f));
        bar.setBackground(barBg);
        bar.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);

        bar.addView(searchGlyph(Ui.dp(this, 15), Ui.alpha(Ui.INK_SOFT, 0.9f)),
                new LinearLayout.LayoutParams(Ui.dp(this, 15), Ui.dp(this, 15)));

        final android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setBackground(null);                        // 去掉 EditText 自带下划线
        input.setHint("搜索曲集、乐曲");
        input.setHintTextColor(Ui.alpha(Ui.INK_SOFT, 0.75f));
        input.setTextColor(Ui.INK);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(this, 14f));
        input.setTypeface(Ui.bodyFont(this));
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        // 垂直居中 + 去掉字体自带上下留白：EditText 默认的 includeFontPadding 会让
        // 文字基线偏下，在胶囊里就表现为"字的上半截被切掉"（提示文字同样）。
        input.setGravity(Gravity.CENTER_VERTICAL);
        input.setIncludeFontPadding(false);
        input.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        // 高度用 MATCH_PARENT（= 胶囊高度），让居中在整条内生效
        bar.addView(input, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        mSearchInput = input;

        // 右侧清空按钮：空输入时隐藏，与提示文字里的「点右边的 ✕ 清空」对齐。
        // 点它直接把输入框清空，由 TextWatcher 走正常收尾。
        final TextView clear = new TextView(this);
        clear.setText("✕");
        clear.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(this, 13f));
        clear.setTextColor(Ui.alpha(Ui.INK_SOFT, 0.8f));
        clear.setGravity(Gravity.CENTER);
        clear.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 2), 0);
        clear.setVisibility(View.GONE);
        clear.setOnClickListener(v -> input.setText(""));
        bar.addView(clear, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                clear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                if (mSuppressSearch) return;
                // 防抖：每敲一个字就重建结果页（搜曲集要解码几十张封面、乐曲几十行），
                // 连续输入时会明显卡顿。停手 250ms 再搜，打字过程只更新输入框。
                final String q = s.toString().trim();
                if (mSearchTask != null) mSearchDelay.removeCallbacks(mSearchTask);
                mSearchTask = () -> {
                    mSearchTask = null;
                    // 换了关键词就回到「曲集」页签，别让上一轮的页签选停留在新结果上
                    if (!q.equals(mQuery)) mSearchTab = 0;
                    mQuery = q;
                    mSearchMode = !q.isEmpty();
                    // 搜索态要取消导航高亮（搜索结果不属于任何类别），
                    // 所以这里必须重画导航；导航在 mNavBox 里、输入框在外面，不影响焦点。
                    renderNav();
                    renderContent();
                };
                mSearchDelay.postDelayed(mSearchTask, SEARCH_DEBOUNCE_MS);
            }
        });

        return bar;
    }

    /** 退出搜索：清左栏输入框 + 状态位（不触发 TextWatcher 的重复渲染）。 */
    private void resetSearch() {
        // 先取消排队中的防抖任务，否则刚清空又会被它按旧词搜回来
        if (mSearchTask != null) {
            mSearchDelay.removeCallbacks(mSearchTask);
            mSearchTask = null;
        }
        mQuery = "";
        mSearchMode = false;
        if (mSearchInput != null) {
            mSuppressSearch = true;
            mSearchInput.setText("");
            mSuppressSearch = false;
        }
    }

    /**
     * 跨**所有大类别**按曲集名搜索（忽略大小写，按 uuid 去重，保持目录顺序）。
     * 用户找的是"那本曲集"，不一定记得它在考级还是名家里。
     */
    private java.util.List<SheetCatalog.Album> searchAllAlbums(String q) {
        String key = q.toLowerCase(java.util.Locale.ROOT);
        java.util.List<SheetCatalog.Album> out = new java.util.ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (SheetCatalog.Category c : SheetCatalog.all(this)) {
            for (SheetCatalog.Tag t : c.tags) {
                for (SheetCatalog.Album a : t.albums) {
                    if (a.name == null || !a.name.toLowerCase(java.util.Locale.ROOT).contains(key)) {
                        continue;
                    }
                    if (seen.add(a.uuid)) {
                        out.add(a);
                    }
                }
            }
        }
        return out;
    }

    /** 曲集卡片网格（一行 5 本，1:1 封面）。搜索/切标签都只重建这一块。 */
    private void renderAlbumGrid(java.util.List<SheetCatalog.Album> albums) {
        mContent.removeAllViews();
        if (albums.isEmpty()) {
            mContent.addView(mQuery.isEmpty() ? emptyState() : noResult(mQuery));
            return;
        }
        mContent.addView(buildAlbumGrid(albums));
    }

    /**
     * 网格一行能放几本。不写死 5：以「1440p 下算出来的卡片宽」（151dp，经 Ui.dp 缩放后）
     * 为目标反算，这样 21:9 / 4:3 / 3:2 / 720p 都能把宽度填满，
     * 且卡片的**相对大小**在任何屏幕上都一致。
     */
    private int albumCols() {
        final int gap = Ui.dp(this, 10);
        int avail = getResources().getDisplayMetrics().widthPixels
                - Ui.dp(this, 276) - Ui.dp(this, 44) - Ui.dp(this, 22);
        final int target = Ui.dp(this, 151);
        return Math.max(3, Math.min(10, Math.round(avail / (float) (target + gap))));
    }

    /** 卡片间距（网格与追加分页共用，保证两处一致）。 */
    private int albumGap() {
        return Ui.dp(this, 10);
    }

    /** 单张卡片的宽度：按列数把可用宽度均分（列数见 {@link #albumCols()}）。 */
    private int albumCardW() {
        final int gap = albumGap();
        final int per = albumCols();
        int avail = getResources().getDisplayMetrics().widthPixels
                - Ui.dp(this, 276) - Ui.dp(this, 44) - Ui.dp(this, 22);
        return (avail - gap * (per - 1)) / per;
    }

    /** 只构建网格本身（不动 mContent），搜索结果页要把它和乐曲列表拼在一页里。 */
    private View buildAlbumGrid(java.util.List<SheetCatalog.Album> albums) {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 16));
        final int gap = albumGap();
        final int perRow = albumCols();
        final int cardW = albumCardW();
        LinearLayout row = null;
        for (int i = 0; i < albums.size(); i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                if (i > 0) rlp.topMargin = gap;
                grid.addView(row, rlp);
            }
            row.addView(albumCard(albums.get(i), cardW),
                    new LinearLayout.LayoutParams(cardW, LinearLayout.LayoutParams.WRAP_CONTENT));
            if (i % perRow != perRow - 1) {
                View sp = new View(this);
                row.addView(sp, new LinearLayout.LayoutParams(gap,
                        LinearLayout.LayoutParams.MATCH_PARENT));
            }
        }
        return grid;
    }

    // ---------------- 搜索结果（曲集 + 乐曲） ----------------

    /**
     * 跨**所有大类别**按乐曲名搜索（忽略大小写，按 uuid 去重，保持目录顺序）。
     *
     * 只比曲名、不比作曲家：作曲家一搜会带出整本曲集几十首，反而看不出想找哪首。
     */
    private java.util.List<SheetCatalog.Sheet> searchAllSheets(String q) {
        String key = q.toLowerCase(java.util.Locale.ROOT);
        java.util.List<SheetCatalog.Sheet> out = new java.util.ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (SheetCatalog.Category c : SheetCatalog.all(this)) {
            for (SheetCatalog.Tag t : c.tags) {
                for (SheetCatalog.Album a : t.albums) {
                    for (SheetCatalog.Sheet s : a.sheets) {
                        String nm = s.name == null ? "" : s.name;
                        if (!nm.toLowerCase(java.util.Locale.ROOT).contains(key)) continue;
                        if (seen.add(s.uuid)) out.add(s);
                    }
                }
            }
        }
        return out;
    }

    /**
     * 搜索结果页：**和「我的收藏」一样** —— 上面两个页签「曲集 / 乐曲」（曲集在前）。
     *
     * 结果走通用分页（{@link #startPagedList} / {@link #startPagedGrid}）：
     * 先铺一批，滚到接近底部再追加，直到载完。
     */
    private void renderSearchResults(java.util.List<SheetCatalog.Album> albums,
                                     java.util.List<SheetCatalog.Sheet> sheets) {
        mContent.removeAllViews();
        mPageBox = null;                       // 先断开旧容器，避免滚动回调往旧视图里追加
        mSearchHitAlbums = albums;
        mSearchHitSheets = sheets;

        if (albums.isEmpty() && sheets.isEmpty()) {
            mContent.addView(noResult(mQuery));
            return;
        }
        // 换关键词/换页签都回到顶部：新的一批从头看起
        if (mScroll != null) mScroll.scrollTo(0, 0);
        // 进入搜索时自动切到有结果的那边（免得开局一片空白）；
        // 但用户自己点了页签就尊重他的选择，下一页用占位文字说明"没有匹配的"。
        if (!mTabPicked) {
            if (mSearchTab == 0 && albums.isEmpty()) mSearchTab = 1;
            if (mSearchTab == 1 && sheets.isEmpty()) mSearchTab = 0;
        }
        mTabPicked = false;

        final String[] names = {"曲集(" + albums.size() + ")", "乐曲(" + sheets.size() + ")"};
        addStickyTabs(tabsRow(names, mSearchTab, new TabPick() {
            @Override
            public void onPick(int idx) {
                if (mSearchTab == idx) return;
                mSearchTab = idx;
                mTabPicked = true;      // 用户主动选的，别再自动跳到另一边
                renderContent();
            }
        }));

        // 用户可能主动点到"0 条"的那个页签，别给他一片空白
        if (mSearchTab == 0 && albums.isEmpty()) {
            mContent.addView(tabEmpty("没有匹配的曲集"));
            return;
        }
        if (mSearchTab == 1 && sheets.isEmpty()) {
            mContent.addView(tabEmpty("没有匹配的乐曲"));
            return;
        }

        if (mSearchTab == 0) {
            startPagedGrid(mSearchHitAlbums, true);
        } else {
            startPagedList(mSearchHitSheets.size(), new RowMaker() {
                @Override
                public View make(int index) {
                    return sheetResultRow(mSearchHitSheets.get(index));
                }
            }, 0);
        }
    }

    // ---------------- 通用分页（搜索结果 / 我的收藏共用） ----------------

    /** 曲集网格分页；{@code padded} 为 true 时上下留白（搜索结果页用）。 */
    private void startPagedGrid(java.util.List<SheetCatalog.Album> albums, boolean padded) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        if (padded) box.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 16));
        mContent.addView(box);

        mPageBox = box;
        mPageIsGrid = true;
        mPageAlbums = albums;
        mPageMaker = null;
        mPageTotal = albums.size();
        mPageShown = 0;
        mPagePerRow = albumCols();
        appendPage();
        fillViewportIfNeeded();
    }

    /** 列表分页：{@code maker} 负责造第 index 行。 */
    private void startPagedList(int total, RowMaker maker, int topPaddingDp) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        if (topPaddingDp > 0) box.setPadding(0, Ui.dp(this, topPaddingDp), 0, 0);
        mContent.addView(box);

        mPageBox = box;
        mPageIsGrid = false;
        mPageAlbums = null;
        mPageMaker = maker;
        mPageTotal = total;
        mPageShown = 0;
        mPagePerRow = 1;
        appendPage();
        fillViewportIfNeeded();
    }

    /** 追加下一批（网格按整行、列表一次 12 行）。已到末尾就什么都不做。 */
    private void appendPage() {
        if (mPageBox == null || mPageShown >= mPageTotal) return;
        if (mPageIsGrid) {
            final int per = Math.max(1, mPagePerRow);
            // 按整行取整，避免最后一行只剩半截卡片
            int end = Math.min(mPageTotal, mPageShown + per * PAGE_ROWS_GRID);
            end = Math.min(mPageTotal, mPageShown + ((end - mPageShown + per - 1) / per) * per);
            appendGridRows(mPageBox, mPageAlbums, mPageShown, end);
            mPageShown = end;
        } else {
            final int end = Math.min(mPageTotal, mPageShown + PAGE_ROWS_LIST);
            for (int i = mPageShown; i < end; i++) {
                mPageBox.addView(mPageMaker.make(i));
            }
            mPageShown = end;
        }
    }

    /** 往网格容器里追加 [from, to) 的曲集（按行成组，行间留间距）。 */
    private void appendGridRows(LinearLayout parent,
                                java.util.List<SheetCatalog.Album> albums, int from, int to) {
        final int per = Math.max(1, mPagePerRow);
        final int gap = albumGap();
        final int cardW = albumCardW();
        LinearLayout chunk = new LinearLayout(this);
        chunk.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        if (parent.getChildCount() > 0) clp.topMargin = gap;
        parent.addView(chunk, clp);

        LinearLayout row = null;
        for (int i = from; i < to; i++) {
            if ((i - from) % per == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                if (i > from) rlp.topMargin = gap;
                chunk.addView(row, rlp);
            }
            row.addView(albumCard(albums.get(i), cardW),
                    new LinearLayout.LayoutParams(cardW, LinearLayout.LayoutParams.WRAP_CONTENT));
            if ((i - from) % per != per - 1) {
                View sp = new View(this);
                row.addView(sp, new LinearLayout.LayoutParams(gap,
                        LinearLayout.LayoutParams.MATCH_PARENT));
            }
        }
    }

    /**
     * 一批不够铺满一屏时（超大屏/超宽比例），用户没法"滚到底"触发追加，
     * 这里补几次，保证首屏是满的。
     */
    private void fillViewportIfNeeded() {
        mScroll.post(new Runnable() {
            @Override
            public void run() {
                if (mPageBox == null || mPageShown >= mPageTotal) return;
                View inner = mScroll.getChildAt(0);
                if (inner == null || inner.getHeight() >= mScroll.getHeight()) return;
                appendPage();
                fillViewportIfNeeded();
            }
        });
    }

    /** 一首乐曲的结果行：封面 + 曲名 + 「作曲家 · 所属曲集」。 */
    private View sheetResultRow(final SheetCatalog.Sheet s) {
        SheetCatalog.Album alb = SheetCatalog.albumOfSheet(this, s.uuid);

        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));

        ImageView thumb = new ImageView(this);
        Bitmap cb = null;
        if (alb != null && alb.cover != null && !alb.cover.isEmpty()) {
            cb = Ui.coverBitmap(this, alb.cover, Ui.dp(this, 56));
        }
        if (cb == null) cb = Ui.assetBitmap(this, DEFAULT_COVER);
        thumb.setImageBitmap(cb);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Ui.frame(this, thumb);
        r.addView(thumb, new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(Ui.dp(this, 14), 0, 0, 0);

        final String title = s.displayName();
        TextView t = new TextView(this);
        t.setTypeface(Ui.bodyFont(this));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(this, 16f));
        t.setTextColor(Ui.INK);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setText(title);
        texts.addView(t);

        // 副标题与「最近打开」「我的收藏→乐曲」**完全一致**：
        // 作曲家 · 曲集名，且曲集名带下划线、可点击跳转。不要在这里另拼一套，
        // 否则顺序和链接行为会和其它列表页分叉。
        final int[] albumSpan = new int[2];
        TextView a = new TextView(this);
        a.setTypeface(Ui.bodyFont(this));
        a.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(this, 12f));
        a.setTextColor(Ui.INK_SOFT);
        a.setSingleLine(true);
        a.setEllipsize(TextUtils.TruncateAt.END);
        a.setText(buildSubtitle(s.composer, alb, albumSpan));
        linkAlbumName(a, albumSpan, alb, r);
        texts.addView(a);
        r.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 收藏 / 取消收藏（和「我的收藏」页同一个图标、同一份存储）。
        // 与曲集卡片 / 详情页保持一致：**不弹提示**，只换图标。
        final ImageView heart = new ImageView(this);
        heart.setScaleType(ImageView.ScaleType.FIT_CENTER);
        heart.setImageBitmap(Ui.favBitmap(this,
                LibraryStore.get(this).isFavSheet(s.uuid), false));
        heart.setPadding(Ui.dp(this, 6), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));
        heart.setOnClickListener(v -> {
            boolean on = LibraryStore.get(this).toggleSheet(s.uuid);
            // 只换图标，不整页重排：重排会丢滚动位置，也会让刚点的行跳走
            heart.setImageBitmap(Ui.favBitmap(this, on, false));
        });
        r.addView(heart, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));

        final String enc = SheetFetcher.localEnc(this, s.uuid);
        r.setOnClickListener(v -> {
            if (enc != null) {
                open(enc, title);
            } else {
                Ui.toast(HomeActivity.this, "乐曲不存在");
            }
        });

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(r, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View line = new View(this);
        line.setBackgroundColor(Ui.alpha(Ui.INK_SOFT, 0.35f));
        wrap.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return wrap;
    }

    /** 某个页签没有结果时的占位（另一个页签还有结果的情况）。 */
    private View tabEmpty(String msg) {
        TextView t = new TextView(this);
        t.setText(msg);
        t.setTypeface(Ui.bodyFont(this));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(this, 14f));
        t.setTextColor(Ui.INK_SOFT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(this, 60), 0, 0);
        return t;
    }

    /** 搜索无结果。 */
    private View noResult(String q) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(0, Ui.dp(this, 70), 0, 0);

        TextView t = new TextView(this);
        t.setText("没有找到「" + q + "」");
        t.setTypeface(Ui.titleFont(this));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(this, 16f));
        t.setTextColor(Ui.INK_SOFT);
        t.setGravity(Gravity.CENTER);
        box.addView(t);

        TextView s = new TextView(this);
        s.setText("曲集名和乐曲名都能搜，换个关键词，或点搜索框右边的 ✕ 清空");
        s.setTypeface(Ui.bodyFont(this));
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(this, 13f));
        s.setTextColor(Ui.alpha(Ui.INK_SOFT, 0.85f));
        s.setGravity(Gravity.CENTER);
        s.setPadding(0, Ui.dp(this, 8), 0, 0);
        box.addView(s);
        return box;
    }

    /**
     * 搜索图标（放大镜）：自己画，不依赖字体里的符号
     * （emoji 会变彩色、'⌕' 在小字号下糊成一团）。
     */
    private View searchGlyph(int size, int color) {
        return new View(this) {
            @Override
            protected void onDraw(android.graphics.Canvas cv) {
                android.graphics.Paint p =
                        new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                p.setColor(color);
                float w = getWidth();
                float h = getHeight();
                p.setStrokeWidth(Math.max(1.4f, h * 0.11f));
                float r = Math.min(w, h) * 0.29f;
                float cx = w * 0.42f;
                float cy = h * 0.42f;
                cv.drawCircle(cx, cy, r, p);
                cv.drawLine(cx + r * 0.72f, cy + r * 0.72f, w * 0.86f, h * 0.86f, p);
            }
        };
    }

    /** 选中标签底下那条金色粗线。 */
    private android.graphics.drawable.Drawable tabUnderline() {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(Ui.GOLD_DARK);
        g.setCornerRadius(Ui.dp(this, 3));
        return g;
    }

    /**
     * 曲集卡片：封面 + 曲集名 + 副标题（出版社或乐曲数）。
     * 封面取 assets/covers/&lt;cover_uuid&gt;.jpg（原版 CDN 的同一张图，
     * 已缩到 216px 宽随包发布），没有就用默认封面。
     */
    private View albumCard(final SheetCatalog.Album alb, int cardW) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), 0);
        card.setOnClickListener(v -> openAlbum(alb));

        ImageView cover = new ImageView(this);
        Bitmap cb = null;
        if (alb.cover != null && !alb.cover.isEmpty()) {
            // 卡片宽度就是显示宽度（px），按它采样：大屏卡片能到 300+px，
            // 统一按 120px 解会糊，统一按原图解又太占堆
            cb = Ui.coverBitmap(this, alb.cover, cardW);
        }
        if (cb == null) cb = Ui.assetBitmap(this, DEFAULT_COVER);
        cover.setImageBitmap(cb);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setAdjustViewBounds(true);
        // 封面 1:1（原版封面就是正方形）
        card.addView(cover, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, cardW));

        TextView t = new TextView(this);
        t.setTypeface(Ui.bodyFont(this));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(this, 13f));
        t.setTextColor(Ui.INK);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setLineSpacing(0, 1.05f);
        t.setText(alb.name);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ui.dp(this, 6);
        card.addView(t, tlp);

        String sub = alb.sheets.size() > 0
                ? (alb.sheets.size() + " 首") : (alb.publisher.isEmpty() ? "" : alb.publisher);
        if (!sub.isEmpty()) {
            TextView s = new TextView(this);
            s.setTypeface(Ui.bodyFont(this));
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(this, 11f));
            s.setTextColor(Ui.INK_SOFT);
            s.setSingleLine(true);
            s.setEllipsize(TextUtils.TruncateAt.END);
            s.setText(sub);
            card.addView(s, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        return card;
    }

    /** 打开曲集详情页（该曲集下的乐曲列表）。 */
    private void openAlbum(SheetCatalog.Album alb) {
        Intent it = new Intent(this, AlbumDetailActivity.class);
        it.putExtra(AlbumDetailActivity.EXTRA_ALBUM_UUID, alb.uuid);
        it.putExtra(AlbumDetailActivity.EXTRA_CATEGORY_NAME, navName(mNavIndex));
        // 调试 extra 一路透传到播放页（见 open()）：--ez selffeed true 自喂演奏
        if (getIntent().getBooleanExtra("selffeed", false)) {
            it.putExtra("selffeed", true);
        }
        startActivity(it);
    }

    /** 空态：原版是左侧一个小音符卡通 + 两句提示，这里用文字简化。 */
    private View emptyState() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(0, Ui.dp(this, 80), 0, 0);

        TextView face = new TextView(this);
        face.setText("♪");
        face.setTypeface(Ui.titleFont(this));
        face.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(this, 56f));
        face.setTextColor(Ui.alpha(Ui.INK_SOFT, 0.5f));
        face.setGravity(Gravity.CENTER);
        box.addView(face);

        TextView t = new TextView(this);
        t.setText("空空如也~");
        t.setTypeface(Ui.titleFont(this));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(this, 18f));
        t.setTextColor(Ui.INK_SOFT);
        t.setGravity(Gravity.CENTER);
        box.addView(t);

        String tip;
        if (mNavIndex == NAV_RECENT) {
            tip = "打开一首曲子就会记在这里\n越晚弹的排在越上面";
        } else if (mNavIndex == NAV_FAV) {
            tip = mFavTab == FAV_ALBUMS
                    ? "在曲集卡片上点心形\n这里就会多一本"
                    : "在乐曲列表里点心形\n这里就会多一首";
        } else if (catalogCategory() != null) {
            tip = "这个标签下还没有曲集";
        } else {
            tip = "「" + navName(mNavIndex) + "」的内容在原版里走服务端，\n离线版只显示已同步到设备的曲集";
        }
        TextView s = new TextView(this);
        s.setText(tip);
        s.setTypeface(Ui.bodyFont(this));
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(this, 13f));
        s.setTextColor(Ui.INK_SOFT);
        s.setGravity(Gravity.CENTER);
        s.setPadding(0, Ui.dp(this, 8), 0, 0);
        box.addView(s);

        return box;
    }

    /**
     * 乐曲行副标题「曲集名｜作曲家」。曲集在前是既定要求；
     * 分隔符用**全角细竖线**「｜」（单字符，比「 · 」= 空格+点+空格更窄，
     * 不占额外宽度），由 {@link #linkAlbumName} 单独上金色，成为一条精致的界线。
     *
     * ⚠ 要凸显的是**作曲家**（深棕 + 斜体），不是曲集名：曲集名保持主棕 +
     * 下划线的链接样式即可，别再加深/加粗。
     * span[0..1] 是曲集名区间；分隔符与作曲家区间由 linkAlbumName 按"紧跟
     * 曲集名的那个『｜』"推算，调用方不必多传参数。
     *
     * 收藏 / 最近打开 / 搜索结果三处共用，避免各拼一遍、span 位置算错。
     */
    private static String buildSubtitle(String composer, SheetCatalog.Album alb, int[] span) {
        span[0] = -1;
        span[1] = -1;
        StringBuilder b = new StringBuilder();
        if (alb != null && alb.name != null && !alb.name.isEmpty()) {
            span[0] = b.length();
            b.append(alb.name);
            span[1] = b.length();
        }
        if (composer != null && !composer.isEmpty()) {
            if (b.length() > 0) b.append("｜");
            b.append(composer);
        }
        return b.length() == 0 ? "本地曲谱" : b.toString();
    }

    /**
     * 只把副标题里的**曲集名那几个字**做成链接：点它进曲集页，
     * 点作曲家 / 行内其它地方仍然打开曲谱（父行的默认行为）。
     *
     * 为什么不用 ClickableSpan + LinkMovementMethod：它会把整个 TextView 变成
     * 可点击并吞掉全部触摸事件，父行的 OnClickListener 就再也收不到点击了
     * （连点作曲家都打不开曲谱）。这里改用 OnTouchListener：
     *   ACTION_DOWN 自己接管（才能收到 ACTION_UP；返回 false 的话父 ViewGroup
     *   会抢走后续事件），ACTION_UP 用 Layout 算出点中的字符下标，
     *   落在曲集名区间就开曲集页，否则手动 performClick() 父行。
     * 父 ScrollView 在 MOVE 超过 slop 时仍会拦截（子 View 收到 CANCEL），
     * 所以列表照常能滚。
     */
    private void linkAlbumName(TextView a, final int[] span,
                               final SheetCatalog.Album alb, final View row) {
        if (alb == null || span[0] < 0 || span[1] <= span[0]) return;
        SpannableString text = new SpannableString(a.getText());
        if (span[1] > text.length()) return;
        // 曲集名：主棕 + 下划线链接样式，**不加粗、不加深**。
        text.setSpan(new ForegroundColorSpan(Ui.BROWN), span[0], span[1],
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new UnderlineSpan(), span[0], span[1],
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        // 分隔符「｜」单独上金色做成细界线；作曲家用深棕 + 斜体凸显。
        // 两者都只改字符样式，不加 padding / 字号 / 背景，整行不占更多空间。
        if (span[1] < text.length() && text.charAt(span[1]) == '｜') {
            int sepEnd = span[1] + 1;
            text.setSpan(new ForegroundColorSpan(Ui.GOLD_DARK),
                    span[1], sepEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (sepEnd < text.length()) {
                text.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.ITALIC),
                        sepEnd, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                text.setSpan(new ForegroundColorSpan(Ui.BROWN_DARK),
                        sepEnd, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        a.setText(text);

        final float[] down = new float[2];
        a.setOnTouchListener((v, ev) -> {
            int action = ev.getAction();
            if (action == MotionEvent.ACTION_DOWN) {
                down[0] = ev.getX();
                down[1] = ev.getY();
                return true;
            }
            if (action == MotionEvent.ACTION_UP) {
                float dx = ev.getX() - down[0], dy = ev.getY() - down[1];
                int slop = ViewConfiguration.get(v.getContext()).getScaledTouchSlop();
                Layout layout = a.getLayout();
                if (Math.abs(dx) <= slop && Math.abs(dy) <= slop && layout != null) {
                    int x = (int) ev.getX() - a.getTotalPaddingLeft();
                    int y = (int) ev.getY() - a.getTotalPaddingTop();
                    int line = layout.getLineForVertical(y);
                    int off = layout.getOffsetForHorizontal(line, x);
                    // 单行 ellipsize 会把曲集名截断，右界按实际可见的末尾夹一下
                    int end = Math.min(span[1], layout.getLineEnd(line));
                    if (off >= span[0] && off < end) {
                        openAlbum(alb);
                        return true;
                    }
                }
                // 没点在曲集名上：交回给整行，仍触发行点击。
                if (Math.abs(dx) <= slop && Math.abs(dy) <= slop) row.performClick();
            }
            return true;   // CANCEL / 其它：自己收掉，别让父行误判成点击
        });
    }

    /**
     * 「最近打开」的一行：封面 + 曲名 / 副标题。
     *
     * 存档里只留 uuid 和 played，曲名 / 作曲家 / 封面全部现从目录取 ——
     * 这样目录更新了列表跟着变，也不会出现"旧存档记着老名字"的问题。
     * 目录里查不到（理论上不该发生）就退回显示 uuid 前 8 位，至少不会空白。
     */
    private View recentRow(final String uuid) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));

        SheetCatalog.Sheet catSheet = SheetCatalog.sheetByUuid(this, uuid);
        SheetCatalog.Album catAlbum = SheetCatalog.albumOfSheet(this, uuid);

        ImageView thumb = new ImageView(this);
        Bitmap cb = null;
        if (catAlbum != null && catAlbum.cover != null && !catAlbum.cover.isEmpty()) {
            cb = Ui.coverBitmap(this, catAlbum.cover, Ui.dp(this, 56));
        }
        if (cb == null) cb = Ui.assetBitmap(this, DEFAULT_COVER);
        thumb.setImageBitmap(cb);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Ui.frame(this, thumb);
        r.addView(thumb, new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(Ui.dp(this, 14), 0, 0, 0);

        final String title = catSheet != null ? catSheet.displayName()
                : uuid.substring(0, Math.min(8, uuid.length()));
        TextView t = new TextView(this);
        t.setTypeface(Ui.bodyFont(this));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(this, 16f));
        t.setTextColor(Ui.INK);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setText(title);
        texts.addView(t);

        // 第二行只放 作曲家 · 所属曲集（曲集名是链接，见 linkAlbumName）
        final long played = LibraryStore.get(this).playedAt(uuid);
        final int[] albumSpan = new int[2];
        final String sub = buildSubtitle(
                catSheet != null ? catSheet.composer : null, catAlbum, albumSpan);
        TextView a = new TextView(this);
        a.setTypeface(Ui.bodyFont(this));
        a.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(this, 12f));
        a.setTextColor(Ui.INK_SOFT);
        a.setSingleLine(true);
        a.setEllipsize(TextUtils.TruncateAt.END);
        a.setText(sub);
        linkAlbumName(a, albumSpan, catAlbum, r);
        texts.addView(a);

        r.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 「1 分钟前」：右侧固定一段宽度、文字自身右对齐。最右新增收藏心按钮后
        // 时间整体向左挪，但不会变成左对齐散在中间。
        TextView when = new TextView(this);
        when.setTypeface(Ui.bodyFont(this));
        when.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(this, 12f));
        when.setTextColor(Ui.alpha(Ui.INK_SOFT, 0.9f));
        when.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        when.setMinWidth(Ui.dp(this, 76));
        when.setSingleLine(true);
        when.setText(relTime(played));
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wlp.leftMargin = Ui.dp(this, 10);
        // 时间文字在心形的**左边**：要拉开两者距离必须设 rightMargin。
        // 设 leftMargin 只会拉开它与曲名的距离，和心形毫无关系；当前间距用 rightMargin。
        wlp.rightMargin = Ui.dp(this, 10);
        r.addView(when, wlp);

        // 行尾收藏心：与「我的收藏→乐曲」「搜索结果」用同一套图标和接口，
        // 点它只切收藏状态，不触发整行的打开曲谱（ImageView 自己消费点击）。
        // 和项目里所有收藏入口一样**不弹提示** —— 图标变化本身就是反馈。
        final ImageView heart = new ImageView(this);
        heart.setScaleType(ImageView.ScaleType.FIT_CENTER);
        heart.setImageBitmap(Ui.favBitmap(this,
                LibraryStore.get(this).isFavSheet(uuid), false));
        heart.setPadding(Ui.dp(this, 6), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));
        heart.setOnClickListener(v -> {
            LibraryStore lib = LibraryStore.get(this);
            lib.toggleSheet(uuid);
            final boolean on = lib.isFavSheet(uuid);
            heart.setImageBitmap(Ui.favBitmap(this, on, false));
        });
        r.addView(heart, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));

        final String enc = SheetFetcher.localEnc(this, uuid);
        if (enc != null) {
            r.setOnClickListener(v -> open(enc, title));
        }

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(r, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View line = new View(this);
        line.setBackgroundColor(Ui.alpha(Ui.INK_SOFT, 0.35f));
        wrap.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return wrap;
    }

    // ---------------- 数据 ----------------

    private void prepare() {
        new Thread(() -> {
            try {
                AppAssets.install(this);
            } catch (Throwable t) {
                Log.e(TAG, "解包资产失败", t);
                runOnUiThread(() -> mHeadSub.setText("解包失败：" + t));
                return;
            }
            // 不扫盘：曲谱只通过「曲集目录」和「最近打开」两个入口出现，
            // 标题由打开时记进 SheetStore。
            runOnUiThread(() -> {
                mReady = true;
                renderNav();
                renderContent();
                // 调试入口：am start -n com.magicpig.piano/.HomeActivity --es enc <谱路径>
                //   [--ez selffeed true] [--ez autotrial true] [--ei startm M ...]
                // 带 enc 时直接进播放页，省去在界面里点（演示/回归用）
                String enc = getIntent().getStringExtra(PlayerActivity.EXTRA_ENC);
                // 曲名要一起转过去：只传 enc 时播放页拿不到目录名就退回解 XML，
                // 「刚刚好」就会被 XML 里残留的 work-title 显示成「一生有你」，
                // 排查时容易误以为是曲库数据错了。
                if (enc != null && !enc.isEmpty()) {
                    open(enc, getIntent().getStringExtra(PlayerActivity.EXTRA_TITLE));
                }
            });
        }, "prepare").start();
    }

    private static String relTime(long t) {
        if (t <= 0) return "未知";
        long diff = System.currentTimeMillis() - t;
        if (diff < 0) diff = 0;
        long min = diff / 60000L;
        if (min < 1) return "刚刚";
        if (min < 60) return min + " 分钟前";
        java.text.SimpleDateFormat hm =
                new java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA);
        java.text.SimpleDateFormat md =
                new java.text.SimpleDateFormat("MM-dd", java.util.Locale.CHINA);
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        long today0 = c.getTimeInMillis();
        if (t >= today0) return "今天 " + hm.format(new java.util.Date(t));
        if (t >= today0 - 86400000L) return "昨天 " + hm.format(new java.util.Date(t));
        return md.format(new java.util.Date(t));
    }

    private void open(String enc, String title) {
        // 调试通路排查：主页收到的 extra 与实际透传的值都记一行
        Log.i(TAG, "open(): 收到 selffeed=" + getIntent().getBooleanExtra("selffeed", false)
                + " autotrial=" + getIntent().getBooleanExtra("autotrial", false)
                + " bundle=" + getIntent().getExtras());
        Intent it = new Intent(this, PlayerActivity.class);
        it.putExtra(PlayerActivity.EXTRA_ENC, enc);
        if (title != null && !title.isEmpty()) {
            it.putExtra(PlayerActivity.EXTRA_TITLE, title);
        }
        // 调试 extra 原样透传（如 --ez selffeed true 自喂演奏），
        // 正常从桌面进没有这些 extra，行为不变。
        if (getIntent().getBooleanExtra("selffeed", false)) {
            it.putExtra("selffeed", true);
        }
        // 演示用：起点定位 + 自动开演（见 PlayerActivity 里的同名 extra 注释）
        if (getIntent().getBooleanExtra("autotrial", false)) {
            it.putExtra("autotrial", true);
        }
        // 调试：演奏时保留单音光标（对比"整小节高亮"和"单音胶囊"）
        if (getIntent().hasExtra("nc")) it.putExtra("nc", getIntent().getBooleanExtra("nc", false));
        // 调试：忽略上次光标位置，等价于"清掉进度缓存"
        if (getIntent().getBooleanExtra("nocursor", false)) it.putExtra("nocursor", true);
        // 调试：直接造一个选段（验证选段持久化）
        if (getIntent().getIntExtra("secsm", -1) >= 0) {
            it.putExtra("secsm", getIntent().getIntExtra("secsm", -1));
            it.putExtra("secem", getIntent().getIntExtra("secem", -1));
            it.putExtra("secso", getIntent().getIntExtra("secso", 0));
            it.putExtra("seceo", getIntent().getIntExtra("seceo", 0));
        }
        // 调试：延迟多久才点「演奏」／开始后多久自动停（验证切换不刷新用）
        it.putExtra("trialdelay", getIntent().getIntExtra("trialdelay", 2500));
        it.putExtra("autostop", getIntent().getIntExtra("autostop", 0));
        if (getIntent().getBooleanExtra("feedfrom0", false)) {
            it.putExtra("feedfrom0", true);
        }
        if (getIntent().getBooleanExtra("feedsilence", false)) {
            it.putExtra("feedsilence", true);
        }
        // 调试：自喂音频升高 N 个半音（制造音高错/节奏错，看错音在 UI 上什么样）
        int detune = getIntent().getIntExtra("detune", 0);
        if (detune != 0) {
            it.putExtra("detune", detune);
            // 从第几块开始变调（演示"前半弹对、后半弹错"）
            it.putExtra("detuneafter", getIntent().getIntExtra("detuneafter", 0));
        }
        // 调试：演奏走 UFP（原版通路）
        if (getIntent().getBooleanExtra("ufptrial", false)) {
            it.putExtra("ufptrial", true);
        }
        // 调试：指定手型（0 合手 / 1 右手 / 2 左手）
        if (getIntent().hasExtra("hand")) {
            it.putExtra("hand", getIntent().getIntExtra("hand", 0));
        }
        // 调试：指定终点音符
        if (getIntent().getIntExtra("endm", -1) >= 0) {
            it.putExtra("endm", getIntent().getIntExtra("endm", -1));
            it.putExtra("endo", getIntent().getIntExtra("endo", 0));
        }
        // 调试：练习区间柔光遮罩探针
        int sm = getIntent().getIntExtra("startm", -1);
        if (sm >= 0) {
            it.putExtra("startm", sm);
            it.putExtra("startv", getIntent().getIntExtra("startv", 0));
            it.putExtra("starto", getIntent().getIntExtra("starto", 0));
        }
        startActivity(it);
    }

    /**
     * 从播放页返回时刷新：刚才弹的那首要冒到「最近打开」最上面，
     * 收藏状态也可能变了。
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (mReady) renderContent();
    }

    @Override
    protected void onDestroy() {
        // 防抖任务持有 Activity，销毁时清掉，免得 250ms 后又碰已死的视图
        if (mSearchTask != null) {
            mSearchDelay.removeCallbacks(mSearchTask);
            mSearchTask = null;
        }
        // 分页加载的延迟任务同理：不摘掉的话，销毁后 120ms 还会回来碰 mScroll/适配器
        if (mScroll != null) mScroll.removeCallbacks(mLoadMore);
        super.onDestroy();
    }
}
