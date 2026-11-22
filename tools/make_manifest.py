# -*- coding: utf-8 -*-
"""生成/增量更新「曲包版本清单」library/library_manifest.json。

清单是设备侧版本判断的唯一依据：每台设备把它带一份，同步脚本读设备上的那份
与本机这份做精确差异，就能算出「这台该补哪些、该替换哪些」，不再依赖
cache/catalog/catalog_diff.json 的历史基线假设。

结构（短键是为了把 1.8 万条的 JSON 控制在 2~3MB）：
  {
    "v": 1, "generated_at": "...",
    "catalog_sha256": "...", "catalog_bytes": 2283076, "sheet_count": 18052,
    "sheets": {
      "<uuid>": {"m": "<服务端 mtime>", "s": <enc 字节数>,
                 "h": "<enc sha256>", "z": "<zip size>:<zip mtime>"}
    }
  }

增量生成：已存在清单且该 uuid 的 `z`（本地 zip 的 size+mtime）与 `m` 都没变，
就直接沿用旧的 `h`/`s` —— 不重复读 4.4GB 谱包。只有新增、或本地 zip 被
重新下载过（mtime 变化）的曲目才重新算 sha256。

用法：
  python tools/make_manifest.py            # 增量
  python tools/make_manifest.py --rehash   # 忽略缓存，全量重算
"""
import argparse
import hashlib
import io
import json
import os
import time
import zipfile

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CATALOG = os.path.join(BASE, 'library', 'catalog.json')
SHEETS = os.path.join(BASE, 'library', 'sheets')
OUT = os.path.join(BASE, 'library', 'library_manifest.json')


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def read_catalog():
    with io.open(CATALOG, 'r', encoding='utf-8') as f:
        c = json.load(f)
    idx = {}
    for cat in c['categories']:
        for tag in cat['tags']:
            for album in tag['albums']:
                for s in album['sheets']:
                    idx[s[0]] = s[4] if len(s) > 4 else ''
    with open(CATALOG, 'rb') as f:
        ch = sha256(f.read())
    return idx, ch, os.path.getsize(CATALOG)


def enc_bytes(uuid):
    """从谱包 zip 里取出 sheet.xml.enc 的字节，并给出 zip 的缓存键。"""
    p = os.path.join(SHEETS, uuid + '.zip')
    with zipfile.ZipFile(p) as z:
        names = [n for n in z.namelist() if n.endswith('sheet.xml.enc')]
        if not names:
            raise ValueError('谱包里没有 sheet.xml.enc')
        data = z.read(names[0])
    key = '%d:%d' % (os.path.getsize(p), int(os.path.getmtime(p)))
    return data, key


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--rehash', action='store_true', help='忽略缓存全量重算')
    args = ap.parse_args()

    idx, catalog_sha, catalog_bytes = read_catalog()
    old = {}
    if not args.rehash and os.path.isfile(OUT):
        try:
            with io.open(OUT, 'r', encoding='utf-8') as f:
                old = json.load(f).get('sheets', {})
        except Exception as e:
            print('旧清单读不了，按全量重算：%s' % e)

    out = {
        'v': 1,
        'generated_at': time.strftime('%Y-%m-%dT%H:%M:%S'),
        'catalog_sha256': catalog_sha,
        'catalog_bytes': catalog_bytes,
        'sheet_count': len(idx),
        'sheets': {},
    }
    reused = rebuilt = 0
    t0 = time.time()
    for i, uuid in enumerate(sorted(idx), 1):
        mtime = idx[uuid]
        zp = os.path.join(SHEETS, uuid + '.zip')
        if not os.path.isfile(zp):
            print('  !! 缺谱包 %s，跳过（会被同步脚本当作待补）' % uuid)
            continue
        zkey = '%d:%d' % (os.path.getsize(zp), int(os.path.getmtime(zp)))
        cached = old.get(uuid)
        if cached and cached.get('z') == zkey and cached.get('m') == mtime \
                and cached.get('h') and cached.get('s'):
            out['sheets'][uuid] = {
                'm': mtime, 's': cached['s'], 'h': cached['h'], 'z': zkey,
            }
            reused += 1
        else:
            data, zkey2 = enc_bytes(uuid)
            out['sheets'][uuid] = {
                'm': mtime, 's': len(data), 'h': sha256(data), 'z': zkey2,
            }
            rebuilt += 1
        if i % 4000 == 0:
            print('  %d/%d  复用 %d / 重算 %d  用时 %.0fs'
                  % (i, len(idx), reused, rebuilt, time.time() - t0), flush=True)

    with io.open(OUT, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(out, f, ensure_ascii=False, separators=(',', ':'))
    print('清单已写 %s（%.2f MB），乐曲 %d 首，复用 %d / 重算 %d，耗时 %.1fs'
          % (OUT, os.path.getsize(OUT) / 1048576.0, len(out['sheets']),
             reused, rebuilt, time.time() - t0))
    print('catalog sha256 = %s' % catalog_sha)


if __name__ == '__main__':
    main()
