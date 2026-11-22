#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从源码树内容派生 Android versionCode / versionName。

为什么要派生、而不是在 AndroidManifest.xml 里手写一个数：

  `tools/sync_devices.py` 靠设备侧 versionCode 判断"要不要重新装 APK"。
  手写值没人推进 —— 改完代码重编、版本号还是老值，同步脚本就会认为
  设备已经是最新，用户手机上跑的其实是旧代码（漏更新，比多装一次严重）。
  派生值随内容自动变，不需要任何人记得 bump。

versionCode / versionName 的取值：**YYMMDDNNN**（9 位）。

  · YYMMDD = 构建当天的本地日期（如 261111）
  · NNN    = 当天第几次"内容真的变了"的构建，001..999（第 1 次 = 001）
  · versionName = `YYYYMMDD`（只取日期，同一天不区分；升级判据始终是 versionCode）

例：11 月 11 日第 3 次构建 → versionCode `261111003`，versionName `20261111`。

versionName 只是给人看的：Android 的升级判据只有 versionCode，两者同源派生，
不存在「改了 name 但 code 没变、设备不装」的情况（旧版写死 `0.4` 时正是这个缺陷）。

⚠ 两条硬约束决定了位数，别随手改：
  1. Android versionCode 是 int32，上限 2147483647；实测 aapt2 对 11 位的
     `20261111004` 直接报 `invalid android:versionCode`。8 位完整日期 + 3 位
     序号装不下，只能把年份缩成 YY。
  2. 8 位的 `YYMMDDNN`（26111101 = 2611 万）比旧方案的 9 位单调计数器
     （215696510 = 2.15 亿）**小**。实测 `adb install -r -d` 也被系统拒绝
     （INSTALL_FAILED_VERSION_DOWNGRADE），新包根本装不上，除非卸载（会丢
     设备上的曲库与存档）。9 位日期码（261111001 = 2.61 亿）天然大于旧值，
     是一次正常升级。→ 序号取 3 位（正好是用户最初设想的"后面三位"）。

为什么仍要 `version-code.txt`：记 `<code> <内容hash前16位>`，保证
"同源码重编不产生新版本"（可复现）、"内容真的变了才递增 NN"：
  · hash 与记录一致且记录已是日期码 → 复用（可复现）
  · 内容变了、还是同一天 → NNN + 1
  · 内容变了、换了一天 → 新日期 + 001（数值必然更大，单调）
  · NNN 到 999 → **直接报错**，绝不回绕（回绕会让设备判成"降级"而拒装）
  · 新 code 不比上次大（系统时间回拨）→ 同样报错，防止悄悄降级

旧方案是 9 位单调计数器（215696510），状态文件里不是合法日期码 →
第一次 bump 直接按今天第 1 次生成；新值比旧值大，`sync_devices.py` 走正常
升级路径，不需要任何迁移分支或用户数据搬迁。

参与 hash 的只有**决定产物内容**的东西：src / assets / res / lib /
AndroidManifest.xml / build.sh / pack.py，以及 **version.py 自身**
（版本规则变了也必须涨号，否则「名称变了、code 没变」，设备不会更新）。
构建期生成的中间产物（如历史上那个 assets/filelist.txt）不参与。

用法：
  python version.py bump    # 内容变了就 +1 并写回 version-code.txt（**build.sh 用这个**）
  python version.py code    # 只读，输出当前 versionCode（不写文件）
  python version.py name    # 输出 versionName
  python version.py both    # 只读出 "code name"
  python version.py hash    # 输出源码树 hash（排查"为什么版本号变了"用）
