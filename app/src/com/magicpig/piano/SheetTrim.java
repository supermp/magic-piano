/*
 * 谱面裁剪工具：把 MusicXML 从第 m 小节（或第 v 声部第 o 个音符）起的谱面裁出来，
 * 供 MainActivity 的裁剪探针使用。
 *
 * 反编译恢复的代码：变量名是 n2/n3 这种，行为与原 .class 等价；改动前先补测试。
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
