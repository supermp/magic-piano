package com.magicpig.piano;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 原版「曲集」目录（分类 → 标签 → 曲集/专辑 → 乐曲）。
 *
 * 数据来源：原版 App 登录后调用的接口（2026-10 抓包）
 *   GET /library/categories
 *   GET /albums_v3/tags/<分类uuid>?instrument=piano&area=<地区uuid>
 *   GET /albums_v3/albums/<标签uuid>?instrument=piano&area=<地区uuid>
 *   GET /albums_v3/sheets/<曲集uuid>?instrument=piano&area=<地区uuid>
 * 抓取后由 tools/update_catalog.py 压成 v2 目录，经资源包或 tools/sync_devices.py
 * 铺到设备 `files/catalog.json`；**不内置进 APK**，运行时零网络。
 * 曲集/乐曲数量以 library/catalog.json 为准，别在代码或注释里写死。
 *
 * 乐曲只留 uuid + 名字 + 作曲家 + 页码：真正要弹的时候再按 uuid 取曲谱包
 * （见 SheetFetcher）。
 */
public final class SheetCatalog {

    private static final String TAG = "SheetCatalog";
    /** 外部资源目录里的目录文件名（与乐谱、封面同处一个存储位置） */
    private static final String EXT = "catalog.json";

    /** 一首乐曲。 */
    public static final class Sheet {
        public final String uuid;
        public final String name;
        /** 作曲家（原版乐曲列表第二行就是它） */
        public final String composer;
        /** 在曲集里的页码，原版右侧那一列 */
        public final String page;
        /** 最近更新时间（接口的 mtime，ISO8601 字符串，可能为空） */
        public final String mtime;

        Sheet(String uuid, String name, String composer, String page, String mtime) {
            this.uuid = uuid;
            this.name = name;
            this.composer = composer;
            this.page = page;
            this.mtime = mtime == null ? "" : mtime;
        }

        /**
         * 展示用的曲名。
         *
         * 拜厄、车尔尼、哈农这类练习曲集，出版社上传时常常只填编号（"1"、"2"…），
         * 全库 18369 首里有 333 首是这样（集中在 8 本），列表里只剩一个光秃秃的
         * 数字，很难读。这里补成「第 N 条」；其余曲名原样返回。
         */
        public String displayName() {
            if (name != null && NUMERIC.matcher(name).matches()) {
                return "第 " + name.trim() + " 条";
            }
            return name == null ? "" : name;
        }

        private static final java.util.regex.Pattern NUMERIC =
                java.util.regex.Pattern.compile("\\s*\\d{1,3}\\s*");
    }

    /** 一个曲集（原版叫 album / 书），下面挂若干乐曲。 */
    public static final class Album {
        public final String uuid;
        public final String name;
        /** 封面文件 uuid，对应 assets/covers/<uuid>.jpg */
        public final String cover;
        public final String publisher;
        public final List<Sheet> sheets = new ArrayList<>();

        Album(String uuid, String name, String cover, String publisher) {
            this.uuid = uuid;
            this.name = name;
            this.cover = cover;
            this.publisher = publisher;
        }
    }

    /** 标签（如「中国音协」「央院考级」），曲集的分组。 */
    public static final class Tag {
        public final String uuid;
        public final String name;
        public final List<Album> albums = new ArrayList<>();

        Tag(String uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }
    }

    /** 左侧栏的大分类（考级 / 音阶练习曲 / 名家 / 流行 / 重奏&协奏曲）。 */
    public static final class Category {
        public final String uuid;
        public final String name;
        public final List<Tag> tags = new ArrayList<>();

        Category(String uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }

        

        
    }

    private static volatile List<Category> sCats;
    /**
     * uuid 索引：catalog.json 解析完建一次，之后 uuid 查名/查曲集都是 O(1)。
     * 之前 sheetByUuid/albumOfSheet 是四层循环全树线性扫 —— 17361 首一查就是
     * 几万次字符串比较，"最近打开"一页 19 行每行还要查两遍（名字+所属曲集）。
     * 多标签曲集会重复出现，用 putIfAbsent 取第一次（曲谱包本来就是 1 曲集:1 曲子）。
     */
    private static volatile Map<String, Sheet> sSheetIdx;
    private static volatile Map<String, Album> sAlbumIdx;
    /** 乐曲 uuid → 它所属的曲集（albumOfSheet 用；注意键是乐曲的 uuid） */
    private static volatile Map<String, Album> sSheetAlbumIdx;

    private SheetCatalog() {
    }

    /** 懒加载并缓存；只在 UI 线程调用（解析 1.65MB JSON 约几十毫秒）。 */
    public static List<Category> all(Context c) {
        List<Category> r = sCats;
        if (r == null) {
            r = parse(c);
            sCats = r;
            buildIndexes(r);
        }
        return r;
    }

    /**
     * 丢弃内存里的目录与 uuid 索引，下次 all() 重新从磁盘解析。
     *
     * 资源包导入完成后必须调：目录是**进程级缓存**的（sCats 一旦建好就不再读盘），
     * 不重建的话刚导入的目录这一辈子都不会生效，要杀进程才行。
     */
    public static void reload(Context c) {
        sCats = null;
        sSheetIdx = null;
        sAlbumIdx = null;
        sSheetAlbumIdx = null;
        all(c);
    }

