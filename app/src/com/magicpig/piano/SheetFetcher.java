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
 * 拷贝方式见 tools/unpack_backup.py（从 library/sheets/ 备份解包后 adb push，
 * 或直接 USB 传文件过去）。没有的乐曲点开就提示"乐曲不存在"，App 不会去下载。
 *
 * 【为什么不再内置下载】
 * 原版服务端取曲谱要带 60 天有效期的 JWT，过期就全量失效；而真正的离线版
 * 本就该一次铺完、之后零网络。所以下载链路（qiniu/private_download_url）、
 * 令牌常量与 SharedPreferences 存储**全部删掉**，连 INTERNET 权限都不再申请。
 *
 * 【目录优先级】
 *   1) 应用专属外部目录 sdcard/Android/data/…/files/sheets —— 主力位置，容量大
 *   2) 内部 /data/data/…/files/sheets —— 只为兼容老版本下过的曲谱，只读不写
 *      （新版不再往这里写任何东西）
 *
 * 没选 OBB：它是只读的，而 native 的 decryptSheet() 必须拿到真实文件路径，
 * 读不了压缩包内的条目；而且单文件上限 2GB、本意是商店分发扩展包，不合适。
 */
public final class SheetFetcher {

    private SheetFetcher() {
    }

    public interface Callback {
        void onReady(String encPath);

        /** 失败原因（人话）。 */
        void onError(String msg);
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

    /**
     * 打开一首曲谱：只查本地，查不到就报"没拷进来"。
     *
     * 同步执行（就是一次 stat），调用方**不用再开后台线程**。
     */
    public static void open(Context c, String uuid, Callback cb) {
        String local = localEnc(c, uuid);
        if (local != null) {
            cb.onReady(local);
        } else {
            cb.onError("乐曲不存在");
        }
    }
}
