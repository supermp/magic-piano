package com.magicpig.piano;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从演奏结束时的评测 JSON 里取"成绩"。
 *
 * 数据来自 {@link UfpTrial} 收尾时 native 的评测 JSON（ufpFinish 的返回），
 * 只取其中的 {@code rating} 段。
 *
 * 之前界面显示的是 {@code UninterruptibleFollowStatus.score}（实时分），
 * 实测它在任何场景下都恒为 100（连故意喂错音都不掉），没有信息量。
 * 真正有区分度的是 JSON 里的 rating 段：
 *
 * <pre>
 * "rating": { "pitch": 100, "pitchWrong": 0, "rhythm": 100, "speed": 100, "total": 100 }
 * </pre>
 *
 * pitch = 音准、rhythm = 节奏、speed = 速度（相对谱面标的 tempo），total = 综合。
 * 权重是 native 内部算的，这里只负责原样取出。
 */
final class EvalRating {

    /** 四项都是 0-100 的整数分。 */
    final int pitch;
    final int rhythm;
    final int speed;
    final int total;

    private EvalRating(int pitch, int rhythm, int speed, int total) {
        this.pitch = pitch;
        this.rhythm = rhythm;
        this.speed = speed;
        this.total = total;
    }

    /** 解析失败（没评测 JSON / 字段缺失）返回 null，调用方退回旧显示。 */
    static EvalRating parse(String evaJson) {
        if (evaJson == null) return null;
        int b = evaJson.indexOf("\"rating\"");
        if (b < 0) return null;
        int ob = evaJson.indexOf('{', b);
        int cb = evaJson.indexOf('}', ob);
        if (ob < 0 || cb < 0) return null;
        String block = evaJson.substring(ob, cb + 1);

        int total = num(block, "total", -1);
        int pitch = num(block, "pitch", -1);
        int rhythm = num(block, "rhythm", -1);
        int speed = num(block, "speed", -1);
        if (total < 0 && pitch < 0) return null;
        return new EvalRating(Math.max(pitch, 0), Math.max(rhythm, 0),
                Math.max(speed, 0), Math.max(total, 0));
    }

    private static int num(String s, String key, int def) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : def;
    }

    /** 卡片上的第二行：三个分项。 */
    String subLine() {
        return "音准 " + pitch + " · 节奏 " + rhythm + " · 速度 " + speed;
    }

    @Override
    public String toString() {
        return "rating{pitch=" + pitch + ", rhythm=" + rhythm + ", speed=" + speed
                + ", total=" + total + "}";
    }
}
