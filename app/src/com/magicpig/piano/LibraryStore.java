package com.magicpig.piano;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 本地曲库状态，**拆成三个互不影响的文件**：
 *
 * <pre>
 * files/recent.json      最近打开
 *   { "v": 1,
 *     "sheets": { "<乐曲uuid>": { "played": 1792…, "m": 12, "o": 3, "v": 0 } } }
 *
 * files/fav_sheets.json  收藏的乐曲
 *   { "v": 1, "sheets": ["uuid", …] }
 *
 * files/fav_albums.json  收藏的曲集
 *   { "v": 1, "albums": ["uuid", …] }
 * </pre>
 *
 * 为什么拆三个：三类数据生命周期不同，改一类不该牵动另两类
 * 分开存也让单个文件损坏时波及面更小。写入是**按需分文件**的——点收藏只重写
 * fav_sheets.json，弹一首曲子只重写 recent.json。
 *
 * 通用约定：
 *  - **一律以 uuid 为键**，不存文件路径（换包名/换存储目录就失效过）；
 *  - **不存曲名**，名字一律从内置目录（SheetCatalog）现取；
 *  - played 给列表排序。
 */
public final class LibraryStore {

    private static final String TAG = "LibraryStore";

    private static final String F_RECENT = "recent.json";
    private static final String F_FAV_SHEETS = "fav_sheets.json";
    private static final String F_FAV_ALBUMS = "fav_albums.json";

    /** 这两个文件名历史上改过几次，首次启动时按顺序找一遍并并进来 */
    private static final String[] OLD_FAV_SHEET_FILES =
            {"favorite_sheets.json", "favorite_songs.json"};
    private static final String[] OLD_FAV_ALBUM_FILES =
            {"favorite_albums.json", "favorite_collections.json"};

    private static final int VERSION = 1;
    /** 最近打开最多存几条（超出的在落盘时按 played 倒序丢掉） */
    private static final int MAX_RECENT = 50;

    private static volatile LibraryStore sInst;

    /** 一首曲子的本地状态。 */
    private static final class Entry {
        long played;          // 最后一次打开（0 = 没打开过）
        // 上次退出曲谱时光标所在的音符（-1 = 没记录过）。重新进入时自动定位过去。
        int curMeasure = -1;
        int curOffset;
        int curVoice;
        // 上次退出时仍生效的选段（secOn=0 表示没有）。重新进入时连括号一起恢复。
        boolean secOn;
        int secStartM = -1, secStartO, secStartV;
        int secEndM = -1, secEndO, secEndV;
    }

    private final Context mCtx;
    private final File mRecentFile;
    private final File mFavSheetsFile;
    private final File mFavAlbumsFile;

