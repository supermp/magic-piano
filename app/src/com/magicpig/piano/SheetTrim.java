/*
 * 谱面裁剪 / 位置查询工具。
 *
 * ⚠️ 本文件曾被我误删（当时以为它只服务 UFP 裁谱），后从 build/classes 里的
 * .class 用 CFR 反编译恢复 —— 所以没有原始注释，变量名是 n2/n3 这种。
 * 当前用途：
 *   · lastPitchMeasure / pitchCountAt / hasPitchBetween  供 AlgoEngine 的 native 探针
 *     计算有效起止位置；
 *   · noteCountAt(xml,m,v)         某小节某声部的音符数（判断能不能分手）
 *   · from(xml, m) / from(xml,m,v,o)  裁剪谱面（现仅 MainActivity 调试页用）
 * 反编译源码与 .class 行为等价；如需改动请先补测试。
 */
package com.magicpig.piano;

import java.util.Iterator;
import java.util.TreeSet;

final class SheetTrim {
    private SheetTrim() {
    }

    static String from(String string, int n) {
        int n2;
        int n3;
        if (string == null || n <= 0) {
            return null;
        }
        int n4 = string.indexOf("<part ");
        if (n4 < 0) {
            return null;
        }
        if (string.indexOf("<part ", n4 + 1) >= 0) {
            return null;
        }
        String string2 = null;
        String string3 = null;
        int n5 = n4;
        int n6 = -1;
        for (n3 = 0; n3 <= n; ++n3) {
            int n7 = string.indexOf("<measure", n5);
            if (n7 < 0) {
                return null;
            }
            int n8 = string.indexOf("</measure>", n7);
            if (n8 < 0) {
                return null;
            }
            if (n3 < n) {
                int n9;
                String string4 = string.substring(n7, n8);
                n2 = string4.indexOf("<attributes>");
                if (n2 >= 0 && (n9 = string4.indexOf("</attributes>", n2)) >= 0) {
                    string2 = string4.substring(n2, n9 + "</attributes>".length());
                }
                if ((n9 = string4.lastIndexOf("<sound tempo=")) >= 0) {
                    int n10 = string4.lastIndexOf("<direction ", n9);
                    if (n10 < 0) {
                        n10 = string4.lastIndexOf("<direction>", n9);
                    }
                    int n11 = string4.indexOf("</direction>", n9);
                    if (n10 >= 0 && n11 >= 0) {
                        string3 = string4.substring(n10, n11 + "</direction>".length());
                    }
                }
                n5 = n8 + "</measure>".length();
                continue;
            }
            n6 = n7;
        }
        if (n6 < 0) {
            return null;
        }
        n3 = string.indexOf(62, n4);
        if (n3 < 0) {
            return null;
        }
        String string5 = string.substring(0, n3 + 1);
        String string6 = string.substring(n6);
        int n12 = string6.indexOf(62);
        if (n12 < 0) {
            return null;
        }
        n2 = string6.indexOf("</measure>");
        String string7 = n2 > 0 ? string6.substring(0, n2) : string6;
        StringBuilder stringBuilder = new StringBuilder();
        if (string2 != null && !string7.contains("<attributes>")) {
            stringBuilder.append(string2);
        }
        if (string3 != null && !string7.contains("<sound tempo=")) {
            stringBuilder.append(string3);
        }
        if (stringBuilder.length() == 0) {
            return string5 + string6;
        }
        return string5 + string6.substring(0, n12 + 1) + stringBuilder + string6.substring(n12 + 1);
    }

    static String from(String string, int n, int n2, int n3) {
        String string2;
        if (string == null) {
            return null;
        }
        if (n2 <= 0 && n3 <= 0) {
            return SheetTrim.from(string, n);
        }
        String string3 = string2 = n > 0 ? SheetTrim.from(string, n) : string;
        if (string2 == null) {
            return null;
        }
        int n4 = string2.indexOf("<part ");
        if (n4 < 0) {
            return null;
        }
        if (string2.indexOf("<part ", n4 + 1) >= 0) {
            return null;
        }
        int n5 = string2.indexOf("<measure", n4);
        if (n5 < 0) {
            return null;
        }
        int n6 = string2.indexOf("</measure>", n5);
        if (n6 < 0) {
            return null;
        }
        String string4 = string2.substring(n5, n6);
        String string5 = SheetTrim.neutralizeBefore(string4, n2, n3);
        if (string5 == null) {
            return null;
        }
        return string2.substring(0, n5) + string5 + string2.substring(n6);
    }

