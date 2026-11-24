# -*- coding: utf-8 -*-
"""一键把「App + 曲谱」装到一台设备（新设备装机就跑这一条）。

以前要两条命令、还要手填 adb 路径和 serial：
    adb install -r dist/magicpiano-<版本号>.apk
    python tools/unpack_backup.py --adb "..." --serial "..."
现在合成一条：
    python tools/deploy.py

常用参数：
    python tools/deploy.py                     # 装 APK + 铺全部曲谱（只铺 sheet.xml.enc）
    python tools/deploy.py --apk-only          # 只装/更新 APK
    python tools/deploy.py --sheets-only       # 只铺曲谱（App 已装好时）
    python tools/deploy.py --top 500           # 只铺前 500 首，几十秒完事（试用/救急）
    python tools/deploy.py --category 考级      # 只铺某一分类
    python tools/deploy.py --album "巴斯蒂安钢琴速成 2B级"
    python tools/deploy.py --full              # 连 cover.png 等一起铺（多约 3.1GB）
    python tools/deploy.py --dry-run           # 只看会做什么，不真装

说明：
  * 默认**只铺 sheet.xml.enc**（App 唯一用到的文件），比铺全部文件省约 3.1GB 和时间。
  * adb 自动探测：优先用 --adb，其次 local.env 的 ADB / ANDROID_SDK_DIR，最后 PATH。
  * 设备自动选：只连了一台就直接用；多台要加 --serial。
  * 曲谱是**增量**的：设备已有就跳过，中断后重跑即可。
"""
import argparse
import hashlib
import json
import os
import subprocess
import sys

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOLS = os.path.join(BASE, 'tools')
sys.path.insert(0, TOOLS)
from local_config import adb_path

DIST = os.path.join(BASE, 'dist')
APK_BUILD = os.path.join(BASE, 'app', 'build', 'MagicPiano.apk')
CATALOG = os.path.join(BASE, 'library', 'catalog.json')


def find_adb(cli=None):
    cands = [cli, adb_path(), 'adb']
    for c in cands:
        try:
            r = subprocess.run([c, 'version'], capture_output=True, text=True, timeout=15)
            if r.returncode == 0:
                return c
        except Exception:
            continue
    raise SystemExit('找不到可用的 adb。请用 --adb 指定完整路径。')


def online_devs(adb):
    r = subprocess.run([adb, 'devices'], capture_output=True, text=True, timeout=30)
    devs = []
    for line in (r.stdout or '').splitlines()[1:]:
        line = line.strip()
        if not line:
            continue
        parts = line.split('\t')
        if len(parts) >= 2 and parts[1] == 'device':
            devs.append(parts[0])
    return devs


def pick_serial(adb, cli_serial=None):
    subprocess.run([adb, 'start-server'], capture_output=True, text=True, timeout=60)
    devs = online_devs(adb)
    if not devs:
        # adb server 刚起来时 devices 可能是空的，等一下再问一次
        import time
        time.sleep(2)
        devs = online_devs(adb)
    if cli_serial:
        if cli_serial not in devs:
            print('!! 指定的 --serial %s 不在在线列表里（在线：%s）' % (cli_serial, devs))
        return cli_serial
    if not devs:
        raise SystemExit('没有在线设备（adb devices 为空）。检查 USB 调试/授权。')
    if len(devs) > 1:
        raise SystemExit('有多台设备 %s，请用 --serial 指定。' % devs)
    return devs[0]


def catalog_stamp():
    """catalog.json 的状态；曲库更新后换暂存，避免沿用旧曲集目录。"""
    try:
        st = os.stat(CATALOG)
        return '%d:%d' % (st.st_size, st.st_mtime_ns)
    except OSError:
        return '-'


def use_stage(args, subset, uuids):
    """挑一个解压暂存目录。

    fast_push 是「整个暂存目录一次 push」，所以**暂存里混着别的东西就会被一起推走**：
    比如先全量铺过（暂存里 17969 首），再跑 --top 20，会把那 17969 首又推一遍。
    因此按筛选条件、catalog 状态和本次 uuid 集分目录，并在签名变化时清空，
    保证推的正是这次要的。
    """
    import shutil
    import unpack_backup as U
    stamp = catalog_stamp()
    u_sig = hashlib.sha1('\n'.join(uuids).encode('utf-8')).hexdigest()[:12]
    if subset:
        sig = 'part|%s|%s|%s|%s|%s' % (args.category or '', args.album or '',
                                        args.top or 0, stamp, u_sig)
        d = os.path.join(BASE, 'cache', 'seed', 'part')
    elif args.full:
        sig = 'full|%s|%s' % (stamp, u_sig)
        d = os.path.join(BASE, 'cache', 'seed', 'full')
    else:
        sig = 'enc|%s|%s' % (stamp, u_sig)
        d = os.path.join(BASE, 'cache', 'seed', 'ext')
    mark = os.path.join(d, '.filter')
    old = None
    if os.path.isfile(mark):
        try:
            old = open(mark, encoding='utf-8').read().strip()
        except Exception:
            old = None
    if os.path.isdir(d) and old != sig:
        print('  暂存目录 %s 是「%s」的，与本次「%s」不同 → 清空重建'
              % (os.path.basename(d), old or '未知', sig))
        shutil.rmtree(d, ignore_errors=True)
    os.makedirs(d, exist_ok=True)
    try:
        open(mark, 'w', encoding='utf-8').write(sig)
    except Exception:
        pass
    U.STAGE_DIR = d
    return d


