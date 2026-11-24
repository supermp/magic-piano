# AGENTS.md · magic-piano

> 当前有效的仓库地图与维护约束。改动后同步本文。
>
> **注释与文档只写当前状态**：不写「某年某月某日改了什么」「用户某日要求过什么」这类时间线，也不写"曾经如何"的变更史。要禁止某种做法时，写成不带时间的约束（如「不要给起播加第二条预热轨」）。过程与时间线写在**本机工作笔记**里（已在 `.gitignore` 中排除，不入库）；本文和代码注释都不承担这个职责。

## 1. 项目与边界

把「某钢琴APP」改造成完全离线的 Android 钢琴练习 App，供本机研究与个人使用。

- 包名 `com.magicpig.piano`，应用名「魔术钢琴房」。
- 原生 Java + WebView/VexFlow 谱面 + native 音频与跟弹引擎；无 Gradle。
- `AndroidManifest.xml` 不声明 INTERNET 权限；无登录、VIP、下载链路或服务端令牌。
- 曲谱不打包进 APK：首次启动可通过 SAF 导入资源包，日常开发用同步脚本铺设。
- 参照 App 仅用于对照；不要改动或卸载设备上的参照 App。
- 仅支持 `arm64-v8a`，`minSdk 26` / `targetSdk 34`，全部页面锁横屏。

### 公开与合规

公开仓库只包含源码、脚本和配置模板。以下本机内容全部由 `.gitignore` 排除：

- 第三方 native 库、音源、模型、字体、UI 图、渲染器与生成图标；
- 曲库目录、封面、谱包、版本清单、资源包；
- 原版参照 APK、本机构建产物、交付 APK、凭据与本机配置。

这些资产和数据的来源不代表已获公开授权；恢复跟踪或单独分发前必须取得授权或替换。`SheetTrim` 仍含部分反编译恢复代码，公开前也需单独审查。不得提交 `local.env` 或 `app/debug.keystore`。

源码里不要留这几类东西：

- 本机绝对路径、设备序列号、局域网 IP、账号邮箱；
- 服务端令牌（只在 `local.env`，App 侧已彻底没有）；
- 具体曲谱/曲集 uuid —— 需要样本时用"运行时发现"（如 `MainActivity.firstSheetEnc()`），写死会在曲库更新后被下架而失效；
- 探针 Activity 不能裸 `exported=true`，必须带 `android:permission`（见 §4）。

## 2. 目录

```text
magic-piano/
├── README.md / AGENTS.md / .gitignore / .gitattributes
├── local.env.example          # 配置模板；真实 local.env 不入库
├── app/
│   ├── src/                   # Java 源码
│   ├── build.sh               # javac → d8 → aapt2 → pack.py → zipalign → apksigner
│   ├── pack.py / version.py / adbw.sh
│   ├── AndroidManifest.xml
│   ├── assets/                # 本地第三方运行资产；不入库
│   ├── lib/arm64-v8a/         # 本地 native 库；不入库
│   ├── res/                   # 本地生成图标；不入库
│   └── build/                 # 构建中间产物；不入库
├── library/                   # 本地曲库资源；不入库，脚本按需创建
│   ├── catalog.json           # 分类 → 标签 → 曲集 → 乐曲
│   ├── covers/                # <cover_uuid>.jpg
│   ├── sheets/                # <sheet_uuid>.zip
│   └── library_manifest.json  # 本地生成的增量同步清单
├── tools/                     # 曲库、资源生成、构建交付、设备同步、自检脚本
├── dist/                      # 本机交付物；不入库
│   ├── magicpiano-<versionName>.apk
│   ├── magicpiano-runtime-assets.zip  # 第三方开发资产，解到仓库根目录
│   └── magicpiano-library-YYYYMMDD.zip  # SAF 曲库包，只留一份
├── reference/                 # 本机参照 APK；不入库
├── cache/                     # 可重建状态；不入库
│   ├── catalog/               # 曲库抓取快照与差异
│   └── seed/                  # 铺设备暂存
└── tmp/                       # 临时脚本、截图与日志；不入库
```

`__pycache__/` 是可重建缓存，不入库，可直接删除。

## 3. 本机配置与构建

首次使用：

```bash
cp local.env.example local.env
```

在 `local.env` 至少填写：