    /**
     * 解析完成后建曲集/乐曲/乐曲所属曲集三张 uuid 索引。
     *
     * ⚠ 三张表**必须用同一个"第一次优先"口径**（`putIfAbsent`）：同一首曲子可能
     * 出现在多个曲集里（多标签、精选集重复收录），而 `sheetByUuid` 与
     * `albumOfSheet` 是配套用的 —— 一个取曲名、一个取"所属曲集"给副标题。
     * 曾经 si 用 putIfAbsent、sa 用 put，两边指向不同曲集，副标题里的曲集名
     * 就跟曲名/详情页对不上了。
     */
    private static void buildIndexes(List<Category> cats) {
        Map<String, Sheet> si = new HashMap<>();
        Map<String, Album> ai = new HashMap<>();
        Map<String, Album> sa = new HashMap<>();
        for (Category cat : cats) {
            for (Tag t : cat.tags) {
                for (Album a : t.albums) {
                    ai.putIfAbsent(a.uuid, a);
                    for (Sheet s : a.sheets) {
                        si.putIfAbsent(s.uuid, s);
                        sa.putIfAbsent(s.uuid, a);
                    }
                }
            }
        }
        sSheetIdx = si;
        sAlbumIdx = ai;
        sSheetAlbumIdx = sa;
    }

    /** 按曲集 uuid 找曲集（供详情页/收藏页用）。 */
    public static Album albumByUuid(Context c, String albumUuid) {
        all(c);
        return sAlbumIdx == null ? null : sAlbumIdx.get(albumUuid);
    }

    /** 按乐曲 uuid 反查它属于哪本曲集（收藏页要把乐曲按曲集归类时用）。 */
    public static Album albumOfSheet(Context c, String sheetUuid) {
        all(c);
        return sSheetAlbumIdx == null ? null : sSheetAlbumIdx.get(sheetUuid);
    }

    /** 按乐曲 uuid 取名字（收藏列表用）；不在目录里返回 null。 */
    public static Sheet sheetByUuid(Context c, String sheetUuid) {
        all(c);
        return sSheetIdx == null ? null : sSheetIdx.get(sheetUuid);
    }

    /**
     * 目录数据源：**只从设备存储读**（外部专属目录优先，内部存储兼容早期版本）。
     *
     * 目录和乐谱、封面放在同一个位置（files/ 下），更新曲库只要换资源包，
     * 不用重打 APK。APK 里不再内置 catalog.json（核对：assets 只剩
     * algo_assets / fonts / sheetShow / ui），所以早先那个 `am.open("catalog.json")`
     * 兜底已经删掉——留着只会每次多抛一次 FileNotFoundException。
     */
    private static InputStream openCatalog(Context c) {
        File f = catalogFile(c);
        if (f == null) return null;
        try {
            return new java.io.FileInputStream(f);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 目录文件位置：**外部专属目录优先**（与乐谱、封面同一个位置），
     * 内部存储作为兼容（早期版本导到过内部），都没有返回 null。
     */
    private static File catalogFile(Context c) {
        File ext = c.getExternalFilesDir(null);
        if (ext != null) {
            File f = new File(ext, EXT);
            if (f.isFile() && f.length() > 0) return f;
        }
        File in = new File(c.getFilesDir(), EXT);
        if (in.isFile() && in.length() > 0) return in;
        return null;
    }

    /**
     * 外部资源目录里的目录文件是否存在。
     * HomeActivity 用它判断"资源包导没导入"，没导入就引导用户去选包。
     */
    public static boolean hasExternalCatalog(Context c) {
        return catalogFile(c) != null;
    }

    private static List<Category> parse(Context c) {
        List<Category> out = new ArrayList<>();
        try {
            InputStream in = openCatalog(c);
            if (in == null) {
                // 资源包还没导入：目录为空。别当解析失败反复打日志，
                // 由 HomeActivity 检测到空目录后引导用户导入。
                return out;
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            JSONObject root = new JSONObject(bos.toString("UTF-8"));
            JSONArray cats = root.getJSONArray("categories");
            for (int i = 0; i < cats.length(); i++) {
                JSONObject jc = cats.getJSONObject(i);
                Category cat = new Category(jc.getString("uuid"), jc.getString("name"));
                JSONArray tags = jc.optJSONArray("tags");
                if (tags == null) continue;
                for (int j = 0; j < tags.length(); j++) {
                    JSONObject jt = tags.getJSONObject(j);
                    Tag tag = new Tag(jt.getString("uuid"), jt.getString("name"));
                    // 数据文件里的键仍叫 albums —— 它对应上游接口的 albums_v3，
                    // 是数据格式，不是我们的变量名（别跟着 Album 一起改）。
                    JSONArray cols = jt.optJSONArray("albums");
                    if (cols == null) continue;
                    for (int k = 0; k < cols.length(); k++) {
                        JSONObject ja = cols.getJSONObject(k);
                        Album col = new Album(ja.getString("uuid"), ja.getString("name"),
                                ja.optString("cover", ""), ja.optString("publisher", ""));
                        JSONArray shs = ja.optJSONArray("sheets");
                        if (shs != null) {
                            for (int m = 0; m < shs.length(); m++) {
                                JSONArray s = shs.getJSONArray(m);
                                col.sheets.add(new Sheet(s.getString(0), s.optString(1, ""),
                                        s.optString(2, ""), s.optString(3, ""),
                                        s.optString(4, "")));
                            }
                        }
                        tag.albums.add(col);
                    }
                    cat.tags.add(tag);
                }
                out.add(cat);
            }
        } catch (Throwable t) {
            Log.e(TAG, "解析 catalog.json 失败", t);
        }
        return out;
    }
}
