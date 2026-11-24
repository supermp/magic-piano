package com.magicpig.piano;

/**
 * 把**首小节的速度标记**从 MusicXML 里摘出来，改由顶栏显示。
 *
 * 为什么要动 XML：渲染器会把 {@code <direction>} 画成谱表上方的「文字 ♩=126」，
 * 并且**把这块高度算进第一行的行高**。于是第一行永远比后面几行高出一截，
 * 而这段高度只服务于一个"看一眼就知道"的信息 —— 挪到顶栏后，
 * 整个曲谱区能把这段高度让出来（{@code score_padding_top} 也能从 40 降到个位数）。
 *
 * 只处理**第一个 measure**里的 tempo 型 direction：
 *   - 含 {@code <metronome>} 或 {@code <sound tempo=...>} 的整个 {@code <direction>} 块被删除；
 *   - 同一小节里其它 direction（力度 f/p、dolce 之类的文字）**原样保留**；
 *   - 后面的小节一概不碰（速度变化标记仍在谱面上）。
 *
 * 注意：native 引擎（AlgoEngine）是**自己解密并解析谱包文件**的，
 * 这里改的字符串只喂给 WebView 渲染器，不影响音频/判定的速度。
 */
final class TempoMark {

    /** 摘掉速度标记后的 XML（原样返回其它内容，绝不重新序列化）。 */
    final String xml;
    /** 顶栏要显示的文字，例如 "Bouncy (♩=126)"（文字在前、速度放括号里，与顶栏其它速度标记一致）；没有速度标记时为 null。 */
    final String label;

    private TempoMark(String xml, String label) {
        this.xml = xml;
        this.label = label;
    }

    static TempoMark extract(String source) {
        if (source == null) return new TempoMark(null, null);
        int m0 = source.indexOf("<measure");
        if (m0 < 0) return new TempoMark(source, null);
        int m1 = source.indexOf("</measure>", m0);
        if (m1 < 0) return new TempoMark(source, null);
        m1 += "</measure>".length();

        int bpm = 0;
        String unit = null;
        String words = null;
        java.util.List<int[]> cut = new java.util.ArrayList<>();

        int p = m0;
        while (true) {
            int d0 = indexOfDirection(source, p);
            if (d0 < 0 || d0 >= m1) break;
            int d1 = source.indexOf("</direction>", d0);
            if (d1 < 0 || d1 > m1) break;
            d1 += "</direction>".length();
            String block = source.substring(d0, d1);
            boolean isTempo = block.contains("<metronome") || block.contains("<sound tempo=");
            if (isTempo) {
                cut.add(new int[]{d0, d1});
                if (words == null) words = tagText(block, "<words", "</words>");
                if (bpm <= 0) bpm = tagInt(block, "<per-minute>", "</per-minute>");
                if (bpm <= 0) bpm = attrInt(block, "<sound", "tempo=");
                if (unit == null) unit = tagText(block, "<beat-unit>", "</beat-unit>");
            }
            p = d1;
        }

        String label = buildLabel(bpm, unit, words);
        if (cut.isEmpty()) return new TempoMark(source, label);

        StringBuilder sb = new StringBuilder(source);
        // 从后往前删，前面块的索引才不会失效
        for (int i = cut.size() - 1; i >= 0; i--) {
            int[] r = cut.get(i);
            sb.delete(r[0], r[1]);
        }
        return new TempoMark(sb.toString(), label);
    }

    /** 只认 "<direction " / "<direction>"，别把 "<direction-type" 当成块起点。 */
    private static int indexOfDirection(String s, int from) {
        int p = from;
        while (true) {
            int i = s.indexOf("<direction", p);
            if (i < 0) return -1;
            int after = i + "<direction".length();
            if (after < s.length()) {
                char c = s.charAt(after);
                if (c == ' ' || c == '>' || c == '\t' || c == '\n' || c == '\r') return i;
            }
            p = i + 1;
        }
    }

    private static String buildLabel(int bpm, String unit, String words) {
        String w = clean(words);
        if (bpm <= 0 && (w == null || w.isEmpty())) return null;
        // 原版写法：文字在前、速度放进括号 —— `Adagio maestoso (♩=50)`；
        // 只有速度没有文字时不加括号（`♩=50`）。现在回到这个原始实现。
        if (w != null && !w.isEmpty() && w.length() > WORDS_MAX) {
            w = w.substring(0, WORDS_MAX - 1) + "…";
        }
        boolean hasWords = w != null && !w.isEmpty();
        StringBuilder sb = new StringBuilder();
        if (hasWords) sb.append(w).append(' ');
        if (bpm > 0) {
            if (hasWords) sb.append('(');
            sb.append(glyph(unit)).append('=').append(bpm);
            if (hasWords) sb.append(')');
        }
        return sb.toString();
    }

    /** 文字过长先自己截短，保证后面的 `♩=数字` 一定完整。 */
    private static final int WORDS_MAX = 22;

    /** quarter → ♩，eighth → ♪，其它（half/whole/附点…）退化成 ♩，宁可不准也别显示空框。 */
    private static String glyph(String unit) {
        if (unit == null) return "♩";
        String u = unit.trim().toLowerCase();
        if (u.startsWith("eighth")) return "♪";
        if (u.startsWith("half")) return "𝅗𝅥";
        return "♩";
    }

    private static String tagText(String block, String open, String close) {
        int i = block.indexOf(open);
        if (i < 0) return null;
        int gt = block.indexOf('>', i);
        int end = block.indexOf(close, gt + 1);
        if (gt < 0 || end < 0) return null;
        return block.substring(gt + 1, end);
    }

    private static int tagInt(String block, String open, String close) {
        String t = tagText(block, open, close);
        return parseInt(t);
    }

    /** 取 <tag ... attr="N"> 里的数字 */
    private static int attrInt(String block, String tagPrefix, String attr) {
        int i = block.indexOf(tagPrefix);
        if (i < 0) return 0;
        int gt = block.indexOf('>', i);
        if (gt < 0) return 0;
        String seg = block.substring(i, gt);
        int a = seg.indexOf(attr);
        if (a < 0) return 0;
        int q = seg.indexOf('"', a);
        int q2 = q < 0 ? -1 : seg.indexOf('"', q + 1);
        if (q < 0 || q2 < 0) return 0;
        return parseInt(seg.substring(q + 1, q2));
    }

    private static int parseInt(String s) {
        if (s == null) return 0;
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** 去标签、解基本实体、压空白 */
    private static String clean(String raw) {
        if (raw == null) return null;
        String s = raw.replaceAll("<[^>]*>", "");
        s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&#160;", " ");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }
}