- `ANDROID_SDK_DIR`：系统级 Android SDK，含 `build-tools/34.0.0`、`platforms/android-34`；不要放在源码仓库内；
- `JAVA_HOME`：JDK 17；
- 可选 `PYTHON`、`ADB`、`ADB_SERIAL`；
- 在线维护曲库时另填 `MAGIC_PIANO_TOKEN`，只从 `local.env` 读取；编译和运行 App 不需要它。

构建和装机：

```bash
./app/build.sh          # 自动产出 dist/magicpiano-<versionName>.apk，并清掉旧 APK
python tools/deploy.py --apk-only --serial <serial>
```

规则：

- `build.sh` 结尾自动把产物复制成 `dist/magicpiano-<versionName>.apk`（如 `magicpiano-20261123.apk`），并删除 `dist/magicpiano-*.apk` 里的其它文件 —— **交付目录始终只有一个 APK**，不会拿旧包冒充。曲库包（`magicpiano-library-*.zip`）和资产包不受影响。
- 文件名用 versionName，同一天多次构建会重名覆盖；先后只能看 versionCode（见「版本号」）。
- 开发资产包 `dist/magicpiano-runtime-assets.zip`（文件名固定）解压到仓库根目录，再生成图片、构建。
- 交付物统一放 `dist/`；`deploy.py` / `sync_devices.py` 默认取最新的 `magicpiano-*.apk`（可用 `--apk` 指定）。
- 改 Java 后必须全量重编。`build.sh` 会清理旧 class，避免隐式编译留下失效类。
- 改 `app/assets/algo_assets/**` 后同时递增 `AppAssets.VERSION`，否则设备仍使用已解压的旧副本。
- `app/version-code.txt` 由版本脚本维护，不入库。
- 资产分发只靠 `AssetManager.list()` 递归（`pack.py` 用正斜杠打包）；不要再引入构建期清单。
- 公开源码、脚本和配置模板保持 LF；具体规则见 `.gitattributes`。

### 版本号

`version.py` 生成 `versionCode=YYMMDDNNN`、`versionName=YYYYMMDD`：

- versionName 由 versionCode **直接截取日期**（`261123003` → `20261123`），同一天多次构建显示值相同；先后顺序只能看 versionCode（交付文件名用的是 versionName，不含它）。
- 不引入任何人维护的常量：`version.py` 参与内容 hash，改规则必然涨号；否则「名称变了、code 没变」，`sync_devices.py` 会判成版本相同而跳过安装。
- 同源码重编复用；内容变化则递增当天序号；换天从 001 开始。
- 序号到 999 或系统时间回拨导致不单调时直接失败，不回绕。
- 不用 11 位日期码（超出 int32）或 8 位 `YYMMDDNN`（可能小于旧版本而被拒绝升级）。
- Manifest 不写死 versionCode/versionName，由 `aapt2 link` 注入。

## 4. Android 模块

| 文件 | 当前职责 |
|---|---|
| `HomeActivity` | 曲库首页、分类、搜索、最近、收藏；默认「最近打开」 |
| `AlbumDetailActivity` | 曲集详情与乐曲列表 |
| `PlayerActivity` | 谱面、试听、UFP 演奏、选段、进度、结果卡与状态协调 |
| `ImportActivity` | SAF 资源包导入、按已有文件跳过与失败记账 |
| `MainActivity` | native 音频链探针（调试用），不参与主流程；暴露方式见下 |
| `AlgoEngine` | 引擎初始化、谱包解密打开、PCM 合成试听、播放头与小节表 |
| `UfpTrial` / `TrialListener` | UFP 会话、麦克风/自喂、精确区间、结算与取消 |
| `SfeInterface` | JNI 声明；包名和方法名必须匹配现有 so 导出符号 |
| `SheetView` | WebView/VexFlow 桥接、渲染补丁、光标、选段括号与结果标记 |
| `SheetCatalog` / `SheetFetcher` | 目录解析与纯本地谱包定位 |
| `LibraryStore` | `recent.json`、`fav_sheets.json`、`fav_albums.json` 的原子读写 |
| `AppAssets` | 按版本把 APK 引擎资产解压到内部存储 |
| `SheetTrim` | 谱面裁剪（仅 `MainActivity` 调试页使用）；含反编译恢复代码 |
| `TempoMark` | 从首小节提取速度标记供顶栏显示，不改 native 读取的谱包 |
| `EvalRating` | 解析音准、节奏、速度、总分 |
| `Ui` / `FlowLayout` / `NineSliceDrawable` | 视觉规范、布局与九宫格 |

