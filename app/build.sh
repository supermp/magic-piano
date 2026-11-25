#!/bin/bash
# 免 Gradle 的极简 APK 构建：javac → jar → d8 → aapt2 → pack.py → zipalign → apksigner
# 注意 pipefail：下面大量 `... | tail` 会把被管命令的退出码吞掉，
# aapt2 报错时必须让管道失败停下，不能产出 0 字节 APK 还打印"完成"。
set -e
set -o pipefail

# 本机路径统一放在项目根 local.env（不入库）；首次使用先复制 local.env.example。
ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
if [ -f "$ROOT_DIR/local.env" ]; then
    set -a
    . "$ROOT_DIR/local.env"
    set +a
fi
: "${ANDROID_SDK_DIR:?请复制 local.env.example 为 local.env，并填写 ANDROID_SDK_DIR}"
: "${JAVA_HOME:?请在 local.env 填写 JDK 17 的 JAVA_HOME}"
SDK="$ANDROID_SDK_DIR"
BT="$SDK/build-tools/34.0.0"
AJ="$SDK/platforms/android-34/android.jar"
# Windows 下绝对路径用带引号的 C:/... 形式；用 ./build.sh 触发 shebang，
# 避免 `bash build.sh` 经 PATH 落到另一套 bash。
JAVAC="$JAVA_HOME/bin/javac.exe"
JAR="$JAVA_HOME/bin/jar.exe"
JAVA="$JAVA_HOME/bin/java.exe"
PY="${PYTHON:-python3}"

cd "$(dirname "$0")"
echo "=== [0/6] 清理与补丁校验 ==="
# 渲染器 JS 语法错或补丁锚点缺失都会让播放页白屏；
# 先在 app/assets/sheetShow 上做语法与锚点校验，任一失败直接中止构建。
"$PY" "$ROOT_DIR/tools/check_sheet_patches.py"

# 不整目录 `rm -rf build`：这里全是本脚本的产物，逐目录、逐文件清理更可控。
mkdir -p build/classes build/dex build/out build/dexout
# javac 是**隐式编译**：只传源码文件时，若某个 .class 比它的 .java 还"新"
# （mtime 只到秒），javac 会跳过不编 —— 源码改了、APK 行为却没变。
# 所以每次构建前清空 build/classes 全量重编，杜绝这种"幽灵改动"。
# 不用 rm -rf：classes 里几十个文件，逐文件删不会误伤整个目录。
if [ -d build/classes ]; then
  find build/classes -type f -exec rm -f {} \;
  find build/classes -depth -type d -exec rmdir {} \; 2>/dev/null || true
