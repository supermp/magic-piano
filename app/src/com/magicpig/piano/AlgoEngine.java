package com.magicpig.piano;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.mangofuture.algo.SfeInterface;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;

/**
 * 对 libsfeALG-lib-jni.so 的封装：初始化 / 解密曲谱 / 实时合成播放。
 *
 * 线程模型：所有 native 调用都排在一条专属后台线程（"algo-engine"）上串行执行。
 * 原因有两点：
 *   1. native 侧的预览模块是全局单例，并发访问不安全；
 *   2. playNext() 产出的 PCM 直接跟在 AudioTrack.write() 后面，放在同一条线程
 *      才能保证"边合成边播放"不抖动。
 *
 * 音频格式：**88200 Hz** / 单声道 / 16bit。
 *
 * 这个采样率花了点功夫才定下来，因为原生日志里的 "SR:44100" 是骗人的 ——
 * 那是【分析】上下文的采样率（pitch follower 用，hop 882 = 20ms），
 * 而【合成输出】流水线的速率是它的两倍。三条独立证据都指向 88200：
 *
 *   1. 谱面时值：voice 1 / voice 5 各 104 个四分音符，@tempo 108 → 57.778 秒。
 *      实测总采样 5,096,000（含末块），5,096,000 / 57.778 = 88200.0，分毫不差。
 *   2. 音高：对导出的 PCM 做 FFT，起音处主频为 0.007941 / 0.008943 / 0.010033
 *      cycles/sample。谱面首句是 F5 D5 G5 F5 A5；×88200 得 700 / 789 / 885 Hz，
 *      与 F5(698.5) / G5(784.0) / A5(880.0) 吻合；×44100 则整体低一个八度。
 *   3. 日志 "Set hop to 441 as min hop." + "Set hop to 882." —— 882 恰是最小 hop 的两倍。
 *
 * 按 44100 播放的后果是整曲慢一倍、音高低一个八度（听感上很明显）。
 */
public final class AlgoEngine {

    public static final int SAMPLE_RATE = 88200;

    private static final String TAG = "AlgoEngine";
    /**
     * 单块 PCM 最多多少个采样。
     *
     * 这个数同时决定三件事：`SfeInterface` 一次最多吐多少（缓冲区按它分配）、
     * 输出轨缓冲要留多大、以及 ticker 的兜底滞后量。改它要一起看
     * {@link #TRACK_BUF_BYTES} 与 `Player.mFallbackLag`。
     */
    private static final int BLOCK_SAMPLES = 49000;
    /** AudioTrack 缓冲字节数 = 两块 BLOCK_SAMPLES（16bit 单声道 → 每采样 2 字节）。 */
    private static final int TRACK_BUF_BYTES = BLOCK_SAMPLES * 2 * 2;
    /** 整曲上限按 20000 块兜底 */
    private static final int MAX_BLOCKS = 20000;

    private static volatile AlgoEngine sInst;

    private final HandlerThread mThread = new HandlerThread("algo-engine");
    private final Handler mBg;
    private final Handler mUi = new Handler(Looper.getMainLooper());

    private boolean mInited;
    /** 跟弹用的 TFLite 模型是否已加载（演奏时才加载） */
    private String mSheetXml;
    private volatile int mMeasureCount;
    /** 物理小节索引 → 乐谱上印的小节号（见 buildMeasureNumbers）；空 = 还没建好 */
    private volatile int[] mMeasureNo = new int[0];
    /** 乐谱上印的最大小节号（进度条 "/ N" 用它，而不是物理小节数） */
    private volatile int mMeasureNoMax;
    private volatile Player mPlayer;
    /**
     * 定位令牌：每次 seekTo / stop 都 +1。轮询里的定位 Runnable 开跑前对一下，
     * 过期就放弃 —— 否则用户连点两下谱面再按停止，队列里作废的定位会把播放又拉起来。
     */
    private volatile long mSeekEpoch;

    private AlgoEngine() {
        mThread.start();
        mBg = new Handler(mThread.getLooper());
    }

    public static AlgoEngine get() {
        if (sInst == null) {
            synchronized (AlgoEngine.class) {
                if (sInst == null) sInst = new AlgoEngine();
            }
        }
        return sInst;
    }

    // ---------------- 引擎生命周期 ----------------

    /** 初始化算法引擎。必须在 openSheet() 之前调用一次。 */
    public void init(String algoDir) {
        post(() -> {
            if (mInited) return;
            long t0 = SystemClock.elapsedRealtime();
            SfeInterface.setDevDebugMode(false);
            int ret = SfeInterface.appAlgorithmInitV2(0, 0, algoDir);
            if (ret != 0) throw new IllegalStateException("appAlgorithmInitV2 返回 " + ret);
            long t1 = SystemClock.elapsedRealtime();
            SfeInterface.appLoadSample(
                    algoDir + "/sound/synthesis_sample_piano/1.0.0/PianoWaveStereo.bin");
            long t2 = SystemClock.elapsedRealtime();
            SfeInterface.setTfLiteCallbacks();
            mInited = true;
            // 注意：**不再在这里 preloadTfModels**。那会把演奏用的 84MB 转谱模型
            // 一并塞进内存，播放页白等好几秒。改成进演奏时才加载 —— 由
            // UfpTrial 在建会话前直接调 SfeInterface.preloadTfModels(true, true)
            // （必须 highPerf，否则 ufpCreateMP 返回 4）。
            Log.i(TAG, "引擎就绪：引擎初始化 " + (t1 - t0) + "ms，加载 62MB 钢琴音源 "
                    + (t2 - t1) + "ms，合计 " + (t2 - t0) + "ms（ML 模型已改为演奏时加载）");
        });
    }

