package com.magicpig.piano;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.mangofuture.algo.SfeInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 探针 Activity：逐步调用 native 音频链，验证「离线播放本地曲谱」可行性。
 *
 * 两种用法：
 *   1) 手动：点按钮逐步执行
 *   2) 自动：adb shell am start -n com.magicpig.piano/.MainActivity --ez autorun true
 *      然后 adb logcat -s OP:V 看结果
 */
public class MainActivity extends Activity {

    private static final String TAG = "OP";

    private TextView logView;
    private final StringBuilder sb = new StringBuilder();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private String filesDir;
    private String algoDir;
    private String samplePath;
    private String sheetEncPath;
    private String sheetCtx = "";
    private String sheetXml;

    private volatile boolean playing = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        filesDir = getFilesDir().getAbsolutePath();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        ScrollView sv = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(11f);
        logView.setPadding(12, 12, 12, 12);
        sv.addView(logView);
        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        String[] labels = {
                "① 安装资产", "② setDevDebugMode", "③ appAlgorithmInitV2",
                "④ appLoadSample", "⑤ preloadTfModels",
                "⑥ decryptSheet", "⑦ initPreviewModule",
                "⑧ playFirst/Next", "⑨ 停止", "⑩ release", "⑫ 跟弹自喂", "清空"
        };
        View.OnClickListener[] acts = {
                v -> step(this::stepInstallAssets, "安装资产"),
                v -> step(() -> { SfeInterface.setDevDebugMode(false); return "ok"; }, "setDevDebugMode"),
                v -> step(this::stepInit, "appAlgorithmInitV2"),
                v -> step(() -> { SfeInterface.appLoadSample(samplePath); return "ok"; }, "appLoadSample"),
                v -> step(() -> { SfeInterface.setTfLiteCallbacks();
            SfeInterface.preloadTfModels(true, true); return "ok"; }, "preloadTfModels"),
                v -> step(this::stepDecryptSheet, "decryptSheet"),
                v -> step(() -> sheetXml == null ? "先点 ⑥" : "ret=" + SfeInterface.initPreviewModule(sheetXml), "initPreviewModule"),
                v -> step(this::stepPlayWithAudioTrack, "AudioTrack 发声"),
                v -> { playing = false; log("已请求停止"); },
                v -> step(() -> "ret=" + SfeInterface.releasePreviewModule(), "releasePreviewModule"),
                v -> step(this::stepUfpSelfFeed, "跟弹自喂探针"),
                v -> { sb.setLength(0); logView.setText(""); }
        };

        LinearLayout row = null;
        for (int i = 0; i < labels.length; i++) {
            if (i % 3 == 0) { row = new LinearLayout(this); root.addView(row); }
            Button btn = new Button(this);
            btn.setText(labels[i]);
            btn.setTextSize(10f);
            btn.setAllCaps(false);
            btn.setOnClickListener(acts[i]);
            row.addView(btn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }

        setContentView(root);
        log("filesDir = " + filesDir);

        Intent it = getIntent();
        if (it != null && it.getBooleanExtra("trimtest", false)) {
            new Thread(this::runTrimTest, "trimtest").start();
        } else if (it != null && it.getBooleanExtra("autorun", false)) {
            log(">>> 自动模式 <<<");
            autoRun();
        } else if (it != null && it.getBooleanExtra("ufp", false)) {
            log(">>> 跟弹自喂探针 <<<");
            autoRunUfp();
        } else {
            log("点击「① 安装资产」开始");
        }
    }


    /**
     * 设备上第一个可用的曲谱包（外部专属目录优先，与 {@link SheetFetcher} 的查找顺序一致）。
     *
     * 探针不写死曲谱 uuid：曲库更新或下架后就会失效；也不只查内部存储，
     * 否则找不到铺在外部目录里的曲库。按设备上实际存在的第一个谱包取。
     */
    private File firstSheetEnc() {
        File ext = getExternalFilesDir(null);
        File[] roots = {ext == null ? null : new File(ext, "sheets"), new File(filesDir, "sheets")};
        for (File r : roots) {
            if (r == null) continue;
            File[] kids = r.listFiles();
            if (kids == null) continue;
            java.util.Arrays.sort(kids, (x, y) -> x.getName().compareTo(y.getName()));
            for (File k : kids) {
                File f = new File(k, "sheet.xml.enc");
                if (f.isFile()) return f;
            }
        }
        return null;
    }