    private final Map<String, Entry> mRecent = new LinkedHashMap<>();
    private final Set<String> mFavSheets = new LinkedHashSet<>();
    private final Set<String> mFavAlbums = new LinkedHashSet<>();
    /** 单线程写盘：同一文件的多次 save 严格按提交顺序执行，旧快照不会覆盖新快照。 */
    private final java.util.concurrent.ExecutorService mWriter =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "library-store");
                t.setDaemon(true);
                return t;
            });

    private LibraryStore(Context ctx) {
        mCtx = ctx.getApplicationContext();
        File dir = mCtx.getFilesDir();
        mRecentFile = new File(dir, F_RECENT);
        mFavSheetsFile = new File(dir, F_FAV_SHEETS);
        mFavAlbumsFile = new File(dir, F_FAV_ALBUMS);
        load();
        migrateRenamedFiles();
    }

    /**
     * 处理改过名字的存档文件。**两类收藏必须分开迁**，绝不能像上一版那样
     * 用同一个循环，否则会把"收藏曲集"的 uuid 塞进"收藏乐曲"里。
     */
    private void migrateRenamedFiles() {
        // 收藏乐曲：favorite_songs.json / favorite_sheets.json → fav_sheets.json
        renameFileIfNeeded(OLD_FAV_SHEET_FILES, mFavSheetsFile, mFavSheets,
                new String[]{"sheets", "songs"}, true);
        // 收藏曲集：favorite_collections.json / favorite_albums.json → fav_albums.json
        renameFileIfNeeded(OLD_FAV_ALBUM_FILES, mFavAlbumsFile, mFavAlbums,
                new String[]{"albums", "collections"}, false);
    }

    private void renameFileIfNeeded(String[] oldNames, File newFile, Set<String> target,
                                    String[] keys, boolean isFavSheets) {
        if (newFile.isFile()) return;
        for (String oldName : oldNames) {
            File old = new File(mCtx.getFilesDir(), oldName);
            if (!old.isFile()) continue;
            try {
                JSONObject o = new JSONObject(readAll(old));
                JSONArray arr = null;
                for (String k : keys) {
                    arr = o.optJSONArray(k);
                    if (arr != null) break;
                }
                addAll(target, arr);
                if (isFavSheets) saveFavSheets();
                else saveFavAlbums();
                if (old.delete()) Log.i(TAG, "已把 " + oldName + " 并入 " + newFile.getName());
            } catch (Throwable t) {
                Log.w(TAG, "迁移 " + oldName + " 失败（忽略）", t);
            }
            return;
        }
    }

    public static LibraryStore get(Context ctx) {
        if (sInst == null) {
            synchronized (LibraryStore.class) {
                if (sInst == null) sInst = new LibraryStore(ctx.getApplicationContext());
            }
        }
        return sInst;
    }

    // ---------------- 最近打开 ----------------

    /** 记一次"打开"：只更新时间戳。 */
    public synchronized void touch(String uuid) {
        if (uuid == null) return;
        entry(uuid).played = System.currentTimeMillis();
        saveRecent();
    }

    public synchronized long playedAt(String uuid) {
        Entry e = mRecent.get(uuid);
        return e == null ? 0L : e.played;
    }

    /** 打开过、且曲谱包还在本地的曲子，按最后打开时间倒序。返回 uuid。 */
    public synchronized List<String> recent() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Entry> kv : mRecent.entrySet()) {
            if (kv.getValue().played <= 0) continue;
            if (SheetFetcher.localEnc(mCtx, kv.getKey()) == null) continue;
            out.add(kv.getKey());
        }
        // 上限 50（旧存档可能超过）
        Collections.sort(out, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                long pa = playedAt(a), pb = playedAt(b);
                if (pa != pb) return pa > pb ? -1 : 1;
                return a.compareTo(b);
            }
        });
        if (out.size() > MAX_RECENT) {
            out = new ArrayList<>(out.subList(0, MAX_RECENT));
        }
        return out;
    }

    // ---------------- 光标位置 ----------------

    /**
     * 上次退出曲谱时光标所在的音符，返回 {@code {measure, offset, voice}}。
     * 从没记录过（或从没定位过）返回 null。
     */
    public synchronized int[] cursor(String uuid) {
        Entry e = uuid == null ? null : mRecent.get(uuid);
        if (e == null || e.curMeasure < 0) return null;
        return new int[]{e.curMeasure, e.curOffset, e.curVoice};
    }


    // ---------------- 选段 ----------------

    /**
     * 上次退出时仍生效的选段，返回
     * {@code {startM, startO, startV, endM, endO, endV}}；没有返回 null。
     */
    public synchronized int[] section(String uuid) {
        Entry e = uuid == null ? null : mRecent.get(uuid);
        if (e == null || !e.secOn || e.secStartM < 0 || e.secEndM < 0) return null;
        return new int[]{e.secStartM, e.secStartO, e.secStartV,
                e.secEndM, e.secEndO, e.secEndV};
    }

    /**
     * 一次写完"阅读现场"：选段 + 光标位置，只落盘一次。
     *
     * 选段和光标同在 recent.json 里，一次写完只落盘一次。分成两次写会各跑一遍
     * "序列化最多 50 条记录 + 写临时文件 + fsync"，
     * 退出曲谱时白等一趟 IO。
     *
     * @param sec     `{startM,startO,startV,endM,endO,endV}`；null / 长度不足 = 清除选段
     * @param measure 光标所在小节；**&lt; 0 表示这次没定位过**，保留上次的记录
     */
    public synchronized void saveReadingState(String uuid, int[] sec,
                                             int measure, int offset, int voice) {
        if (uuid == null) return;
        Entry e = mRecent.get(uuid);
        if (e == null) return;          // 没打开过（没 touch 过）就不新建记录
        if (sec == null || sec.length < 6) {
            e.secOn = false;
        } else {
            e.secOn = true;
            e.secStartM = sec[0];
            e.secStartO = sec[1];
            e.secStartV = sec[2];
            e.secEndM = sec[3];
            e.secEndO = sec[4];
            e.secEndV = sec[5];
        }
        if (measure >= 0) {
            e.curMeasure = measure;
            e.curOffset = Math.max(0, offset);
            e.curVoice = Math.max(0, voice);
        }
        saveRecent();
    }

    /**
     * 清掉这首曲子的"阅读现场"（选段 + 光标），只留「最近打开」的时间戳。
     *
     * 用于光标停在**曲首**（第 1 小节第 1 个音）时退出、以及用户明确拒绝载入
     * 上次进度时（{@code PlayerActivity.finishRestore(false)}）：那个位置等价于
     * 没有进度，留着它下次进谱会弹一个"是否载入上次光标"、载入完还是曲首,
     * 纯属打扰。清掉之后 `cursor()` / `section()` 都返回 null，进谱直接不询问。
     * ⚠ 不删 `mRecent` 里的整条记录 —— 最近打开列表还要用它。
     */
    public synchronized void clearReadingState(String uuid) {
        if (uuid == null) return;
        Entry e = mRecent.get(uuid);
        if (e == null) return;
        e.secOn = false;
        e.curMeasure = -1;
        e.curOffset = 0;
        e.curVoice = 0;
        saveRecent();
    }

    // ---------------- 收藏 ----------------

    public synchronized boolean isFavSheet(String uuid) {
        return uuid != null && mFavSheets.contains(uuid);
    }

    /** 切换乐曲收藏，返回切换后是否已收藏。只重写 fav_sheets.json。 */
    public synchronized boolean toggleSheet(String uuid) {
        if (uuid == null) return false;
        boolean added = mFavSheets.add(uuid);
        if (!added) mFavSheets.remove(uuid);
        saveFavSheets();
        return added;
    }

    public synchronized List<String> favSheets() {
        return new ArrayList<>(mFavSheets);
    }

    public synchronized boolean isFavAlbum(String uuid) {
        return uuid != null && mFavAlbums.contains(uuid);
    }

    /** 切换曲集收藏，返回切换后是否已收藏。只重写 fav_albums.json。 */
    public synchronized boolean toggleAlbum(String uuid) {
        if (uuid == null) return false;
        boolean added = mFavAlbums.add(uuid);
        if (!added) mFavAlbums.remove(uuid);
        saveFavAlbums();
        return added;
    }

    public synchronized List<String> favAlbums() {
        return new ArrayList<>(mFavAlbums);
    }

    // ---------------- 工具 ----------------

    /** .../sheets/&lt;uuid&gt;/sheet.xml.enc → &lt;uuid&gt;；不是这个布局返回 null。 */
    public static String uuidOfPath(String encPath) {
        if (encPath == null) return null;
        File dir = new File(encPath).getParentFile();
        if (dir == null) return null;
        String name = dir.getName();
        return name.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                ? name : null;
    }

    private Entry entry(String uuid) {
        Entry e = mRecent.get(uuid);
        if (e == null) {
            e = new Entry();
            mRecent.put(uuid, e);
        }
        return e;
    }

    // ---------------- 读 ----------------

    private void load() {
        // 最近打开
        JSONObject o = readJson(mRecentFile);
        JSONObject sheets = o == null ? null : o.optJSONObject("sheets");
        if (sheets == null && o != null) sheets = o.optJSONObject("songs");   // 旧键名
        if (sheets != null) {
            for (java.util.Iterator<String> it = sheets.keys(); it.hasNext(); ) {
                String uuid = it.next();
                JSONObject jo = sheets.optJSONObject(uuid);
                if (jo == null) continue;
                Entry e = new Entry();
                e.played = jo.optLong("played");
                e.curMeasure = jo.optInt("m", -1);
                e.curOffset = jo.optInt("o", 0);
                e.curVoice = jo.optInt("v", 0);
                e.secOn = jo.optBoolean("sec", false);
                e.secStartM = jo.optInt("sm", -1);
                e.secStartO = jo.optInt("so", 0);
                e.secStartV = jo.optInt("sv", 0);
                e.secEndM = jo.optInt("em", -1);
                e.secEndO = jo.optInt("eo", 0);
                e.secEndV = jo.optInt("ev", 0);
                mRecent.put(uuid, e);
            }
        }
        // 收藏乐曲
        o = readJson(mFavSheetsFile);
        JSONArray favSheets = o == null ? null : o.optJSONArray("sheets");
        if (favSheets == null && o != null) favSheets = o.optJSONArray("songs");  // 旧键名
        addAll(mFavSheets, favSheets);
        // 收藏曲集
        o = readJson(mFavAlbumsFile);
        addAll(mFavAlbums, o == null ? null : o.optJSONArray("albums"));

        if (!mRecent.isEmpty() || !mFavSheets.isEmpty() || !mFavAlbums.isEmpty()) {
            Log.i(TAG, "已载入：最近 " + mRecent.size() + " 收藏乐曲 " + mFavSheets.size()
                    + " 收藏曲集 " + mFavAlbums.size());
        }
    }

    private static JSONObject readJson(File f) {
        if (!f.isFile()) return null;
        try {
            return new JSONObject(readAll(f));
        } catch (Throwable t) {
            Log.e(TAG, "读取 " + f.getName() + " 失败", t);
            return null;
        }
    }

    private static void addAll(Set<String> dst, JSONArray arr) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i);
            if (s != null && !s.isEmpty()) dst.add(s);
        }
    }


    // ---------------- 写 ----------------

    private void saveRecent() {
        try {
            JSONObject sheets = new JSONObject();
            // 只留最近 50 首：打开次数一多，recent.json 会无限增长。
            // 按 played 倒序取前 50，超出的整条丢掉。
            List<Map.Entry<String, Entry>> entries = new ArrayList<>(mRecent.entrySet());
            java.util.Collections.sort(entries,
                    (a, b) -> Long.compare(b.getValue().played, a.getValue().played));
            int end = Math.min(entries.size(), MAX_RECENT);
            for (int i = 0; i < end; i++) {
                Map.Entry<String, Entry> kv = entries.get(i);
                Entry e = kv.getValue();
                if (e.played <= 0) continue;   // 空壳不落盘
                JSONObject jo = new JSONObject();
                jo.put("played", e.played);
                if (e.curMeasure >= 0) {
                    jo.put("m", e.curMeasure);
                    jo.put("o", e.curOffset);
                    jo.put("v", e.curVoice);
                }
                if (e.secOn && e.secStartM >= 0 && e.secEndM >= 0) {
                    jo.put("sec", true);
                    jo.put("sm", e.secStartM);
                    jo.put("so", e.secStartO);
                    jo.put("sv", e.secStartV);
                    jo.put("em", e.secEndM);
                    jo.put("eo", e.secEndO);
                    jo.put("ev", e.secEndV);
                }
                sheets.put(kv.getKey(), jo);
            }
            // 内存表也裁到同样的 MAX_RECENT 条：mRecent 只在 touch() 里增、
            // 从不减；落盘既然只留 50 条，内存也保持一致。
            for (int i = end; i < entries.size(); i++) {
                mRecent.remove(entries.get(i).getKey());
            }
            JSONObject o = new JSONObject();
            o.put("v", VERSION);
            o.put("sheets", sheets);
            writeAsync(mRecentFile, o.toString());
        } catch (Throwable t) {
            Log.e(TAG, "序列化 " + F_RECENT + " 失败", t);
        }
    }

    private void saveFavSheets() {
        try {
            JSONObject o = new JSONObject();
            o.put("v", VERSION);
            o.put("sheets", new JSONArray(new ArrayList<>(mFavSheets)));
            writeAsync(mFavSheetsFile, o.toString());
        } catch (Throwable t) {
            Log.e(TAG, "序列化 " + F_FAV_SHEETS + " 失败", t);
        }
    }

    private void saveFavAlbums() {
        try {
            JSONObject o = new JSONObject();
            o.put("v", VERSION);
            o.put("albums", new JSONArray(new ArrayList<>(mFavAlbums)));
            writeAsync(mFavAlbumsFile, o.toString());
        } catch (Throwable t) {
            Log.e(TAG, "序列化 " + F_FAV_ALBUMS + " 失败", t);
        }
    }

    /** 先写临时文件再 rename，避免写一半被杀导致档案损坏。 */
    private void writeAsync(final File target, final String json) {
        mWriter.execute(() -> {
            try {
                File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
                try (FileOutputStream fo = new FileOutputStream(tmp)) {
                    fo.write(json.getBytes(StandardCharsets.UTF_8));
                    fo.flush();
                    fo.getFD().sync();
                }
                if (!tmp.renameTo(target)) {
                    // rename 失败（个别 FUSE/sdcardfs 上"目标已存在"会返回 false）：
                    // **不能**退回"直接覆写目标" —— 那等于放弃原子性，写到一半被杀
                    // 就留下半截 JSON，下次启动 readJson 解析失败 → 整份存档当成空的。
                    // 正确做法是先把旧文件删掉腾位置，再 rename 一次；仍失败才降级覆写，
                    // 这条路径至少只剩一次"极短窗口内没有文件"，不会出现坏文件。
                    Log.w(TAG, "rename 失败，先删旧文件再试：" + target.getName());
                    //noinspection ResultOfMethodCallIgnored
                    target.delete();
                    if (!tmp.renameTo(target)) {
                        Log.w(TAG, "二次 rename 仍失败，退回直接覆写：" + target.getName());
                        try (FileOutputStream fo = new FileOutputStream(target)) {
                            fo.write(json.getBytes(StandardCharsets.UTF_8));
                            fo.flush();
                            fo.getFD().sync();
                        }
                        //noinspection ResultOfMethodCallIgnored
                        tmp.delete();
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "写入 " + target.getName() + " 失败", t);
            }
        });
    }

    private static String readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        }
    }
}
