# -*- coding: utf-8 -*-
"""按「设备侧版本清单」做真正的多设备增量同步（App + 曲包）。

App 的升级判据是 **versionCode** —— Android 的标准做法，PackageManager 本来
就记着它，设备侧**不需要任何凭据文件**：
  · 本机 versionCode 从 APK 里读（app/version.py 生成 YYMMDDNNN，build.sh 注入）
  · 设备 versionCode 从 `dumpsys package` 读
  · 设备低 → install；相等 → 跳过；设备高 → 拒绝（防降级）

  版本码统一用 9 位日期码（261111001）。它比历史上的 9 位单调计数器
  （215696510）大，属正常升级，**不需要迁移分支**；8 位的 YYMMDDNN（26111101）
  反而比旧码小，实测连 `install -r -d` 都会被系统拒绝
  （INSTALL_FAILED_VERSION_DOWNGRADE），所以序号取 3 位。

历史上这里用过 `app_fingerprint.txt`（APK 的 sha256），已删除：构建不可复现
（zip 条目时间戳、v1 签名文件每次都变），同一份源码重编 sha 也变，拿它判
"要不要装"只会白重装 137MB；而它本来该干的活（校验字节完整）也没人用。

曲包仍然靠设备侧清单，因为一万多首没法靠文件名判断内容版本：
  library_manifest.json  catalog 的 sha256 + 每首乐曲的 enc sha256（由 make_manifest.py 生成）

同步流程：
  · 曲包：
      - 设备有清单 → 精确算 add（本机有设备无）/ replace（hash 或 mtime 不同）/
        orphan（设备有本机无，只报告不删）。
      - 设备没有清单（首次纳管）→ 扫描设备现有 enc 的 uuid 集合，补 add；
        对 cache/catalog/catalog_diff.json 里标记为「内容重传」的曲目做字节数校验，
        大小不同才算 replace。随后把本机清单推上去完成纳管。
    之后每次都是精确 hash 差异，不再依赖历史 diff 文件。

只推变化的谱包；catalog.json / covers / 清单 / 指纹这几份元数据每次都推
（合计约 12MB，比 4.4GB 谱包便宜得多，也顺带保证目录与封面一致）。

用法：
  python tools/sync_devices.py                       # 所有在线设备
  python tools/sync_devices.py --serial <serial>   # 单台
  python tools/sync_devices.py --dry-run             # 只看差异报告
  python tools/sync_devices.py --no-apk             # 只同步曲包
"""
import argparse
import hashlib
import io
import json
import os
import re
import subprocess
import sys
import time

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(BASE, 'tools'))
from local_config import adb_path, aapt2_path

ADB = adb_path()
UNPACK = os.path.join(BASE, 'tools', 'unpack_backup.py')
CATALOG = os.path.join(BASE, 'library', 'catalog.json')
COVERS = os.path.join(BASE, 'library', 'covers')
MANIFEST = os.path.join(BASE, 'library', 'library_manifest.json')
# 与 app/build.sh 里的 $BT 保持一致（读 APK 的 versionCode 要用 dump badging）
AAPT2 = aapt2_path()
DIFF = os.path.join(BASE, 'cache', 'catalog', 'catalog_diff.json')
STAGE = os.path.join(BASE, 'cache', 'seed', 'ext')

PKG = 'com.magicpig.piano'
REMOTE = '/sdcard/Android/data/%s/files' % PKG
UUID_RE = re.compile(r'/sheets/([0-9a-f-]{36})/sheet\.xml\.enc')


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def apk_version_code(path):
    """从 APK 里读 versionCode（aapt2 dump badging 的第一行）。读不到返回 0。"""
    try:
        r = subprocess.run([AAPT2, 'dump', 'badging', path],
                           capture_output=True, text=True)
        m = re.search(r"versionCode='(\d+)'", r.stdout or '')
        return int(m.group(1)) if m else 0
    except Exception:
        return 0


def run(cmd, binary=False):
    return subprocess.run(cmd, capture_output=True,
                          text=not binary)


