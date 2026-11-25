package com.magicpig.piano;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 APK 内置资产解到内部存储。
 *
 * 目前只剩算法引擎那套资产（algo_assets → files/algo）。曲谱包一律不内置：
 * 全部由外部拷贝进 Android/data/…/files/sheets/（见 SheetFetcher）。
 *
 * 资产条目名必须是 "assets/algo_assets/sound/..." 这种正斜杠形式，
 * AssetManager 才能识别子目录。Windows 下用 jar.exe 打包会写成反斜杠，
 * 导致 AssetManager.list() 返回空、open() 抛 FileNotFoundException；
 * 所以打包统一用 pack.py（Python zipfile），见 build.sh。
 */
public final class AppAssets {

    /**
     * 资产结构版本：assets 内容有变就 +1，强制重新解包。
     *
     * ⚠ 不只是"结构"变了才 +1：`files/algo/**` 是**一次性解压**出来的副本，
     * native 引擎读的是那份、不是 APK 里的 assets。所以改了 `ALGConf.json`
     * （判定参数）、模型或音源，都必须 +1，否则设备上永远是旧副本，
     * `eva.json` 里的 `params` 不会跟着更新。
     */
    private static final int VERSION = 7;

    private AppAssets() {
    }

    public static synchronized void install(Context ctx) throws IOException {
        File files = ctx.getFilesDir();
        File mark = new File(files, ".assets_v" + VERSION);
        if (mark.exists()) return;

        AssetManager am = ctx.getAssets();
        copyDir(am, "algo_assets", new File(files, "algo"));
        // 曲谱不在 APK 内，也不下载：只从外部拷贝的 sheets 目录里读

        File[] old = files.listFiles();
        if (old != null) {
            for (File f : old) {
                if (f.getName().startsWith(".assets_v")) f.delete();
            }
        }
        try (OutputStream os = new FileOutputStream(mark)) {
            os.write(new byte[]{1});
        }
    }

    private static void copyDir(AssetManager am, String assetDir, File dst) throws IOException {
        List<String> rel = new ArrayList<>();
        collect(am, assetDir, "", rel);
        if (rel.isEmpty()) throw new IOException("资产目录为空：" + assetDir);

        for (String r : rel) {
            File out = new File(dst, r);
            File parent = out.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("无法创建目录 " + parent);
            }
            try (InputStream in = am.open(assetDir + "/" + r);
                 OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
        }
    }

    /**
     * 递归枚举。AssetManager.list() 对「文件」返回空数组、对「目录」返回子项名，
     * 因此用「有没有子项」来判断叶子节点。
     *
     * 不要再引入构建期清单做兜底：`list()` 返回空数组时这里会 `add(prefix)`、
     * `rel` 恒非空，返回 null 时又直接抛异常，兜底分支永远走不到。真遇到
     * `list()` 不可用，只会得到「资产目录为空」或 open 失败，要修这里而不是加清单。
     */
    private static void collect(AssetManager am, String path, String prefix, List<String> out)
            throws IOException {
        String[] kids = am.list(path);
        if (kids == null) throw new IOException("无法列出资产路径：" + path);
        if (kids.length == 0) {
            out.add(prefix);
            return;
        }
        for (String k : kids) {
            collect(am, path + "/" + k, prefix.isEmpty() ? k : prefix + "/" + k, out);
        }
    }
}
