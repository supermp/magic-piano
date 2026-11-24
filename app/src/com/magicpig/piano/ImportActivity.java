package com.magicpig.piano;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 资源包导入页。
 *
 * APK 只带程序和引擎资产（音源 + 模型），**不带任何曲谱数据**；
 * 目录、封面、乐谱全都来自用户选的一个资源包 zip，三者一起落到
 * files/ 下同一个存储位置（和乐谱文件放一起）。
 *
 * 流程：
 *   1. 进 App 发现 files/catalog.json 不存在 → 到这一页
 *   2. 点「选择资源包」→ 系统文件选择器（SAF，**不需要任何存储权限**）
 *   3. 后台顺序解压 → 进度条按「已读字节 / 包总字节」走（总大小从
 *      OpenableColumns.SIZE 直接拿，不用预扫一遍 zip）
 *   4. 完成 → 回曲库页
 *
 * 为什么必须解压而不是直接读 zip 条目：
 * 引擎的 decryptSheet(path) 与 ufpCreateMP(..., path, ...) 只认**真实文件路径**，
 * 而 SAF 只给 content:// URI、zip 条目也没有路径。所以只能落地。
 */
public class ImportActivity extends Activity {

    private static final int REQ_PICK = 91;
    private static final int BUF = 256 * 1024;
    private static final String TAG = "ImportActivity";

