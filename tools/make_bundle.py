# -*- coding: utf-8 -*-
"""把「曲集/乐曲目录 + 封面 + 乐谱」打成一个资源包 zip，供 App 用 SAF 导入。

包结构（App 解压后原样落到 files/ 下）：
    catalog.json                      曲集/乐曲目录
    covers/<cover_uuid>.jpg           曲集封面
    sheets/<uuid>/sheet.xml.enc       乐谱（App 唯一用到的文件）

用法：
    python tools/make_bundle.py                    # 全量（体积随曲库增长）
    python tools/make_bundle.py --top 500          # 只打前 500 首的乐谱（目录仍是全的）
    python tools/make_bundle.py --category 考级     # 只打该分类的乐谱
    python tools/make_bundle.py --album "..."      # 只打该曲集的乐谱，文件名带曲集名
    python tools/make_bundle.py --out 自定义名.zip
    python tools/make_bundle.py --dry-run          # 只算规模不打包
    python tools/make_bundle.py --allow-missing    # 缺本地谱包时仍出包（损坏仍拒绝）

要点：
  * **ZIP_STORED 不压缩**：.enc 本身加密过、压缩比 1.01，压缩纯属白烧 CPU；
    jpg 也已是压缩格式。不压缩还能让 App 侧解压变成纯拷贝，快很多。
  * **zip64**：全量包 >4GB 必须开 zip64（Python 默认自动，Java ZipInputStream 支持）。
  * 乐谱只取 sheet.xml.enc（App 唯一读的文件），不带 cover.png 等附属文件。
  * 默认要求本次选中的谱包全部存在且每个包可读；`--allow-missing` 只放行缺失，
    损坏、为空或缺少 sheet.xml.enc 仍拒绝。
"""
import argparse
import io
import json
import os
import re
import sys
import time
import zipfile

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CATALOG = os.path.join(BASE, 'library', 'catalog.json')
COVERS = os.path.join(BASE, 'library', 'covers')
BACKUP = os.path.join(BASE, 'library', 'sheets')
OUTDIR = os.path.join(BASE, 'dist')


def sheets_of_album(cat, name, category=None):
    """按曲集名取乐曲 uuid。同名曲集可能有多本（不同分类下），
    给 category 时只在该分类下查找，否则把所有同名的合并。"""
    out = []
    for c in cat['categories']:
        if category and c.get('name') != category:
            continue
        for t in c['tags']:
            for a in t['albums']:
                if a.get('name') == name:
                    for s in a['sheets']:
                        out.append(s[0])
    return list(dict.fromkeys(out))


def sheets_of_category(cat, cname):
    out = []
    for c in cat['categories']:
        if c['name'] != cname:
            continue
        for t in c['tags']:
            for a in t['albums']:
                for s in a['sheets']:
                    out.append(s[0])
    return list(dict.fromkeys(out))


def all_sheets(cat):
    out = []
    for c in cat['categories']:
        for t in c['tags']:
            for a in t['albums']:
                for s in a['sheets']:
                    out.append(s[0])
    return list(dict.fromkeys(out))


def cover_uuids(cat):
    out = set()
    for c in cat['categories']:
        for t in c['tags']:
            for a in t['albums']:
                if a.get('cover'):
                    out.add(a['cover'])
    return sorted(out)