    /** 裁谱自测：对真实谱面跑音符级裁剪，打印结构统计（adb --ez trimtest true 触发）。 */
    private void runTrimTest() {
        File enc = firstSheetEnc();
        if (enc == null) {
            log("trimtest：设备上没有任何曲谱包，先跑一次同步再试");
            return;
        }
        log("trimtest 用谱: " + enc.getAbsolutePath());
        String xml = SfeInterface.decryptSheet(enc.getAbsolutePath());
        if (xml == null) { log("decrypt 失败"); return; }
        log("明文长度 " + xml.length());
        int[][] cases = {{6, 0, 0}, {6, 0, 1}, {6, 0, 2}, {12, 0, 1}, {20, 1, 0}};
        for (int[] c : cases) {
            String out = SheetTrim.from(xml, c[0], c[1], c[2]);
            if (out == null) { log("  (" + c[0] + "," + c[1] + "," + c[2] + ") -> null"); continue; }
            int m1s = out.indexOf("<measure");
            int m1e = out.indexOf("</measure>", m1s);
            String first = out.substring(m1s, m1e);
            int rests = first.split("<rest/>", -1).length - 1;
            int pitches = first.split("<pitch>", -1).length - 1;
            int firstPitch = first.indexOf("<pitch>");
            int lastRest = first.lastIndexOf("<rest/>");
            log("  (" + c[0] + "," + c[1] + "," + c[2] + ") 长 " + out.length()
                    + " 首小节 rests=" + rests + " pitches=" + pitches
                    + " 首个pitch在休止之后=" + (firstPitch > lastRest)
                    + " 小节数=" + (out.split("<measure", -1).length - 1));
        }
    }