fi
mkdir -p build/classes
# javac 只覆盖"还有源码"的类：删掉或改名的 .java 会留下旧 .class 被打进 dex，
# 同一包里失效的类也一样，死类的字符串还会留在 dex 里。清理顺序：
# 1) src 里已不存在的包整个删掉；
# 2) 逐个 .class 对照：src 里没有同名 .java 的一律删掉（覆盖内部类 X$Y）。
for d in build/classes/com/*/; do
    [ -d "$d" ] || continue
    name=$(basename "$d")
    if [ "$name" != "mangofuture" ] && [ ! -d "src/com/$name" ]; then
        echo "    清理失效包：com/$name"
        # 同样逐文件删，不用 rm -rf
        find "$d" -type f -exec rm -f {} \;
        find "$d" -depth -type d -exec rmdir {} \; 2>/dev/null || true
    fi
done
for cls in build/classes/com/magicpig/piano/*.class; do
    [ -f "$cls" ] || continue
    base=$(basename "$cls" .class)
    top=${base%%\$*}                      # 去掉内部类后缀：Album$1 -> Album
    [ -f "src/com/magicpig/piano/$top.java" ] || {
        echo "    清理失效类：$base"
        rm -f "$cls"
    }
done

echo "=== [1/6] javac ==="
find src -name '*.java' > build/sources.txt
# 注意：绝不能用 `| grep ... || true` 吞掉 javac 报错 —— 否则类静默丢失，
# 运行期只会看到 ClassNotFoundException，很难排查。
JAVAC_LOG=build/javac.log
if ! "$JAVAC" -encoding UTF-8 -source 8 -target 8 -classpath "$AJ" -nowarn \
    -d build/classes @build/sources.txt > "$JAVAC_LOG" 2>&1; then
    echo "!!! javac 编译失败，原始输出："
    cat "$JAVAC_LOG"
    exit 1
fi
"$JAR" cf build/classes.jar -C build/classes .
echo "    编译产物：" $(cd build/classes && find . -name '*.class' | tr '\n' ' ')

echo "=== [2/6] d8 ==="
"$JAVA" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
    --lib "$AJ" --min-api 26 --output build/dexout build/classes.jar 2>&1 | tail -5
ls -la build/dexout/classes.dex

echo "=== [3/6] aapt2 compile 资源 + link（资源与清单；assets 交给 pack.py）==="
# 资源目录要先 compile 成 .flat 再交给 link（直接给 res/ 目录 link 不认，
# 会报 "resource mipmap/ic_launcher not found"）
if [ -d res ]; then
    "$BT/aapt2.exe" compile --dir res -o build/res.zip 2>&1 | tail -5
    RES_ARGS=(build/res.zip)
else
    RES_ARGS=()
fi
# 版本号 = YYMMDDNNN（见 version.py 顶部说明）：内容变了取当天下一个序号、重编不变。
# AndroidManifest.xml 里**不能**再写 versionCode —— 写了 aapt2 就不认这里的参数。
# bump 把 `<code> <内容hash>` 写回 app/version-code.txt；versionName = YYYYMMDD（由 code 截取日期）。
VC=$("$PY" version.py bump | tr -d '\r')
VN=$("$PY" version.py name | tr -d '\r')
[ -n "$VC" ] && [ -n "$VN" ] || { echo "!!! 版本号生成失败（version.py）"; exit 1; }
echo "    versionCode=$VC  versionName=$VN  （由源码树内容派生）"
"$BT/aapt2.exe" link -o build/base.apk \
    -I "$AJ" --manifest AndroidManifest.xml \
    --min-sdk-version 26 --target-sdk-version 34 \
    --version-code "$VC" --version-name "$VN" \
    "${RES_ARGS[@]}" 2>&1 | tail -10
ls -la build/base.apk

echo "=== [4/6] 打包 dex + native libs + assets ==="
cp build/dexout/classes.dex build/classes.dex
"$PY" pack.py build/base.apk 2>&1 | tail -12
"$JAR" tf build/base.apk | grep -E "classes.dex|libsfeALG|libtensorflowlite_c|PianoWaveStereo|sheet_show.js" | head -8

echo "=== [5/6] zipalign ==="
"$BT/zipalign.exe" -f 4 build/base.apk build/aligned.apk

echo "=== [6/6] 签名 ==="
if [ ! -f debug.keystore ]; then
    "$JAVA_HOME/bin/keytool.exe" -genkeypair -v -keystore debug.keystore \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass android -keypass android -dname "CN=Android Debug,O=Android,C=US" 2>&1 | tail -3
fi
"$JAVA" -jar "$BT/lib/apksigner.jar" sign \
    --ks debug.keystore --ks-key-alias androiddebugkey \
    --ks-pass pass:android --key-pass pass:android \
    --out build/MagicPiano.apk build/aligned.apk 2>&1 | tail -5

echo
echo "=== 完成 ==="
ls -la build/MagicPiano.apk

# 交付：dist/magicpiano-<versionName>.apk，且只保留这一个 APK。
# 用 versionName 命名（如 magicpiano-20261123.apk）；同一天多次构建会重名，直接覆盖
# ——用户明确接受这个取舍，版本先后看 APK 里的 versionCode（dumpsys package 可读）。
# 旧包先删，曲库包 / 资产包不受影响。
DIST="$ROOT_DIR/dist"
mkdir -p "$DIST"
for old in "$DIST"/magicpiano-*.apk; do
    [ -e "$old" ] && rm -f "$old"
done
cp build/MagicPiano.apk "$DIST/magicpiano-$VN.apk"
echo "交付：dist/magicpiano-$VN.apk（versionCode=$VC，已清掉旧 APK）"