"""
import datetime
import hashlib
import os
import sys

BASE = os.path.dirname(os.path.abspath(__file__))

# 目录型输入（递归取全部文件）
ROOTS = ('src', 'assets', 'res', 'lib')
# 单文件输入。`version.py` 自己也在内：它决定了 versionCode/versionName 的生成逻辑，
# 改版本规则（比如 versionName 从带分隔符改成 YYYYMMDD）必须让版本号 +1，否则
# 「名称变了、code 没变」，sync_devices.py 会判成版本相同而跳过安装。
FILES = ('AndroidManifest.xml', 'build.sh', 'pack.py', 'version.py')
# 状态文件：`<YYMMDDNNN> <内容hash前16位>`，由 `bump` 子命令维护
COUNTER = os.path.join(BASE, 'version-code.txt')
# 每天最多 999 次内容变更；到顶直接报错，绝不回绕
MAX_SEQ = 999


def collect():
    items = []
    for root in ROOTS:
        base = os.path.join(BASE, root)
        if not os.path.isdir(base):
            continue
        for dirpath, dirnames, filenames in os.walk(base):
            dirnames.sort()               # 目录顺序也要定死，walk 本身不保证
            for fn in sorted(filenames):
                rel = os.path.relpath(os.path.join(dirpath, fn), BASE)
                rel = rel.replace(os.sep, '/')
                items.append(rel)
    for fn in FILES:
        if os.path.isfile(os.path.join(BASE, fn)):
            items.append(fn)
    items.sort()                          # 路径排序后逐个喂 hash，保证跨平台一致
    return items


def tree_hash():
    h = hashlib.sha256()
    for rel in collect():
        # 路径也进 hash：改文件名算内容变化（assets 里改名会影响运行时寻址）
        h.update(rel.encode('utf-8'))
        h.update(b'\0')
        with open(os.path.join(BASE, rel), 'rb') as fp:
            for chunk in iter(lambda: fp.read(1 << 20), b''):
                h.update(chunk)
        h.update(b'\0')
    return h.hexdigest()


def read_counter():
    """读 `version-code.txt`，返回 (code, 记录的 hash 前16位)。坏了就当没有。"""
    if not os.path.isfile(COUNTER):
        return 0, ''
    try:
        with open(COUNTER, 'r', encoding='utf-8') as fp:
            parts = fp.read().split()
        code = int(parts[0]) if parts else 0
        head = parts[1] if len(parts) > 1 else ''
        return code, head
    except Exception:
        return 0, ''


def is_date_code(code):
    """9 位 YYMMDDNNN，且月/日/序号合法。旧计数器码（215696510 → 月=56）为 False。"""
    if code <= 0 or len(str(code)) != 9:
        return False
    s = str(code)
    mm, dd, nnn = int(s[2:4]), int(s[4:6]), int(s[6:9])
    return 1 <= mm <= 12 and 1 <= dd <= 31 and 0 <= nnn <= MAX_SEQ


def resolve_code(digest, write):
    """同一份内容复用上次的 code；内容变了就取当天下一个序号。write=False 只读。"""
    last, last_head = read_counter()
    head = digest[:16]
    # 只有"上次已经是日期码且内容没变"才复用。旧计数器码即使 hash 相同也要换掉，
    # 否则格式永远迁移不过去。
    if is_date_code(last) and last_head == head:
        return last
    today_prefix = int(datetime.date.today().strftime('%y%m%d')) * 1000
    if is_date_code(last) and last // 1000 * 1000 == today_prefix:
        seq = last % 1000 + 1
    else:
        seq = 1
    if seq > MAX_SEQ:
        raise SystemExit('今天的构建序号已满（%d 次）：等明天或人工处理，绝不回绕'
                         % MAX_SEQ)
    code = today_prefix + seq
    # 旧计数器码迁移到日期码时数值必然变小，那是预期的一次性迁移；
    # 除此以外"新码 <= 旧码"只可能是系统时间回拨，必须拦住。
    if is_date_code(last) and code <= last:
        raise SystemExit('新 versionCode %d 不大于上次的 %d（系统时间回拨？），拒绝生成'
                         % (code, last))
    if write:
        with open(COUNTER, 'w', newline='\n') as fp:
            fp.write('%d %s\n' % (code, head))
    return code


def version_name(code):
    """`YYYYMMDD`：只取日期部分，同一天多次构建显示值相同。

    这是刻意的：versionName 只用于展示，Android 的升级判据只有 versionCode。
    同一天的先后通过 versionCode（以及 dist/magicpiano-<versionCode>.apk 文件名）区分。
    """
    s = str(code)                                  # YYMMDDNNN
    return '20%s%s%s' % (s[0:2], s[2:4], s[4:6])


def main():
    digest = tree_hash()
    arg = sys.argv[1] if len(sys.argv) > 1 else 'both'
    # 只有 bump 会写计数器；其余子命令纯读，方便随手查询不产生副作用
    code = resolve_code(digest, write=(arg == 'bump'))
    name = version_name(code)
    if arg in ('code', 'bump'):
        print(code)
    elif arg == 'name':
        print(name)
    elif arg == 'hash':
        print(digest)
    else:
        print('%d %s' % (code, name))
    return 0


if __name__ == '__main__':
    sys.exit(main())