    /** 解密 .enc 并打开曲谱。返回 MusicXML 明文字符数。 */
    public int openSheet(String encPath) {
        final int[] len = new int[1];
        post(() -> {
            // 先查文件：曲谱包缺失/截断时，native 的 DecryptSheetImpl 会抛
            // std::runtime_error 直接 abort 掉整个进程
            // （AlgoScoreManager.cpp:30，SIGABRT，Java 层完全拦不住）。
            // 没有 magic 头可以校验，所以只能查"存在 + 大小像个谱子"。
            java.io.File f = new java.io.File(encPath);
            if (!f.isFile() || f.length() < 1024) {
                throw new IllegalStateException("曲谱文件缺失或过小（放弃解密，避免引擎崩溃）："
                        + encPath + " size=" + (f.exists() ? f.length() : -1));
            }
            mSheetXml = SfeInterface.decryptSheet(encPath);
            if (mSheetXml == null || mSheetXml.isEmpty()) {
                throw new IllegalStateException("decryptSheet 返回空：" + encPath);
            }
            // 选段属于单首曲子：引擎是跨页面单例，不清的话上一首的 BEGIN/END
            // 会在本曲 Player 起播时被 applyPlaySection 再下发一次
            // （段号落在本曲范围内就只播那一段，超出范围则 setSection 失败）。
            if (mPlaySection != null) Log.i(TAG, "换曲：清掉上一首的试听区间");
            mPlaySection = null;
            // 先释放旧模块再建新的。预览模块是全局单例，直接对已存在的模块
            // 再 init 一次不会复位播放游标 —— 上一次会话停在哪，这回就从哪开始播。
            // release + init 是唯一可靠的复位方式（resetCursor 用的也是这一套）。
            SfeInterface.releasePreviewModule();
            if (!SfeInterface.initPreviewModule(mSheetXml)) {
                throw new IllegalStateException("initPreviewModule 失败");
            }
            len[0] = mSheetXml.length();
            mMeasureCount = countMeasures(mSheetXml);
        });
        return len[0];
    }

    public String getSheetXml() {
        return mSheetXml;
    }

    // ---------------- 手型（合手/右手/左手） ----------------

    /**
     * 当前手型：0=合手（默认） 1=右手 2=左手。
     * 取值与原版 App 实测一致（原版 logcat：MFPitchHandType 0/1/2）。
     */
    private volatile int mHandType;

    /**
     * 手型修订号：每次选择都递增，不能用“应用后清 dirty”代替——旧 Player 的清脏
     * 可能覆盖并发的新选择，导致 UI 已合手、native 永久停在单手音量。
     */
    private final java.util.concurrent.atomic.AtomicInteger mHandRevision =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 最近一次真正完成 native 下发的修订号；由引擎线程或 Player 线程写。 */
    private volatile int mAppliedHandRevision = -1;

    /**
     * 预览合成的播放区间（原版试炼选段试听用的 native setSection）。
     * 6 个数 = BEGIN[measure,voice,offset] + END[measure,voice,offset]；
     * null = 不设区间（整曲）。每次 Player 重建预览模块之后重新下发。
     */
    private volatile int[] mPlaySection;

    /**
     * 切换手型（0=合手 1=右手 2=左手）——**不打断播放**。
     *
     * 【实现方式与验证】原生预览模块没有按手型过滤声部的接口：
     *   - changeHandType(ctx, N) 对预览模块无效（实测 PCM 逐字节不变）；
     *   - setSection 的声部范围也不过滤（实测两手输出相同）。
     * 但 so 里有双手独立音量 setElectricPianoLeft/RightHandVolume(ctx, 0..100)，
     * 把【另一只手】静音即得"只听见这只手"。已用整曲 PCM 频谱验证：
     *   静音左手 → 低频(贝斯 60-300Hz)能量降 79%、旋律保留；
     *   静音右手 → 旋律(600-1400Hz)能量降 57%、贝斯保留。
     * 音量 100 = 默认满音量（实测与不设置时输出逐字节一致）。
     *
     * 【为什么不重建模块】静音只是改音量，不改变合成时序，播放进度也不变 ——
     * 不需要停播，更不需要重建预览模块（重建会把正在播放的 PCM 源头抽走，必然断音）。
     *
     * 【线程安全】native 预览模块是全局单例，不能和 Player 线程的 playNext
     * 并发调用。所以：
     *   - 没在播放：直接在引擎线程下发；
     *   - 正在播放：由 Player 线程在两块 PCM 之间比较修订号并下发。
     * 因为播放循环是「合成一块 → 整块写完（缓冲满就睡）→ 再合成下一块」，
     * 最多只有一块（≈0.56 秒）已合成未播出，所以听感上就是"从下一个音
     * 开始消失"，音乐本身完全不断。
     */
    public void setHandType(int handType) {
        final int ht = (handType < 0 || handType > 2) ? 0 : handType;
        mHandType = ht;
        mHandRevision.incrementAndGet();
        mBg.post(() -> {
            if (mPlayer == null) {
                // 没在播放：引擎线程上没有并发的 native 调用，直接下发
                int applied = applyHandVolumes();
                Log.i(TAG, "手型已切换：" + applied + "（0合手/1右手/2左手）· 空闲态直接生效");
            } else {
                Log.i(TAG, "手型已切换：" + ht + "（0合手/1右手/2左手）· 播放中，下一块生效");
            }
        });
    }