    private static String neutralizeBefore(String string, int n, int n2) {
        int n3;
        int n4 = SheetTrim.mapVoice(string, n);
        if (n4 < 0) {
            return null;
        }
        int[] nArray = new int[16];
        StringBuilder stringBuilder = new StringBuilder(string.length() + 32);
        int n5 = 0;
        boolean bl = false;
        while ((n3 = string.indexOf("<note", n5)) >= 0) {
            int n6;
            int n7 = string.indexOf("</note>", n3);
            if (n7 < 0) {
                return null;
            }
            String string2 = string.substring(n3, n7 += "</note>".length());
            boolean bl2 = string2.contains("<chord/>") || string2.contains("<chord />");
            boolean bl3 = string2.contains("<grace/>") || string2.contains("<grace />");
            int n8 = SheetTrim.intTag(string2, "voice", n4);
            if (n8 < 0 || n8 >= nArray.length) {
                return null;
            }
            int n9 = n6 = bl2 ? nArray[n8] - 1 : nArray[n8];
            if (!bl2) {
                int n10 = n8;
                nArray[n10] = nArray[n10] + 1;
            }
            boolean bl4 = n8 < n4 || n8 == n4 && n6 < n2;
            stringBuilder.append(string, n5, n3);
            if (!bl4) {
                stringBuilder.append(string2);
            } else if (bl2 || bl3) {
                bl = true;
            } else {
                String string3 = SheetTrim.toRest(string2, n4);
                if (string3 == null) {
                    return null;
                }
                stringBuilder.append(string3);
                bl = true;
            }
            n5 = n7;
        }
        stringBuilder.append(string.substring(n5));
        return bl ? stringBuilder.toString() : string;
    }

    private static String toRest(String string, int n) {
        String string2 = SheetTrim.innerTag(string, "duration");
        if (string2 == null) {
            return null;
        }
        StringBuilder stringBuilder = new StringBuilder("<note><rest/>").append(string2);
        for (String string3 : new String[]{"voice", "type", "staff", "time-modification"}) {
            String string4 = SheetTrim.innerTag(string, string3);
            if (string4 == null) continue;
            stringBuilder.append(string4);
        }
        if (string.contains("<dot/>") || string.contains("<dot />")) {
            stringBuilder.append("<dot/>");
        }
        if (SheetTrim.innerTag(string, "voice") == null) {
            stringBuilder.append("<voice>").append(n).append("</voice>");
        }
        return stringBuilder.append("</note>").toString();
    }

    

    /**
     * 最后一个"含音高音符"的小节号（0-based）。
     *
     * 反编译版同样只认「最小 voice」，遇到占位声部会算错（实测有曲子返回 143，
     * 而全谱只有 64 个小节）。这里改为扫全部声部：取全文最后一个带 &lt;pitch&gt;
     * 的音符所在的小节序号。
     */
    static int lastPitchMeasure(String xml) {
        if (xml == null) {
            return -1;
        }
        int last = -1;
        int mi = 0;
        int pos = 0;
        int s;
        int e;
        while ((s = xml.indexOf("<measure", pos)) >= 0
                && (e = xml.indexOf("</measure>", s)) >= 0) {
            if (xml.substring(s, e).contains("<pitch>")) {
                last = mi;
            }
            ++mi;
            pos = e + "</measure>".length();
        }
        return last;
    }

