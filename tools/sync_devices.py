# -*- coding: utf-8 -*-
"""按「设备侧版本清单」做真正的多设备增量同步（App + 曲包）。

App 的升级判据是 **versionCode** —— Android 的标准做法，PackageManager 本来
就记着它，设备侧**不需要任何凭据文件**：
  · 本机 versionCode 从 APK 里读（app/version.py 生成 YYMMDDNNN，build.sh 注入）
  · 设备 versionCode 从 `dumpsys package` 读
  · 设备低 → install；相等 → 跳过；设备高 → 拒绝（防降级）

  版本码统一用 9 位日期码（YYMMDDNNN），序号取 3 位：8 位的 YYMMDDNN 比 9 位码
  小，实测连 `install -r -d` 都会被系统拒绝（INSTALL_FAILED_VERSION_DOWNGRADE）。

  App 的升级判据只有 versionCode，不用 APK 指纹：构建不可复现（zip 条目时间戳等），
  同一份源码重编 sha 也会变，拿它判"要不要装"只会白重装。

曲包仍然靠设备侧清单，因为一万多首没法靠文件名判断内容版本：
  library_manifest.json  catalog 的 sha256 + 每首乐曲的 enc sha256（由 make_manifest.py 生成）

同步流程：
  · 曲包：
      - 设备有清单 → 精确算 add（本机有、设备无、catalog 仍列出；
        设备清单有、但设备实际缺 enc 的也补）/
        replace（清单 hash 不同；mtime 变化时回读 enc hash 确认）/
        下架（设备实际有、catalog 无：删除）。
      - 设备没有清单（首次纳管）→ 扫描设备现有 enc 的 uuid 集合，补 add；
        对 cache/catalog/catalog_diff.json 里标记为「内容重传」的曲目读回设备 enc
        算 sha256 再判 replace（重传新旧大小可能完全相同，不能只比字节数）。
        随后把本机清单推上去完成纳管。
    平时每次只比较当前清单与设备，不读取 diff 文件。

只推变化的谱包；catalog.json / covers / 清单这几份元数据每次都推
（体积远小于整份谱包，也顺带保证目录与封面一致）。

用法：
  python tools/sync_devices.py                       # 所有在线设备
  python tools/sync_devices.py --serial <serial>   # 单台
  python tools/sync_devices.py --dry-run             # 只看差异报告
  python tools/sync_devices.py --no-apk             # 只同步曲包
  python tools/sync_devices.py --force              # 设备清单有、但 enc 全缺时仍重铺
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
SHEETS = os.path.join(BASE, 'library', 'sheets')
MANIFEST = os.path.join(BASE, 'library', 'library_manifest.json')
# 与 app/build.sh 里的 $BT 保持一致（读 APK 的 versionCode 要用 dump badging）
AAPT2 = aapt2_path()
DIFF = os.path.join(BASE, 'cache', 'catalog', 'catalog_diff.json')

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

        用 dumpsys package：PackageManager 记录版本，版本判断不依赖额外凭据文件。
        """
        out = self('shell', 'dumpsys package %s' % PKG)
        m = re.search(r'versionCode=(\d+)', out or '')
        return int(m.group(1)) if m else 0

    def enc_uuids(self):
        """返回设备实际存在的 enc uuid。

        `find` 的命令失败（adb 断连、设备存储异常）必须抛异常，不能把空结果
        当成“设备上一首都没有”；目录不存在则视为合法空集。否则调用方可能
        把整库重推一遍，或反过来漏掉真正需要补的包。
        """
        cmd = ('if [ -d %s/sheets ]; then '
               'find %s/sheets -name sheet.xml.enc; fi') % (REMOTE, REMOTE)
        r = run([self.adb, '-s', self.serial, 'shell', cmd])
        if r.returncode != 0:
            raise RuntimeError('读取设备 enc 列表失败：%s'
                               % ((r.stderr or r.stdout or '').strip()[-200:]))
        return set(UUID_RE.findall((r.stdout or '').replace('\r', '')))

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
    """catalog_diff.json 里被标记为「内容重传」的 uuid，仅首次纳管时用来校验设备旧谱。"""
    if not os.path.isfile(DIFF):
        return []
    try:
        with io.open(DIFF, 'r', encoding='utf-8') as f:
            return [x['uuid'] for x in json.load(f).get('sheets_mtime_changed', [])]
    except Exception:
        return []


