package com.magicpig.piano;

/**
 * 演奏（跟弹）会话的回调契约，由 {@link UfpTrial}（UFP，原版那条路）实现。
 */
public interface TrialListener {

    /** 光标首次脱离初始位(0,0,0)：跟随已锁定到真实弹奏，此后才显示得分 */
    void onLocked();

    /** 光标移动（仅位置变化时回调；score/state 一并带给状态行刷新） */
    void onCursor(int measure, int offset, int voice, int score, int state, boolean recording);

    /**
     * 会话结束（自然走完或用户停止）。userStopped=true 表示手动停。
     *
     * @param evaJson       ufpFinish 的原生评测 JSON（含 rating / pitch_bias / rhythm_bias）
     * @param metricsJson   过程统计（pitch_cluster_total_count 等）
     * @param pitchResultJson ufpFinish 的 vexflow JSON，里面的 pitches_info 是**逐音对错**：
     *                      {@code {pitch_cluster_position, pitch:[{key, bias_type}]}}，
     *                      bias_type 取 ok / miss（漏弹）/ extra（弹错，附带正确音）。
     *                      播放页据此在谱面上打出对勾与错音标记 —— 这是"错音展示"的唯一数据源。
     * @param lastScore     过程中引擎报的实时分（恒 100，只作兜底）
     */
    void onFinished(String evaJson, String metricsJson, String pitchResultJson,
                    int lastScore, boolean userStopped);

    /**
     * 建会话失败 / 收音中断。
     *
     * reason 必须是**一句短话**（演奏没能开始 / 这首曲子暂时开不了演奏 /
     * 演奏被打断…），不带括号、返回码、异常原文；detail 才是异常原文，只进日志。
     * UI 侧会把 reason 原样显示在反色中断卡片的提示行里。
     */
    void onError(String reason, String detail);
}
