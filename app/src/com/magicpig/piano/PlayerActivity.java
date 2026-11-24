package com.magicpig.piano;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 播放器页面：上方 VexFlow 渲染的曲谱，下方走带控制。
 *
 * 单机化的边界：这一页没有任何网络请求、没有登录态、没有 VIP / 付费入口。
 * AndroidManifest 里也刻意不声明 INTERNET 权限 —— 从平台层面杜绝联网。
 */
public class PlayerActivity extends Activity {

    public static final String EXTRA_ENC = "enc";
    /** 可选：调用方已知的乐曲名（曲集目录里有），省得再从 XML 里猜 */
    public static final String EXTRA_TITLE = "title";

    private static final String TAG = "Player";

    private SheetView mSheet;
    private TextView mStatus;
    /** 进度条滑块：金色圆点 + 当前小节号（见 MeasureThumb） */
    private MeasureThumb mMeasureThumb;
    private View mLoading;
    /** 加载卡片里的文案（恢复进度时改成「正在恢复上次进度…」） */
    private TextView mLoadingText;
    /**
     * 恢复存档期间的全屏拦截层：透明、可点（把点击吃掉），挡住谱面点按 /
     * 横向划动 / 顶栏按钮 / 底部进度条，避免和"等画面稳定"的重画抢状态。
     */
    private View mRestoreOverlay;
    private boolean mRestoring;
    /**
     * 恢复要跳到的目标 {measure, voice, offset}；null = 非恢复流程。
     *
     * 解锁条件是「目标小节**真的滚到屏幕里**且画面安静」，不是任何定时器 ——
     * 低性能设备（T618/Chromium 74）上"判稳 20s 封顶"先到、整页还在画，
     * 那时撤卡片用户会看到谱面停在原地、点了也没反应，跳转要再等几十秒。
     *
     */
    private int[] mRestoreTarget;
    /** 恢复目标已真的滚到屏幕内（跳转已发生） */
    private boolean mJumpConfirmed;
    private long mRestorePollStart;
    private long mRestoreLastScrollAttempt;
    /** 上一次轮询时渲染器画到的小节 + 时间戳：用来判"渲染是不是停了" */
    private int mRestoreLastDrawn = -1;
    private long mRestoreLastDrawnAt;
    /** 目标行已画出、但连续几次都判"不在视口"（滚动失效）的计数 */
    private int mRestoreDrawnStuck;
    /**
     * 恢复等待的**总**上限。原来是 90 秒（"兜底，正常走不到"），但那正是用户报
     * "卡在正在准备第 x/y 小节上不动、不点取消就一直等"的来源 —— 手机上长曲
     * （384 小节）逐行渲染本来就要几十秒，90 秒内卡片会一直停在某个数字上。
     * 现在有更聪明的三条提前退出（见 {@link #mRestorePollRun}：目标上屏 /
     * 目标已画但滚动失效 / 渲染停滞），这个上限只是最后的硬保底。
     */
    private static final long RESTORE_POLL_MAX_MS = 25000;
    /**
     * 渲染"停滞"判定：轮询发现**已画到的小节连续这么久没变**、且还没画到目标，
     * 就认为渲染器卡住了（真机上偶尔会遇到逐行绘制停在中途），立刻解锁，
     * 不让用户对着一个不动的数字干等。
     */
    private static final int RESTORE_STALL_MS = 6000;
    /**
     * 独立看门狗：只负责"轮询链万一断了"（异常 / Handler 被误摘）时也能解锁。
     * 必须比 {@link #RESTORE_POLL_MAX_MS} 大，正常路径永远是轮询先解锁。
     */
    private static final long RESTORE_WATCHDOG_MS = 30000;
    /** 目标没上屏时，隔多久重试一次滚动 */
    private static final int RESTORE_SCROLL_RETRY_MS = 2000;
    /** 跳转发生后，等画面安静最多再等这么久（onSheetSettled 的兜底） */
    private static final int JUMP_QUIET_FALLBACK_MS = 8000;
    private static final int RESTORE_POLL_INTERVAL_MS = 500;
    /** 两个 loading（冷启动 / 恢复进度）共用：当前是否处于遮罩态 */
    private boolean mLoadingBusy;
    private SeekBar mSeek;
    /** 底部那条：进度条 + 左右两个边缘跳转（本曲首 / 本曲尾） */
    private View mBottomBar;
    /** 换曲钮在**顶栏**、曲名左右 */
    private TransportButton mPrevSheetBtn;
    private TransportButton mNextSheetBtn;
    /** 曲首 / 曲尾跳转在**底栏**、进度条两端 */
    private TransportButton mToStartBtn;
    private TransportButton mToEndBtn;
    /** 正在切到本曲集的上一首/下一首（切换期间别再动引擎，见 openNeighbor） */
    private boolean mSwitching = false;
    /**
     * 演奏中"再按一次返回"的确认窗口。
     *
     * 2 秒偏紧：用户看到 toast、意识到该干嘛、再抬手按下去，实测很容易超时
     * （超时后又是一次"第一次"，体感就是"按了三四下都退不出去"）。
     * 3 秒既不误触也够从容；再长就起不到防误触的作用了。
     */
    private static final long BACK_CONFIRM_MS = 3000;
    /** 上一次按返回的时间（0 = 没按过）；离开本页时要复位，否则会把残留状态带到下一次 */
    private long mBackConfirmAt;
    /** 演奏中最近一次光标位置（-1 = 还没有）：视口跟随定时任务靠它拉回视口 */
    private int mLastCurM = -1, mLastCurO, mLastCurV;
    /**
     * 本次演奏光标**离开过起点**吗。用它判"有没有真的演奏推进进度"：
     * 引擎一开跑会一路报起点的空闲假位置，那种不算；只有 onCursor 报出一个与
     * 起点不同的位置（弹对了、光标往前走）才置 true。需求：全程没推进（一直落在
     * 第一个音上）算"没演奏"，和"没收到任何声音"一样直接静默退出、不弹结果窗。
     */
    private volatile boolean mTrialProgressed;
    /** 本次演奏是否判定为"没演奏"（静默退出，不出结算卡）；onFinished 兜底用 */
    private volatile boolean mTrialNoPlay;
    /** 建会话期间用户按了停止（= 取消这一轮），失败分支别再报"开不了演奏" */
    private boolean mTrialCanceled;
    private Ui.CircleButton mPlayBtn;
    private Ui.CircleButton mFav;
    private Ui.CircleButton mHandBtn;
    private Ui.CircleButton mTrialBtn;
    private TextView mTitle;
    /** 顶栏曲名下那行速度标记（"Adagio maestoso ♩=50"）—— 从首小节 XML 里摘出来显示的 */
    private TextView mTempoChip;
    /** 根布局：演奏态要整页换底色（原版连顶栏、边距一起铺暖羊皮纸） */
    private LinearLayout mRoot;

    /** UFP 演奏会话是否在跑（停止/收尾要靠它判断） */
    private boolean mUfpRunning = false;
    /** 播放位置当前是否还在视口里（看得见就继续跟随） */
    private boolean mPlayPosVisible = true;
    /** 起播后的第一次光标允许滚动到光标那一行，之后不再滚 */
    private boolean mPreviewScrollPending = false;
    /** 最后一次用户动作（滚动/点击）的时刻；超过 IDLE_RESUME_MS 没动作就拉回播放位置 */
    private long mLastUserActionMs = 0;
    /** 上一次查询"播放位置可见吗"的时间（节流 250ms） */
    private long mLastVisibleQueryMs = 0;
    private static final long VISIBLE_QUERY_MS = 250;
    /** 演奏中查"光标还在视口里吗"的间隔：比试听宽，避免和光标下发抢 WebView */
    private static final long TRIAL_QUERY_MS = 500;
    /** 连续多少次查到"看不见"才真的暂停跟随（单次抖动不算，实测可见性会在边缘处跳变） */
    private int mInvisibleHits = 0;
    private static final int INVISIBLE_HITS_PAUSE = 3;
    /**
     * 起播/跳转之后的"跟随宽限期"：这段时间里不因为可见性查询而暂停跟随。
     * 因为跳转后谱面要滚动过去，滚动动画期间位置本来就在视口外，
     * 若照常判定会连着几次判"看不见" → 暂停 → 光标卡在半路。
     */
    private long mFollowGraceUntilMs = 0;
    private static final long FOLLOW_GRACE_MS = 1500;
    /** 用户多久没动作就把视口拉回播放位置（毫秒） */
    private static final long IDLE_RESUME_MS = 3000;

    /** 本进程内因 WebView 渲染进程消失而重建页面的次数（防反复重建死循环） */
    private static int sRenderRebuilds;

    // ---- 自由选段（原版"自由选段"的同一套机制：音符级 start/end）----
    /** 是否已经有生效的选段（对试听和演奏都生效） */
    private boolean mSecOn = false;
    /** 是否正在点谱面选段（第一次点起点，第二次点终点） */
    private boolean mSecPicking = false;
    private int mSecStartM = -1, mSecStartO = 0, mSecStartV = 0;
    private int mSecEndM = -1, mSecEndO = 0, mSecEndV = 0;
    /**
     * 渲染器上报的「段内音符集合」("measure|offset|voice")，null = 还没收到。
     * 这是唯一跨声部也正确的依据：渲染器按**真实时长**算（listAllChordContainer），
     * 左右手天然一致；而 offset 只是"声部内序号"，左右手各自编号，单独比必然出错。
     */
    private java.util.Set<String> mSecKeys;
    private Ui.CircleButton mSectionBtn;

    /** 本次演奏的起点小节（引擎还没听到音时会报 0，用它把这些报告挡掉） */
    private int mTrialStartMeasure = 0;
    /** 本次演奏起点在起点小节里的偏移（用来挡掉引擎空闲时的 (0,0,0) 假位置） */
    private int mTrialStartOffset = 0;
    /** 本次演奏起点的声部：连同小节/偏移一起用来判"光标有没有离开过第一个音" */
    private int mTrialStartVoice = 0;
    /** 起点守卫只在开跑后这一小段生效，之后一律跟随真实弹奏（见 onCursor） */
    private static final long TRIAL_START_GUARD_MS = 1200;
    private volatile long mTrialStartedAtMs;

    private volatile boolean mTrialRunning;
    /**
     * 已经点了"停止"、native ufpFinish 还没回来（结算中）。
     * 这段时间按钮必须是灰的：否则用户以为没反应会反复点，而每一次点都会
     * 再排一次 stop()，状态越点越乱。它同时也是 openEntryButtons() 的输入
     * —— 结算中不能被"渲染回调"之类的迟到事件把按钮点亮。
     */
    private volatile boolean mTrialSettling;
    /** 从点击试听到 Player 真正退出都置位，覆盖 mPlayer 尚未创建的起播窗口。 */
    private volatile boolean mPreviewActive;
    /** 这首曲子两只手是否都有音符（决定手型按钮能不能点） */
    private volatile boolean mHandCanSplit = true;
    /** 运行时申请录音权限的请求码（onRequestPermissionsResult 里按它分流） */
    private static final int REQ_MIC = 41;

    /** 谱面整页是否已画完（跳转/恢复要等它，避免和渲染抢 CPU） */
    private boolean mRendered = false;
    /**
     * 「曲首光标待放」：已决定要放，但还在等画面稳定（绘制/重排彻底停下）。
     *
     * ⚠ 别用任何一个渲染回调当"画完了"的判据：真机实测 `onSheetOverallLoaded`
     * 八次进谱只来一次，而且大谱面它 4.6 秒就报完成、DOM 其实还在变；
     * `onRowsDrawn` 同样时有时无。只有"谱面高度连续不变"能真实反映这件事，
     * 由 {@link SheetView#beginSettleWatch()} 在 JS 侧盯着。
     */
    private boolean mPlaceAtStartPending;
    /** 正在由程序把光标放到曲首（不算用户定位，见 placeCursorAt）。 */
    private boolean mPlacingStart;
    /** 曲首光标的可见性锁是否已加上（用户一自己定位就解掉）。 */
    private boolean mCursorLocked;
    /** 演奏时是否还画单音光标（原版只铺小节高亮，不画单音） */
    private boolean mTrialNoteCursor = true;
    /**
     * 调试启动（带 secsm / startm / endm / autotrial / selffeed 等 extra）时
     * **不写 recent.json**。否则每次回归测试退出都会把调试用的选段和光标
     * 落到用户存档里：下次打开这首曲子自动恢复选段，「到开头」只到段首、
     * 段外定位又被 inSectionGap 挡掉。
     */
    private boolean mDebugSession;

    /**
     * 试听的进度回调。引擎在自己的线程里回调，一律切回 UI 再动控件。
     */
    private final AlgoEngine.ProgressListener mProgress = new AlgoEngine.ProgressListener() {
        @Override
        public void onProgress(int measure, int offset, int voice) {
            // 选段终点完全由 native setSection 在合成层负责（段外 PCM 不生成）。
            // 这里**不做**软件层截断：按 PCM 块（约 0.56s）判终点精度更差，
            // 还可能在"段内音符集合"与 native 终点不一致时抢跑、切掉段尾音。
            runOnUiThread(() -> updateProgress(measure, offset, voice, true));
        }

        @Override
        public void onEnded() {
            // native 播完（整曲曲末 / setSection 段尾）都走这一条
            runOnUiThread(() -> {
                if (!uiGone()) finishPreviewAtStart();
            });
        }

        @Override
        public void onError(String msg) {
            runOnUiThread(() -> {
                if (uiGone()) return;
                mPlayBtn.setState("ic_sheet_preview_play.png", "试听");
                setPlaybackUi(false);
                toast(msg);
                // 起播排队期失败：上次存档还没恢复，这里补一次机会
                restoreSavedState();
            });
        }
    };
    /** 首屏谱表是否已经出现（遮罩在这一刻就可以撤了） */
    private boolean mFirstRowsShown = false;
    /**
     * 撤载入遮罩前至少要画出的行数。一屏通常 3 行，1 行还不够铺满视口；
     * 顺带让载入框的「正在准备第 x/y 小节…」有机会被用户看到。
     * 短谱行数不足时由 onSheetRendered / 10 秒兜底撤，不会卡死。
     */
    private static final int FIRST_SCREEN_ROWS = 3;
    /** 手型：0=合手（默认） 1=右手 2=左手，取值与原生引擎一致 */
    private int mHandType;
    /** 手型按钮的循环顺序：合手 → 右手 → 左手 → 合手 */
    private static final int[] HAND_CYCLE = {0, 1, 2};

    private String mEncPath;
    private volatile boolean mLoaded;
    /** 手指正按在进度条上：这段时间不让 ticker 把进度条和状态文字抢回去 */
    private volatile boolean mScrubbing;
    /** 最近一次已知的小节（0-based）：停止/跳转后 ticker 不再回调，状态行靠它刷新 */
    private volatile int mCurMeasure;
    /** 上次退出时的光标位置 {measure, offset, voice}；null = 本次不恢复 */
    private int[] mResumeCursor;
    /**
     * 上次阅读现场是否**已经真正从 LibraryStore 读出来**（bootstrap 里赋值后才置 true）。
     *
     * 时序坑：`restoreSavedState()` 由 `onSheetRendered`（JS 渲染
     * 完成）触发，而 `mResumeCursor` 是在 bootstrap 的后台线程里读完、再 `runOnUiThread`
     * 赋值 —— 小曲子渲染只要 1~2 秒，而 bootstrap 还要在后台读目录、数小节，于是渲染先完成、存档还没读出来，
     * 那时 `mResumeCursor == null` 被当成"本来就没存档"，`mCursorRestored = true` 永久放弃；
     * 等 bootstrap 赋完值也没人再调，用户就再也看不到"是否载入上次状态"的询问。
     * 现在存档没读完就**先不判断**，由 bootstrap 赋值后补调一次。
     */
    private boolean mResumeScanned;
    /** 上次退出时仍生效的选段 {startM,startO,startV,endM,endO,endV}；null = 没有 */
    private int[] mResumeSection;
    /** 恢复只做一次：首屏画完后定位过去，之后的重绘不再把用户拽走 */
    private boolean mCursorRestored;
    /** 「已排了一次延迟恢复」：试听/演奏中跳过后允许重试，但别重复排队 */
    private boolean mRestoreScheduled;
    /** 进谱时"是否载入上次状态"的浮层；null = 没在询问 */
    private View mRestoreBar;
    private TextView mRestoreNo;
    private int mRestoreLeft;

    /**
     * 统一的"等目标小节上屏"等待：**恢复存档**和**拖进度条跳转**共用一套。
     *
     * 两者耗时都在同一件事上 —— 渲染器按小节顺序逐行画，目标在很后面时
     * `scrollTo` 只把请求缓存起来（`lastSyncScrollCache`），要等那一行画完才真的滚。
     * 所以共用同一张载入框（文案「正在准备第 x 小节…」+ 取消按钮），
     * 轮询 `queryMeasureReady`，上屏就撤；超时也撤，绝不把页面永久锁死。
     *
     * 存的是 **{measure, voice, offset}**（与 `queryMeasureReady` / `placeCursorAt`
     * 一致）；只有 `moveCursor` 是 (measure, offset, voice)，调用时要换序。
     */
    private int[] mJumpTarget;
    /** 取消跳转时回到的位置（发起跳转前光标所在处；-1 = 之前没定位过） */
    private int mJumpBackMeasure = -1, mJumpBackVoice, mJumpBackOffset;
    /** 载入框里的「取消」按钮（只在恢复/跳转等待期间显示） */
    private TextView mLoadingCancel;
    private long mJumpStartAt;
    private long mJumpLastScrollAt;
    private static final long JUMP_WAIT_MAX_MS = 60000;
    /** 补发滚动的间隔：渲染器会自己缓存 scrollTo，所以只在久等无动静时补一次 */
    private static final long JUMP_WAIT_RETRY_MS = 8000;
    /**
     * 渲染器已经**画到**的小节（0-based，-1 = 还不知道）。
     *
     * 由 `onRowsDrawn` 每批行带上来（JS 侧从这一批的 measure XML id 反查索引）。
     * 载入框用它显示「正在准备第 38/334 小节…」—— 只有一个不动的目标号时，
     * 用户没法判断还在不在动。
     */
    private int mWaitDrawnMeasure = -1;
    /** 倒计时每秒一跳；页面销毁时随 mUi.removeCallbacksAndMessages(null) 一起清掉 */
    private final Runnable mRestoreTick = new Runnable() {
        @Override public void run() {
            if (mRestoreBar == null || uiGone()) return;
            mRestoreLeft--;
            if (mRestoreLeft <= 0) { finishRestore(false); return; }
            if (mRestoreNo != null) mRestoreNo.setText("不用 (" + mRestoreLeft + ")");
            mUi.postDelayed(this, 1000);
        }
    };
    /** 页面级 UI Handler：所有 postDelayed 都走它，onDestroy 统一清掉，避免持有已销毁的 Activity */
    private final android.os.Handler mUi =
            new android.os.Handler(android.os.Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        mEncPath = getIntent().getStringExtra(EXTRA_ENC);
        // 这些都是 HomeActivity.open() **条件透传**的调试 extra（正常从曲库
        // 点开曲子不会带）。trialdelay / autostop 是无条件透传的默认值，别算进来。
        android.os.Bundle dbg = getIntent().getExtras();
        mDebugSession = dbg != null && (
                dbg.containsKey("secsm") || dbg.containsKey("startm")
                || dbg.containsKey("endm") || dbg.containsKey("selffeed")
                || dbg.containsKey("autotrial") || dbg.containsKey("feedsilence")
                || dbg.containsKey("nocursor") || dbg.containsKey("ufptrial")
                || dbg.containsKey("detune") || dbg.containsKey("detuneafter")
                || dbg.containsKey("hand") || dbg.containsKey("nc")
                || dbg.containsKey("feedfrom0"));
        if (mDebugSession) Log.i(TAG, "调试启动：本轮退出不写存档");
        mTrialNoteCursor = getIntent().getBooleanExtra("nc", true);
        // 调试通路（演示/回归）靠这几个 extra 驱动，缺一个行为就完全不同，
        // 所以进来先记一行，排查"自喂没生效"这类问题不用再猜。
        Log.i(TAG, "intent: enc=" + (mEncPath != null ? "有" : "无")
                + " selffeed=" + getIntent().getBooleanExtra("selffeed", false)
                + " feedsilence=" + getIntent().getBooleanExtra("feedsilence", false)
                + " autotrial=" + getIntent().getBooleanExtra("autotrial", false)
                + " startm=" + getIntent().getIntExtra("startm", -1));
        // 调试：--ei detune 2 表示自喂时升两个半音（制造音高错/节奏错）
        UfpTrial.setDetune(getIntent().getIntExtra("detune", 0));
        // 演示：前半段正常弹、后半段升调（弹错）。--ei detuneafter 220
        UfpTrial.setDetuneAfter(getIntent().getIntExtra("detuneafter", 0));

        if (mEncPath == null) mEncPath = defaultSheetPath();

        goFullscreen();
        buildUi();
        bootstrap();
    }

    /**
     * 沉浸式全屏：原版曲谱页是整屏奶油底 + 悬浮控件，没有状态栏/导航栏，
     * 让谱面能铺满整屏、也省出顶栏空间。
     */
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

    private String defaultSheetPath() {
        File dir = new File(getFilesDir(), "sheets");
        File[] subs = dir.listFiles();
        if (subs != null) {
            for (File s : subs) {
                File enc = new File(s, "sheet.xml.enc");
                if (enc.isFile()) return enc.getAbsolutePath();
            }
        }
        return new File(dir, "sheet.xml.enc").getAbsolutePath();
    }

    // ---------------- 界面 ----------------

    private void buildUi() {
        LinearLayout root = mRoot = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.SHEET_BG);

        // ---- 顶部：返回箭头 + 曲名/速度 + 换曲箭头 + 五个功能圆钮 ----
        // 左侧照原版：返回箭头是 btn_library_back_arrow.png（资源本身即 #774B04）；
        // 曲名与速度标记原版由谱面页自己渲染，这里做成顶栏的一块；
        // 曲名左右各一枚细圆环箭头＝曲集内上一首/下一首（从底栏搬来
        // 版式照原版演奏页标题两侧那对）。
        // 右侧一排「实心棕圆 + 白图标 + 圆下文字」：合手 → 演奏 → 试听 → 选段 → 收藏，
        // 其中试听/演奏是按一下就切换的停止钮。
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        // 顶栏压扁：上下内边距 18/8 → 8/5，给曲谱区多让出 ~13dp 高度
        top.setPadding(dp(18), dp(8), dp(26), dp(5));

        ImageView back = new ImageView(this);
        android.graphics.Bitmap arrow = Ui.assetBitmap(this, "btn_library_back_arrow.png");
        if (arrow != null) {
            back.setImageBitmap(arrow);
            back.setScaleType(ImageView.ScaleType.FIT_CENTER);
        } else {
            // 资源缺失兜底：退化成文字箭头
            back.setImageDrawable(null);
        }
        mBackBtn = back;
        Ui.pressHighlight(this, back);   // 返回箭头也有按下反馈（与顶栏圆钮一致）
        back.setOnClickListener(v -> leavePage());
        top.addView(back, new LinearLayout.LayoutParams(dp(34), dp(28)));

        // 返回箭头右侧：曲名 + 速度标记组成一个**左对齐**的竖排块，左右各一枚
        // 细圆环箭头，与**整块**垂直居中 —— 箭头同时跨在标题与速度行两侧，
        // 但整块靠左、不居中。
        //
        // 圆环 24dp 这次放得下：它夹的是两行（曲名 ~53px + 速度 ~29px ≈ 82px），
        // 行高 = max(82, 24dp=84px) ≈ 84px，仍压在右侧圆钮列的 87px 以内。
        // ⚠ 上一版只夹"标题"一行时，24dp 会把顶栏顶高 30px，所以才缩到 16dp；
        //   结构变了尺寸才能回 24 —— 两个数是一对，别单独改。
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setGravity(Gravity.START);