    /**
     * 按当前手型设双手音量：所选之手 100，另一只手 0（合手时都 100）。
     * 每次播放会话开始（Player.run 的 resetCursor 之后）也会调一遍，
     * 防止模块重建丢掉音量设置。
     *
     * 修订号在 native 调用前取样、完成后才发布。若下发期间又发生切换，发布出去的
     * 仍是旧修订号，下一块会继续补发最新状态，不会像布尔 dirty 那样把新请求清掉。
     *
     * 只允许在「当前正在调用 native 播放接口的那条线程」上调用
     * （引擎线程空闲时，或 Player 线程两块之间）。
     *
     * @return 本次实际下发的手型
     */
    private int applyHandVolumes() {
        final int revision = mHandRevision.get();
        final int ht = mHandType;
        int lv = ht == 1 ? 0 : 100;   // 右手模式：静音左手
        int rv = ht == 2 ? 0 : 100;   // 左手模式：静音右手
        SfeInterface.setElectricPianoLeftHandVolume("", lv);
        SfeInterface.setElectricPianoRightHandVolume("", rv);
        mAppliedHandRevision = revision;
        return ht;
    }

    public int getMeasureCount() {
        return mMeasureCount;
    }

    /**
     * 物理小节索引 → **乐谱上印的小节号**（进度条"第 x 小节"用这个）。
     * 隐式小节（`number="0"` / `"X1"` 之类）沿用前一个号，见 buildMeasureNumbers。
     */
    public int measureNumber(int index) {
        int[] a = mMeasureNo;
        if (index < 0) index = 0;
        if (a.length == 0) return index + 1;
        if (index >= a.length) index = a.length - 1;
        int v = a[index];
        return v > 0 ? v : index + 1;
    }

    /** 乐谱上印的最大小节号（进度条 "/ N" 里的 N；没有编号信息时退回物理小节数） */
    public int getMeasureNumberTotal() {
        return mMeasureNoMax > 0 ? mMeasureNoMax : mMeasureCount;
    }

    /**
     * 数出小节总数。
     *
     * 不能在整份 XML 上 `数 <measure 标签 ÷ <part> 个数`：
     *   · MusicXML 允许个别小节**不带 `number` 属性**（弱起、拆分小节、导出工具的怪癖），
     *     只数 `<measure number=` 会**偏小** —— 进度条拖到最右也到不了真正的曲末；
     *   · 各 part 的小节数在真实谱包里并不总是相等（空 part、只写了一半的 part），
     *     一除就两头都不准。
     * 现在改成**逐 part 各自数所有 `<measure` 标签，取最大值**：既不依赖 number
     * 属性，也不要求各 part 等长。<part-list> 是 `<part-`，靠"part 后面必须跟空格"
     * 区分开；`<measure-distance>` / `<measure-style>` 靠"measure 后面必须跟空格或 >"
     * 区分开。
     */
    private int countMeasures(String xml) {
        if (xml == null) return 0;
        int parts = 0, maxPerPart = 0;
        // 只留第一个 part 的编号做"印刷号"表（各 part 的小节编号相同）
        java.util.ArrayList<Integer> firstPart = null;
        int from = 0;
        while (true) {
            int p = xml.indexOf("<part ", from);
            if (p < 0) break;
            int end = xml.indexOf("</part>", p);
            if (end < 0) end = xml.length();
            java.util.ArrayList<Integer> nums = new java.util.ArrayList<>();
            scanMeasures(xml, p, end, nums);   // 一遍扫描同时拿到"个数"和"编号"
            if (firstPart == null) firstPart = nums;
            if (nums.size() > maxPerPart) maxPerPart = nums.size();
            parts++;
            from = end + 7;   // "</part>".length()
        }
        if (parts == 0) {     // 不是分 part 的谱：整份数一遍
            firstPart = new java.util.ArrayList<>();
            scanMeasures(xml, 0, xml.length(), firstPart);
            maxPerPart = firstPart.size();
            parts = 1;
        }
        buildMeasureNumbers(firstPart, maxPerPart);
        int missing = 0, maxNumber = -1;
        if (firstPart != null) {
            for (int n : firstPart) {
                if (n <= 0) missing++;
                else if (n > maxNumber) maxNumber = n;
            }
        }
        Log.i(TAG, "小节数 = " + maxPerPart + "（parts=" + parts
                + "，最大 number=" + maxNumber + "，无有效编号的小节=" + missing
                + "，乐谱上印的最大小节号=" + mMeasureNoMax + "）");
        return maxPerPart;
    }

    /**
     * 把"物理小节索引 → 乐谱上印的小节号"算出来。
     *
     * 为什么需要这张表：MusicXML 里有**不占编号的小节**——
     * 弱起小节常写成 `number="0" implicit="yes"`，反复段的第二结尾写成 `number="X1"`。
     * 保卫黄河就是这种谱：物理 386 个小节，但乐谱上印的号只到 **384**
     * （0 是弱起、X1 是复奏小节），于是进度条写 386/386、谱面最后一行却是 384 ——
     * 用户看到的两个数字对不上。
     *
     * 规则：能读出数字就用它；隐式/非数字（0、X1、1a…）**沿用前一个号**；
     * 开头没有可沿用的就按 1 算。这样"第 x 小节"永远等于谱面上那个号。
     */
    private void buildMeasureNumbers(java.util.ArrayList<Integer> raw, int count) {
        if (count <= 0) {
            mMeasureNo = new int[0];
            mMeasureNoMax = 0;
            return;
        }
        int[] labels = new int[count];
        int prev = 0;
        for (int i = 0; i < count; i++) {
            int n = (raw != null && i < raw.size()) ? raw.get(i) : 0;
            if (n > 0) prev = n;
            labels[i] = prev > 0 ? prev : (i + 1);
        }
        int max = 0;
        for (int v : labels) if (v > max) max = v;
        if (max <= 0) max = count;
        mMeasureNo = labels;
        mMeasureNoMax = max;
    }

