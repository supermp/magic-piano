package com.magicpig.piano;

import android.media.AudioFormat;
import android.media.AudioRecord;
import java.util.concurrent.atomic.AtomicInteger;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.util.Log;

import com.mangofuture.algo.SfeInterface;

/**
 * UFP（Uninterruptible Follow）演奏控制器 —— **原版 App 用的那条通路**。
 *
 * 下面这张对照表是历史的：旧的 magicPiano 通路（原 {@code MagicTrial} 类）
 * 已整体删除，留表只为说明"为什么没有逐音实时反馈"。
 * <pre>
 *            | magicPiano（旧，已删）        | UFP（本类，原版走的）
 * 建会话     | magicPianoInitModule          | ufpCreateMP + preloadTfModels(highPerf)
 * 起点       | magicPianoSetNoteSequence     | ufpSetCustomRangeWithResult（0=成功）
 * 喂音       | magicPianoFeedAudio           | ufpFeedAudio(pcm, len, status)
 * 过程反馈   | jsonStr 带 heads/bias（逐音） | status 只有光标/容错窗口/score（**没有逐音**）
 * 结束       | magicPianoGetFollowStatistics | ufpFinish（evaJson 带原生 rating）
 * </pre>
 *
 * 关键前提：ufpCreateMP 之前**必须** preloadTfModels(true, true)
 * 否则返回 4（"no high-performance realtime model loaded"）—— 当初就是被这个 4
 * 误判成"UFP 不可用"。
 */
public final class UfpTrial {

    private static final String TAG = "UfpTrial";
    private static final int CHUNK = 2048;
    /**
     * 本次会话喂进来的音频的**峰值幅度**（0..32767）。
     * 用途：按停时判断"到底有没有弹"。峰值接近 0 = 全程静音 → 评分结果必然是空的，
     * 可以直接跳过 `ufpFinish`（T618 实测 3.5 秒）。
     * ⚠ 不用"光标帧数"判断：弹错音时引擎不推进光标，但错音标记恰恰是用户要看的，不能跳。
     */
    private static volatile int sPeakLevel;
    /** 末小节终点探测上限：offset 试到被 native 拒绝为止，最多试这么多个 */
    private static final int MAX_END_PROBE_OFFSET = 32;

    /**
     * 判断"AudioRecord 缓冲里已经积压了旧数据"用的门限。
     *
     * 正常情况 `read(2048)` 会**阻塞到数据够**，耗时 ≈ 2048/44100 ≈ 46ms；
     * 一旦缓冲里已经压着数据，read 会**立刻返回**（几毫秒）。所以：
     *   read 明显快于实时 + 连续多次 = 真的积压 → 丢掉这一块往前追。
     *
     * ⚠ 别用"墙钟 − 已消费音频时长"来算延迟：
     * `startRecording()` 后第一块要等 HAL 启动（实测 326ms），这个一次性开销会把
     * 计算出的延迟永久抬高到阈值以上 —— 结果每一块都被判成"落后"、全部丢弃，
     * 引擎一块音频都收不到（日志 `已喂 0、丢弃 N`）。HAL 启动延迟不是积压。
     */
    private static final int FAST_READ_MS = 20;
    /** 连续几次"立刻返回"才认定积压（单次快速返回可能只是调度抖动） */
    private static final int FAST_STREAK = 3;
    private static final int FEED_RATE = 44100;

    private static volatile boolean mRunning;
    private static volatile Thread mFeedThread;
    private static volatile TrialListener sListener;

    /**
     * 会话代数：每次 start / stop / abandon 都推进一位。
     *
     * 为什么需要：建 UFP 会话是**秒级同步过程**（装模型 + ufpCreateMP）。用户完全
     * 可能在这个窗口里点返回、甚至重进曲谱页再点一次演奏 —— 那时旧线程还在建，
     * 建完就会去写 mRunning / sListener / mFeedThread 这些**静态**状态，把新页面
     * 的会话踩掉；反过来新页面 start 撞上它还会直接收到"已经在演奏中"，界面复位
     * 了但演奏态标记还挂着（表现就是“试听/选段可点但预览里还画着演奏光标”）。
     * 有了代数，迟到的 start 建完先对一下号，对不上就静默放弃，一个静态字段都不碰。
     */
    private static final AtomicInteger sGen = new AtomicInteger();
    /**
     * 当前**活着的** native UFP handler 属于哪一代；-1 表示没有 handler。
     *
     * 为什么不能再用 {@link #sGen} 判断收尾归属：
     *   · sGen 是"会话代数"，`start()` 一抢到号它就变了，但那一刻 handler 还是
     *     上一轮的 —— 按 sGen 判会把"该释放的旧 handler"当成"别人的"跳过；
     *   · 反过来若新会话已经 `ufpCreateMP` 成功，按 sGen 判又会漏掉这层保护。
     * 只有这个字段真正代表 native 句柄的归属：create 成功写 myGen，任何一次
     * release 之后写 -1。收尾任务用它判断"这个 handler 是不是我的"。
     */
    private static volatile int sHandlerGen = -1;
    /** 静态会话状态的检查与写入必须在同一把锁里完成，防止旧收尾清掉新会话。 */
    private static final Object sLock = new Object();
    private static volatile AudioRecord mRec;

    /** 自喂时把音频升高 N 个半音（调试：制造错音） */
    private static int sDetune = 0;
    /** 从第几块开始变调（0 = 全程变调）；用来做"前半弹对、后半弹错"的演示 */
    private static int sDetuneAfter = 0;

    public static void setDetune(int semitones) {
        sDetune = semitones;
    }

    /** setDetune 的补充：从第 afterChunk 块开始才变调。 */
    public static void setDetuneAfter(int afterChunk) {
        sDetuneAfter = afterChunk;
    }

    /** 本次会话的输入峰值（0..32767），见 {@link #sPeakLevel}。 */
    public static int peakLevel() {
        return sPeakLevel;
    }

