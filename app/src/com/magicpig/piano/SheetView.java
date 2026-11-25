package com.magicpig.piano;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 曲谱渲染视图：把原 App 的 sheetShow（VexFlow MusicXML 渲染器）装进 WebView。
 *
 * 【复用而非重写】原 App 的 flutter_assets/sheetShow/ 是一个自包含前端包：
 *   - sheet_show.html  页面骨架
 *   - jsbridge.js      WebViewJavascriptBridge 实现
 *   - sheet_show.js    1.6MB 的 VexFlow 渲染主程序（含谱面、光标、分段等全部逻辑）
 * 它渲染用的是 SVG path，不依赖外部音乐字体；全文只有一处外链
 * （file.17lianqin.cn 的提示 GIF），不申请网络权限时静默失败，不影响离线使用。
 *
 * 三个文件在装载时内联进一个 HTML 字符串，避免 file:// 下跨文件加载 script 的限制。
 *
 * 桥接协议（见 jsbridge.js）：
 *   JS → 原生：window.flutter_inappwebview.callHandler("onMessageReceived", <URI 编码的 JSON>)
 *   原生 → JS：WebViewJSBridge.onMessageReceived(<URI 编码的 JSON>)
 * 报文形如 {"id":N,"type":"request"|"response","action":"drawSheet","data":{...},
 *          "resolved":bool,"rejected":bool}
 *
 * 【点谱面定位】点击坐标→小节 的换算全部由 JS 侧完成：渲染器命中音符后通过
 * onNoteClick2 上报 {measure, offset, voice}（native 侧 0-based）。
 * 这条通道是原版 App 自己用的，且自动处理页面滚动与双指缩放；不要在原生侧
 * 用小节线坐标反推屏幕区间 —— 谱面一滚动（播放时光标跟随会自动滚）映射
 * 就整体失效，点哪儿都跳错位置。
 */
public class SheetView extends WebView {

    /**
     * 演奏结果的符号画法：
     *   0 = ✓ 和 ✗ 都画（原版行为）
     *   1 = **只画红叉**（当前）：对音不画，弹错/漏弹/其它问题一律在音符上画红叉；
     *       所有符头保持黑色，避免和「合手/左手/右手」的灰化染色打架。
     *   2 = 都不画，只靠符头颜色（已废弃：灰漏弹太难发现，红错音又和手型冲突）
     *
     * 用户明确要黑音符 + 红叉：红叉在演奏过程中保留到下一次出分作提醒；
     * 出分那一刻先清后画，只显示本轮结果。
     */
    private static final int RESULT_MARK_STYLE = 1;

    private static final String TAG = "SheetView";

    /**
     * 注入的小工具：把 base64 还原成 UTF-8 字符串。
     *
     * ⚠ 别改回"逐字节拼 %XX + decodeURIComponent"：那个写法要求字节序列是
     * **合法 UTF-8**，一旦谱包 XML 里有非法字节
     * 会抛 URIError: URI malformed，整个 drawSheet 中断、onSheetOverallLoaded
     * 永不回调 —— 现象是**进曲谱页一直白屏**，日志只有"渲染回调超时"。
     * TextDecoder 默认 fatal=false，坏字节替换成 U+FFFD，不会抛；而且是原生解码，
     * 比"拼 180 万字符 %XX 再解码"快一个量级（60 万字节的谱面很常见）。
     * 外层 try/catch 只兜住极老的 WebView（没有 TextDecoder）。
     */
    private static final String HELPER_JS =
            "<script>function __fromB64(b){var r=atob(b),n=r.length,a=new Uint8Array(n),i;"
            + "for(i=0;i<n;i++)a[i]=r.charCodeAt(i)&255;"
            + "var s;try{s=new TextDecoder('utf-8').decode(a);}"
            + "catch(e){s='';for(i=0;i<n;i++)s+=String.fromCharCode(a[i]);}"
            // ⚠ 必须把 % 转义成 %25：jsbridge 的 _onMessageReceived 第一行会对入参
            // 再做一次 decodeURIComponent（原版 Dart 侧发消息前做过 URI 编码）。
            // 我们传的是明文 JSON，谱面 XML 里只要有一个 %，那里就抛
            // URIError: URI malformed —— 整条 drawSheet 中断、页面永久白屏。
            // 转义后 decodeURIComponent
            // 恰好还原成 %，其余字符（含中文）它本来就原样返回，往返无损。
            + "return s.replace(/%/g,'%25');}"
            // 全局未捕获异常打一行带**调用栈**的日志：渲染器内部的异常只会被它
            // 自己的 try 吞成 "on error ++ errorMsg: ..."，没有栈就无从定位。
            // 只在出错时触发，零开销。
            + "window.addEventListener('error',function(ev){try{console.log('WB_UNCAUGHT '"
            + "+(ev.message||'')+' @'+(ev.filename||'')+':'+(ev.lineno||0)+':'+(ev.colno||0)"
            + "+' stack='+((ev.error&&ev.error.stack)||''));}catch(x){}});"
            + "</script>";

    public interface Listener {
        /**
         * 每批行绘制完成。
         *
         * @param rows        已绘制的行数（累加）。首屏可见时就能拿到
         * @param lastMeasure 这一批画完时、页面上**最后一个小节**的索引（0-based）；
         *                    JS 侧取不到时是 -1。载入框用它显示"当前/目标"进度
         */
        void onRowsDrawn(int rows, int lastMeasure);

        void onSheetRendered();

        /**
         * 画面**真的**停止变化了（绘制与重排都停了）。
         *
         * 和 {@link #onSheetRendered()} 不是一回事：真机实测 onSheetRendered 八次
         * 进谱只来一次，而且大谱面（保卫黄河）它 4.6s 就报"完成"，画面其实还在
         * 到 4~6 秒才真的稳定。这个回调由 JS 观察 DOM 变化得出，连续 600ms 不变才算数，
         * 用来决定"什么时候可以放心画曲首光标"（画早了会被后续绘制擦掉 = 闪）。
         */
        default void onSheetSettled() {
        }

        void onJsLog(String msg);

        /**
         * 点谱面时回调。measure/offset/voice 是 JS 从点击位置直接算出的
         * native 侧 0-based 索引（与原版 App 同一通道），可直接喂给
         * SfeInterface.setPlayPositionWithoutCountDown。
         */
        void onNoteClick(int measure, int offset, int voice);

        /** 渲染器拒绝了选段范围（例如区间里音符太少 / 位置不合法）。 */
        /** 选段：起点已定（渲染器画好左括号了）。 */
        default void onSectionBegin() {
        }

        /** 选段：起终点都定了（新选完，或拖动微调完）。start/end 形如 {measure,voice,offset}。 */
        default void onSectionChanged(JSONObject start, JSONObject end) {
        }

        /**
         * 选段确认后，渲染器用**真实时长**算出的"段内包含哪些音"。
         * 每项是 "measure|offset|voice"。这是唯一跨声部也正确的依据 ——
         * offset 只是"声部内序号"，左右手各自编号，单独拿来比必然出错。
         */
        default void onSectionKeys(JSONArray keys) {
        }

        /**
         * WebView 的渲染进程没了（系统回收 / GPU 崩）。
         *
         * 这个 WebView 实例之后不可再用 —— 表现就是**整页白屏且刷新也没用**。
         * 只能让上层重建页面（销毁 WebView 再开一次）。
         */
        default void onRenderProcessGone() {
        }
    }

    private final Handler mUi = new Handler(Looper.getMainLooper());
    /**
     * 切回预览态后的"非强制滚动"冻结窗口。要盖住 initPage 回执、光标样式回执
     * (+120ms 补画)、在飞帧的 500ms 节流补发、以及分批 followPitch2 这些迟到帧；
     * 期间 forceScroll=true 的主动滚动照常可用，长一点也不会挡住用户操作。
     */
    private static final long AUTO_SCROLL_FREEZE_MS = 1200;
    private final Runnable mUnfreezeScroll = () -> freezeAutoScroll(false);
    private final Map<Integer, Callback> mPending = new HashMap<>();
    /**
     * 跨轮累积的红叉：key = measure/voice/offset，value = 待画的 voice JSON。
     * 出分时只替换「本轮判定覆盖的小节」内的旧标记，区间外的红叉保留 ——
     * 这样"把光标定位到某个乐句重弹一遍"才不会把别处的错音标记一起抹掉。
     */
    private final java.util.LinkedHashMap<String, JSONObject> mWrongMarks =
            new java.util.LinkedHashMap<>();
    private int mSeq = 0;
    /** 已销毁：之后任何 JS 调用都直接丢弃（喂音频线程比 Activity 活得久） */
    private volatile boolean mDestroyed;
    /** 行批次日志的起始时刻（仅诊断用，0 = 本次还没打过） */
    private long mRowLogT0;
    /** 渲染器已经画到的小节索引（0-based，-1 = 一批都还没画完） */
    private volatile int mLastDrawnMeasure = -1;
    /**
     * 渲染器当前实际 zoom（drawSheet 回执里的 current_zoom）。
     *
     * ⚠ 兜底值必须是 {@link #mExpectedZoom}（我们自己下发给 initPage 的那个），
     * **不能是 1.0**：回执万一没带 current_zoom / 解析失败，用 1.0 会让
     * `queryMeasureReady` 里 `y * zoom` 放大 2 倍多，目标小节永远判"不在视口"，
     * 恢复流程就会一直等到超时才解锁。
     */
    private double mSheetZoom = 1.0;
    /** 最近一次下发给渲染器的 zoom（initPage 里算出来的），给上面的兜底用 */
    private double mExpectedZoom = 1.0;
    /** 最后一次光标所在小节（-1 = 还没定位过） */
    private int mLastMeasure = -1;
    /** 最后一次光标位置，退出演奏时用它把胶囊画回原处 */
    private int mLastOffset;
    private int mLastVoice;
    private UserActionListener mUserAction;
    private float mTouchX, mTouchY;
    private boolean mScrollFired;
    /** 判定"用户真的在滚"的位移阈值（px） */
    private static final float SCROLL_SLOP_PX = 24f;

    private Listener mListener;
    private boolean mPageReady;
    private boolean mInitDone;
    private String mQueuedXml;
    private String mQueuedTitle;
    /** 首小节速度标记的文字（已被 TempoMark 从 XML 里摘掉），顶栏显示用 */
    private String mTempoLabel;
    /** 手型：both / left / right。只用于给 CSS 挂 data-hand 类，渲染器永远画黑色 */
    private String mHandType = "both";
    /**
     * 演奏态（只影响 Java 侧逻辑，**不传给渲染器**）。
     *
     * 渲染器恒用 mode="preview"；本字段只做两件事：整小节高亮、演奏态阻止预览光标补画。
     * magic 那套视觉差异（谱线 0.5px、#DFCAB4 浅棕线、字号 39、bottomPadding=0）
     * 只在**重绘**时落地；不切 mode，也不会带入 scrollToMeasure("top") 的 magic
     * 偏移和双谱表光标的 y 公式。
     *
     * 原版 magic 的其它效果我们本来就没在用：呼吸光柱（光标已统一金胶囊）、
     * 弹对染色（当前全黑 + 红叉）、命中星光（未接）、onPageClick（没注册）、
     * levelRange 关卡裁剪（不传）。
     *
     * 这个布尔只影响演奏态：moveCursor2 的 need_highlight_measure，以及阻止 reissueCursor
     * 在演奏中补画预览光标。
     */
    private boolean mPerformMode = false;
    /**
     * 发 initPage 那一刻 WebView 的宽度（px）。
     *
     * 渲染器的布局宽取的是 `window.innerWidth`：WebView 还没布局时是 0，
     * 于是 `scale = 0.9 * (0 / 最宽行)` = 0，整页按 0 宽排出来 —— **白屏**，
     * 而且它不会自己重算。冷启动首帧忙、WebView 布局被推迟时就会踩到，
     * 表现就是"偶发进入曲谱白屏"。onSizeChanged 里拿它判断要不要重绘。
     */
    private int mInitPageAtWidth = -1;
    /** 内联好的整页 HTML，重载时直接复用（避免每次重读 1.6MB JS） */
    private String mHtml;
    /** 进程内共享的 HTML 缓存：buildHtml 只做一次 */
    private static String sHtml;

    public SheetView(Context ctx) {
        super(ctx);
        setup();
    }

