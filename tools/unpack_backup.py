# -*- coding: utf-8 -*-
"""把 library/sheets/ 里的曲谱包铺设到设备（离线版 App 的唯一曲谱来源）。

App 端已经没有任何下载逻辑：曲谱只能放在
    sdcard/Android/data/com.magicpig.piano/files/sheets/<乐曲uuid>/sheet.xml.enc
没有拷贝进去的曲谱，点开只弹 toast，不会联网。所以这个脚本就是离线版的"装填器"。

用法：
python tools/unpack_backup.py --serial <serial>  # adb 来自 local.env 或 PATH
    python tools/unpack_backup.py --only-enc            # 只铺 sheet.xml.enc（约 4.3GB）
    python tools/unpack_backup.py --album "巴斯蒂安钢琴速成 2B级"   # 只铺某一本曲集
    python tools/unpack_backup.py --uuid <uuid> [...]   # 只铺指定乐曲
    python tools/unpack_backup.py --dry-run             # 只看会铺哪些，不真推
    python tools/unpack_backup.py --no-catalog          # 不推 catalog.json（默认会推）
    python tools/unpack_backup.py --no-covers           # 不推封面（默认会推）

已存在于设备的直接跳过，可随时中断后重跑（增量铺设）。
默认会顺带把 library/catalog.json 与 library/covers/ 推到设备：
- 只推谱包不推目录 → 新曲在曲库/最近打开里查不到名字，显示 uuid 前缀；
- 不推封面 → 所有曲集都退化成同一张占位图（清应用数据后只补谱包就是这种结果）。
不需要 root —— 推的是应用专属外部目录。
"""
import argparse
import io
import json
import os
import subprocess
import sys
import tempfile
import zipfile

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from local_config import adb_path

BACKUP = os.path.join(BASE, 'library', 'sheets')
CATALOG = os.path.join(BASE, 'library', 'catalog.json')
COVERS = os.path.join(BASE, 'library', 'covers')
PKG = 'com.magicpig.piano'

# 本地解压暂存目录（deploy.py 会覆盖它，让「只铺 enc」和「铺全部文件」各用一份）
STAGE_DIR = os.path.join(BASE, 'cache', 'seed', 'ext')


def sheets_of_album(catalog, name):
    """按曲集名取该曲集全部乐曲 uuid（顺序保持目录里的顺序）。"""
    out = []
    for c in catalog['categories']:
        for t in c['tags']:
            for a in t['albums']:
                if a['name'] == name:
                    for s in a['sheets']:
                        if s[0] not in out:
                            out.append(s[0])
    return out


def all_sheets(catalog):
    out, seen = [], set()
    for c in catalog['categories']:
        for t in c['tags']:
            for a in t['albums']:
                for s in a['sheets']:
                    if s[0] not in seen:
                        seen.add(s[0])
                        out.append(s[0])
    return out


def remote_dir(uuid):
    return '/sdcard/Android/data/%s/files/sheets/%s' % (PKG, uuid)