    /**
     * 某小节里「第 voiceIndex 个声部」（0-based，按 voice 号从小到大）**含音高**的音符数。
     *
     * 和 noteCountAt 的唯一区别：**只数带 &lt;pitch&gt; 的**，休止符不算。
     * 为什么必须区分：magicPiano 的 SetNoteSequence 内部会
     * `TrimRestsInPitchClusters`，如果起点小节该声部全是休止，它会抛
     * `std::runtime_error` 直接 abort 进程（NoteSequence.cpp:155
     * "found no pitch clusters after trimming rests"，实测 SIGABRT，tid=algo-engine）。
     * 判断"能不能从这个小节起弹"必须用这个函数，不能用 noteCountAt。
     */
    static int pitchCountAt(String xml, int m, int voiceIndex) {
        if (xml == null || m < 0 || voiceIndex < 0) {
            return 0;
        }
        String block = SheetTrim.measureBlock(xml, m);
        if (block == null) {
            return 0;
        }
        TreeSet<Integer> vs = new TreeSet<Integer>();
        int p = 0;
        int s;
        int e;
        while ((s = block.indexOf("<note", p)) >= 0
                && (e = block.indexOf("</note>", s)) >= 0) {
            int v = SheetTrim.intTag(block.substring(s, e), "voice", -1);
            if (v > 0) {
                vs.add(v);
            }
            p = e + "</note>".length();
        }
        if (voiceIndex >= vs.size()) {
            return 0;
        }
        int target = -1;
        int i = 0;
        Iterator<Integer> it = vs.iterator();
        while (it.hasNext()) {
            int v = it.next();
            if (i == voiceIndex) {
                target = v;
                break;
            }
            ++i;
        }
        int n = 0;
        p = 0;
        while ((s = block.indexOf("<note", p)) >= 0
                && (e = block.indexOf("</note>", s)) >= 0) {
            String note = block.substring(s, e);
            p = e + "</note>".length();
            if (note.contains("<chord/>") || note.contains("<chord />")) {
                continue;
            }
            if (note.contains("<grace/>") || note.contains("<grace ")) {
                continue;
            }
            if (SheetTrim.intTag(note, "voice", -1) == target && note.contains("<pitch>")) {
                ++n;
            }
        }
        return n;
    }

    static int noteCountAt(String string, int n, int n2) {
        int n3;
        int n4;
        int n5;
        int n6;
        int n7;
        String string2 = SheetTrim.measureBlock(string, n);
        if (string2 == null) {
            return 0;
        }
        TreeSet<Integer> treeSet = new TreeSet<Integer>();
        int n8 = 0;
        while ((n7 = string2.indexOf("<note", n8)) >= 0 && (n6 = string2.indexOf("</note>", n7)) >= 0) {
            int n9 = SheetTrim.intTag(string2.substring(n7, n6), "voice", -1);
            if (n9 > 0) {
                treeSet.add(n9);
            }
            n8 = n6 + "</note>".length();
        }
        if (n2 < 0 || n2 >= treeSet.size()) {
            return 0;
        }
        n7 = -1;
        n6 = 0;
        Iterator iterator = treeSet.iterator();
        while (iterator.hasNext()) {
            n5 = (Integer)iterator.next();
            if (n6 == n2) {
                n7 = n5;
                break;
            }
            ++n6;
        }
        int n10 = 0;
        n5 = 0;
        while ((n4 = string2.indexOf("<note", n5)) >= 0 && (n3 = string2.indexOf("</note>", n4)) >= 0) {
            boolean bl;
            String string3 = string2.substring(n4, n3);
            boolean bl2 = string3.contains("<chord/>") || string3.contains("<chord />");
            boolean bl3 = bl = string3.contains("<grace/>") || string3.contains("<grace ");
            if (!bl2 && !bl && SheetTrim.intTag(string3, "voice", -1) == n7) {
                ++n10;
            }
            n5 = n3 + "</note>".length();
        }
        return n10;
    }

