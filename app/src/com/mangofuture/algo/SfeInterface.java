package com.mangofuture.algo;

import android.util.Log;

import java.nio.charset.StandardCharsets;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 自研 JNI 声明层 —— 只声明本工程用到的方法。
 *
 * 与 libsfeALG-lib-jni.so 对接的三条硬约束（违反即运行期崩溃）：
 *
 *  1. 方法名必须与导出的 Java_com_mangofuture_algo_SfeInterface_* 符号对应。
 *  2. 原始 DEX 里 native 方法全部是 public **static** —— 必须声明为 static，
 *     否则 JNI 第 2 个参数是 jobject 而非 jclass。
 *  3. native 会**反向回调** Java 的两个静态方法，缺失会导致：
 *       - log(int, byte[])   缺失 → JNI_OnLoad 返回 JNI_ERR → UnsatisfiedLinkError
 *       - decrypt(byte[],byte[],byte[]) 缺失 → 曲谱解密失败
 *
 * 内部静态类的字段名同样必须与 native 的 GetFieldID 字符串一致，否则 NoSuchFieldError。
 */
public class SfeInterface {

    static {
        System.loadLibrary("sfeALG-lib-jni");
    }

    private static final String TAG = "SfeInterface";

    // ---------- native 反向回调（必须存在） ----------

    public interface AlgoLogCallback {
        void algoLog(int level, String msg);
    }

    private static volatile AlgoLogCallback sAlgoLogCallback;

    public static void setAlgoLogCallback(AlgoLogCallback cb) {
        sAlgoLogCallback = cb;
    }

    /**
     * native SfeLogger 的回调：JNI_OnLoad 阶段就会通过
     * GetStaticMethodID("log", "(I[B)V") 查找它。
     * 缺失 → SfeLogger::init 失败 → JNI_OnLoad 返回 JNI_ERR。
     *
     * 注意：原实现在 sAlgoLogCallback 为 null 时抛 NullPointerException，
     * 这里改成兜底打到 logcat，避免初始化期被回调打断。
     */
    public static void log(int level, byte[] msg) {
        String s = (msg == null) ? "" : new String(msg, StandardCharsets.UTF_8);
        AlgoLogCallback cb = sAlgoLogCallback;
        if (cb != null) {
            try {
                cb.algoLog(level, s);
                return;
            } catch (Throwable ignored) {
            }
        }
        Log.println(level, TAG, s);
    }