def catalog_uuids():
    """当前 catalog 的全部 uuid；下架清理只认它，manifest 缺曲不能作为删除依据。"""
    with io.open(CATALOG, 'r', encoding='utf-8') as f:
        cat = json.load(f)
    out = set()
    for c in cat.get('categories') or []:
        for t in c.get('tags') or []:
            for a in t.get('albums') or []:
                for s in a.get('sheets') or []:
                    if s and s[0]:
                        out.add(s[0])
    return out


def push_meta(d, manifest_path=MANIFEST):
    """推送目录、清单与封面；返回（可打印摘要，失败项列表）。

    调用方必须把失败项并入设备的最终结果，不能只看最后的 enc 数量：
    catalog 推失败时 enc 仍可能齐全，但 App 首页已经读不到目录。
    """
    failed = []
    if d.rc('shell', 'mkdir -p %s/covers %s/sheets' % (REMOTE, REMOTE)) != 0:
        failed.append('covers/sheets 目录')
    if d.rc('shell', 'touch %s/sheets/.nomedia' % REMOTE) != 0:
        failed.append('.nomedia')
    ok = []
    for src, dst in ((CATALOG, 'catalog.json'), (manifest_path, 'library_manifest.json')):
        r = run([d.adb, '-s', d.serial, 'push', src, '%s/%s' % (REMOTE, dst)])
        good = r.returncode == 0
        ok.append('%s=%s' % (dst, 'ok' if good else 'FAIL'))
        if not good:
            failed.append(dst)
    r = run([d.adb, '-s', d.serial, 'push', COVERS + os.sep + '.',
             '%s/covers' % REMOTE])
    good = r.returncode == 0
    ok.append('covers=%s' % ('ok' if good else 'FAIL'))
    if not good:
        failed.append('covers')
    # 设备侧不需要 APK 指纹；清掉可能残留的旧文件。
    d('shell', 'rm -f %s/app_fingerprint.txt' % REMOTE)
    return ', '.join(ok), failed