        mTitle = new TextView(this);
        mTitle.setTextColor(Ui.BROWN);
        // 顶栏曲名：实测当前左侧两行(82)已≈右侧按钮列(87)，这里贴着上限略放大一点。
        // 仍走 Ui.sp（fontScale 0.68 保底，与全局字号一致），两机等比、不会撑高顶栏。
        mTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Ui.sp(this, 18f));
        mTitle.setTypeface(Ui.titleFont(this));
        mTitle.setSingleLine(true);
        mTitle.setGravity(Gravity.START);   // 左对齐
        mTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleBox.addView(mTitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // 速度标记：内容来自首小节的 <direction>（TempoMark 从 XML 里摘出来后交到这里）。
        // 直接跟在曲名下面、与曲名左端对齐，用无底色的小字 ——
        // 这里加圆角底色反而像第二个按钮。没有速度标记的曲子整行隐藏。
        // 速度行：一个 TextView，系统字形原样渲染（不放大、不换字体、不自绘）。
        mTempoChip = new TextView(this);
        mTempoChip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Ui.sp(this, 12f));
        mTempoChip.setTypeface(Ui.bodyFont(this));
        mTempoChip.setTextColor(Ui.INK_SOFT);
        mTempoChip.setSingleLine(true);
        mTempoChip.setGravity(Gravity.START);
        mTempoChip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mTempoChip.setIncludeFontPadding(false);
        mTempoChip.setVisibility(View.GONE);
        titleBox.addView(mTempoChip, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        mPrevSheetBtn = new TransportButton(this, TransportButton.PREV_SHEET);
        mNextSheetBtn = new TransportButton(this, TransportButton.NEXT_SHEET);
        mPrevSheetBtn.setOnClickListener(v -> openNeighbor(-1));
        mNextSheetBtn.setOnClickListener(v -> openNeighbor(1));

        LinearLayout navRow = new LinearLayout(this);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        navRow.setGravity(Gravity.CENTER_VERTICAL);   // 不做 CENTER：整组靠左
        LinearLayout.LayoutParams navPrev =
                new LinearLayout.LayoutParams(dp(24), dp(24));
        navPrev.rightMargin = dp(10);
        navRow.addView(mPrevSheetBtn, navPrev);
        navRow.addView(titleBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams navNext =
                new LinearLayout.LayoutParams(dp(24), dp(24));
        navNext.leftMargin = dp(10);
        navRow.addView(mNextSheetBtn, navNext);

        // 曲名上限 = 导航行宽度 − 左箭头(24+10) − 右箭头(24+10)。超过就截断，
        // 保证再长的标题也不会把右箭头挤出屏幕。只在尺寸变化时设一次，避免布局回环。
        navRow.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int avail = (r - l) - dp(34) * 2;
            if (avail > 0 && mTitle.getMaxWidth() != avail) {
                mTitle.setMaxWidth(avail);
                mTempoChip.setMaxWidth(avail);
            }
        });

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        blp.leftMargin = dp(12);
        // 右侧留 16dp：最长曲名（81 字符）会把可用宽度吃满，箭头几乎贴到「合手」，
        // 这里留出的空档是它和功能按钮之间的唯一间隔。
        blp.rightMargin = dp(16);
        top.addView(navRow, blp);

        // 手型按钮（合手/右手/左手）——照原版演奏页顶栏第一个按钮：
        // 棕圆 + 手型图标 + 圆下文字。**点一下就循环切到下一种手型**
        // （合手→右手→左手→合手），不再弹下拉选择框：
        //   · 切换更直接，少一次点击；
        //   · 不弹窗就不会遮住谱面，播放中切换观感更顺。
        // 图标用原 APK 的 icon_advanced_*_hand 合成（与收藏/试听同款 84×84 棕圆）。
        // 右侧一排圆钮从左到右：手型 → 演奏 → 试听 → 收藏。
        // 收藏原来夹在演奏和试听之间，现按你的要求挪到最右（试听后面）。
        // 顺带把间距统一成相邻两个之间 16dp：原先收藏不带边距、试听用
        // leftMargin 20dp，直接换序会变成收藏紧贴试听；最右由 top 的
        // paddingRight 26dp 收边。
        mHandBtn = Ui.circleButton(this, "合手", "ic_hand_both.png");
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.rightMargin = dp(12);
        bindClick(mHandBtn, this::cycleHandType);
        top.addView(mHandBtn.root, hlp);

        // 演奏按钮（魔法演奏/跟弹）：麦克风听现场钢琴，弹到哪儿光标走到哪儿。
        // 图标用原 APK ic_sheet_preview_go_practice 的符形合成到同款棕圆上，
        // 运行中变停止图标（与「试听→停止」同一交互语法）。
        mTrialBtn = Ui.circleButton(this, "演奏", "ic_sheet_trial.png");
        LinearLayout.LayoutParams trlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        trlp.rightMargin = dp(12);
        bindClick(mTrialBtn, this::toggleTrial);
        top.addView(mTrialBtn.root, trlp);

        // 「试听」播放/停止二合一：播放中变成停止
        mPlayBtn = Ui.circleButton(this, "试听", "ic_sheet_preview_play.png");
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        plp.rightMargin = dp(12);
        bindClick(mPlayBtn, this::togglePlay);
        top.addView(mPlayBtn.root, plp);

        // 「自由选段 / 取消选段」——放在收藏左边（原版试炼页有同名功能，
        // 我们复用它的渲染机制：音符级 start/end + 段外压暗 + 两端粉色括号）。
        mSectionBtn = Ui.circleButton(this, "选段", "ic_sheet_section.png");
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.rightMargin = dp(12);
        bindClick(mSectionBtn, this::toggleSection);
        top.addView(mSectionBtn.root, slp);

        // 收藏按钮：本地收藏（不联网）。已收藏时换成实心粉心图标。
        mFav = Ui.circleButton(this, "收藏", "ic_sheet_preview_collect.png");
        bindClick(mFav, this::toggleFav);
        top.addView(mFav.root, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // 四格等宽：把每个按钮的文字标签钉成同一个固定宽度（取最宽字样的实测值）。
        // 不这么做的话，「收藏」↔「已收藏」字数一变，整个按钮就跟着变宽/变窄，
        // 右边的一变宽就把左边的图标依次往左推，位置全跑。
        // 钉死标签宽度后四格等宽、文字在格内居中，换成两个字也不会缩，图标不动。
        equalLabelWidth(mHandBtn, mTrialBtn, mPlayBtn, mSectionBtn, mFav);

        // 曲谱就绪（mLoaded）之前，演奏/试听/选段都没有意义：一律直接置灰，
        // 点不到就不需要解释 —— 点击处理器里不再留重复的状态判断和 toast。
        // 就绪后由 onRowsDrawn / onSheetRendered / 10 秒超时兜底三处放开。
        mTrialBtn.setUiEnabled(false);
        mPlayBtn.setUiEnabled(false);
        mSectionBtn.setUiEnabled(false);

        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mSheet = new SheetView(this);
        mSheet.setUserActionListener(scroll -> {
            // 任何动作（滚谱 / 点音符）都算"用户还在这儿"
            mLastUserActionMs = SystemClock.elapsedRealtime();
            if (scroll) mLastVisibleQueryMs = 0;   // 滚过之后立刻重新判断可见性
        });
        mSheet.setListener(new SheetView.Listener() {
            @Override
            public void onRenderProcessGone() {
                // WebView 的渲染进程没了：这个实例之后只会白屏（滚动/点击全无反应）。
                // 只能重建整个页面 —— intent 还在，重建后会自动重新打开同一首。
                runOnUiThread(() -> {
                    if (uiGone()) return;
                    if (sRenderRebuilds >= 2) {
                        Log.e(TAG, "渲染进程反复消失（已重建 " + sRenderRebuilds + " 次），不再重试");
                        toast("渲染出了点问题，返回再进一次试试");
                        return;
                    }
                    sRenderRebuilds++;
                    Log.w(TAG, "重建页面（第 " + sRenderRebuilds + " 次）");
                    toast("渲染异常，正在重新加载曲谱");
                    mUi.postDelayed(PlayerActivity.this::recreate, 300);
                });
            }

            @Override
            public void onRowsDrawn(int rows, int lastMeasure) {
                // 页面已经在退出：WebView 还在继续画完剩下的行，回调会一路过来。
                // 这时再去动遮罩/按钮没有意义（视图已 detach），直接丢掉。
                if (uiGone()) return;
                // 画到哪了：载入框靠它显示「当前/目标」进度（冷启动时目标位置换成
                // 整曲小节数，所以这里只要有载入框就刷新，不再限定"跳转/恢复中"）
                if (lastMeasure >= 0) mWaitDrawnMeasure = lastMeasure;
                // 只有"等目标小节"（跳转/恢复）才需要刷进度文案；冷启动是统一的
                // 「正在初始化曲谱…」，每批行都去重设一次纯属浪费
                if (mLoadingBusy && (mRestoring || mJumpTarget != null)) updateWaitText();
                // 曲首光标的落笔由 JS 的"画面稳定"检测驱动（SheetView.beginSettleWatch），
                // 这里不需要再做任何计时 —— 这两个渲染回调本身都不可靠。
                if (mFirstRowsShown) return;
                // 首屏按"视口能铺满"算：一屏通常 3 行，只画到第 1 行就撤卡片的话，
                // 下半屏还是一片白，看着像"加载到一半"，而且载入框里那句
                // 「正在准备第 x/y 小节…」根本没机会被看到（它就是靠行批次刷新的）。
                // 所以等到第 FIRST_SCREEN_ROWS 行再撤；短谱（行数不够）由
                // onSheetRendered 兜底。
                if (rows + 1 < FIRST_SCREEN_ROWS) return;
                mFirstRowsShown = true;
                // 首屏这几行已经画出来了，剩下的行会继续在后台补。
                // 不等 onSheetOverallLoaded（整页）：大谱面整页要 7 秒多，
                // 而首屏一秒左右就可见 —— 原版观感快就是因为不等整页。
                Log.i(TAG, "首屏谱表已出（已绘制到第 " + (rows + 1) + " 行）");
                showLoading(false);
                openEntryButtons();
            }

            @Override
            public void onSectionKeys(JSONArray keys) {
                // 渲染器按真实时长算出的"段内音符集合"。存成 Set，点击/停止判断都查它。
                java.util.HashSet<String> set = new java.util.HashSet<>();
                if (keys != null) {
                    for (int i = 0; i < keys.length(); i++) {
                        String k = keys.optString(i, null);
                        if (k != null && !k.isEmpty()) set.add(k);
                    }
                }
                mSecKeys = set;
                Log.i(TAG, "段内音符集合： " + set.size() + " 个");
            }

            public void onSheetRendered() {
                Log.i(TAG, "曲谱渲染完成");
                // 同上：按返回后渲染才收尾是常态（大谱面要好几秒），别再往下走
                if (uiGone()) return;
                mRendered = true;
                // 整页都画完了 → 首屏自然也算"已出"（短谱行数不足 FIRST_SCREEN_ROWS
                // 时就是靠这里撤掉遮罩的）
                mFirstRowsShown = true;
                // 整页画出来了 → 说明渲染恢复正常，重建计数清零（下次崩还能再救一次）
                sRenderRebuilds = 0;
                // 遮罩**在这里才撤**：大谱面（100 万+ 字符的 XML）要 2~3 秒才画完，
                // 之前在 mSheet.show() 之后就撤，中间就是一段白屏，
                // 看着像"卡住/还在载入"。让"正在载入曲谱"一直撑到画面真的出来。
                showLoading(false);
                openEntryButtons();
                // 整页画完再恢复"阅读现场"（选段 + 光标）：提前滚会露白。
                // ⚠ 演奏中绝不能恢复：seekToNote 会画一枚**预览胶囊**，而演奏态
                // 已经有一枚演奏光标（金胶囊），两者并存就是用户看到的"两道光"。
                // 试听/演奏中由 restoreSavedState() 内部跳过，但**不能因此放弃**：
                // 试听按停后还会再触发一次恢复（见 togglePlay / onError）。
                restoreSavedState();
                // 这个回调**不代表**整页真的画完（真机实测它常常紧跟首屏 3ms 才来，
                // 八次进谱只来一次，大谱面 4.6s 报完成而 DOM 还在变），
                // 所以不在这里动曲首光标 —— 交给 beginSettleWatch 的稳定检测。
            }

            @Override
            public void onSheetSettled() {
                // JS 侧判定画面真的停了（谱面高度连续 600ms 不变，或 20 秒封顶）
                if (uiGone()) return;
                PlayerActivity.this.onSheetSettled();
            }

            @Override
            public void onJsLog(String msg) {
                // 渲染器内部日志走 logcat tag SheetJS
            }

            @Override
            public void onNoteClick(int measure, int offset, int voice) {
                // 两个 loading 期间：拦截层已挡住触摸，这里再兜一层，避免 JS 侧
                // 迟到的点击报文把刚恢复的光标位置改掉。
                if (mRestoring || mLoadingBusy) return;
                // 用户主动点谱面定位 = 不要上次的进度：按「不用」收掉恢复浮层
                // （只删档、不摆光标），之后以他点的位置为准。
                autoDismissRestore();
                // 选段模式：只有**点谱面**才画起点/终点。进度条、划动、到头到尾
                // 都走 seekToNote，不能让它们混进这条语义（用户报：
                // 选段时点进度条把边界自动选了）。
                if (mSecPicking) {
                    handleSectionTap(measure, offset, voice);
                    return;
                }
                // 点谱面就地定位：播放中接着播，没播放就从这里开始播
                seekToNote(measure, offset, voice, false);
            }

        });
        // 谱面铺满整块可视区（不做任何裁切）。
        //
        // 曾经试过"缩短 WebView（bottomMargin 92dp）让一屏只放两行"，第三行确实
        // 不露头了，但代价是**第二行自己被切掉半行**（低音谱表只剩上半截），
        // 比露出第三行还难看 —— 用户明确要求回退。渲染器本身没有"每页行数"
        // 这个参数（它按 window.innerHeight 自己排），所以这里维持满高：
        // 宁可底部露出下一行的半截，也不裁掉当前行的内容。
        root.addView(mSheet, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- 底部：一条可点/可拖的进度线 + 一行小字 ----
        // 原版预览页没有进度条（定位全靠点谱面），这里保留它是为了方便快进：
        // 点一下/拖一下就跳到对应小节。行为与点谱面一致——播放中接着播，
        // 没播放就只挪播放头不发声。
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        // 上下各留 5dp：按钮的圆角底几乎贴着最后一行的谱线，视觉上像"长在谱面上"。
        // 左右 20 → 10dp 是因为四个钮自带圆角底
        // 外侧不需要那么宽的留白；省下来的高度正好补在上面这 5dp 里。
        bottom.setPadding(dp(10), dp(5), dp(10), dp(5));

        mSeek = new SeekBar(this);
        mSeek.setMax(1000);
        // 左右各留半个滑块（22dp / 2 = 11dp）的内边距，thumbOffset 用同样的值 ——
        // 否则进度条推到最左/最右时，带数字的圆点会被裁掉一半。
        mSeek.setPadding(dp(11), 0, dp(11), 0);
        tintSeek(mSeek);
        mSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) previewProgress(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                mScrubbing = true; // 按住期间别让 ticker 把进度条和文字抢回去
                // 用户主动拖进度条 = 不要上次的进度：按「不用」收掉浮层（只删档、
                // 不摆光标），之后以他拖到的位置为准。
                autoDismissRestore();
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                // 单纯"点一下"也会走 start→stop，所以抬手时统一落跳。
                // 先解除压制再落跳：jumpToProgress → seekToNote → updateProgress
                // 内部会刷新状态行，若 mScrubbing 还是 true 会被整段跳过，
                // 抬手后就再也刷不回真实小节了（ticker 已停）。
                mScrubbing = false;
                jumpToProgress(sb.getProgress());
            }
        });
        // 触摸区 = 控件高。26dp → 20dp 仍是能一指点中的高度（滑块 18dp），
        // 再矮就要靠扩大热区补偿，没必要。

        // ---- 一排：到开头 | 进度条(x 画在滑块上) | / N | 到结尾 ----
        // 小节信息并进进度条同一排（右端紧挨着它），省一整行高度（约 14dp）：
        // 当前小节 x 画在金色滑块上，右边文字只剩 "/ N"。
        // 上一首/下一首在顶栏（见 buildUi），这里只剩左右两个边缘跳转，
        // 按钮和进度条之间各留 10dp —— 两种图标不相邻，不会点混。
        LinearLayout barRow = new LinearLayout(this);
        barRow.setOrientation(LinearLayout.HORIZONTAL);
        barRow.setGravity(Gravity.CENTER_VERTICAL);

        mToStartBtn = new TransportButton(this, TransportButton.TO_START);
        mToEndBtn = new TransportButton(this, TransportButton.TO_END);

        LinearLayout.LayoutParams lpStart =
                new LinearLayout.LayoutParams(dp(32), dp(26));
        lpStart.rightMargin = dp(10);      // 与进度条拉开，别和滑块粘在一起
        LinearLayout.LayoutParams lpEnd =
                new LinearLayout.LayoutParams(dp(32), dp(26));
        lpEnd.leftMargin = dp(10);

        mStatus = new TextView(this);
        mStatus.setTypeface(Ui.bodyFont(this));
        mStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(this, 10f));
        mStatus.setTextColor(Ui.INK_SOFT);
        mStatus.setGravity(Gravity.CENTER_VERTICAL);
        // 去掉字形上下预留：这行现在只显示 "/ N"（总小节数），不需要为
        // 中文标点/拼音留空间，也不该再占掉进度条的宽度。
        mStatus.setIncludeFontPadding(false);
        mStatus.setSingleLine(true);
        mStatus.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lpStatus = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(26));
        lpStatus.leftMargin = dp(4);

        barRow.addView(mToStartBtn, lpStart);
        barRow.addView(mSeek, new LinearLayout.LayoutParams(0, dp(24), 1f));
        barRow.addView(mStatus, lpStatus);
        barRow.addView(mToEndBtn, lpEnd);

        mToStartBtn.setOnClickListener(v -> jumpToEdge(true));
        mToEndBtn.setOnClickListener(v -> jumpToEdge(false));

        bottom.addView(barRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mBottomBar = bottom;

        root.addView(bottom, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 加载遮罩：冷启动解包/解密期间盖在屏幕正中的一张小卡片。
        // 底部只显示「/ N」（当前小节在滑块上），加载文案没地方放，
        // 就放到用户本来就在盯着的谱面区中心。
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.addView(root, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 全屏拦截层（默认 GONE）先加，载入卡片后加 —— **顺序不能反**：
        // FrameLayout 后加的在上面，卡片被压在拦截层下面时「取消」按钮收不到触摸。
        // 拦截层仍然会吃掉
        // 卡片以外的所有触摸（WebView 点按、横向划动都到不了谱面）。
        View overlay = new View(this);
        overlay.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        overlay.setClickable(true);
        overlay.setVisibility(View.GONE);
        frame.addView(overlay, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mRestoreOverlay = overlay;

        mLoading = buildLoading();
        frame.addView(mLoading, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        setContentView(frame);
    }

    /**
     * 把首小节的速度标记刷到曲名下面那行。没有速度标记就隐藏，
     * 曲名回到垂直居中（顶栏高度不变）。
     */
    private void applyTempoChip() {
        if (mTempoChip == null) return;
        String label = mSheet == null ? null : mSheet.getTempoLabel();
        if (label == null || label.isEmpty()) {
            mTempoChip.setVisibility(View.GONE);
            return;
        }
        // **直接用系统字形渲染**，不做放大、不换字体、不自绘。
        // 形状是各机型自带的 ♩/♪/𝅗𝅥（本来就标准），只是真机上比数字略小 ——
        // 而自绘的椭圆怎么调都嫌胖、span 倍率跨平台永远对不上，绕一圈不如原样。
        mTempoChip.setText(label);
        mTempoChip.setVisibility(View.VISIBLE);
    }




    /**
     * 居中的加载卡片：金色转圈（+ 一行进度文案，由 {@link #setWaitText} 填）。
     * 反色款，与 Ui.toast 同一套配色。文案在一开始是空的 —— 那时连小节数都没有，
     * 写什么都不对（见 updateWaitText 的说明）。
     */
    private View buildLoading() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        // padding 与 toast 对齐：两边都走浮层专用尺寸（手机上不跟着布局系数缩太小）
        box.setPadding(Ui.floatDp(this, 20), Ui.floatDp(this, 18),
                Ui.floatDp(this, 20), Ui.floatDp(this, 16));

        // 反色浮层：半透明棕底 + 金边 + 奶油字。
        // 原来是浅奶油底 + 棕细边 + 灰字，跟浅色谱面几乎同色、浮层感不强。
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.FLOAT_BG);
        bg.setCornerRadius(Ui.floatDp(this, 24));
        bg.setStroke(Ui.floatDp(this, 2), Ui.GOLD_LIGHT);
        box.setBackground(bg);
        box.setElevation(Ui.floatDp(this, 6));   // 与 toast / 恢复条同一浮层高度

        android.widget.ProgressBar bar = new android.widget.ProgressBar(this);
        bar.setIndeterminate(true);
        android.graphics.drawable.Drawable d = bar.getIndeterminateDrawable();
        // 深棕底上原来的棕色转圈会糊成一片，改成金色（呼应金边 / 奶油字）
        if (d != null) d.setColorFilter(Ui.GOLD_LIGHT, android.graphics.PorterDuff.Mode.SRC_IN);
        box.addView(bar, new LinearLayout.LayoutParams(
                Ui.floatDp(this, 32), Ui.floatDp(this, 32)));

        TextView t = new TextView(this);
        // 一开始就是"初始化"阶段：解包 / 引擎初始化 / 解密 / 解析 / 首次排版。
        // 等渲染器画出第一批行，updateWaitText() 会换成「正在准备第 x/y 小节…」。
        t.setText(WAIT_INIT_TEXT);
        t.setTypeface(Ui.titleFont(this));   // 与 toast 同用标题字体，反色浮层更醒目
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.floatSp(this, 21f));
        t.setTextColor(Ui.SHEET_BG);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ui.floatDp(this, 12);
        box.addView(t, tlp);
        mLoadingText = t;

        // 「取消」：只在恢复 / 跳转等待时露出（冷启动加载时没有可取消的动作）。
        // 与成绩卡 / 恢复条按钮共用 Ui.floatButton（圆角、金边、按下态一致，尺寸走紧凑档）。
        // 紧凑档：这张卡只有一行进度文字（21sp），按钮再按 21sp/56dp 会"比正文还大"
        TextView cancel = Ui.floatButton(this, "取消", false, Ui.SIZE_COMPACT);
        cancel.setOnClickListener(v -> onLoadingCancel());
        cancel.setVisibility(View.GONE);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = Ui.floatDp(this, 14);
        box.addView(cancel, clp);
        mLoadingCancel = cancel;
        return box;
    }

    private void showLoading(boolean on) {
        // 恢复进度 / 跳转等待期间：迟到回调（首屏 / 整页渲染完成 / 10 秒兜底）
        // 都会来调 showLoading(false)，不能让它们把正在等的遮罩提前撤掉。
        if ((mRestoring || mJumpTarget != null) && !on) return;
        mLoadingBusy = on;
        if (mLoading != null) mLoading.setVisibility(on ? View.VISIBLE : View.GONE);
        // 「取消」只在有可取消的等待时露出
        if (mLoadingCancel != null) {
            mLoadingCancel.setVisibility(
                    on && (mRestoring || mJumpTarget != null) ? View.VISIBLE : View.GONE);
        }
        // 两个 loading 用同一套：都盖全屏拦截层、都把入口按钮置灰、返回箭头都保持可点。
        // 用户 要求"这两个统一一下"，之前冷启动只有一张小卡片（按钮状态
        // 各不一样、返回能点），恢复中却是全锁（返回也点不了）。
        if (mRestoreOverlay != null) mRestoreOverlay.setVisibility(on ? View.VISIBLE : View.GONE);
        if (uiGone()) return;
        if (on) lockEntryButtons();
        else openEntryButtons();   // 按当前状态推导，不无脑点亮
    }

    /**
     * 演奏结果弹窗：**结算卡 / 成绩卡 / 错误卡是同一个 Dialog**。
     *
     * 先弹「正在结算…」+ 转圈，native ufpFinish 回来后只更新文字变成成绩卡，
     * 全程只弹一次窗。现在换成与 Ui.toast /
     * 载入卡片 / 恢复询问条**同一套反色浮层**：半透明棕底 + 2dp 金边 + 24dp 圆角
     * + 奶油字 + 21sp 标题字体，按钮统一由 `Ui.floatButton` 造（一主二次）。
     *
     * 分数是整张卡唯一保留大号的一行（成绩要一眼看到）；其余文字一律 21sp。
     */
    private static class ResultCard {
        android.app.Dialog dlg;
        TextView title, score, sub, hint;
        /** 结算期：一个金色转圈，标题就是「正在结算…」 */
        LinearLayout waiting;
        /** 成绩卡用的一行两按钮：主按钮（文案随结束方式变）/ 下一曲 */
        LinearLayout actions;
        TextView cont, next;
        /** 错误卡（演奏中断）用的单个「知道了」 */
        TextView ok;
    }
    private ResultCard mResultCard;
    /** 顶栏左上返回箭头：结算期间要跟着一起锁住（Dialog 是 modal，但显式置灰更清楚） */
    private View mBackBtn;
    /** 结算期按返回时卡片内那句反馈的显示时长 */
    private static final long RESULT_BACK_HINT_MS = 1600;
    private final Runnable mResultBackHintClear = () -> {
        // 出分后卡片已被 showTrialResult 改成正式内容，别把正式提示行擦掉
        if (mTrialSettling && mResultCard != null) cardLine(mResultCard.hint, "");
    };

    /** 一行可选内容：空串就整行隐藏，不占位（卡片不会头重脚轻） */
    private static void cardLine(TextView v, String text) {
        v.setText(text == null ? "" : text);
        v.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // 成绩卡 / 载入卡 / 恢复条的所有按钮统一由 Ui.floatButton 构造（主次两款 +
    // 按下态），这里不再另画一套。见 Ui.java「浮层统一件」。

    /** 关掉结果卡，**不动任何状态**：成绩卡两个动作、错误卡「知道了」、点框外/BACK 之外
     *  的所有代码收卡路径都走这里。 */
    private void dismissResultCard() {
        if (mResultCard != null) mResultCard.dlg.dismiss();
    }

    /** 成绩卡主按钮：从当前进度继续演奏；已到曲尾/段尾则先回开头/段首。
     *  文案按结束方式变（按停「继续演奏」/ 自然结束「重新演奏」），动作不变。 */
    private void resultContinue() {
        dismissResultCard();
        // 自然结束时 onFinished 已把光标/播放头回段首或曲首；按停则留在原地。
        // 唯一要额外处理的是"停在末尾"：再进演奏会一开就结束，先收回去。
        int hm = AlgoEngine.get().headMeasure();
        int endM = (mSecOn && mSecEndM >= 0)
                ? mSecEndM : Math.max(0, AlgoEngine.get().getMeasureCount() - 1);
        if (hm >= endM) {
            final boolean sec = mSecOn && mSecStartM >= 0;
            rewindToStart(sec ? mSecStartM : 0, sec ? mSecStartV : 0,
                    sec ? mSecStartO : 0, true);
            Log.i(TAG, "主按钮：已在曲尾/段尾，先回到" + (sec ? "段首" : "曲首"));
        }
        startTrial();
    }

    /** 成绩卡「下一曲」：与进度条的下一曲按钮完全同一条路径。 */
    private void resultNext() {
        dismissResultCard();
        openNeighbor(1);
    }

    /** 只在第一次结算时建一次；之后所有状态变化都只是 setText / setVisibility */
    private ResultCard buildResultCard() {
        ResultCard c = new ResultCard();
        final android.app.Dialog dlg = new android.app.Dialog(this);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        // 外部区域点了不关窗；Dialog 默认 touch-modal，触摸也不会穿透到后面的
        // 谱面/按钮。结算期间连**返回键**也要锁住：
        // 默认 cancelable=true 时按返回会 dismiss 卡片，用户以为演奏没了、又去点
        // 演奏/试听，跟还在跑的 ufpFinish 撞在一起。setCancelable(false) 让 Dialog
        // 自己吃掉 KEYCODE_BACK。成绩出来后由 showTrialResult 放开「点框外关闭」，
        // BACK 则由下面的 OnKeyListener 自己处理。
        dlg.setCanceledOnTouchOutside(false);
        dlg.setCancelable(false);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        // 反色浮层：与 Ui.toast / buildLoading / 恢复询问条同一套底色、金边、圆角。
        // 收紧留白（24/20 → 20/18）：去掉「关闭」后卡片只剩两颗按钮
        // 原来为三颗按钮留的宽度就用不上了。
        box.setPadding(Ui.floatDp(this, 20), Ui.floatDp(this, 18),
                Ui.floatDp(this, 20), Ui.floatDp(this, 18));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.FLOAT_BG);
        bg.setCornerRadius(Ui.floatDp(this, 24));
        bg.setStroke(Ui.floatDp(this, 2), Ui.GOLD_LIGHT);
        box.setBackground(bg);

        TextView h = new TextView(this);
        h.setTypeface(Ui.titleFont(this));
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.floatSp(this, 21f));
        h.setTextColor(Ui.SHEET_BG);
        h.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(h, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        c.title = h;

        // 结算期：一个金色转圈。标题已经写着「正在结算…」，不再重复一行文字，
        // 也不显示任何按钮 —— 没出分之前主按钮/下一曲都还没有意义。
        LinearLayout waiting = new LinearLayout(this);
        waiting.setOrientation(LinearLayout.HORIZONTAL);
        waiting.setGravity(Gravity.CENTER);
        android.widget.ProgressBar bar = new android.widget.ProgressBar(this);
        bar.setIndeterminate(true);
        android.graphics.drawable.Drawable spin = bar.getIndeterminateDrawable();
        if (spin != null) spin.setColorFilter(Ui.GOLD_LIGHT,
                android.graphics.PorterDuff.Mode.SRC_IN);
        waiting.addView(bar, new LinearLayout.LayoutParams(Ui.floatDp(this, 32), Ui.floatDp(this, 32)));
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = Ui.floatDp(this, 14);
        box.addView(waiting, wlp);
        c.waiting = waiting;

        // 分数：整张卡唯一值得一眼看到的大数字，其余文字一律 21sp
        TextView sv = new TextView(this);
        sv.setTypeface(Ui.titleFont(this));
        sv.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.floatSp(this, 40f));
        sv.setTextColor(Ui.SHEET_BG);
        sv.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.floatDp(this, 10);
        box.addView(sv, slp);
        c.score = sv;

        // 分项：音准 / 节奏 / 速度
        TextView subv = new TextView(this);
        subv.setTypeface(Ui.bodyFont(this));
        subv.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.floatSp(this, 21f));
        subv.setTextColor(Ui.alpha(Ui.SHEET_BG, 0.85f));
        subv.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        svlp.topMargin = Ui.floatDp(this, 6);
        box.addView(subv, svlp);
        c.sub = subv;

        // 提示行：错误卡的原因 / 没能评分的说明 / 结算期按返回的反馈
        TextView m = new TextView(this);
        m.setTypeface(Ui.bodyFont(this));
        m.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.floatSp(this, 21f));
        m.setTextColor(Ui.alpha(Ui.SHEET_BG, 0.85f));
        m.setGravity(Gravity.CENTER_HORIZONTAL);
        m.setLineSpacing(Ui.floatDp(this, 4), 1f);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = Ui.floatDp(this, 12);
        box.addView(m, mlp);
        c.hint = m;

        // 两套按钮，互斥显示（结算期两套都隐藏）：
        //   · 成绩卡（有正常演奏推进）→ 一行两按钮：主按钮 + 下一曲
        //   · 错误卡（演奏中断）      → 单个「知道了」：
        // 去掉原来的「关闭」——出分后点卡片外区域或按 BACK 都能收起
        // 没必要为纯关闭再占 1/3 行宽；卡片也相应收窄、留白收紧。
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        // 主按钮文案在 showTrialResult 里按结束方式刷新：
        // 按停 →「继续演奏」，自然弹完 →「重新演奏」，**动作完全相同**。
        TextView cont = Ui.floatButton(this, "继续演奏", true);
        TextView next = Ui.floatButton(this, "下一曲", false);
        cont.setOnClickListener(v -> resultContinue());
        next.setOnClickListener(v -> resultNext());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        blp.setMargins(Ui.floatDp(this, 3), 0, Ui.floatDp(this, 3), 0);
        actions.addView(cont, blp);
        actions.addView(next, new LinearLayout.LayoutParams(blp));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = Ui.floatDp(this, 14);
        box.addView(actions, alp);
        c.actions = actions;
        c.cont = cont; c.next = next;

        TextView ok = Ui.floatButton(this, "知道了", true);
        ok.setOnClickListener(v -> dismissResultCard());
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.topMargin = Ui.floatDp(this, 14);
        box.addView(ok, olp);
        c.ok = ok;

        dlg.setContentView(box);
        // 结算中按返回：Dialog 在 setCancelable(false) 时会把 BACK 自己吃掉，
        // Activity.onBackPressed 根本收不到，界面**毫无反馈** —— 用户以为卡死、连按
        // 好几下。⚠ 反馈必须写在卡片**里面**：卡片是独立的 Dialog 窗口且带 dim，
        // 挂在 Activity 窗口的 Ui.toast 会被它压在后面看不见。
        dlg.setOnKeyListener((d, keyCode, ev) -> {
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK
                    && ev.getAction() == android.view.KeyEvent.ACTION_UP) {
                if (mTrialSettling) {
                    cardLine(c.hint, "结算完成后再操作");
                    mUi.removeCallbacks(mResultBackHintClear);
                    mUi.postDelayed(mResultBackHintClear, RESULT_BACK_HINT_MS);
                } else {
                    d.dismiss();   // 已出分：第一下 BACK 先收卡片，再按才退页面
                }
                return true;
            }
            return false;
        });
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            // 以前三颗按钮要 480dp 才放得下四字标签；去掉「关闭」后只剩两颗，
            // 400dp 足够（分项行「音准 100 · 节奏 100 · 速度 100」实测约 264dp，
            // 卡片内宽 400-40=360dp 也不会折行），卡片不再占大半屏。
            // 仍留窄屏兜底：min(400dp, 屏宽-24dp)。
            int w = Math.min(Ui.floatDp(this, 400),
                    getResources().getDisplayMetrics().widthPixels - Ui.floatDp(this, 24));
            dlg.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        c.dlg = dlg;
        return c;
    }

    /**
     * @param settling    true = 还在等 native 出分：只显示标题「正在结算…」+ 转圈，
     *                    分数/分项/提示/两套按钮全部隐藏，整张卡不可交互，不弹第二次窗。
     * @param resultMode  true = 成绩卡（主按钮 / 下一曲）；false = 错误卡（知道了）。
     * @param userStopped true = 用户按停，false = 引擎自然弹完。只决定主按钮的**文案**
     *                    （继续演奏 / 重新演奏），动作都是 {@link #resultContinue()}：
     *                    自然结束时 onFinished 已经把光标/播放头回到曲首或段首，
     *                    「继续」本来就等价于从头再弹一遍。
     */
    private void showTrialResult(String title, String score, String sub,
                                 String hint, boolean settling, boolean resultMode,
                                 boolean userStopped) {
        if (mResultCard == null) mResultCard = buildResultCard();
        ResultCard c = mResultCard;
        c.title.setText(title);
        cardLine(c.score, settling ? "" : score);
        cardLine(c.sub, settling ? "" : sub);
        cardLine(c.hint, settling ? "" : hint);
        c.waiting.setVisibility(settling ? View.VISIBLE : View.GONE);
        // 结算期一套按钮都不露面，避免"3 个灰按钮 → 1 个知道了"的形态跳变
        c.actions.setVisibility(!settling && resultMode ? View.VISIBLE : View.GONE);
        c.ok.setVisibility(!settling && !resultMode ? View.VISIBLE : View.GONE);
        // 按钮虽然隐藏了，结算中仍一并禁用，避免残留可点状态；出分后一起放开。
        for (TextView b : new TextView[]{c.cont, c.next}) {
            Ui.setDisabled(b, settling);
        }
        // 曲集最后一首没有"下一曲"可去：按钮跟底部那条进度栏的 ▶▶ 一样置灰。
        // 判定口径与 updateTransportState 完全一致
        // 不另算一套 —— 不然会出现"底部是灰的、卡片里还能点"的分裂。
        if (!settling && resultMode) {
            c.cont.setText(userStopped ? "继续演奏" : "重新演奏");
            boolean hasNext = hasNextSheet();
            Ui.setDisabled(c.next, !hasNext);
        }
        Ui.setDisabled(c.ok, settling);
        setSettlingLock(settling);
        // 结算期不许点框外关窗（native 还在算分，卡片被收掉会让人以为演奏没了）。
        // 出分后放开：「关闭」按钮已移除，点框外 + BACK 键就是收卡片的两个出口。
        // ⚠ setCancelable 由上面的 setSettlingLock 同步放开，两者必须同真同假。
        c.dlg.setCanceledOnTouchOutside(!settling);
        if (!c.dlg.isShowing()) c.dlg.show();
    }

    /** 结算期间把「返回」也一起锁住：系统返回键 + 左上返回箭头 + 卡片取消。 */
    private void setSettlingLock(boolean locked) {
        if (mBackBtn != null) Ui.setDisabled(mBackBtn, locked);
        if (mResultCard != null && mResultCard.dlg != null) {
            mResultCard.dlg.setCancelable(!locked);
        }
    }

    /**
     * **唯一的退出入口**：顶栏箭头与系统返回键都走这里。
     *
     * 三道闸按顺序：结算中直接拒 → 演奏中要二次确认 → 才真的 finish。
     * 以前箭头和系统返回各写一份（`finish()` vs `super.onBackPressed()`），
     * 两边的拦截条件得手动保持同步，改一处漏一处就会出现"箭头顶得住、
     * 返回键顶不住"。
     */
    private void leavePage() {
        if (mTrialSettling) {
            // 结算卡在 setCancelable(false) 时会把 BACK 自己吃掉，这里是兜底：
            // 焦点不在卡片上（比如刚弹完、卡还没抢到焦点）也不能让页面先没了。
            Log.i(TAG, "结算中，返回已忽略");
            return;
        }
        if (!trialLeaveGuard()) return;
        finish();
    }

    @Override
    public void onBackPressed() {
        // 默认实现就是 finish()，这里统一走 leavePage，行为与左上箭头完全一致
        leavePage();
    }

    /**
     * 演奏中返回的保护：第一次只提示，3 秒内再按一次才真的退出
     * （BACK_CONFIRM_MS=3000，与提示文案「3 秒内再按一次退出」一致）。
     *
     * 直接**禁死**会把人困住 —— 想中止得先点「演奏中」、等几秒结算完才走得了；
     * 完全不拦又容易误触，一整遍弹奏连同成绩直接没了。所以取中间：
     * 给一次明确的"再按一次"确认，系统返回键和左上箭头共用同一套。
     */
    private boolean trialLeaveGuard() {
        if (!mTrialRunning || mTrialSettling) return true;
        long now = SystemClock.elapsedRealtime();
        if (mBackConfirmAt != 0 && now - mBackConfirmAt < BACK_CONFIRM_MS) {
            mBackConfirmAt = 0;
            Log.i(TAG, "演奏中确认返回，中止并离开");
            return true;
        }
        mBackConfirmAt = now;
        // 文案带上时限：原来只说"再按一次退出"，用户以为随时都行，实际窗口只有 2 秒
        toast("演奏中，3 秒内再按一次退出");
        Log.i(TAG, "演奏中第一次返回：只提示，不退出");
        return false;
    }

    /**
     * 给整个圆钮（图标 / 圆下文字 / 外层容器）都绑上同一个点击。
     *
     * 只绑外层容器时，热区只有「图标宽 28dp」那一条竖带 —— 点在文字稍偏的
     * 位置（比如两个字的空隙外侧）就落空了，表现是「点了没反应」。
     * 三个都绑上之后整个按钮块都是热区，点哪儿都算。
     */
    private void bindClick(Ui.CircleButton b, Runnable action) {
        View.OnClickListener l = v -> action.run();
        b.root.setOnClickListener(l);
        b.icon.setOnClickListener(l);
        b.label.setOnClickListener(l);
        // 上下留内边距把热区撑高（背景透明，看不出变化）。
        // 横向内边距这里**不能加**：标签宽度会被 equalLabelWidth 钉死，
        // 再塞左右内边距等于把文字挤压、"已收藏"就会被折成两行
        // （曾用 dp(9) 左右内边距 + 72dp 固定宽度，实测正好挤爆）。
        // 横向热区本来就有标签那么宽，不缺这 9dp。
        b.root.setPadding(0, dp(3), 0, dp(3));
    }

    /**
     * 让一组圆钮的文字标签等宽 —— 图标绝对位置不随文字长短变化。
     *
     * 做法：用第一个按钮标签的画笔，量一遍所有可能出现的最长字样，取最大值
     * 再留 6dp 余量，然后把每个标签 setWidth 成这个宽度。
     * 标签是 WRAP_CONTENT 的按钮里唯一的「宽度决定者」（图标只有 28dp），
     * 标签钉死 → 按钮等宽 → 后面按钮的文字再变也不会推前面的图标。
     */
    private void equalLabelWidth(Ui.CircleButton... btns) {
        if (btns.length == 0) return;
        android.text.TextPaint p = btns[0].label.getPaint();
        // 三字态有「已收藏」「演奏中」「试听中」，其余都是两字：
        // 量全了才不依赖字体细节（换字体/换字号也不至于被截断）
        String[] all = {"已收藏", "演奏中", "试听中", "选段", "选段中", "取消选段",
                "合手", "右手", "左手", "演奏", "试听", "收藏"};
        int w = 0;
        for (String s : all) w = Math.max(w, (int) Math.ceil(p.measureText(s)));
        w += dp(6);
        for (Ui.CircleButton b : btns) b.label.setWidth(w);
    }

    /**
     * 进度条滑块：金色圆点 + **当前小节号**（1-based）。
     *
     * 当前小节 x 画在小球上、右边只剩 "/ N"，不把「第 x / N 小节」整串放右边占宽度。
     * 数字用**固定 dp 字号**（不跟 Ui.sp 的 fontScale 缩），否则真机上
     * 0.5x 缩放后三位小节号根本看不清。
     * 不持有 Activity / View，只持有 Paint —— 长生命周期的 Drawable 安全。
     */
    private static class MeasureThumb extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint mFill =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint mStroke =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint mText =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final int mSize;
        private final float mStrokeW;
        private int mNumber = 1;

        MeasureThumb(int sizePx, float strokePx, float textPx) {
            mSize = sizePx;
            mStrokeW = strokePx;
            mFill.setStyle(android.graphics.Paint.Style.FILL);
            mFill.setColor(Ui.GOLD_LIGHT);
            mStroke.setStyle(android.graphics.Paint.Style.STROKE);
            mStroke.setStrokeWidth(strokePx);
            mStroke.setColor(Ui.BROWN);
            mText.setColor(Ui.BROWN_DARK);
            mText.setTextSize(textPx);
            mText.setTextAlign(android.graphics.Paint.Align.CENTER);
            mText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }

        /** 小节号变化时只重绘自己；返回是否真的变了（供调用方决定是否刷 SeekBar）。 */
        boolean setNumber(int n) {
            if (n == mNumber) return false;
            mNumber = n;
            invalidateSelf();
            return true;
        }

        @Override public int getIntrinsicWidth() { return mSize; }
        @Override public int getIntrinsicHeight() { return mSize; }

        @Override
        public void draw(android.graphics.Canvas cv) {
            android.graphics.Rect b = getBounds();
            float cx = b.centerX(), cy = b.centerY();
            float r = mSize / 2f - mStrokeW / 2f;
            cv.drawCircle(cx, cy, r, mFill);
            cv.drawCircle(cx, cy, r, mStroke);
            // baseline 居中：cy - (ascent + descent)/2
            cv.drawText(String.valueOf(mNumber), cx,
                    cy - (mText.ascent() + mText.descent()) / 2f, mText);
        }

        @Override
        public void setAlpha(int alpha) {
            mFill.setAlpha(alpha);
            mStroke.setAlpha(alpha);
            mText.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
            mFill.setColorFilter(cf);
            invalidateSelf();
        }

        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** 把 SeekBar 的轨道/滑块刷成原版的棕色系。 */
    private void tintSeek(SeekBar sb) {
        try {
            android.graphics.PorterDuff.Mode mode = android.graphics.PorterDuff.Mode.SRC_IN;
            sb.getProgressDrawable().setColorFilter(Ui.BROWN, mode);
            mMeasureThumb = new MeasureThumb(dp(22), dp(2), dp(9));
            sb.setThumb(mMeasureThumb);
            sb.setThumbOffset(dp(11));   // 与左右内边距一致，两端圆点完整
            sb.setBackgroundColor(Color.TRANSPARENT);
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 手型：合手 / 右手 / 左手 ----------------

    /** 点手型按钮：按 合手 → 右手 → 左手 → 合手 循环切到下一种。 */
    private void cycleHandType() {
        int cur = 0;
        for (int i = 0; i < HAND_CYCLE.length; i++) {
            if (HAND_CYCLE[i] == mHandType) {
                cur = i;
                break;
            }
        }
        applyHandType(HAND_CYCLE[(cur + 1) % HAND_CYCLE.length]);
    }

    /**
     * 应用手型（0=合手 1=右手 2=左手，默认合手）——**全程不打断播放**。
     *
     *  - 曲谱渲染：重发 initPage + drawSheet，非当前手的音符涂灰（与原版
     *    同一份渲染器逻辑）。不再整页重载，避免播放中谱面白屏一秒。
     *  - 音频引擎：只下发双手音量（另一只手静音），**不停播、不重建模块**，
     *    所以音乐连续不断，另一只手从下一块 PCM（≤0.56 秒后）起消失。
     */
    private void applyHandType(int ht) {
        if (ht == mHandType) return;
        mHandType = ht;

        String png = ht == 1 ? "ic_hand_right.png"
                : ht == 2 ? "ic_hand_left.png" : "ic_hand_both.png";
        String label = ht == 1 ? "右手" : ht == 2 ? "左手" : "合手";
        mHandBtn.setState(png, label);

        mSheet.setHandType(ht == 1 ? "right" : ht == 2 ? "left" : "both");
        AlgoEngine.get().setHandType(ht);
        // 不弹 toast、也不写状态行：手型按钮自己的文字（合手/右手/左手）和
        // 谱面灰度已经说清楚了，再补一句"已切右手"是重复播报
    }

    // ---------------- 自由选段 / 取消选段 ----------------

    /**
     * 「自由选段」按钮。语义照原版试炼页：
     *   点一下进入选段 → 点谱面第一个音（起点）→ 点第二个音（终点）→ 生效；
     *   生效后再点这个按钮 = 取消选段。
     * 选段对**试听和演奏都生效**，演奏的评分也只统计段内。
     */
    private void toggleSection() {
        autoDismissRestore();
        if (mSecPicking || mSecOn) {
            cancelSection();
            return;
        }
        mSecPicking = true;
        mSecStartM = -1;
        mSecEndM = -1;
        // 「选段中」和「取消选段」用同一个图标：两个状态下点这一下都是"撤掉当前选段"，
        // 只有空闲态（点了是进入选段）才用普通的括号图标。
        mSectionBtn.setState("ic_sheet_section_off.png", "选段中");
        // 选段期间不需要那个黄色预览光标（它表示"播放到哪儿"，此刻没意义），
        // 而且它正好会盖在括号上。演奏/试听也一并置灰 —— 范围还没定，不该开跑。
        mSheet.hidePreviewCursor();
        mSheet.sectionClear();
        setSectionPickingUi(true);
        // 说明：渲染器自带的编辑模式（startEditMagicCustomSectionMode）虽然返回 success，
        // 但在我们这套桥/页面里它的点击分支没被走到（实测 inEditMode=true 时点击仍然
        // 走 onNoteClick2）。所以仍然用我们自己的两次点击，只是把终点线画在符头右缘。
        toast("选任意音符作为起点");
    }

    /**
     * 把当前选段同步给 native 预览模块（`setSection`）。
     * 原版「试炼」的试听能精确停在段尾，靠的就是它在**合成层**裁掉段外音，
     * 不是 Java 每 60ms 去喊停。Java 侧**没有任何终点截断逻辑**，精度完全由 native 决定。
     */
    private void syncPlaySection() {
        if (mSecOn && mSecStartM >= 0 && mSecEndM >= 0) {
            AlgoEngine.get().setPlaySection(new int[]{mSecStartM, mSecStartV, mSecStartO,
                    mSecEndM, mSecEndV, mSecEndO});
            Log.i(TAG, "下发试听区间 " + mSecStartM + "/" + mSecStartV + "/" + mSecStartO
                    + " -> " + mSecEndM + "/" + mSecEndV + "/" + mSecEndO);
        } else {
            AlgoEngine.get().setPlaySection(null);
        }
    }

    /**
     * 把光标、播放头、进度条滑块和球上数字一次性摆到同一位置。
     *
     * 选段生效 / 恢复存档选段这类路径是**直接改播放头**、不经过 ticker，
     * 以前只调 setMeasureStatus 更新了文字/数字，mSeek 还停在旧位置。
     */
    private void placeCursorAt(int measure, int voice, int offset, boolean followScroll) {
        // 用户主动定位（载入存档 / 点谱面 / 拖进度条 / 播放跟随）：解除曲首光标的
        // 可见性锁，并按用户的定位来 —— 别让"稳定后重画曲首"把他拽回去。
        // （程序自己放曲首时 mPlacingStart=true，不走这条）
        if (!mPlacingStart && mCursorLocked) {
            mCursorLocked = false;
            mPlaceAtStartPending = false;
            mUi.removeCallbacks(mForcePlaceRun);
            if (mSheet != null) {
                mSheet.endSettleWatch();
                mSheet.unlockPreviewCursor();
            }
        }
        AlgoEngine.get().movePlayheadTo(measure, voice, offset);
        mCurMeasure = measure;
        // updateProgress 负责 mSeek.setProgress + 状态行 + 谱面光标；
        // 后面再补一次 setMeasureStatus，压住 mScrubbing 时跳过的那次。
        updateProgress(measure, offset, voice, followScroll);
        setMeasureStatus(measure);
    }

    /** 选段模式下点谱面：第一次设起点，第二次设终点（点更早的音会改起点，和原版一致）。 */
    private void handleSectionTap(int m, int o, int v) {
        if (mSecStartM < 0) {
            mSecStartM = m; mSecStartO = o; mSecStartV = v;
            // 点睛：点完立刻把左括号画出来（不等两个点），用户一眼看到起点落哪儿了
            mSheet.sectionBegin(m, v, o);
            toast("再选一个音符作为终点");
            return;
        }
        // 「更早」= 小节更小；同小节内**按拍位 offset 比**，offset 相同再比声部。
        // 原来的顺序是先比 voice：起点在右手第 3 拍、点左手第 1 拍时，v=4 > 0 被判成
        // "更晚"，实际它在小节开头 —— 这就是用户反馈的"终点能选到起点前面去"。
        // offset 才是时间轴（拍内第几个音），voice 只决定同一拍内上下的先后。
        boolean before = m < mSecStartM
                || (m == mSecStartM && (o < mSecStartO
                        || (o == mSecStartO && v < mSecStartV)));
        if (before) {
            mSecStartM = m; mSecStartO = o; mSecStartV = v;
            mSheet.sectionBegin(m, v, o);
            toast("起点已更新，再选一个音符作为终点");
            return;
        }
        mSecEndM = m; mSecEndO = o; mSecEndV = v;
        mSecOn = true;
        mSecPicking = false;
        // 段内音符集合由渲染器在 confirmSelection 里算好后回调（onSectionKeys）。
        // 先清空，免得沿用上一段选段的旧集合。
        mSecKeys = null;
        // 第二个点：补上右括号（左括号上一步已经画好了）
        mSheet.sectionEnd(m, v, o);
        mSectionBtn.setState("ic_sheet_section_off.png", "取消选段");
        setSectionPickingUi(false);
        syncPlaySection();   // native 合成层裁到段尾
        // 选完先把光标（播放头）收到左界 —— 选段的"默认起点"就是段首；
        // 之后光标可以在段内自由移动，下次试听/演奏从光标所在处开始。
        placeCursorAt(mSecStartM, mSecStartV, mSecStartO, true);
        // 恢复「选段完成」提示：上一次是「再选一个音符
        // 作为终点」，如果手快，那句还没消失就变成了选段生效，容易被误读成"还要再点"。
        // 同一位置换一句话说清楚"这一步结束了"，比只靠括号+按钮变化可靠。
        toast("选段完成");
        Log.i(TAG, "选段生效 " + mSecStartM + "/" + mSecStartV + "/" + mSecStartO
                + " -> " + mSecEndM + "/" + mSecEndV + "/" + mSecEndO);
    }

    private void cancelSection() {
        mSecPicking = false;
        mSecOn = false;
        mSecKeys = null;
        mSecStartM = -1;
        mSecEndM = -1;
        syncPlaySection();   // 清除 native 区间，恢复整曲
        // 取消：只把括号藏起来（不重绘 —— 重绘会让谱面重排、视口跳回曲首）
        mSheet.sectionClear();
        mSectionBtn.setState("ic_sheet_section.png", "选段");
        setSectionPickingUi(false);
        // 进选段时把预览光标藏了（它会压在括号上），取消后必须还回来：
        // 不还的话屏幕上一条光标都没有，看不出"现在播到哪儿/从哪儿开始"。
        // forceScroll=false：只画回原位，别把视口拽走。
        mSheet.moveCursor(AlgoEngine.get().headMeasure(),
                AlgoEngine.get().headOffset(), AlgoEngine.get().headVoice(), false);
        // 取消不弹 toast：括号消失 + 按钮复原「选段」+ 光标还回来
        // 视觉反馈已足够，文字提示是噪音。
        Log.i(TAG, "选段已取消");
    }

    /**
     * 选段期间把「演奏」「试听」置灰：范围还没定下来，这时候开跑没有意义。
     * 选好或取消之后恢复（手型按钮不锁——选段时切手型不影响什么）。
     */
    private void setSectionPickingUi(boolean on) {
        if (mTrialBtn != null) mTrialBtn.setUiEnabled(!on);
        if (mPlayBtn != null) mPlayBtn.setUiEnabled(!on);
    }

    /**
     * 是否已经播出段尾。
     *
     * 跨小节直接比小节号（引擎的播放范围本来就是小节级，这条最可靠）。
     * 段尾小节内则查「段内音符集合」：已经播进过段内、现在报的位置却不在集合里，
     * 说明已经出了段内 → 收尾。
     *
     * 不能拿 offset 和段尾比：引擎上报的是"每个音频块开头那颗音"，块与块之间还会在
     * 左右手之间来回跳（实测 o=5/v=0 下一块变 o=2/v=4），旧写法就是这么误判的。
     * 集合判断不受跳变影响 —— 不管哪只手，在不在段内是确定的。
     */

    /**
     * 把评测结果整理成渲染器认的「逐音标记」JSON。
     *
     * 原生 `ufpFinish` 返回两份：`evaJson`（rating + **detail 逐音**）和 `vexflowJson`
     * （`pitches_info` + `ranges_info`）。实测 `pitches_info` **恒为空数组**（自喂一段
     * 全对也只返回 []），逐音数据只在 `evaJson.detail` 里，每项形如：
     * <pre>
     *   { "position": {"measure":0,"voice":0,"offset":1},
     *     "pitch": [{"bias_type":"ok","key":"B2"}],
     *     "status": "ALIGNED" | "MISS" | ... }
     * </pre>
     * 所以这里用 detail 自己拼 `{"pitches_info":[...]}`，交给 {@link SheetView#markTrialResult}，
     * 渲染器那套 followPitch2 逻辑一行都不用改（它内部用 convertAlgKeyToVexKey 转 "B2" 这类算法键）。
     *
     * 选段时按 `mSecKeys`（渲染器算出的段内音符集合）过滤：native 的 detail
     * 偶尔会带段外音（起点前 / 段尾后），只给段内音符画红叉。引擎现在支持音符级
     * 终点、不再多放一小节，但边界附近的抖动仍靠这层过滤兜住。
     *
     * @return 给 markTrialResult 的 JSON；detail 不可用时原样返回原生 vexflowJson
     */
    private String buildResultJson(String evaJson, String vexflowJson) {
        if (evaJson == null || !evaJson.contains("\"detail\"")) return vexflowJson;
        try {
            org.json.JSONObject src = new org.json.JSONObject(evaJson);
            org.json.JSONArray detail = src.optJSONArray("detail");
            Log.i(TAG, "评测明细：evaJson " + evaJson.length() + " 字符，detail "
                    + (detail == null ? "null" : detail.length()) + " 项，vexflowJson "
                    + (vexflowJson == null ? 0 : vexflowJson.length()) + " 字符");
            if (detail == null || detail.length() == 0) return vexflowJson;

            final java.util.Set<String> keys = mSecOn ? mSecKeys : null;

            // 预扫：记下所有 "弹了谱上没有的音"（extra）发生在哪一拍。
            // UFP 判定错音的方式是「期望音 miss + 实际音 extra」，两段记录 offset 相同
            // （voice 可能不同：左右手各报一份），据此把对应的 miss 升级成 wrong
            // 便于日志统计弹错的数量。谱面上现在不区分错音/漏弹：
            // 渲染器补丁会把所有非 ok 统一画成红叉。
            java.util.Set<String> extraAt = new java.util.HashSet<>();
            for (int i = 0; i < detail.length(); i++) {
                org.json.JSONObject it = detail.optJSONObject(i);
                if (it == null) continue;
                org.json.JSONObject pos = it.optJSONObject("position");
                org.json.JSONArray ps = it.optJSONArray("pitch");
                if (pos == null || ps == null) continue;
                for (int j = 0; j < ps.length(); j++) {
                    org.json.JSONObject p = ps.optJSONObject(j);
                    if (p != null && "extra".equals(p.optString("bias_type", ""))) {
                        extraAt.add(tickKey(pos));
                        break;
                    }
                }
            }

            org.json.JSONArray out = new org.json.JSONArray();
            int kept = 0, outside = 0, rest = 0, upgraded = 0;
            java.util.Map<String, Integer> biasCount = new java.util.TreeMap<>();
            for (int i = 0; i < detail.length(); i++) {
                org.json.JSONObject it = detail.optJSONObject(i);
                if (it == null) continue;
                org.json.JSONObject pos = it.optJSONObject("position");
                org.json.JSONArray ps = it.optJSONArray("pitch");
                if (pos == null || ps == null || ps.length() == 0) continue;
                // 演奏起点之前的音不属于这一遍（native 偶尔会带回起点前的音）。
                // 留着它们会把"根本没弹到"的小节也算进本轮覆盖范围，
                // 从而把那些地方的旧红叉误当成"弹对了"清掉。
                final int pm = pos.optInt("measure", -1);
                if (pm >= 0 && pm < mTrialStartMeasure) { outside++; continue; }
                // 选段：只保留段内音符（左右手都算，mSecKeys 是渲染器按真实时长算的集合）
                if (keys != null && !keys.isEmpty()
                        && !keys.contains(sectionKey(pos.optInt("measure", -1),
                        pos.optInt("offset", 0), pos.optInt("voice", 0)))) {
                    outside++;
                    continue;
                }
                final boolean wrongHere = extraAt.contains(tickKey(pos));
                org.json.JSONArray pitch = new org.json.JSONArray();
                for (int j = 0; j < ps.length(); j++) {
                    org.json.JSONObject p = ps.optJSONObject(j);
                    if (p == null) continue;
                    String key = p.optString("key", "");
                    if (key.isEmpty() || "rest".equals(key)) continue;   // 休止符没有符头
                    String bt = p.optString("bias_type", "ok");
                    if ("extra".equals(bt)) continue;   // 谱上没有这颗音，没符头可染色
                    if ("miss".equals(bt) && wrongHere) {
                        bt = "wrong";                    // 这一拍弹了，但弹的不是它 → 错音
                        upgraded++;
                    }
                    Integer cn = biasCount.get(bt);
                    biasCount.put(bt, cn == null ? 1 : cn + 1);
                    pitch.put(new org.json.JSONObject()
                            .put("key", key)
                            .put("bias_type", bt));
                }
                if (pitch.length() == 0) {                    // 整个音团都是休止符 / 只有 extra
                    rest++;
                    continue;
                }
                out.put(new org.json.JSONObject()
                        .put("pitch_cluster_position", pos)
                        .put("pitch", pitch));
                kept++;
            }
            if (kept == 0) return vexflowJson;
            Log.i(TAG, "bias 分布: " + biasCount + "（miss→wrong " + upgraded + "）");
            Log.i(TAG, "结果标记：来自 eva.detail，保留 " + kept + " 个音团"
                    + "，段外丢弃 " + outside + "，休止跳过 " + rest);
            return new org.json.JSONObject().put("pitches_info", out).toString();
        } catch (Throwable t) {
            Log.w(TAG, "构造结果标记失败，退回原生 vexflow：" + t);
            return vexflowJson;
        }
    }

    /** 同一拍的标识：只用 measure + offset —— extra 与 miss 的 voice 可能不同（左右手各报一次）。 */
    private static String tickKey(org.json.JSONObject pos) {
        return pos.optInt("measure", -1) + "|" + pos.optInt("offset", 0);
    }

    /**
     * 试听自然结束的统一收尾：整曲曲末与 setSection 段尾都走 native `finished`，
     * 汇到同一个 `onEnded` → 这里。按钮、播放头、进度条、状态行、光标、视口
     * 一起回到曲首（有选段回段首）。
     */
    private void finishPreviewAtStart() {
        mPlayBtn.setState("ic_sheet_preview_play.png", "试听");
        setPlaybackUi(false);
        final boolean sec = mSecOn && mSecStartM >= 0;
        rewindToStart(sec ? mSecStartM : 0, sec ? mSecStartV : 0, sec ? mSecStartO : 0, false);
        Log.i(TAG, "试听结束，光标回到" + (sec ? "段首 " : "曲首 ") + mCurMeasure);
    }

    /**
     * 光标 / 播放头 / 进度条 / 状态行 / 视口一起摆回起点。
     *
     * 试听自然结束（{@link #finishPreviewAtStart()}）与演奏自然结束必须**同一套口径**
     * —— 两处原来各抄了一份 20 行的同样代码，改一处漏一处就会"试听回曲首、演奏停在末小节"。
     *
     * @param forceViewport 是否再补一次 moveToStart（演奏路径需要：setMagicMode(false)
     *                      之后的补画光标是异步排队的，updateProgress 那次滚动可能被它盖掉）
     */
    private void rewindToStart(int m, int v, int o, boolean forceViewport) {
        AlgoEngine.get().movePlayheadTo(m, v, o);
        mPlayPosVisible = true;
        mPreviewScrollPending = true;   // 这一次允许滚回开头
        mFollowGraceUntilMs = SystemClock.elapsedRealtime() + FOLLOW_GRACE_MS;
        mCurMeasure = m;
        updateProgress(m, o, v, true);
        if (forceViewport) mSheet.moveToStart(m, o, v);
        setMeasureStatus(m);
    }


    // ---------------- 演奏（跟弹）：现场收音驱动光标 ----------------

    /**
     * 本次演奏算不算"没演奏"：
     * ① 一个音都没收到 —— 沿用按停时的输入峰值口径 {@code peak < 300}；
     * ② 收到了声音，但光标从头到尾没离开过起点（一直落在第一个音上，比如一直在
     *    弹错音 / 环境噪声让峰值不为 0 却没推进任何进度）。
     * 命中任一条就**直接退出演奏状态、不弹任何结果窗口**（原来的「未演奏」卡片
     * 分数是 0、没有判定数据，报出来只会误导，用户也不要这个提示）。
     * ⚠ 别用光标帧数判：弹错音时引擎不推进光标但仍报帧，那正是②要抓的情况。
     */
    private boolean trialHadNoPlay() {
        return UfpTrial.peakLevel() < 300 || !mTrialProgressed;
    }

    /**
     * 「演奏」按钮：与「试听」互斥的二态开关。
     * 开始 = 停掉并等死试听 Player（它独占 native 预览模块）→ 建 UFP 会话
     * → 麦克风 44100Hz 喂入；期间引擎对照曲谱实时匹配，光标按真实弹奏推进
     * （非计时器）。演奏运行中「试听」置灰 —— 现场演奏不该被误触截断，
     * 要切回试听先按停止结束演奏。
     */
    private void toggleTrial() {
        if (mTrialRunning) {
            if (!mUfpRunning) {
                // 会话还在建（T618 冷启 2~4 秒）：这一下是**取消**，不是按停。
                // 以前按钮在这段时间是禁用的，点按被直接吞掉 —— 用户看到的是
                // "点了没反应，几秒后反而开始演奏"，再按停又要等 ufpFinish。
                cancelBuildingTrial();
                return;
            }
            if (mUfpRunning) {
                mUfpRunning = false;
                // "没演奏"判定要在弹结算卡**之前**做：没收到声音
                // 或光标一直落在第一个音上，就直接退出演奏状态、全程不弹任何窗口。
                final boolean noPlay = trialHadNoPlay();
                mTrialNoPlay = noPlay;
                final long played = SystemClock.elapsedRealtime() - mTrialStartedAtMs;
                final int peak = UfpTrial.peakLevel();
                Log.i(TAG, "按停：已跑 " + played + "ms，输入峰值 " + peak + "，推进过=" + mTrialProgressed
                        + "，没演奏=" + noPlay);
                if (!noPlay) {
                    // 收尾要算评分，得好几秒（native ufpFinish）。这段时间必须让用户
                    // 看得出"已经在结算"：立刻置灰按钮 + 弹结算卡。原来只置了
                    // mUfpRunning，按钮照样能点、界面毫无反馈 —— 用户以为没反应，
                    // 反复点，状态更乱。
                    markSettling();
                }
                // ⚠ 必须丢到后台线程：stop() 里要 join 喂音线程（最多 800ms）
                // 再调 ufpFinish，在主线程跑会把界面冻住 —— 用户看到的就是
                // "点了没反应"，而冻结期间点的按钮会排队、松手后连续触发，
                // 状态越点越乱。
                // 传本页 listener：按停期间若用户已返回重进，旧页面的 stop
                // 不能顺着静态 sListener 把新会话停掉（UfpTrial 会校验归属）。
                // 没演奏时跳过 ufpFinish：结果本来就是空的，T618 上这一步要 3.5 秒；
                // 有演奏但弹错音（光标没推进）旧逻辑仍要评分出红叉，现在一并静默退出。
                final boolean skipScoring = noPlay;
                new Thread(() -> UfpTrial.stop(mTrialListener, true, skipScoring),
                        "trial-stop").start();
            }
            return;
        }
        // 先清掉上个页面留下的会话（用户在演奏中按了返回、旧实例没来得及收尾）。
        // 不清就只能把"已经在演奏中"甩给用户，而他什么也做不了。
        if (UfpTrial.isRunning()) {
            Log.w(TAG, "发现上个页面遗留的演奏会话，先收掉再重新开始");
            UfpTrial.abandon();
        }
        // 麦克风权限（targetSdk 34 必须运行时申请）。
        // 自喂演示时音频是本机合成的 PCM，不开麦克风，也就不必申请权限
        final boolean selfFeed = getIntent().getBooleanExtra("selffeed", false)
                || getIntent().getBooleanExtra("feedsilence", false);
        if (!selfFeed
                && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        startTrial();
    }

    /** 权限回调：用户点头后立即进入演奏，不必再点一次 */
    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(code, perms, grants);
        if (code == REQ_MIC && grants.length > 0
                && grants[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startTrial();
        } else if (code == REQ_MIC) {
            Ui.toast(this, "没有麦克风权限无法演奏");
        }
    }

    private void startTrial() {
        autoDismissRestore();
        final boolean hadPreview = mPreviewActive || AlgoEngine.get().isPlaying();
        // 即使 Player 尚未创建，也要让后面的 stopAndWait 作废旧 seekEpoch。
        if (mPreviewActive) setPlaybackUi(false);
        mTrialProgressed = false;   // 新一轮：还没离开过起点
        mTrialNoPlay = false;       // 上一轮的静默退出标志不带到这一轮
        // 进谱时为了让曲首光标不被重绘擦掉，给 body 加过 `wb-cursor-lock`
        // （CSS visibility:visible !important）。演奏期间光标仍是同一枚胶囊，
        // 这把锁不会造成"两个光标"，但留着它会在整页重绘后把旧位置的光标钉住，
        // 所以照旧先解掉。
        if (mCursorLocked && mSheet != null) {
            mCursorLocked = false;
            mSheet.unlockPreviewCursor();
        }
        // 进演奏态：只置 Java 侧标志（演奏态的小节高亮 + 引擎报文门控），
        // 渲染器不切模式、不重绘（见 SheetView.mPerformMode）。
        // ⚠ 光标不再跟着切样式了：现在只有金胶囊一套（见 SheetView.applyCursorStyle）。
        mSheet.setMagicMode(true);
        // 不写状态行：准备期间状态行停在上一次的小节数上即可，
        // 按钮已经变成「演奏中」说明点到了
        mTrialBtn.setState("ic_sheet_preview_stop.png", "演奏中");
        mTrialRunning = true;
        // 起点 = **屏幕上那颗光标**（用户说的"从光标所在地方开始"就是它）。
        // 先把播放头同步过去：正常点谱面/拖进度条时两者本就一致，但上一轮演奏
        // 若被引擎空闲误报写脏过，播放头会滞留曲首 —— 这里兜住它，让
        // "显示的光标 / 播放头 / 引擎区间起点"三者是同一个位置。
        int lm = mSheet.lastMeasure();
        if (lm >= 0) {
            AlgoEngine.get().movePlayheadTo(lm, Math.max(0, mSheet.lastVoice()),
                    Math.max(0, mSheet.lastOffset()));
        }
        // 有选段：sectionHead() 会把这个位置 clamp 进段内（段内可自由移动起点）；
        // 用光标位置而不是硬取段首 —— 选段只约束"能播的范围"，练长段时想从
        // 上次练到的那个音接着来，每次都从头弹没意义。
        final int[] start = (mSecOn && mSecStartM >= 0) ? sectionHead() : null;
        final int bm = start != null ? start[0] : AlgoEngine.get().headMeasure();
        final int bo = start != null ? start[1] : AlgoEngine.get().headOffset();
        final int bv = start != null ? start[2] : AlgoEngine.get().headVoice();
        mTrialStartMeasure = bm;
        mTrialStartOffset = bo;
        mTrialStartVoice = bv;
        // 守卫窗口必须从"引擎真正开始喂音"算起，不能从这里：建 UFP 会话模拟器
        // 要 3.6s、真机 ~370ms，从现在起算的 1200ms 窗口在开跑前就过期了。
        // 真正在用的时间戳在 UfpTrial.start() 成功后重置（见下面 if (ok)）。
        mTrialStartedAtMs = SystemClock.elapsedRealtime();
        // 建会话要先装 84MB 模型（几百毫秒到几秒）。这段时间按钮置灰，
        // 成功后恢复可点（那一下是"停止"）—— 连点第二下点不到。
        // 按钮**不置灰**：建会话要 2~4 秒，这段时间再点一下就是"取消"
        // （见 cancelBuildingTrial）。置灰会让这一下被吞掉。
        mTrialCanceled = false;
        setTrialUi(true);
        // 切完演奏态立刻把胶囊摆到起点，不等 1~3 秒建会话：否则用户点完演奏
        // 先看到"光标消失"，再突然出现在别处。
        //
        // ⚠ 这一帧必须 forceScroll=true：试听起播靠
        // mPreviewScrollPending + updateProgress 发一帧 forceScroll=true 的
        // moveCursor，把光标所在小节顶到视口顶部 —— 用户把光标停在"只露半截"
        // 的位置时，试听会把整行补齐。演奏侧原本全是 forceScroll=false，又刚
        // 碰过谱面落进 H5 的 2 秒触摸抑制窗口，于是这一行永远补不齐。
        // 这里对齐试听：起播强制跟一次，之后演奏中仍按老规矩不抢视口。
        mUi.postDelayed(() -> {
            if (uiGone() || !mTrialRunning) return;
            mSheet.moveCursor2(bm, bo, bv, mTrialNoteCursor, true, true);
        }, 150);
        new Thread(() -> {
            // 1. 试听若在播，置停止请求并等 Player 线程彻底退出（native
            //    预览模块全局单例，必须与 UFP 严格先后，不能并发）
            if (hadPreview || AlgoEngine.get().isPlaying()) {
                if (!AlgoEngine.get().stopAndWait()) {
                    mTrialListener.onError("演奏没能开始", "试听 Player 未在 3 秒内退出");
                    return;
                }
                runOnUiThread(() -> mPlayBtn.setState(
                        "ic_sheet_preview_play.png", "试听"));
            }
            // 上一次演奏留下的红叉**不清**：用户要它继续留在谱面上，
            // 提醒下一遍注意这些音。渲染器 followPitch2 会按 id 替换同一位置的标记，
            // 不会叠成两层；只在换曲（新 SheetView）时才自然清空。
            // 3. 合成(自喂)+建 UFP 会话在引擎线程跑：试听/演奏与预览模块的关系
            //    与预览模块的关系全靠这条线程串行化
            AlgoEngine.get().post(() -> {
                try {
                // 练习起点 = 屏幕上那颗光标（外层算好的 bm/bo/bv）。用户先点谱面/
                // 拖进度条定位，再点演奏，就只练这一小段（练琴最常见的用法）。
                // 以前在这里重取 headMeasure()，跨越建会话的几秒会和 UI 上摆好的
                // 起点不一致 —— 现在整次演奏统一用同一个起点。

                // 自喂音频的合成在 UfpTrial 内部做（必须在 releasePreviewModule 之前），
                // 这里原来还算了一份 blocks，纯属 magicPiano 时代的残留 —— 等于把整曲
                // 合成两遍，白等好几秒，已删除。

                // 演奏走 UFP —— 原版 App 用的那条通路：真实的音高/节奏判定 +
                // native 算的 rating（magicPiano 已整体移除：它不校验真实音高，
                // 弹错也报 fully matched）。
                boolean self = getIntent().getBooleanExtra("selffeed", false);
                boolean quiet = getIntent().getBooleanExtra("feedsilence", false);
                // 终点必须是**越过最后一小节**的那个"小节号"：实测终点是按位置生效的
                // （{lastMeasure,0,0} 会停在最后一小节开头，最后一小节就弹不到了），
                // 而越界一格会被引擎夹成整曲 —— 正是我们要的"到曲末"。
                int lastM = Math.max(bm + 1, AlgoEngine.get().getMeasureCount());
                if (mSecOn && mSecEndM >= 0) {
                    // ⚠ 旧注释说"带 offset 的终点会被忽略、只认小节边界"是 **magicPiano 时代**
                    //   的结论，UFP 并不成立：实测 end=[{measure:5,voice:0,offset:2}]
                    //   `ufpSetCustomRangeWithResult` 直接返回 0 并精确停在该音符。
                    // 以前一律放宽一小节（mSecEndM+1）→ 越界 → 退回段尾小节 offset 0，
                    // 结果段尾小节被整段判进去，用户看到的正是"段落右边不在小节结尾也会播完那小节"。
                    // 现在直接把选段的**精确终点音符**交给 native，与试听 setSection 对齐。
                    Log.i(TAG, "演奏区间 = 精确终点 " + mSecEndM + "/" + mSecEndV
                            + "/" + mSecEndO + "（起点 " + bm + "/" + bv + "/" + bo + "）");
                }
                // 实验：--ei endm N --ei endo M 指定终点音符（endm<0 = 到曲末）
                int em = getIntent().getIntExtra("endm", -1);
                int eo = getIntent().getIntExtra("endo", 0);
                int[] endPos;
                if (em >= 0) {
                    endPos = new int[]{em, 0, eo};
                } else if (mSecOn && mSecEndM >= 0) {
                    endPos = new int[]{mSecEndM, mSecEndV, mSecEndO};
                } else {
                    endPos = new int[]{lastM, 0, 0};   // 到曲末：越界一格交给 applyRange 探测
                }
                Log.i(TAG, "实验终点 -> " + endPos[0] + "/" + endPos[1] + "/" + endPos[2]);
                boolean ok = UfpTrial.start(mEncPath, mHandType,
                        new int[]{bm, bv, bo}, endPos,
                        self, quiet, mTrialListener);
                if (ok) {
                    if (uiGone()) {
                        // 建会话期间用户已离开：onStop 已经跑过，不会再有人停这次
                        // 晚建好的会话。当场丢弃，避免麦克风/识别线程变成幽灵会话。
                        Log.w(TAG, "页面已离开，丢弃刚建好的演奏会话");
                        UfpTrial.abandon();
                        return;
                    }
                    mUfpRunning = true;
                    // 视口跟随用定时任务：真机不弹就没有光标帧，见 mTrialFollow
                    startTrialFollow(bm, bv, bo);
                    // 引擎马上开始喂音 —— 守卫窗口从**现在**重新计时，才盖得住
                    // 开跑初期的空闲误报（建会话耗时会把旧时间戳耗光）。
                    mTrialStartedAtMs = SystemClock.elapsedRealtime();
                    Log.i(TAG, "演奏走 UFP（原版通路），起点 " + bm + "/" + bv + "/" + bo);
                    // 把界面摆到起点（和引擎的"起点"一致）：否则高亮还停在上一次的
                    // 小节上，跟真正开始弹的位置对不上。
                    final int sm = bm, so = bo, sv = bv;
                    runOnUiThread(() -> {
                        if (uiGone()) return;
                        mCurMeasure = sm;
                        setMeasureStatus(sm);
                        // 这里必须跟着 mTrialNoteCursor 走：固定 false 会让演奏中
                        // **只有整小节高亮、没有单音光标**，用户看不出从哪颗音开始。
                        mSheet.moveCursor2(sm, so, sv, mTrialNoteCursor, true);
                        // 不要再在 900ms 后补摆一次光标：演奏态不切魔法模式、不重绘，
                        // 这次补摆只会让视口中途再跳一下。
                    });
                } else {
                    Log.w(TAG, "UFP 建会话失败");
                    runOnUiThread(() -> {
                        // 页面已经走了（返回 / 重进）：onStop 早就把 UI 复位并把
                        // 这一轮作废了，这里再改 UI 就是对着销毁的 View 操作。
                        if (uiGone()) {
                            Log.w(TAG, "建会话失败时页面已销毁，跳过 UI 复位");
                            return;
                        }
                        // ⚠ 两个状态标志必须一起复位：startTrial 一进来就把
                        // mTrialRunning 置了 true，失败分支不清的话页面会永久卡在
                        // "演奏中"——试听/选段一直灰着、点谱面定位被静默吞掉，
                        // 而其实一个会话都没有。
                        mTrialRunning = false;
                        mUfpRunning = false;
                        mTrialSettling = false;
                        mTrialBtn.setState("ic_sheet_trial.png", "演奏");
                        mTrialBtn.setUiEnabled(true);
                        setTrialUi(false);
                        mSheet.setMagicMode(false);
                        // 用户在建会话期间主动取消：UI 已经复位过了，别再报错
                        if (!mTrialCanceled) {
                            Ui.toast(PlayerActivity.this, "这首曲子暂时开不了演奏");
                        }
                        mTrialCanceled = false;
                    });
                    return;
                }
                // 成功：按钮恢复可点（这一下是"停止"）
                runOnUiThread(() -> mTrialBtn.setUiEnabled(true));
                } catch (Throwable t) {
                    // 任一准备步骤异常都不能把页面留在“演奏中”：统一走 onError，
                    // 它会复位三个状态标志和 native 占用标志，并恢复预览视觉。
                    Log.e(TAG, "演奏初始化异常", t);
                    mTrialListener.onError("演奏没能开始", String.valueOf(t));
                }
            });
        }, "trial-start").start();
    }

    /** 建会话期间点停止 = 取消这一轮（递增会话代数，在途建会话会自我作废）。 */
    private void cancelBuildingTrial() {
        // 300ms 内的一下当作误触的双击（用户连点两下"演奏"很常见），
        // 否则"点了开始又立刻被自己取消"会更莫名其妙。
        if (SystemClock.elapsedRealtime() - mTrialStartedAtMs < 300) {
            Log.i(TAG, "建会话期间 300ms 内的二次点击，按误触忽略");
            return;
        }
        Log.i(TAG, "建会话期间取消演奏");
        mTrialCanceled = true;
        mTrialRunning = false;
        mUfpRunning = false;
        mTrialSettling = false;
        stopTrialFollow();
        UfpTrial.abandon(mTrialListener);   // 代数 +1 → 建会话线程建完会静默丢弃
        if (!uiGone()) {
            mTrialBtn.setState("ic_sheet_trial.png", "演奏");
            mTrialBtn.setUiEnabled(true);
            setTrialUi(false);
            mSheet.setMagicMode(false);
            // 不再 hidePreviewCursor：光标只有胶囊这一套，演奏结束照旧显示它
            //（setMagicMode(false) 里会立刻把胶囊按最后位置画回来）。
        }
        updateTransportState();
    }

    /**
     * 演奏/试听期间保持屏幕常亮。
     *
     * 练琴时手在琴键上、眼睛看谱，一整段可能好几分钟不碰屏幕；超过系统的
     * 自动息屏时间就会黑屏，回来还得解锁（真机上实测）。用窗口 FLAG 而不是
     * WakeLock：不需要额外权限，Activity 销毁/会话结束自动失效，也不会泄漏。
     */
    private boolean mScreenOnTrial, mScreenOnPlay;

    private void applyKeepScreenOn() {
        final int flag = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        if (mScreenOnTrial || mScreenOnPlay) getWindow().addFlags(flag);
        else getWindow().clearFlags(flag);
    }

    /**
     * 进入结算：按钮置灰 + 显示「正在结算」卡片（同一张卡，出分时原地变成绩卡）。
     *
     * 用户按停和引擎自然弹到曲末（state=3）两条路径都必须走这里。
     * 之前只有按停那条做了反馈：自然结束是 feedLoop 在末尾直接调
     * stop(listener,false)，native ufpFinish 要好几秒，这段时间界面仍显示
     * 「演奏中」、光标钉在最后一个音上 —— 用户反馈就是"最后一个音卡了很久"。
     * 按钮文案**不改**：状态由卡片统一表达，按钮只负责不可用（置灰）。
     * 结算期间卡片外区域不可点，卡片内只有「正在结算…」+ 转圈，按钮都还没露面。
     * 幂等：state=3 可能连报几帧。
     */
    private void markSettling() {
        if (mTrialSettling) return;
        mTrialSettling = true;
        mTrialBtn.setUiEnabled(false);
        // 按钮文案不重复表达状态：结算过程由**同一张卡**说明。
        // 这张卡在 onFinished 出分时原地变成成绩卡，不会弹第二次窗。
        showTrialResult("正在结算…", "", "", "", true, true, false);
        Log.i(TAG, "进入结算，按钮置灰 + 结算卡（仅转圈/返回/外部全锁）");
        // 结算期间底部整排也要灰：这时候拖进度条同样没有意义
        updateTransportState();
    }

    /**
     * 演奏看的视口跟随：每隔 400ms 判一次"光标还在视口里吗"。
     *
     * ⚠ 不能挂在光标帧上做：真机演奏走麦克风，**不弹就没有 onCursor**，
     * 靠帧驱动等于没有 —— 用户滚走之后永远回不来。
     * 这里改成定时任务，与是否在弹奏无关。
     */
    private final Runnable mTrialFollow = new Runnable() {
        @Override
        public void run() {
            if (!mTrialRunning || mTrialSettling || uiGone()) return;   // 结束就不再重排
            updateTrialFollow();
            mUi.postDelayed(this, 400);
        }
    };

    /**
     * @param m/v/o 演奏起点。跟随任务一开始就记下它 —— 否则"还没弹第一个音"
     *              这段时间里 mLastCurM 是 -1，用户滚走之后不会追回来，
     *              和试听（只要有播放位置就追）不一致。
     */
    private void startTrialFollow(int m, int v, int o) {
        mLastCurM = m;
        mLastCurO = o;
        mLastCurV = v;
        mInvisibleHits = 0;
        mPlayPosVisible = true;
        mUi.removeCallbacks(mTrialFollow);
        mUi.postDelayed(mTrialFollow, 400);
    }

    private void stopTrialFollow() {
        mUi.removeCallbacks(mTrialFollow);
    }

    private void updateTrialFollow() {
        if (mLastCurM < 0 || mSheet == null) return;
        final long now = SystemClock.elapsedRealtime();
        // 用户还在滚 / 刚停手：先不查也不拉，免得跟手指和滚动抢 WebView。
        // 手感最差的就是"正在滚的时候后台还在发 JS 查询 + 跳视口"。
        if (now - mLastUserActionMs < 1500) return;
        // 演奏时放宽到 500ms：每次查询都是一次 JS 往返（T618 约 75ms），
        // 4 次/秒会跟"每帧下发光标"抢 WebView 主线程，表现就是跟随/滚动发卡。
        if (now - mLastVisibleQueryMs >= TRIAL_QUERY_MS) {
            mLastVisibleQueryMs = now;
            mSheet.queryMeasureVisible(mLastCurM, mLastCurV, mLastCurO, vis -> {
                if (vis) {
                    mInvisibleHits = 0;
                    mPlayPosVisible = true;
                    return;
                }
                if (SystemClock.elapsedRealtime() < mFollowGraceUntilMs) {
                    mInvisibleHits = 0;      // 正在滚过去，不算看不见
                    return;
                }
                if (++mInvisibleHits >= INVISIBLE_HITS_PAUSE) mPlayPosVisible = false;
            });
        }
        if (!mPlayPosVisible && now - mLastUserActionMs >= IDLE_RESUME_MS) {
            Log.i(TAG, "演奏中停手 " + IDLE_RESUME_MS + "ms，视口拉回光标 "
                    + mLastCurM + "/" + mLastCurV + "/" + mLastCurO);
            mPlayPosVisible = true;
            mInvisibleHits = 0;
            mFollowGraceUntilMs = now + FOLLOW_GRACE_MS;
            // 对齐必须是 **top**，不能是 center：恢复 / 试听 /
            // 演奏起播三条路径都是 top（光标所在行顶边贴视口顶），只有这里用 center
            // 会多留半屏；按停后再点试听（top）就会"乐谱往上提一截"（用户报）。
            //
            // 这里用 scrollToMeasure 而不是 moveCursor(..., true)：后者会连整小节
            // 高亮和光柱一起重画，低端机上这几百毫秒的卡顿是用户明确抱怨过的。
            // 两者实测滚动结果完全一致（同一小节的光标 y 都是 365）。
            mSheet.scrollToMeasure(mLastCurM, "top");
        }
    }

    /** 演奏期间把试听/选段/手型入口置灰（试听期间对称地置灰演奏/选段，见 setPlaybackUi） */
    private void setTrialUi(boolean on) {
        mScreenOnTrial = on;
        applyKeepScreenOn();
        mPlayBtn.setUiEnabled(!on);
        // 演奏中不能改选段（不然范围会跟正在跑的会话不一致）
        if (mSectionBtn != null) mSectionBtn.setUiEnabled(!on);
        // 手型在 UFP 建会话时就固定了，演奏中改不了 —— 直接灰掉，
        // 直接置灰，点击处理器里不再留对应的状态判断和 toast。
        // 恢复时要带上 mHandCanSplit：只能合手的曲子本来就是灰的，别把它点亮。
        // 试听期间**不**灰：切手型只下发左右手音量、不重建模块，是设计支持的用法。
        if (mHandBtn != null) mHandBtn.setUiEnabled(!on && mHandCanSplit);
        // 演奏期间底部整排（进度条 + 四个跳转钮）一并置灰：位置由弹奏驱动，
        // 拖进度条只会让滑块和光标对不上
        updateTransportState();
    }

    /**
     * 谱面"可以操作了"之后放开入口按钮 —— **唯一**允许在这里批量置 true 的地方。
     *
     * 为什么不能用裸的 setUiEnabled(true)：下面这三个回调（首屏谱表 /
     * 整页渲染完成 / 10 秒超时兜底）都可能**晚于**用户点「演奏」才到达。
     * 那时试听/选段已经被 setTrialUi(true) 置灰，无脑放开就成了
     * "演奏中还能点试听/选段"（用户实测：点演奏后过一会儿
     * 这两个按钮自己亮了）。所以这里按**当前状态**推导，只放开真正空闲的。
     */
    private void openEntryButtons() {
        // 恢复存档期间：这三个迟到回调不许把按钮重新点亮（会与"稳定后重画光标"
        // 抢状态）。endRestoring() 会统一放开。
        if (mRestoring) {
            lockEntryButtons();
            return;
        }
        // 两个 loading 共用的按钮锁：收藏和返回箭头不在下面的播放态推导里，
        // 撤遮罩时在这里统一恢复（返回箭头始终可点，这里只是把 enabled 兜回来）。
        if (mFav != null) mFav.setUiEnabled(true);
        if (mBackBtn != null) mBackBtn.setEnabled(true);
        // mPreviewActive 覆盖“已点试听、Player 还没创建”的窗口；isPlaying 再兜住
        // 停止请求发出后 Player 尚未退出的短窗口。二者都不能让演奏入口重新点亮。
        final boolean preview = mPreviewActive || AlgoEngine.get().isPlaying();
        // 选段中（刚点进选段、还没点第二个音）同样要压住这两个入口：
        // setSectionPickingUi(true) 已经把按钮灰掉了，但本方法被三个**迟到**回调
        // 调用（首屏谱表 / 整页渲染完成 / 10 秒超时兜底），它们只看播放状态，
        // 一跑就把灰掉的按钮重新点亮 —— 表现为"选段选到一半，试听自己能点了"。
        final boolean sectionLock = mSecPicking;
        if (!mTrialRunning && !mTrialSettling) {
            mTrialBtn.setUiEnabled(!preview && !sectionLock);
        }
        // 试听是开始/停止二态按钮：试听中必须保持可点，只有演奏及其结算才禁用。
        mPlayBtn.setUiEnabled(!mTrialRunning && !mTrialSettling && !sectionLock);
        // ⚠ 选段中**不能**灰「选段」按钮：它此时显示「选段中」，并且是"取消选段"
        // 的唯一入口（点击走 cancelSection）。原来 busy 把 sectionLock 也算进去，
        // 于是选段到一半按钮自己灰掉、退不出来。
        final boolean sectionBusy = mTrialRunning || mTrialSettling || preview;
        if (mSectionBtn != null) mSectionBtn.setUiEnabled(!sectionBusy);
        // 手型：只有演奏及其结算不能切（UFP 会话建好时手型就固定了）
        if (mHandBtn != null) {
            mHandBtn.setUiEnabled(!mTrialRunning && !mTrialSettling && mHandCanSplit);
        }
        // 恢复"上次阅读现场"也挂在这里兜一手：本方法被**首屏谱表 / 整页渲染完成 /
        // 10 秒超时兜底**三处迟到回调调用，而 `onSheetRendered` 在个别 ROM 上会丢
        // （OPPO 的 WebView 重建 / 窗口重配，真机实测：渲然完成日志完全
        // 没打，于是浮层永远不出现、退出时还把存档按"第一小节"清掉）。
        // restoreSavedState() 自身幂等（mCursorRestored / mRestoreBar / mResumeScanned
        // 三重保护），从这里重复调是安全的。
        restoreSavedState();
        // ⚠ 底部进度条 + 四个跳转钮不在上面的顶栏推导里，由本方法统一收口。
        // 之前冷启动靠 bootstrap() 里那次 updateTransportState() 恢复，而"恢复存档"
        // 结束不会再走 bootstrap —— 于是遮罩撤了、顶栏亮了，底部却还是灰的。
        // 两个 loading 现在都在撤遮罩时走到这里。
        updateTransportState();
    }

    /** 试听播放期间把演奏入口置灰（与 setTrialUi 对称：同一时刻只有一个能用） */
    private void setPlaybackUi(boolean on) {
        mPreviewActive = on;
        mScreenOnPlay = on;
        applyKeepScreenOn();
        mTrialBtn.setUiEnabled(!on && !mTrialRunning && !mTrialSettling);
        mPlayBtn.setUiEnabled(!mTrialRunning && !mTrialSettling);
        if (mSectionBtn != null) {
            mSectionBtn.setUiEnabled(!on && !mTrialRunning && !mTrialSettling);
        }
        updateTransportState();
    }

    /**
     * 离开页面就结束演奏。
     *
     * 演奏是全局静态会话 + 真麦克风：页面一走，会话没人管就成了"幽灵会话"
     * （mRunning 卡在 true、录音线程空转），用户再点「演奏」只会看到
     * "演奏已经在运行"。这里显式丢弃，麦克风当场释放。
     */
    @Override
    protected void onStop() {
        Log.i(TAG, "onStop: finishing=" + isFinishing() + " settling=" + mTrialSettling
                + " card=" + (mResultCard != null && mResultCard.dlg != null
                        && mResultCard.dlg.isShowing()));
        super.onStop();
        // 切后台/返回时别把"恢复中"的锁留着：那波渲染回调可能永远不来了，
        // 回来后会一直不可点（只剩 22 秒兜底）。放开即可，光标状态由存档兜住。
        endRestoring();
        endJumpWait();   // 跳转等待同理：页面走了就别再留着遮罩和轮询
        // 先记光标位置，再停播放/演奏：停止回调可能把光标位置改掉
        saveCursorPosition();
        // 试听也要停：AlgoEngine 是全局单例，页面走掉后 Player 线程还在跑，
        // 音乐会继续在曲库页响（原版离开预览页就静音）。
        if (mPreviewActive || AlgoEngine.get().isPlaying()) {
            AlgoEngine.get().stop();
            mPlayBtn.setState("ic_sheet_preview_play.png", "试听");
            setPlaybackUi(false);
            Log.i(TAG, "页面离开，试听已停止（含起播排队）");
        }
        // 顺序很重要：**先**复位界面与标志，**再**丢弃会话。
        // abandon() 要等喂音线程退出、还要往引擎队列排清理任务，反过来的话
        // 这段等待里界面仍停在"演奏中"，返回时能看见它闪一下。
        final boolean wasTrial = mTrialRunning || UfpTrial.isRunning();
        final boolean wasSettling = mTrialSettling;
        mTrialRunning = false;
        mUfpRunning = false;
        mTrialSettling = false;
        // 返回确认的计时也要清：不清的话"按了一次返回提示 → 切后台 → 回来再按"
        // 会被当成"3 秒内第二次"，直接把页面退掉。
        mBackConfirmAt = 0;
        stopTrialFollow();
        // 结算锁必须在这里解开：切后台再回来是个新页面状态，返回箭头不该一直灰着
        setSettlingLock(false);
        // 页面被盖住（home / 切后台）时下面会 abandon 会话，native 那头再也不会
        // 回调出分 —— 卡片会永远停在「正在结算」。这种情况直接收掉，回来重开即可。
        if (wasSettling && mResultCard != null && mResultCard.dlg != null
                && mResultCard.dlg.isShowing()) {
            dismissResultCard();
            mResultCard = null;
            Log.i(TAG, "页面离开时仍在结算，卡片已收掉（会话已丢弃）");
        }
        if (!uiGone()) {
            mTrialBtn.setState("ic_sheet_trial.png", "演奏");
            mTrialBtn.setUiEnabled(true);
            setTrialUi(false);
            // 只是被盖住（切后台 / 按 home）：WebView 还活着，把演奏态标志关掉，
            // 回来时按预览态继续（只动 Java 侧，不发报文、不重绘）。
            // 光标只有胶囊一套，不用再额外"藏胶囊"。
            if (wasTrial && !isFinishing()) {
                mSheet.setMagicMode(false);
            }
        }
        if (wasTrial || UfpTrial.isRunning()) {
            // abandon 内部会推进会话代数：正在建会话的那条线程建完就自动作废，
            // 不会把静态状态写成本页面的。
            UfpTrial.abandon(mTrialListener);
            Log.i(TAG, "页面离开，演奏会话已丢弃（在途建会话也一并作废）");
        }
    }

    /**
     * 页面真正销毁：释放 WebView + 清掉所有延迟任务。
     *
     * WebView 不显式 destroy 的话，JS 引擎 / DOM 的原生内存不会随 Java 对象一起回收，
     * 每开一首谱就残留一份 —— 这才是"打开很多曲子后内存越涨越高"的主因。
     * 延迟任务（10 秒兜底、演示自动点按、光标恢复）都持有 Activity，也必须一起清。
     */
    @Override
    protected void onDestroy() {
        mUi.removeCallbacksAndMessages(null);
        // 恢复询问浮层挂在 android.R.id.content 上，页面没了必须自己摘掉
        if (mRestoreBar != null) {
            try {
                android.view.ViewParent p = mRestoreBar.getParent();
                if (p instanceof ViewGroup) ((ViewGroup) p).removeView(mRestoreBar);
            } catch (Throwable ignored) {
            }
            mRestoreBar = null;
            mRestoreNo = null;
        }
        // 结算卡持有 Activity：页面销毁时必须收掉，否则 leaked window
        if (mResultCard != null && mResultCard.dlg != null) {
            dismissResultCard();
            mResultCard = null;
        }
        // 兜底：onStop 没跑到的极端情况（直接销毁）也要收掉演奏会话。
        // ⚠ 不能只看 isRunning()：建会话在途时 mRunning 可能仍是 false，但
        // sListener 已经指向本页面，必须让 abandon 的 owner/代数校验去判断。
        // owner 不匹配时 abandon 会直接 return，不会误伤新页面的会话。
        UfpTrial.abandon(mTrialListener);
        if (mSheet != null) {
            mSheet.destroy();
            mSheet = null;
        }
        super.onDestroy();
    }

    /**
     * 整页画完后处理"上次的阅读现场"（只做一次）。
     *
     * 用户要求：有存档**先问**，不直接跳过去。问的是/否，带 3 秒
     * 倒计时，超时按「否」处理（= 删档，见 {@link #finishRestore(boolean)}）；
     * 没有存档就不问，光标直接落在曲首。
     *
     * 不能直接在 onSheetOverallLoaded 的回调里动谱面：那是 JS 重入，实测会把
     * 剩余行的绘制打断、整页空白。等渲染收尾后再做。
     */
    private void restoreSavedState() {
        if (mCursorRestored || mRestoreScheduled || mRestoreBar != null) return;
        // 存档还没从磁盘/缓存读出来：这次先不判断，等 bootstrap 赋值后补调
        // （否则会把"还没读到"误判成"本来没有"，见 mResumeScanned 的注释）。
        if (!mResumeScanned) return;
        if (mResumeSection == null && mResumeCursor == null) {
            mCursorRestored = true;      // 本来就没有存档，不必再问
            placeCursorAtStart();
            return;
        }
        mRestoreScheduled = true;
        mUi.postDelayed(() -> {
            mRestoreScheduled = false;
            if (uiGone() || mSheet == null) return;
            // 延迟期间用户可能已经点了演奏或试听：别让浮层盖在他正在做的事上，
            // 这次跳过（按停 / 起播失败时还会再调本方法补一次机会）。
            // ⚠ 绝不能在这里把 mCursorRestored 置位 —— 原来在方法开头就置位，
            // 会让"首屏后提前点试听"把上次的存档机会永久丢掉。
            if (mTrialRunning || mPreviewActive || mSecPicking) return;
            showRestorePrompt();
        }, 120);
    }

    /**
     * Snackbar 风格的询问条：中下部浮层、不阻塞谱面操作，倒计时显示在「否」
     * 按钮上（Material 的 "Undo (3)" 那一套），到 0 自动按否。
     */
    private void showRestorePrompt() {
        if (mRestoreBar != null || uiGone()) return;
        final boolean hasSection = mResumeSection != null;

        // 两行布局：第一行问句，第二行「恢复 / 不用」
        // 避免长问句把按钮挤到右边、整条忽宽忽窄。
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        // 与 toast / 载入卡 / 成绩卡统一：半透明棕底 + 24dp 圆角 + 2dp 金边 + 6dp 浮层高度
        bg.setColor(Ui.FLOAT_BG);
        bg.setCornerRadius(Ui.floatDp(this, 24));
        bg.setStroke(Ui.floatDp(this, 2), Ui.GOLD_LIGHT);
        bar.setBackground(bg);
        bar.setPadding(Ui.floatDp(this, 20), Ui.floatDp(this, 16),
                Ui.floatDp(this, 20), Ui.floatDp(this, 16));
        bar.setElevation(Ui.floatDp(this, 6));

        TextView msg = new TextView(this);
        // 用词与载入卡片统一（不暴露「光标」这个实现细节）
        msg.setText(hasSection ? "恢复上次的选段和进度？" : "恢复上次的进度？");
        msg.setTextColor(Ui.SHEET_BG);
        msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.floatSp(this, 21f));
        msg.setTypeface(Ui.titleFont(this));
        msg.setIncludeFontPadding(false);
        msg.setSingleLine(true);
        msg.setGravity(Gravity.CENTER);
        bar.addView(msg, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 第二行：两个按钮水平居中
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        // 两颗都走 Ui.floatButton 的**紧凑档**：主/次同款圆角、金边、按下态。
        // 原来「恢复」是 15dp 金药丸 + 无边框 + titleFont，「不用」是纯文字 +
        // bodyFont，同一行里不像一对；随后又发现统一成
        // 成绩卡那档（21sp/56dp）后按钮比 21sp 的问句还大，整条像"大按钮卡片"
        // —— 见 Ui.floatButton 的两档说明。
        // 紧凑档：整条只有一行问句（21sp），按钮用 18sp/48dp 才不成"大按钮卡片"
        TextView yes = Ui.floatButton(this, "恢复", true, Ui.SIZE_COMPACT);
        yes.setOnClickListener(v -> finishRestore(true));
        LinearLayout.LayoutParams ylp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row.addView(yes, ylp);

        TextView no = Ui.floatButton(this, "不用 (3)", false, Ui.SIZE_COMPACT);
        no.setOnClickListener(v -> finishRestore(false));
        // ⚠ measure() 之前必须先把 LayoutParams 挂上：measure 会生成 layout，
        // 之后 setText() 触发的 checkForRelayout() 要读 getLayoutParams().width，
        // 空 LP 直接 NPE 崩溃（261111042 在模拟器上崩的就是这里）。
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        no.setLayoutParams(nlp);
        // 倒计时数字是**比例字宽**：把宽度定死在三档里最宽的那档，
        // 「不用 (3)」→「(2)」→「(1)」时整条提示框不左右抖。
        // ⚠ 必须用 View 自己的 measure()（Paint.measureText 少算字距，会折行）。
        int noW = 0;
        int unsp = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        for (int i = 1; i <= 3; i++) {
            no.setText("不用 (" + i + ")");
            no.measure(unsp, unsp);
            noW = Math.max(noW, no.getMeasuredWidth());
        }
        no.setText("不用 (3)");
        nlp.width = noW + Ui.floatDp(this, 1);
        nlp.leftMargin = Ui.floatDp(this, 12);   // 两个按钮之间的间隔
        row.addView(no, nlp);
        mRestoreNo = no;

        // 按钮行挂到第二行（第一行是问句）
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = Ui.floatDp(this, 10);
        bar.addView(row, rowLp);

        FrameLayout content = (FrameLayout) findViewById(android.R.id.content);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        // 宽度按内容收窄（原来写死 470dp，对这么短一句话太长），整条因此只有
        // 文字 + 两个按钮那么宽；按钮宽度已定死，倒计时变化不会再改总宽。
        int hpx = getResources().getDisplayMetrics().heightPixels;
        // 与 Ui.toast 同一视线：共用 TOAST_BOTTOM_RATIO
        // 都落在屏幕中部 ~47%。恢复条比 toast 高（多一行按钮 + 更大字号），中心会略高一点，
        // 但仍在同一区域。别再各写一个 bottomMargin 比例，改一处就两处一起变。
        lp.bottomMargin = Math.round(hpx * Ui.TOAST_BOTTOM_RATIO);
        content.addView(bar, lp);
        mRestoreBar = bar;
        mRestoreLeft = 3;
        mUi.postDelayed(mRestoreTick, 1000);
        Log.i(TAG, "询问是否载入上次阅读状态（3 秒倒计时）");
    }

    /**
     * 结束询问：是 = 载入存档；否 = **当场把这首曲子的存档删掉**，光标回曲首。
     *
     * 「否」包含三条路径：点「不用」、3 秒倒计时走完、以及浮层还在时用户直接
     * 开始试听/演奏/选段（见 autoDismissRestore）。三条都算"这次不要上次的进度"，
     * 一律删档，删的时机就在这一刻 —— 不能等退出时再用光标位置反推，因为
     * 这之后光标会被放回曲首、lastMeasure 也会复位成 -1，退出时已经分不清
     * "用户拒绝了"和"这次根本没定位过"。
     */
    private void finishRestore(boolean load) {
        finishRestore(load, true);
    }

    /**
     * @param placeIfEmpty 「否」时若画面上一个光标都没有，是否补一个曲首光标。
     *   · 点「不用」/ 3 秒倒计时 → true：用户等于说"这次什么都不恢复"，谱面不该空着；
     *   · 用户自己要定位（点谱面 / 拖进度条 / 横向划动 / 起试听·演奏）→ **false**：
     *     他下一句就会把光标摆到自己点的地方，这里先补一个曲首，画面上就是
     *     "闪到开头再跳过去" —— 正是用户反复要求消灭的闪烁。
     */
    private void finishRestore(boolean load, boolean placeIfEmpty) {
        mUi.removeCallbacks(mRestoreTick);
        if (mRestoreBar != null) {
            try {
                android.view.ViewParent p = mRestoreBar.getParent();
                if (p instanceof ViewGroup) ((ViewGroup) p).removeView(mRestoreBar);
            } catch (Throwable t) {
                Log.w(TAG, "移除恢复浮层失败：" + t);
            }
            mRestoreBar = null;
        }
        mRestoreNo = null;
        mCursorRestored = true;
        if (load) {
            if (uiGone() || mSheet == null) return;
            if (mResumeSection != null) {
                beginRestoring();
                if (!restoreSection(mResumeSection, mResumeCursor)) {
                    // 存档里的选段已失效，但**光标位置通常仍然有效**：别把用户丢回曲首。
                    // 原来 restoreSection 直接 return，随后 markRestoreTargetAndPoll()
                    // 拿不到 lastMeasure/mSecStartM，把目标退化成 {0,0,0} ——
                    // 用户点「恢复」只回到第 1 小节，白丢进度。
                    if (mResumeCursor != null) {
                        int[] c = mResumeCursor;
                        Log.i(TAG, "存档选段不可用，只恢复光标 " + c[0] + "/" + c[2] + "/" + c[1]);
                        // mRestoring=true → placeCursorAt 不会滚视口/不解锁，
                        // seekToNote 也不会再起一次跳转等待（beginJumpWait 里有守卫）
                        seekToNote(c[0], c[1], c[2], true);
                    } else {
                        placeCursorAtStart();
                    }
                }
                // 恢复的选段/光标也一样要锁可见：长曲子还在逐行绘制时
                // 后续批次会把刚画好的光标擦掉（用户报"到目标位置闪一下就没了"）。
                keepCursorAlive();
                markRestoreTargetAndPoll();
                return;
            }
            if (mSheet.lastMeasure() >= 0) return;   // 用户自己点过了，别抢
            // 恢复期间整个页面不可点：等画面稳定、光标真正落到目标小节再放开，
            // 免得迟到回调 / 点谱面 / 拖进度条和"稳定后重画"抢状态。
            beginRestoring();
            if (mResumeCursor != null) {
                int[] c = mResumeCursor;
                Log.i(TAG, "载入上次光标位置 " + c[0] + "/" + c[2] + "/" + c[1]);
                seekToNote(c[0], c[1], c[2], true);
                keepCursorAlive();
                markRestoreTargetAndPoll();
                return;
            }
            placeCursorAtStart();
            return;
        }
        // 不载入 = 用户不要上次的进度：**立刻删档**，不等退出。
        // 「不用」/3 秒超时 = 不恢复：不滚回上次的位置、不建会话、不动用户已有的落点。
        //
        // 但它**不等于"连光标都不给"**：接受恢复时画面上是有
        // 光标的，拒绝之后如果谱面空着、一个落点都没有，反而像"卡住了没反应"。
        // 所以补一条：**画面上本来就有光标就一点不动；完全没有才补一个曲首光标**。
        // 补的这一步复用 placeCursorAtStart()（立刻落笔 + CSS 锁可见），不会出现
        // "闪一下就被重绘擦掉"的动画感。
        discardReadingState();
        if (!placeIfEmpty) {
            Log.i(TAG, "不用：用户接下来要自己定位，先不摆光标");
            return;
        }
        if (uiGone() || mSheet == null) return;
        if (hasVisibleCursor()) {
            Log.i(TAG, "不用：画面上已有光标，保持不动");
            return;
        }
        Log.i(TAG, "不用：画面上没有光标，补一个曲首光标");
        placeCursorAtStart();
    }

    /**
     * 画面上此刻是否已经有一个光标。
     *
     * 只查两个**同步**字段，不去问 DOM（JS 查询是异步的，还会和绘制抢状态）：
     *   · {@link #mCursorLocked} —— 我们刚放的光标，正被 CSS 锁成常亮；
     *   · `mSheet.lastMeasure() >= 0` —— 用户点过谱面 / 恢复过存档，位置已知。
     * ⚠ 必须两个都看：`placeCursorAtStartNow()` 在 300ms 后会把 lastMeasure 复位成
     *   -1（"程序摆的曲首不算用户定位"），只认 lastMeasure 会把已经在曲首的光标
     *   判成"没有"，于是再画一遍。
     */
    private boolean hasVisibleCursor() {
        return mCursorLocked || (mSheet != null && mSheet.lastMeasure() >= 0);
    }

    /**
     * 点「恢复」后进入"恢复中"：整个页面不可点，等画面稳定、光标真正落到
     * 目标小节再一次性放开。
     *
     * 竞态来源（用户担心、实测也确实存在）：首屏 / 整页渲染完成 / 10 秒兜底
     * 这三个**迟到回调**都会调 `openEntryButtons()` 与 `showLoading(false)`，
     * 一不留神就会在恢复进行到一半时把按钮点亮、把遮罩撤掉；此外点谱面、横向
     * 划动进度条、点演奏/试听/选段都会改光标状态，和"稳定后原地重画"抢位置。
     * 所以：① 全屏透明拦截层吃掉一切触摸；② 按钮显式置灰；
     * ③ `openEntryButtons()` / `showLoading(false)` / `updateTransportState()`
     * 三处都加 `mRestoring` 守卫；④ 系统返回键仍可用（按键事件不吃）。
     *
     * 遮罩延迟 250ms 才显示：短曲子判稳往往不到 1 秒，避免"闪一下 loading"。
     */
    private void beginRestoring() {
        if (mRestoring) return;
        mRestoring = true;
        // 载入框进度的起点：当前已经画到哪
        mWaitDrawnMeasure = mSheet != null ? mSheet.lastDrawnMeasure() : -1;
        // 停滞检测的起点（见 mRestorePollRun）：从"现在画到哪"开始计时
        mRestoreLastDrawn = mWaitDrawnMeasure;
        mRestoreLastDrawnAt = android.os.SystemClock.elapsedRealtime();
        mRestoreDrawnStuck = 0;
        // 按钮先立刻置灰，遮罩等 250ms 再显示（短曲子判稳快，不闪这一下）
        lockEntryButtons();
        mUi.removeCallbacks(mShowRestoreLoadingRun);
        mUi.postDelayed(mShowRestoreLoadingRun, 250);
        // 独立看门狗：**不依赖轮询链**。轮询万一被某条异常路径摘掉，25 秒上限
        // 也就永远不会触发，用户看到的就是"卡片一直挂着"。
        mUi.removeCallbacks(mRestoreWatchdog);
        mUi.postDelayed(mRestoreWatchdog, RESTORE_WATCHDOG_MS);
        // ⚠ 恢复锁的解除**必须有判据**，不是"到 N 秒就无脑放开"：低性能设备上整页
        // 还没画完时放开，用户点的谱面/拖的进度条会被后续重绘与判稳重画覆盖 ——
        // 看着能点，其实白点。现在三条判据
        // 都不是"纯计时"：① 目标真的上屏；② 目标行已画出来但连续 3 次判不在视口
        // （滚动失效）；③ 已画到的小节 6 秒没动（渲染停滞）。25 秒只是最后的硬保底。
    }

    /**
     * 载入框在"还没有任何可报的进度"时的文案：解包 → 引擎初始化 → 解密 → 解析
     * → **首次排版**，这一段都算初始化（用户 明确："解析 + 首次排版
     * 也应该归到初始化时间里"）。它和「正在准备第 x/y 小节…」是同一件事的两个阶段，
     * 不是一个独立的状态。
     */
    private static final String WAIT_INIT_TEXT = "正在初始化曲谱…";

    /**
     * 刷载入框文案。等待目标小节时显示「正在准备第 当前/目标 小节…」，
     * 当前值来自 {@link #mWaitDrawnMeasure}（渲染器每画完一批行就报一次）。
     *
     * 冷启动分两段：一行都还没画出来时是 {@link #WAIT_INIT_TEXT}
     * （解析/首次排版，没有进度可报，写 `1/N` 只会让人觉得"卡在 1"）；
     * 第一批行画完才开始报，单位是**行**、分母是首屏需要的行数
     * （`FIRST_SCREEN_ROWS`）—— 冷启动只等首屏，拿整曲小节数当分母不对应
     * 用户实际在等的东西。
     */
    private void updateWaitText() {
        if (mLoadingText == null) return;
        int target = -1;
        if (mJumpTarget != null) {
            target = mJumpTarget[0];
        } else if (mRestoring && mRestoreTarget != null) {
            target = mRestoreTarget[0];
        }
        if (target < 0) {
            // 冷启动：**不报进度**，一直「正在初始化曲谱…」到首屏画完撤卡。
            // 试过两种分子都不合适：整曲小节数（386，跟"只等首屏"不对应、永远走不到头）、
            // 首屏行数（3 行，数字太碎、跳两下就没了）。引擎这边拿不到"首屏包含哪些小节"，
            // 索性和解析/排版并成同一句话。
            setWaitText(WAIT_INIT_TEXT);
            return;
        }
        int targetShown = AlgoEngine.get().measureNumber(target);
        int cur = mWaitDrawnMeasure >= 0 ? Math.min(mWaitDrawnMeasure, target) : -1;
        setWaitText(cur > 0
                ? "正在准备第 " + AlgoEngine.get().measureNumber(cur) + "/" + targetShown + " 小节…"
                : "正在准备第 " + targetShown + " 小节…");
    }

    /** 载入框那行字**唯一**的写入点（别在别处直接 `mLoadingText.setText`）。 */
    private void setWaitText(String s) {
        if (mLoadingText == null || s == null || s.isEmpty()) return;
        mLoadingText.setText(s);
    }

    private final Runnable mShowRestoreLoadingRun = () -> {
        if (!mRestoring || uiGone()) return;
        // 文案与"拖进度条跳转"统一成"正在准备第 x/y 小节…"：两者耗时是同一件事
        // （等渲染器画到目标行），没必要用两套说法。
        updateWaitText();
        showLoading(true);   // 与冷启动同一套：卡片 + 拦截层 + 按钮锁
    };

    /**
     * 记下恢复目标并启动"等跳转"轮询。
     *
     * 解锁条件（见 {@link #mRestoreTarget}）：目标小节真的滚到屏幕内，且随后画面
     * 安静。轮询里目标没上屏就定期重试滚动（渲染器要等那一行的 DOM 画出来才滚得动），
     * 上屏了就重新起一次判稳、由 onSheetSettled 收尾解锁。
     */
    private void markRestoreTargetAndPoll() {
        if (uiGone() || mSheet == null) { endRestoring(); return; }
        int m = mSheet.lastMeasure();
        if (m >= 0) {
            mRestoreTarget = new int[]{m, mSheet.lastVoice(), mSheet.lastOffset()};
        } else if (mSecStartM >= 0) {
            mRestoreTarget = new int[]{mSecStartM, mSecStartV, mSecStartO};
        } else {
            mRestoreTarget = new int[]{0, 0, 0};
        }
        mJumpConfirmed = false;
        mRestorePollStart = android.os.SystemClock.elapsedRealtime();
        mRestoreLastScrollAttempt = 0;
        Log.i(TAG, "恢复目标 = 第 " + AlgoEngine.get().measureNumber(mRestoreTarget[0]) + " 小节，等它真的上屏");
        mUi.removeCallbacks(mRestorePollRun);
        mUi.postDelayed(mRestorePollRun, RESTORE_POLL_INTERVAL_MS);
    }

    /**
     * 恢复等待的独立看门狗：只兜"轮询链断了"。
     *
     * 恢复锁原本只有轮询一条路能解；轮询要是被某条异常路径摘掉（或 Handler 消息
     * 被误清），卡片就永久挂着 —— 用户报"卡在正在准备第 x/y 小节上不动，不点取消
     * 就一直等"。这个任务由 {@link #beginRestoring()} 单独 post，不参与轮询链，
     * 到点无条件收尾（光标仍会摆到目标小节）。
     */
    private final Runnable mRestoreWatchdog = () -> {
        if (!mRestoring || uiGone()) return;
        int target = mRestoreTarget != null ? mRestoreTarget[0] : -1;
        int drawn = mSheet != null ? mSheet.lastDrawnMeasure() : -1;
        Log.w(TAG, "恢复看门狗触发（" + RESTORE_WATCHDOG_MS / 1000 + "s）：目标第 "
                + AlgoEngine.get().measureNumber(Math.max(0, target))
                + " 小节，已画到第 " + AlgoEngine.get().measureNumber(Math.max(0, drawn))
                + " 小节，强制解锁");
        jumpConfirmedThenUnlock();
    };

    private final Runnable mRestorePollRun = new Runnable() {
        @Override public void run() {
            if (!mRestoring || uiGone() || mSheet == null || mRestoreTarget == null) return;
            long now = android.os.SystemClock.elapsedRealtime();
            // 渲染停滞检测：轮询同时观察"已画到哪"，6 秒没推进且还没画到目标 →
            // 认为逐行绘制卡住了（真机偶发），立刻放人，别让卡片挂在那儿不动。
            int drawn = mSheet.lastDrawnMeasure();
            if (drawn != mRestoreLastDrawn) {
                mRestoreLastDrawn = drawn;
                mRestoreLastDrawnAt = now;
            }
            final boolean stalled = drawn < mRestoreTarget[0]
                    && now - mRestoreLastDrawnAt > RESTORE_STALL_MS;
            if (now - mRestorePollStart > RESTORE_POLL_MAX_MS || stalled) {
                Log.w(TAG, (stalled ? "渲染停滞" : "恢复等待超时")
                        + "（已画到第 " + AlgoEngine.get().measureNumber(Math.max(0, drawn))
                        + " / 目标第 " + AlgoEngine.get().measureNumber(mRestoreTarget[0])
                        + " 小节），强制解锁");
                jumpConfirmedThenUnlock();
                return;
            }
            // 先排下一次轮询再发查询：万一 JS 回调丢了（WebView 忙到没回执），
            // 轮询也不能断，否则恢复锁就永远解不开了。
            mUi.postDelayed(mRestorePollRun, RESTORE_POLL_INTERVAL_MS);
            mSheet.queryMeasureReady(mRestoreTarget[0], mRestoreTarget[1], mRestoreTarget[2], vis -> {
                if (!mRestoring || uiGone() || mRestoreTarget == null) return;
                if (!vis) {
                    // 目标行**已经画出来了**，但连查 3 次都判"不在视口"：说明滚动没生效
                    // （或判定用的 zoom 与实际不符）。继续每 2 秒补滚只是空转，直接解锁。
                    if (drawn >= mRestoreTarget[0]) {
                        if (++mRestoreDrawnStuck >= 3) {
                            Log.w(TAG, "目标行已画出（第 "
                                    + AlgoEngine.get().measureNumber(mRestoreTarget[0])
                                    + " 小节）但连续 3 次判不在视口，按滚动失效解锁");
                            jumpConfirmedThenUnlock();
                            return;
                        }
                    } else {
                        mRestoreDrawnStuck = 0;
                    }
                    // 目标行还没画出来 / 滚动还没生效：定期重试一次滚动。
                    long t = android.os.SystemClock.elapsedRealtime();
                    if (t - mRestoreLastScrollAttempt >= RESTORE_SCROLL_RETRY_MS) {
                        mRestoreLastScrollAttempt = t;
                        mPlacingStart = true;
                        try {
                            placeCursorAt(mRestoreTarget[0], mRestoreTarget[1], mRestoreTarget[2], true);
                        } finally {
                            mPlacingStart = false;
                        }
                    }
                    return;
                }
                // 跳转真的发生了：最后原地重画一次（纠正重排位移并滚回目标），
                // 然后**立刻解锁** —— 用户的要求是"卡片消失=马上能用"。
                // 不再等跳转后的"画面安静"：下方未画的行是往下追加，不会移动
                // 已经画好的行，光标位置不会漂；判稳兜底（mForcePlaceRun）保留，
                // 只负责它原本的"重画"，不再承担解锁。
                Log.i(TAG, "恢复目标（第 " + AlgoEngine.get().measureNumber(mRestoreTarget[0]) + " 小节）已滚到屏幕内，解锁");
                mJumpConfirmed = true;
                mUi.removeCallbacks(mRestorePollRun);
                mUi.removeCallbacks(mForcePlaceRun);
                mPlacingStart = true;
                try {
                    placeCursorAt(mRestoreTarget[0], mRestoreTarget[1], mRestoreTarget[2], true);
                } finally {
                    mPlacingStart = false;
                }
                endRestoring();
            });
        }
    };

    /** 跳转兜底收尾：重画一次光标并解除恢复锁（判稳回调迟迟不来时用）。 */
    private void jumpConfirmedThenUnlock() {
        mUi.removeCallbacks(mRestorePollRun);
        mJumpConfirmed = true;
        if (mRestoreTarget != null) {
            mPlacingStart = true;
            try {
                placeCursorAt(mRestoreTarget[0], mRestoreTarget[1], mRestoreTarget[2], true);
            } finally {
                mPlacingStart = false;
            }
        }
        endRestoring();
    }

    private void endRestoring() {
        if (!mRestoring) return;
        mRestoring = false;
        mWaitDrawnMeasure = -1;
        mUi.removeCallbacks(mShowRestoreLoadingRun);
        mUi.removeCallbacks(mRestorePollRun);
        // 看门狗必须一起摘：它只认 mRestoring，留着也只是空跑一次（开头就 return），
        // 但别让一个已经解锁的会话还挂着定时任务
        mUi.removeCallbacks(mRestoreWatchdog);
        mRestoreTarget = null;
        mJumpConfirmed = false;
        mRestoreDrawnStuck = 0;
        if (uiGone()) return;   // 页面已销毁，别再碰任何 View
        setWaitText(WAIT_INIT_TEXT);   // 下次显示载入框时由 updateWaitText 重新填
        showLoading(false);   // 守卫已解除，这里才真的撤卡片/拦截层并放开按钮
    }

    /**
     * 用户拖进度条/跳到尚未渲染的位置后调用：先等一小会儿，确认目标没上屏就
     * 亮出「正在准备第 x 小节…」卡片，直到那一行真的画出来（或超时）。
     *
     * @param backM/backV/backO 取消时回到的位置（发起跳转前光标所在处）
     */
    private void beginJumpWait(int measure, int voice, int offset,
                               int backM, int backV, int backO) {
        if (mRestoring || uiGone() || mSheet == null) return;
        mJumpTarget = new int[]{measure, voice, offset};
        mJumpBackMeasure = backM;
        mJumpBackVoice = backV;
        mJumpBackOffset = backO;
        mJumpStartAt = android.os.SystemClock.elapsedRealtime();
        mJumpLastScrollAt = mJumpStartAt;   // 第一次补发滚动在 8 秒后
        // 用"已经画到哪"作起点：渲染可能早就开始了，等下一批行回调才有数会一直空着
        mWaitDrawnMeasure = mSheet != null ? mSheet.lastDrawnMeasure() : -1;
        mUi.removeCallbacks(mJumpShowRun);
        mUi.removeCallbacks(mJumpPollRun);
        // 200ms 先查一轮：目标已在视口里（短距离跳转）就什么都不做，不能闪一下卡片。
        mUi.postDelayed(mJumpPollRun, 200);
        mUi.postDelayed(mJumpShowRun, 500);
        Log.i(TAG, "跳转等待：第 " + AlgoEngine.get().measureNumber(measure) + " 小节（未上屏则显示载入框）");
    }

    private final Runnable mJumpShowRun = () -> {
        if (mJumpTarget == null || uiGone()) return;
        Log.i(TAG, "目标未上屏，显示载入框（第 " + AlgoEngine.get().measureNumber(mJumpTarget[0]) + " 小节）");
        updateWaitText();
        showLoading(true);
    };

    private final Runnable mJumpPollRun = new Runnable() {
        @Override public void run() {
            if (mJumpTarget == null || uiGone() || mSheet == null) return;
            final int[] t = mJumpTarget;   // {measure, voice, offset}
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - mJumpStartAt > JUMP_WAIT_MAX_MS) {
                Log.w(TAG, "跳转目标一直没上屏（超 " + JUMP_WAIT_MAX_MS / 1000 + "s），放弃等待");
                endJumpWait();
                return;
            }
            // 800ms 一次：每次查询都是一次 JS 往返，渲染期间太密会和逐行绘制抢线程，
            // 反而让"目标行画出来"更慢（实测 400ms 轮询把整页拖长了几秒）。
            mUi.postDelayed(this, 800);
            mSheet.queryMeasureReady(t[0], t[1], t[2], vis -> {
                // 身份校验：queryMeasureReady 的回调是异步的，**已经发出的查询
                // 取消不掉**。用户在等目标 A 时又拖到 B，beginJumpWait 会把
                // mJumpTarget 换成新数组；A 的结果回来若只管 `!= null`，就会
                // 撤掉 B 的载入框并停掉 B 的轮询，或者按 A 补一次滚动把视口拉回去。
                // 比对新旧数组引用即可（同一次跳转复用同一个 t）。
                if (mJumpTarget != t) return;
                if (vis) {
                    Log.i(TAG, "跳转目标（第 " + AlgoEngine.get().measureNumber(t[0]) + " 小节）已上屏，撤载入框");
                    endJumpWait();
                    return;
                }
                long n = android.os.SystemClock.elapsedRealtime();
                // 渲染器自己会缓存 scrollTo（lastSyncScrollCache），画到那一行时自动滚；
                // 不需要每 2 秒重发一次滚动。只在等了很久还没动静时补一次，避免
                // 反复的滚动计算打断绘制。
                if (n - mJumpLastScrollAt >= JUMP_WAIT_RETRY_MS) {
                    mJumpLastScrollAt = n;
                    Log.i(TAG, "目标仍未上屏，补一次滚动（第 " + AlgoEngine.get().measureNumber(t[0]) + " 小节）");
                    // moveCursor 是 (measure, offset, voice)：这里的 t 是 {m, v, o}，要换序
                    mSheet.moveCursor(t[0], t[2], t[1], true);
                }
            });
        }
    };

    /** 结束跳转等待：撤卡片、放开按钮（目标上屏 / 超时 / 页面离开都走这里）。 */
    private void endJumpWait() {
        if (mJumpTarget == null) return;
        mJumpTarget = null;
        mWaitDrawnMeasure = -1;
        mUi.removeCallbacks(mJumpShowRun);
        mUi.removeCallbacks(mJumpPollRun);
        if (mLoadingCancel != null) mLoadingCancel.setVisibility(View.GONE);
        if (uiGone()) return;
        setWaitText(WAIT_INIT_TEXT);   // 下次显示载入框时由 updateWaitText 重新填
        showLoading(false);
    }

    /**
     * 载入框上的「取消」：恢复中 = 等同「不用」（删档、光标回曲首）；
     * 跳转中 = 回到发起跳转前的位置。两条都立刻解锁。
     */
    private void onLoadingCancel() {
        if (mJumpTarget != null) {
            cancelJumpWait();
        } else if (mRestoring) {
            cancelRestoreWait();
        }
    }

    private void cancelJumpWait() {
        int m = mJumpBackMeasure, v = mJumpBackVoice, o = mJumpBackOffset;
        Log.i(TAG, "用户取消跳转，回到 " + m + "/" + v + "/" + o);
        endJumpWait();
        if (uiGone() || mSheet == null) return;
        if (m >= 0) placeCursorAt(m, v, o, false);
        else placeCursorAtStart();
    }

    /**
     * 用户取消恢复：等同「不用」，但必须**把已经发生的恢复一并回滚**。
     *
     * ⚠ 点「恢复」的那一刻 finishRestore(true) 就 seekToNote / restoreSection 了，
     * 载入卡片只是在"等目标小节画出来"。所以取消时若只撤卡片 + 删档，
     * 光标、播放头、进度条、视口仍停在存档位置；更糟的是 keepCursorAlive()
     * 排的判稳重画里记的也是恢复目标，整页画完会把光标再画回上次进度。
     * 与 cancelJumpWait() 对称：取消跳转会回到发起前的位置，取消恢复要回曲首。
     */
    private void cancelRestoreWait() {
        Log.i(TAG, "用户取消恢复，按「不用」处理并回到曲首");
        // 1) 先关掉所有还能再次触发恢复的入口，再撤卡片 —— endRestoring() 会走
        //    openEntryButtons() → restoreSavedState()，必须保证它这次直接返回。
        mCursorRestored = true;
        mUi.removeCallbacks(mRestoreTick);
        if (mRestoreBar != null) {
            try {
                android.view.ViewParent p = mRestoreBar.getParent();
                if (p instanceof ViewGroup) ((ViewGroup) p).removeView(mRestoreBar);
            } catch (Throwable t) {
                Log.w(TAG, "移除恢复浮层失败：" + t);
            }
            mRestoreBar = null;
        }
        mRestoreNo = null;
        final boolean hadSection = mResumeSection != null && mSecOn;
        mResumeCursor = null;
        mResumeSection = null;

        endRestoring();       // 撤卡片 / 解锁 / 停掉恢复轮询
        // 2) 停掉"判稳后原地重画"：里面记的是恢复目标，晚到会再把光标拽回去
        mUi.removeCallbacks(mForcePlaceRun);
        mPlaceAtStartPending = false;
        if (mSheet != null) mSheet.endSettleWatch();
        discardReadingState();
        if (uiGone() || mSheet == null) return;
        // 3) 播放头 / 进度条 / 状态行 / 视口 / 光标一起回曲首。
        //    先归零再撤选段：cancelSection() 会按 headMeasure() 补画一次光标，
        //    顺序反了它会把旧位置又画一遍。
        rewindToStart(0, 0, 0, true);
        if (hadSection) cancelSection();   // 括号 / native 区间 / 顶栏按钮文字
        // 4) 曲首光标上一步已经画好了，这里只补"锁可见 + 判稳重画 + 标记这不是
        //    用户定位"。**不要再走 placeCursorAtStart()**：它会再下发一次
        //    movePlayheadTo + 两次 moveCursor2，而这三条报文要排在整页渲染的
        //    WebView 队列后面（长曲实测 2~3 秒），期间光标可能又被重绘擦掉。
        keepCursorAlive();
        scheduleStartMarkReset();
    }

    private void lockEntryButtons() {
        if (mTrialBtn != null) mTrialBtn.setUiEnabled(false);
        if (mPlayBtn != null) mPlayBtn.setUiEnabled(false);
        if (mSectionBtn != null) mSectionBtn.setUiEnabled(false);
        if (mHandBtn != null) mHandBtn.setUiEnabled(false);
        if (mFav != null) mFav.setUiEnabled(false);
        // ⚠ 返回箭头**不禁用**：两个 loading 期间都允许退出。
        // 进谱加载时退出没有数据风险；恢复进度时退出，存档本来就没被删（只有点
        // 「不用」/超时才删档），onStop 会按当前光标位置存档，下次照常能接着看。
        // 全屏拦截层会挡住这个 View，所以 dispatchTouchEvent 里再按坐标放行一次。
        Ui.setDisabled(mSeek, true);
        Ui.setDisabled(mStatus, true);
        // 换曲（顶栏）与首尾跳转（底栏）在 loading 期间一律禁用；
        // 放开统一走 updateTransportState()，别在别处单独 setEnabled(true)。
        Ui.setDisabled(mPrevSheetBtn, true);
        Ui.setDisabled(mNextSheetBtn, true);
        Ui.setDisabled(mToStartBtn, true);
        Ui.setDisabled(mToEndBtn, true);
    }

    /**
     * 删掉本曲的阅读现场（选段 + 光标），保留「最近打开」那条记录。
     *
     * 只在"这次不要上次的进度"被确定的那一刻调用（点「不用」/ 倒计时走完 /
     * 直接开始试听·演奏·选段）。调试会话不碰存档，这里一并跳过 —— 否则一次
     * 回归测试就能把用户存的进度删了。
     */
    private void discardReadingState() {
        if (mDebugSession) {
            Log.i(TAG, "调试启动：不删除本曲存档");
            return;
        }
        String uuid = LibraryStore.uuidOfPath(mEncPath);
        if (uuid == null) return;
        LibraryStore.get(this).clearReadingState(uuid);
        Log.i(TAG, "不载入上次进度，已清除本曲存档的阅读现场");
    }

    /**
     * 不载入存档 / 没有存档：把光标**画在乐谱最开始的地方**
     * 同时把播放头 / 进度条 / 状态行一起归零（AlgoEngine 单例不会自己复位）。
     *
     * 之前为了躲"进谱微小上移"把这一步整段删掉，结果谱面上根本没有可见光标 ——
     * 等于把用户的需求改没了。上移的根因已在渲染器侧修掉
     * （见 SheetView 的「首行 top 滚动补丁」：视口在顶部时，第一行的 top 对齐
     * 滚动会被短路），所以这里可以正常下发 moveCursor2 画曲首光标，视口不动。
     *
     * ⚠ **画上去之后必须锁住可见性**：首屏只画了前一两行，剩下的行还在后台
     * 一批批补，大谱面（保卫黄河）还要补 4~6 秒；每批重绘都会把光标元素改回
     * hidden —— 用户看到的就是"第一个位置有个光标在闪"。
     * 单纯"等画完再画"试过，不行：它的"完成"回调 4.6s 就来，而画面还在长。
     * 现在是 立刻画 + CSS 锁可见（{@link SheetView#lockPreviewCursor()}），
     * 外加画面真稳定后再画一次纠正重排位移。
     */
    private void placeCursorAtStart() {
        if (uiGone() || mSheet == null || !mLoaded) return;
        // 1) 立刻落笔（曲首）
        placeCursorAtStartNow();
        // 2) 锁可见 + 等画面稳定后原地重画一次
        keepCursorAlive();
    }

    /**
     * 程序把光标放到某个位置之后（曲首 / 载入存档 / 恢复选段）统一走这里：
     * 锁住可见性，等画面真正停下再**原地**重画一次纠正重排位移。
     *
     * ⚠ 载入存档到很后面时最容易踩：seekToNote 把光标画到第 353 小节，
     * 但那时长曲子还在逐行绘制，后续批次会把光标擦掉 —— 用户看到的正是
     * "到目标位置闪一下就没了"。以前只给曲首路径加了锁。
     */
    private void keepCursorAlive() {
        if (mSheet == null) return;
        mSheet.showPreviewCursor();
        mSheet.lockPreviewCursor();
        mCursorLocked = true;
        mPlaceAtStartPending = true;
        mSheet.beginSettleWatch();
        mUi.removeCallbacks(mForcePlaceRun);
        mUi.postDelayed(mForcePlaceRun, 22000);
    }

    /** JS 判定画面已稳定（或 20 秒封顶）→ 原地重画一次光标，随后放开页面。 */
    private void onSheetSettled() {
        flushPlaceAtStart();
        // 恢复流程里，只有"跳转已确认"之后的判稳才撤卡片；
        // 判稳 20s 封顶先到、目标还没上屏时，卡片必须继续留着（等轮询重试滚动）。
        if (mRestoreTarget == null || mJumpConfirmed) endRestoring();
    }

    /** mForcePlaceRun 的实际逻辑（单独提出来：Runnable 自引用自己会编译不过）。 */
    private void forcePlaceNow() {
        Log.w(TAG, "画面稳定回调超时，强制重画光标");
        if (mSheet != null) mSheet.endSettleWatch();
        flushPlaceAtStart();
        // 恢复流程里目标还没上屏：不能撤卡片（撤了用户就会点在空白/旧位置上），
        // 让恢复轮询继续重试滚动，跳转确认后再解锁。
        if (mRestoreTarget != null && !mJumpConfirmed) {
            Log.i(TAG, "恢复目标还没上屏，继续等轮询重试滚动");
            mUi.postDelayed(mForcePlaceRun, JUMP_QUIET_FALLBACK_MS);
            return;
        }
        endRestoring();
    }

    /** 兜底：稳定回调彻底没来，也不能漏掉"纠正重排位移"这一步、更不能把页面锁死。 */
    private final Runnable mForcePlaceRun = () -> forcePlaceNow();

    private void flushPlaceAtStart() {
        if (!mPlaceAtStartPending) return;
        mPlaceAtStartPending = false;
        mUi.removeCallbacks(mForcePlaceRun);
        if (mSheet != null) mSheet.endSettleWatch();
        if (uiGone() || mSheet == null || !mLoaded) return;
        // 用户已经点了试听/演奏/选段：光标由播放或选段带着走，别再动它。
        if (mTrialRunning || mPreviewActive || mSecPicking) {
            Log.i(TAG, "画面稳定时已在试听/演奏/选段，不再重画光标");
            return;
        }
        // 原地重画：有位置（载入存档/恢复选段后的目标小节）就画回那儿，
        // 没定位过（曲首路径 resetLastMeasure 后是 -1）就画曲首。
        int m = mSheet.lastMeasure();
        if (m >= 0) {
            Log.i(TAG, "画面已稳定，重画光标到第 " + AlgoEngine.get().measureNumber(m) + " 小节（不动视口）");
            mPlacingStart = true;   // 这是程序重画，不能让 placeCursorAt 的解锁逻辑打断
            try {
                // ⚠ 这里**永远不滚视口**：恢复目标在解锁那一刻
                // 已经由 mRestorePollRun 的 placeCursorAt(..., true) 滚到位，整页画完
                // 再滚一次是历史遗留（加"目标上屏才解锁"的轮询之前，渲染器会在逐行
                // 绘制时把视口拉走）。实测第 303 / 384 小节不滚也稳定；留着它反而会
                // 在用户自己滚过谱面后把视口抢回去。只重画光标（带 wb_no_scroll）。
                placeCursorAt(m, mSheet.lastVoice(), mSheet.lastOffset(), false);
            } finally {
                mPlacingStart = false;
            }
        } else {
            Log.i(TAG, "画面已稳定，重画曲首光标（纠正重排位移）");
            placeCursorAtStartNow();
        }
        mSheet.showPreviewCursor();
        mSheet.lockPreviewCursor();
        mCursorLocked = true;
    }

    private void placeCursorAtStartNow() {
        // 标记"这一下是程序放曲首"，别让 placeCursorAt 里的解锁/放弃逻辑把它自己打断
        mPlacingStart = true;
        try {
            placeCursorAt(0, 0, 0, false);   // followScroll=false，且首行滚动已被补丁挡住
        } finally {
            mPlacingStart = false;
        }
        // moveCursor2 只重画胶囊，不会把被 HIDE_TRAIL_CURSORS_JS 藏掉的整组
        // 重新显出来；补一刀，曲首的黄色光标才真的看得见（见 SheetView.showPreviewCursor）。
        mSheet.showPreviewCursor();
        scheduleStartMarkReset();
        Log.i(TAG, "光标放在曲首（首行不滚动，不算用户定位）");
    }

    /**
     * 这一下是**程序**放的曲首，不是"用户主动把光标定位到第一小节"：稍后把
     * lastMeasure 复位成 -1，让它不算"定位过"，退出时走「尚未定位」分支
     * （保留/不动存档，删除已在 discardReadingState 做过）。
     *
     * 300ms 是为了等 moveCursor2 的 JS 异步回传落定，否则会被它再写回 0。
     * ⚠ 只清"程序放的曲首"这一种情况：300ms 内用户已经点到别处时，lastMeasure
     * 是他的定位，清掉会让他这一下不算定位（退出丢存档、hasVisibleCursor 误判成
     * "没有光标"）。长曲渲染慢时这个窗口很容易撞上。
     *
     * 两处调用：{@link #placeCursorAtStartNow()}（刚画完曲首光标）与
     * {@link #cancelRestoreWait()}（rewindToStart 已经画好了曲首，只补这一步）。
     */
    private void scheduleStartMarkReset() {
        mUi.postDelayed(() -> {
            if (!uiGone() && mSheet != null && mSheet.lastMeasure() == 0) {
                mSheet.resetLastMeasure();
            }
        }, 300);
    }

    /**
     * 用户已经开始操作（试听/演奏/选段）：浮层按「否」自动收掉，不打断他。
     * 与点「不用」完全相同 —— 包括删档。
     */
    private void autoDismissRestore() {
        if (mRestoreBar != null) {
            Log.i(TAG, "用户已开始操作，恢复询问按「否」处理");
            // 不补曲首光标：调用方紧接着就会按用户点的/拖到的位置定位，
            // 先补一个曲首会看到"闪到开头再跳过去"。
            finishRestore(false, false);
        }
    }

    /**
     * 恢复上次的选段：重画左右括号（段内音符集合由渲染器 confirmSelection 重算），
     * 并把光标/播放头放回上次的位置。
     *
     * 定位优先用上次的光标记（{@code cursor}），只有它不在段内（或无记录）时才回段首 ——
     * 选段只约束"能播的范围"，不约束"从哪儿开始练"。之后光标还能在段内自由移动，
     * 下次试听/演奏就从移动到的那个音符起。
     *
     * @return 选段是否真的恢复了。false = 存档里的选段越界/反序（典型：曲库更新后
     *         这首曲子变短了 / 旧版本写坏了），**此时一个字段都没改** ——
     *         调用方必须自己决定拿什么兜底（见 finishRestore：退回存档光标）。
     */
    private boolean restoreSection(int[] s, int[] cursor) {
        int total = AlgoEngine.get().getMeasureCount();
        int sm = s[0], so = s[1], sv = s[2], em = s[3], eo = s[4], ev = s[5];
        // 终点必须严格在起点之后：只比小节不够，同一小节里 ev/eo 更小同样是反的
        // （与 handleSectionTap 的比较口径保持一致：offset 是时间轴，voice 管同一拍上下）。
        boolean reversed = em == sm && (eo < so || (eo == so && ev < sv));
        if (sm < 0 || em < sm || reversed || (total > 0 && em >= total)) {
            Log.w(TAG, "旧选段越界/反序，忽略 " + sm + "/" + sv + "/" + so
                    + " -> " + em + "/" + ev + "/" + eo + "（共 " + total + " 小节）");
            return false;
        }
        mSecStartM = sm;
        mSecStartO = so;
        mSecStartV = sv;
        mSecEndM = em;
        mSecEndO = eo;
        mSecEndV = ev;
        mSecOn = true;
        mSecPicking = false;
        mSecKeys = null;      // 等渲染器 onSectionKeys 回调重新填
        mSectionBtn.setState("ic_sheet_section_off.png", "取消选段");
        setSectionPickingUi(false);
        syncPlaySection();   // 恢复存档里的选段也要下发 native 区间
        mSheet.sectionBegin(sm, sv, so);
        mSheet.sectionEnd(em, ev, eo);

        // 定位点：上次光标在段内就用它，否则回段首
        int tm = sm, to = so, tv = sv;
        if (cursor != null && cursor[0] >= sm && cursor[0] <= em) {
            tm = cursor[0];
            to = cursor[1];
            tv = cursor[2];
        }
        placeCursorAt(tm, tv, to, true);
        Log.i(TAG, "恢复选段 " + sm + "/" + sv + "/" + so + " -> " + em + "/" + ev + "/" + eo
                + "，光标回到 " + tm + "/" + tv + "/" + to);
        return true;
    }

    /**
     * 退出页面时把"阅读现场"记进库（键 = 乐曲 uuid）：光标位置 + 选段。
     * 光标记的是**实际所在的那颗音**（选段态也一样，段内可以自由移动）；
     * 从没定位过（lastMeasure &lt; 0）时，有选段就兜底记段首，否则不写，
     * 免得把上次的记录覆盖成"曲首"。
     */
    private void saveCursorPosition() {
        if (mDebugSession) {
            Log.i(TAG, "调试启动：跳过存档写入（不污染 recent.json 的选段/光标）");
            return;
        }
        if (mSheet == null) return;
        String uuid = LibraryStore.uuidOfPath(mEncPath);
        if (uuid == null) return;
        LibraryStore store = LibraryStore.get(this);
        final boolean secOn = mSecOn && mSecStartM >= 0 && mSecEndM >= 0;
        int m = mSheet.lastMeasure();
        int o = mSheet.lastOffset();
        int v = mSheet.lastVoice();
        if (m < 0 && secOn) {
            // 选段但还没点过任何音符：光标就是段首（选完段时已经收到左界）
            m = mSecStartM;
            o = mSecStartO;
            v = mSecStartV;
        }
        // ⚠ m < 0 = **这次从没定位过**（典型：刚打开曲子、谱面还没渲染完就
        // 触发了一次 onStop —— 方向切换/窗口重配都会这样，实测启动后 0.4s
        // 就来一发，那时 lastMeasure() 还是 -1）。这种情况必须**保留**上次
        // 的存档，绝不能当成"光标在第一小节"去 clearReadingState ——
        // 否则每次进谱都会把上次存的光标/选段悄悄抹掉，用户永远看不到
        // "是否载入上次状态"的询问。
        if (m < 0) {
            Log.i(TAG, "尚未定位过光标（m=-1），保留上次的阅读状态");
            return;
        }
        if (!secOn && m == 0 && o == 0) {
            // 光标就在**最开始**（第 1 小节、第 1 个音）：等价于"没有进度"，
            // 不留阅读现场，否则下次进谱会弹一个"是否载入上次光标"、载入完
            // 还是曲首，纯属打扰。
            // 除此之外**一律保存**：第 1 小节里往后挪到第 2 个音也算。
            store.clearReadingState(uuid);
            Log.i(TAG, "光标仍在曲首，不保存阅读状态");
            return;
        }
        // 选段与光标写在**同一个** recent.json 里，一次落盘即可；
        // 分成两次写会各触发一次全量序列化 + fsync（最多 50 条记录），
        // 退出曲谱时白等一趟 IO。
        store.saveReadingState(uuid,
                secOn ? new int[]{mSecStartM, mSecStartO, mSecStartV,
                        mSecEndM, mSecEndO, mSecEndV} : null,
                m, o, v);
        if (secOn) Log.i(TAG, "已记住选段 " + mSecStartM + " -> " + mSecEndM);
        if (m >= 0) {
            Log.i(TAG, "已记住光标位置 " + m + "/" + v + "/" + o + (secOn ? "（选段内）" : ""));
        }
    }

    /**
     * 把引擎的播放头挪到"光标最后所在的位置"。
     *
     * 演奏结束会把预览胶囊画回最后弹到的地方，但播放头是另一套状态（默认曲首）。
     * 不同步的话，屏幕上光标在第 5 小节、下次点演奏却从第 1 小节开始 ——
     * 用户看到的就是"没从黄色光标的位置开始"。
     */
    private void syncHeadToCursor() {
        int m = mSheet.lastMeasure();
        if (m < 0) return;
        AlgoEngine.get().movePlayheadTo(m, mSheet.lastVoice(), mSheet.lastOffset());
        Log.i(TAG, "播放头同步到光标位置 " + m);
    }

    /**
     * UI 是否已经不可用（Activity 正在销毁，或谱面 WebView 已销毁）。
     *
     * 引擎/喂音线程的回调比 Activity 活得久：用户按返回、或 System 回收页面之后，
     * UfpTrial 仍会回调 onCursor/onFinished。这些回调都在 runOnUiThread 里，
     * 里面直接摸 mSheet 就是 NPE（实测崩溃栈：
     *   NullPointerException ... SheetView.setMagicMode(boolean) on null reference
     *   at PlayerActivity$3.lambda$onFinished）。所有 UI 回调入口先过这一关。
     */
    private boolean uiGone() {
        return isFinishing() || isDestroyed() || mSheet == null;
    }

    private final TrialListener mTrialListener = new TrialListener() {
        @Override
        public void onLocked() {
            // 引擎第一次真正跟上弹奏。以前这里会往状态行写一句"弹到哪儿光标走到哪儿"
            // 的说明，现在状态行只报小节，什么也不用写。
        }

        @Override
        public void onCursor(int measure, int offset, int voice,
                             int score, int state, boolean recording) {
            // UF_FINISHED(state=3)：UfpTrial.step 里紧接着就会退出喂音循环并跑
            // ufpFinish（好几秒）。这里先切 UI 线程弹结算卡，否则这一整段
            // 界面都停在最后一个音上、按钮还是「演奏中」，看着像卡死。
            // 必须放在下面的 measure<0 早退之前：终点那一帧可能带 (-1,-1,-1)。
            if (state == 3) {
                runOnUiThread(() -> {
                    // 只对"本页仍在跑的演奏"生效。onStop 已经 abandon 的会话还会
                    // 吐最后一帧，那时再 markSettling 会把页面钉死在结算中：
                    // 结算卡不可关、返回键被 mTrialSettling 拦死，而 onFinished
                    // 永远不会来（会话已作废）。
                    if (!uiGone() && mTrialRunning && !mTrialCanceled) markSettling();
                });
            }
            // 引擎报的**就是绝对小节号**（谱面从未裁剪过），不需要任何偏移补偿。
            // 但跳转瞬间它会报 (-1,-1,-1)（位置未知），别让它把光标甩回第 1 小节
            if (measure < 0) return;
            final int m = measure;
            // 引擎刚开跑、还没听到音时会一路报 (0,0,0) —— 这是"位置未知"的空闲
            // 假位置，不是真实弹奏结果。只要本次起点不在曲首，它就不可能合法：
            // 放行会把起点光柱甩回第 1 小节，还会写脏 SheetView.lastMeasure()，
            // 演奏结束时 syncHeadToCursor() 再把播放头归零 → 下次点演奏真的从
            // 曲首开始（用户反馈"点演奏光标总跑到开始"）。时间窗口挡不住它，
            // 因为建会话本身就要几百毫秒到几秒，用户也不一定立刻弹。
            // 起点本来就在曲首时不挡：那正是真实的起弹位置。
            boolean startAtScoreHead = mTrialStartMeasure == 0 && mTrialStartOffset == 0;
            if (!startAtScoreHead && m == 0 && offset == 0 && voice == 0) {
                return;
            }
            // 原来写的是「m < 起点一律忽略」，但手动停止后起点停在中段，
            // 再点演奏从曲首弹时，要一路弹到那个位置光标才肯动 —— 用户看到的
            // 就是"隔很久才继续推进"。
            // 引擎刚建会话、还没听到音时也会一路报 (0,0,0)（位置未知），
            // 这种空闲误报同样只挡开跑后的短暂窗口。窗口之后一律跟随真实报告，
            // 哪怕报的位置在起点之前（那正是用户从头弹的情况）。
            if (SystemClock.elapsedRealtime() - mTrialStartedAtMs < TRIAL_START_GUARD_MS
                    && (m < mTrialStartMeasure
                        || (m == 0 && offset == 0 && voice == 0))) {
                return;
            }
            // 起点不在小节开头时，上面那种 (0,0,0) 还会把刚摆好的起点光柱
            // 冲回小节第一颗音。只挡"小节首 + 偏移 0 + 右手"这个形态，
            // 别误伤真实的左手音（voice>=4、offset 可能就是 0）。
            if (m == mTrialStartMeasure && offset == 0 && voice == 0
                    && mTrialStartOffset > 0) {
                return;
            }

            // 合并成"最新一帧"再画（见 mCursorPending 的注释）：UI 忙时中间的
            // 位置直接丢掉，只保证最终跳到用户此刻弹到的地方，延迟不会越积越大。
            mCursorPending.set(new long[]{measure, offset, voice,
                    SystemClock.elapsedRealtime()});
            if (mCursorPosted.compareAndSet(false, true)) {
                runOnUiThread(this::flushCursor);
            }
        }

        /**
         * 把最新一次 onCursor 的位置画到谱面上。
         * 与原来的内联 lambda 等价，只是改成"取最新值"，不再逐帧排队。
         */
        private void flushCursor() {
            // 先清标志、再取最新值：这两步之间若来了新位置，会重新排一次任务，
            // 既不会丢最新位置，也不会重复入队。
            mCursorPosted.set(false);
            long[] c = mCursorPending.getAndSet(null);
            if (c == null || uiGone()) return;
            final int measure = (int) c[0], offset = (int) c[1], voice = (int) c[2];
            // 从"引擎报出这个位置"到"真的要下发 JS"之间的排队时间。
            // 低端机上 WebView 主线程忙时这里会很长，是光标追不上的直接证据。
            final long delay = SystemClock.elapsedRealtime() - c[3];
            mCursorFrames++;
            mCursorDelaySum += delay;
            if (delay > mCursorDelayMax) mCursorDelayMax = delay;
            if (mCursorFrames % 100 == 0) {
                Log.i(TAG, "光标帧延迟：已画 " + mCursorFrames + " 帧，排队 平均 "
                        + (mCursorDelaySum / mCursorFrames) + "ms / 最大 "
                        + mCursorDelayMax + "ms");
            }
            if (mTrialSettling) return;
            if (mTrialRunning) {
                // 演奏中只有光标位置（UFP 的实时状态不带逐音数据）→ moveCursor2。
                // mTrialNoteCursor=true 时"整小节淡金高亮 + 起点那颗音一根光柱"
                // 一起画：只有高亮的话看不出从哪颗音开始（--ez nc false 可关掉光柱）。
                // 视口跟随不在这里做：真实演奏时**不弹就没帧**，靠帧驱动等于没有；
                // 交给 mTrialFollow 定时任务（见 startTrialFollow）。
                mLastCurM = measure;
                mLastCurO = offset;
                mLastCurV = voice;
                // 光标离开过起点 = 真在推进进度（这些帧已过 onCursor 的空闲假位置守卫）。
                // 需求：全程一直落在第一个音上算"没演奏"，静默退出、不弹结果窗。
                if (measure != mTrialStartMeasure || offset != mTrialStartOffset
                        || voice != mTrialStartVoice) {
                    mTrialProgressed = true;
                }
                mSheet.moveCursor2(measure, offset, voice, mTrialNoteCursor, true, false);
            } else {
                mSheet.moveCursor(measure, offset, voice);
            }
            // 记下当前小节：演奏结束/中断后 ticker 不会回调，
            // onFinished 靠它把状态行刷回"光标停在哪一小节"。
            mCurMeasure = measure;
            // 只报小节，和试听态完全同一句话 —— 「演奏中」由顶栏按钮自己说。
            setMeasureStatus(measure);
        }

        @Override
        public void onFinished(String evaJson, String metricsJson,
                               String pitchResultJson, int lastScore, boolean userStopped) {
            // 完整评测 JSON 只在调试会话落盘（cache/eva.json），普通用户演奏不写。
            if (mDebugSession) {
                try {
                    java.io.File f = new java.io.File(getCacheDir(), "eva.json");
                    StringBuilder sb = new StringBuilder();
                    sb.append("EVA:").append(evaJson == null ? "null" : evaJson).append((char) 10);
                    sb.append("METRICS:").append(metricsJson == null ? "null" : metricsJson).append((char) 10);
                    sb.append("VEXFLOW:").append(pitchResultJson == null ? "null" : pitchResultJson);
                    try (java.io.FileWriter w = new java.io.FileWriter(f)) {
                        w.write(sb.toString());
                    }
                    Log.i(TAG, "评测 JSON 已存 " + f + " 长度="
                            + (evaJson == null ? -1 : evaJson.length()));
                } catch (Throwable t) {
                    Log.w(TAG, "存评测 JSON 失败 " + t);
                }
            }

            final String note = evalNote(evaJson);
            // 成绩取 rating 四项（音准/节奏/速度/总分），实时分 lastScore 只作兜底
            final EvalRating r = EvalRating.parse(evaJson);
            if (r != null) Log.i(TAG, "评测成绩 " + r);
            runOnUiThread(() -> {
                if (uiGone()) {
                    Log.w(TAG, "演奏结束时页面已销毁，跳过 UI 收尾");
                    return;
                }
                mTrialRunning = false;
                // ⚠ mUfpRunning 必须一起清：留着 true 的话，下一轮建会话期间
                // 点「停止」会被判成"会话已经在跑"→ 走按停分支（弹结算卡 +
                // 白跑一次 ufpFinish），而不是 cancelBuildingTrial 的取消。
                mUfpRunning = false;
                mTrialSettling = false;
                stopTrialFollow();
                mTrialBtn.setState("ic_sheet_trial.png", "演奏");
                // 结算期间按钮是灰的（见 toggleTrial 的停止分支），这里必须放开
                mTrialBtn.setUiEnabled(true);
                setTrialUi(false);
                // 退出演奏态（小节高亮 / 报文门控），光标始终是同一枚金色胶囊
                mSheet.setMagicMode(false);
                // 把演奏时的整小节高亮撤掉（否则白块会停在最后一小节上）
                mSheet.clearMeasureHighlight();
                // 需求：没收到任何钢琴声音，或收到了但光标一直落在
                // 第一个音上（没推进任何进度）→ 直接退出演奏状态，**不弹任何窗口**。
                // 这里是两条路径（按停 / 引擎自然结束 state=3）的共同收口，必须兜底：
                // 自然结束时 onCursor 的 state==3 会先 markSettling() 弹出「正在结算」卡，
                // 那时还没算出有没有推进，所以要在收尾时把它 dismiss 掉。
                // mTrialNoPlay 是按停瞬间算好的；自然结束那条路径没设，实时再算一次。
                final boolean noPlay = mTrialNoPlay || trialHadNoPlay();
                if (noPlay) {
                    if (mResultCard != null && mResultCard.dlg != null
                            && mResultCard.dlg.isShowing()) {
                        dismissResultCard();
                    }
                    setSettlingLock(false);   // 放开返回箭头 / 卡片 cancelable
                    Log.i(TAG, "没演奏（无声音或没推进进度）：直接退出，不弹结算/成绩卡");
                } else {
                    // 自然结束（不是自己按停）：和试听 onEnded 完全一致的口径
                    // —— 光标、播放头、进度条、状态行、视口一起回到整曲开头；
                    // 有选段则回段首。中途按停不回，留在原地方便接着练。
                    if (!userStopped) {
                        mSheet.skipRestoreCursor();
                    } else {
                        syncHeadToCursor();
                    }
                    // 逐音结果标记（对勾 = 弹对、蓝 = 漏弹、黄 = 弹错）：逐音对错
                    // 只存在于 ufpFinish 返回的 vexflow JSON（pitches_info）。
                    mSheet.markTrialResult(buildResultJson(evaJson, pitchResultJson));
                    if (!userStopped) {
                        final boolean sec = mSecOn && mSecStartM >= 0;
                        final int tm = sec ? mSecStartM : 0;
                        rewindToStart(tm, sec ? mSecStartV : 0, sec ? mSecStartO : 0, true);
                        Log.i(TAG, "演奏自然结束，光标回到" + (tm > 0 ? "段首 " : "曲首") + tm);
                    }
                    // 有正常推进 → 成绩卡，两按钮；主按钮文案按结束方式在
                    // showTrialResult 里切成「继续演奏」/「重新演奏」。
                    showTrialResult(trialTitle(userStopped),
                            trialScore(userStopped, note, lastScore, r),
                            r == null ? "" : r.subLine(),
                            trialHint(userStopped, note),
                            false, true, userStopped);
                }
                // 演奏结束后状态行回到光标所在的小节（演奏期间一直在报它）
                setMeasureStatus(mCurMeasure);
            });
            // 完整评测 JSON 已落盘（见上面 cache/eva.json），这里不刷屏
        }

        /**
         * 建会话失败 / 收音中断。
         *
         * reason 是给用户看的**一句短话**（演奏没能开始 / 这首曲子暂时开不了演奏 /
         * 演奏被打断…），不允许带括号、返回码、异常原文 —— 那些全部放 detail，
         * 只进 logcat。错误卡现在与 toast / 载入卡片同用 21sp 反色浮层，长句会挤歪。
         */
        @Override
        public void onError(String reason, String detail) {
            Log.w(TAG, "演奏出错：" + (detail == null ? reason : detail));
            runOnUiThread(() -> {
                if (uiGone()) {
                    Log.w(TAG, "演奏出错时页面已销毁，跳过 UI 收尾");
                    return;
                }
                mTrialRunning = false;
                mUfpRunning = false;   // 与 onFinished 口径一致，见那里的说明
                mTrialSettling = false;
                // 出错时 mTrialFollow 的下一跳会因为 !mTrialRunning 自行退出，
                // 但这一跳之前它可能正好把视口滚回旧光标位置（用户已经在看别处了）
                stopTrialFollow();
                mTrialBtn.setState("ic_sheet_trial.png", "演奏");
                mTrialBtn.setUiEnabled(true);
                setTrialUi(false);
                mSheet.setMagicMode(false);
                mSheet.clearMeasureHighlight();
                syncHeadToCursor();
                setMeasureStatus(mCurMeasure);
                // 用结果卡而不是 toast：错误需要用户确认一下才收得掉
                // 错误卡只要一个「知道了」——演奏没能正常起来，不该给主按钮/下一曲
                showTrialResult("演奏中断", "", "", reason, false, false, false);
            });
        }
    };

    /**
     * 结束时卡的标题，只留两句：
     *   自己按停 → 「演奏中止」，不评价弹得怎么样，但把当时的分数报出来
     *   弹到曲末 → 「演奏完成」
     * 原来还多一句「演奏结束」专给"没跟上"，和「完成」「中止」「中断」挤成四个
     * 近义词，而且它其实是在说没弹成，措辞反而更含糊，已删。
     */
    private static String trialTitle(boolean userStopped) {
        return userStopped ? "演奏中止" : "演奏完成";
    }

    /**
     * 分数行：优先用引擎评测的 rating.total（有区分度）；
     * 取不到评测（老数据/异常）才退回实时分 lastScore。
     * "没跟上"（引擎全程没进入跟随）不给数字 —— 什么都没弹，报分只会误导。
     */
    private static String trialScore(boolean userStopped, String note, int lastScore,
                                     EvalRating r) {
        if (!note.isEmpty()) return "";
        if (r != null) return r.total + " 分";
        return lastScore + " 分";
    }

    /**
     * 提示行：只在没拿到成绩时用，说明为什么没分数。
     * 弹完和按停都不给提示（用户要求去掉，卡片上只剩标题 + 分数 + 按钮）。
     */
    private static String trialHint(boolean userStopped, String note) {
        return note.isEmpty() ? "" : note;
    }

    /**
     * 从评测 JSON 判断"这次有没有成绩"。
     *
     * retcode != 0 表示引擎全程没进入正常跟随，errinfo 是引擎内部状态机的原话
     * （典型 "Still in the state of GENESIS 未进入正常跟随"），只有排查价值，
     * 只进 logcat。界面上统一给一句中性短说明；原来的「这次没听到你的弹奏」
     * 「这次没能跟上你的弹奏」是否定式评价，且 现在「没演奏」已静默
     * 退出、根本不弹卡，属于残留文案，一并收敛。
     */
    private static String evalNote(String evaJson) {
        if (evaJson == null) return "";
        try {
            org.json.JSONObject eva = new org.json.JSONObject(evaJson);
            int rc = eva.optInt("retcode", 0);
            if (rc != 0) {
                String err = eva.optString("errinfo", "");
                Log.i(TAG, "演奏未进入正常跟随 retcode=" + rc + " errinfo=" + err);
                return "这次没能完成评分";
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    // ---------------- 收藏（本地） ----------------

    /**
     * 收藏按钮：切本地收藏状态，已收藏的曲子会出现在曲库「我的收藏 · 乐曲」里。
     *
     * 记的是乐曲 uuid（曲谱包目录名），不是文件路径 —— 这样在乐曲列表里点心形
     * 收藏时，哪怕曲谱包还没铺到设备也一样有效（见 LibraryStore）。
     */
    private void toggleFav() {
        String uuid = LibraryStore.uuidOfPath(mEncPath);
        if (uuid == null) return;
        applyFavBtn(LibraryStore.get(this).toggleSheet(uuid));
    }

    /** 按本地档案刷新收藏按钮的显示（进页面/切换曲子时调）。 */
    private void refreshFavBtn() {
        String uuid = LibraryStore.uuidOfPath(mEncPath);
        if (uuid == null) return;
        applyFavBtn(LibraryStore.get(this).isFavSheet(uuid));
    }

    /** 收藏按钮的唯一写入点：图标与文字一起跟着状态走。 */
    private void applyFavBtn(boolean fav) {
        mFav.setState(fav ? "ic_sheet_preview_collected.png" : "ic_sheet_preview_collect.png",
                fav ? "已收藏" : "收藏");
    }

    /**
     * 「试听」按钮：原版是播放/停止二合一，播放中变成停止。
     * 从播放头当前位置起播 —— 没点过谱面就是曲首，点过就从点到的那个音符开始。
     */
    private void togglePlay() {
        if (mPreviewActive || AlgoEngine.get().isPlaying()) {
            // 引擎里被喊停的 Player 不再回调 onEnded，UI 在这里直接更新
            mPlayBtn.setState("ic_sheet_preview_play.png", "试听");
            setPlaybackUi(false);
            // 停止后 ticker 不再回调，状态行手动刷回当前小节（播放头留在原地）
            setMeasureStatus(mCurMeasure);
            AlgoEngine.get().stop();
            // 用户主动按停：补一次上次阅读现场的恢复（自然播完要回曲首，不补）
            restoreSavedState();
        } else {
            autoDismissRestore();
            mPlayBtn.setState("ic_sheet_preview_stop.png", "试听中");
            setPlaybackUi(true);
            // 起播：先跟一次（滚到光标那行），之后只在播放位置还可见时继续跟随
            mPlayPosVisible = true;
            mPreviewScrollPending = true;
            mLastUserActionMs = SystemClock.elapsedRealtime();
            mFollowGraceUntilMs = mLastUserActionMs + FOLLOW_GRACE_MS;
            // 红叉保留到试听里也不清（同一套"下次注意"的提醒，见 startTrial）
            try {
                if (mSecOn) {
                    // 有选段就从段内的光标处起（段外会被拉回段首，见 sectionHead）
                    int[] p = sectionHead();
                    AlgoEngine.get().seekTo(p[0], p[2], p[1], mProgress);
                } else {
                    AlgoEngine.get().start(mProgress);
                }
            } catch (Throwable t) {
                // 起播是异步排队，同步抛错（构造 Player 失败）时
                // onError 未必回调；必须把二态按钮与 mPreviewActive 一起回滚，
                // 否则一直显示「试听中」、演奏入口也一直灰。
                Log.e(TAG, "试听起播失败", t);
                mPlayBtn.setState("ic_sheet_preview_play.png", "试听");
                setPlaybackUi(false);
                setMeasureStatus(mCurMeasure);
                restoreSavedState();
            }
        }
    }

    /**
     * 点谱面定位到某个音符。
     *
     * measure/offset/voice 是 JS 从点击位置直接算出的 native 侧 0-based 索引
     * （onNoteClick2 通道，与原版 App 一致），offset/voice 一起透传给引擎，
     * 才能精确落在点击的那个音符而不是小节开头。
     *
     * 分两种情形：
     *   - **播放中**：就地切过去并接着播（这条路径会在百毫秒内生效）
     *   - **没在播**：只把播放头挪过去（移动光标/进度），不发声、不起一天的播放会话。
     *     之后再按「试听」，就从刚才定位的那个音符开始播。
     */
    private void seekToNote(int measure, int offset, int voice, boolean followScroll) {
        if (!mLoaded || measure < 0) return;
        // 选段中**允许**定位（拖进度条 / 划屏 / 到头到尾都走这里）：
        // 长曲子里用户经常要滚到远处看谱再选第二个点，把导航一并禁掉会很难受。
        // 点谱面这条路径不经过这里 —— onNoteClick 里 mSecPicking 已分流给
        // handleSectionTap，所以放开定位不会让"点音符改选边界"失效。
        // 选段生效时，段外**什么都不做**：不定位光标、不出声、不弹提示。
        // 段外本来就播不到，让光标跑过去只会停在"听不到的地方"，还得再点回来。
        // 先按小节挡一道（不依赖渲染器回调的集合），再按音符挡段尾小节里的段外音。
        if (inSectionGap(measure) || outsideSection(measure, offset, voice)) {
            Log.i(TAG, "选段生效中，段外点击忽略 " + measure + "/" + offset + "/" + voice);
            return;
        }
        if (mTrialRunning) {
            // 演奏中光标由弹奏驱动，点谱面不定位。静默忽略、不弹 toast
            // （用户删：演奏时注意力在弹奏上，提示一闪而过没人看
            // 且"光标跟着弹奏走"本就是演奏的自然状态，无需说明）；也不写状态行。
            return;
        }
        int total = AlgoEngine.get().getMeasureCount();
        if (total > 0) measure = Math.min(measure, total - 1);

        if (mPreviewActive || AlgoEngine.get().isPlaying()) {
            // 播放中（含起播排队中）就地切过去接着播：seekEpoch 会作废旧请求
            AlgoEngine.get().seekTo(measure, voice, offset, mProgress);
            // 跳转之后**必须**重新跟随并允许滚过去，否则光标会停在跳转前的位置不动
            mPlayPosVisible = true;
            mInvisibleHits = 0;
            mPreviewScrollPending = true;
            mLastUserActionMs = SystemClock.elapsedRealtime();
            mFollowGraceUntilMs = mLastUserActionMs + FOLLOW_GRACE_MS;
            // 引擎要等第一块 PCM 合成完才会回调进度（实测 0.5~1 秒），
            // 这段时间光标会滞留在旧位置 —— 干脆立刻摆到新位置，不等 ticker。
            mSheet.moveCursor(measure, offset, voice, true);
            return;
        }
        // 没在播：纯跳转，不发出声音。光标挪过去 + 状态行显示新小节就够了，
        // 不要再补一句"已定位到第 X 小节"——眼睛看得到的事不用文字复述。
        // ⚠ 必须走 placeCursorAt（参数顺序 measure, voice, offset）：它带"用户主动
        // 定位"的解锁逻辑（清曲首光标锁 / 取消判稳重画）。原来直接 updateProgress，
        // 曲首光标的锁和 mPlaceAtStartPending 都留着，8~22 秒后判稳回调还会再重画
        // 一次并且 followScroll=true —— 用户点完谱面，过一会儿视口又自己跳一下。
        // 取消跳转要回到发起前的位置：placeCursorAt 会覆盖 lastMeasure/lastVoice/lastOffset，
        // 必须在它之前记下来。
        final int backM = mSheet.lastMeasure();
        final int backV = mSheet.lastVoice();
        final int backO = mSheet.lastOffset();
        placeCursorAt(measure, voice, offset, followScroll);
        // 需要"把目标滚进画面"的跳转（拖进度条 / 到头到尾 / 横向划动）才等；
        // 点谱面是点在可见音符上（followScroll=false），目标必然已渲染，不该弹卡片。
        if (followScroll) beginJumpWait(measure, voice, offset, backM, backV, backO);
    }

    /** 手指在进度条上时先给个预览（只改文字，不动引擎）。 */
    private void previewProgress(int progress) {
        int m = measureOfProgress(progress);
        // 段外不可定位：拖动时状态行不跟着跑（松手也不会跳，见 seekToNote）
        if (inSectionGap(m)) return;
        setMeasureStatus(m);
    }

    /** 该小节是否在选段范围之外（挡住段外的定位/拖动）。 */
    private boolean inSectionGap(int measure) {
        return mSecOn && mSecStartM >= 0 && mSecEndM >= 0
                && (measure < mSecStartM || measure > mSecEndM);
    }

    /**
     * 起播点 = 当前播放头（也就是屏幕上那颗光标），但必须落在选段内。
     *
     * 选段只约束"能播到哪儿"，不约束"从哪儿开始"：段内点过的音符、演奏停在的位置、
     * 上次退出时的光标，都可以当起点 —— 长段练琴时每次都从段首重来没意义。
     * 段外（正常操作到不了，点谱面/拖进度条已被 {@link #inSectionGap} 挡掉）
     * 一律拉回最近的边界，免得一开跑就判"越过段尾"直接结束。
     *
     * @return {measure, offset, voice}，顺序与 {@link SheetView#moveCursor} 一致
     */
    private int[] sectionHead() {
        int m = AlgoEngine.get().headMeasure();
        int o = AlgoEngine.get().headOffset();
        int v = AlgoEngine.get().headVoice();
        if (mSecStartM >= 0 && m < mSecStartM) return new int[]{mSecStartM, mSecStartO, mSecStartV};
        if (mSecEndM >= 0 && m > mSecEndM) return new int[]{mSecEndM, mSecEndO, mSecEndV};
        return new int[]{m, o, v};
    }

    /**
     * 该位置是否落在选段之外。
     *
     * 直接查渲染器给的「段内音符集合」—— 它按真实时长算出，跨声部天然正确，
     * 不再需要拿 offset 去猜（offset 只是声部内序号，左右手各编各的，比不了）。
     * 集合还没到（刚选完、渲染器还没回）时一律放行，避免误挡。
     */
    private boolean outsideSection(int measure, int offset, int voice) {
        if (!mSecOn || mSecStartM < 0) return false;
        if (mSecKeys == null) return false;
        return !mSecKeys.contains(sectionKey(measure, offset, voice));
    }

    /** 段内音符集合的 key：measure|offset|voice（与渲染器上报的格式一致）。 */
    private static String sectionKey(int measure, int offset, int voice) {
        return measure + "|" + offset + "|" + voice;
    }

    /**
     * 进度条抬手（或点一下）——跳到对应小节。
     * 与点谱面同一套语义：播放中接着播，没播放就只挪播放头不发声。
     */
    private void jumpToProgress(int progress) {
        if (!mLoaded) return;
        // 选段中不拦：见 seekToNote 的说明（选段未生效时 mSecOn=false，
        // 下面的 inSectionGap 自然放行；选段已生效时仍然只允许段内定位）。
        // 演奏/结算期间进度条是灰的（updateTransportState），这里再兜一道：
        // 位置由弹奏驱动，拖了只会让滑块和光标对不上
        if (mTrialRunning || mTrialSettling) {
            restoreSeekToRealPosition();
            return;
        }
        int m = measureOfProgress(progress);
        if (inSectionGap(m)) {
            // 段外不可定位：滑块弹回真实进度（否则它会停在手指松开的地方，
            // 而光标和状态行都还在段内，两边对不上）
            restoreSeekToRealPosition();
            return;
        }
        seekToNote(m, 0, 0, true);
    }

    /** 小节 → 进度条位置（‰）：多小节时首小节 0、末小节 1000；单小节固定 0。
     *
     * 所有需要按小节换算的滑块位置都走它，**不要各写一套**：
     * 同一位置用两种公式会算出两个不同的滑块值。
     */
    private int progressOfMeasure(int measure) {
        int mc = AlgoEngine.get().getMeasureCount();
        if (mc <= 0) return 0;
        int m = Math.max(0, Math.min(measure, mc - 1));
        return measureToProgress(m);
    }

    /**
     * 把进度条和状态行刷回**播放头真正所在的位置**。
     *
     * 两个调用场景：① 选段生效/演奏中拖到段外被拒；② 横向划动被载入卡片中途
     * 接管（手势的 UP/CANCEL 进不来，见 dispatchTouchEvent）。
     *
     * 状态行一起刷：只回弹滑块会出现"小球在曲首、右边写着第 200 小节"。
     */
    private void restoreSeekToRealPosition() {
        int hm = AlgoEngine.get().headMeasure();
        if (hm < 0 || AlgoEngine.get().getMeasureCount() <= 0) return;
        mSeek.setProgress(progressOfMeasure(hm));
        setMeasureStatus(hm);
    }

    /**
     * 小节 → 进度条位置（‰）：多小节时首小节 0、末小节 1000；单小节固定 0。
     *
     * 以 (mc-1) 为分母，让最后一小节正好落在最右端；{@link #measureOfProgress}
     * 用同一组端点反算，只差整数取整。
     */
    private static int measureToProgress(int measure) {
        int mc = AlgoEngine.get().getMeasureCount();
        if (mc <= 1) return 0;
        return Math.max(0, Math.min(1000, Math.round(measure * 1000f / (mc - 1))));
    }

    /**
     * 进度条位置 -> 小节号：多小节时 0‰ 是第 1 小节、1000‰ 是最后一小节；
     * 单小节固定 0。
     *
     * 与 {@link #measureToProgress} 共用同一组端点，中间取最近的小节。小节数从
     * XML 直接数，不需要整曲合成；进度条粒度是"小节"而不是"采样"。
     */
    private static int measureOfProgress(int progress) {
        int mc = AlgoEngine.get().getMeasureCount();
        if (mc <= 1) return 0;
        // 端点映射：第 m 小节在 m*1000/(mc-1)，满进度就是 mc-1（最后一小节）。
        // 取最近的小节，保证拖到最右/滑块回到该位置时都落在同一小节。
        int p = Math.max(0, Math.min(1000, progress));
        int m = Math.round((mc - 1) * p / 1000f);
        return Math.max(0, Math.min(mc - 1, m));
    }

    /**
     * 刷新进度条、状态行与谱面光标。
     * 播放中由 ticker 每 60ms 调；点谱面静默跳转时调一次。
     *
     * @param followScroll 允许视口跟到目标小节。
     *                     点音符定位时传 false —— 渲染器 moveCursor2 里的
     *                     scrollToMeasure 用的是 align:"top"，会把该小节的顶边
     *                     **顶到视口顶部**；第一行上方本来有 score_padding_top
     *                     (40 渲染单位 × zoom) 的留白，一滚就整页上跳
     *                     （模拟器实测 40×0.652≈26 CSS = 58 设备px）。
     *                     用户点的音一定已经在视口里，没有滚的理由。
     *                     回曲首/回段首/拖进度条这类"必须让它出现在画面里"
     *                     的场景传 true。
     */
    private void updateProgress(int measure, int offset, int voice, boolean followScroll) {
        // 跳转（进度条/点谱面）的瞬间，引擎会先报一个 (-1,-1,-1) 的"位置未知"。
        // 拿它去刷状态行 → 小节数闪回第 1 小节；拿它去挪光标 → 光标被定住。
        // 实测：PROG m=-1 o=-1 v=-1 与真实位置交替出现，就是用户看到的反复跳。
        if (measure < 0) return;
        // 页面已经走掉（引擎回调比 Activity 活得久）：别再摸控件和谱面
        if (uiGone()) return;
        // 演奏中：进度/状态行/谱面光标一律归 onCursor 管。
        // 否则预览的 ticker（试听播放会留下一个还在跑的 Player）
        // 会跟演奏抢 UI —— 表现就是谱面光标在走、底部状态行却停在别的小节上。
        if (mTrialRunning) return;
        mCurMeasure = measure;
        if (!mScrubbing) {
            // 手指按在进度条上时别让 ticker 把进度条和状态行抢回去
            mSeek.setProgress(measureToProgress(measure));
            setMeasureStatus(measure);
        }
        // 谱面跟随：默认把光标挪到当前播放位置；只有起播那一次（以及用户
        // 停手 3 秒后重新拉回时）允许滚动，其余时候只挪光标、不动视口。
        if (!AlgoEngine.get().isPlaying()) {
            // 没在播（点谱面/拖进度条定位）：只挪光标，不滚视口 —— 见 followScroll 说明。
            // ⚠ 长曲逐行渲染时这一发会在 WebView 队列里排 2~3 秒（实测 2.7s），
            // 试过在 onRowsDrawn 批次间隙补发，实测同样排队（回调发出时 JS 并没真让出），
            // 只会多一条重复报文，已撤回。要根治得让渲染器绘制循环主动让出主线程。
            mSheet.moveCursor(measure, offset, voice, followScroll);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - mLastVisibleQueryMs >= VISIBLE_QUERY_MS) {
            mLastVisibleQueryMs = now;
            final int qm = measure, qo = offset, qv = voice;
            mSheet.queryMeasureVisible(qm, qv, qo, vis -> {
                if (vis) {
                    mInvisibleHits = 0;
                    mPlayPosVisible = true;
                    return;
                }
                if (SystemClock.elapsedRealtime() < mFollowGraceUntilMs) {
                    mInvisibleHits = 0;   // 正在滚过去的路上，不算"看不见"
                    return;
                }
                if (++mInvisibleHits >= INVISIBLE_HITS_PAUSE) mPlayPosVisible = false;
            });
        }
        if (mPlayPosVisible) {
            boolean scroll = mPreviewScrollPending;
            mPreviewScrollPending = false;
            mSheet.moveCursor(measure, offset, voice, scroll);
        } else if (now - mLastUserActionMs >= IDLE_RESUME_MS) {
            // 用户滚走之后连续 3 秒没再动 → 拉回播放位置，继续跟随
            mPreviewScrollPending = true;
            mSheet.moveCursor(measure, offset, voice, true);
            mPlayPosVisible = true;
        } else {
            // 位置暂时在视口外：**光标照常更新，只是不滚屏**。
            // 以前这里什么都不做，要等 3 秒才会再动一次 —— 表现就是
            // "光标卡在一个音符上，过一会儿一下子跳过好几个音"。
            mSheet.moveCursor(measure, offset, voice, false);
        }
    }

    /** 底部状态行（只写小节）的底层 setter。 */
    /** 上次写进状态行的文本：相同就不重复 setText（演奏中每 46ms 会调一次） */
    private String mStatusText = "";

    /**
     * 演奏中**最新**待画的光标位置（measure/offset/voice）。
     * 低端机上 UI 线程 / WebView 跟不上 46ms 一次的回调时，原来每个回调都往 UI
     * 队列塞一个 runOnUiThread，队列越排越长 → 光标落后实际弹奏越来越多。
     * 这里只保留最新一帧，UI 忙完取一次就跳到当前位置。
     */
    private final AtomicReference<long[]> mCursorPending = new AtomicReference<>();
    /** 光标帧延迟统计（产生 → 真正画到谱面上），每 100 帧打一行 */
    private long mCursorFrames, mCursorDelaySum, mCursorDelayMax;
    /** 已经排过一次 UI 任务？保证合并期间只排一次，不重复入队 */
    private final AtomicBoolean mCursorPosted = new AtomicBoolean();

    private void setStatus(String s) {
        if (s == null) s = "";
        if (s.equals(mStatusText)) return;
        mStatusText = s;
        if (mStatus != null) mStatus.setText(s);
    }

    /**
     * 底部状态行的**唯一**内容：「第 x / N 小节」。
     * 写成「第 2 / 64 小节」而不是「第 2 小节　/　共 64 小节」：后者把"小节"
     * 这个词重复了两遍，读起来绕，铺在底部又占宽。
     *
     * 状态行只放小节号，不承载时长、跳转提示、手型切换、演奏准备/得分/评语
     * 或错误文案。演奏准备期间不写额外提示（按钮已经变成「演奏中」）；得分/评语
     * 和中断原因走 {@link #showTrialResult}，打不开/播放中断/拦截走 {@link #toast}，
     * 冷启动解包走 {@link #buildLoading} 居中加载卡片。
     * 演奏态也不加「演奏中」前缀：顶栏那个按钮的文字已经切成了「演奏中」，
     * 状态行再来一遍是重复信息。
     */
    private void setMeasureStatus(int measure) {
        int count = AlgoEngine.get().getMeasureCount();
        if (count <= 0) {
            setStatus("");
            return;
        }
        int m = Math.max(0, Math.min(measure, count - 1));
        // 显示的是**乐谱上印的小节号**，不是物理索引 +1：谱里可能有弱起小节
        // (`number="0"`) 和复奏小节 (`number="X1"`) 这种不占编号的，物理 386 个
        // 但谱面只印到 384（保卫黄河）。见 AlgoEngine.buildMeasureNumbers。
        int shown = AlgoEngine.get().measureNumber(m);
        if (mMeasureThumb != null && mMeasureThumb.setNumber(shown)) {
            // 硬件加速下 invalidateSelf() 只标脏 drawable，SeekBar 不一定重画
            // thumb；显式刷一次整条进度条，数字才跟得上滑块位置。
            if (mSeek != null) mSeek.invalidate();
        }
        // 分母同样用"谱面印的最大号"，否则会出现 384/386 这种对不上的组合
        setStatus("/ " + AlgoEngine.get().getMeasureNumberTotal());
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------------- 启动流程 ----------------

    private void bootstrap() {
        final long t0 = SystemClock.elapsedRealtime();
        // 加载态走屏幕正中的遮罩卡片（解包 → 初始化引擎 → 解密曲谱），
        // 底部状态行始终只报小节，冷启动期间那一句就是空的
        showLoading(true);
        new Thread(() -> {
            try {
                AppAssets.install(this);

                String algoDir = new File(getFilesDir(), "algo").getAbsolutePath();
                AlgoEngine.get().init(algoDir);

                AlgoEngine.get().openSheet(mEncPath);
                // 小节数这时已经数出来了（XML 直接数，不需要合成）：把载入框从
                // 空文案换成「正在准备第 1/386 小节…」，后面每画完一批行
                // 由 onRowsDrawn 把当前值往上推。
                mUi.post(this::updateWaitText);

                // 分手可行性：直接数曲谱里左右手各有多少个音。
                // 只看谱面结构（staff/voice），不猜：右手 = <staff>1</staff>
                // （没写 staff 时 voice<4），左手 = <staff>2</staff>（voice>=4）。
                // 有一边一个音都没有，这首就属于"不可分合/左/右手"。
                //
                // ⚠ 只扫一遍：这首 XML 大曲子能到 1~2MB，而 handNoteCounts 是
                // 纯字符串扫描。原来 canSplitHands() 和 handNoteStats() 各调一次，
                // 等于在首屏上屏的临界路径上把整份谱扫两遍（还各拿一次 getSheetXml）。
                final String xml = AlgoEngine.get().getSheetXml();
                final int[] hands = handNoteCounts(xml);
                final boolean canSplit = hands[0] > 0 && hands[1] > 0;
                Log.i(TAG, "分手可行=" + canSplit
                        + " 右手音=" + hands[0] + " 左手音=" + hands[1]);

                // 存档以乐曲 uuid 为键。
                final String sheetUuid = LibraryStore.uuidOfPath(mEncPath);
                LibraryStore store = LibraryStore.get(this);

                // 曲名只有一个来源：**目录（catalog）里的 name**。
                // 这不是偏好问题——拆开原版 APK 查过：dex 里 work-title / credit-words /
                // movement-title 出现 0 次，连"未命名"这种兜底串都没有；渲染器 JS 里也是 0 次。
                // 即原版从不解析 XML 标题，标题栏的曲名完全来自服务端数据。
                // 入参没给就按 uuid 反查目录；查不到（目录里没有这首、或谱不在
                // sheets/<uuid>/ 这种标准位置）就显示"未命名"，不去猜 XML。
                String given = getIntent().getStringExtra(EXTRA_TITLE);
                if (given == null || given.isEmpty()) {
                    if (sheetUuid != null) {
                        SheetCatalog.Sheet sh = SheetCatalog.sheetByUuid(this, sheetUuid);
                        if (sh != null && sh.name != null && !sh.name.isEmpty()) {
                            given = sh.name;
                        }
                    }
                }
                final String title = (given != null && !given.isEmpty()) ? given : "未命名";
                // 打开即记一次「最近打开」（本地时间戳），曲库页按它倒序排
                if (sheetUuid != null) store.touch(sheetUuid);

                // 上次退出时的光标位置：首屏画完后自动定位过去（见 restoreSavedCursor）。
                // demo/演示入口（startm / autotrial / selffeed）不恢复，避免盖掉调试指定的位置。
                int[] cur = null;
                int[] sec = null;
                if (sheetUuid != null
                        && getIntent().getIntExtra("startm", -1) < 0
                        && !getIntent().getBooleanExtra("autotrial", false)
                        && !getIntent().getBooleanExtra("selffeed", false)
                        && !getIntent().getBooleanExtra("nocursor", false)) {   // 调试：等价于清进度
                    cur = store.cursor(sheetUuid);
                    // 只有**落在曲首**（第 1 小节第 1 个音）的记录不恢复 —— 那个位置
                    // 等价于"没有进度"，问"要不要载入"没有意义。第 1 小节里往后挪
                    // 到第 2 个音、以及第 2 小节起，一律照常恢复（用户要求
                    // "光标只要不在最开始，都记进度"）。
                    if (cur != null && cur[0] == 0 && cur[1] == 0) {
                        Log.i(TAG, "旧光标停在曲首(0/0)，改为从头开始");
                        cur = null;
                    }
                    sec = store.section(sheetUuid);
                }
                final int[] resumeCursor = cur;
                final int[] resumeSection = sec;
                // AlgoEngine 是单例，openSheet 不复位播放头。没得恢复就归零，
                // 否则上一首停在哪儿，这一首点「试听/演奏」就从哪儿开始。
                if (resumeCursor == null) AlgoEngine.get().movePlayheadTo(0, 0, 0);

                // 谱面立刻上屏；播放会话自己会重建模块，不依赖整曲时长预计算。
                runOnUiThread(() -> {
                    // 加载期间用户已经返回：谱面不用再画了（Activity 已 finish，
                    // 继续往 WebView 灌 100 万字符只是白耗 CPU）
                    if (uiGone()) return;
                    mResumeCursor = resumeCursor;
                    mResumeSection = resumeSection;
                    mResumeScanned = true;
                    // 渲染可能已经完成（小曲子 1~2 秒就画完，而存档还在后台读）：
                    // 那时 onSheetRendered 里的那次 restoreSavedState 被门控挡住了，
                    // 这里必须补调一次，否则询问浮层永远不会出现。
                    if (mRendered) {
                        Log.i(TAG, "存档已读出（渲染已完成），补一次阅读现场恢复");
                        restoreSavedState();
                    }
                    // 调试：--ei secsm M --ei secem N 直接造一个选段（验证选段持久化用）
                    final int dbgSm = getIntent().getIntExtra("secsm", -1);
                    final int dbgEm = getIntent().getIntExtra("secem", -1);
                    if (dbgSm >= 0 && dbgEm > dbgSm) {
                        mUi.postDelayed(() -> {
                            if (uiGone()) return;
                            mSecPicking = true;
                            mSecStartM = -1;
                            mSecEndM = -1;
                            // secso / seceo：让调试选段的端点落在小节中间
                            final int dbgSo = getIntent().getIntExtra("secso", 0);
                            final int dbgEo = getIntent().getIntExtra("seceo", 0);
                            handleSectionTap(dbgSm, dbgSo, 0);
                            handleSectionTap(dbgEm, dbgEo, 0);
                        }, 1800);
                    }
                    mSheet.show(xml, title);
                    mTitle.setText(title);
                    applyTempoChip();
                    refreshFavBtn();
                    mLoaded = true;
                    // 曲目信息就绪后才能判断"本曲集里有没有上一首/下一首"
                    updateTransportState();
                    // 遮罩不在这里撤 —— 谱面还没画出来，撤掉只会露出白屏。
                    // 真正撤掉是在 onSheetRendered（见那里）+ 下面的兜底定时器。
                    // 谱面一上屏，状态行就切到「第 1 小节 / 共 N 小节」。
                    setMeasureStatus(0);
                    // 每个新页面都从合手开始。AlgoEngine 是跨页面单例，必须显式同步；
                    // 否则上一首留下的单手音量会配着本页默认的「合手」按钮起播。
                    mHandCanSplit = canSplit;
                    mHandType = 0;
                    mHandBtn.setState("ic_hand_both.png", "合手");
                    AlgoEngine.get().setHandType(0);
                    mSheet.setHandType("both");
                    if (!canSplit) {
                        // 只能合手的曲子置灰，免得谱面灰掉一半、演奏却毫无区别
                        mHandBtn.setUiEnabled(false);
                    }
                    // 调试：--ei hand N 直接指定手型（0 合手 / 1 右手 / 2 左手），
                    // 用来核对手型有没有真的作用到判定/试听上
                    if (getIntent().hasExtra("hand") && mHandCanSplit) {
                        applyHandType(getIntent().getIntExtra("hand", 0));
                    }
                    // —— 演示入口（adb 调试用，日常使用不会触发）——
                    //   am start -n com.magicpig.piano/.PlayerActivity --es enc <谱路径>
                    //     --ez selffeed true --ez autotrial true
                    //     --ei startm M --ei startv V --ei starto O
                    //   selffeed：把本机合成的 PCM 当麦克风输入喂给引擎（不用真人弹）
                    //   startm/v/o：先定位到这颗音（演示"从任意音符开始"）
                    //   autotrial：谱面上屏后自动点「演奏」
                    final int demoM = getIntent().getIntExtra("startm", -1);
                    if (demoM >= 0) {
                        seekToNote(demoM, getIntent().getIntExtra("starto", 0),
                                getIntent().getIntExtra("startv", 0), true);
                    }
                    if (getIntent().getBooleanExtra("autotrial", false)) {
                        int delay = getIntent().getIntExtra("trialdelay", 2500);
                        mUi.postDelayed(PlayerActivity.this::toggleTrial, delay);
                        // 调试用：开始演奏 N 毫秒后自动停止（验证"停止演奏不刷新"）
                        int autostop = getIntent().getIntExtra("autostop", 0);
                        if (autostop > 0) {
                            mUi.postDelayed(PlayerActivity.this::toggleTrial, delay + autostop);
                        }
                    }
                });
                Log.i(TAG, "启动耗时：谱面上屏 " + (SystemClock.elapsedRealtime() - t0) + "ms");

                // 兜底：渲染回调万一没来（谱面异常），10 秒后必须把遮罩撤掉、
                // 按钮放开，否则用户就一直卡在"正在载入曲谱"上。
                mUi.postDelayed(() -> {
                    if (mRendered) return;
                    Log.w(TAG, "渲染回调超时，强制撤掉加载遮罩");
                    showLoading(false);
                    openEntryButtons();
                    // 曲首光标**不在这里落笔**：大谱面 10 秒时往往还在画，这时画上去
                    // 会被擦掉（就是"闪一下"）。它有自己的兜底（mForcePlaceRun，22s）。
                }, 10000);
            } catch (Throwable t) {
                Log.e(TAG, "启动失败", t);
                // 异常原文只进 logcat；界面上一句人话（toast），状态行保持干净
                runOnUiThread(() -> {
                    showLoading(false);
                    toast("乐曲打开异常");
                });
            }
        }, "bootstrap").start();
    }

    /**
     * 数谱面里两只手各有多少个音。
     * 只看结构、不猜：右手 = &lt;staff&gt;1&lt;/staff&gt;（没写 staff 时 voice&lt;4），
     * 左手 = &lt;staff&gt;2&lt;/staff&gt;（voice&gt;=4）。和弦副音与休止都不计数。
     */
    private static int[] handNoteCounts(String xml) {
        int right = 0;
        int left = 0;
        if (xml == null) return new int[]{0, 0};
        int p = 0;
        while (true) {
            int a = xml.indexOf("<note", p);
            if (a < 0) break;
            int b = xml.indexOf("</note>", a);
            if (b < 0) break;
            String note = xml.substring(a, b);
            p = b + "</note>".length();
            if (note.contains("<chord/>") || note.contains("<chord />")) continue;
            if (!note.contains("<pitch>")) continue;
            int staff = tagInt(note, "staff", -1);
            int voice = tagInt(note, "voice", -1);
            if (staff == 1 || (staff < 0 && voice >= 0 && voice < 4)) {
                right++;
            } else if (staff == 2 || (staff < 0 && voice >= 4)) {
                left++;
            }
        }
        return new int[]{right, left};
    }

    /** 取 &lt;tag&gt;123&lt;/tag&gt; 里的数字，取不到返回 def。 */
    private static int tagInt(String note, String tag, int def) {
        int a = note.indexOf("<" + tag + ">");
        if (a < 0) return def;
        int b = note.indexOf("</" + tag + ">", a);
        if (b < 0) return def;
        String v = note.substring(a + tag.length() + 2, b).trim();
        try {
            return Integer.parseInt(v);
        } catch (Exception e) {
            return def;
        }
    }

    private void toast(String msg) {
        // 系统 Toast 是**排队**的：上一条还没消失时新的一条要等，
        // 结果就是"动作做完了，提示才慢半拍出来"。这边先 cancel 再 show。
        Ui.toast(PlayerActivity.this, msg);
    }

    // ---------------- 底部进度条：首/尾跳转 + 曲集内上一首/下一首 ----------------

    /**
     * 跳到曲首 / 曲末（有选段时跳到段首 / 段尾）。
     *
     * 和拖进度条同一套语义：试听中接着播，没播就只挪播放头。
     */
    private void jumpToEdge(boolean toStart) {
        // 选段中同样允许（选段未生效时 mSecOn=false，target 就落到曲首/曲末）
        if (!mLoaded || mTrialRunning || mTrialSettling) return;
        int mc = AlgoEngine.get().getMeasureCount();
        int target = 0, off = 0, voi = 0;
        if (toStart) {
            if (mSecOn && mSecStartM >= 0) {
                target = mSecStartM;
                off = mSecStartO;
                voi = mSecStartV;
            }
        } else {
            target = mc > 0 ? mc - 1 : 0;
            if (mSecOn && mSecEndM >= 0) {
                target = Math.min(mSecEndM, target);
                off = mSecEndO;
                voi = mSecEndV;
            }
        }
        seekToNote(target, off, voi, true);
        // 没在播时不会走 ticker，滑块自己摆到两端（否则按了没视觉反馈）。
        // 顺手再压一次数字：球上的小节号必须和滑块位置同时到位
        // （有选段时 target 已经是段首 / 段尾，见上面的 clamp）。
        if (!mPreviewActive && !AlgoEngine.get().isPlaying()) {
            // 有选段时起点/终点不是曲首/曲末，滑块必须按真实位置摆：
            // 钉在最左/最右却和球上的小节号矛盾。多小节统一走 progressOfMeasure；
            // 单小节没有可区分的中间位置，直接贴两端。
            mSeek.setProgress(mc > 1 ? progressOfMeasure(target)
                    : (toStart ? 0 : 1000));
            setMeasureStatus(target);
        }
    }

    /**
     * 本曲集里的上一首 / 下一首（{@code delta} = -1 / +1）。
     *
     * 语义上等于「退出当前乐谱再进另一个」，所以必须自己先收尾：新页面的
     * onCreate 早于本页的 onStop，等 onStop 去停引擎就晚了 —— 两个页面共用
     * AlgoEngine / UfpTrial 单例，交错执行会把新页面的会话/播放踩掉。
     * 这里先存光标、丢会话、停试听，再 finish 掉自己。
     */
    private void openNeighbor(int delta) {
        if (!mLoaded || mSwitching || mTrialRunning || mTrialSettling) return;
        String uuid = LibraryStore.uuidOfPath(mEncPath);
        if (uuid == null) return;
        SheetCatalog.Album alb = SheetCatalog.albumOfSheet(this, uuid);
        if (alb == null || alb.sheets == null) return;
        int idx = -1;
        for (int i = 0; i < alb.sheets.size(); i++) {
            if (uuid.equals(alb.sheets.get(i).uuid)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) return;
        int ni = idx + delta;
        if (ni < 0 || ni >= alb.sheets.size()) return;
        final SheetCatalog.Sheet nb = alb.sheets.get(ni);
        final String enc = SheetFetcher.localEnc(this, nb.uuid);
        if (enc == null) {
            toast("乐曲不存在");
            return;
        }
        final String title = nb.displayName();
        Log.i(TAG, "切换到本曲集第 " + (ni + 1) + "/" + alb.sheets.size() + " 首：" + title);
        mSwitching = true;
        // 顺序：存光标 → 丢演奏会话 → 停试听。反过来的话停止回调会把光标改掉。
        saveCursorPosition();
        if (mTrialRunning || UfpTrial.isRunning()) UfpTrial.abandon(mTrialListener);
        mTrialRunning = false;
        mUfpRunning = false;
        mTrialSettling = false;
        mBackConfirmAt = 0;
        if (mPreviewActive || AlgoEngine.get().isPlaying()) AlgoEngine.get().stop();
        mPreviewActive = false;
        // 退一帧再切：让上面的 stop/abandon 先入引擎队列（它是串行的），
        // 新页面的 openSheet 一定排在它们后面执行。
        mUi.postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            startActivity(new Intent(PlayerActivity.this, PlayerActivity.class)
                    .putExtra(EXTRA_ENC, enc).putExtra(EXTRA_TITLE, title));
            finish();
            overridePendingTransition(0, 0);
        }, 80);
    }

    /**
     * 当前曲目在本曲集里的下标与总数，写进 out[0] / out[1]（查不到 -1 / 0）。
     *
     * 一次 `albumOfSheet` 同时给出两个值：`updateTransportState()` 被
     * setTrialUi / setPlaybackUi / markSettling / setMeasureStatus 等高频路径调用，
     * 原先"取下标查一次、取总数又查一次"，等于把 776 个曲集的索引扫两遍。
     */
    private void albumPosition(int[] out) {
        out[0] = -1;
        out[1] = 0;
        String uuid = LibraryStore.uuidOfPath(mEncPath);
        if (uuid == null) return;
        SheetCatalog.Album alb = SheetCatalog.albumOfSheet(this, uuid);
        if (alb == null || alb.sheets == null) return;
        out[1] = alb.sheets.size();
        for (int i = 0; i < out[1]; i++) {
            if (uuid.equals(alb.sheets.get(i).uuid)) {
                out[0] = i;
                return;
            }
        }
    }

    /**
     * 本曲集里还有没有"下一首"。
     *
     * 与 {@link #updateTransportState()} 里 ▶▶ 的启用条件**同一条判据**
     * （末首、或查不到曲集 / 找不到自己，都算没有下一首），成绩卡的「下一曲」
     * 按钮直接用它，两边状态才不会分歧。
     */
    private boolean hasNextSheet() {
        int[] pos = new int[2];
        albumPosition(pos);
        return pos[0] >= 0 && pos[1] > 0 && pos[0] < pos[1] - 1;
    }

    /**
     * 刷新底部四个跳转钮与进度条的可用性。
     *
     * 演奏/结算期间**整排都置灰**：演奏的位置由弹奏驱动，拖进度条只会让
     * 滑块和光标对不上（以前能拖，滑块还会停在手指松开的地方）。
     */
    private void updateTransportState() {
        // mLoadingBusy：两个 loading（冷启动 / 恢复进度）期间进度条与跳转钮一律灰。
        // 之前 bootstrap() 在 mLoaded=true 后单独调一次本方法，把进度条放开了，
        // 可遮罩要等首屏回调才撤 —— 那一小段窗口看着能拖、拖了又被后面的恢复
        // 状态覆盖。遮罩撤掉时 showLoading(false) 会再调一次。
        // 上一首/下一首搬到顶栏，但置灰口径不变（同一个 busy + 曲集边界）。
        boolean busy = mTrialRunning || mTrialSettling || mSwitching
                || mRestoring || mLoadingBusy;
        Log.i(TAG, "底部状态 busy=" + busy + "（playing=" + mTrialRunning
                + " restoring=" + mRestoring + " loading=" + mLoadingBusy + "）");
        Ui.setDisabled(mSeek, busy);
        // 「/ N」与滑块是同一条状态行，一起灰，否则整排灰了它还亮着
        Ui.setDisabled(mStatus, busy);
        int[] pos = new int[2];
        albumPosition(pos);
        final int idx = pos[0], total = pos[1];
        Ui.setDisabled(mPrevSheetBtn, busy || idx <= 0);
        Ui.setDisabled(mNextSheetBtn, busy || idx < 0 || idx >= total - 1);
        Ui.setDisabled(mToStartBtn, busy);
        Ui.setDisabled(mToEndBtn, busy);
    }

    /**
     * 谱面区左右划动 = 拖动进度条。
     *
     * 挂在 Activity 的 dispatchTouchEvent 上**只观察、不消费**（照常交给
     * WebView），所以点音符、上下滚谱全部不受影响。
     *
     * 行为对齐手机视频软件：**按下就开始拖**，横向位移实时换算成进度
     * （划满约 85% 屏宽 = 整曲），状态行跟着报小节，松手才真正落跳。
     * 以前是"一次划动 = 前进/后退 5%"，手感完全不像拖动。
     */
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        // 两个 loading 期间都允许点返回箭头：全屏拦截层会盖住它，所以在这里
        // 按坐标放行并直接退出（事件不再往下分发，拦截层收不到）。
        if (mLoadingBusy
                && ev.getActionMasked() == android.view.MotionEvent.ACTION_DOWN
                && hitBackArrow(ev)) {
            leavePage();
            return true;
        }
        // 两个 loading 期间都不处理横向划动：全屏拦截层挡得住 View，但
        // trackSwipeSeek 挂在 Activity 层、照样能收到 MotionEvent，会偷偷改进度。
        // ⚠ 但"跳到一半卡片弹出来"是常态（拖到远处 → 500ms 后亮载入卡），
        // 那一刻后续 MOVE/UP 就都进不来：只跳过的话 mScrubbing 会永久为 true
        // （ticker 再也刷不了滑块），滑块还停在手指最后的位置。所以这里按
        // "手势被取消"收尾，把滑块与状态行退回播放头。
        if (!mRestoring && !mLoadingBusy) {
            trackSwipeSeek(ev);
        } else if (mSwipeScrub) {
            // 只看 mSwipeScrub：SeekBar 自己的拖动有 onStopTrackingTouch/取消回调，
            // 由它收尾（这里再插手会把用户正在拖的滑块抢回去）。
            Log.i(TAG, "横向划动被载入中接管，滑块退回播放头位置");
            abortSwipeSeek();
        }
        return super.dispatchTouchEvent(ev);
    }

    /**
     * 横向划动没走完就被打断（载入卡片/恢复锁接管、系统取消手势）：
     * 清掉 mScrubbing 压制，并把滑块与状态行退回播放头当前所在位置。
     *
     * 这里**不是**"撤销上一次跳转"：跳转一旦发起，播放头就已经移过去了
     * （拖到远处 → beginJumpWait 期间卡片弹出），退回的目标就是那个新位置。
     */
    private void abortSwipeSeek() {
        mSwipeScrub = false;
        mSwipeOk = false;
        mScrubbing = false;
        if (mSeek != null) restoreSeekToRealPosition();
    }

    private boolean hitBackArrow(android.view.MotionEvent ev) {
        if (mBackBtn == null) return false;
        int[] loc = new int[2];
        mBackBtn.getLocationOnScreen(loc);
        int pad = dp(16);   // 图标小，给手指一点容差
        float x = ev.getRawX(), y = ev.getRawY();
        return x >= loc[0] - pad && x <= loc[0] + mBackBtn.getWidth() + pad
                && y >= loc[1] - pad && y <= loc[1] + mBackBtn.getHeight() + pad;
    }

    private float mSwipeX, mSwipeY, mSwipeStartProgress;
    private boolean mSwipeOk, mSwipeScrub;

    private void trackSwipeSeek(android.view.MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                mSwipeX = ev.getX();
                mSwipeY = ev.getY();
                mSwipeStartProgress = mSeek == null ? 0 : mSeek.getProgress();
                mSwipeScrub = false;
                // 底部那条（进度条 + 四个钮）自己处理手势，别重复响应
                mSwipeOk = mBottomBar == null || ev.getY() < mBottomBar.getTop() - dp(6);
                break;
            case android.view.MotionEvent.ACTION_MOVE: {
                if (!mSwipeOk || !mLoaded || mTrialRunning || mTrialSettling) break;
                float dx = ev.getX() - mSwipeX;
                float dy = ev.getY() - mSwipeY;
                if (!mSwipeScrub) {
                    if (Math.abs(dx) < dp(24)) break;
                    if (Math.abs(dy) > Math.abs(dx) * 0.8f) {
                        mSwipeOk = false;      // 纵向滚动，整个手势都不再当划动
                        break;
                    }
                    mSwipeScrub = true;
                    mScrubbing = true;         // 压住 ticker，别把滑块抢回去
                    // 用户主动横向拖 = 不要上次的进度：按「不用」收掉浮层（只删档、
                    // 不摆光标），后续以他拖到的位置为准。
                    // 放在"确认是横向"之后，纵向滚谱不受影响。
                    autoDismissRestore();
                }
                int w = Math.max(1, mRoot.getWidth());
                float span = Math.max(1f, w * 0.85f);
                int p = Math.round(mSwipeStartProgress + dx / span * 1000f);
                p = Math.max(0, Math.min(1000, p));
                mSeek.setProgress(p);
                previewProgress(p);            // 状态行实时报小节
                break;
            }
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL: {
                boolean scrub = mSwipeScrub;
                boolean ok = mSwipeOk;
                if (!scrub || !ok) {
                    mSwipeScrub = false;
                    mSwipeOk = false;
                    break;
                }
                if (ev.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                    mSwipeScrub = false;
                    mSwipeOk = false;
                    mScrubbing = false;
                    jumpToProgress(mSeek.getProgress());   // 松手才落跳
                } else {
                    // 手势被系统（来电/下拉通知/窗口失焦）或别的 View 取消：
                    // 这一下**不算定位**，滑块和状态行必须退回播放头 ——
                    // 只清 mScrubbing 的话，静音看谱时 ticker 不回调，滑块会
                    // 永久停在手指离开的地方，和光标/状态行对不上。
                    Log.i(TAG, "横向划动被取消，滑块与状态行退回播放头");
                    abortSwipeSeek();
                }
                break;
            }
            default:
                break;
        }
    }

    /**
     * 底部进度条两侧的小图标（没有原版素材，用 Canvas 画）。
     *
     * 四种：到开头（▮◀）、到结尾（▶▮）、上一首（◀◀）、下一首（▶▶）。
     * 置灰时不改图形，只换颜色 —— 和顶栏按钮的置灰口径一致。
     */
    private static final class TransportButton extends View {
        static final int TO_START = 0;
        static final int TO_END = 1;
        static final int PREV_SHEET = 2;
        static final int NEXT_SHEET = 3;

        private final int mKind;
        private final android.graphics.Paint mPaint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint mBgPaint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF mBg = new android.graphics.RectF();

        TransportButton(Context c, int kind) {
            super(c);
            mKind = kind;
            mPaint.setStyle(android.graphics.Paint.Style.FILL);
            setClickable(true);
            setFocusable(false);
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            invalidate();
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();     // 按下高亮要跟着 drawable state 走
        }

        @Override
        protected void onDraw(android.graphics.Canvas cv) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;

            // 两种外观：
            //   · PREV_SHEET / NEXT_SHEET：顶栏标题两侧那对「细圆环 + 双箭头」，
            //     照原版演奏页标题两侧的样式（描边圆、浅棕、轻）——
            //     它们是"换一首"，属于导航级，不该跟功能按钮抢注意力；
            //   · TO_START / TO_END：底栏进度条两端，仍是浅色圆角矩形底 + 细边。
            //     相邻两个图标挨在一起时，光看形状分不清是几个按钮。
            final boolean circle = mKind == TransportButton.PREV_SHEET
                    || mKind == TransportButton.NEXT_SHEET;
            final float cx = w / 2f;
            final float cy = h / 2f;
            if (circle) {
                float r = Math.min(w, h) / 2f - Math.max(1f, Math.min(w, h) * 0.02f);
                if (isPressed()) {
                    mBgPaint.setStyle(android.graphics.Paint.Style.FILL);
                    mBgPaint.setColor(Ui.alpha(Ui.BROWN, 0.10f));
                    cv.drawCircle(cx, cy, r, mBgPaint);
                }
                mBgPaint.setStyle(android.graphics.Paint.Style.STROKE);
                mBgPaint.setStrokeWidth(Math.max(1f, h * 0.05f));
                mBgPaint.setColor(Ui.alpha(Ui.BROWN, isPressed() ? 0.62f : 0.34f));
                cv.drawCircle(cx, cy, r, mBgPaint);
            } else {
                float inset = Math.max(1f, w * 0.05f);
                mBg.set(inset, 1f, w - inset, h - 1f);
                float rad = Math.min(h, w) * 0.30f;
                mBgPaint.setStyle(android.graphics.Paint.Style.FILL);
                mBgPaint.setColor(isPressed() ? Ui.alpha(Ui.BROWN, 0.18f) : Ui.alpha(Ui.BROWN, 0.06f));
                cv.drawRoundRect(mBg, rad, rad, mBgPaint);
                mBgPaint.setStyle(android.graphics.Paint.Style.STROKE);
                mBgPaint.setStrokeWidth(Math.max(1f, h * 0.035f));
                // 不可用统一由 Ui.setDisabled 的整 View alpha 表达（0.40），这里
                // 不再另画一套灰色 —— 全套界面只有一种"灰"。
                mBgPaint.setColor(Ui.alpha(Ui.BROWN, 0.22f));
                cv.drawRoundRect(mBg, rad, rad, mBgPaint);
            }

            mPaint.setColor(Ui.BROWN);

            // 统一按高度定三角形，再夹一次宽度保险：宁可整体缩小，也不能溢出控件被裁掉
            // 圆环里的箭头比圆角底里的再小一档（0.52 → 0.44），圆环才留得住"呼吸"
            float th = h * (circle ? 0.44f : 0.52f);
            float tw = th * 0.58f;
            float gap = Math.max(1.2f, w * 0.07f);
            float barW = Math.max(1.6f, w * 0.09f);
            // 双三角只有 PREV/NEXT（也就是画圆环的那两种），与上面的 circle 是同一个条件
            final boolean pair = circle;
            float content = pair ? tw * 2 + gap : barW + gap + tw;
            float avail = w * (circle ? 0.60f : 0.72f);   // 留出按钮底的边距，图形别顶到边框
            if (content > avail) {
                float k = avail / content;
                th *= k;
                tw *= k;
                gap *= k;
                barW *= k;
                content = avail;
            }
            float x = (w - content) / 2f;

            if (mKind == TransportButton.TO_START) {
                // ▮◀：竖条在左，三角在右、朝左
                cv.drawRect(x, cy - th / 2f, x + barW, cy + th / 2f, mPaint);
                triangle(cv, x + barW + gap, cy, tw, th, true);
            } else if (mKind == TransportButton.TO_END) {
                // ▶▮：三角在左、朝右，竖条在右
                triangle(cv, x + tw, cy, tw, th, false);
                float bx = x + tw + gap;
                cv.drawRect(bx, cy - th / 2f, bx + barW, cy + th / 2f, mPaint);
            } else if (mKind == TransportButton.PREV_SHEET) {
                // ◀◀
                triangle(cv, x, cy, tw, th, true);
                triangle(cv, x + tw + gap, cy, tw, th, true);
            } else {
                // ▶▶
                triangle(cv, x + tw, cy, tw, th, false);
                triangle(cv, x + tw * 2 + gap, cy, tw, th, false);
            }
        }

        /**
         * 画一个三角形，tip 在 tipX 上。
         *
         * ⚠ 每个图形都 new 一个 Path：共用一个 Path 时，硬件加速下后面的
         * reset/lineTo 会把前面已经记录的绘制一起改掉 —— 实测画出来
         * 一大一小、还被 View 边界裁掉。
         */
        private void triangle(android.graphics.Canvas cv, float tipX, float cy,
                              float tw, float th, boolean pointLeft) {
            android.graphics.Path p = new android.graphics.Path();
            p.moveTo(tipX, cy);
            if (pointLeft) {
                p.lineTo(tipX + tw, cy - th / 2f);
                p.lineTo(tipX + tw, cy + th / 2f);
            } else {
                p.lineTo(tipX - tw, cy - th / 2f);
                p.lineTo(tipX - tw, cy + th / 2f);
            }
            p.close();
            cv.drawPath(p, mPaint);
        }
    }
}