def pick_apk():
    candidates = []
    if os.path.isdir(DIST):
        candidates = [os.path.join(DIST, f) for f in os.listdir(DIST)
                      if f.startswith('magicpiano-') and f.endswith('.apk')]
    if candidates:
        return max(candidates, key=os.path.getmtime)
    if os.path.isfile(APK_BUILD):
        return APK_BUILD
    raise SystemExit('找不到 APK：先构建并复制为 dist/magicpiano-<版本号>.apk；'
                     '构建中间文件 %s 也不存在。' % APK_BUILD)


def uuids_for(args):
    import unpack_backup as U
    catalog = json.load(open(CATALOG, encoding='utf-8'))
    if args.album:
        u = U.sheets_of_album(catalog, args.album)
        print('曲集《%s》共 %d 首' % (args.album, len(u)))
        if not u:
            raise SystemExit('目录里找不到这本曲集：%s' % args.album)
    elif args.category:
        u, hit = [], False
        for c in catalog['categories']:
            if c['name'] != args.category:
                continue
            hit = True
            for t in c['tags']:
                for a in t['albums']:
                    for s in a['sheets']:
                        u.append(s[0])
        if not hit:
            raise SystemExit('没有这个分类：%s（可选：%s）'
                             % (args.category,
                                '/'.join(c['name'] for c in catalog['categories'])))
        u = list(dict.fromkeys(u))
        print('分类「%s」共 %d 首' % (args.category, len(u)))
    else:
        u = U.all_sheets(catalog)
    if args.top:
        u = u[:args.top]
        print('--top %d：只铺前 %d 首' % (args.top, len(u)))
    return u


def main():
    ap = argparse.ArgumentParser(description='一键安装 App + 铺曲谱')
    ap.add_argument('--adb', help='adb 可执行文件路径（默认自动找）')
    ap.add_argument('--serial', help='设备 serial（默认自动选唯一一台）')
    ap.add_argument('--apk-only', action='store_true', help='只装 APK')
    ap.add_argument('--sheets-only', action='store_true', help='只铺曲谱')
    ap.add_argument('--category', help='只铺这个分类（考级/音阶练习曲/名家/流行/重奏&协奏曲）')
    ap.add_argument('--album', help='只铺这本曲集')
    ap.add_argument('--top', type=int, default=0, help='只铺前 N 首')
    ap.add_argument('--full', action='store_true',
                    help='连 cover.png 等一起铺（默认只铺 sheet.xml.enc，省 3.1GB）')
    ap.add_argument('--dry-run', action='store_true')
    ap.add_argument('--no-launch', action='store_true', help='装完不自动启动 App')
    ap.add_argument('--skip-catalog', action='store_true',
                    help='不推 catalog.json / covers（自己用资源包导入时）')
    args = ap.parse_args()

    adb = find_adb(args.adb)
    serial = pick_serial(adb, args.serial)
    print('adb    = %s' % adb)
    print('设备   = %s' % serial)

    def A(*a):
        cmd = [adb]
        if serial:
            cmd += ['-s', serial]
        cmd += list(a)
        return subprocess.run(cmd, capture_output=True, text=True)

    # ---------- 1) 装 APK ----------
    if not args.sheets_only:
        apk = pick_apk()
        print('\n[1/2] 安装 APK：%s（%.1f MB）'
              % (os.path.relpath(apk, BASE), os.path.getsize(apk) / 1048576))
        if args.dry_run:
            print('      (--dry-run 跳过)')
        else:
            r = A('install', '-r', apk)
            tail = ((r.stdout or '') + (r.stderr or '')).strip().splitlines()
            print('      ' + (tail[-1] if tail else '?'))
            if r.returncode != 0:
                raise SystemExit('安装失败')

    # ---------- 2) 铺曲谱 ----------
    if not args.apk_only:
        import unpack_backup as U
        u = uuids_for(args)
        subset = bool(args.category or args.album or args.top)
        stage = use_stage(args, subset, u)
        print('\n[2/2] 铺曲谱：%d 首（%s），暂存 %s'
              % (len(u), '全部文件' if args.full else '只铺 sheet.xml.enc',
                 os.path.basename(stage)))
        ns = argparse.Namespace(only_enc=not args.full, dry_run=False)
        if args.dry_run:
            print('      (--dry-run，不推送)')
            return
        remote = '/sdcard/Android/data/%s/files' % U.PKG
        A('shell', 'mkdir -p %s/sheets' % remote)
        A('shell', 'touch %s/sheets/.nomedia' % remote)
        # 目录与封面（和乐谱放同一个位置）：不走资源包时，adb 部署自己补上，
        # 否则 App 起来会因为没有 catalog.json 而停在"导入资源包"页。
        if not args.skip_catalog:
            cat = os.path.join(BASE, 'library', 'catalog.json')
            cov = os.path.join(BASE, 'library', 'covers')
            if os.path.isfile(cat):
                A('push', cat, remote + '/catalog.json')
                print('      已推送 catalog.json')
            if os.path.isdir(cov):
                A('push', cov, remote + '/covers')
                print('      已推送 covers/')
        U.fast_push(ns, u, A)

    # ---------- 收尾 ----------
    if args.dry_run:
        return
    n = A('shell', 'find /sdcard/Android/data/%s/files/sheets/ -name sheet.xml.enc | wc -l'
          % 'com.magicpig.piano')
    print('\n设备侧谱文件总数：%s' % (n.stdout or '').strip())
    if not args.no_launch:
        A('shell', 'am', 'start', '-n', 'com.magicpig.piano/.HomeActivity')
        print('已启动 App。')
    print('\n完成。')


if __name__ == '__main__':
    main()