def safe_slug(name):
    """把分类/曲集名收敛成文件名片段；空结果回退到 item，过长截到 48 字符。"""
    slug = re.sub(r'[^\w\u4e00-\u9fff]+', '-', name.strip(), flags=re.UNICODE)
    return slug[:48].strip('-_') or 'item'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--top', type=int, default=0, help='只打前 N 首乐谱（目录仍全量）')
    ap.add_argument('--category', help='只打该分类的乐谱')
    ap.add_argument('--album', help='只打该曲集的乐谱')
    ap.add_argument('--out', help='输出文件名（默认自动带日期）')
    ap.add_argument('--no-catalog', action='store_true', help='不打 catalog.json（调试用）')
    ap.add_argument('--no-covers', action='store_true', help='不打封面')
    ap.add_argument('--dry-run', action='store_true')
    ap.add_argument('--allow-missing', action='store_true',
                    help='缺本地谱包时仍出包（损坏/为空/无 enc 仍拒绝）')
    args = ap.parse_args()

    cat = json.load(open(CATALOG, encoding='utf-8'))
    if args.album:
        uu = sheets_of_album(cat, args.album, args.category or None)
    elif args.category:
        uu = sheets_of_category(cat, args.category)
    else:
        uu = all_sheets(cat)
    if args.top:
        uu = uu[:args.top]
    if not uu:
        raise SystemExit('筛选结果为空：category=%s / album=%s，检查名称是否正确'
                         % (args.category or '-', args.album or '-'))

    covers = [] if args.no_covers else cover_uuids(cat)
    # 统计体积；缺失、损坏、为空或缺少 sheet.xml.enc 都先记下来，不带着残缺出包。
    total = 0
    missing = []
    unreadable = []
    for u in uu:
        zp = os.path.join(BACKUP, u + '.zip')
        if not os.path.exists(zp):
            missing.append(u)
            continue
        try:
            with zipfile.ZipFile(zp) as z:
                for i in z.infolist():
                    if i.filename.endswith('sheet.xml.enc') and i.file_size > 0:
                        total += i.file_size
                        break
                else:
                    unreadable.append(u)
        except Exception:
            unreadable.append(u)
    cov_size = 0
    for cu in covers:
        p = os.path.join(COVERS, cu + '.jpg')
        if os.path.exists(p):
            cov_size += os.path.getsize(p)
    catalog_bytes = 0 if args.no_catalog else os.path.getsize(CATALOG)

    print('乐谱 %d 首（缺失 %d / 无有效 enc %d）%.2f GB'
          % (len(uu), len(missing), len(unreadable), total / 1073741824))
    print('封面 %d 张 %.1f MB' % (len(covers), cov_size / 1048576))
    print('目录 %s' % ('跳过' if args.no_catalog else '%.1f MB' % (catalog_bytes / 1048576)))
    print('合计 %.2f GB' % ((total + cov_size + catalog_bytes) / 1073741824))
    if missing:
        print('!! 缺 %d 个本地谱包：%s%s'
              % (len(missing), ', '.join(missing[:10]), '…' if len(missing) > 10 else ''))
    if unreadable:
        print('!! %d 个本地谱包没有有效的 sheet.xml.enc：%s%s'
              % (len(unreadable), ', '.join(unreadable[:10]),
                 '…' if len(unreadable) > 10 else ''))
    if unreadable:
        raise SystemExit('本地谱包损坏、为空或缺少 sheet.xml.enc，未出包；修复后重跑')
    if missing and not args.allow_missing:
        raise SystemExit('缺本地谱包，未出包；补齐后重跑，或显式加 --allow-missing')
    if args.dry_run:
        return

    os.makedirs(OUTDIR, exist_ok=True)
    if args.out:
        out = args.out
    else:
        import datetime
        tag = datetime.date.today().strftime('%Y%m%d')
        # 逐个筛选条件累加后缀：组合使用时文件名要能看出「打了哪一类、多少首」，
        # 否则 --category X 与 --category X --top 500 会生成同一个文件名互相覆盖。
        parts = []
        if args.category:
            parts.append(safe_slug(args.category))
        if args.album:
            parts.append('album-' + safe_slug(args.album))
        if args.top:
            parts.append('top%d' % args.top)
        suffix = ''.join('-' + x for x in parts)
        out = os.path.join(OUTDIR, 'magicpiano-library-%s%s.zip' % (tag, suffix))

    t0 = time.time()
    n = 0
    done = 0
    read_failed = []
    tmp_out = out + '.part'
    try:
        with zipfile.ZipFile(tmp_out, 'w', zipfile.ZIP_STORED, allowZip64=True) as z:
            if not args.no_catalog:
                z.write(CATALOG, 'catalog.json', zipfile.ZIP_STORED)
                n += 1
            for cu in covers:
                p = os.path.join(COVERS, cu + '.jpg')
                if os.path.exists(p):
                    z.write(p, 'covers/%s.jpg' % cu, zipfile.ZIP_STORED)
                    n += 1
            for i, u in enumerate(uu, 1):
                zp = os.path.join(BACKUP, u + '.zip')
                if not os.path.exists(zp):
                    continue
                try:
                    with zipfile.ZipFile(zp) as zi:
                        for info in zi.infolist():
                            if info.filename.endswith('sheet.xml.enc') and info.file_size > 0:
                                data = zi.read(info)
                                break
                        else:
                            raise ValueError('包内没有非空的 sheet.xml.enc')
                        if not data:
                            raise ValueError('sheet.xml.enc 读出为空')
                except Exception as e:
                    print('  !! 读取失败 %s: %s' % (u, str(e)[:60]))
                    read_failed.append(u)
                    continue
                zi_info = zipfile.ZipInfo('sheets/%s/sheet.xml.enc' % u)
                zi_info.compress_type = zipfile.ZIP_STORED
                z.writestr(zi_info, data)
                done += 1
                n += 1
                if i % 500 == 0 or i == len(uu):
                    print('  %d/%d  已写入 %.2f GB  用时 %.0f 秒'
                          % (i, len(uu), done and total * done / len(uu) / 1073741824 or 0,
                             time.time() - t0), flush=True)
        if read_failed:
            raise SystemExit('有 %d 个谱包读取失败，未出包：%s%s'
                             % (len(read_failed), ', '.join(read_failed[:10]),
                                '…' if len(read_failed) > 10 else ''))
        os.replace(tmp_out, out)
    finally:
        if os.path.exists(tmp_out):
            os.remove(tmp_out)

    # 全量包只保留无筛选后缀的正式包；筛选包和自定义 --out 不删，便于与全量包并存。
    if not args.out and not suffix:
        for name in os.listdir(OUTDIR):
            if not (re.fullmatch(r'magicpiano-library-\d{8}\.zip', name)
                    or name.startswith('magicpiano-res-')):
                continue
            old = os.path.join(OUTDIR, name)
            if os.path.abspath(old) != os.path.abspath(out):
                os.remove(old)
                print('  删除旧曲库包：%s' % old)

    size = os.path.getsize(out)
    print('\n完成：%s' % out)
    print('  %d 个条目，%.2f GB，耗时 %.1f 分钟'
          % (n, size / 1073741824, (time.time() - t0) / 60))


if __name__ == '__main__':
    main()