    private static void append(java.io.File f, String line) {
        try {
            java.io.FileOutputStream w = new java.io.FileOutputStream(f, true);
            w.write((line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            w.close();
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 自动流程 ----------------

    private void autoRun() {
        new Thread(() -> {
            run("安装资产", this::stepInstallAssets);
            sleep(500);
            run("setDevDebugMode", () -> { SfeInterface.setDevDebugMode(false); return "ok"; });
            run("appAlgorithmInitV2", this::stepInit);
            run("appLoadSample", () -> { SfeInterface.appLoadSample(samplePath); return "ok"; });
            run("preloadTfModels", () -> { SfeInterface.setTfLiteCallbacks();
            SfeInterface.preloadTfModels(true, true); return "ok"; });
            run("decryptSheet", this::stepDecryptSheet);
            run("initPreviewModule", () -> {
                if (sheetXml == null) return "跳过（前置 decryptSheet 失败）";
                return "ret=" + SfeInterface.initPreviewModule(sheetXml);
            });
            run("getSheetContent", this::stepGetSheet);
            // 注意：getSheetDuration / getSheetHeader 走 getAlgoContext 的
            // ufpHandler / magicPianoBasicModeModule 分支，未调 magicPianoInitModule
            // 时全局为空会 SIGSEGV（Java 层 catch 不到），故先验证播放链路。
            run("playFirst+playNext", this::stepPlaySync);
            run("★AudioTrack 发声", this::stepPlayWithAudioTrack);
            run("releasePreviewModule", () -> "ret=" + SfeInterface.releasePreviewModule());
            log(">>> 自动流程结束 <<<");
        }, "autorun").start();
    }

    private void run(String name, Step s) {
        try {
            long t = System.currentTimeMillis();
            Object r = s.run();
            log("✔ " + name + " (" + (System.currentTimeMillis() - t) + "ms) → " + r);
        } catch (Throwable t) {
            log("✘ " + name + " 抛出 " + t.getClass().getName() + ": " + t.getMessage());
            Log.e(TAG, name, t);
        }
    }

    /** 跟弹自喂探针的自动流程：建预览模块只为合成 PCM，随后立即进入 UFP 探针 */
    private void autoRunUfp() {
        new Thread(() -> {
            run("安装资产", this::stepInstallAssets);
            run("setDevDebugMode", () -> { SfeInterface.setDevDebugMode(false); return "ok"; });
            run("appAlgorithmInitV2", this::stepInit);
            run("appLoadSample", () -> { SfeInterface.appLoadSample(samplePath); return "ok"; });
            run("preloadTfModels", () -> { SfeInterface.setTfLiteCallbacks();
            SfeInterface.preloadTfModels(true, true); return "ok"; });
            run("decryptSheet", this::stepDecryptSheet);
            run("initPreviewModule", () -> {
                if (sheetXml == null) return "跳过（前置 decryptSheet 失败）";
                return "ret=" + SfeInterface.initPreviewModule(sheetXml);
            });
            run("跟弹自喂探针", this::stepUfpSelfFeed);
            log(">>> 探针结束 <<<");
        }, "autorun-ufp").start();
    }

    /**
     * 跟弹（UFP）链路探针：不依赖麦克风，把预览模块自己合成的钢琴 PCM
     * 降采样喂回跟弹引擎 —— 如果识别正常，cursorNotePosition 应当随
     * 喂入进度逐小节推进。这等于让引擎"听"它自己弹的琴。
     *
     * 步骤：
     *   1. 预览模块合成整曲 PCM（88200Hz mono）到内存，同时记录每块的
     *      (采样起点, measure, offset) —— 作为"标准答案"对照组
     *   2. releasePreviewModule + ufpCreateMP(合手, enc路径, 不重复, "{}", "{}", 整曲)
     *      —— 与原版演奏页同一入口；ufpPreInitializeMLModels 预载识别模型
     *   3. PCM 2:1 降采样到 44100Hz（跟弹输入采样率，同原版 AudioRecord 配置），
     *      按原版 2048 采样/块、约半实时的节奏喂 ufpFeedAudio
     *   4. 周期性打印 status：isActive / state / score / cursor 位置，
     *      并与对照组换算出的"此时应在第几小节"并排
     *   5. ufpFinish 拿评测 JSON，releaseUfpHandler 销毁，最后重建预览模块
     */
    private Object stepUfpSelfFeed() {
        if (sheetXml == null) return "先点 ⑥⑦（需要预览模块合成 PCM）";

        // ---- 1. 合成整曲 PCM 到内存 + 记录对照位置 ----
        java.util.ArrayList<short[]> blocks = new java.util.ArrayList<>();
        java.util.ArrayList<int[]> blkPos = new java.util.ArrayList<>(); // {startSample, measure, offset}
        java.util.ArrayList<Integer> blkLen = new java.util.ArrayList<>();
        int total = 0;
        {
            SfeInterface.PlayNote n = new SfeInterface.PlayNote();
            n.position = new SfeInterface.NotePosition();
            n.segment = new SfeInterface.Segment();
            SfeInterface.playFirst(sheetCtx, 0, 0, 0, n);
            for (int i = 0; i < 20000; i++) {
                if (n.pcm != null && n.pcmLength > 0) {
                    blocks.add(n.pcm.clone());
                    blkLen.add(n.pcmLength);
                    blkPos.add(new int[]{total,
                            n.position == null ? 0 : n.position.measure,
                            n.position == null ? 0 : n.position.offset});
                    total += n.pcmLength;
                }
                if (n.finished != 0) break;
                SfeInterface.playNext(sheetCtx, n);
            }
        }
        log("    合成完成：" + blocks.size() + " 块 / " + total + " 采样 ≈ "
                + String.format("%.1f", total / 88200.0) + " 秒 @88200Hz");

        // 对照组：44100Hz 采样位置 → 小节（2:1 降采样后块起点也减半）
        // blkPos 里的 startSample 是 88200 域，/2 即 44100 域。

        // ---- 2. 释放预览模块，建跟弹会话 ----
        // 注意顺序：ufpPreInitializeMLModels 必须在 ufpCreateMP **之前** ——
        // 否则 create 内部模型选择阶段报
        // "no high-performance realtime model loaded" 并返回 4。
        SfeInterface.releasePreviewModule();
        boolean pre = SfeInterface.ufpPreInitializeMLModels(false);
        log("    ufpPreInitializeMLModels -> " + pre);
        int ret = SfeInterface.ufpCreateMP(0, sheetEncPath, false, "{}", "{}", true);
        log("    ufpCreateMP ret=" + ret);
        if (ret != 0) {
            SfeInterface.initPreviewModule(sheetXml); // 恢复
            return "ufpCreateMP 失败 ret=" + ret;
        }

        // ---- 3. 降采样 + 喂入 ----
        // 2:1 零阶降采样：每隔一个取一个。合成音频干净，对识别足够。
        SfeInterface.UninterruptibleFollowStatus st = new SfeInterface.UninterruptibleFollowStatus();
        short[] chunk = new short[2048];
        int chunkFill = 0;
        long fed = 0;          // 已喂 44100Hz 采样数
        int fedChunks = 0;
        int lastLoggedMeasure = -999;
        StringBuilder traj = new StringBuilder();
        long tFeed0 = System.currentTimeMillis();
        int FEED_SECONDS = 26; // 只喂前 26 秒（士兵进行曲约 9 个小节），够验证推进
        boolean finished = false;

        outer:
        for (int bi = 0; bi < blocks.size(); bi++) {
            short[] b = blocks.get(bi);
            int len = blkLen.get(bi);
            for (int k = 0; k < len; k += 2) {
                chunk[chunkFill++] = b[k];
                if (chunkFill == 2048) {
                    SfeInterface.ufpFeedAudio(chunk, 2048, st);
                    fed += 2048;
                    fedChunks++;
                    chunkFill = 0;
                    if (st.computationalUnitState == 3) { finished = true; break outer; }
                    // 约半实时节奏（2048/44100≈46ms，睡 23ms），防 native 队列积压
                    if ((fedChunks & 1) == 0) sleep(46);
                    if (fed / 44100.0 > FEED_SECONDS) break outer;
                    int curM = st.cursorNotePosition == null ? -1 : st.cursorNotePosition.measure;
                    if (curM != lastLoggedMeasure || fedChunks % 43 == 0) {
                        lastLoggedMeasure = curM;
                        int truthM = measureAt44100(blkPos, (int) (fed));
                        String line = String.format(
                                "    [fed %.1fs] active=%b rec=%b state=%d score=%d q=%d cursor=%s 对照≈m%d",
                                fed / 44100.0, st.isActive, st.startRecording,
                                st.computationalUnitState, st.score, st.pendingTaskQueueLen,
                                fmtPos(st.cursorNotePosition), truthM);
                        log(line);
                        traj.append(line).append('\n');
                    }
                }
            }
        }

        // ---- 4. 收尾 ----
        SfeInterface.UfpResult ur = new SfeInterface.UfpResult();
        try {
            SfeInterface.ufpFinish(ur);
        } catch (Throwable t) {
            log("    ufpFinish 抛 " + t);
        }
        log("    UfpResult: eva=" + trunc(ur.evaJson) + " metrics=" + trunc(ur.metricsJson));
        try {
            SfeInterface.releaseUfpHandler();
        } catch (Throwable t) {
            log("    releaseUfpHandler 抛 " + t);
        }
        long wall = System.currentTimeMillis() - tFeed0;

        // 恢复预览模块（探针不能破坏播放链状态）
        SfeInterface.initPreviewModule(sheetXml);

        return "喂入 " + fedChunks + " 块 ≈ " + String.format("%.1f", fed / 44100.0)
                + " 秒音频，wall=" + wall + "ms，finished(自报)=" + finished
                + "\n    最终光标=" + fmtPos(st.cursorNotePosition)
                + " score=" + st.score + " state=" + st.computationalUnitState;
    }

    /** 44100Hz 域的「采样位置 → 对照小节」（blkPos 存的是 88200 域，/2 折算） */
    private static int measureAt44100(java.util.ArrayList<int[]> blkPos, int sample44100) {
        int idx = 0;
        for (int[] p : blkPos) {
            if (p[0] / 2 <= sample44100) idx = p[1]; else break;
        }
        return idx;
    }

    private static String fmtPos(SfeInterface.NotePosition p) {
        return p == null ? "null"
                : "(m=" + p.measure + ",o=" + p.offset + ",v=" + p.voice + ")";
    }

    private static String trunc(String s) {
        if (s == null) return "null";
        return s.length() <= 200 ? s : s.substring(0, 200) + "…(" + s.length() + "字符)";
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    // ---------------- 各步骤 ----------------

    private Object stepInstallAssets() throws Exception {
        // 诊断：打包修好后 AssetManager.list() 应该能列出子目录。
        try {
            String[] root = getAssets().list("");
            String[] algo = getAssets().list("algo_assets");
            log("    [诊断] assets root 条目=" + (root == null ? "null" : root.length)
                    + " algo_assets 条目=" + (algo == null ? "null" : algo.length)
                    + (algo != null && algo.length > 0 ? " → " + java.util.Arrays.toString(algo) : ""));
        } catch (Throwable t) {
            log("    [诊断] AssetManager.list 失败：" + t);
        }

        // 优先用 adb push 到外部目录的资产（getExternalFilesDir()/op_assets）。
        // 原因：AssetManager 在本工程的打包方式下读不到子目录资产，
        // 而 native 链路验证才是本探针的重点，不能被资产分发卡住。
        File ext = new File(getExternalFilesDir(null), "op_assets");
        if (new File(ext, "algo_assets").isDirectory()) {
            log("    使用外部资产目录：" + ext);
            copyTree(new File(ext, "algo_assets"), new File(filesDir, "algo"));
            copyTree(new File(ext, "sheets"), new File(filesDir, "sheets"));
        } else {
            // 和 AppAssets 走同一条 AssetManager 递归，探针验证的就是 App 真实使用的
            // 分发路径，也没有构建期清单可依赖。
            copyAssetDir("algo_assets", new File(filesDir, "algo").getAbsolutePath());
            log("    已按目录递归安装资产：" + new File(filesDir, "algo"));
        }
        algoDir = new File(filesDir, "algo").getAbsolutePath();
        samplePath = algoDir + "/sound/synthesis_sample_piano/1.0.0/PianoWaveStereo.bin";
        File enc = firstSheetEnc();
        sheetEncPath = enc == null ? "" : enc.getAbsolutePath();
        return "algoDir=" + algoDir
                + "\n    sample 存在=" + new File(samplePath).exists()
                + " (" + new File(samplePath).length() + " B)"
                + "\n    测试谱 = " + (enc == null ? "（设备上没有任何曲谱包）" : enc.getAbsolutePath())
                + "\n    enc 存在=" + (enc != null)
                + " (" + (enc == null ? 0 : enc.length()) + " B)";
    }

    /** 枚举 (a,b) 组合，找到能成功初始化的参数 */
    private Object stepInit() {
        StringBuilder r = new StringBuilder();
        int[][] combos = {{0, 0}, {0, 1}, {0, 2}, {0, 3}, {44100, 0}, {1, 0}};
        for (int[] c : combos) {
            try {
                int ret = SfeInterface.appAlgorithmInitV2(c[0], c[1], algoDir);
                r.append("    (").append(c[0]).append(",").append(c[1]).append(") -> ret=").append(ret).append('\n');
                if (ret == 0) break;
            } catch (Throwable t) {
                r.append("    (").append(c[0]).append(",").append(c[1]).append(") -> 抛 ").append(t).append('\n');
            }
        }
        return "\n" + r;
    }

    /** 用 native 解密 .enc；顺带把明文落盘，便于离线比对 */
    private Object stepDecryptSheet() throws Exception {
        sheetXml = SfeInterface.decryptSheet(sheetEncPath);
        if (sheetXml == null) return "decryptSheet() = null";
        File f = new File(filesDir, "decrypted.xml");
        try (java.io.FileWriter w = new java.io.FileWriter(f)) { w.write(sheetXml); }
        return "长度=" + sheetXml.length()
                + "\n    头=" + sheetXml.substring(0, Math.min(70, sheetXml.length()))
                + "\n    已存 " + f;
    }

    private Object stepGetSheet() throws Exception {
        // magicPianoGetSheetContent 依赖已移除的 magicPiano 模块，不能再调；
        // 这里取引擎里已解密的整曲 XML。
        String xml = AlgoEngine.get().getSheetXml();
        if (xml == null) return "getSheetXml() = null（引擎还没打开曲谱？）";
        File f = new File(filesDir, "decrypted.xml");
        try (java.io.FileWriter w = new java.io.FileWriter(f)) { w.write(xml); }
        return "长度=" + xml.length() + "\n    头=" + xml.substring(0, Math.min(70, xml.length()))
                + "\n    已存 " + f;
    }

    private Object stepPlaySync() {
        SfeInterface.PlayNote note = new SfeInterface.PlayNote();
        note.position = new SfeInterface.NotePosition();
        note.segment = new SfeInterface.Segment();

        SfeInterface.playFirst(sheetCtx, 0, 0, 0, note);
        String first = "playFirst: finished=" + note.finished
                + " beatCountDown=" + note.beatCountDown
                + " pcmLength=" + note.pcmLength
                + " onsetPos=" + note.onsetPos
                + " pcm=" + (note.pcm == null ? "null" : "short[" + note.pcm.length + "]")
                + " pos(m=" + note.position.measure + ",o=" + note.position.offset
                + ",v=" + note.position.voice + ")";

        int nonZero = 0, maxLen = 0, totalPcm = 0;
        // 记录能量最大的一块用于离线分析（首块通常是静音前奏）
        short[] loudest = null;
        int loudestLen = 0, loudestIdx = -1;
        long loudestEnergy = 0;
        // 曲首连续若干块，用于离线测速（起音间隔 → 反推采样率）
        java.io.ByteArrayOutputStream head = new java.io.ByteArrayOutputStream();
        int headBlocks = 0, HEAD_WANT = 12;
        for (int i = 0; i < 300; i++) {
            SfeInterface.playNext(sheetCtx, note);
            if (note.finished != 0) break;
            if (note.pcm != null && note.pcmLength > 0) {
                nonZero++;
                totalPcm += note.pcmLength;
                maxLen = Math.max(maxLen, note.pcmLength);
                long energy = 0;
                for (int k = 0; k < note.pcmLength; k += 7) energy += Math.abs(note.pcm[k]);
                if (energy > 0 && (loudest == null || energy > loudestEnergy)) {
                    loudestEnergy = energy;
                    loudest = note.pcm.clone();
                    loudestLen = note.pcmLength;
                    loudestIdx = i;
                }
                if (headBlocks < HEAD_WANT) {
                    for (int k = 0; k < note.pcmLength; k++) {
                        head.write(note.pcm[k] & 0xff);
                        head.write((note.pcm[k] >> 8) & 0xff);
                    }
                    headBlocks++;
                }
            }
        }
        try {
            File f = new File(getExternalFilesDir(null), "pcm_head.raw");
            try (FileOutputStream os = new FileOutputStream(f)) {
                os.write(head.toByteArray());
            }
            log("    曲首 PCM 已存 " + f + " (" + headBlocks + " 块 / "
                    + (head.size() / 2) + " 采样)");
        } catch (Exception e) {
            log("    曲首 PCM 存盘失败 " + e);
        }

        // 存 PCM 供离线分析（写到外部目录，便于 adb pull）
        if (loudest != null) {
            try {
                File dir = getExternalFilesDir(null);
                File f = new File(dir, "pcm_dump.raw");
                int n = Math.min(loudestLen, 48000);
                try (FileOutputStream os = new FileOutputStream(f)) {
                    for (int i = 0; i < n; i++) {
                        short v = loudest[i];
                        os.write(v & 0xff);
                        os.write((v >> 8) & 0xff);
                    }
                }
                log("    PCM 样本已存 " + f + " (" + n + " 采样, 取自第 " + loudestIdx + " 块)");
            } catch (Exception e) {
                log("    PCM 存盘失败 " + e);
            }
        }

        return first
                + "\n    playNext×300: 有数据=" + nonZero + " 次, 总样本=" + totalPcm
                + ", 单次最大=" + maxLen
                + "\n    末次 finished=" + note.finished;
    }

    /**
     * 真正的发声验证：把 playFirst/playNext 产出的 PCM 灌进 AudioTrack。
     *
     * 采样率是 88200 而不是 44100 —— 原生日志里的 "SR:44100" 指的是分析上下文，
     * 合成输出是它的两倍。判定依据见 AlgoEngine.SAMPLE_RATE 的注释
     * （谱面时值 104 拍 @108BPM → 57.78s，实测 5,096,000 采样，恰好 88200Hz）。
     */
    private Object stepPlayWithAudioTrack() {
        final int SR = 88200;
        int minBuf = AudioTrack.getMinBufferSize(SR,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufBytes = Math.max(minBuf, 49000 * 2 * 2);
        log("    AudioTrack: " + SR + "Hz 单声道 16bit, minBuf=" + minBuf + ", 使用=" + bufBytes);

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SR)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();

        SfeInterface.PlayNote note = new SfeInterface.PlayNote();
        note.position = new SfeInterface.NotePosition();
        note.segment = new SfeInterface.Segment();

        long t0 = System.currentTimeMillis();
        long written = 0;
        int blocks = 0;
        long peak = 0;
        try {
            track.play();
            SfeInterface.playFirst(sheetCtx, 0, 0, 0, note);
            written += writeChunk(track, note, 0);
            blocks++;
            for (int i = 0; i < 3000 && note.finished == 0; i++) {
                SfeInterface.playNext(sheetCtx, note);
                if (note.finished != 0) break;
                written += writeChunk(track, note, i + 1);
                blocks++;
                for (int k = 0; k < note.pcmLength && note.pcm != null; k += 37) {
                    long a = Math.abs(note.pcm[k]);
                    if (a > peak) peak = a;
                }
            }
        } finally {
            try { track.stop(); } catch (Throwable ignored) { }
            track.release();
        }
        long ms = System.currentTimeMillis() - t0;
        return "播放结束：共 " + blocks + " 块 / " + written + " 采样"
                + "\n    实际耗时 " + ms + " ms，按 " + SR + "Hz 折算音频时长 "
                + String.format("%.1f", written / (double) SR) + " 秒"
                + "\n    峰值幅度 " + peak + " ("
                + String.format("%.1f", 100.0 * peak / 32767) + "% FS)";
    }

    private int writeChunk(AudioTrack track, SfeInterface.PlayNote note, int idx) {
        if (note.pcm == null || note.pcmLength <= 0) return 0;
        int n = Math.min(note.pcmLength, note.pcm.length);
        int off = 0;
        while (off < n) {
            int w = track.write(note.pcm, off, n - off);
            if (w <= 0) break;
            off += w;
        }
        return off;
    }

    // ---------------- 工具 ----------------

    private interface Step { Object run() throws Exception; }

    private void step(Step s, String name) {
        new Thread(() -> {
            try { log("✔ " + name + " → " + s.run()); }
            catch (Throwable t) { log("✘ " + name + " 抛出 " + t); Log.e(TAG, name, t); }
        }, name).start();
    }

    private void copyTree(File src, File dst) throws Exception {
        if (src.isDirectory()) {
            dst.mkdirs();
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) copyTree(k, new File(dst, k.getName()));
        } else {
            dst.getParentFile().mkdirs();
            try (InputStream in = new java.io.FileInputStream(src);
                 OutputStream os = new FileOutputStream(dst)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
        }
    }

    private void copyAssetDir(String assetPath, String destDir) throws Exception {
        String[] children = getAssets().list(assetPath);
        if (children == null || children.length == 0) {
            copyAssetFile(assetPath, destDir);
            return;
        }
        new File(destDir).mkdirs();
        for (String c : children) copyAssetDir(assetPath + "/" + c, destDir + "/" + c);
    }

    private void copyAssetFile(String assetPath, String destPath) throws Exception {
        File out = new File(destPath);
        out.getParentFile().mkdirs();
        try (InputStream in = getAssets().open(assetPath);
             OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    private void log(String s) {
        Log.i(TAG, s);
        sb.append(s).append('\n');
        ui.post(() -> logView.setText(sb.toString()));
    }
}
