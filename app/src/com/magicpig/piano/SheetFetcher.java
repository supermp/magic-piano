package com.magicpig.piano;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 曲谱定位器：**纯粹的本地查找，不联网**。
 *
 * 曲谱由外部拷贝进来，放在应用专属外部目录：
 *     sdcard/Android/data/com.magicpig.piano/files/sheets/&lt;乐曲uuid&gt;/sheet.xml.enc
 * 日常由 tools/deploy.py / sync_devices.py 铺设，手工恢复用 unpack_backup.py。
 * 没有的乐曲点开就提示"乐曲不存在"；App 不申请 INTERNET，也没有任何下载链路。
 *
 * 【目录优先级】
 *   1) 应用专属外部目录 sdcard/Android/data/…/files/sheets —— 主力位置，容量大
 *   2) 内部 /data/data/…/files/sheets —— 兼容位置，只读
 *
 * 没选 OBB：它是只读的，而 native 的 decryptSheet() 必须拿到真实文件路径，
 * 读不了压缩包内的条目；而且单文件上限 2GB、本意是商店分发扩展包，不合适。
 */
public final class SheetFetcher {

    private SheetFetcher() {
    }

    /**
     * 曲谱存放位置，按优先级排列。外部目录没挂载时只剩内部那一项。
     */
    private static File[] roots(Context c) {
        List<File> out = new ArrayList<>(2);
        File ext = c.getExternalFilesDir(null);
        if (ext != null) out.add(new File(ext, "sheets"));
        out.add(new File(c.getFilesDir(), "sheets"));
        return out.toArray(new File[0]);
    }

    /** 本地曲谱包的 sheet.xml.enc 路径（没有时返回 null）。 */
    public static String localEnc(Context c, String uuid) {
        for (File r : roots(c)) {
            File f = new File(new File(r, uuid), "sheet.xml.enc");
            if (f.isFile()) return f.getAbsolutePath();
        }
        return null;
    }
}
