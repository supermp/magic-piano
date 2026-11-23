# -*- coding: utf-8 -*-
"""把全部乐曲的曲谱包 zip 备份到 library/sheets/。

链路（从原版 App 抓包逆向；离线版 App 已不含此链路）：
    POST https://api.lianqinba.com/magic_piano/v1/qiniu/private_download_url
         header: x-access-token: <JWT>
         body:   {"uuid_array":[...], "type":"album"}
         →      {"url_map": {uuid: "https://qin-album.lianqinba.com/.../<uuid>"}}
    每个 URL 是一个 ZIP：sheet.xml.enc / cover.png / section.json / sheet.json /
                       score_markings.json / sheet_markings.json

要点：
  · uuid_array 支持批量，先批量换 URL（每批 50 个），再并发下载 zip；
  · 已存在的文件直接跳过，可随时中断后重跑（增量备份）；
  · 失败的重试 3 次，仍失败的记进 library/sheets/manifest.json 的 failed_list，最后汇总；
  · 只存 zip 原文件，不解包。

用法：
    python tools/fetch_sheet_zips.py                 # 全量备份到 library/sheets/
    python tools/fetch_sheet_zips.py --workers 12    # 调整并发
"""
import io
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
API = 'https://api.lianqinba.com/magic_piano/v1/qiniu/private_download_url'
CATALOG = os.path.join(BASE, 'library', 'catalog.json')
OUT = os.path.join(BASE, 'library', 'sheets')
BATCH = 50
WORKERS = 8
RETRY = 3

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from update_catalog import auth_token  # noqa: E402  令牌首次联网时才读 local.env

_lock = threading.Lock()
_stats = {'ok': 0, 'skip': 0, 'fail': 0, 'bytes': 0}
_failed = []


def post_urls(uuids):
    body = json.dumps({'uuid_array': uuids, 'type': 'album'}).encode('utf-8')
    req = urllib.request.Request(API, data=body, headers={
        'x-access-token': auth_token(), 'Content-Type': 'application/json'})
    for attempt in range(RETRY + 1):
        try:
            with urllib.request.urlopen(req, timeout=60) as f:
                r = json.loads(f.read().decode('utf-8'))
            return (r or {}).get('url_map') or {}
        except Exception as e:
            if attempt == RETRY:
                print('  !! 换 URL 失败（%d 个）：%s' % (len(uuids), e), file=sys.stderr)
                return {}
            time.sleep(2.0 * (attempt + 1))
    return {}


def download(uuid, url):
    dst = os.path.join(OUT, uuid + '.zip')
    if os.path.exists(dst) and os.path.getsize(dst) > 0:
        with _lock:
            _stats['skip'] += 1
        return
    tmp = dst + '.part'
    for attempt in range(RETRY + 1):
        try:
            req = urllib.request.Request(url)
            with urllib.request.urlopen(req, timeout=120) as f, open(tmp, 'wb') as o:
                while True:
                    b = f.read(1 << 16)
                    if not b:
                        break
                    o.write(b)
            os.replace(tmp, dst)
            sz = os.path.getsize(dst)
            with _lock:
                _stats['ok'] += 1
                _stats['bytes'] += sz
            return
        except Exception as e:
            if attempt == RETRY:
                with _lock:
                    _stats['fail'] += 1
                    _failed.append({'uuid': uuid, 'error': str(e)[:200]})
                if os.path.exists(tmp):
                    os.remove(tmp)
            else:
                time.sleep(1.5 * (attempt + 1))


def main():
    os.makedirs(OUT, exist_ok=True)
    cat = json.load(open(CATALOG, encoding='utf-8'))
    uuids = []
    for c in cat['categories']:
        for t in c['tags']:
            for a in t['albums']:
                for s in a['sheets']:
                    uuids.append(s[0])
    # 去重保序
    seen, all_u = set(), []
    for u in uuids:
        if u not in seen:
            seen.add(u)
            all_u.append(u)
    print('乐曲 %d 首，开始备份到 %s' % (len(all_u), OUT), flush=True)
    t0 = time.time()
    done = 0

    for i in range(0, len(all_u), BATCH):
        batch = all_u[i:i + BATCH]
        url_map = post_urls(batch)
        missing = [u for u in batch if u not in url_map]
        if missing:
            print('  !! 本批 %d 个换不到 URL：%s' % (len(missing), missing[:3]), file=sys.stderr)
            with _lock:
                _stats['fail'] += len(missing)
                _failed.extend({'uuid': u, 'error': 'no url'} for u in missing)

        # 分批起线程，避免一次性开太多
        for j in range(0, len(batch), WORKERS):
            group = batch[j:j + WORKERS]
            ths = []
            for u in group:
                url = url_map.get(u)
                if not url:
                    continue
                th = threading.Thread(target=download, args=(u, url), daemon=True)
                th.start()
                ths.append(th)
            for th in ths:
                th.join()
            done += len(group)
            if done % 500 < WORKERS or done == len(all_u):
                el = time.time() - t0
                rate = done / el if el > 0 else 0
                eta = (len(all_u) - done) / rate / 60 if rate > 0 else 0
                print('  进度 %d/%d  ok=%d skip=%d fail=%d  %.1f MB  预计还剩 %.1f 分钟'
                      % (done, len(all_u), _stats['ok'], _stats['skip'], _stats['fail'],
                         _stats['bytes'] / 1048576.0, eta), flush=True)

    manifest = {
        'generated_at': time.strftime('%F %T'),
        'sheets': len(all_u),
        'downloaded': _stats['ok'],
        'skipped': _stats['skip'],
        'failed': _stats['fail'],
        'total_bytes': _stats['bytes'],
        'failed_list': _failed,
    }
    with io.open(os.path.join(OUT, 'manifest.json'), 'w', encoding='utf-8') as f:
        json.dump(manifest, f, ensure_ascii=False, indent=1)
    print('\n完成：成功 %d / 跳过 %d / 失败 %d，共 %.2f MB，耗时 %.1f 分钟'
          % (_stats['ok'], _stats['skip'], _stats['fail'],
             _stats['bytes'] / 1048576.0, (time.time() - t0) / 60.0), flush=True)
    if _failed:
        print('失败清单见 library/sheets/manifest.json 的 failed_list（可重跑补下）')


if __name__ == '__main__':
    if '--workers' in sys.argv:
        WORKERS = int(sys.argv[sys.argv.index('--workers') + 1])
    main()