    public static boolean isRunning() {
        return mRunning;
    }

    /**
     * 本轮会话是否仍然有效：全局运行标志 + 代数仍属于本轮。
     *
     * ⚠ feed 线程只认自己那一代。旧线程被 abandon 后，新 start 会把
     * mRunning 重新置 true；只看 mRunning 会让旧线程继续往全局 UFP handler
     * 喂音，并在 finally 里 stop() 掉新会话（sGen 递增 → 新会话当场作废）。
     * 循环条件、喂音前检查、收尾判断都必须走这里，不能裸读 mRunning。
     */
    private static boolean alive(int myGen) {
        return mRunning && myGen == sGen.get();
    }

    /**
     * 开始一次 UFP 演奏。
     *
     * @param start 0-based {measure, voice, offset}；没有音符级起点就传 {m,0,0}
     * @param end   终点（同样 0-based）
     * @param selfFeed true = 用本机合成的 PCM 当麦克风（调试/演示）；
     *                 false = 真麦克风
     */
    public static boolean start(String encPath,
                                int handType, int[] start, int[] end,
                                boolean selfFeed, boolean feedSilence,
                                TrialListener listener) {
        // 检查必须和后面的写入在同一把锁里：两个 start 并发时都看到
        // mRunning=false 会各自抢代数、互相覆盖 sListener。
        boolean duplicate = false, stale = false;
        synchronized (sLock) {
            if (mRunning) {
                if (sListener == listener && mFeedThread != null && mFeedThread.isAlive()) {
                    duplicate = true;
                } else {
                    stale = true;
                }
            }
        }
        if (duplicate) {
            // 同一个页面重复点：才是真的"已经在演奏中"
            listener.onError("演奏已经在运行", "duplicate start");
            return false;
        }
        if (stale) {
            // 标记活着但**不属于本页面**：上一个页面遗留的会话（它 onStop 的
            // abandon 没赶上这一轮建完），或者线程已死的历史残留。
            // 一律先丢弃再重建 —— 否则新页面会直接失败：界面复位了，但 startTrial
            // 开头已经把演奏态打开（整小节高亮 + 演奏光标），于是「能点试听/选段 + 两道光标」。
            Log.w(TAG, "发现非本页面的残留会话，先丢弃再重建");
            abandon();
        }
        // 抢到本轮代数：之后任何 abandon / stop / 新 start 都会让它变，
        // 建会话结束时对不上号就说明这轮已经作废。
        final int myGen;
        synchronized (sLock) {
            myGen = sGen.incrementAndGet();
            sListener = listener;
            mRunning = true;
        }

        // ---- 建会话（引擎线程，同步等待）----
        final StringBuilder err = new StringBuilder();
        final int[] created = {1};
        final java.util.List<short[]> pre = new java.util.ArrayList<>();
        final long tSession = SystemClock.elapsedRealtime();
        AlgoEngine.get().runOnEngine(() -> {
            if (myGen != sGen.get()) {
                // 排队期间就被作废了（页面离开 / 新页面抢占）：这一步要装 84MB
                // 模型，白跑几秒还会把 native 搞得一团，直接退出。
                created[0] = -2;
                return;
            }
            try {
                // 自喂要的 PCM 必须**在释放预览模块之前**合成好
                // （synthesizeAll 用的就是预览模块，UFP 建会话时会把它释放）
                if (selfFeed && !feedSilence) {
                    long t = SystemClock.elapsedRealtime();
                    // 自喂也要**从演奏起点开始合成**：起点在末段时，从曲首合成长曲
                    // 要几十秒才轮到该段，喂进去的音还和引擎区间对不上。
                    java.util.List<short[]> bl = AlgoEngine.get().synthesizeAll(
                            start[0], start[1], start[2]);
                    if (bl != null) pre.addAll(bl);
                    Log.i(TAG, "自喂 PCM 合成完成 " + (SystemClock.elapsedRealtime() - t)
                            + "ms，块数 " + pre.size());
                }
                SfeInterface.releasePreviewModule();
                // 必须走高性能档，否则 ufpCreateMP 直接返回 4
                SfeInterface.preloadTfModels(true, true);
                // ⚠ 建会话前先兜底释放遗留的 UFP handler。
                // 上一轮的收尾任务可能因"已有新会话接管"被跳过，handler 一直挂在
                // native 全局单例里；这里 create 会直接把它盖掉 → 泄漏。
                // sHandlerGen 是**handler 归属的代**（-1 = 没有活 handler），
                // 只有它才代表 native 真实状态，别拿 sGen 判断（sGen 是会话代数，
                // 抢到代数 ≠ 已经建过 handler）。
                if (sHandlerGen >= 0) {
                    Log.w(TAG, "建会话前发现遗留 UFP handler（gen " + sHandlerGen + "），先补释放");
                    try {
                        SfeInterface.releaseUfpHandler();
                    } catch (Throwable ignored) {
                    }
                    synchronized (sLock) {
                        sHandlerGen = -1;
                    }
                }
                int c = SfeInterface.ufpCreateMP(handType, encPath, false, "{}", "{}", true);
                if (c != 0) {
                    err.append("ufpCreateMP 返回 ").append(c);
                    created[0] = c;
                    return;
                }
                synchronized (sLock) {
                    sHandlerGen = myGen;   // 这个 handler 归本轮
                }
                SfeInterface.ufpPreInitializeMLModels(true);

                String sJson = pos(start);
                String eJson = applyRange(sJson, start, end);
                SfeInterface.NotePosition p0 = SfeInterface.ufpGetFirstValidPosition();
                Log.i(TAG, "firstValid=" + fmt(p0));
                created[0] = 0;   // 建会话成功（之前漏了这一句，导致永远判失败）
            } catch (Throwable t) {
                err.append(String.valueOf(t));
                created[0] = -1;
            } finally {
                // 取消/失败也在引擎队列内收尾：回到调用线程再碰 native 会与新试听竞态。
                if (created[0] != 0 || myGen != sGen.get()) {
                    if (sHandlerGen == myGen) {
                        try { SfeInterface.releaseUfpHandler(); }
                        catch (Throwable t) { Log.w(TAG, "建会话收尾释放失败", t); }
                        sHandlerGen = -1;
                    }
                    try {
                        SfeInterface.initPreviewModule(AlgoEngine.get().getSheetXml());
                    } catch (Throwable t) {
                        Log.w(TAG, "建会话收尾恢复预览失败", t);
                    }
                }
            }
        });

        if (myGen != sGen.get()) {
            // 这轮已被作废：建会话任务的 finally 已按 handler 归属收尾；
            // 若作废发生在任务结束之后，则由 abandon() 清理仍在 native 里的句柄。
            // 这里绝对不能碰 mRunning / sListener / mFeedThread —— 它们现在可能
            // 已经是新会话的了，一碰就把新页面搞死。
            Log.w(TAG, "建会话期间被作废（gen " + myGen + " -> " + sGen.get()
                    + "），丢弃本轮，不动任何静态状态");
            return false;
        }
        if (created[0] != 0) {
            synchronized (sLock) {
                // 失败回报也可能迟到；若已有新会话，旧轮一个静态字段都不能碰。
                if (myGen != sGen.get()) return false;
                mRunning = false;
                sListener = null;
            }
            // native 收尾已在引擎任务的 finally 里完成；这里不能再直接调 native。
            // reason 只给一句人话；引擎返回码/异常原文放 detail，只进 logcat
            listener.onError("这首曲子暂时开不了演奏", err.toString());
            return false;
        }

        Log.i(TAG, "UFP 建会话耗时 " + (SystemClock.elapsedRealtime() - tSession) + "ms");
        sPeakLevel = 0;   // 新一轮会话重新统计输入峰值（按停时判"有没有弹"）
        Thread feed = new Thread(() -> feedLoop(selfFeed, feedSilence,
                        pre, listener, myGen), "ufp-feed");
        synchronized (sLock) {
            if (myGen != sGen.get()) return false;
            listener.onLocked();
            mFeedThread = feed;
            // 在锁内启动：abandon 一旦拿到线程引用，它就一定是可 join 的活线程。
            feed.start();
        }
        return true;
    }