    private TextView mStatus;
    private TextView mDetail;
    private ProgressBar mBar;
    private Ui.CircleButton mPickBtn;
    private volatile boolean mCancelled;
    private Thread mWorker;
    /** 本次导入的来源（SAF 的 URI 或调试用的文件）；成功后会被删掉 */
    private Uri mSrcUri;
    private File mSrcFile;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        goFullscreen();
        buildUi();
        // 刚才导入过（比如从这一页跳回来）就直接回曲库
        if (SheetCatalog.hasExternalCatalog(this)) {
            gotoLibrary();
            return;
        }
        // 调试入口：--es res_path <绝对路径>（见 startImportFile 的说明）
        String dbg = getIntent().getStringExtra("res_path");
        if (dbg != null && !dbg.isEmpty()) {
            startImportFile(dbg);
        }
    }

    private void goFullscreen() {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF7E3BE);
        root.setGravity(Gravity.CENTER);
        int pad = Ui.dp(this, 60);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(Ui.dp(this, 40), Ui.dp(this, 32), Ui.dp(this, 40), Ui.dp(this, 32));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFCE8);
        bg.setCornerRadius(Ui.dp(this, 16));
        bg.setStroke(Ui.dp(this, 1), Ui.alpha(Ui.BROWN, 0.18f));
        card.setBackground(bg);
        // 宽度写死：WRAP_CONTENT 取的是"最宽子视图"，而进度条初始是 GONE、
        // 按钮又只有 90dp，结果卡片被压到 172dp，标题和说明全被截断
        // （实测标题只画出「还没有载」四个字）。给一个够宽的固定值。
        card.setLayoutParams(new LinearLayout.LayoutParams(
                Ui.dp(this, 620), LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setTypeface(Ui.titleFont(this));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(this, 22f));
        title.setTextColor(Ui.BROWN_DARK);
        title.setText("还没有载入曲谱资源");
        title.setGravity(Gravity.CENTER);
        card.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));


        mPickBtn = Ui.circleButton(this, "选择资源包", "ic_sheet_trial.png");
        mPickBtn.root.setOnClickListener(v -> pickBundle());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 22);
        card.addView(mPickBtn.root, blp);

        mBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mBar.setMax(1000);
        mBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 6));
        plp.topMargin = Ui.dp(this, 24);
        card.addView(mBar, plp);

        mStatus = new TextView(this);
        mStatus.setTypeface(Ui.bodyFont(this));
        mStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(this, 13f));
        mStatus.setTextColor(Ui.BROWN_DARK);
        mStatus.setGravity(Gravity.CENTER);
        mStatus.setVisibility(View.GONE);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.dp(this, 12);
        slp.width = LinearLayout.LayoutParams.MATCH_PARENT;
        card.addView(mStatus, slp);

        mDetail = new TextView(this);
        mDetail.setTypeface(Ui.bodyFont(this));
        mDetail.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(this, 11f));
        mDetail.setTextColor(Ui.INK_SOFT);
        mDetail.setGravity(Gravity.CENTER);
        mDetail.setVisibility(View.GONE);
        card.addView(mDetail, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(card);
        setContentView(root);
    }

    private void pickBundle() {
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("application/zip");
        try {
            startActivityForResult(it, REQ_PICK);
        } catch (Exception e) {
            // 某些设备不认 application/zip，退到通配
            Intent it2 = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            it2.addCategory(Intent.CATEGORY_OPENABLE);
            it2.setType("*/*");
            startActivityForResult(it2, REQ_PICK);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        startImport(uri);
    }

    private void startImport(Uri uri) {
        long total = querySize(uri);
        startImportCommon(total, uri, null);
    }

    /**
     * 从**文件路径**直接导入（调试/自动化用）。
     *
     * 正常用户路径是 SAF 选文件（拿到的只有 content:// URI）。但 adb 自动化时
     * 没法在系统文件选择器里点选，所以留了这个入口：只有显式传 `res_path`
     * 才生效，正常启动不会走到这里。
     */
    private void startImportFile(String path) {
        File f = new File(path);
        if (!f.isFile()) {
            mStatus.setVisibility(View.VISIBLE);
            mStatus.setText("找不到这个文件：" + path);
            return;
        }
        startImportCommon(f.length(), null, f);
    }

    private void startImportCommon(long total, Uri uri, File file) {
        // 防重入：系统选择器偶尔会连回两次 onActivityResult，两条线程同时往
        // 同一个目录解压会互相覆盖半截文件（大小对不上 → 下次断点也判不出来）。
        if (mWorker != null && mWorker.isAlive()) {
            post("上一包还在导入中", "等它结束，或退出本页再重新选包");
            return;
        }
        mSrcUri = uri;
        mSrcFile = file;
        mPickBtn.setUiEnabled(false);
        mBar.setVisibility(View.VISIBLE);
        mBar.setProgress(0);
        mStatus.setVisibility(View.VISIBLE);
        mDetail.setVisibility(View.VISIBLE);
        mStatus.setText("正在准备…");
        mDetail.setText(total > 0 ? ("资源包大小 " + mb(total)) : "");
        mCancelled = false;
        final Uri fu = uri;
        final File ff = file;
        mWorker = new Thread(() -> doImport(fu, ff, total), "res-import");
        mWorker.start();
    }

    private long querySize(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(
                uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.SIZE);
                if (i >= 0) return c.getLong(i);
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /**
     * 读已有文件的 CRC32；读失败返回 -1（此时不做“已存在”跳过）。
     *
     * 资源包里的 sheet.xml.enc 是 ZIP_STORED，ZipInputStream 能直接拿到 entry 的
     * CRC；只比大小会把「新包同大小、内容不同」的旧 enc 当成已完成。
     * 复用调用方的 buffer，避免每条目各分配一份 256KB。
     */
    private long crc32(File f, byte[] buf) {
        CRC32 crc = new CRC32();
        try (FileInputStream in = new FileInputStream(f)) {
            int n;
            while ((n = in.read(buf)) > 0) crc.update(buf, 0, n);
        } catch (Exception e) {
            return -1;
        }
        return crc.getValue();
    }

    private void doImport(Uri uri, File file, long total) {
        // 导到**外部专属目录**：和乐谱（SheetFetcher 的 files/sheets）同一个位置，
        // 用户要求"目录、封面、乐谱都放一块"。外部不可用时才退回内部。
        File base = getExternalFilesDir(null);
        if (base == null) base = getFilesDir();
        final File baseDir = base;
        int n = 0, skipped = 0, failed = 0;
        long read = 0;
        // int[] 是为了让进度节流值能被 maybeProgress 就地改写（局部变量传不进去）
        int[] lastPct = {-1};
        InputStream raw;
        try {
            raw = (file != null)
                    ? new java.io.FileInputStream(file)
                    : getContentResolver().openInputStream(uri);
        } catch (Exception e) {
            post("打不开这个资源包：" + e.getMessage(), "");
            return;
        }
        if (raw == null) {
            post("打不开这个资源包", "");
            return;
        }
        try (InputStream unused = raw) {
            ZipInputStream zis = new ZipInputStream(raw);
            byte[] buf = new byte[BUF];
            ZipEntry e;
            // processed 单独计数：进度刷新原来挂在"成功写入数 n"上，
            // 二次导入（绝大多数条目都在跳过）时 n 一直是 0，进度条纹丝不动。
            int processed = 0;
            while (!mCancelled && (e = zis.getNextEntry()) != null) {
                String name = e.getName();
                if (e.isDirectory() || name.endsWith("/")) continue;
                File out = safeDest(baseDir, name);
                if (out == null) continue;                 // 路径不合法，跳过
                final long size = e.getSize();
                final long crc = e.getCrc();
                processed++;
                if (size > 0 && out.isFile() && out.length() == size
                        && crc >= 0 && crc32(out, buf) == crc) {
                    skipped++;                              // 已有且大小、CRC 一致
                    read += size;
                    // 跳过也要刷进度：二次导入时绝大多数条目都走这一支，
                    // 不刷的话进度条从头到尾不动。
                    maybeProgress(total, read, processed, n, skipped, failed, lastPct);
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    failed++;
                    continue;
                }
                try (OutputStream os = new FileOutputStream(out)) {
                    int len;
                    while ((len = zis.read(buf)) > 0) os.write(buf, 0, len);
                } catch (Exception ex) {
                    // 单个文件失败不中断整体（个别谱面坏包不该让整包作废），
                    // 但**必须记账**：失败也算"导入成功"就会走到删源那条路，
                    // 用户手里的包没了、曲库里还缺几首（不可逆）。
                    // 半截文件一并删掉，否则下次判断会拿它当"已有且大小、CRC 一致"跳过。
                    failed++;
                    //noinspection ResultOfMethodCallIgnored
                    out.delete();
                    continue;
                }
                n++;
                read += size > 0 ? size : e.getCompressedSize();
                maybeProgress(total, read, processed, n, skipped, failed, lastPct);
            }
        } catch (Exception ex) {
            post("导入失败，资源包已保留：" + ex.getMessage(), "再点一次「选择资源包」可以继续，已导入的会跳过");
            runOnUiThread(() -> mPickBtn.setUiEnabled(true));
            return;
        }
        if (mCancelled) {
            post("已取消", "再点一次「选择资源包」可以继续");
            runOnUiThread(() -> mPickBtn.setUiEnabled(true));
            return;
        }
        // 成败判定：
        //  - 少了 catalog.json → 曲库根本打不开，算彻底失败：不删源、不进曲库，
        //    让用户重选（常见于选错文件、包还在复制中）。
        //  - 个别文件失败 → 曲库能用（那几首打不开），**但绝不能删源**：
        //    删了就再没机会补回来。仍然进曲库，界面上留一句"失败 N 个"。
        final boolean catalogOk = new File(baseDir, "catalog.json").isFile();
        if (!catalogOk) {
            final int fn = n, fs = skipped, ff = failed;
            runOnUiThread(() -> {
                mStatus.setText("导入未完成：" + fn + " 个文件成功"
                        + (fs > 0 ? "，" + fs + " 个已存在" : "")
                        + (ff > 0 ? "，" + ff + " 个失败" : "")
                        + "，缺少目录文件 catalog.json");
                mDetail.setText("资源包已保留。请确认选的是「资源包」而不是别的 zip，再点一次可继续");
                mPickBtn.setUiEnabled(true);
            });
            Log.w(TAG, "导入失败：缺 catalog.json（成功 " + fn + " 跳过 " + fs
                    + " 失败 " + ff + "，源包未删除）");
            return;
        }
        final int fn = n, fs = skipped, ff = failed;
        // 有失败项就保留源包；全部干净才删（前提是"装完了"，不是"跑完流程了"）。
        final boolean removed = ff == 0 && deleteSource();
        runOnUiThread(() -> {
            mBar.setProgress(1000);
            mStatus.setText(ff > 0
                    ? ("导入完成 " + fn + " 个文件，" + ff + " 个失败")
                    : ("导入完成，共 " + fn + " 个文件" + (fs > 0 ? "（跳过已有 " + fs + "）" : "")));
            mDetail.setText((ff > 0 ? "资源包已保留（里面有文件没解开）"
                    : (removed ? "资源包已删除" : "")) + "，正在打开曲库…");
        });
        Log.i(TAG, "导入完成：成功 " + fn + " 跳过 " + fs + " 失败 " + ff
                + "，删源=" + removed);
        // 目录数据换了要重建内存索引
        SheetCatalog.reload(this);
        mBar.postDelayed(this::gotoLibrary, 600);
    }

    /**
     * 每处理 50 个条目刷一次进度（"写成功"和"跳过已有"都算处理过）。
     *
     * 只报"已读 / 总量 / 百分比"：剩几分几秒是按瞬时速率估的，ZIP_STORED 包里
     * 文件大小差异极大，估出来忽快忽慢反而误导。
     */
    private void maybeProgress(long total, long read, int processed,
                               int n, int skipped, int failed, int[] lastPct) {
        if (processed % 50 != 0) return;
        final int pct = total > 0 ? (int) (1000L * read / total) : 0;
        if (pct == lastPct[0]) return;
        lastPct[0] = pct;
        final int fp = pct, fn = n, fs = skipped, ff = failed;
        final long fr = read, ft = total;
        runOnUiThread(() -> {
            mBar.setProgress(Math.max(0, Math.min(1000, fp)));
            mStatus.setText("已导入 " + fn + " 个文件"
                    + (fs > 0 ? "（跳过已有 " + fs + "）" : "")
                    + (ff > 0 ? "（失败 " + ff + "）" : ""));
            if (ft > 0) {
                mDetail.setText(mb(fr) + " / " + mb(ft) + "　" + (fp / 10) + "%");
            }
        });
    }

    /**
     * 把 zip 里的条目名映射成 files/ 下的目标文件。
     *
     * 必须做**路径穿越检查**（zip slip）：条目名可能是 "../../xxx"，
     * 直接拼接就会写到 files/ 外面去。规范化后必须仍在 base 之内。
     */
    private static File safeDest(File base, String name) {
        String n = name.replace('\\', '/');
        if (n.startsWith("/") || n.contains("..")) {
            // 含 .. 的先规范化再判断，别一棍子打死（有些包会写 ./sheets/ 这种）
            File f = new File(base, n);
            try {
                String bp = base.getCanonicalPath();
                String fp = f.getCanonicalPath();
                if (!fp.equals(bp) && !fp.startsWith(bp + File.separator)) return null;
                return f;
            } catch (Exception e) {
                return null;
            }
        }
        return new File(base, n);
    }

    /**
     * 导入成功后删掉资源包原文件（装完就不必再留整包）。
     *
     * 只在**成功**后调：失败/取消都保留，免得包没了又没导进去。
     * SAF 的 URI 用 DocumentsContract.deleteDocument 删（ACTION_OPEN_DOCUMENT
     * 授予了写权限）；调试入口传的文件路径直接删。
     */
    private boolean deleteSource() {
        try {
            if (mSrcFile != null) {
                return mSrcFile.delete();
            }
            if (mSrcUri != null) {
                return android.provider.DocumentsContract.deleteDocument(
                        getContentResolver(), mSrcUri);
            }
        } catch (Throwable t) {
            Log.w(TAG, "删除资源包失败：" + t);
        }
        return false;
    }

    private void gotoLibrary() {
        Intent it = new Intent(this, HomeActivity.class);
        it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(it);
        finish();
    }

    private void post(String s, String d) {
        runOnUiThread(() -> {
            mStatus.setText(s);
            mDetail.setText(d);
        });
    }

    @Override
    protected void onDestroy() {
        mCancelled = true;
        super.onDestroy();
    }

    /** 字节数 → 人类可读（导入页只报 GB / MB 两个量级）。 */
    private static String mb(long bytes) {
        if (bytes >= 1073741824L) return String.format("%.2f GB", bytes / 1073741824.0);
        return String.format("%.0f MB", bytes / 1048576.0);
    }
}