def sync_one(d, local, local_sha, local_vc, catalog_set, args, apk_path):
    """同步一台设备；返回 False 表示它没有达到目标状态。"""
    serial = d.serial
    ok = True
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
            ok = False
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
                    ok = False
                else:
                    # 装完回读一次：versionCode 对不上说明装错了包（或包被系统拦下）
                    back_vc = d.installed_version_code()
                    if back_vc != local_vc:
                        print('  !! 安装后回读 versionCode=%d，与本机 %d 不一致'
                              % (back_vc, local_vc))
                        ok = False

    # ---- 曲包差异 ----
    raw = d.read_remote('%s/library_manifest.json' % REMOTE)
    dev = None
    try:
        dev = json.loads(raw.decode('utf-8')) if raw else None
    except Exception:
        dev = None
    local_sheets = local['sheets']
    manifest_gap = []
    if dev and isinstance(dev.get('sheets'), dict):
        ds = dev['sheets']
        allowed = set(local_sheets) & catalog_set
        # 设备清单不等于设备现实：清单有、实际 enc 文件不存在时，
        # 也要并入待补集合，否则最后只能报“需复查”，下一轮仍然不补。
        # 这里只判断存在性；大小/内容不逐份扫描（全库读一遍代价太高）。
        have = set(d.enc_uuids())
        # 清单说有一堆、实际一个都读不到：可能是 sheets 目录被清空，
        # 也可能是 adb/存储异常。按“全部都缺”处理会把整库重推一遍；
        # 默认跳过这台，确认确实要重铺时显式加 --force。
        if ds and not have:
            print('  !! 设备清单有 %d 首，但读不到任何 enc；'
                  '疑似目录被清空或设备异常' % len(ds))
            if not args.force:
                print('    已跳过这台；确认要重铺整库请加 --force')
                return False
            print('    --force 已指定，按全量待补继续')
        manifest_gap = sorted((allowed & set(ds)) - have)
        add = sorted((allowed - set(ds)) | set(manifest_gap))
        replace = []
        for u in sorted(allowed & set(ds)):
            if u not in have:
                continue   # 实际文件缺失，已由 add 接管；不要同时算替换
            want = local_sheets[u]['h']
            dev_h = ds[u].get('h')
            if dev_h != want:
                replace.append(u)
            elif ds[u].get('m') != local_sheets[u]['m'] and d.enc_sha(u) != want:
                replace.append(u)
        # ⚠ 下架残留必须按设备上实际的 enc 文件算，不能只比清单：
        # 设备清单可能已不含它，只比清单会漏，enc 数也会一直多一个。
        # 下架只认 catalog；设备清单缺曲不能作为删除依据。
        delisted = sorted(have - catalog_set)
        mode = '精确清单（v%s）' % dev.get('v', '?')
    else:
        have = set(d.enc_uuids())
        allowed = set(local_sheets) & catalog_set
        add = sorted(allowed - have)
        changed = [u for u in load_changed() if u in have and u in allowed]
        # ⚠ 不能只比字节数：服务端重传的谱新旧大小可能完全相同，
        # 直接读回设备 enc 算 sha256 才判得准，且只读少数几份。
        replace = sorted(u for u in changed
                         if d.enc_sha(u) != local_sheets[u]['h'])
        delisted = sorted(have - catalog_set)
        mode = '首次纳管（扫描现有 enc + 重传项逐份 hash 校验）'
    print('曲包：%s | 补 %d / 替换 %d / 设备 enc %d（下架 %d）'
          % (mode, len(add), len(replace), len(have), len(delisted)))
    if add[:3]:
        print('  补示例 %s' % ', '.join(add[:3]))
    if manifest_gap[:3]:
        print('  清单有、实际缺 enc %d 首：%s'
              % (len(manifest_gap), ', '.join(manifest_gap[:3])))
    if replace[:3]:
        print('  替换示例 %s' % ', '.join(replace[:3]))
    if delisted[:3]:
        print('  （%s）%s' % ('下架候选' if args.dry_run else '下架：设备与本地一并删',
                              ', '.join(delisted[:3])))

    if args.dry_run:
        return ok

    need = sorted(set(add) | set(replace))
    if need:
        # 暂存复用交给 unpack_backup.fast_push：它按源 zip 的 size+mtime 校验，
        # 并按本次 need 清掉暂存里多余的 uuid，不再在这里整目录清空。
        cmd = [sys.executable, UNPACK, '--adb', d.adb, '--serial', serial,
               '--only-enc', '--no-catalog', '--no-covers', '--uuid'] + need
        r = run(cmd)
        lines = [x for x in (r.stdout or '').splitlines() if x.strip()]
        print('谱包：%s' % (' | '.join(lines[-3:]) if lines else 'no output'))
        if r.returncode != 0:
            print('  !! 铺谱包失败：%s' % (r.stderr or '')[-200:])
            ok = False
    else:
        print('谱包：无需推送')

    # ---- 下架清理：只删 catalog 也没有的 uuid ----
    # catalog 是下架判据；设备清单不等于实际文件，删除只看 catalog 和实际 enc。
    # 设备删除成功后才删本地 zip；超过 50 个先不动 —— 那多半是 catalog 本身出了问题。
    if delisted:
        if len(delisted) > 50:
            print('下架清理：%d 个，超过 50，先不自动删（catalog 可能有问题，先人工核对）'
                  % len(delisted))
            ok = False
        else:
            removed = set()
            for u in delisted:
                if not u or '/' in u:
                    continue
                if d.rc('shell', 'rm', '-rf', '%s/sheets/%s' % (REMOTE, u)) == 0:
                    removed.add(u)
            loc_n = 0
            for u in delisted:
                if u not in removed:
                    continue
                p = os.path.join(BASE, 'library', 'sheets', u + '.zip')
                if os.path.isfile(p):
                    try:
                        os.remove(p)
                        loc_n += 1
                    except OSError as e:
                        print('  !! 删本地包失败 %s：%s' % (u[:8], e))
                        ok = False
            failed = [u for u in delisted if u and '/' not in u and u not in removed]
            if failed:
                print('  !! 设备删除失败，保留本地 zip：%s' % ', '.join(failed[:3]))
                ok = False
            print('下架清理：设备 %d / 本地 %d（共 %d 个下架）'
                  % (len(removed), loc_n, len(delisted)))

    # ---- 元数据（目录、封面与设备侧版本清单）----
    meta, meta_failed = push_meta(d, args.manifest)
    print('元数据：%s' % meta)
    if meta_failed:
        print('  !! 元数据推送失败：%s' % ', '.join(meta_failed))
        ok = False

    # ---- 核对 ----
    enc = d.enc_uuids()
    back = d.read_remote('%s/library_manifest.json' % REMOTE)
    back_sha = hashlib.sha256(back).hexdigest() if back else None
    verified = bool(len(enc) == local['sheet_count'] and back_sha
                    and back_sha == local_sha)
    print('核对：enc %d（清单 %d） / covers %d / 设备清单 sha %s（本机 %s）'
          % (len(enc), local['sheet_count'], d.cover_count(),
             back_sha[:12] if back_sha else '(缺失)', local_sha[:12]))
    result = bool(verified and ok)
    print('结果：%s' % ('OK' if result else '需复查（见上方失败项）'))
    return result


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
    ap.add_argument('--force', action='store_true',
                    help='设备清单非空但实际 0 个 enc 时仍按全量待补继续（默认跳过）')
    args = ap.parse_args()

    if not os.path.isfile(args.manifest):
        raise SystemExit('缺设备侧版本清单：先跑 python tools/make_manifest.py')
    if not args.no_apk:
        args.apk = args.apk or latest_apk()
        if not os.path.isfile(args.apk):
            raise SystemExit('找不到 APK：%s' % args.apk)

    with io.open(args.manifest, 'r', encoding='utf-8') as f:
        local = json.load(f)
    catalog_set = catalog_uuids()
    if not catalog_set:
        raise SystemExit('catalog.json 没有曲目，拒绝同步（下架清理会误删）')
    local_sheets = local.get('sheets')
    if not isinstance(local_sheets, dict) or len(local_sheets) != local.get('sheet_count'):
        raise SystemExit('清单不完整：sheets %d / sheet_count %s，先重跑 make_manifest'
                         % (len(local_sheets or {}), local.get('sheet_count')))
    missing_manifest = sorted(catalog_set - set(local_sheets))
    if missing_manifest:
        raise SystemExit('清单缺 %d 首（catalog 有、manifest 没有）：先重跑 make_manifest'
                         % len(missing_manifest))
    missing_zips = sorted(u for u in catalog_set
                          if not os.path.isfile(os.path.join(SHEETS, u + '.zip')))
    if missing_zips:
        raise SystemExit('本地缺 %d 个谱包（清单/catalog 有）：先补包再同步'
                         % len(missing_zips))
    local_sha = sha256_file(args.manifest)
    local_vc = apk_version_code(args.apk) if not args.no_apk else 0
    if not args.no_apk and local_vc == 0:
        raise SystemExit('读不出 APK 的 versionCode（aapt2 dump badging 失败）：%s'
                         % args.apk)
    print('本机清单：%d 首 / catalog %d 首 / catalog sha %s… / 清单 sha %s… / APK versionCode %s'
          % (local['sheet_count'], len(catalog_set), local['catalog_sha256'][:12],
             local_sha[:12], local_vc if local_vc else '(跳过)'))

    serials = args.serial or online_devices(args.adb)
    if not serials:
        raise SystemExit('没有在线设备（adb devices）')
    print('目标设备：%s' % ', '.join(serials))
    failed = []
    for s in serials:
        try:
            if not sync_one(Device(args.adb, s), local, local_sha, local_vc,
                            catalog_set, args, args.apk):
                failed.append(s)
        except Exception as e:
            print('!! %s 同步异常：%s' % (s, e))
            failed.append(s)
    if failed:
        print('\n!! %d 台设备未完成同步：%s' % (len(failed), ', '.join(failed)))
        raise SystemExit(1)


if __name__ == '__main__':
    main()