    /** 用户主动停止（无参重载，供 PlayerActivity 统一调用）。 */
    public static void stop() {
        TrialListener listener = sListener;
        if (listener != null) stop(listener, true);
    }

    /** 停止并取成绩。 */
    public static void stop(final TrialListener listener, final boolean userStopped) {
        stop(listener, userStopped, false);
    }

    /**
     * @param skipScoring 跳过 `ufpFinish`（不算分），只做释放与预览模块恢复。
     *                    用于"刚开跑就按停、一个音都没判到"的场景：T618 实测
     *                    `ufpFinish` 要 **3.5 秒**，而结果本来就是空的（score=0）。
     */
    public static void stop(final TrialListener listener, final boolean userStopped,
                            final boolean skipScoring) {
        // 只停本轮自己的会话：listener 已被新会话换掉时，旧页面的按停
        // 不能递增代数把新会话作废，更不能 stop/release 掉它的麦克风。
        final int myGen;
        /** 停止这一刻，native UFP handler 属于哪一代（-1 = 没有）。 */
        final int handlerGen;
        final AudioRecord rec;
        final Thread feed;
        synchronized (sLock) {
            if (sListener != listener) {
                Log.w(TAG, "stop：listener 已不属于当前会话，忽略");
                return;
            }
            if (!mRunning) {
                // 本轮已经结束（自然播完已经 stop 过一次）：**绝不能**再递增代数。
                // 否则上一轮正在跑的 native 收尾会因 `myGen != sGen.get()` 被跳过
                // → 没有成绩卡，ufpFinish / releaseUfpHandler 全丢（native 泄漏）。
                // 用户"按停后立刻返回"就会踩到（onStop 的 abandon 也是同一条路）。
                Log.i(TAG, "stop：本轮已结束，忽略（不递增代数）");
                return;
            }
            myGen = sGen.incrementAndGet();
            // ⚠ 必须在这里快照：handler 是**上一轮 start 建的那一个**，它的代数
            // 与 myGen（stop 自己刚 ++ 出来的号）永远不相等，不能直接拿 myGen 比。
            handlerGen = sHandlerGen;
            mRunning = false;
            // 快照到局部变量并立刻摘掉静态引用：之后即便新 start 写入新的
            // mRec / mFeedThread，这里的清理也只碰本轮那一份。
            rec = mRec;
            feed = mFeedThread;
            mRec = null;
            mFeedThread = null;
        }
        if (rec != null) {
            try {
                rec.stop();
            } catch (Throwable ignored) {
            }
            try {
                rec.release();
            } catch (Throwable ignored) {
            }
        }
        // 等喂音线程退出再 finish（避免和 native feed 并发）。
        // 自然结束时 stop 由 feed 线程自己调用，不能 join 自己（白等 800ms）。
        if (feed != null && feed != Thread.currentThread()) {
            try {
                feed.join(400);   // 喂音线程每块都会检查 alive()，正常 50ms 内退出
            } catch (InterruptedException ignored) {
            }
            // 2a：超时还没退出 = 它正卡在 read 或 ufpFeedAudio 里，下面排的
            // ufpFinish/releaseUfpHandler 会与它并发碰 native（无锁 JNI 调用）。
            // 线程杀不掉，只能留痕：真出现异常日志时先看这一行。
            if (feed.isAlive()) {
                Log.w(TAG, "stop：喂音线程 400ms 未退出，native 收尾可能与喂音并发");
            }
        }
        // 结算要跑好几秒（ufpFinish）。这里**不再整段 return**：
        // 原来一旦发现"已有新会话接管"就直接 return，结果是本轮的 native 句柄
        // 永远没人释放（新 start 直接 create 覆盖），而且连成绩也拿不到。
        // 现在只按 **handler 归属**决定能不能动 native；成绩回调照旧走完。
        final boolean handlerMine = handlerGen >= 0 && sHandlerGen == handlerGen;
        if (!handlerMine) {
            Log.w(TAG, "stop：本轮没有活 handler（快照 gen " + handlerGen + "，当前 "
                    + sHandlerGen + "），跳过 native 收尾，仍照常出分");
        }

        final SfeInterface.UfpResult res = new SfeInterface.UfpResult();
        // ⚠ 结算等待里只放**必须拿到结果**的两步：
        //   ufpFinish(res) 要写回评测 JSON，releaseUfpHandler 必须在下一次
        //   initPreviewModule 之前完成（预览模块与 UFP handler 全局互斥）。
        //   initPreviewModule 原先也塞在这个同步块里，等于让用户多等一遍
        //   "重新加载整份曲谱"，而成绩卡/红叉根本不需要它 —— 实测按停结算
        //   短弹 4.2s、长弹 14.7s，其中有一截是白等的。
        // 改成 post（不 await）：引擎队列是串行的，后续任何试听/native 调用
        // 都会排在它之后，所以不会"试听时预览模块还没好"。
        // 只有**要分数**时才阻塞等 ufpFinish；跳过时整个 native 收尾都丢到引擎队列。
        // ⚠ releaseUfpHandler 不再和 ufpFinish 绑在一起等：跳过评分后它自己要
        //   1.5 秒（ufpFinish 在时会顺带清掉大部分状态），而成绩卡根本不需要它。
        if (!skipScoring && handlerMine) {
            AlgoEngine.get().runOnEngine(() -> {
                if (sHandlerGen != handlerGen) {
                    Log.w(TAG, "ufpFinish 排队期间 handler 已被换掉，跳过算分（gen "
                            + handlerGen + "）");
                    return;
                }
                long t0 = SystemClock.elapsedRealtime();
                try {
                    SfeInterface.ufpFinish(res);
                } catch (Throwable t) {
                    Log.e(TAG, "ufpFinish 失败", t);
                }
                Log.i(TAG, "结算耗时：ufpFinish " + (SystemClock.elapsedRealtime() - t0) + "ms");
            });
        } else if (skipScoring) {
            Log.i(TAG, "跳过 ufpFinish（本次没有判定数据，直接释放）");
        }
        // 释放 + 预览恢复都放到引擎队列（串行）：顺序有保证，又不用等它跑完才出分。
        // ⚠ 必须用 postAsync：post() 里 await 了 latch，等于同步，会把按停线程卡住。
        AlgoEngine.get().postAsync(() -> {
            // 判断依据是 **handler 归属**，不是会话代数：抢到新代数不代表已经
            // create 过新 handler；没有 handler（gen < 0）也不能执行收尾。
            if (handlerGen < 0 || sHandlerGen != handlerGen) {
                Log.w(TAG, "stop：收尾时 handler 不可用（快照 gen " + handlerGen
                        + "，当前 " + sHandlerGen + "），跳过 native 收尾");
                return;
            }
            long t0 = SystemClock.elapsedRealtime();
            try {
                SfeInterface.releaseUfpHandler();
            } catch (Throwable ignored) {
            }
            synchronized (sLock) {
                sHandlerGen = -1;
            }
            long t1 = SystemClock.elapsedRealtime();
            try {
                SfeInterface.initPreviewModule(AlgoEngine.get().getSheetXml());
            } catch (Throwable ignored) {
            }
            Log.i(TAG, "native 收尾：releaseUfpHandler " + (t1 - t0) + "ms，initPreviewModule "
                    + (SystemClock.elapsedRealtime() - t1) + "ms（异步，不占结算等待）");
        });

        EvalRating rating = EvalRating.parse(res.evaJson);
        int score = rating != null ? rating.total : 0;
        Log.i(TAG, "结束 userStopped=" + userStopped + " score=" + score
                + " rating=" + (rating == null ? "null"
                : (rating.pitch + "/" + rating.rhythm + "/" + rating.speed + "/" + rating.total)));
        Log.i(TAG, "metrics=" + res.metricsJson);
        listener.onFinished(res.evaJson, res.metricsJson, res.vexflowJson,
                score, userStopped);
        synchronized (sLock) {
            if (myGen == sGen.get() && sListener == listener) {
                // 只有当前这一代才能清静态引用；旧会话的 ufpFinish 可能晚于新会话开始。
                sListener = null;
            }
        }
    }

