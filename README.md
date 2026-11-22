# 魔术钢琴房

一款完全离线的 Android 钢琴练习 App。使用本地曲谱渲染谱面，支持合成试听和现场钢琴跟弹，不需要账号或网络连接。

## 功能

- 本地曲库、搜索、收藏与最近打开；
- MusicXML / VexFlow 谱面、试听、进度定位与选段练习；
- 麦克风识别现场弹奏，支持跟弹反馈与练习结果；
- 通过 SAF 导入曲库资源包，曲库不内置在 APK 中。

运行要求：Android 8.0 及以上、`arm64-v8a` 设备、横屏。跟弹功能需要麦克风权限。

## 源码与资源

本仓库只提供源码、脚本和配置模板，不包含第三方 native 库、音源、模型、字体、渲染器、UI 素材或曲库。

只想安装使用：取配套网盘中的完整 APK 和曲库资源 ZIP，跳到「安装与运行」。自行编译：把开发资产 ZIP（`dist/magicpiano-runtime-assets.zip`）解压到仓库根目录，包内已带 `app/` 前缀，含：

```text
app/
├── lib/arm64-v8a/           # native 库
└── assets/
    ├── algo_assets/         # 音源、模型与引擎配置
    ├── fonts/
    ├── sheetShow/
    └── ui/images/
```

请确认所使用的第三方资源和曲库具有相应授权。

## 构建环境

当前构建脚本面向 **Windows + Git Bash**，不需要 Gradle。

准备：

- JDK 17；
- Android SDK：`platform-tools`、`platforms;android-34`、`build-tools;34.0.0`；
- Python 3，以及用于生成图片的 Pillow。

SDK 建议安装在系统级目录，不要放进源码仓库。

### 1. 配置本机路径

```bash
cp local.env.example local.env
```

编辑 `local.env`，至少填写：

```bash
ANDROID_SDK_DIR='C:/path/to/Android/Sdk'
JAVA_HOME='C:/path/to/jdk-17'
PYTHON='python3'
```

`MAGIC_PIANO_TOKEN` 只供维护人员在线更新曲库使用，编译和运行离线 App 不需要它。

### 2. 生成图片资源

将开发资产放好后执行：

```bash
py -3 -m pip install Pillow
py -3 tools/gen_icon.py
py -3 tools/gen_adaptive_icon.py
py -3 tools/gen_library_title.py
py -3 tools/gen_nav_star.py
```

### 3. 编译 APK

在 Git Bash 中运行：

```bash
./app/build.sh
```

构建结束会自动把产物放到 `dist/magicpiano-<versionName>.apk`，并删掉 `dist/` 里其它
`magicpiano-*.apk`——始终只保留最新一个。不需要再手工 `cp`。

版本名形如 `20261123`，由 `app/version.py` 从 versionCode 截取日期派生：同一份源码重编不变，无需手改常量。
APK 文件名用它（`magicpiano-20261123.apk`），同一天多次构建会重名覆盖；要区分先后就看 APK 里的 versionCode。

## 安装与运行

普通用户直接安装网盘中的完整 APK，曲库资源 ZIP 可以放到设备可读取的位置（如 Downloads），
首次打开 App 时在导入页面选中它即可。

从源码构建时，连接设备并确认 `adb devices` 能识别后：

```bash
python tools/deploy.py --apk-only --serial <设备序列号>
```

也可以直接用 adb 安装 `dist/magicpiano-<versionName>.apk`（`dist/` 里只会有一个 APK）。

首次打开 App 后：

1. 在导入页面选择配套网盘中的曲库资源 ZIP；
2. 等待导入完成，再从首页选择乐曲；
3. 使用「试听」检查音频与谱面，使用「演奏」检查现场跟弹。

如果本机已有完整的 `library/` 曲库工作区，也可以用 `python tools/deploy.py --serial <设备序列号>` 一并安装 APK 和铺设曲库。

更多模块职责、资源生成和维护约束见 [AGENTS.md](AGENTS.md)。