class Device:
    def __init__(self, adb, serial):
        self.adb, self.serial = adb, serial

    def __call__(self, *args, binary=False):
        cmd = [self.adb, '-s', self.serial] + list(args)
        r = run(cmd, binary=binary)
        return r.stdout if isinstance(r.stdout, bytes) else (r.stdout or '').replace('\r', '')

    def rc(self, *args):
        return run([self.adb, '-s', self.serial] + list(args)).returncode

    def read_remote(self, path):
        """读设备文件（二进制安全）。

        ⚠ 只认 exec-out 且 returncode==0：文件不存在时部分 ROM 会把
        `cat: ... No such file or directory` 混进 exec-out 的 stdout，
        若不检查就会被误读成「有内容但不同」。
        """
        r = run([self.adb, '-s', self.serial, 'exec-out', 'cat', path], binary=True)
        if r.returncode == 0 and isinstance(r.stdout, bytes):
            return r.stdout
        return b''

    def enc_sha(self, uuid):
        """读回设备上一份 enc 算 sha256（只用于少数内容重传项，开销很小）。"""
        path = '%s/sheets/%s/sheet.xml.enc' % (REMOTE, uuid)
        data = self.read_remote(path)
        return hashlib.sha256(data).hexdigest() if data else ''

    def installed_version_code(self):
        """读设备上已安装包的 versionCode。没装 / 读不到返回 0。

        用 dumpsys package：PackageManager 自己记着这个值，不需要我们往设备
        写任何凭据文件（这正是删掉 app_fingerprint.txt 的原因）。
        """
        out = self('shell', 'dumpsys package %s' % PKG)
        m = re.search(r'versionCode=(\d+)', out or '')
        return int(m.group(1)) if m else 0

    def enc_uuids(self):
        out = self('shell', 'find %s/sheets -name sheet.xml.enc 2>/dev/null' % REMOTE)
        return set(UUID_RE.findall(out or ''))

    def cover_count(self):
        out = self('shell', 'ls %s/covers/*.jpg 2>/dev/null | wc -l' % REMOTE)
        try:
            return int((out or '').strip())
        except ValueError:
            return 0


def online_devices(adb):
    r = run([adb, 'devices'])
    out = []
    for line in (r.stdout or '').splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == 'device':
            out.append(parts[0])
    return out


def load_changed():
    """上次抓取里被标记为「内容重传」的 uuid，仅首次纳管时用来校验旧谱。"""
    if not os.path.isfile(DIFF):
        return []
    try:
        with io.open(DIFF, 'r', encoding='utf-8') as f:
            return [x['uuid'] for x in json.load(f).get('sheets_mtime_changed', [])]
    except Exception:
        return []


def clear_stage():
    """fast_push 会复用 cache/seed/ext 里的旧 enc；每次同步前清干净（逐个删）。"""
    if not os.path.isdir(STAGE):
        return
    for root, dirs, files in os.walk(STAGE, topdown=False):
        for f in files:
            os.remove(os.path.join(root, f))
        for d in dirs:
            os.rmdir(os.path.join(root, d))
    os.rmdir(STAGE)


def push_meta(d):
    d('shell', 'mkdir -p %s/covers %s/sheets' % (REMOTE, REMOTE))
    d('shell', 'touch %s/sheets/.nomedia' % REMOTE)
    ok = []
    for src, dst in ((CATALOG, 'catalog.json'), (MANIFEST, 'library_manifest.json')):
        r = run([d.adb, '-s', d.serial, 'push', src, '%s/%s' % (REMOTE, dst)])
        ok.append('%s=%s' % (dst, 'ok' if r.returncode == 0 else 'FAIL'))
    r = run([d.adb, '-s', d.serial, 'push', COVERS + os.sep + '.',
             '%s/covers' % REMOTE])
    ok.append('covers=%s' % ('ok' if r.returncode == 0 else 'FAIL'))
    # 判据改成 versionCode 之后，设备上这份 sha256 凭据已经没有意义；
    # 顺手删掉，避免以后有人看到它还以为"版本是靠它记的"。
    d('shell', 'rm -f %s/app_fingerprint.txt' % REMOTE)
    return ', '.join(ok)