探针的 `exported` 不能改成 `false`：`am start`（shell uid）启动未导出 Activity 会被 `SecurityException` 拒绝，探针就废了；用 DUMP 权限挡第三方 App 是正解。

曲名唯一来源是 `catalog.json` 的 `name`，不要从 MusicXML 标题另建来源。

## 5. native 与会话约束

### 线程与互斥

- `AlgoEngine` 的协调任务走 `algo-engine`；试听 Player 在 `algo-player`，UFP 喂音在 `ufp-feed`。
- 预览模块和 UFP handler 是全局单例：旧 Player 必须彻底退出后才能建 UFP。
- `AlgoEngine.post()` 会同步等待；只排队不等待使用 `postAsync()`。
- `stopAndWait()` 返回 boolean；false 时本轮演奏失败，不得继续建 UFP。
- 旧 Player 超时未退出时，本次 `seekTo()` 失败并回调错误，不得再建第二个 Player。
- `Player.finally` 仅在 `mPlayer == this` 时清空全局引用。
- 带 `join()`、`ufpFinish()` 的收尾不能在 UI 线程执行。

### UFP 归属

- 会话代数 `sGen` 必须贯通建会话、喂音、停止与放弃；`alive(myGen)` 同时检查运行态和代数。
- native handler 归属单独由 `sHandlerGen` 记录，不能用 stop 自己产生的新代数代替 handler 代数。
- `sListener/mRec/mFeedThread` 等静态引用在锁内快照、校验归属后清理；旧线程不得碰新会话资源。
- 建会话取消或失败在同一引擎任务的 `finally` 内释放本代 handler并恢复预览，调用线程不再直接补做 native 清理。
- `onStop/onDestroy` 使用带 owner 的 `abandon()`；建会话在途时也不能只靠 `isRunning()` 判断。

### 试听与演奏行为

- 首播使用朴素顺序：创建一条 AudioTrack → `play()` → 复位/定位 → 写 PCM → 循环 → drain。不要加第二条预热轨。
- 选段试听由 native `setSection` 精确截断；UFP 用 `ufpSetCustomRangeWithResult` 设置音符级起终点。
- 选段终点音包含在区间内；到曲末时探测末小节最大合法 offset，不能退到末小节第一颗音。
- Java 不另做精度更差的终点抢停。native 区间失败要打日志并暴露错误。
- 选段画边界只认 `onNoteClick`；进度条、导航和划动仍可用。选段生效后段外定位被拦截。
- 没捕获到钢琴声音，或光标从未离开起点时，静默退出演奏：不弹结算/成绩卡、不画红叉。
- native `ufpFinish` 耗时随输入长度增长；Java 只能及时显示同一张结算卡，不能把它变成快速操作。
- 反复记号当前不展开：`ufpCreateMP(..., false, ...)`，试听也不启用 repeat。开启前必须重新验证光标、进度、选段和结果位置。

## 6. 谱面与交互约束

### 渲染器

`SheetView.buildHtml()` 内联 `sheet_show.html`、`jsbridge.js`、`sheet_show.js` 和补丁。这里改坏会导致整页空白。

- 改拼接 JS 后必须做语法检查；改 `sheet_show.js` 后执行：

  ```bash
  node -e "new Function(require('fs').readFileSync('app/assets/sheetShow/sheet_show.js','utf8'))"
  ```

- Java→JS 明文 JSON 在桥接前保护 `%`，避免原桥 `decodeURIComponent` 抛 `URIError`；UTF-8 使用 `TextDecoder`。
- 光标统一使用 `#sheet-cursor-preview` 金色胶囊；只有一套位置状态和一条阅读存档。
- 渲染器恒为 preview 模式；演奏态由 Java `mPerformMode` 控制整小节高亮（命中标记另走结果接口），不切 magic、不重绘。
- 点谱面定位不主动滚屏；进度条、恢复和回起点允许滚动。重画光标不得无条件抢回视口。
- 渲染调度使用 setTimeout 补丁，避免长曲 MessageChannel 排队饿死交互 JS。
- 恢复/跳转到未渲染位置时显示载入卡；以目标小节真正上屏为完成判据，并提供取消与完整状态回滚。
- 迟到 JS 回调必须校验当前目标身份；Activity 销毁后不得继续触达 listener。