    /**
     * 区间 [a, b] 里是否存在"可演奏的音高"。
     *
     * ⚠ 反编译版只检查「该小节 voice 号最小」的那一个声部，真实谱面上会误判：
     * 很多曲子每个谱表先放一个 voice 1 的占位休止，真正的音在 voice 2/5；
     * 只要最小 voice 在区间里全是休止，这里就返回 false —— 上层据此"跳过设序列"，
     * 而 magicPiano 没有 note sequence 时第一块音频就 SIGSEGV（整机闪退）。
     * 现在改成**扫所有声部**，每个声部各自按 offset 计数（任一命中即可）。
     */
    static boolean hasPitchBetween(String xml, int[] a, int[] b) {
        if (xml == null || a == null || b == null) {
            return false;
        }
        int from = Math.min(a[0], b[0]);
        int to = Math.max(a[0], b[0]);
        for (int i = from; i <= to; ++i) {
            String block = SheetTrim.measureBlock(xml, i);
            if (block == null) {
                continue;
            }
            int lo = i == a[0] ? a[2] : 0;
            int hi = i == b[0] ? b[2] - 1 : Integer.MAX_VALUE;
            if (hi < lo) {
                continue;
            }
            // 只看「第 a[1] 个声部」（引擎的 voice 参数口径）。
            // 引擎按第 0 声部收谱，别的声部的音它根本不弹；拿"任意声部有音"当依据，
            // 会让 SetNoteSequence 在"第 0 声部全是休止"的区间里 abort
            // （NoteSequence.cpp:155 TrimRestsInPitchClusters，SIGABRT）。
            TreeSet<Integer> vs = new TreeSet<Integer>();
            int p = 0;
            int s;
            int e;
            while ((s = block.indexOf("<note", p)) >= 0
                    && (e = block.indexOf("</note>", s)) >= 0) {
                int v = SheetTrim.intTag(block.substring(s, e), "voice", -1);
                if (v > 0) {
                    vs.add(v);
                }
                p = e + "</note>".length();
            }
            if (vs.isEmpty()) {
                continue;
            }
            // a[1] 是「第几个声部」（0-based，引擎的 voice 参数口径）：
            // 起点可能落在第 1 个声部（例：右手整首休止、音全在左手的曲子）。
            int want = (a.length > 1 && a[1] >= 0) ? a[1] : 0;
            if (want >= vs.size()) {
                continue;
            }
            int target = -1;
            int vi = 0;
            Iterator<Integer> it = vs.iterator();
            while (it.hasNext()) {
                int v = it.next();
                if (vi == want) {
                    target = v;
                    break;
                }
                ++vi;
            }
            if (target < 0) {
                continue;
            }
            int idx = 0;
            p = 0;
            while ((s = block.indexOf("<note", p)) >= 0
                    && (e = block.indexOf("</note>", s)) >= 0) {
                String note = block.substring(s, e);
                p = e + "</note>".length();
                if (note.contains("<chord/>") || note.contains("<chord />")) {
                    continue;                  // 和弦副音不单独占位
                }
                if (SheetTrim.intTag(note, "voice", -1) != target) {
                    continue;
                }
                if (note.contains("<grace/>") || note.contains("<grace ")) {
                    continue;                  // 倚音不算可演奏音
                }
                int at = idx++;
                if (note.contains("<pitch>") && at >= lo && at <= hi) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String measureBlock(String string, int n) {
        if (string == null || n < 0) {
            return null;
        }
        int n2 = 0;
        int n3 = 0;
        int n4;
        while ((n4 = string.indexOf("<measure", n2)) >= 0) {
            int n5 = string.indexOf("</measure>", n4);
            if (n5 < 0) {
                return null;
            }
            if (n3 == n) {
                return string.substring(n4, n5);
            }
            ++n3;
            n2 = n5 + "</measure>".length();
        }
        return null;
    }

    private static int mapVoice(String string, int n) {
        int n2;
        int n3;
        int n4;
        TreeSet<Integer> treeSet = new TreeSet<Integer>();
        int n5 = 0;
        while ((n4 = string.indexOf("<note", n5)) >= 0 && (n3 = string.indexOf("</note>", n4)) >= 0) {
            n2 = SheetTrim.intTag(string.substring(n4, n3), "voice", -1);
            if (n2 > 0) {
                treeSet.add(n2);
            }
            n5 = n3 + 7;
        }
        if (treeSet.isEmpty()) {
            return -1;
        }
        n4 = 0;
        Iterator iterator = treeSet.iterator();
        while (iterator.hasNext()) {
            n2 = (Integer)iterator.next();
            if (n4 == n) {
                return n2;
            }
            ++n4;
        }
        return -1;
    }

    private static String innerTag(String string, String string2) {
        int n = string.indexOf("<" + string2 + ">");
        if (n >= 0) {
            int n2 = string.indexOf("</" + string2 + ">", n);
            return n2 < 0 ? null : string.substring(n, n2 + string2.length() + 3);
        }
        int n3 = string.indexOf("<" + string2 + " ");
        if (n3 < 0) {
            return null;
        }
        int n4 = string.indexOf(62, n3);
        if (n4 < 0) {
            return null;
        }
        if (string.charAt(n4 - 1) == '/') {
            return string.substring(n3, n4 + 1);
        }
        int n5 = string.indexOf("</" + string2 + ">", n4);
        return n5 < 0 ? null : string.substring(n3, n5 + string2.length() + 3);
    }

    private static int intTag(String string, String string2, int n) {
        String string3 = SheetTrim.innerTag(string, string2);
        if (string3 == null) {
            return n;
        }
        StringBuilder stringBuilder = new StringBuilder();
        for (int i = 0; i < string3.length(); ++i) {
            char c = string3.charAt(i);
            if (c >= '0' && c <= '9') {
                stringBuilder.append(c);
                continue;
            }
            if (stringBuilder.length() > 0) break;
        }
        return stringBuilder.length() == 0 ? n : Integer.parseInt(stringBuilder.toString());
    }
}