    /**
     * 丢弃一个**遗留会话**：停线程、释放 native、恢复预览模块，但**不回调任何 UI**。
     *
     * 演奏是静态状态（mRunning），而页面可能在演奏中被返回键 / home 干掉。
     * 以前没有任何收尾，会话就永远留在 mRunning=true、麦克风线程还活着，
     * 用户再点「演奏」只会得到一句"已经在演奏中"，自己也没法恢复。
     */
    public static void abandon() {
        abandon(null);
    }

    /** owner 非空时只丢弃该页面自己的会话，避免旧页面 onStop 停掉新页面的演奏。 */
    public static void abandon(TrialListener owner) {
        final int myGen;
        /** 丢弃这一刻，native UFP handler 属于哪一代（-1 = 没有）。 */
        final int handlerGen;
        final AudioRecord rec;
        final Thread feed;
        final boolean hadNative;
        final boolean liveSession;
        synchronized (sLock) {
            if (owner != null && sListener != owner) return;
            // ⚠ 只有**真有活会话**（运行中 / 正在建）才推进代数去作废它。
            // 结算收尾在途时 mRunning 已经是 false，这时再 ++ 会把 stop() 已经排进
            // 引擎队列的 releaseUfpHandler / initPreviewModule 一并作废 —— 一跳过就
            // 没人补释放，UFP handler 和预览模块都留在"被 UFP 占用"的状态。
            // 用户"按停后立刻返回"必踩：onStop 的 wasTrial 是 false 不会 abandon，
            // 但 onDestroy 是无条件的。修。
            liveSession = mRunning;
            myGen = liveSession ? sGen.incrementAndGet() : sGen.get();
            handlerGen = sHandlerGen;
            // ⚠ 清静态引用必须在锁内、且赶在 join 之前。原来放在方法末尾，
            // join(600) 这段时间里新 start 写好的 listener 会被旧 abandon 清掉，
            // 新会话按停时 stop() 读到 null 直接不做事。
            sListener = null;
            // 只有真的跑过 UFP（handler 还活着）才需要收 native；建会话在途 /
            // 失败残留不碰全局单例，否则可能打断正在播的试听（预览模块是同一个）。
            hadNative = liveSession && handlerGen >= 0;
            mRunning = false;
            // 快照到局部变量：新 start 之后写入的 mRec / mFeedThread 不受影响
            rec = mRec;
            feed = mFeedThread;
            mRec = null;
            mFeedThread = null;
        }
        // 即使没在跑也按引用释放残留的录音/线程引用：原来 !mRunning 直接
        // return，失败残留的 mRec 会一直留到下一次会话。
        if (rec != null) {
            try {
                rec.stop();
            } catch (Throwable ignored) {
            }
            try {
                rec.release();
            } catch (Throwable ignored) {
            }
        }
        if (feed != null && feed != Thread.currentThread()) {
            try {
                feed.join(600);
            } catch (InterruptedException ignored) {
            }
            // 2a：join 超时说明喂音线程还卡在 read/ufpFeedAudio 里。下面排的
            // releaseUfpHandler 会跟它并发碰 native，只能告警（不能强杀线程）。
            if (feed.isAlive()) {
                Log.w(TAG, "abandon：喂音线程 600ms 未退出，native 收尾可能与喂音并发");
            }
        }
        if (hadNative) {
            // 用 postAsync 而不是 runOnEngine/post：abandon 在 onStop（主线程）里调用，
            // runOnEngine 会 await（最长 20s）；post 内部同样 await latch，等于同步 ——
            // 释放要 1.5~3 秒，按返回键会明显卡一下。postAsync 只排队不等待。
            // 引擎队列是串行的，排在这里的清理一定会排在后面的 start 之前执行。
            // ⚠ 归属判断用 handler 代数（快照值），不用会话代数：abandon 自己会 ++sGen，
            // 拿 myGen 比只会永远不相等。
            AlgoEngine.get().postAsync(() -> {
                if (sHandlerGen != handlerGen) {
                    Log.w(TAG, "abandon：收尾时 handler 已被换掉（快照 gen " + handlerGen
                            + "，当前 " + sHandlerGen + "），跳过 native 收尾");
                    return;
                }
                long t0 = SystemClock.elapsedRealtime();
                try {
                    SfeInterface.releaseUfpHandler();
                } catch (Throwable ignored) {
                }
                synchronized (sLock) {
                    sHandlerGen = -1;
                }
                try {
                    SfeInterface.initPreviewModule(AlgoEngine.get().getSheetXml());
                } catch (Throwable ignored) {
                }
                Log.i(TAG, "abandon：native 收尾完成（" + (SystemClock.elapsedRealtime() - t0)
                        + "ms）");
            });
        }
        // 两条路径分开打：只有真作废会话时 myGen 才是"本轮新号"，
        // 无活会话时它只是当前代数（可能已属于新会话），别拿它当本轮标识。
        Log.i(TAG, liveSession
                ? "已丢弃遗留演奏会话（活会话作废，gen " + myGen + "）"
                : "abandon：无活会话，只解绑 listener/引用，不作废在途收尾（handler gen "
                        + handlerGen + "）");
    }