### 结果与存档

- 错音/漏音统一画红叉，正确音符不画符号；只提交有问题的音团，过长结果分批发送。
- `mWrongMarks` 按 `(measure, voice, offset)` 保留全集；局部重弹只替换本轮评测覆盖范围。
- `pitches_info` 为空表示未弹或跳过评分，不等于本轮全对。
- 光标和选段统一存 `recent.json`；曲首 `(0,0,0)` 视为无进度，其余位置可恢复。
- 有存档时询问是否恢复；明确不载入或倒计时拒绝时当场清阅读现场，但保留最近打开记录。
- 调试 extra 命中 `mDebugSession` 时不污染正常存档。
- 页面离开、弹窗和 Handler 必须有对称清理；静态会话不得持有已销毁 Activity。

### 进度与小节号

- 进度条按小节端点等分：首小节 0‰、末小节 1000‰，单小节曲子固定 0；不依赖时长或采样数。
- 进度映射使用物理小节索引；UI 显示使用印刷小节号，两者不能混用。
- MusicXML 小节可能无编号、弱起编号 0 或使用非数字编号；逐 part 数 `<measure>` 并取最大值。
- 非数字/隐式编号沿用前一个印刷号；进度条不能因此映射错位。
- 底栏只显示当前小节滑块与 `/ N`；其它提示走 toast 或浮层。

## 7. 当前视觉与音频参数

- 色板：底 `#FFFCE8`、主棕 `#774B04`、深棕 `#4D2D00`、金 `#F0C03C→#D89A30`、羊皮纸 `#EBC6A5`。
- 布局 `Ui.scale()` 与字号 `Ui.fontScale()` 分离；字号下限 0.68，浮层使用独立 float 尺寸体系。
- 不可用状态统一 `Ui.setDisabled()`，不要另造置灰颜色或重复点击守卫。
- 谱面 zoom 按曲谱区 CSS 高度分档：大屏约 0.65、小屏约 0.465；不要为凑整数行反复改系数。
- 顶栏速度标记使用系统音符字形，`applyTempoChip()` 只设置文字，不自绘或强行放大。
- 手型切换使用 `setElectricPianoLeft/RightHandVolume` 和 CSS 灰化；每个新谱页显式重置合手。
- 播放中用手型修订号补发最新音量，不能只用一个易丢失更新的 dirty 布尔量。
- 麦克风块固定 2048 采样；当前音源 `VOICE_RECOGNITION`。不要未经验证换成 UNPROCESSED。
- 配置采样率明确不是 44100 时走完整错误清理，不按错误速率喂音。
- 真弹、音频延迟和听感问题必须在真机验证；模拟器只用于功能与布局冒烟。

## 8. 曲库更新与设备同步

### 资源职责

- `library/catalog.json`：曲集、乐曲、作曲家、页码、mtime 的目录源数据。
- `library/covers/`：曲集封面；缺目录或封面会导致名称/封面显示退化。
- `library/sheets/<uuid>.zip`：含 `sheet.xml.enc`；设备路径为 `Android/data/com.magicpig.piano/files/sheets/<uuid>/sheet.xml.enc`。
- `library/library_manifest.json`：记录 catalog 哈希及每首谱包 mtime、enc 哈希、字节数，供多设备精确增量同步；由 `make_manifest.py` 生成。
- `dist/magicpiano-library-YYYYMMDD.zip`：SAF 曲库导入包；默认全量打包成功后自动清理旧包，只留一份。
- `cache/catalog/`：`update_catalog.py` 的全量抓取快照与差异；可重建。

### 更新曲包

在线脚本只用于 PC 维护曲库，App 不读令牌；令牌只取 `local.env` 的 `MAGIC_PIANO_TOKEN`。

1. 检查更新（默认只报告，不修改目录）：

   ```bash
   python tools/update_catalog.py
   ```

   查看 `cache/catalog/catalog_diff.json` 中新增、下架及 mtime 变化。无变化则停止。抓取途中任一接口失败、或分页累计数与 total 不一致都会直接中止，不写快照、差异或目录；重跑整次抓取，不要拿旧的 `catalog_full.json` 顶替。