def sync_one(d, local, local_sha, local_vc, args, apk_path):
    serial = d.serial
    print('\n===== %s =====' % serial)
    print('型号 %s' % (d('shell', 'getprop ro.product.model') or '').strip())

    # ---- App ----
    if not args.no_apk:
        dev_vc = d.installed_version_code()
        if dev_vc == local_vc:
            print('App：versionCode 一致（%d），跳过 install' % dev_vc)
        elif dev_vc > local_vc:
            # 设备上的比本机还新（手工装过别的包、或从别的机器同步过）：
            # 直接装等于降级，App 可能带着不兼容的存档/曲包口径。
            print('App：设备 versionCode %d 高于本机 %d，**拒绝降级**'
                  % (dev_vc, local_vc))
        else:
            action = ('首装（设备未安装）' if dev_vc == 0
                      else 'versionCode %d -> %d' % (dev_vc, local_vc))
            if args.dry_run:
                print('App：将 install（%s）' % action)
            else:
                r = run([d.adb, '-s', serial, 'install', '-r', apk_path])
                tail = ((r.stdout or '') + (r.stderr or '')).strip().splitlines()
                print('App：install（%s）-> %s'
                      % (action, tail[-1] if tail else 'no output'))
                if r.returncode != 0:
                    print('  !! 安装失败，继续同步曲包')
                else:
                    # 装完回读一次：versionCode 对不上说明装错了包（或包被系统拦下）
                    back_vc = d.installed_version_code()
                    if back_vc != local_vc:
                        print('  !! 安装后回读 versionCode=%d，与本机 %d 不一致'
                              % (back_vc, local_vc))

    # ---- 曲包差异 ----
    raw = d.read_remote('%s/library_manifest.json' % REMOTE)
    dev = None
    try:
        dev = json.loads(raw.decode('utf-8')) if raw else None
    except Exception:
        dev = None
    local_sheets = local['sheets']
    if dev and isinstance(dev.get('sheets'), dict):
        ds = dev['sheets']
        add = sorted(set(local_sheets) - set(ds))
        replace = sorted(u for u in (set(local_sheets) & set(ds))
                         if ds[u].get('h') != local_sheets[u]['h']
                         or ds[u].get('m') != local_sheets[u]['m'])
        orphan = sorted(set(ds) - set(local_sheets))
        mode = '精确清单（v%s）' % dev.get('v', '?')
    else:
        have = d.enc_uuids()
        add = sorted(set(local_sheets) - have)
        changed = [u for u in load_changed() if u in have and u in local_sheets]
        # ⚠ 不能只比字节数：服务端重传的谱新旧大小可能完全相同（这次 4 首里
        # 只有 1 首大小变了）。直接读回设备 enc 算 sha256 才判得准，且只读几份。
        replace = sorted(u for u in changed
                         if d.enc_sha(u) != local_sheets[u]['h'])
        orphan = sorted(have - set(local_sheets))
        mode = '首次纳管（扫描现有 enc + 重传项逐份 hash 校验）'
    print('曲包：%s | 补 %d / 替换 %d / 设备多出 %d'
          % (mode, len(add), len(replace), len(orphan)))
    if add[:3]:
        print('  补示例 %s' % ', '.join(add[:3]))
    if replace[:3]:
        print('  替换示例 %s' % ', '.join(replace[:3]))
    if orphan[:3]:
        print('  （下架曲目只报告不删）%s' % ', '.join(orphan[:3]))

    if args.dry_run:
        return

    need = sorted(set(add) | set(replace))
    if need:
        clear_stage()
        cmd = [sys.executable, UNPACK, '--adb', d.adb, '--serial', serial,
               '--only-enc', '--no-catalog', '--no-covers', '--uuid'] + need
        r = run(cmd)
        lines = [x for x in (r.stdout or '').splitlines() if x.strip()]
        print('谱包：%s' % (' | '.join(lines[-3:]) if lines else 'no output'))
        if r.returncode != 0:
            print('  !! 铺谱包失败：%s' % (r.stderr or '')[-200:])
    else:
        print('谱包：无需推送')

    # ---- 元数据（含设备侧版本清单与 App 指纹）----
    print('元数据：%s' % push_meta(d))

    # ---- 核对 ----
    enc = d.enc_uuids()
    back = d.read_remote('%s/library_manifest.json' % REMOTE)
    print('核对：enc %d（清单 %d） / covers %d / 设备清单 sha %s（本机 %s）'
          % (len(enc), local['sheet_count'], d.cover_count(),
             hashlib.sha256(back).hexdigest()[:12], local_sha[:12]))
    print('结果：%s' % ('OK' if len(enc) == local['sheet_count']
                       and back and hashlib.sha256(back).hexdigest() == local_sha
                       else '需复查'))


def latest_apk():
    dist = os.path.join(BASE, 'dist')
    candidates = []
    if os.path.isdir(dist):
        candidates = [os.path.join(dist, f) for f in os.listdir(dist)
                      if f.startswith('magicpiano-') and f.endswith('.apk')]
    if not candidates:
        raise SystemExit('找不到交付 APK：dist/magicpiano-<版本号>.apk')
    return max(candidates, key=os.path.getmtime)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--adb', default=ADB)
    ap.add_argument('--apk')
    ap.add_argument('--manifest', default=MANIFEST)
    ap.add_argument('--serial', action='append', default=[])
    ap.add_argument('--no-apk', action='store_true')
    ap.add_argument('--dry-run', action='store_true')
    args = ap.parse_args()

    if not os.path.isfile(args.manifest):
        raise SystemExit('缺设备侧版本清单：先跑 python tools/make_manifest.py')
    if not args.no_apk:
        args.apk = args.apk or latest_apk()
        if not os.path.isfile(args.apk):
            raise SystemExit('找不到 APK：%s' % args.apk)

    with io.open(args.manifest, 'r', encoding='utf-8') as f:
        local = json.load(f)
    local_sha = sha256_file(args.manifest)
    local_vc = apk_version_code(args.apk) if not args.no_apk else 0
    if not args.no_apk and local_vc == 0:
        raise SystemExit('读不出 APK 的 versionCode（aapt2 dump badging 失败）：%s'
                         % args.apk)
    print('本机清单：%d 首 / catalog sha %s… / 清单 sha %s… / APK versionCode %s'
          % (local['sheet_count'], local['catalog_sha256'][:12], local_sha[:12],
             local_vc if local_vc else '(跳过)'))

    serials = args.serial or online_devices(args.adb)
    if not serials:
        raise SystemExit('没有在线设备（adb devices）')
    print('目标设备：%s' % ', '.join(serials))
    for s in serials:
        try:
            sync_one(Device(args.adb, s), local, local_sha, local_vc, args, args.apk)
        except Exception as e:
            print('!! %s 同步异常：%s' % (s, e))


if __name__ == '__main__':
    main()
