#!/bin/bash
# 自包含 adb 包装：起守护进程 → 等设备就绪 → 执行传入的 adb 命令
# （沙箱会在两条命令之间回收后台进程，导致 adb daemon 每次都得重启、设备短暂 offline）
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [ -f "$ROOT/local.env" ]; then
    . "$ROOT/local.env"
fi
SDK="${ANDROID_SDK_DIR:-}"
ADB="${ADB:-${SDK:+$SDK/platform-tools/adb.exe}}"
ADB="${ADB:-adb}"
S="${ADB_SERIAL:?请在 local.env 填写 ADB_SERIAL，或改用带 --serial 的工具脚本}"
"$ADB" start-server >/dev/null 2>&1
for i in $(seq 1 20); do
    n=$("$ADB" devices 2>/dev/null | grep -c "^$S[[:space:]]*device")
    [ "$n" = "1" ] && break
    sleep 1
done
if [ "$n" != "1" ]; then echo "ADB_WAIT_FAIL after ${i}s"; exit 1; fi
exec "$ADB" -s "$S" "$@"