    // ---------------- 喂音 ----------------

    private static void feedLoop(boolean selfFeed, boolean feedSilence,
                                 java.util.List<short[]> pre,
                                 TrialListener listener, int myGen) {
        try {
            if (!alive(myGen)) return;
            if (selfFeed) {
                selfFeed(pre, feedSilence, listener, myGen);
            } else {
                micFeed(listener, myGen);
            }
        } catch (Throwable t) {
            Log.e(TAG, "喂音异常", t);
            if (alive(myGen)) {
                String detail = t.getMessage() == null ? String.valueOf(t) : t.getMessage();
                // 失败也必须走完整收尾：释放 UFP handler、恢复预览模块并清静态 listener。
                abandon(listener);
                // 标题已经是「演奏中断」，提示行别再重复一遍，也不带异常原文
                listener.onError("演奏被打断", detail);
            }
        } finally {
            // ⚠ 不要在这里清 mRec：它可能已经属于新会话。麦克风由 micFeed
            // 自己的 finally 按引用释放，stop/abandon 也只清本轮快照。
            // 真麦克风自然弹到终点时，step 只是退出喂音循环；必须在这里补
            // stop(listener,false)，否则 ufpFinish/成绩卡/恢复预览都不会发生。
            // 只有本轮仍有效才收尾：旧线程被作废后新会话会把 mRunning 重新
            // 置 true，裸判 mRunning 会 stop() 掉新会话（代数递增 → 当场作废）。
            if (alive(myGen)) stop(listener, false);
        }
    }