    /**
     * 扫 [from,to) 里所有 `<measure>` 标签，把每个的 number 数字部分收进 `out`
     * （读不出数字记 0）。**一遍扫描同时给出小节个数**（= out.size()）与编号，
     * 原来"数个数"和"收编号"是两个几乎一样的循环，等于把 1~3MB 的 XML 扫两遍。
     *
     * 认标签的规则：`<measure` 后面必须是空格或 `>`（排除 `<measure-distance>` /
     * `<measure-style>`）；number 取值只认双引号紧跟数字（`X1` / `1a` 记 0）。
     */
    private static void scanMeasures(String s, int from, int to,
                                     java.util.ArrayList<Integer> out) {
        int i = from;
        while (true) {
            i = s.indexOf("<measure", i);
            if (i < 0 || i >= to) break;
            int after = i + 8;
            if (after >= to) break;
            char c = s.charAt(after);
            if (c != ' ' && c != '>' && c != '\n' && c != '\t' && c != '\r') {
                i = after;
                continue;
            }
            int tagEnd = s.indexOf('>', after);
            int q = s.indexOf("number=\"", after);
            int n = 0;
            if (q >= 0 && (tagEnd < 0 || q < tagEnd)) {
                int v = q + 8, e = v;
                while (e < to && e < s.length() && Character.isDigit(s.charAt(e))) e++;
                if (e > v) {
                    try {
                        n = Integer.parseInt(s.substring(v, e));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            out.add(n);
            i = after;
        }
    }

    /**
     * 从指定位置开始合成整曲 PCM —— 演奏支持"从当前音符开始"后，自喂调试通路
     * 也得从同一个位置起喂，否则喂进去的是曲首音频、跟引擎的练习区间对不上。
     *
     * 做法与 Player 的定位一致：先 playFirst 建会话，再 setPlayPositionWithoutCountDown
     * 挪游标；定位后第一块 PCM 是曲首的，必须丢掉。
     */
    public java.util.List<short[]> synthesizeAll(int measure, int voice, int offset) {
        java.util.ArrayList<short[]> blocks = new java.util.ArrayList<>();
        SfeInterface.PlayNote n = newNote();
        SfeInterface.playFirst("", 0, 0, 0, n);
        final boolean seek = !(measure == 0 && voice == 0 && offset == 0);
        if (seek) {
            int ret = SfeInterface.setPlayPositionWithoutCountDown("", measure, voice, offset);
            Log.i(TAG, "自喂从第 " + measure + " 小节 voice=" + voice + " offset=" + offset
                    + " 起合成，setPlayPosition ret=" + ret);
        } else if (n.pcm != null && n.pcmLength > 0) {
            blocks.add(n.pcm.clone());
        }
        for (int i = 0; i < MAX_BLOCKS && n.finished == 0; i++) {
            SfeInterface.playNext("", n);
            if (n.pcm != null && n.pcmLength > 0) blocks.add(n.pcm.clone());
        }
        Log.i(TAG, "synthesizeAll: " + blocks.size() + " 块（自喂调试用，起点 "
                + measure + "/" + voice + "/" + offset + "）");
        return blocks;
    }

    public void release() {
        post(() -> {
            if (mSheetXml != null) {
                SfeInterface.releasePreviewModule();
                mSheetXml = null;
            }
        });
    }

    // ---------------- 播放 ----------------

    public interface ProgressListener {
        void onProgress(int measure, int offset, int voice);

        void onEnded();

        void onError(String msg);
    }

    /**
     * 播放头当前所在的位置。它只是个"书签"，不一定有对应的播放会话 ——
     * 没在播放时点谱面就是只动它、不发声。默认 (0,0,0) 即曲首。
     */
    private volatile int mHeadMeasure = 0;
    private volatile int mHeadVoice = 0;
    private volatile int mHeadOffset = 0;

    /**
     * 没在播放时点谱面：只把播放头挪过去，**不发声、不建立播放会话**。
     *
     * 之所以完全不碰 native：预览模块是用 playFirst 建会话、再 setPlayPosition
     * 挪游标的，只要不调用就没有任何开销也没有声音；等用户按「试听」时，
     * start() 会按这个书签用正常路径（resetCursor → playFirst → setPlayPosition）
     * 从头建一次会话，结果一样。
     */
    public void movePlayheadTo(int measure, int voice, int offset) {
        mHeadMeasure = measure;
        mHeadVoice = voice;
        mHeadOffset = offset;
    }

    /** 当前播放头位置（演奏时作为"从这儿开始"的起点）。 */
    public int headMeasure() { return mHeadMeasure; }
    public int headVoice() { return mHeadVoice; }
    public int headOffset() { return mHeadOffset; }

    /** 从播放头当前位置起播：没点过谱面就是曲首，点过就从点到的那个音符开始。 */
    public void start(ProgressListener l) {
        seekTo(mHeadMeasure, mHeadVoice, mHeadOffset, l);
    }

    /** 建一条 16bit 单声道 PCM 流式输出轨。 */
    static AudioTrack createStreamTrack(int bufBytes) {
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
        return new AudioTrack.Builder()
                .setAudioAttributes(attrs).setAudioFormat(fmt)
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM).build();
    }

    /**
     * 本次进程实际使用的缓冲字节数。
     *
     * 设备上报的 `getMinBufferSize` 偶尔比我们自己的下限还大（低端机常见），
     * 这时必须跟着放大。
     */
    static int trackBufBytes() {
        int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        return Math.max(minBuf, TRACK_BUF_BYTES);
    }

    /**
     * 从第 measure 小节、第 voice 声部、第 offset 个位置起播；
     * measure=0、voice=0、offset=0 表示从头。
     *
     * voice/offset 直接透传 JS（onNoteClick2）报上来的值，和原版 App 走同一条
     * 通道，所以能精确落到点击的那个音符，而不只是小节开头。
     *
     * 线程模型（重要）：Player 跑在自己的线程上，本方法只在 mBg 协调线程里
     * 「喊停旧的 → 等它退出 → 起新的」。早年把 Player 直接跑在 mBg 上，结果
     * 播放中收到的定位请求全被堵在播放循环后面，要等整首播完才生效 ——
     * 播放中点谱面必须立刻跳转，所以播放器必须有独立线程。
     *
     * native 预览模块是全局单例，因此同一时刻只允许一个 Player 存活：旧的
     * 彻底退出（mPlayer 置空）之后，新的才会开始调 native。
     */
    public void seekTo(int measure, int voice, int offset, ProgressListener l) {
        final long epoch = ++mSeekEpoch;
        final Player np = new Player(l, measure, voice, offset);
        final long waitStart = SystemClock.elapsedRealtime();
        mBg.post(new Runnable() {
            @Override
            public void run() {
                if (epoch != mSeekEpoch) return; // 被更新的定位/停止作废
                Player cur = mPlayer;
                if (cur != null) {
                    // 直接喊停即可，不用等 mBg 腾出来 —— 旧的几毫秒内就会退出
                    cur.requestStop();
                    if (SystemClock.elapsedRealtime() - waitStart > 3000) {
                        // native 全局单例：旧 Player 仍存活时绝不能清空引用后起新的。
                        // 本次定位失败并复位 UI；旧 Player 退出后用户可再次起播。
                        Log.e(TAG, "旧 Player 3s 未退出，取消本次起播");
                        mUi.post(() -> np.mListener.onError("上一次试听还没停住，请稍后再试"));
                        return;
                    } else {
                        mBg.postDelayed(this, 60);
                        return;
                    }
                }
                mPlayer = np;
                Thread t = new Thread(np, "algo-player");
                t.start();
            }
        });
    }

    /**
     * 把播放游标复位到曲首。
     * 释放再重建预览模块是唯一可靠的复位方式 —— playFirst 只是读当前游标，
     * 不会自动回到开头。
     */
    private void resetCursor() {
        SfeInterface.releasePreviewModule();
        if (!SfeInterface.initPreviewModule(mSheetXml)) {
            throw new IllegalStateException("复位游标失败");
        }
    }

    /** 设置/清除试听区间。传 null 清除，下一次起播恢复整曲。 */
    public void setPlaySection(int[] beginEnd) {
        mPlaySection = beginEnd == null || beginEnd.length != 6 ? null : beginEnd.clone();
    }

    /** 模块刚重建完、playFirst 之前下发区间。native 在合成层裁掉段外音。 */
    private void applyPlaySection() {
        int[] r = mPlaySection;
        if (r == null) return;   // 不调用 = 模块默认整曲
        try {
            int ret = SfeInterface.setSection("", r[0], r[1], r[2], r[3], r[4], r[5]);
            if (ret != 0) Log.w(TAG, "setSection 返回 " + ret + "，区间未生效");
            Log.i(TAG, "setSection -> " + ret + " BEGIN[" + r[0] + "," + r[1] + "," + r[2]
                    + "] END[" + r[3] + "," + r[4] + "," + r[5] + "]");
        } catch (Throwable t) {
            Log.w(TAG, "setSection 不可用，退回软件层选段判断", t);
            mPlaySection = null;
        }
    }

    public void stop() {
        mSeekEpoch++; // 作废所有排队中的定位，否则停止之后它们还会把播放拉起来
        Player p = mPlayer;
        if (p != null) p.requestStop();
    }

    /**
     * 停止播放并等待 Player 线程<b>彻底退出</b>（mPlayer 置空）再返回。
     *
     * stop() 只是置标志位，Player 要到下一块（≈0.56 秒）边界才会停；
     * 演奏要立刻释放预览模块这个全局单例，必须等它完全退场，否则两个
     * 线程并发碰 native。返回 false 表示旧 Player 仍在运行，调用方不得建 UFP。
     */
    public boolean stopAndWait() {
        stop();
        long deadline = SystemClock.elapsedRealtime() + 3000;
        while (isPlaying() && SystemClock.elapsedRealtime() < deadline) {
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        if (isPlaying()) {
            Log.e(TAG, "stopAndWait 超时：Player 仍未退出，禁止创建 UFP");
            return false;
        }
        return true;
    }

    public boolean isPlaying() {
        return mPlayer != null;
    }

    private static SfeInterface.PlayNote newNote() {
        SfeInterface.PlayNote n = new SfeInterface.PlayNote();
        n.position = new SfeInterface.NotePosition();
        n.segment = new SfeInterface.Segment();
        return n;
    }

    /**
     * 一个已写入块的起始采样位置 + 它对应的谱面坐标。
     *
     * 有了它才能把"播放头现在所在的采样位置"翻译回"第几小节"。
     */
    private static final class Cue {
        final long start;
        final int measure, offset, voice;

        Cue(long start, int measure, int offset, int voice) {
            this.start = start;
            this.measure = measure;
            this.offset = offset;
            this.voice = voice;
        }
    }

    private final class Player implements Runnable {
        /** 光标刷新间隔。按 88200Hz 算，60ms ≈ 5292 采样，远小于一块的 BLOCK_SAMPLES */
        private static final int TICK_MS = 60;
        /** 播放头连续多少次读不到才算"卡死"，之后退回按缓冲区估算 */
        private static final int HEAD_STUCK_MAX = 8;

        private final ProgressListener mListener;
        private final int mStartMeasure;
        private final int mVoice;
        private final int mOffset;
        private volatile boolean mStopRequested;
        private volatile boolean mFinished;
        private volatile AudioTrack mTrack;
        private volatile long mWrittenFrames;
        private volatile boolean mNativeDone;
        /** 播放过程中抛过异常：finally 里不要再报 onEnded（会把错误卡覆盖掉） */
        private volatile boolean mErrored;
        /** 兜底滞后量（缓冲区能容纳的采样数） */
        private volatile long mFallbackLag = BLOCK_SAMPLES;

        private long mStartWall;
        private long mTickCount;
        private final ArrayList<Cue> mCues = new ArrayList<>();
        private int mHeadStuck;

        Player(ProgressListener l, int startMeasure, int voice, int offset) {
            mListener = l;
            mStartMeasure = startMeasure;
            mVoice = voice;
            mOffset = offset;
        }

        void requestStop() {
            mStopRequested = true;
        }

        /**
         * 光标驱动器。
         *
         * 关键点：位置取 <b>AudioTrack.getPlaybackHeadPosition()</b>（硬件已经播出去的量），
         * 而不是"写了多少"。两者的差就是缓冲区占用 —— bufBytes = 196000 字节
         * = 98000 采样 ≈ 1.11 秒，之前正是这个差值让光标一直跑在音乐前面。
         */
        private final Runnable mTick = new Runnable() {
            @Override
            public void run() {
                if (mFinished) return;
                AudioTrack t = mTrack;
                if (t == null) {
                    mUi.postDelayed(this, TICK_MS);
                    return;
                }
                long played = readPlayedFrames(t);
                emitAt(played);
                if (mNativeDone && played >= mWrittenFrames) return; // 播完，交给 onEnded
                mUi.postDelayed(this, TICK_MS);
            }
        };

        private long readPlayedFrames(AudioTrack t) {
            long head;
            try {
                head = t.getPlaybackHeadPosition();
            } catch (Throwable e) {
                head = -1;
            }
            if (head < 0) head = 0;
            long written = mWrittenFrames;
            // 播放头从 play() 起算；缓冲里现在只有真实 PCM（不再垫静音），
            // 所以 head 本身就是音乐位置，不需要任何扣减。
            if (head == 0 && written > mFallbackLag) {
                // 极少数设备的 MODE_STREAM 播放头不推进，退回按缓冲区容量估算
                if (++mHeadStuck > HEAD_STUCK_MAX) {
                    long est = written - mFallbackLag;
                    return Math.max(0, est);
                }
            } else {
                mHeadStuck = 0;
            }
            // 夹一下防止读数抖动时把进度条顶出界；再用「从 play() 起真实经过的
            // 时间」夹一次。起播瞬间 head 若虚高（与 drain 同一个坑），不加这层
            // 会把光标直接甩到曲尾、ticker 误判播完而停摆。正常播放时 head 本来就
            // ≈ elapsed × 采样率，这层不会改变结果。
            long music = Math.max(0, Math.min(head, written));
            if (mStartWall > 0) {
                long byTime = (SystemClock.elapsedRealtime() - mStartWall)
                        * SAMPLE_RATE / 1000L;
                music = Math.min(music, Math.max(0, byTime));
            }
            return music;
        }

        /** 把"播放到第几个采样"翻译成"第几小节"，然后上报 UI。 */
        private void emitAt(long played) {
            int measure = 0, offset = 0, voice = 0;
            synchronized (mCues) {
                int lo = 0, hi = mCues.size() - 1, idx = -1;
                while (lo <= hi) {
                    int mid = (lo + hi) >>> 1;
                    if (mCues.get(mid).start <= played) {
                        idx = mid;
                        lo = mid + 1;
                    } else {
                        hi = mid - 1;
                    }
                }
                if (idx < 0) return; // 还没有任何块写入，位置未知就别报，
                                     // 否则会被当成"第 0 小节"把光标甩回开头
                Cue c = mCues.get(idx);
                measure = c.measure;
                offset = c.offset;
                voice = c.voice;
            }
            final int m = measure, o = offset, v = voice;

            // 播放中让播放头跟着走：中途按停止再按「试听」，就从停下的地方接着听
            mHeadMeasure = m;
            mHeadOffset = o;
            mHeadVoice = v;

            // 稀疏诊断：wall 与 played 的差值就是"发声比起播晚了多少毫秒"。
            // 它应当是一个几百毫秒的小常数（重建模块 + 合成首块 + 硬件延迟）；
            // 若持续变大，说明 getPlaybackHeadPosition 没在推进。
            if (++mTickCount % 17 == 0) {
                long wall = mStartWall > 0 ? SystemClock.elapsedRealtime() - mStartWall : 0;
                long lag = wall - played * 1000L / SAMPLE_RATE;
                Log.v(TAG, String.format(
                        "sync played=%.2fs written=%.2fs wall=%dms lag=%dms m=%d",
                        played / (float) SAMPLE_RATE, mWrittenFrames / (float) SAMPLE_RATE,
                        wall, lag, m));
            }

            mUi.post(() -> mListener.onProgress(m, o, v));
        }

        public void run() {
            AudioTrack track = null;
            long written = 0;
            try {
                final int bufBytes = trackBufBytes();
                // 16bit 单声道：1 frame = 2 字节
                mFallbackLag = bufBytes / 2;
                track = createStreamTrack(bufBytes);
                mTrack = track;

                final boolean seek = mStartMeasure > 0 || mVoice > 0 || mOffset > 0;

                // 【先定位置，再开 ticker】ticker 每 60ms 按「已写入块的 cue 表」
                // 换算位置汇报给 UI，而定位后第一块 PCM 要等 resetCursor + 合成
                // 完才写得进去（实测 0.5~1 秒）。这段空窗期里 cue 表是空的，
                // ticker 一旦汇报就会把光标甩回第 1 个音符，等真正的块写入后再
                // 跳到目标 —— 表现就是"先闪一下再播"。所以这里提前把目标位置
                // 塞进 cue 表。
                if (seek) {
                    synchronized (mCues) {
                        mCues.add(new Cue(0, mStartMeasure, mOffset, mVoice));
                    }
                }

                mUi.removeCallbacks(mTick);

                // 先开时钟。MODE_STREAM 的轨必须在写入前 play()，否则硬件不消费、
                // write() 一直返回 0。
                track.play();
                mStartWall = SystemClock.elapsedRealtime();
                mUi.post(mTick);

                resetCursor();
                // 模块刚重建：区间、手型音量都要重新下发
                applyPlaySection();
                // 模块刚重建，按当前手型重设双手音量（另一只手静音=只听见所选之手）
                applyHandVolumes();

                SfeInterface.PlayNote n = newNote();
                SfeInterface.playFirst("", 0, 0, 0, n);
                // 定位必须在 playFirst 之后再做。实测在 playFirst 之前调用时
                // native 一律返回 0 但游标纹丝不动 —— 这个函数改的是"正在播放"的
                // 会话状态，会话还不存在就等于空操作。
                // voice/offset 是 JS 从点击位置算出来的（与原版 App 同一通道），
                // 透传过去才能精确落到点击的那个音符。
                if (seek) {
                    SfeInterface.setPlayPositionWithoutCountDown(
                            "", mStartMeasure, mVoice, mOffset);
                    // 上面这块是曲首的 PCM，丢弃；真正的数据从 playNext 开始
                }

                if (!seek) {
                    record(written, n);
                    written += write(track, n);
                    mWrittenFrames = written;
                }

                // 末块（finished=1）仍含曲子结尾的 PCM，要照写不误
                for (int i = 0; i < MAX_BLOCKS && !mStopRequested && n.finished == 0; i++) {
                    // 手型切换的音量下发点：在 Player 线程、两块 PCM 之间，
                    // 既不会和 playNext 并发抢全局单例，又能让下一块立刻按
                    // 新手型合成 —— 播放不中断，中间另一只手从下一块起消失。
                    // 只比较修订号，不在应用后清布尔标志：即使本次下发途中又切了一次，
                    // mAppliedHandRevision 仍落后一个版本，下一块一定会补发最新手型。
                    if (mAppliedHandRevision != mHandRevision.get()) {
                        int applied = applyHandVolumes();
                        Log.i(TAG, "播放中应用手型 " + applied + " 的双手音量");
                    }
                    SfeInterface.playNext("", n);
                    if (n.pcmLength > 0) {
                        record(written, n);
                        written += write(track, n);
                        mWrittenFrames = written;
                    }
                }
                mNativeDone = true;
                // 排空：等最后的尾巴真的从喇叭出来再收尾，否则会被 stop() 截掉
                if (!mStopRequested) drain(track, written);
            } catch (Throwable t) {
                mErrored = true;
                Log.e(TAG, "播放异常", t);
                final String msg = t.toString();
                mUi.post(() -> mListener.onError(msg));
            } finally {
                mFinished = true;
                mUi.removeCallbacks(mTick);
                if (track != null) {
                    try {
                        track.stop();
                        written = Math.max(written, track.getPlaybackHeadPosition());
                    } catch (Throwable ignored) {
                    }
                    // release 必须自保：它一抛异常，下面 mTrack=null / onEnded /
                    // mPlayer=null 全会被跳过 → mPlayer 永远非空 → isPlaying()
                    // 恒为 true，之后所有定位都落进 seekTo 的重试链。
                    try {
                        track.release();
                    } catch (Throwable ignored) {
                    }
                }
                mTrack = null;
                // 被喊停的（定位切换 / 用户按停止）不上报 onEnded：UI 已经在
                // 按下的那一刻更新过了，再报一次会把按钮/状态刷回旧值。
                // 抛过异常的也不再报：onError 已经把错误卡弹出来了，再报一次
                // onEnded 会被当成"正常播完"覆盖掉错误状态、回写播放头。
                if (!mStopRequested && !mErrored) {
                    mUi.post(() -> mListener.onEnded());
                }
                if (mPlayer == this) mPlayer = null; // 只清理自己，不能误清新 Player
            }
        }

        /**
         * 阻塞等待缓冲区里的残余音频播完，带超时兜底。
         *
         * ⚠ **不能只看 head**：
         * 判据曾是 `head >= written`，少了时间裕量。"刚进页面就点"时
         * `getPlaybackHeadPosition()` 可能一上来就报个偏大的值，于是这里
         * 瞬间返回 → finally 里 `track.stop()` 把还没出声的 PCM 全丢掉 →
         * 没声音 + onEnded 把按钮刷回「试听」。第二次点 HAL 稳定了就正常。
         *
         * 现在要求 **head 追上 written 且从 play() 起真的过了 written 帧的时长**
         * 才收尾：head 虚高会被时间挡住，HAL 启动慢导致 head 滞后则会多等一会。
         * 两个条件都满足时没有额外延迟（elapsed ≈ needMs）。
         */
        private void drain(AudioTrack t, long written) {
            final long needMs = written * 1000L / SAMPLE_RATE;
            final long start = mStartWall > 0 ? mStartWall : SystemClock.elapsedRealtime();
            final long deadline = System.currentTimeMillis() + needMs + 4000;
            boolean warned = false;
            while (!mStopRequested && System.currentTimeMillis() < deadline) {
                long head;
                try {
                    head = t.getPlaybackHeadPosition();
                } catch (Throwable e) {
                    return;
                }
                long elapsed = SystemClock.elapsedRealtime() - start;
                if (head >= written) {
                    if (elapsed >= needMs - 100) return;   // 真播够了才收尾
                    if (!warned) {
                        warned = true;
                        // 真机上若重现"无声回弹"，这一行就是证据
                        Log.w(TAG, "播放头提前到 " + head + "/" + written + " 帧，但起播才 "
                                + elapsed + "ms（需 " + needMs + "ms），继续等真实播完");
                    }
                }
                try {
                    Thread.sleep(40);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private void record(long start, SfeInterface.PlayNote n) {
            SfeInterface.NotePosition p = n.position;
            Cue c = new Cue(start,
                    p == null ? 0 : p.measure,
                    p == null ? 0 : p.offset,
                    p == null ? 0 : p.voice);
            synchronized (mCues) {
                mCues.add(c);
            }
        }

        /**
         * 写一块 PCM。非阻塞 + 短睡重试，而不是阻塞式 write()：阻塞写在缓冲满时
         * 一挂就是整整一缓冲（约 1.1 秒），定位/停止请求半天叫不停播放循环。
         * 绝不能"写不下就顺手合成下一块"，那会在硬件还没消费时就把整首曲子合成完。
         */
        private int write(AudioTrack track, SfeInterface.PlayNote n) {
            if (n.pcm == null || n.pcmLength <= 0) return 0;
            int total = Math.min(n.pcmLength, n.pcm.length);
            int off = 0;
            // 卡死保护：连续 1.2 秒写不进去，说明 track 已死，放弃剩余部分。
            long stuckSince = 0;
            while (off < total && !mStopRequested) {
                int w = track.write(n.pcm, off, total - off, AudioTrack.WRITE_NON_BLOCKING);
                if (w > 0) {
                    off += w;
                    stuckSince = 0;
                } else if (w == 0) {
                    long now = SystemClock.elapsedRealtime();
                    if (stuckSince == 0) stuckSince = now;
                    if (now - stuckSince > 1200) {
                        Log.w(TAG, "写入卡住 1.2s（剩 " + (total - off) + " 帧），放弃剩余");
                        break;
                    }
                    try {
                        Thread.sleep(6);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    break; // 出错（例如 track 已被停掉）
                }
            }
            return off;
        }
    }

    // ---------------- 工具 ----------------

    /** 在引擎线程同步执行；已在引擎线程上则直接跑。异常原样抛到调用方。 */
    public void runOnEngine(Runnable r) {
        // post() 本身就会等待任务完成并传播异常，无需再包一层 latch。
        post(r);
    }

    /**
     * 只排队、**不等待**。
     *
     * ⚠ {@link #post(Runnable)} 内部 `latch.await()`，事实上是同步调用 ——
     * 之前几处"异步收尾"的注释都写错了，低端机上照样会把调用线程卡住
     * （实测 releaseUfpHandler 3 秒，全卡在按停的那条线程上）。
     * 需要"排到引擎队列但不阻塞调用方"时用这个。
     */
    public void postAsync(Runnable r) {
        if (Thread.currentThread() == mThread) {
            r.run();
            return;
        }
        mBg.post(r);
    }

    public void post(Runnable r) {
        if (Thread.currentThread() == mThread) {
            r.run();
            return;
        }
        final Throwable[] err = new Throwable[1];
        final CountDownLatch latch = new CountDownLatch(1);
        mBg.post(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                err[0] = t;
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (err[0] != null) throw asRuntime(err[0]);
    }

    @SuppressWarnings("unchecked")
    private static RuntimeException asRuntime(Throwable t) {
        if (t instanceof RuntimeException) return (RuntimeException) t;
        return new RuntimeException(t);
    }
}