    private void setup() {
        getSettings().setJavaScriptEnabled(true);
        getSettings().setDomStorageEnabled(true);
        getSettings().setLoadWithOverviewMode(false);
        getSettings().setUseWideViewPort(true);
        setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                mPageReady = true;
                // ⚠ 不能在 onPageFinished 里**无条件** sendInitPage()：show() 往往还没
                // 被调用（页面就绪早于谱面数据准备好），此时 mQueuedTitle 还是 null →
                // 给渲染器的 name 是空串 → `e.name || isMagicMode` 为假 →
                // 走 else 打 "FATAL: data.name is required."，并且**整个 6656 字符的
                // 初始化块被跳过**（header_height / 左右 padding / bg_color /
                // GlobalState.headerHeight / drawMaxRow 全都没设）。
                // 更糟的是：这次 initPage 会把 mInitDone 置 true，等真正 show() 时
                // 只会走 sendDrawSheet()，再也不会重新 initPage —— 初始化缺失会
                // 贯穿整个会话。所以：没有待渲染的谱面就别发。
                if (mQueuedXml != null) sendInitPage();
                // 页面刚就绪就按当前手型挂上 CSS 类，避免先闪一下"全黑"再淡入灰
                applyHandClass();
            }

            /**
             * 渲染进程被系统回收或崩掉：这个 WebView 实例之后画不出任何东西
             * （白屏，滚动/缩放都没反应）。返回 true 表示"已处理"，否则系统会
             * 直接把整个 App 杀掉。这里交给上层重建页面。
             */
            @Override
            public boolean onRenderProcessGone(WebView view,
                    android.webkit.RenderProcessGoneDetail detail) {
                Log.e(TAG, "WebView 渲染进程消失（crashed=" + detail.didCrash() + "）");
                if (mListener != null) mListener.onRenderProcessGone();
                return true;
            }
        });
        // 把 JS 里的 console / 报错也打到 logcat，排查注入脚本问题用
        setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                Log.d("SheetJS", "console: " + m.message() + " (line " + m.lineNumber() + ")");
                return true;
            }
        });
        addJavascriptInterface(this, "flutter_inappwebview");

        try {
            // 内联 HTML 与 App 同生命周期：缓存成静态的，第二次进播放页就不用
            // 再读一遍 1.6MB 的 JS 重新打补丁/拼样式（每次省几十毫秒）
            if (sHtml == null) {
                sHtml = buildHtml(getContext());
                Log.d(TAG, "HTML " + sHtml.length() + " 字符（首次构建）");
            }
            mHtml = sHtml;
            loadDataWithBaseURL("file:///android_asset/sheetShow/", mHtml,
                    "text/html", "UTF-8", null);
        } catch (Exception e) {
            Log.e(TAG, "装载曲谱页面失败", e);
        }
    }

    /**
     * WebView 尺寸就绪/变化时的补救。
     *
     * 见 {@link #mInitPageAtWidth}：初始化时宽度为 0 会让渲染器把整页排成 0 宽（白屏），
     * 它自己不会重算。等真实尺寸下来后重发一次 initPage + drawSheet 就能恢复。
     * 正常路径（初始化时已有尺寸）这里什么都不做。
     */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) return;
        if (mInitPageAtWidth == 0 && mInitDone && mPageReady && mQueuedXml != null) {
            Log.w(TAG, "WebView 尺寸就绪（" + w + "x" + h + "），重发 initPage 修正 0 宽布局");
            mInitPageAtWidth = w;
            mInitDone = false;
            post(this::sendInitPageNow);
        }
    }

    /**
     * 把 jsbridge.js 与 sheet_show.js 内联进 HTML。
     *
     * ⚠ 下面每个补丁的匹配串都是对渲染器压缩代码的**原位字符串替换**。改这些串时必须同步
     * `tools/check_sheet_patches.py` 的 PATCHES 清单，否则构建期查的仍是旧锚点、照样通过，
     * 直到启动时才以空白页 + 异常暴露。
     */
    private static String buildHtml(Context ctx) throws Exception {
        String html = readAsset(ctx, "sheetShow/sheet_show.html");
        String bridge = readAsset(ctx, "sheetShow/jsbridge.js");
        String app = readAsset(ctx, "sheetShow/sheet_show.js");
        java.util.List<String> missed = new java.util.ArrayList<>();

        // ---- 修「重绘后光标永久消失」的渲染器 bug ----
        // 渲染器的光标元素是惰性创建并缓存引用的：
        //   x.cursorPreview || ((x.cursorPreview = createElementNS(...)), ...)
        // 而 drawSheet 重绘时 SVG 被整体清空，缓存引用却不清 —— 之后一直更新
        // 一个已脱离文档的节点，光标就再也看不见了（切手型会触发重绘，所以
        // 切完手型后无论哪种模式都没光标）。
        // 补丁：创建前先判断引用是否还挂在**文档**上，已脱离就置空重建。
        //
        // 注意必须用 document.contains 而不是 parentNode：重绘时渲染器是把
        // 整个图层 <g> 从 SVG 根上摘下来再挂新的，被摘掉的元素 parentNode
        // 仍然非空（还指向那个已脱离的旧图层），只查 parentNode 会误判成
        // "还在文档里"，于是继续更新一个看不见的节点；表现就是切完手型光标永久消失。
        int litIdx = app.indexOf("x.cursorPreview||((x.cursorPreview=document.createElementNS(i,\"g\"))");
        if (litIdx >= 0) {
            app = app.replace(
                    "x.cursorPreview||((x.cursorPreview=document.createElementNS(i,\"g\"))",
                    "(x.cursorPreview&&!document.contains(x.cursorPreview)&&(x.cursorPreview=null)),"
                            + "x.cursorPreview||((x.cursorPreview=document.createElementNS(i,\"g\"))");
        } else {
            missed.add("光标重建");
        }
        Log.i(TAG, "光标补丁 " + (litIdx >= 0 ? "已应用" : "未命中（JS 结构变了？）"));

        // ---- 补丁：视口已在顶部时，别把第一行的 firstRowY 留白顶掉 ----
        //
        // 渲染器 scrollToMeasure / scrollMeasureToTop / moveCursor2 最终都汇到
        // be(e,t) 里的这一句：align="top" 时目标 Y = measureY*scale + viewerTop。
        // 第一行 measureY 就是 score_padding_top(8)，于是"把第一行顶到视口顶部"
        // 实际是滚 ~5 CSS px（真机约 12 设备px）—— 用户看到的"谱面微小上移"。
        // 载入上次进度（落在第一行）、开始选段（段含第一行）、进谱画曲首光标
        // 都会走它，所以只在 Java 侧绕开一两处是堵不住的，必须在这里收口。
        //
        // 条件刻意收得很窄，只在"目标小节在第一行 && 视口本来就在顶部"时跳过滚动：
        //   · n.measureY < 60 → 只有第一行（firstRowY=8）；第二行至少隔一个行高
        //   · scrollTop < 40  → 用户若已滚到中段，点回曲首仍要正常滚回来
        // 短路后 checkDisplay() 照常执行，只是不再发起那次无意义的滚动动画。
        String rowTopScrollKey = "(0,u.scrollToScreenPosition)(document.body,l.y+e,s,i,a,t)";
        if (app.contains(rowTopScrollKey)) {
            // 额外加一个 __wbNoScroll：Java 要"只挪光标、别动视口"时（点击定位、判稳重画）
            // 不能只靠 forceScroll=false —— H5 里它只在"最近 2s 碰过屏幕"时才跳过滚动，
            // 过 2s 照样把当前小节顶到视口顶部。这就是"光标定位到第二行、整页加载完
            // 它自己上移到第一行"的真凶。
            String rowTopScrollNew = "((\"top\"===i&&(window.__wbNoScroll"
                    + "||(n.measureY<60"
                    + "&&(document.scrollingElement?document.scrollingElement.scrollTop:window.scrollY)<40)))"
                    + "||" + rowTopScrollKey + ")";
            app = app.replace(rowTopScrollKey, rowTopScrollNew);
            Log.i(TAG, "首行 top 滚动补丁已应用（不再顶掉 firstRowY 留白）");
        } else {
            Log.w(TAG, "首行 top 滚动补丁未命中（JS 结构变了？）");
            missed.add("首行 top 滚动");
        }

        // ---- 补丁：moveCursor2 支持 wb_no_scroll（本次只挪光标、不滚视口）----
        // Java 侧 scroll=false 的调用（点击定位 / 判稳重画 / 退出演奏恢复）都会带
        // wb_no_scroll=true；handler 里把它变成一次性 __wbNoScroll，交给上面的
        // scrollToMeasure 补丁短路滚动。moveCursor 是同步的，所以末尾/异常都清掉。
        String mcHead = "try{q.GlobalState.shareInstance.lastCursors=[e],";
        String mcTail = "(0,ae.moveCursor)(e)}catch(e){B(\"Error - Move Cursor: \"+e)}";
        if (app.contains(mcHead) && app.contains(mcTail)) {
            app = app.replace(mcHead,
                    "try{window.__wbNoScroll=e.wb_no_scroll?1:0,"
                            + "q.GlobalState.shareInstance.lastCursors=[e],");
            app = app.replace(mcTail,
                    "(0,ae.moveCursor)(e),window.__wbNoScroll=0}"
                            + "catch(e){window.__wbNoScroll=0,"
                            + "B(\"Error - Move Cursor: \"+e)}");
            Log.i(TAG, "光标不滚动补丁已应用（wb_no_scroll）");
        } else {
            Log.w(TAG, "光标不滚动补丁未命中（JS 结构变了？）");
            missed.add("moveCursor2 wb_no_scroll");
        }

        // ---- 补丁：给"非强制自动滚动"加一个可冻结开关 ----
        // H5 里 scrollTo() 与 scrollToScreenPosition() 各有一份几乎相同的抑制判断：
        //   !forceScroll && 最近 2s 碰过 WebView → 跳过这次滚动
        // 反过来说：演奏时几秒不碰屏幕，之后每次 forceScroll=false 的
        // moveCursor2 / followPitch2 都**照样滚**（把当前小节顶到视口顶部，实测 ~62px）。
        // 退出演奏的收尾会连发好几条这种帧（initPage 回执里的 restorePreviewCursor、
        // reissueCursor、clearMeasureHighlight、结果标记 followPitch2），于是谱面
        // 先往上小滚一下、再被 Java 的延迟还原拉回来 —— 就是用户看到的上下抖动。
        // 这里把抑制条件扩成"最近碰过 **或** Java 显式冻结"，从源头让这些帧不滚。
        // 只作用于 forceScroll=false：用户点谱面定位、试听起播、滚回曲首这些
        // forceScroll=true 的主动滚动完全不受影响。
        String autoScrollKey = "!o&&u.GlobalState.shareInstance.lastTouchActiveDate"
                + "&&+new Date-u.GlobalState.shareInstance.lastTouchActiveDate"
                + "<1e3*u.GlobalState.shareInstance.doNotScrollAfterTouchActiveSecond";
        if (app.contains(autoScrollKey)) {
            int hits = 0;
            for (int from = 0; (from = app.indexOf(autoScrollKey, from)) >= 0;
                 from += autoScrollKey.length()) {
                hits++;
            }
            String autoScrollNew = "!o&&(window.__wbFreezeAutoScroll"
                    + "||u.GlobalState.shareInstance.lastTouchActiveDate"
                    + "&&+new Date-u.GlobalState.shareInstance.lastTouchActiveDate"
                    + "<1e3*u.GlobalState.shareInstance.doNotScrollAfterTouchActiveSecond)";
            app = app.replace(autoScrollKey, autoScrollNew);
            Log.i(TAG, "自动滚动冻结补丁已应用（命中 " + hits + " 处：scrollTo / scrollToScreenPosition）");
            if (hits != 2) missed.add("自动滚动冻结（需要 2 处，实到 " + hits + "）");
        } else {
            Log.w(TAG, "自动滚动冻结补丁未命中（JS 结构变了？）");
            missed.add("自动滚动冻结");
        }

        // ---- 补丁：逐行绘制改用 setTimeout 调度，给外部 JS 注入留缝隙 ----
        // 渲染器的 deepTraversal 用 MessageChannel 把"画下一批"排成高优先级宏任务：
        //   O ? (I.push(t), O.port2.postMessage(null)) : setTimeout(t, 0)
        // Chromium 里 MessageChannel 的任务会不断插队，evaluateJavascript 被饿死 ——
        // 长曲（保卫黄河 386 小节）实测：点谱面 Java 立即收到，`moveCursor2` 到 JS
        // 执行却排了 **2.67 秒**，用户看到"进度变了、光标不动"。
        // 把 O 置空就自动走 setTimeout(t,0) 分支：浏览器会在任务之间处理其它任务，
        // 注入的 JS 能插队（代价是每轮多 4ms 定时器间隔，整页会慢 1~2 秒，可接受）。
        // 保留 MessageChannel 写法，只是不创建实例；I/O 队列在 O=null 时不会被用。
        String mcSchedKey = "O=\"undefined\"!=typeof MessageChannel"
                + "?new MessageChannel:null,I=[]";
        if (app.contains(mcSchedKey)) {
            app = app.replace(mcSchedKey, "O=null,I=[]");
            Log.i(TAG, "渲染调度补丁已应用（MessageChannel → setTimeout，交互 JS 可插队）");
        } else {
            Log.w(TAG, "渲染调度补丁未命中（JS 结构变了？）");
            missed.add("渲染调度");
        }

        // ---- 补丁：把"每批行画完"转发给 Java ----
        // 渲染器是分批发行的（一行或多行一批），整页画完要 onSheetOverallLoaded；
        // 335 万字符的谱面（李斯特超技 4）整页要 7 秒多，但**首屏那几行**一秒内就出来了。
        // 原版观感快，就是因为它不等整页。这里借 MusicScoreController 现成的
        // addOnDrawMeasuresRowListener 把批次数抛给 Java（P 在这个作用域就是 bridge），
        // 让加载遮罩能在首屏可见时立刻撤掉。
        // 回调参数 (e,t)：e = 这一批的小节 XML id 数组，t = 行序号（0-based）。
        // 顺手把这批最后一小节反查成索引一起报上去 —— 载入框靠它显示
        // 「正在准备第 当前/目标 小节…」，只有一个不动的目标号用户看不出在不在动。
        String drawHook = "F.MusicScoreController.shareInstance.setFinishDrawHandler(function(){"
                + "P.callHandler(\"onSheetOverallLoaded\"";
        String rowsHook = "function(e,t){try{var l=-1;try{var p=D.VexFlowCache.getPart(\"P1\");"
                + "if(p&&e)for(var i=0;i<e.length;i++){var k=p.getIndexWithMeasureXMLId(e[i]);"
                + "if(void 0!==k&&k>l)l=k;}}catch(y){}"
                + "P.callHandler(\"onRowsDrawn\",{row:t,last:l});}catch(x){}}";
        if (app.contains(drawHook)) {
            app = app.replace(drawHook,
                    // 把 bundle 作用域里的 P（jsbridge）暴露到全局：我们用
                    // safeEval 注入的脚本跑在全局作用域，直接写 P 会 ReferenceError，
                    // 回调静默丢掉。
                    "window.__wbCallHandler=function(a,d){try{P.callHandler(a,d);}catch(x){}},"
                            + "F.MusicScoreController.shareInstance.addOnDrawMeasuresRowListener("
                            + rowsHook + "),"
                            + drawHook);
            Log.i(TAG, "分批绘制回调补丁已应用（带当前小节进度）");
        } else {
            Log.w(TAG, "分批绘制回调补丁未命中（JS 结构变了？）");
            missed.add("分批绘制回调");
        }

        // ---- 补丁：速度记号（Andantino ♩=70 这类）上移，避开连音线 ----
        // VexFlow 的 StaveTempo 画在 `stave.getYForTopText(1) + shift_y`，也就是
        // 谱表上方一条固定距离的基线。而跨小节的连音线（slur/tie）会向上拱到
        // 差不多同样的高度 —— 两者一撞就糊成一团（用户截图里的 Andantino 就是）。
        // VexFlow 的 modifier 碰撞处理不管 slur，所以只能整体把 tempo 抬一点。
        // 20 是 VexFlow 单位（会被谱面 scale 缩放，约 10 CSS px）。
        // 不能按"看起来够"去给：这个量要配合首行的顶部留白一起用 ——
        // 首行贴顶（firstRowY=0）时 tempo 距视口顶只有 ~18px，抬多一点就直接被裁掉
        // （试过 -46，整条速度记号消失）。
        String tempoKey = "h=e.getYForTopText(1)+this.shift_y";
        if (app.contains(tempoKey)) {
            app = app.replace(tempoKey, "h=e.getYForTopText(1)+this.shift_y-30");
            Log.i(TAG, "速度记号上移补丁已应用（-30 单位）");
        } else {
            Log.w(TAG, "速度记号上移补丁未命中（JS 结构变了？）");
            missed.add("速度记号上移");
        }

        // ---- 补丁：结果只画红叉，符头一律黑色 ----
        // 渲染器 drawPitchType 的 switch 只认 ok / wrong* / little*，而 UFP 会报
        // miss / extra / skipped 等多种 bias_type。这里把所有非 ok 统一改成 "wrong"
        // 再把颜色参数 o 强制成红色：漏弹、弹错、节奏错都变成同一个红叉。
        // 符头染色发生在调用 drawPitchType 之前（用的是下面的色板，全黑），
        // 所以改 o 只影响叉，不会把音符染红。
        if (RESULT_MARK_STYLE > 0) {
            String glyphKey = "drawPitchType=function(e,t,n,r,o){";
            String emptyG = "document.createElementNS('http://www.w3.org/2000/svg','g')";
            String glyphGuard = RESULT_MARK_STYLE == 2
                    ? "return " + emptyG + ";"
                    : "if(e==='ok')return " + emptyG + ";e='wrong';o='#E0211A';";
            if (app.contains(glyphKey)) {
                app = app.replace(glyphKey, glyphKey + glyphGuard);
                Log.i(TAG, "结果符号画法 = " + RESULT_MARK_STYLE
                        + (RESULT_MARK_STYLE == 2 ? "（不画符号）" : "（只画红叉）"));
            } else {
                Log.w(TAG, "结果符号补丁未命中：JS 结构变了？");
                missed.add("结果符号");
            }
        }

        // ---- 补丁：符头色板全部改黑 ----
        // 原版色板 high/low/miss/extra 会把符头染成红/灰等。问题有两个：
        //   1) 灰色"漏弹"在奶油白底上几乎看不见；
        //   2) 红色"错音"和手型切换的 CSS 灰化/黑色符头混在一起，分不清是判定还是手型。
        // 所有音符保持黑色，只在错的音上画红叉（见上一条补丁）。
        // ColorMap 是 followPitchColorMap / pitchResultMap 的来源，改这里就够了。
        // resultFrameColor 只画音团外框（不是音符），保留暖棕不干扰。
        String themeKey = "{\"high\":\"#EA5353\",\"low\":\"#35CEB3\","
                + "\"resultFrameColor\":\"#F25B5B\",\"miss\":\"#FFBE20\",\"extra\":\"#EA5353\"}";
        String themeNew = "{\"high\":\"#000000\",\"low\":\"#000000\","
                + "\"resultFrameColor\":\"#C08A4A\",\"miss\":\"#000000\",\"extra\":\"#000000\"}";
        if (app.contains(themeKey)) {
            app = app.replace(themeKey, themeNew);
            Log.i(TAG, "符头色板补丁已应用（全黑）");
        } else {
            Log.w(TAG, "符头色板补丁未命中（JS 结构变了？）");
            missed.add("符头色板");
        }

        // 谱线粗细：渲染器的 magic 模式会把谱线/小节线的描边压到 0.5px。
        // 本页恒 preview，但 CSS 仍显式锁 1px，避免以后误切 mode 时线变虚
        // （CSS 优先于 SVG presentation attribute，规则加在下面注入的样式表里）。
        Log.i(TAG, "谱线粗细规则随样式表注入");

        // 页面骨架的三个拼接锚点。缺任何一个都会让 buildHtml 抛异常、播放页空白；
        // 构建期由 tools/check_sheet_patches.py 先校验，这里再做一次启动期兜底。
        if (!html.contains("<head>")) missed.add("HTML <head>");
        if (!html.contains("<script type=\"text/javascript\" src=\"sheet_show.js\"></script>")) {
            missed.add("HTML 渲染器脚本");
        }
        if (!html.contains("</head>")) missed.add("HTML </head>");

        html = html.replace("<head>", "<head>" + HELPER_JS + "<script>" + bridge + "</script>");
        html = html.replace(
                "<script type=\"text/javascript\" src=\"sheet_show.js\"></script>",
                "<script>" + app + "</script>");
        // 原版曲谱页的底色是 #FFFCE8（奶油白），不是纯白。
        // 色值取自原版预览页截图。
        //
        // 同时把页面自带的几行纯文字头隐藏掉：#sheet_name 是 <h2 class="title">，
        // 由 initPage 的 name 参数填进来（渲染器并不读 XML 的 movement-title），
        // 原版预览页顶部是空的、谱表直接从最上面开始，所以这里也不显示 ——
        // 标题已经由曲库页承担，谱面页再来一行大字纯属重复。
        // 注意只隐藏文字元素，不动 #header/#viewer 的结构，避免影响渲染器布局。
        //
        // 【分手灰化用 CSS 做，不重绘】
        // 渲染器自己那套灰化是「画音符时按 staveId 上色」，想换手就得整页重画 ——
        // 谱面会明显刷一下，观感很差，所以这里不重绘，改用 CSS 灰化。
        // 而 DOM 里每一行谱表本来就是独立分组：
        //   g#vf-measuresRow-<行号>-<谱表号>.vf-staveCell
        // 谱表号 1 = 高音谱表（右手），2 = 低音谱表（左手）
        // （依据：渲染器里 "1"==stave?"left":"right" 与 handType 比较后涂灰，
        //  而 handType="right" 时实际灰掉的是低音谱表，已截图核对）。
        // 所以只要给 <body> 挂一个 data-hand，用 CSS 把另一行谱表里的音符
        // 染成 noteDisableColor(#C4C4C4) 即可 —— 零重绘，还能加过渡动画，
        // 切换手感是"颜色淡入淡出"而不是"谱面刷新"。
        html = html.replace("</head>", "</head><style>"
                // 底色默认奶油白（原版预览页）；演奏态的暖羊皮纸由 applyPlayClass
                // 用 inline style 覆盖（见那边的注释：CSS 属性选择器在 WebView 里压不住这条）
                + "html,body{background:#FFFCE8 !important;margin:0;padding:0;}"
                // 演奏模式会把谱线/小节线描边压到 0.5px（见上面的注），统一压回 1px，
                // 让演奏态和试听态的谱面观感一致
                + "#viewer path,#viewer line{stroke-width:1;}"
                // 预览胶囊必须是**静态**的：任何动画/过渡都会让它在"第一个位置"
                // 一闪一闪。渲染器本身没给胶囊配动画
                // 这条是防呆 —— 万一以后有人给它挂上，也压得住。
                + "#sheet-cursor-preview,#sheet-cursor-preview rect{"
                + "animation:none !important;transition:none !important;}"
                // 曲首光标**锁死可见**（body.wb-cursor-lock）：
                // 大谱面（保卫黄河 267 万字符）进谱后还要重绘 4~6 秒，它每批绘制
                // 都会把光标元素改回 hidden（或整组藏掉），于是"画上→擦掉→画上"
                // 反复几次 —— 用户看到的就是"第一个位置有个光标在闪"。
                // 用 CSS `!important` 压住它自己的 visibility 写入：presentation
                // attribute 优先级低于 CSS 规则，锁住之后不管它重画多少批都可见。
                + "body.wb-cursor-lock #sheet-cursor-preview,"
                + "body.wb-cursor-lock #sheet-cursor-preview rect,"
                + "body.wb-cursor-lock #sheet-cursor-group{visibility:visible !important;}"
                // 曲谱自带的"音符较密，已为您缩小音符大小"提示条：原版渲染器自己的
                // 东西（sheet_show.html 里的 #auto-minify-score-notation），
                // 但在我们的页面上它只是个横在谱面顶部的打扰，且实测本次渲染
                // sheet_notes_crowd_auto_minify 是 false（根本没缩小），纯属误报。
                + "#sheet_name,#sheet_subtitle,#current-change-key,"
                + "#sheet_editor,#sheet_author,#sheet_copyright,"
                + "#auto-minify-score-notation{display:none !important;}"
                // 演奏时"当前小节"那块底色：渲染器写死 #FFFFFF / 55% + 白描边，
                // 铺在奶油白纸上就是一块突兀的白板（还会盖住谱表上方的速度记号）。
                // 暖金低透明度，与整体配色一致；无描边，边缘不会发亮。
                + "#measureHighLight{fill:#F0C03C !important;fill-opacity:.10 !important;"
                + "stroke:none !important;}"
                // 颜色过渡：切换手型时淡入淡出，看不出"刷新"
                + "#viewer .vf-stavenote path,#viewer .vf-stavenote line,"
                + "#viewer .vf-beam path,#viewer .vf-grace-note path{"
                + "transition:fill .22s ease,stroke .22s ease;}"
                // 右手模式：灰化低音谱表（-2）＝左手
                + "body[data-hand=\"right\"] [id^=\"vf-measuresRow-\"][id$=\"-2\"].vf-staveCell.vf-staveCell"
                + " .vf-stavenote path,"
                + "body[data-hand=\"right\"] [id^=\"vf-measuresRow-\"][id$=\"-2\"].vf-staveCell.vf-staveCell"
                + " .vf-stavenote line,"
                + "body[data-hand=\"right\"] [id^=\"vf-measuresRow-\"][id$=\"-2\"].vf-staveCell.vf-staveCell"
                + " .vf-beam path,"
                + "body[data-hand=\"right\"] [id^=\"vf-measuresRow-\"][id$=\"-2\"].vf-staveCell.vf-staveCell"
                + " .vf-grace-note path{fill:#C4C4C4;stroke:#C4C4C4;}"
                // 左手模式：灰化高音谱表（-1）＝右手
                + "body[data-hand=\"left\"] [id^=\"vf-measuresRow-\"][id$=\"-1\"].vf-staveCell.vf-staveCell"
                + " .vf-stavenote path,"
                + "body[data-hand=\"left\"] [id^=\"vf-measuresRow-\"][id$=\"-1\"].vf-staveCell.vf-staveCell"
                + " .vf-stavenote line,"
                + "body[data-hand=\"left\"] [id^=\"vf-measuresRow-\"][id$=\"-1\"].vf-staveCell.vf-staveCell"
                + " .vf-beam path,"
                + "body[data-hand=\"left\"] [id^=\"vf-measuresRow-\"][id$=\"-1\"].vf-staveCell.vf-staveCell"
                + " .vf-grace-note path{fill:#C4C4C4;stroke:#C4C4C4;}"
                + "</style>");
        if (!missed.isEmpty()) {
            throw new IllegalStateException("曲谱渲染补丁未命中：" + missed
                    + "；检查 app/assets/sheetShow 与 buildHtml 的字符串锚点");
        }
        return html;
    }

    private static String readAsset(Context ctx, String name) throws Exception {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    public void setListener(Listener l) {
        mListener = l;
    }

    /**
     * 安全执行 JS。
     *
     * 背景：喂音频的线程（magic-feed）比 Activity/WebView 活得久 —— 用户点返回、
     * 或系统回收 Activity 之后，引擎仍在每音回调，最终走到这里。
     * 对已 destroy 的 WebView 调 evaluateJavascript 会抛 IllegalStateException，
     * 而调用点都在 post 到主线程的 Runnable 里、没有任何 catch ——
     * 主线程未捕获异常 = 闪退。这就是"点演奏偶尔整个 App 崩/直接退回列表"的来源。
     */
    private void safeEval(String js) {
        safeEval(js, null);
    }

    /** 带回调版本：回调在 JS 真正执行完之后触发，用来量渲染往返耗时。 */
    private void safeEval(String js, android.webkit.ValueCallback<String> cb) {
        if (mDestroyed) return;
        try {
            evaluateJavascript(js, cb);
        } catch (Throwable t) {
            Log.w(TAG, "丢弃 JS 调用（WebView 已不可用）：" + t);
        }
    }

    /** 光标下发的完整往返：下发 → evaluateJavascript 回调（JS 里已跑完，含滚动） */
    private long mCursorRtCount, mCursorRtSum, mCursorRtMax;

    /**
     * 上一帧光标下发的时刻（0 = 没有在途帧）。
     *
     * 真实弹奏大谱面时，一次 moveCursor2 在 JS 里要 **75ms** 左右（含滚动重排），
     * 而音频每 46ms 就来一帧 —— 不限制的话 WebView 队列会无限积压，光标越落越远
     * （用户实测："基本只跟上第一小节，后面就落后于手指"，日志 `光标渲染往返 平均 75ms`）。
     * 所以同一时刻只允许一帧在渲染，后续帧直接丢弃：画面帧率降到 ~13Hz，但**永远
     * 贴着最新位置**，不会累积延迟。
     * 500ms 是防呆：万一 evaluateJavascript 的回调丢了，别让光标永久卡死。
     */
    private long mCursorInFlightAt;

    /**
     * 在飞帧期间被丢掉的最新一次光标请求（measure/offset/voice/needCursor/highlight）。
     * 回调一回来就补发它，见 {@link #flushDeferredCursor()}。
     */
    private int[] mCursorDeferred;

    @Override
    public void destroy() {
        mDestroyed = true;
        mListener = null;
        mPending.clear();
        mWrongMarks.clear();
        // 队列里还压着若干"延迟解冻自动滚动 / 补一帧光标"的任务（postDelayed）。
        // 它们执行时虽然会被 safeEval 的 mDestroyed 挡掉，但没必要再排一趟主线程；
        // 而且这些 lambda 捕获了本 View，清掉能更早断开引用。
        mUi.removeCallbacksAndMessages(null);
        // 大字符串必须在这里断开：mHtml 是整份渲染器 bundle（页面 HTML，几百 KB），
        // mQueuedXml 是一首曲子的完整 MusicXML（大曲子 1~2MB）。本 View 被 Activity
        // 通过 mSheet 引用着，直到 Activity 彻底回收才可能释放 —— 而 destroy() 之后
        // 它们**永远用不到了**（mDestroyed 为 true，send/initPage 全部早退）。
        // 不清就是"关掉页面还在替一首曲子留两份副本"。
        mHtml = null;
        mQueuedXml = null;
        mQueuedTitle = null;
        mCursorDeferred = null;
        // WebView 还挂在 Activity 的布局里时直接 destroy，Chromium 会打
        // "WebView.destroy() called while still attached" 并在部分机型上崩。
        // 官方要求先脱离父容器再销毁 —— 这里自己处理，调用方不必记得 removeView。
        try {
            android.view.ViewParent p = getParent();
            if (p instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) p).removeView(this);
            }
        } catch (Throwable t) {
            Log.w(TAG, "从父容器移除 WebView 失败：" + t);
        }
        try {
            super.destroy();
        } catch (Throwable t) {
            Log.w(TAG, "destroy WebView 失败：" + t);
        }
    }

    /** 渲染曲谱；页面还没就绪会排队，等 onPageFinished 再发 */
    public void show(String sheetXml, String title) {
        // 新谱面一定是预览态。演奏结束的正常路径也会 setPerformMode(false)，
        // 但异常路径（建会话失败后直接换曲）可能漏，这里兜一道，避免
        // 演奏态的高亮/门控被带到下一首。
        mPerformMode = false;
        // 首小节的速度标记从 XML 里摘掉，改由顶栏显示（见 TempoMark 类注释）。
        // 摘掉后渲染器不再为它保留行高，score_padding_top 才能压到个位数。
        TempoMark tm = TempoMark.extract(sheetXml);
        mQueuedXml = tm.xml;
        mQueuedTitle = title;
        mTempoLabel = tm.label;
        if (mPageReady) sendInitPage();
    }

    /** 顶栏要显示的速度标记（"Bouncy ♩=126"），没有就返回 null。 */
    public String getTempoLabel() {
        return mTempoLabel;
    }

    // ---------------- 原生 → JS ----------------

    private void sendInitPage() {
        if (mInitDone) {
            sendDrawSheet();
            return;
        }
        sendInitPageNow();
    }

    /**
     * 实际发 initPage 配置。
     *
     * 只有一个用途：首次装载 / 尺寸修正 —— 回调里接着发 drawSheet。
     */
    private void sendInitPageNow() {
        JSONObject p = new JSONObject();
        try {
            // name 必须是非空字符串：渲染器拿它当"这是一次真的初始化"的开关
            // （`e.name || isMagicMode`），空串会让整个初始化块被跳过并打 FATAL。
            // 正常路径下 show() 已经把标题写进来了，这里只是兜底。
            p.put("name", (mQueuedTitle == null || mQueuedTitle.isEmpty())
                    ? "sheet" : mQueuedTitle);
            // 恒 preview：演奏态由 Java 侧 mPerformMode 控制（见 mPerformMode 注释）。
            // 渲染器的 mode 只在这里写一次，之后全生命周期不变。
            p.put("mode", "preview");
            p.put("locale", "zh");
            p.put("is_android_device", true);
            p.put("is_low_equipment", false);
            // 手型：固定传 "both"。灰化改由注入的 CSS 按谱表分组完成
            // （见 buildHtml 里的样式表与 setHandType），渲染器只负责画黑色，
            // 这样切手型不用重绘，谱面不会刷一下。
            p.put("hand_type", "both");
            // 首行谱表的 Y 偏移（渲染器 MeasuresRows.firstRowY）。不传的话用默认 60，
            // 整个谱面块偏低 ~108px —— 顶部空一大片。
            // 速度标记已由 TempoMark 从 XML 摘掉、改在顶栏显示，这里不再为它留空间，
            // 只留 8 给其它"谱线上方"内容（指法弧线/八度线）一点呼吸。
            // 别再往上加：加多少就等于第一行多高多少，直接影响一屏行数。
            p.put("score_padding_top", 8);
            // initPage 里对 state 做的是**整体覆盖**
            // （localGlobalState = e.state.global_state || {}），所以每次都传空对象，
            // 避免把本地覆盖态清掉。initPage 的调用者只剩"首次装载/尺寸修正"。
            JSONObject state = new JSONObject();
            state.put("global_state", new JSONObject());
            state.put("score_state", new JSONObject());
            p.put("state", state);
            p.put("header_height", 0);
            p.put("footer_height", 0);
            // 底色必须走 initPage（不是 drawSheet）：bg_color 只在 initPage 的
            // handler 里被读，作用是设 body 背景并同步 GlobalState.backgroundColor
            // 与 SectionView.maskColor。演奏页暖羊皮纸 #F1D6BF 取自原版演奏页
            // 截图取样，预览页是奶油白 #FFFCE8。
            // 底色统一用预览页的奶油白。原版演奏页是暖羊皮纸，但用户明确
            // 要求"演奏态没必要跟着换，维持试听时的颜色"，所以两种 mode 同色。
            p.put("bg_color", "#FFFCE8");
            // feature.single_note_play：开光标旁那个单音播放图标。它不只是装饰 ——
            // 原版 moveMagicCursor 的 animation_type="shake" 抖的就是这个元素。
            // 我们的命中反馈不靠它（错音画红叉），这里仍按原版开着，
            // 免得以后想用 shake 时发现图标根本没画出来。
            JSONObject feature = new JSONObject();
            feature.put("single_note_play", true);
            p.put("feature", feature);

            // ---- 谱面缩放：一屏稳定放下约 3 行 ----
            // 行数 = 曲谱区高 ÷ (行高vf × scale)，行高vf 随曲子变（单/双谱表、歌词、
            // 8va 线…实测 270~330），所以没有常数能保证"正好 N 行"；但让 scale 与
            // **曲谱区高** 等比，各种屏的行数就一致。
            //
            // 为什么用曲谱区高而不是整屏高：两条栏占的比例各机不同（模拟器 97/640，
            // 21:9 手机 80/437），按整屏算会让手机的谱面偏大、第 3 行被顶出视口。
            // 基准是实测值：曲谱区 542.7 CSS → scale 0.65，正好 3 整行。
            // 速度标记由顶栏显示（见 TempoMark），**首行**不留它的高度，首屏会多出
            // 约 35 CSS 的余量；但那部分空间只存在于第一行 —— 滚到中段以后每个小节
            // 本来就没有 tempo，放大整体 zoom 只会让中段少一行（真机上实测：
            // 0.46 时第 3 行的低音谱表掉到视口外）。所以 zoom 保持 0.65 不动。
            // 设备分档：
            //   · 大屏（曲谱区 >= 400 CSS：模拟器 542.7 / 平板）：保持 3 行等比；
            //   · 小屏（手机，曲谱区 283 CSS）：目标是「**最宽的两行也完整**」。
            // 行高随曲子变很大：六度音阶有 8va 线、密集指法与加线，行距比普通曲
            // 大约 30%。真机实测 12 首（视口 y=155..1146）：
            //   zoom 0.560 → 降B/B大调六度音阶第 2 行低音谱表直接被切（只剩 3 个谱表组）；
            //   zoom 0.490 → 最坏的降B大调末线 1124，只剩 22px，指法/加线仍被切；
            //   zoom 0.465 → 4 首最宽曲末线 1044~1074，留 72~102px 余量，全部两行完整。
            // 所以小屏基准取 283 CSS → 0.465（宁可音符小一点，也要保证完整），
            // 按曲谱区高等比、夹到 [0.42, 0.70]。普通曲会多露一点第 3 行抬头，无妨。
            // 别拿 measure_row_padding_bottom 凑行数：负值只压"上一行低音谱表→下一行
            // 高音谱表"的间距，-40 才省 32 CSS，要 -100 以上才够一行，那时行间力度
            // 记号/8va 线会叠一起。
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int hPx = getHeight();
            // 首帧可能还没布局（getHeight()==0），先用"屏高 − 两条栏"兜底；
            // 宽度为 0 时 onSizeChanged 会重发 initPage，那时拿到的才是真值。
            float scoreCssH = hPx > 0
                    ? hPx / dm.density
                    : (dm.heightPixels / dm.density - 97f);
            final boolean compact = scoreCssH < 400f;
            double zoom;
            if (compact) {
                // 283 CSS 是小屏实测曲谱区高；0.465 对应"最宽的两行也完整"
                zoom = 0.465d * (scoreCssH / 283f);
                zoom = Math.max(0.42d, Math.min(0.70d, zoom));
            } else {
                zoom = 0.65d * (scoreCssH / 542.7d);
                zoom = Math.max(0.42d, Math.min(0.90d, zoom));
            }
            p.put("zoom", zoom);
            // 记下"我们请求的 zoom"：drawSheet 回执没带 current_zoom 时用它兜底
            // （用 1.0 会让可见性判定整体放大，恢复永远等不到目标上屏）
            mExpectedZoom = zoom;
            Log.i(TAG, "谱面缩放：曲谱区 " + Math.round(scoreCssH)
                    + " CSS（" + (hPx > 0 ? "实测" : "兜底") + "） → zoom "
                    + String.format("%.3f", zoom) + "（"
                    + (compact ? "小屏/保证两行完整" : "大屏/目标三行") + "）");
        } catch (Exception e) {
            Log.e(TAG, "构造 initPage 参数失败", e);
            return;
        }
        // 记下这一刻的宽度：为 0 说明 WebView 还没布局，渲染器会把整页算成 0 宽
        mInitPageAtWidth = getWidth();
        send("initPage", p, (ok, data) -> {
            Log.i(TAG, "initPage -> " + data);
            if (mInitPageAtWidth <= 0) {
                Log.w(TAG, "initPage 时 WebView 宽度为 0，谱面可能整页空白（等 onSizeChanged 补救）");
            }
            mInitDone = true;
            sendDrawSheet();
        });
    }

    /**
     * 切换手型（"both"/"left"/"right"）——**只切一个 CSS 类，完全不重绘**。
     *
     * 灰化规则写在 buildHtml 注入的 <style> 里：给 <body> 挂 data-hand，
     * CSS 按谱表分组（vf-measuresRow-<行>-<谱表>，1=高音/右手、2=低音/左手）
     * 把另一只手的音符染成 #C4C4C4，并带 0.22s 颜色过渡。
     *
     * 为什么不让渲染器自己灰化：它是在画音符时按 handType 上色的，换手必须
     * 重发 initPage + drawSheet 整页重画，谱面会明显刷一下。CSS 这条路
     * 零重绘、零状态重置（光标、滚动位置、缩放全不受影响），切换手感是
     * 颜色淡入淡出。
     *
     * 因此 initPage 里固定传 hand_type="both"（让渲染器永远画黑色），
     * 灰化一律由这里的 CSS 负责，两套机制不会互相打架。
     */
    private static JSONObject pos(int measure, int voice, int offset) throws org.json.JSONException {
        return new JSONObject().put("measure", measure).put("voice", voice).put("offset", offset);
    }

    /**
     * 选段的**渐进绘制**：点第一下就画出左括号，点第二下再补右括号。
     *
     * 复用渲染器 MagicCustomSectionController 自己的 setBeginPoint / setEndPoint + drawV2
     * （drawV2 本来就支持"只有起点"或"只有终点"），所以不用等两个点齐了才画。
     */
    public void sectionBegin(int measure, int voice, int offset) {
        try {
            send("wbSectionBegin", pos(measure, voice, offset), (ok, d) ->
                    Log.i(TAG, "wbSectionBegin -> " + ok));
        } catch (Exception e) {
            Log.e(TAG, "sectionBegin 失败", e);
        }
    }

    public void sectionEnd(int measure, int voice, int offset) {
        try {
            send("wbSectionEnd", pos(measure, voice, offset), (ok, d) ->
                    Log.i(TAG, "wbSectionEnd -> " + ok));
        } catch (Exception e) {
            Log.e(TAG, "sectionEnd 失败", e);
        }
    }

    /**
     * 取消选段：只把两个括号藏起来 + 清掉内部记录，**不重绘**。
     *
     * 整页重绘会重排谱面、滚回曲首；drawV2 只画不擦，控制器本身有
     * setShow(false)，正好用来"擦"。
     */
    public void sectionClear() {
        try {
            send("wbSectionClear", new JSONObject(), (ok, d) ->
                    Log.i(TAG, "wbSectionClear -> " + ok));
        } catch (Exception e) {
            Log.e(TAG, "sectionClear 失败", e);
        }
    }

    /**
     * 查询某个音符位置当前是否在视口里（异步）。
     * 试听中用它判断"播放位置还看得见吗"：看得见就继续跟随，看不见才暂停。
     */
    public void queryMeasureVisible(int measure, int voice, int offset,
                                    java.util.function.Consumer<Boolean> cb) {
        try {
            send("wbMeasureVisible", pos(measure, voice, offset), (ok, d) -> {
                boolean vis = (d instanceof JSONObject) && ((JSONObject) d).optBoolean("vis", false);
                if (cb != null) mUi.post(() -> cb.accept(vis));
            });
        } catch (Exception e) {
            Log.e(TAG, "queryMeasureVisible 失败", e);
        }
    }

    /**
     * 渲染器已经画到的小节索引（0-based；-1 = 还没画完一批）。
     * 载入框用它显示「正在准备第 当前/目标 小节…」。
     */
    public int lastDrawnMeasure() {
        return mLastDrawnMeasure;
    }

    /**
     * 目标小节是否「已经画出来**并且**真的滚到了屏幕里」。
     *
     * ⚠ 恢复解锁必须用这个，不能用 {@link #queryMeasureVisible}：后者的 JS 里
     * `vis = !pos || (y 在视口内)` —— 定位不到节点（那一行还没画出来）时返回
     * **true**，那是给播放跟随兜底的语义；拿它判"恢复完成"会在目标行还不存在时
     * 提前解锁，用户看到的还是曲首。
     * 这里的判据：`y >= 0`（渲染器找到了这颗音的位置，说明那行已画）且
     * `y ∈ [scrollY, scrollY+innerHeight]`（真的滚到可视区了 —— scrollY 没变
     * 就说明滚动还没生效，继续等）。
     */
    public void queryMeasureReady(int measure, int voice, int offset,
                                  java.util.function.Consumer<Boolean> cb) {
        try {
            // 首帧 drawSheet 回执（current_zoom）之前 mSheetZoom 还是初值，
            // 用我们自己下发的 mExpectedZoom 兜底，别按 1.0 算。
            final double zoom = mSheetZoom > 0.05d ? mSheetZoom : mExpectedZoom;
            send("wbMeasureVisible", pos(measure, voice, offset), (ok, d) -> {
                boolean ready = false;
                if (d instanceof JSONObject) {
                    JSONObject o = (JSONObject) d;
                    int y = o.optInt("y", -1), sy = o.optInt("sy", 0), h = o.optInt("h", 0);
                    // y 是未缩放坐标：乘 zoom 才是 CSS 像素（实测 T618：y=1820、zoom=0.64
                    // → 1164，视口 [1147,1736]，与截图一致）
                    double yCss = y * zoom;
                    ready = y >= 0 && yCss >= sy && yCss <= sy + h;
                    // 恢复卡在"一直没上屏"时只能靠这行定位：y<0 = 那行还没画出来；
                    // yCss 远比 sy+h 大 = zoom 与实际不符（判定永远为 false）。
                    Log.d(TAG, "ready? m=" + measure + " y=" + y + " sy=" + sy + " h=" + h
                            + " zoom=" + String.format("%.3f", zoom)
                            + " yCss=" + Math.round(yCss) + " -> " + ready);
                }
                boolean f = ready;
                if (cb != null) mUi.post(() -> cb.accept(f));
            });
        } catch (Exception e) {
            Log.e(TAG, "queryMeasureReady 失败", e);
            if (cb != null) mUi.post(() -> cb.accept(false));
        }
    }

    /**
     * 演奏结束后在谱面上打结果标记 —— 这就是"错音展示"。
     *
     * 数据来自 ufpFinish 的 vexflow JSON：
     * <pre>
     * pitches_info: [
     *   { pitch_cluster_position:{measure,voice,offset},
     *     bias_type:"ok",                    // 音团级：ok / 节奏错
     *     pitch:[ {key:"C4", bias_type:"miss", note_position:{...}},   // 漏弹
     *             {key:"F4", bias_type:"extra"} ] }                     // 弹错（实际弹成这个音）
     * ]
     * </pre>
     * 这里原样转成渲染器 followPitch2 认的 {voices:[{position,pitch:[{key,bias_type}]}]}，
     * 由原版那套 FollowPitchController 自己画，但 buildHtml 里注入了两处补丁：
     *   - 符头色板全黑（不再把漏弹染灰、错音染红）；
     *   - drawPitchType 把所有非 ok 的 bias_type 归一成 wrong 并强制红色，
     *     于是弹错 / 漏弹 / 其它问题统一画一个红叉，ok 不画任何符号。
     * 演奏过程中标记保持不动（上一轮的红叉正好当提醒）；出分时先清旧标记再画本轮，
     * 所以看到的永远只有"这一遍"的结果。
     *
     * 注意：这**不是**实时标记。UFP 的实时状态（UninterruptibleFollowStatus）
     * 只有光标 / 左右容错窗口 / score，根本没有逐音数据；原版同样是演奏结束后
     * 才在谱面上标结果。过程中要标错音只能自己做基频检测。
     */
    public void markTrialResult(String vexflowJson) {
        if (vexflowJson == null || vexflowJson.isEmpty() || "null".equals(vexflowJson)) return;
        try {
            JSONObject src = new JSONObject(vexflowJson);
            JSONArray info = src.optJSONArray("pitches_info");
            if (info == null || info.length() == 0) {
                // 没有判定数据（刚开跑就按停 / 跳过评分）：别动旧红叉，
                // 否则"什么都没弹"也会把上一遍的错音标记抹掉。
                Log.w(TAG, "结果标记：pitches_info 为空，保留上一遍的红叉");
                return;
            }
            JSONArray freshWrong = new JSONArray();
            int minM = Integer.MAX_VALUE, maxM = -1;
            int okDropped = 0;
            for (int i = 0; i < info.length(); i++) {
                JSONObject it = info.optJSONObject(i);
                if (it == null) continue;
                JSONObject pos = it.optJSONObject("pitch_cluster_position");
                JSONArray ps = it.optJSONArray("pitch");
                if (pos == null || ps == null || ps.length() == 0) continue;
                // 本轮判定覆盖的小节范围（**含弹对的音团**）。只有这个范围内的
                // 旧红叉该被本轮结果替换，范围外的照旧留着当提醒。
                int m = pos.optInt("measure", -1);
                if (m >= 0) {
                    minM = Math.min(minM, m);
                    maxM = Math.max(maxM, m);
                }
                JSONArray pitch = new JSONArray();
                boolean wrong = false;
                for (int k = 0; k < ps.length(); k++) {
                    JSONObject p = ps.optJSONObject(k);
                    if (p == null) continue;
                    String key = p.optString("key", "");
                    if (key.isEmpty()) continue;
                    String bt = p.optString("bias_type", "ok");
                    if (!"ok".equals(bt)) wrong = true;
                    pitch.put(new JSONObject().put("key", key).put("bias_type", bt));
                }
                if (pitch.length() == 0) continue;
                // 只发有问题的音团：ok 塞进报文没有显示效果，长演奏会把报文
                // 撑到 3 万字符以上导致 evaluateJavascript 整批失败。
                if (!wrong) { okDropped++; continue; }
                freshWrong.put(new JSONObject()
                        .put("position", pos)
                        .put("pitch", pitch)
                        .put("grace", new JSONArray()));
            }
            // 先撤掉本轮覆盖范围内的旧红叉，再把这一遍新判错的加回去。
            // 顺序不能反：新错音往往就在同一小节里，先加会被一起删掉。
            if (maxM >= 0) {
                java.util.Iterator<Map.Entry<String, JSONObject>> it =
                        mWrongMarks.entrySet().iterator();
                while (it.hasNext()) {
                    JSONObject v = it.next().getValue();
                    JSONObject vp = v.optJSONObject("position");
                    int m = vp == null ? -1 : vp.optInt("measure", -1);
                    if (m >= minM && m <= maxM) it.remove();
                }
            }
            for (int i = 0; i < freshWrong.length(); i++) {
                JSONObject v = freshWrong.optJSONObject(i);
                mWrongMarks.put(markKey(v.optJSONObject("position")), v);
            }
            // 渲染器 addFollowPitchs 是累加的、clearResults 是全清，所以这里
            // 改成"全清 + 重画最终红叉全集"，才能让弹对的那几颗真的消失。
            // clearResults 无回执，靠 FIFO 保证先后，别挂回调。
            send("clearResults", new JSONObject(), null);
            if (mWrongMarks.isEmpty()) {
                Log.i(TAG, "结果标记：本轮 " + minM + ".." + maxM
                        + " 小节全弹对，已清除这些小节的旧红叉（弹对省略 "
                        + okDropped + " 个音团）");
                return;
            }
            JSONArray voices = new JSONArray();
            for (JSONObject v : mWrongMarks.values()) voices.put(v);
            final String fpsAll = new JSONObject().put("voices", voices).toString();
            Log.i(TAG, "结果标记报文：最终红叉 " + voices.length()
                    + " 个，following_pitch_string " + fpsAll.length() + " 字符");
            final int BATCH = 80;
            int batches = 0;
            if (voices.length() <= BATCH) {
                send("followPitch2", new JSONObject()
                        .put("following_pitch_string", fpsAll), null);
                batches = 1;
            } else {
                for (int from = 0; from < voices.length(); from += BATCH) {
                    JSONArray part = new JSONArray();
                    for (int k = from; k < Math.min(from + BATCH, voices.length()); k++) {
                        part.put(voices.opt(k));
                    }
                    send("followPitch2", new JSONObject()
                            .put("following_pitch_string",
                                    new JSONObject().put("voices", part).toString()), null);
                    batches++;
                }
            }
            Log.i(TAG, "演奏结果标记：本轮覆盖 " + minM + ".." + maxM + " 小节，新错 "
                    + freshWrong.length() + " 个、弹对省略 " + okDropped
                    + " 个，最终保留红叉 " + voices.length() + " 个，分 " + batches + " 批");
        } catch (Exception e) {
            Log.e(TAG, "结果标记失败", e);
        }
    }

    /** 音团标识：小节 + 声部 + 偏移，与渲染器替换标记的粒度一致。 */
    private static String markKey(JSONObject pos) {
        if (pos == null) return "?";
        return pos.optInt("measure", -1) + "/"
                + pos.optInt("voice", 0) + "/" + pos.optInt("offset", 0);
    }

    /**
     * 光标接口（唯一一条）：平铺的 measure/offset/voice。
     * 原版还有一条 moveMagicCursor（cursors 数组结构），随 magicPiano 通路一起删了。
     */
    public void moveCursor2(int measure, int offset, int voice, boolean needCursor) {
        moveCursor2(measure, offset, voice, needCursor, false);
    }

    /**
     * @param highlightMeasure 是否把**整个当前小节**铺一块半透明高亮（原版试炼就是这么做的）。
     *
     * 渲染器里这是两个独立元素，对应两套语义：
     *   - need_cursor            → `moveCursor(vfNote)`：一个音符级的光标（金色胶囊）；
     *   - need_highlight_measure → `moveMeasureHighlight(measureCell)`：整个小节一块底色。
     * 详见 moveCursor 实现：`if (need_highlight_measure || need_highlight_line==1)
     * moveMeasureHighlight(cell) else moveMeasureHighlight(null)`。
     *
     * UFP 给的数据本来就是"窗口"（cursor + leftWin + rightWin），不是精确的一个点，
     * 所以区域高亮比单音胶囊更贴合语义，也不会跟着引擎的来回跳而抖。
     */
    public void moveCursor2(int measure, int offset, int voice, boolean needCursor,
                            boolean highlightMeasure) {
        moveCursor2(measure, offset, voice, needCursor, highlightMeasure, false);
    }

    /**
     * @param forceScroll 让视口滚到这颗音。演奏中一般传 false（跟着弹奏走、不抢视口），
     *                    只有"用户停手一段时间、光标已经滚出屏幕"时才传 true 拉回来。
     */
    public void moveCursor2(int measure, int offset, int voice, boolean needCursor,
                            boolean highlightMeasure, boolean forceScroll) {
        // 光标样式已经统一成胶囊（见 applyCursorStyle），这里只挪位置、不切样式。
        // 同一时刻只放一帧出去，其余丢弃（见 mCursorInFlightAt 的注释）
        long now = android.os.SystemClock.elapsedRealtime();
        if (mCursorInFlightAt != 0 && now - mCursorInFlightAt < 500) {
            // 丢掉的同时**记下最新位置**：回调一回来就补发这一帧。
            // 只丢不记就只能等"下一帧音频"再触发 —— 试听时碰上
            // 一次慢回调，光标会原地停住几百毫秒再跳过好几个音。
            mCursorDeferred = new int[]{measure, offset, voice,
                    needCursor ? 1 : 0, highlightMeasure ? 1 : 0, forceScroll ? 1 : 0};
            return;
        }
        mCursorInFlightAt = now;
        try {
            JSONObject p = new JSONObject();
            p.put("measure", measure);
            p.put("offset", offset);
            p.put("voice", voice);
            p.put("need_cursor", needCursor);
            p.put("need_highlight_measure", highlightMeasure);
            p.put("need_highlight_line", false);
            p.put("forceScroll", forceScroll);
            // 记下最后位置：演奏结束后 restorePreviewCursor 靠它把胶囊放回来，
            // 顺带用 highlight=false 把整小节高亮撤掉（不记的话那一步会被跳过，
            // 白色小块就留在谱面上）。
            mLastMeasure = measure;
            mLastOffset = offset;
            mLastVoice = voice;
            send("moveCursor2", p, null);
        } catch (Exception e) {
            Log.e(TAG, "moveCursor2 参数失败", e);
        }
    }

    /** 补发在飞期间被丢掉的那一帧光标（没有就什么都不做）。 */
    private void flushDeferredCursor() {
        int[] d = mCursorDeferred;
        if (d == null || mDestroyed) return;
        mCursorDeferred = null;
        moveCursor2(d[0], d[1], d[2], d[3] == 1, d[4] == 1, d[5] == 1);
    }

    public void setHandType(String handType) {
        if (handType == null) handType = "both";
        if (handType.equals(mHandType)) return;
        mHandType = handType;
        applyHandClass();
    }

    /** 把当前手型写到 <body data-hand>；页面没就绪时只记着，等 onPageFinished 再补 */
    private void applyHandClass() {
        if (!mPageReady) return;
        final String ht = mHandType;
        Log.i(TAG, "切换手型 -> CSS 灰化（data-hand=" + ht + "），不重绘");
        mUi.post(() -> safeEval(
                "document.body.setAttribute('data-hand','" + ht + "')"));
    }

    private void sendDrawSheet() {
        if (mQueuedXml == null) return;
        JSONObject d = new JSONObject();
        try {
            d.put("sheet_xml", mQueuedXml);
        } catch (Exception e) {
            Log.e(TAG, "构造 drawSheet 参数失败", e);
            return;
        }
        Log.i(TAG, "drawSheet，曲谱 " + mQueuedXml.length() + " 字符");
        // 重绘 = 从第一行重新画，进度也从头算（载入框会显示 当前/目标）
        mLastDrawnMeasure = -1;
        send("drawSheet", d, (ok, data) -> {
            Log.i(TAG, "drawSheet -> ok=" + ok + " " + data);
            // 记下渲染器实际采用的 zoom：wbMeasureVisible 返回的 y 是**未缩放**的
            // 谱面坐标，换算成 CSS 像素要用它（见 queryMeasureReady）。
            // 回执没带 current_zoom / 给了非正值时退回 mExpectedZoom，
            // 绝不能退回 1.0（见 mSheetZoom 的注释）。
            try {
                JSONObject z = new JSONObject(String.valueOf(data));
                double zz = z.optDouble("current_zoom", mExpectedZoom);
                if (!(zz > 0.05d)) zz = mExpectedZoom;
                mSheetZoom = zz;
                Log.i(TAG, "渲染器 zoom=" + mSheetZoom
                        + (Math.abs(zz - mExpectedZoom) > 0.005 ? "（与请求值 "
                        + String.format("%.3f", mExpectedZoom) + " 有偏差）" : ""));
            } catch (Exception ignored) {
            }
            // 每次重绘后都补一次光标样式：initPage 重发/重绘会把手型与光标状态
            // 一起重置，不补的话切完手型光标就变回默认（细线）甚至消失。
            applyCursorStyle();
            // 重绘会把谱面从第 1 小节重排、滚动归零。用户"点了某个音当起点再点演奏"
            // 时看到的就是谱面跳回曲首，所以画完立刻把视口滚回最后一次定位的小节。
            if (mLastMeasure > 0) {
                JSONObject sc = new JSONObject();
                try {
                    sc.put("measure", mLastMeasure);
                    sc.put("align", "center");
                    sc.put("forceScroll", true);
                    // 必须显式关掉：scrollTo 的 need_highlight_measure 默认是 true
                    // （渲染器里 `null==r&&(r=!0)`），会给当前小节画一块默认蓝色矩形
                    // （#328DFF / 22%；这里的 CSS 已把它覆盖成暖金）。
                    sc.put("need_highlight_measure", false);
                } catch (Exception ignored) {
                }
                send("scrollTo", sc, (ok2, d2) ->
                        Log.i(TAG, "重绘后回滚到第 " + mLastMeasure + " 小节 -> " + ok2));
            }
        });
    }

    /**
     * 把播放光标移到指定音符位置，并让谱面自动滚动跟随。
     * moveCursor2 在 JS 侧只注册了一个参数（不返回 response），所以这里不挂回调。
     */
    public void moveCursor(int measure, int offset, int voice) {
        moveCursorAt(measure, offset, voice, true);
    }

    /**
     * @param scroll false 时只挪光标、不滚谱面 —— 试听过程中用户自己滚了谱之后
     *               就不该再把视口拽回播放的那一行（只在起播时跟一次即可）。
     */
    public void moveCursor(int measure, int offset, int voice, boolean scroll) {
        moveCursorAt(measure, offset, voice, scroll);
    }

    /** 用户在谱面上的动作：scroll=true 表示真的在滚谱面，false 只是按下（点击/点音符）。 */
    public interface UserActionListener {
        void onUserAction(boolean scroll);
    }

    /** 监听用户在谱面上的动作（试听时据此决定要不要暂停跟随）。 */
    public void setUserActionListener(UserActionListener l) {
        mUserAction = l;
        setOnTouchListener((v, e) -> {
            if (mUserAction == null) return false;
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    mTouchX = e.getX();
                    mTouchY = e.getY();
                    mScrollFired = false;
                    mUi.post(() -> mUserAction.onUserAction(false));
                    break;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (!mScrollFired && Math.abs(e.getY() - mTouchY) > SCROLL_SLOP_PX) {
                        mScrollFired = true;
                        mUi.post(() -> mUserAction.onUserAction(true));
                    }
                    break;
                default:
                    break;
            }
            return false; // 不消费：WebView 自己还要处理滚动和点击
        });
    }

    /** 撤掉演奏时的整小节高亮（不动谱面、不画光标）。演奏结束/中断时调用。 */
    public void clearMeasureHighlight() {
        if (mLastMeasure < 0) return;
        moveCursor2(mLastMeasure, Math.max(0, mLastOffset), Math.max(0, mLastVoice),
                false, false);
    }

    /**
     * 把预览光标（金色胶囊）画到指定音符上。
     *
     * @param scroll false 时不滚动谱面 —— 退出演奏恢复光标要用，
     *               否则会把用户刚才看到的位置又滚走（本来就是"位置别动"的诉求）。
     */
    private void moveCursorAt(int measure, int offset, int voice, boolean scroll) {
        mLastMeasure = measure;
        mLastOffset = offset;
        mLastVoice = voice;
        JSONObject d = new JSONObject();
        try {
            d.put("measure", measure);
            d.put("offset", offset);
            d.put("voice", voice);
            d.put("need_cursor", true);
            // 演奏态高亮当前小节（那块浅色底），预览态不要 ——
            // 试听时整块底色会跟滚动叠加，看着脏。
            // 渲染器恒 preview，演奏态高亮由 Java 侧 mPerformMode 独立判断，
            // 不读渲染器的 isMagicMode。
            d.put("need_highlight_measure", mPerformMode);
            d.put("need_highlight_line", false);
            d.put("forceScroll", scroll);
            // scroll=false 要的是"真的不滚"。H5 里 forceScroll=false 只在"最近 2s
            // 碰过屏幕"时才跳过滚动，过 2s 照样把当前小节顶到顶部 —— 这就是
            // "用户点第二行定位、整页加载完它自己上移"的真凶。
            d.put("wb_no_scroll", !scroll);
        } catch (Exception e) {
            return;
        }
        send("moveCursor2", d, null);
    }

    /**
     * 藏掉**金色胶囊**（#sheet-cursor-preview）。
     *
     * 现在唯一的用途是**进选段时**：那条光标表示"播放到哪儿"，选段过程中没意义，
     * 而且正好压在括号上。演奏/试听都用同一枚胶囊，不需要再藏。
     * 只用 visibility 藏，不用 display:none：胶囊重画时 JS 会把 visibility 改回
     * visible，用 display 藏会让它再也回不来。
     */
    public void hidePreviewCursor() {
        // 打上标志：showPreviewCursor 里排队的延迟显示看到它就直接放弃，
        // 否则用户进谱后立刻点选段，几百毫秒后那几条 setTimeout 会把光标又画回来。
        safeEval("(function(){window.__wbCursorHidden=true;"
                + "var p=document.querySelectorAll('#sheet-cursor-preview');"
                + "for(var i=0;i<p.length;i++)p[i].setAttribute('visibility','hidden');})()");
    }

    /**
     * 把预览态的金色胶囊**显式显出来**。
     *
     * `HIDE_TRAIL_CURSORS_JS` 会把所有胶囊设成 hidden；之后 moveCursor2 只重画
     * 胶囊本身，不会把 visibility 改回来 —— 于是进谱时虽然下发了 `C=1`，
     * 谱面上却看不到任何光标。
     * 这里把胶囊显式恢复可见。
     */
    public void showPreviewCursor() {
        // 渲染器的光标 DOM 是 moveCursor2 里**异步**创建的，进谱回调里立刻查
        // 往往一个都还没有（实测 shown=0）。所以立即试一次 + 三次延迟补显，
        // 并用 __wbCursorHidden 让 hidePreviewCursor 能取消排队的任务。
        //
        // ⚠ 只在**确实被藏起来**时才写属性：无条件 setAttribute('visibility')
        // 每次都触发一次样式重算，四次补显叠在整页绘制期间，画面上就是光标闪几下。
        // 值没变就不要动它。
        String js = "(function(){window.__wbCursorHidden=false;"
                + "window.__wbShowPreview=function(){"
                + "if(window.__wbCursorHidden)return 'skip';"
                + "if(document.body.classList.contains('last-pass-review'))"
                + "document.body.classList.remove('last-pass-review');"
                + "var p=document.querySelectorAll('#sheet-cursor-preview'),n=0;"
                + "for(var i=0;i<p.length;i++){"
                + "if(p[i].getAttribute('visibility')!=='visible'){"
                + "p[i].setAttribute('visibility','visible');n++;}"
                + "if(p[i].style.display==='none'){p[i].style.display='';n++;}}"
                + "return 'p='+p.length+' fixed='+n;};"
                + "var r0=window.__wbShowPreview();"
                + "setTimeout(function(){window.__wbShowPreview()},180);"
                + "setTimeout(function(){window.__wbShowPreview()},450);"
                + "setTimeout(function(){window.__wbShowPreview()},900);"
                + "return r0;})()";
        safeEval(js);
    }

    /**
     * 把曲首光标**锁成常亮**（见注入的 `body.wb-cursor-lock` 规则）。
     *
     * 不采用"等画完再画"：实测保卫黄河 6 秒判定的"谱面高度稳定"之后画面仍在
     * 重绘十几秒，画上去照样被擦。锁住可见性才是根治 —— 渲染器怎么重画都压不动
     * CSS `!important`。用户自己定位（点谱面/拖进度条/载入存档）时解锁。
     */
    public void lockPreviewCursor() {
        safeEval("(function(){try{document.body.classList.add('wb-cursor-lock');"
                + "window.__wbCursorHidden=false;}catch(e){}return 'lock';})()");
    }

    public void unlockPreviewCursor() {
        safeEval("(function(){try{document.body.classList.remove('wb-cursor-lock');}catch(e){}"
                + "return 'unlock';})()");
    }

    /**
     * 开始盯"画面是否真的停止变化"。
     *
     * 用 MutationObserver 而不是轮询谱面高度：实测 scrollHeight 6 秒就定住了，
     * 而 DOM 还在继续被填（保卫黄河到 4~6 秒才停）。稳定后回调
     * {@link Listener#onSheetSettled()}，用来把曲首光标重新画一次（纠正重排位移）。
     */
    public void beginSettleWatch() {
        safeEval(SETTLE_WATCH_JS);
    }

    /** 结束稳定检测（用户开始定位/载入时调）。 */
    public void endSettleWatch() {
        safeEval("(function(){window.__wbSettling=false;return 'off';})()");
    }

    private static final String SETTLE_WATCH_JS =
            "(function(){window.__wbSettling=true;"
                    + "var root=document.querySelector('#viewer')||document.body;"
                    + "var last=Date.now(),t0=Date.now();"
                    + "try{console.log('WB_SETTLE start');}catch(e){}"
                    + "var mo=new MutationObserver(function(){last=Date.now();});"
                    + "try{mo.observe(root,{childList:true,subtree:true,attributes:true,"
                    + "characterData:true});}catch(e){}"
                    + "function tick(){"
                    + "if(!window.__wbSettling){try{mo.disconnect();}catch(e){}return;}"
                    // 封顶 20s：这是"整页真的画完"的最后兜底，恢复锁也等它。
                    // ⚠ 别为了"快点放开按钮"把它压到 10s：低性能设备上谱面还没画稳
                    // 就放开交互，用户的定位会被后续重绘覆盖。
                    + "if(Date.now()-last>=600||Date.now()-t0>20000){"
                    + "window.__wbSettling=false;"
                    + "try{mo.disconnect();}catch(e){}"
                    + "try{console.log('WB_SETTLE done quiet='+(Date.now()-last)"
                    + "+' ms='+(Date.now()-t0));}catch(e){}"
                    + "try{window.__wbCallHandler('onSheetSettled',0);}catch(e){}"
                    + "return;}"
                    + "setTimeout(tick,50);}"
                    + "setTimeout(tick,50);"
                    + "return 'watch';})()";
    /**
     * 演奏自然结束后把光标/视口收回曲首（或段首）。
     * 必须在 markTrialResult 之后调用：渲染器 followPitch2 内部会 moveCursor 到
     * 最后一个标记音，我们的 moveCursor2 排在它后面（FIFO）才能压住。
     * forceScroll=true，所以切回预览时的自动滚动冻结挡不住这一次。
     */
    public void moveToStart(int measure, int offset, int voice) {
        moveCursorAt(measure, offset, voice, true);
    }

    /** 光标最后所在的音符位置（演奏结束后用来同步引擎播放头）。 */
    public int lastMeasure() {
        return mLastMeasure;
    }

    /**
     * 把"最后光标位置"复位成**从未定位过**（-1）。
     *
     * 用于"程序把光标放到曲首"之后：那一下不算用户主动定位，不该在退出时
     * 按"光标在第一小节"去清掉上次的存档。
     * 用户之后自己点谱面 / 拖进度条，`mLastMeasure` 会重新被赋真值，不受影响。
     */
    public void resetLastMeasure() {
        mLastMeasure = -1;
        mLastOffset = 0;
        mLastVoice = 0;
    }

    public int lastOffset() {
        return mLastOffset;
    }

    public int lastVoice() {
        return mLastVoice;
    }

    /**
     * 自然结束回曲首时置 true：视口与光标已经由 {@link #moveToStart} 摆到曲首/段首，
     * initPage 那条异步回执里就不能再把胶囊画回**演奏末尾**的位置。用完即清。
     */
    private boolean mSkipRestoreCursor;

    public void skipRestoreCursor() {
        mSkipRestoreCursor = true;
    }

    /**
     * 退出演奏时把金色胶囊画回最后位置，**视口完全不动**。
     *
     * 不需要记 scrollY 再延迟拉回：{@link #setPerformMode} 在切回预览时冻结了
     * 所有非强制滚动，这几帧不会产生位移，恢复逻辑只需"把胶囊画回去"。
     */
    private void restorePreviewCursor() {
        if (mSkipRestoreCursor) {
            mSkipRestoreCursor = false;
            return;
        }
        if (mLastMeasure >= 0) {
            moveCursorAt(mLastMeasure, mLastOffset, mLastVoice, false);
        }
    }

    /**
     * 把**所有**预览胶囊藏起来（含重复节点）。
     *
     * 同一 id 可能被建出两份（drawSheet 清空 SVG 后缓存引用重建，旧节点没走），
     * 渲染器只会把"当前正在用"的那份设回 visible，多余的会停在旧位置变成第二个光标。
     * 所以这里全量藏，再由 `applyCursorStyle()` 的 `reissueCursor()` 按最新位置把
     * 当前那一枚画回来 —— 顺序不能反。
     */
    private static final String HIDE_TRAIL_CURSORS_JS =
            "(function(){"
            + "var p=document.querySelectorAll('#sheet-cursor-preview');"
            + "for(var i=0;i<p.length;i++)p[i].setAttribute('visibility','hidden');"
            + "})()";

    /**
     * 按最后已知位置把预览光标重新画出来（不改模式、不滚视口）。
     *
     * 用在 `applyCursorStyle()` 跑完 `HIDE_TRAIL_CURSORS_JS` 之后：那一步会把预览胶囊
     * 一起藏掉，而"用户点了谱面/拖了进度条"的静态光标没有任何东西会把它再画回来。
     * 演奏态不补：那里由引擎的 moveCursor2 画光标。
     */
    private void reissueCursor() {
        if (mDestroyed || !mPageReady || mPerformMode) return;
        if (mLastMeasure < 0) return;
        Log.i(TAG, "补画预览光标 -> 第 " + (mLastMeasure + 1) + " 小节");
        // 走 moveCursorAt（scroll=false → 报文带 wb_no_scroll）而不是 moveCursor2：
        // 后者只带 forceScroll=false，H5 过 2 秒照样会把这一行顶到视口顶部 ——
        // 用户点第二行定位后，这种"补画"会把画面自己滚上去。
        moveCursorAt(mLastMeasure, mLastOffset, mLastVoice, false);
    }

    /**
     * 确保光标是**唯一那套样式**：金色胶囊（`preview-cursor`）。
     *
     * 只保留胶囊：`changeCursorStyle` 仍然要发（渲染器默认是 normal 细线，
     * 不发就是那根丑线），但永远只发 preview-cursor，不再有"切来切去"。
     *
     * 发完仍然跑一次 `HIDE_TRAIL_CURSORS_JS`：藏掉渲染器留下的其它光标组，
     * 并藏掉重绘后重建的重复胶囊节点；紧接着按最后位置补画一次，保证用户
     * 自己定位的那枚光标不会因为这次清理而消失。
     */
    private void applyCursorStyle() {
        JSONObject d = new JSONObject();
        try {
            d.put("style", "preview-cursor");
        } catch (Exception e) {
            return;
        }
        send("changeCursorStyle", d, (ok, data) -> {
            Log.i(TAG, "光标样式 preview-cursor（统一）-> ok=" + ok);
            safeEval(HIDE_TRAIL_CURSORS_JS);
            // HIDE_TRAIL 藏的是**所有** #sheet-cursor-preview，包括当前正在显示的
            // 那一枚；渲染器只在下次 moveCursor 时才把它设回 visible。用户点完谱面
            // 不动就没人再画 —— 表现为"点一下定位，过几秒光标自己没了"。
            // 这里按最后已知位置补一发。
            if (mLastMeasure >= 0) {
                mUi.postDelayed(this::reissueCursor, 120);
            }
        });
    }

    /**
     * 冻结 H5 的**非强制**自动滚动（forceScroll=false 的那些）。
     *
     * 配套 bundle 补丁在 scrollTo / scrollToScreenPosition 的抑制条件里查
     * window.__wbFreezeAutoScroll。用户主动定位 / 起播 / 滚回曲首都是
     * forceScroll=true，不受这个开关影响。
     */
    private void freezeAutoScroll(boolean on) {
        if (mDestroyed) return;
        safeEval("(function(){window.__wbFreezeAutoScroll=" + (on ? "1" : "0") + ";})()");
    }

    /**
     * 进/出演奏态。
     *
     * 渲染器恒用 preview；本方法只切 Java 侧一个布尔，并管住 H5 的非强制自动
     * 滚动窗口，不会在演奏时再向渲染器切换 mode。
     *
     * 演奏态与预览态共用同一套 preview 渲染，避免重绘时切换线宽、字号和
     * bottomPadding；渲染器只收到 preview，所以不会带入 magic 的 scrollToMeasure
     * 偏移和双谱表光标 y 公式。详见 {@link #mPerformMode}。
     *
     * 冻结窗口这一段仍然要留：退出演奏后 initPage 回执补画光标、
     * clearMeasureHighlight、markTrialResult 这些帧全是 forceScroll=false，
     * H5 却会把当前小节顶到视口顶部，起点不在第一行时就是"先往上小滚一下再滚回来"。
     */
    public void setPerformMode(boolean perform) {
        if (perform == mPerformMode) return;
        mPerformMode = perform;
        Log.i(TAG, "演奏态 -> " + (perform ? "on" : "off") + "（渲染器恒 preview）");
        mUi.removeCallbacks(mUnfreezeScroll);
        if (perform) {
            freezeAutoScroll(false);
            return;
        }
        freezeAutoScroll(true);
        mUi.postDelayed(mUnfreezeScroll, AUTO_SCROLL_FREEZE_MS);
        // 退出演奏没有切换模式带来的 initPage：在这里确保光标仍是胶囊，
        // 并按最后位置画回原位（演奏期间由引擎 moveCursor2 负责）。
        if (mPageReady && !mDestroyed) {
            applyCursorStyle();
            restorePreviewCursor();
        }
    }

    /** 把某小节滚到视口指定位置（"center"/"top"） */
    public void scrollToMeasure(int measure, String align) {
        scrollToMeasure(measure, align, null);
    }

    /** 带回调版本：{@code done} 在 JS 里滚完之后回调（用来量滚动耗时）。 */
    public void scrollToMeasure(int measure, String align, final Runnable done) {
        JSONObject sc = new JSONObject();
        try {
            sc.put("measure", measure);
            sc.put("align", align);
            // 不要那条"当前小节"的浅色高亮；演奏态的小节浅底由 moveCursor2 自己带
            sc.put("need_highlight_measure", false);
            sc.put("forceScroll", true);
        } catch (Exception e) {
            return;
        }
        send("scrollTo", sc, done == null ? null : (ok, data) -> done.run());
    }

    interface Callback {
        void onResult(boolean ok, Object data);
    }

    private void send(String action, Object data, Callback cb) {
        JSONObject m = new JSONObject();
        final int id = mSeq++;
        try {
            m.put("id", id);
            m.put("type", "request");
            m.put("resolved", false);
            m.put("rejected", false);
            m.put("action", action);
            if (data != null) m.put("data", data);
        } catch (Exception e) {
            Log.e(TAG, "构造报文失败", e);
            return;
        }
        if (cb != null) mPending.put(id, cb);
        String b64 = Base64.encodeToString(m.toString().getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP);
        final String js = "WebViewJSBridge.onMessageReceived(__fromB64(\"" + b64 + "\"))";
        // 光标是演奏中唯一的高频下发（每 46ms 一次），单独统计完整往返：
        // 下发 → evaluateJavascript 回调（此时 JS 里 moveCursor2 已跑完，含滚动）。
        final boolean timed = "moveCursor2".equals(action);
        final long t0 = android.os.SystemClock.elapsedRealtime();
        android.webkit.ValueCallback<String> vcb = timed ? value -> {
            // JS 这一帧跑完了，放行下一帧
            mCursorInFlightAt = 0;
            long dt = android.os.SystemClock.elapsedRealtime() - t0;
            mCursorRtCount++;
            mCursorRtSum += dt;
            if (dt > mCursorRtMax) mCursorRtMax = dt;
            if (mCursorRtCount % 100 == 0) {
                Log.i(TAG, "光标渲染往返：已 " + mCursorRtCount + " 次，平均 "
                        + (mCursorRtSum / mCursorRtCount) + "ms / 最大 " + mCursorRtMax + "ms");
            }
            // 在飞期间被丢掉的那一帧在这里补发：不等下一次音频回调，
            // 否则低端机上一次慢往返就能让光标停住半秒
            flushDeferredCursor();
        } : null;
        // 已经在主线程就直接跑：演奏中光标每 46ms 下发一次，多一次 post
        // 就多一跳排队（低端机上这一跳能攒出几百毫秒的滞后）。
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            safeEval(js, vcb);
        } else {
            mUi.post(() -> safeEval(js, vcb));
        }
    }

    private void reply(String action, int id, boolean ok, Object data) {
        JSONObject m = new JSONObject();
        try {
            m.put("action", action);
            m.put("data", data == null ? JSONObject.NULL : data);
            m.put("id", id);
            m.put("type", "response");
            m.put("resolved", ok);
            m.put("rejected", !ok);
        } catch (Exception e) {
            Log.e(TAG, "构造回包失败", e);
            return;
        }
        String b64 = Base64.encodeToString(m.toString().getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP);
        final String js = "WebViewJSBridge.onMessageReceived(__fromB64(\"" + b64 + "\"))";
        // 已经在主线程就直接跑：演奏中光标每 46ms 下发一次，多一次 post
        // 就多一跳排队（低端机上这一跳能攒出几百毫秒的滞后）。
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            safeEval(js);
        } else {
            mUi.post(() -> safeEval(js));
        }
    }

    // ---------------- JS → 原生 ----------------

    /** jsbridge.js 的 _postMessage 会调到这里（运行在 binder 线程，必须切回 UI） */
    @JavascriptInterface
    public void callHandler(String handler, String payload) {
        if (!"onMessageReceived".equals(handler)) return;
        mUi.post(() -> {
            try {
                JSONObject m = new JSONObject(percentDecode(payload));
                String type = m.optString("type");
                String action = m.optString("action");
                int id = m.optInt("id");

                if ("response".equals(type)) {
                    Callback cb = mPending.remove(id);
                    if (cb != null) cb.onResult(m.optBoolean("resolved"), m.opt("data"));
                    return;
                }
                if ("request".equals(type)) handleJsRequest(action, id, m.opt("data"));
            } catch (Throwable t) {
                Log.e(TAG, "解析 JS 报文失败：" + t);
            }
        });
    }

    private void handleJsRequest(String action, int id, Object data) {
        switch (action) {
            case "jsLog": {
                String msg = String.valueOf(data);
                Log.d("SheetJS", msg);
                if (mListener != null) mListener.onJsLog(msg);
                reply(action, id, true, "");
                break;
            }
            case "onRowsDrawn": {
                int rows = 0, last = -1;
                if (data instanceof JSONObject) {
                    JSONObject o = (JSONObject) data;
                    rows = o.optInt("row", 0);
                    last = o.optInt("last", -1);
                } else {
                    // 兼容老报文（纯行数）
                    try {
                        rows = Integer.parseInt(String.valueOf(data));
                    } catch (Exception ignored) {
                    }
                }
                if (last >= 0) mLastDrawnMeasure = last;
                // 诊断用：每批行打一行（含相对首屏的耗时），用来量"整页画完要多久"。
                // 只在 D 级，正式使用时不会污染 I 级日志。
                long now = android.os.SystemClock.elapsedRealtime();
                if (mRowLogT0 == 0) mRowLogT0 = now;
                Log.d(TAG, "行批次 rows=" + rows + " 到第 " + (last + 1) + " 小节 t=+"
                        + (now - mRowLogT0) + "ms");
                if (mListener != null) mListener.onRowsDrawn(rows, last);
                reply(action, id, true, "");
                break;
            }
            case "onSheetOverallLoaded":
                Log.i(TAG, "谱面渲染完成");
                reply(action, id, true, "");
                // 渲染完成后再补一次光标样式：drawSheet 的重绘会重置光标元素，
                // 这次回调是"整页真的画完了"的时机，比 drawSheet 的 response 更晚、
                // 更稳（要等全部谱表都画出来，光标才定位得准）
                applyCursorStyle();
                if (mListener != null) mListener.onSheetRendered();
                break;

            case "onSheetSettled":
                // MutationObserver 判定 DOM 连续 600ms 无变化（或 20 秒封顶）
                Log.i(TAG, "画面已稳定（可以重画曲首光标了）");
                reply(action, id, true, "");
                if (mListener != null) mListener.onSheetSettled();
                break;

            case "updateLocalStorage":
                // 原 App 用它把谱面滚动位置等状态写回原生端持久化。
                // 单机版不依赖服务端，直接忽略即可，不影响渲染。
                reply(action, id, true, "");
                break;

            case "getAlignNotes":
                // 书签功能用：给定 (measure,voice,offset) 取同和弦的其它声部音符。
                // 单机版不做书签，返回空数组即可。
                reply(action, id, true, new org.json.JSONArray());
                break;

            case "onNoteClick2": {
                // 点谱面定位：JS 从点击坐标（已含页面滚动/缩放换算）命中音符后，
                // 把 (measure, offset, voice) 报上来 —— 这三个值与原版 App 走的
                // 是同一条通道，可直接喂给 setPlayPositionWithoutCountDown。
                int measure = -1, offset = 0, voice = 0;
                if (data instanceof JSONObject) {
                    JSONObject d = (JSONObject) data;
                    measure = d.optInt("measure", -1);
                    offset = d.optInt("offset", 0);
                    voice = d.optInt("voice", 0);
                }
                Log.i(TAG, "点谱面 -> measure=" + measure
                        + " offset=" + offset + " voice=" + voice);
                reply(action, id, true, "");
                if (mListener != null && measure >= 0) {
                    mListener.onNoteClick(measure, offset, voice);
                }
                break;
            }

            case "wbSectionBegin": {
                reply(action, id, true, true);
                // ⚠ 一律在 lambda 内部取一次 mListener 再判空，不要写成
                // `mUi.post(() -> mListener.xxx())`：那种写法是在**执行时**才读字段，
                // 而排队期间用户完全可能按返回（destroy() 把 mListener 置 null），
                // 于是这几毫秒后的回包直接 NPE 崩掉页面。
                final Listener l0 = mListener;
                if (l0 != null) mUi.post(l0::onSectionBegin);
                break;
            }

            case "onChangeMagicCustomSection":
            case "wbSectionDragged": {
                // 选段完成 / 拖动微调完：{start:{measure,voice,offset}, end:{...}}
                reply(action, id, true, true);
                JSONObject bag = (data instanceof JSONObject) ? (JSONObject) data : new JSONObject();
                JSONObject st = bag.optJSONObject("start");
                JSONObject en = bag.optJSONObject("end");
                final Listener l1 = mListener;
                if (l1 != null && st != null && en != null) {
                    final JSONObject fst = st, fen = en;
                    mUi.post(() -> l1.onSectionChanged(fst, fen));
                }
                break;
            }

            case "onSectionKeys": {
                // 渲染器选段确认后上报的"段内音符集合"（"measure|offset|voice"）。
                reply(action, id, true, true);
                JSONObject kb = (data instanceof JSONObject) ? (JSONObject) data : new JSONObject();
                final JSONArray ka = kb.optJSONArray("keys");
                final Listener l2 = mListener;
                if (l2 != null) {
                    mUi.post(() -> l2.onSectionKeys(ka));
                }
                break;
            }

            case "isNotePositionInRange": {
                // 渲染器的选段编辑模式会问"这个位置在不在允许的范围内"，
                // 它读的是回包里的 in_range 字段。这里必须回 true，否则
                // handleNoteClick 每次都判"超出范围"，点什么都不会发生。
                JSONObject r = new JSONObject();
                try {
                    r.put("in_range", true);
                } catch (Exception ignored) {
                }
                reply(action, id, true, r);
                break;
            }

            case "getChordGroupCountInRange":
                // 渲染器用它卡"至少 4 个音团才让人选"；我们不设这道门槛，
                // 给个大数让它直接放行（选 1 个音也算合法区间）。
                reply(action, id, true, 999);
                break;

            default:
                // 其余都是跟弹评测 / 社区评论 / VIP 引导相关的回调，单机版一律不支持。
                reply(action, id, false, "offline shell: 不支持 " + action);
                break;
        }
    }

    /**
     * 还原 JS encodeURIComponent 的结果。
     * encodeURIComponent 只放行 A-Za-z0-9-_.!~*'()，其余一律 %XX，
     * 因此这里按「%XX → 字节、其余字符按 UTF-8 单字节」拼回原始字节再整体解码。
     */
    private static String percentDecode(String s) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int h = Character.digit(s.charAt(i + 1), 16);
                int l = Character.digit(s.charAt(i + 2), 16);
                if (h >= 0 && l >= 0) {
                    bo.write((h << 4) | l);
                    i += 2;
                    continue;
                }
            }
            byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
            bo.write(b, 0, b.length);
        }
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }
}