    /** 自喂：把本机合成的 PCM 当麦克风（调试/演示用）。 */
    private static void selfFeed(java.util.List<short[]> blocks, boolean silence,
                                 TrialListener listener, int myGen) {
        // 静音自喂：合成被跳过，这里造一段全零 PCM，
        // 用来验证「光标是跟着听到的音频走，而不是按时间轴推进」。
        if (blocks == null || blocks.isEmpty()) {
            if (silence) {
                // 静音自喂：合成被跳过（没意义），这里直接造一段全零 PCM，
                // 用来验证"光标是跟着听到的音频走，不是按时间轴推进"。
                java.util.ArrayList<short[]> zero = new java.util.ArrayList<>();
                for (int i = 0; i < 900; i++) zero.add(new short[4096]);   // ≈41 秒
                blocks = zero;
                Log.i(TAG, "静音自喂：造 " + zero.size() + " 块全零 PCM");
            } else {
                throw new IllegalStateException("本机合成失败，无法自喂");
            }
        }
        if (!silence) sPeakLevel = 32767;   // 自喂的是合成音频：肯定算"弹过"
        int fill = 0;
        short[] chunk = new short[CHUNK];
        int chunks = 0;
        SfeInterface.UninterruptibleFollowStatus st = new SfeInterface.UninterruptibleFollowStatus();
        outer:
        for (short[] b : blocks) {
            for (int k = 0; k + 1 < b.length; k += 2) {
                if (!alive(myGen)) break outer;
                short v = silence ? 0 : b[k];   // 隔点取单声道
                chunk[fill++] = v;
                if (fill < CHUNK) continue;
                fill = 0;
                short[] feed = chunk;
                // 到点之后才开始"弹错"：演示用，前半段真弹、后半段升调
                if (sDetune != 0 && chunks >= sDetuneAfter) {
                    feed = resample(chunk, Math.pow(2.0, sDetune / 12.0));
                }
                if (!step(feed, st, listener, myGen)) break outer;
                chunks++;
                try {
                    Thread.sleep(46);   // 2048 @44.1k ≈ 46ms
                } catch (InterruptedException ignored) {
                }
            }
        }
        if (fill > 0 && alive(myGen)) {
            step(java.util.Arrays.copyOf(chunk, fill), st, listener, myGen);
        }
        Log.i(TAG, "自喂结束，共 " + chunks + " 块");
        if (alive(myGen)) {
            // 注意别先把 mRunning 置 false —— stop() 开头就是 if (!mRunning) return，
            // 先置 false 会让收尾（ufpFinish / 评分 / 恢复预览）整个被跳过，
            // 表现就是"演奏一直停在演奏中、没有成绩"。
            stop(listener, false);   // 自然走完 → 取成绩
        }
    }