    /**
     * native mf_aes 的解密回调：AES-128-CBC / PKCS7Padding。
     *
     * 【关键】参数顺序是 (data, keyMaterial, iv)，不是 (key, data, iv)。
     * 原 DEX 里 doFinal 用的是 v3、getKey 用的是 v4、IvParameterSpec 用的是 v5，
     * 而静态方法 decrypt([B[B[B) 的三个参数依次落在 v3/v4/v5 —— 即密文在最前。
     * 实测：native 传入的第 1 个参数是 10 万字节级的密文，第 2 个才是 16 字节密钥块。
     *
     * 密钥 = keyMaterial 的前 16 字节；IV 单独传入。
     */
    public static byte[] decrypt(byte[] data, byte[] keyMaterial, byte[] iv) {
        try {
            SecretKeySpec key = getKey(keyMaterial);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS7Padding");
            cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(iv));
            byte[] out = cipher.doFinal(data);
            Log.d(TAG, "algo decrypt original = " + out.length);
            return out;
        } catch (Throwable t) {
            Log.e(TAG, "decrypt() 失败: " + t);
            t.printStackTrace();
            return new byte[0];
        }
    }

    private static int len(byte[] b) { return b == null ? -1 : b.length; }

    private static SecretKeySpec getKey(byte[] in) {
        byte[] key = new byte[16];
        for (int i = 0; i < in.length && i < 16; i++) key[i] = in[i];
        return new SecretKeySpec(key, "AES");
    }

    /** native UFP（跟弹）退出完成的回调，本工程暂不使用，保留空实现以防查找失败 */
    public static void onUfpQuitComplete() {
    }

    // ---------- native 方法（全部 static） ----------

    public static native void setDevDebugMode(boolean debug);

    /**
     * 内部 C++ 原型：
     * InitAppAlgorithm(int, MFDeviceLevel, const std::string& assetsDir, logCb, cipherCb)
     * 第 1 个 int 对应日志里的 "A4:%d"（原 App 实测 0）；第 2 个是设备档位枚举。
     */
    public static native int appAlgorithmInitV2(int a, int deviceLevel, String algoAssetsDir);

    public static native void appLoadSample(String samplePath);

    /** Dart 侧叫 preInitializeMLModels，JNI 导出名是 preloadTfModels */
    public static native void preloadTfModels(boolean realtime, boolean highPerf);

    /**
     * 注册 so 内部的 S7 运行时回调（含 TFLite 推理器工厂）。
     * 必须在 appAlgorithmInitV2 之后、preloadTfModels 之前调用 —— 否则
     * preloadTfModels 只"选择场景"却永远不真正加载模型，后续 ufpCreateMP
     * 会因 "no high-performance realtime model loaded" 返回 4。
     * 纯 native 自注册，无 Java 回调；原版在 initModule 前调用（dex 实证）。
     */
    public static native void setTfLiteCallbacks();

    

    // ---------- 曲谱 ----------

    /**
     * 解密 .enc 文件，返回 MusicXML 明文。
     * 这一步会调用 DecryptSheetImpl → mf_sheet_init（注册 AES 回调）→ mf_decode_enc，
     * 解密时通过 Java 回调 {@link #decrypt} 完成真正的 AES 运算。
     */
    public static native String decryptSheet(String sheetEncPath);

    /**
     * 注意：参数不是文件路径，而是【已解密的 MusicXML 内容字符串】。
     * 传路径会得到 "LoadScoreFromString: Error parsing score" +
     * "SheetPreviewModule: failed to initialize AlgoScoreManager"。
     * （对照：magicPianoInitModule 的第一个参数才是 encrypted sheet path。）
     */
    public static native boolean initPreviewModule(String decryptedSheetContent);

    public static native boolean releasePreviewModule();

    /**
     * 预览合成的**播放区间**（原版「试炼」选段试听用的就是它）。
     *
     * 签名取自原版 classes.dex：
     * {@code setSection(Ljava/lang/String; I I I I I I)I}，
     * 6 个 int = BEGIN[3 个位置量] + END[3 个位置量]；
     * so 里的日志 {@code set-syn-section to BEGIN:[%d, %d, %d] END:[%d, %d, %d]}
     * 与 {@code MapSection2SegmentSpotsRange(..., int×6, ...)} 印证了这是在**合成层**
     * 把音符位置映射成片段范围，段外的 PCM 根本不生成 —— 所以原版能精确停在选段末尾。
     *
     * 传全 0 表示"没有区间，到曲末"（so 日志 {@code set-section param zero, use sheet end}）。
     * ⚠ 必须在 {@code initPreviewModule} 重建模块之后、{@code playFirst} 之前调用。
     */
    public static native int setSection(String ctx, int beginMeasure, int beginVoice,
                                        int beginOffset, int endMeasure, int endVoice,
                                        int endOffset);



    // ---------- 播放 ----------
    public static native int setPlayPositionWithoutCountDown(String ctx, int measure, int voice, int offset);

    /**
     * 该曲谱是否启用反复（native 查询）。
     * so 里有 `Java_..._sheetRepeatsEnable` 入口；原版用它决定 UI/播放是否按反复走。
     */
    public static native boolean sheetRepeatsEnable(String ctx);

    /** 运行时开关演奏侧的反复遍历（so: Java_..._ufpSetEnableRepeats）。 */
    public static native void ufpSetEnableRepeats(boolean enable);


    /** 电钢琴左手音量（原版 dex 签名：setElectricPianoLeftHandVolume(Ljava/lang/String; I)V） */
    public static native void setElectricPianoLeftHandVolume(String ctx, int volume);

    /** 电钢琴右手音量 */
    public static native void setElectricPianoRightHandVolume(String ctx, int volume);


    public static native void playFirst(String ctx, int a, int b, int c, PlayNote out);

    public static native void playNext(String ctx, PlayNote out);

    

    // ---------- 跟弹（UFP：Uninterruptible Follower for Piano） ----------
    //
    // 原 App「魔法演奏/魔法闯关」的现场音频跟弹链路（签名取自原版 dex）：
    //   ufpCreateMP(handType, sheetPath, enableRepeats, startJsonStr, endJsonStr, isFullScore)
    //     handType    0=合手 1=右手 2=左手（MFPitchHandType）
    //     sheetPath   .enc 曲谱文件路径（native 自己解密，与 magicPianoInitModule 同）
    //     start/endJsonStr  自定义练习区间，整曲传 "{}"
    //     isFullScore 整曲模式传 true
    //   之后循环 ufpFeedAudio(pcm44100, len, status) 喂麦克风 PCM（44100Hz 单声道 16bit，
    //   原版每次 2048 采样），status.cursorNotePosition 即识别到的谱面位置。
    //   退出：ufpFinish(out) 拿评测 JSON，releaseUfpHandler() 销毁。
    public static native int ufpCreateMP(int handType, String sheetPath, boolean enableRepeats,
            String startJsonStr, String endJsonStr, boolean isFullScore);

    /** 预载跟弹 ML 模型（pitch_follower_piano/*.tflite）。原版在 ufpCreate 后调用 */
    public static native boolean ufpPreInitializeMLModels(boolean enableEqStrategy);

    /** 喂一块麦克风 PCM；len 为采样数（原版固定 2048）。状态写进 outStatus */
    public static native void ufpFeedAudio(short[] pcm, int len, UninterruptibleFollowStatus outStatus);

    /** 结束跟弹并取评测结果（evaJson/metricsJson/vexflowJson） */
    public static native void ufpFinish(UfpResult outResult);

    /** 销毁跟弹会话句柄 */
    public static native void releaseUfpHandler();

    /**
     * 自由选段：把跟弹范围限制到 [start, end)。
     *
     * 原版 App 就是走这个接口（Dart 侧
     * dev.flutter.pigeon.sfealgo_plugin.SfealgoUfpInterface.ufpSetCustomRangeWithResult），
     * 谱面上拖动出来的粉红区间最终由它生效 —— 注意**不是** ufpCreateMP 的
     * startJson/endJson 那两个参数（那两个在这套 so 里恒返回 4）。
     *
     * 两个参数是 JSON，字段与 create 的区间参数同形：
     *   {"measure":32,"voice":0,"offset":0}   →  起点（0-based 小节）
     *   {"measure":-1,"voice":-1,"offset":-1} →  -1 表示到曲末
     * 返回值 0 = 成功。
     */
    public static native int ufpSetCustomRangeWithResult(String startJson, String endJson);

    // setUninterruptiblePlayPosition / setUninterruptibleMode 在原版 dex 里有声明，
    // 但当前两版 .so 都没有对应 JNI 导出；不要恢复声明，调用只会 UnsatisfiedLinkError。





    





    

    /** 曲谱里的首个有效音符位置 */
    public static native NotePosition ufpGetFirstValidPosition();

    // ---------- 数据类（字段名不可改） ----------
    public static class NotePosition {
        public short measure;
        public short offset;
        public short voice;

        public short getMeasure() { return measure; }
        public void setMeasure(short v) { measure = v; }
        public short getOffset() { return offset; }
        public void setOffset(short v) { offset = v; }
        public short getVoice() { return voice; }
        public void setVoice(short v) { voice = v; }
    }

    public static class Segment {
        public int measure;
        public int segment;
    }

    public static class PlayNote {
        public int          beatCountDown;
        public int          finished;
        public short        measureEnd;
        public float        onsetPos;
        public short[]      pcm;
        public int          pcmLength;
        public NotePosition position;
        public Segment      segment;
    }

    

    /**
     * 跟弹实时状态（ufpFeedAudio 的出参）。字段名与原版 dex 完全一致
     * （native 用 GetFieldID 反写，改名即 NoSuchFieldError）。
     *
     * cursorNotePosition 由 native 直接 new 出来挂上（原版 Java 侧只 new 外层），
     * 所以读之前必须判空。
     *
     * computationalUnitState 取值（原版常量名）：
     *   UF_GENESIS / UF_NORMAL / UF_LOST / UF_FINISHED —— 具体数值运行时观测。
     */
    public static class UninterruptibleFollowStatus {
        public boolean      isActive;
        public boolean      startRecording;
        public int          computationalUnitState;
        public NotePosition cursorNotePosition;
        public NotePosition leftWinBoundNotePosition;
        public NotePosition rightWinBoundNotePosition;
        public int          pendingTaskQueueLen;
        public int          score;

        /**
         * 原版 <init> 会预建三个 NotePosition 并赋给各字段 —— native
         * setPosition 只做「GetObjectField 取现成对象再 SetIntField」,
         * 字段为 null 时仅打日志 "setPosition: %s field is null" 直接返回,
         * 光标永远不会被赋值。三个对象必须在此预分配。
         */
        public UninterruptibleFollowStatus() {
            this.cursorNotePosition        = new NotePosition();
            this.leftWinBoundNotePosition  = new NotePosition();
            this.rightWinBoundNotePosition = new NotePosition();
        }
    }

    /** 跟弹结束的评测结果（三个 JSON 串；字段名同原版） */
    public static class UfpResult {
        public String evaJson;
        public String metricsJson;
        public String vexflowJson;
    }
}
