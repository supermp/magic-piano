# -*- coding: utf-8 -*-
"""按 catalog.json 补齐曲集封面到 library/covers/<cover_uuid>.jpg。

每次目录同步后运行：输入 library/catalog.json，已存在的封面跳过，只下载新增曲集封面到 library/covers/。

用法：
    python tools/fetch_covers.py            # 补齐所有缺失的封面
    python tools/fetch_covers.py --dry-run  # 只报告缺哪些
    python tools/fetch_covers.py --only <cover_uuid> [...]

规格（与现有 774 张保持一致）：
    URL   https://qin-file.lianqinba.com/<cover_uuid>?imageView2/0/w/240
    落地  高 216px（正方形 → 216x216；竖图按比例缩），JPEG q82，约 12KB
"""
import argparse
import io
import json
import os
import sys
import time
import urllib.request

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CATALOG = os.path.join(BASE, 'library', 'catalog.json')
COVERS = os.path.join(BASE, 'library', 'covers')
CDN = 'https://qin-file.lianqinba.com/%s?imageView2/0/w/240'
UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/120.0 Safari/537.36')
TARGET = (216, 216)   # thumbnail 盒子：正方形图落到 216x216，竖图按高度 216 等比缩


def all_covers():
    d = json.load(open(CATALOG, encoding='utf-8'))
    out = []
    for c in d['categories']:
        for t in c['tags']:
            for a in t['albums']:
                cu = a.get('cover')
                if cu:
                    out.append((cu, a.get('name', ''), c['name']))
    return out


def fetch(cu, retries=3):
    url = CDN % cu
    for i in range(retries):
        try:
            req = urllib.request.Request(url, headers={'User-Agent': UA})
            with urllib.request.urlopen(req, timeout=30) as r:
                return r.read()
        except Exception as e:
            if i == retries - 1:
                print('  !! 下载失败 %s: %s' % (cu, str(e)[:80]))
                return None
            time.sleep(1.5 * (i + 1))
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--dry-run', action='store_true')
    ap.add_argument('--only', nargs='*', help='只下这些 cover_uuid')
    args = ap.parse_args()

    os.makedirs(COVERS, exist_ok=True)
    items = all_covers()
    if args.only:
        want = set(args.only)
        items = [x for x in items if x[0] in want]

    seen = set()
    todo = []
    for cu, name, cat in items:
        if cu in seen:
            continue
        seen.add(cu)
        p = os.path.join(COVERS, cu + '.jpg')
        if not os.path.exists(p) or os.path.getsize(p) == 0:
            todo.append((cu, name, cat))

    print('目录引用封面 %d 张（去重），缺失 %d 张' % (len(seen), len(todo)))
    for cu, name, cat in todo[:20]:
        print('   缺 %s  %s / %s' % (cu, cat, name))
    if args.dry_run or not todo:
        return

    try:
        from PIL import Image
    except ImportError:
        raise SystemExit('需要 Pillow：pip install pillow')

    ok = fail = 0
    for i, (cu, name, cat) in enumerate(todo, 1):
        data = fetch(cu)
        if not data:
            fail += 1
            continue
        try:
            im = Image.open(io.BytesIO(data))
            im = im.convert('RGB')
            im.thumbnail(TARGET, Image.LANCZOS)
            im.save(os.path.join(COVERS, cu + '.jpg'), 'JPEG', quality=82, optimize=True)
            ok += 1
            print('  [%d/%d] %s  %s → %s (%d B)'
                  % (i, len(todo), cu[:8], name[:24], im.size,
                     os.path.getsize(os.path.join(COVERS, cu + '.jpg'))))
        except Exception as e:
            print('  !! 处理失败 %s: %s' % (cu, str(e)[:80]))
            fail += 1
    print('\n完成：下载 %d 张，失败 %d 张' % (ok, fail))
    if fail:
        sys.exit(1)


if __name__ == '__main__':
    main()