    /** 真麦克风。 */
    private static void micFeed(TrialListener listener, int myGen) {
        if (!alive(myGen)) return;
        int min = AudioRecord.getMinBufferSize(FEED_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        // 低延迟：原来留 4 个 CHUNK（≈186ms）的缓冲，读满一整块才喂 native，
        // 加上系统缓冲就是"按下琴键到光标动"慢半拍的一部分。这里收到 2 个 CHUNK
        // （≈93ms），仍不低于系统要求的最小值，避免 underrun。
        int bufBytes = Math.max(min, CHUNK * 2 * 2);
        // 音源用 VOICE_RECOGNITION 而不是 MIC：MIC 在部分 ROM 上会
        // 挂语音降噪/AGC，把钢琴的基频削掉 —— T618 上实测"能听到音但音高全判错"
        // （rating pitch=17 / 0，而同一台机器直接喂 PCM 的自喂是 pitch=100）。
        // VOICE_RECOGNITION 通常关掉这些增强、保留较原始信号；打不开时下面的
        // catch 会退回系统最小缓冲（仍是同一音源）。UNPROCESSED 在部分 ROM 上
        // read 恒返回 0，不要换过去。
        // ⚠ 用局部变量持有**本会话**的 recorder：静态 mRec 可能已被新会话换掉，
        // 旧线程既不能靠 mRec 读数据，也不能在退出时清掉别人的 mRec。
        AudioRecord rec = null;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, FEED_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes);
        } catch (Throwable t) {
            Log.w(TAG, "麦克风打不开（小缓冲）。改用系统最小缓冲再试：" + t);
            try {
                rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, FEED_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min);
            } catch (Throwable ignored) {
            }
        }
        if (rec == null) {
            throw new IllegalStateException("麦克风打不开（被占用或系统拒绝）");
        }
        try {
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("麦克风初始化失败（被占用或系统拒绝）");
            }
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
            rec.startRecording();
            // 3 = RECORDSTATE_RECORDING。启动失败时某些 ROM 只在这里看得出来，
            // 表现为 read 永远返回 0 / 永远凑不满一块。
            // 实际采样率：设备不支持 44100 时 HAL 可能给别的速率；我们固定按 44100
            // 喂给 UFP，速率不符会把音高整体判错（pitch 接近 0、光标不推进）。
            Log.i(TAG, "实际采样率 " + rec.getSampleRate() + "（请求 " + FEED_RATE
                    + "），声道 " + rec.getChannelCount()
                    + "，格式 " + rec.getAudioFormat());
            Log.i(TAG, "AudioRecord 启动后状态 " + rec.getRecordingState()
                    + "（3=正在录音），buffer=" + bufBytes + "B，minBuffer=" + min + "B");
            final int actualRate = rec.getSampleRate();
            if (actualRate > 0 && actualRate != FEED_RATE) {
                // 明确报了别的速率才失败：按错误速率喂音会把所有音判错。
                // ⚠ getSampleRate() 返回的是 AudioRecord 的**配置速率**，部分 ROM
                // 在 HAL 层做 SRC 后仍报 44100 —— 这个检查只能挡住"明确报出别的
                // 速率"的设备，不能证明数据一定是 44.1k。要覆盖全部设备得做连续流
                // 重采样；逐块独立重采样再补零会引入咔哒声，不要那么做。
                throw new IllegalStateException("设备实际录音采样率为 " + actualRate
                        + "Hz，演奏判定需要 " + FEED_RATE + "Hz");
            }
            if (actualRate <= 0) {
                Log.w(TAG, "getSampleRate() 返回 " + actualRate
                        + "，无法确认采样率，按请求的 " + FEED_RATE + "Hz 继续");
            }
            // 建好之后若本轮已被作废（用户返回重进），不要发布到静态 mRec，
            // 免得覆盖新会话的 recorder；finally 会释放这一份。
            //
            // ⚠ 发布必须在锁内复校一次代数：只做锁外 `alive()` 的话
            // 检查通过到写 mRec 之间新会话完全可能已经发布了自己的 recorder ——
            // 旧线程把 mRec 写成旧值、随后 finally 的 `mRec == rec` 又把它置 null，
            // 新会话的 AudioRecord 就彻底失去引用：stop() 快照拿不到它 → 麦克风
            // 不 release、下次录音打不开。锁内复校才能把这段窗口关掉。
            synchronized (sLock) {
                if (!alive(myGen)) return;
                mRec = rec;
            }
            short[] buf = new short[CHUNK];
            SfeInterface.UninterruptibleFollowStatus st =
                    new SfeInterface.UninterruptibleFollowStatus();
            long feedSum = 0, feedMax = 0;
            int feeds = 0, dropped = 0;
            int loops = 0;
            int zeroReads = 0;
            long readSum = 0;
            int fastStreak = 0;
            while (alive(myGen)) {
                long readT0 = SystemClock.elapsedRealtime();
                int fill = 0;
                while (fill < CHUNK && alive(myGen)) {
                    // 显式 READ_BLOCKING：阻塞到读满请求量（3 参版理论上等价，
                    // 但真机上遇到过它立刻返回 0 的情况，写清楚不留歧义）。
                    int n = rec.read(buf, fill, CHUNK - fill, AudioRecord.READ_BLOCKING);
                    if (n <= 0) {
                        // 没有麦克风 / 被占用时 read 会**立刻**返回 0：原来直接 break，
                        // 外层 while 马上再来一次 → 满核空转（模拟器上实测把 CPU 打满）。
                        if (n < 0) Log.w(TAG, "AudioRecord.read=" + n);
                        if (zeroReads++ < 20) {
                            Log.i(TAG, "read 返回 " + n + "（第 " + zeroReads + " 次，fill="
                                    + fill + "，recState=" + rec.getRecordingState() + "）");
                        }
                        try {
                            Thread.sleep(5);
                        } catch (InterruptedException ignored) {
                        }
                        if (n < 0) break;
                        continue;
                    }
                    fill += n;
                }
                if (fill < CHUNK) continue;
                // 峰值：按停时判断"有没有弹过"（2048 次比较 / 46ms，可忽略）
                int pk = 0;
                for (int k = 0; k < CHUNK; k++) {
                    int a = buf[k] < 0 ? -buf[k] : buf[k];
                    if (a > pk) pk = a;
                }
                if (pk > sPeakLevel) sPeakLevel = pk;
                long readMs = SystemClock.elapsedRealtime() - readT0;
                readSum += readMs;
                loops++;
                // 积压判定：read 明显快于实时（正常情况下要阻塞 ~46ms）
                fastStreak = readMs < FAST_READ_MS ? fastStreak + 1 : 0;
                long feedMs = 0;
                boolean cont = true;
                if (fastStreak >= FAST_STREAK) {
                    // 缓冲里已经压着旧数据了：丢掉这块，直接追最新的（见 FAST_READ_MS）
                    fastStreak = 0;
                    dropped++;
                } else {
                    long t0 = SystemClock.elapsedRealtime();
                    cont = step(buf, st, listener, myGen);
                    feedMs = SystemClock.elapsedRealtime() - t0;
                    feedSum += feedMs;
                    if (feedMs > feedMax) feedMax = feedMs;
                    feeds++;
                }
                // 前 5 轮和每 100 轮各打一行：这样"一直在丢块、feeds 不涨"的
                // 死循环也能看出来（只按 feeds 取模的话永远不打印）。
                if (loops % 300 == 0) {
                    // 采集链路延迟：getTimestamp 给的是"最后一帧被麦克风采到"的时刻，
                    // now - nanoTime 就是「声音进麦克风 → 我们拿到它」的耗时（HAL 缓冲）。
                    android.media.AudioTimestamp ats = new android.media.AudioTimestamp();
                    // getTimestamp 返回 int（AudioRecord.SUCCESS==0），不是 boolean
                    boolean ok = rec.getTimestamp(ats,
                            android.media.AudioTimestamp.TIMEBASE_MONOTONIC)
                            == android.media.AudioRecord.SUCCESS;
                    long capLag = ok ? (System.nanoTime() - ats.nanoTime) / 1_000_000L : -1;
                    // 输入电平：太小说明麦克风增益/权限问题，削顶说明过载，都会让音高识别失准
                    long sq = 0;
                    for (int k = 0; k < buf.length; k++) sq += (long) buf[k] * buf[k];
                    int rms = (int) Math.sqrt((double) sq / buf.length);
                    Log.i(TAG, "采集延迟 " + capLag + "ms（时间戳" + (ok ? "可用" : "不可用")
                            + "），输入电平 RMS=" + rms + "/32767，已喂 " + feeds);
                }
                if (loops <= 5 || loops % 300 == 0) {
                    Log.i(TAG, "喂音循环#" + loops + "：read " + readMs + "ms（累计均 "
                            + (readSum / loops) + "ms）、本轮 feed " + feedMs
                            + "ms（累计均 " + (feeds > 0 ? feedSum / feeds : 0)
                            + "ms / 最大 " + feedMax + "ms）、已喂 " + feeds
                            + "、丢弃 " + dropped);
                }
                if (!cont) break;
            }
            Log.i(TAG, "喂音结束：共 " + loops + " 轮、实喂 " + feeds + " 块、丢弃 " + dropped
                    + " 块；feed 平均 " + (feeds > 0 ? feedSum / feeds : 0) + "ms / 最大 "
                    + feedMax + "ms，read 平均 " + (loops > 0 ? readSum / loops : 0) + "ms");
        } finally {
            // 按引用释放：只清自己发布的那一份 mRec，绝不碰新会话的。
            // 读-判-写要入锁，否则这条判断和 stop() 的快照置空会互相踩。
            try {
                rec.stop();
            } catch (Throwable ignored) {
            }
            try {
                rec.release();
            } catch (Throwable ignored) {
            }
            synchronized (sLock) {
                if (mRec == rec) mRec = null;
            }
        }
    }

    /** 喂一块进去并回报光标。@return false = 该退出 */
    private static boolean step(short[] pcm, SfeInterface.UninterruptibleFollowStatus st,
                                TrialListener listener, int myGen) {
        // 2a：进 native 之前再校一次代数。外层 while 与本句之间有一段窗口，
        // stop() 完全可能在里面递增代数并开始排 native 收尾 —— 那一帧再喂进去
        // 就是"旧会话往新/空 handler 喂音"，只能靠这里挡。
        if (!alive(myGen)) return false;
        // UFP 的硬约束：input_len 必须正好 2048，否则 native 直接 abort
        // （ufp_magic.cpp:166 "input_len must be 2048"，实测 SIGABRT，Java 拦不住）。
        // 末尾不足块、以及变调重采样后的长度都要补齐到 2048。
        if (pcm.length != CHUNK) {
            short[] fixed = new short[CHUNK];
            System.arraycopy(pcm, 0, fixed, 0, Math.min(pcm.length, CHUNK));
            pcm = fixed;
        }
        try {
            SfeInterface.ufpFeedAudio(pcm, pcm.length, st);
        } catch (Throwable t) {
            throw new IllegalStateException("UFP 喂音失败", t);
        }
        SfeInterface.NotePosition p = st.cursorNotePosition;
        if (p != null && p.measure >= 0) {
            listener.onCursor(p.measure, p.offset, p.voice, st.score, st.computationalUnitState, true);
        }
        // 引擎走到区间终点会报 UF_FINISHED（computationalUnitState == 3）。
        // 不处理的话喂音循环会一直空转（真麦克风下就是"弹完了还卡在演奏中、
        // 成绩卡不出来"），所以见 3 就收：让外层结束喂音并走 ufpFinish 取成绩。
        if (st.computationalUnitState == 3) {
            Log.i(TAG, "引擎到终点（state=3），结束本次演奏");
            return false;
        }

        return alive(myGen);
    }

    // ---------------- 工具 ----------------

    /**
     * 区间端点的 JSON：**必须是数组**（原版 Dart 侧就是 `[{...}]`），
     * 单个对象会被 native 拒（r=4）。字段顺序 measure/voice/offset。
     */
    private static String pos(int[] p) {
        return "[{\"measure\":" + p[0] + ",\"voice\":" + p[1] + ",\"offset\":" + p[2] + "}]";
    }

    /**
     * 设置判定区间，返回**真正生效**的终点 JSON（null = 区间没设上，退回整曲）。
     *
     * 终点是位置而不是"小节号"：越界一格（end = 小节数）直接返回 2 拒绝。
     * 旧实现遇到 2 就退回「末小节 + offset 0」——那正好停在末小节的**第一颗音**，
     * 于是最后一小节永远判不到（用户报的"光标走到最后一个小节第一颗音就结算"
     * 和"末两小节弹错也不出红叉"，都是这个）。
     *
     * 现在在末小节里**探测可用的最大 offset**：0、1、2 一路试到被拒为止，
     * 取最后一个能用的位置。这样"到曲末"就能把最后一小节尽量完整地包进来。
     * 探测只有十几次 setCustomRange 调用（纯数据，不碰模型），相对建会话的
     * 几百毫秒可以忽略。
     */
    private static String applyRange(String sJson, int[] start, int[] end) {
        String eJson = pos(end);
        int r = SfeInterface.ufpSetCustomRangeWithResult(sJson, eJson);
        if (r == 0) {
            Log.i(TAG, "区间生效 start=" + sJson + " end=" + eJson);
            return eJson;
        }
        // 终点越界 / 不可用：逐颗音往后试。
        // 两种调用形态：① 到曲末传 {measureCount,0,0}（越界一格）→ 试末小节；
        // ② 选段传精确 {m,v,o}（v/o 可能不被接受）→ 先试同一小节，再退一格。
        for (int m : new int[]{end[0], end[0] - 1}) {
            if (m < start[0]) continue;
            String best = null;
            int bestOffset = -1;
            for (int o = 0; o <= MAX_END_PROBE_OFFSET; o++) {
                String cand = "[{\"measure\":" + m + ",\"voice\":0,\"offset\":" + o + "}]";
                int rr = SfeInterface.ufpSetCustomRangeWithResult(sJson, cand);
                if (rr != 0) break;
                best = cand;
                bestOffset = o;
            }
            if (best != null) {
                Log.i(TAG, "精确终点不可用(r=" + r + ")，第 " + m + " 小节可用到 offset="
                        + bestOffset + "，区间生效 start=" + sJson + " end=" + best);
                return best;
            }
        }
        Log.w(TAG, "setRangeWithResult=" + r + "，兜底探测也失败，退回整曲");
        return null;
    }

    private static String fmt(SfeInterface.NotePosition p) {
        return p == null ? "null" : (p.measure + "/" + p.offset + "/" + p.voice);
    }

    private static short[] resample(short[] in, double ratio) {
        if (ratio == 1.0 || in.length < 2) return in;
        int outN = (int) Math.floor((in.length - 1) / ratio);
        if (outN <= 1) return in;
        short[] out = new short[outN];
        for (int i = 0; i < outN; i++) {
            double x = i * ratio;
            int i0 = (int) x;
            double f = x - i0;
            int i1 = Math.min(i0 + 1, in.length - 1);
            out[i] = (short) (in[i0] * (1 - f) + in[i1] * f);
        }
        return out;
    }

    private UfpTrial() {
    }
}