2. 确认后，用刚抓取的快照应用目录，不重复联网抓取：

   ```bash
   python - <<'PY'
   import json
   from tools.update_catalog import to_v2
   with open('cache/catalog/catalog_full.json', encoding='utf-8') as f:
       catalog = to_v2(json.load(f))
   with open('library/catalog.json', 'w', encoding='utf-8') as f:
       json.dump(catalog, f, ensure_ascii=False, separators=(',', ':'))
   PY
   ```

   写入格式固定为紧凑单行（`separators=(',', ':')`），与 `update_catalog.py --write` 一致；换用别的缩进会让整份目录产生无意义的全文件 diff。

   `update_catalog.py --write` 会用**同一次抓取**的结果直接覆盖目录（不会再抓一遍），但等于跳过人工复核 diff，通常不用。

3. 对 `sheets_mtime_changed` 的 uuid 删除对应本地旧 zip，再增量下载。下载器只按文件存在跳过，不删除就不会换新版；**不要手删设备 enc**：

   ```bash
   rm -f library/sheets/<uuid>.zip
   python tools/fetch_sheet_zips.py
   ```

4. 有新增曲集时，在打包和同步前补封面（使用装有 Pillow 的本机解释器）：

   ```bash
   py -3 tools/fetch_covers.py --only <cover_uuid>
   ```

5. 检查新包 zip CRC、包含 `sheet.xml.enc`，并确认 `library/sheets/manifest.json` 的 `failed_list` 为空。失败可重跑补下，不是永久黑名单。
6. 打新资源包，成功后再删除被取代的旧包：

   ```bash
   python tools/make_bundle.py
   ```

7. 更新清单，先 dry-run 核对每台设备，再实际同步：

   ```bash
   python tools/make_manifest.py
   python tools/sync_devices.py --dry-run --serial <serial>
   python tools/sync_devices.py --serial <serial>
   ```

   省略 `--serial` 处理所有在线设备；只更新曲库而不处理 App 时加 `--no-apk`。**曲库变化不需要重打 APK。**

### 同步判据与维护

- 元数据 catalog/covers/manifest 每次同步都推；谱包只推 add/replace。
- mtime 变化用于发现服务端重传；设备是否替换由 enc 哈希决定，不能只看 mtime 或字节数。
- App 按 versionCode 决定升级：设备低才安装，相等跳过，设备高拒绝降级。
- 下架清理以 **catalog.json** 为准（manifest 只做增量差异）：设备有、catalog 没有的才删；catalog 仍列出的清单缺曲/本地缺包只报告，不删。设备删除成功后才删本地 zip；一次超过 50 个先不删。
- 验收 enc 数与清单一致、设备清单哈希与本机一致；连续第二次应补 0/替换 0。
- 铺设备暂存 `cache/seed/` 会复用旧 enc：`sync_devices.py` 每次同步前自己清，手工跑 `unpack_backup.py` 前也要清。
- 下载 URL 现换现下，不缓存。修改目录解析时注意 `composers_zh/mtime/ctime` 在乐曲对象顶层，`page_in_book` 在 `data` 内。
- SAF 导入只有全部成功才删源包；单文件失败删除半截文件并保留源包。

## 9. 收尾与验证

按改动范围验证；纯文档或目录整理不必运行设备测试。

1. 运行 `python tools/clean_check.py`，按报告处理到 0 项；`app/build` 里的 APK 中间产物确认不再装机后再删。
2. 删除本轮临时脚本、截图、设备端 dump/screencap 和铺设备暂存；不要碰用户目录。
3. Java 改动全量构建；拼接 JS/渲染器改动做语法与真实页面验证。
4. 涉及试听、演奏或 native 生命周期时，验证起播/推进/停止、建会话、取消或结束、native 释放与预览恢复，并检查 listener、Handler、WebView、AudioRecord/AudioTrack 的对称清理。
5. 交付前确认 `dist/` 里的 APK 晚于 `app/src` 与 `app/assets` 的最新改动。
6. 本文只写当前状态与约束，不写临时曲库数量、设备序列号、本机路径或调试日志。

仓库已 `git init`（含 `.gitignore` / `.gitattributes`）；不要擅自提交或推送。公开前用 `git status` / `git check-ignore` 再核对候选文件。