def fast_push(args, uuids, adb):
    """在本地把目录树搭好，然后一条 adb push 推完。

    逐个文件调 adb 的话，每次都要建一次连接、跑一次 sync 协议；
    17893 个文件实测要一个多小时。改成"本地搭树 + 目录级 push"后
    （adb 内部走单次 sync 会话）只要 2~3 分钟，实测 28MB/s。
    """
    # 解压暂存目录（deploy.py 在"全量模式"下会换一个，避免和"只铺 enc"的暂存混在一起）
    stage = STAGE_DIR
    os.makedirs(stage, exist_ok=True)
    names = ['sheet.xml.enc'] if args.only_enc else None

    todo, missing = [], []
    for u in uuids:
        zp = os.path.join(BACKUP, u + '.zip')
        if not os.path.exists(zp):
            missing.append(u)
            continue
        out_dir = os.path.join(stage, u)
        out_enc = os.path.join(out_dir, 'sheet.xml.enc')
        if os.path.exists(out_enc) and os.path.getsize(out_enc) > 0:
            todo.append(u)
            continue
        try:
            with zipfile.ZipFile(zp) as z:
                take = names or [n for n in z.namelist() if not n.endswith('/')]
                os.makedirs(out_dir, exist_ok=True)
                for n in take:
                    with open(os.path.join(out_dir, os.path.basename(n)), 'wb') as w:
                        w.write(z.read(n))
            todo.append(u)
        except Exception as e:
            print('  !! 解包失败 %s: %s' % (u, e))

    total = sum(os.path.getsize(os.path.join(stage, u, 'sheet.xml.enc'))
                for u in todo if os.path.exists(os.path.join(stage, u, 'sheet.xml.enc')))
    print('本地暂存 %d 首 / %.2f GB；备份缺失 %d 首'
          % (len(todo), total / 1073741824.0, len(missing)))
    if args.dry_run:
        print('(--dry-run，不推送)')
        return
    print('开始推送（目录级单次 sync，实测约 28MB/s）…', flush=True)
    r = adb('push', os.path.join(stage, '.'),
            '/sdcard/Android/data/%s/files/sheets/' % PKG)
    ok = r.returncode == 0
    print((r.stdout or r.stderr or '').strip()[-200:])
    if not ok:
        raise SystemExit('推送失败')
    n = adb('shell', 'find /sdcard/Android/data/%s/files/sheets/ -name sheet.xml.enc | wc -l' % PKG)
    print('设备侧现有谱文件：%s' % (n.stdout or '').strip())
    if missing:
        print('备份里没有的 uuid（服务端 404，需另找）：%s…' % ', '.join(missing[:5]))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--adb', default=adb_path())
    ap.add_argument('--serial', default='')
    ap.add_argument('--only-enc', action='store_true',
                    help='只铺 sheet.xml.enc（其余文件 App 用不到，可省 3.1GB）')
    ap.add_argument('--album', help='只铺这本曲集')
    ap.add_argument('--uuid', nargs='*', help='只铺这些乐曲 uuid')
    ap.add_argument('--dry-run', action='store_true')
    ap.add_argument('--limit', type=int, default=0, help='只铺前 N 首（试跑用）')
    ap.add_argument('--no-catalog', action='store_true',
                    help='不推 catalog.json（默认会推，保证新曲能查到名字）')
    ap.add_argument('--no-covers', action='store_true',
                    help='不推封面（默认会推，否则所有曲集都显示同一张占位图）')
    ap.add_argument('--slow', action='store_true',
                    help='逐个文件 adb push（默认走"本地搭树 + 一次推送"，快 20 倍以上）')
    args = ap.parse_args()

    catalog = json.load(open(CATALOG, encoding='utf-8'))
    if args.uuid:
        uuids = list(args.uuid)
    elif args.album:
        uuids = sheets_of_album(catalog, args.album)
        print('曲集《%s》共 %d 首' % (args.album, len(uuids)))
        if not uuids:
            raise SystemExit('目录里找不到这本曲集：%s' % args.album)
    else:
        uuids = all_sheets(catalog)
    if args.limit:
        uuids = uuids[:args.limit]

    def adb(*a):
        cmd = [args.adb]
        if args.serial:
            cmd += ['-s', args.serial]
        cmd += list(a)
        return subprocess.run(cmd, capture_output=True, text=True)

    if not args.dry_run:
        adb('shell', 'mkdir -p /sdcard/Android/data/%s/files/sheets' % PKG)
        # .nomedia：否则媒体扫描器会把上万个目录和 cover.png 全索引进图库
        adb('shell', 'touch /sdcard/Android/data/%s/files/sheets/.nomedia' % PKG)
        # 目录也同步一份：只推谱包不推 catalog.json 的话，新曲在曲库/最近打开里
        # 查不到名字，会显示 uuid 前缀。catalog 只有 2MB，每次推代价可以忽略。
        if not args.no_catalog and os.path.isfile(CATALOG):
            r = adb('push', CATALOG,
                    '/sdcard/Android/data/%s/files/catalog.json' % PKG)
            print('已同步 catalog.json' if r.returncode == 0 else '!! catalog.json 推送失败')
        # 封面：目录里只有 cover uuid，图在 files/covers/<uuid>.jpg。
        # 缺了会全部退化成占位图（用户看到"封面全一样"），只有 9MB，一并推。
        if not args.no_covers and os.path.isdir(COVERS):
            adb('shell', 'mkdir -p /sdcard/Android/data/%s/files/covers' % PKG)
            r = adb('push', COVERS + os.sep + '.',
                    '/sdcard/Android/data/%s/files/covers' % PKG)
            print('已同步封面 %d 张' % len([f for f in os.listdir(COVERS)
                                            if f.endswith('.jpg')])
                  if r.returncode == 0 else '!! 封面推送失败')

    if not args.slow:
        fast_push(args, uuids, adb)
        return

    todo, missing, ok, skip = [], [], 0, 0
    for u in uuids:
        zp = os.path.join(BACKUP, u + '.zip')
        if not os.path.exists(zp):
            missing.append(u)
            continue
        # 设备端已有就跳过（增量）
        if not args.dry_run:
            r = adb('shell', 'ls %s/sheet.xml.enc' % remote_dir(u))
            if r.returncode == 0 and 'No such file' not in (r.stdout + r.stderr):
                skip += 1
                continue
        todo.append(u)

    print('待铺 %d 首 / 已有 %d 首 / 备份缺失 %d 首' % (len(todo), skip, len(missing)))
    if args.dry_run:
        for u in todo[:20]:
            print('   将铺', u)
        return

    tmp = tempfile.mkdtemp(prefix='sheet_seed_')
    for i, u in enumerate(todo, 1):
        zp = os.path.join(BACKUP, u + '.zip')
        out = os.path.join(tmp, u)
        os.makedirs(out, exist_ok=True)
        try:
            z = zipfile.ZipFile(zp)
            names = ['sheet.xml.enc'] if args.only_enc \
                else [n for n in z.namelist() if not n.endswith('/')]
            for n in names:
                z.extract(n, out)
            z.close()
        except Exception as e:
            print('  !! 解包失败 %s: %s' % (u, e))
            continue
        adb('shell', 'mkdir -p %s' % remote_dir(u))
        for n in names:
            r = adb('push', os.path.join(out, n), '%s/%s' % (remote_dir(u), n))
            if r.returncode != 0:
                print('  !! 推送失败 %s/%s: %s' % (u, n, (r.stderr or '').strip()[:120]))
                break
        else:
            ok += 1
        if i % 200 == 0 or i == len(todo):
            print('  进度 %d/%d  已铺 %d' % (i, len(todo), ok), flush=True)

    print('\n完成：本次铺设 %d 首（跳过已有 %d，备份缺失 %d）' % (ok, skip, len(missing)))
    if missing:
        print('备份里没有的 uuid：%s…' % ', '.join(missing[:5]))


if __name__ == '__main__':
    main()
