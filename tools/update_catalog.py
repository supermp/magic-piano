# -*- coding: utf-8 -*-
"""全量更新「曲库目录」，并报告最近有哪些乐曲/曲集更新过。

数据源（客户端接口，抓包整理）：
    GET  library/categories?instrument=piano&area=<area>
    GET  albums_v3/tags/<分类uuid>
    GET  albums_v3/albums/<标签uuid>?page&page_size=100
    GET  albums_v3/sheets/<曲集uuid>          ← 每首乐曲带 composers_zh / page_in_book / mtime
鉴权头 x-access-token: <JWT>，令牌只读项目根 local.env 的 MAGIC_PIANO_TOKEN。
App 内没有令牌；过期后需在本机配置中更新。

任何一页接口失败、或分页累计数与 total 不符，都会中止整次抓取：不写快照、不写差异、更不会覆盖目录；失败被折叠成空列表，等于把整本曲集从目录里抹掉。

用法：
    python tools/update_catalog.py              # 抓全量 + 对比 + 报告（不覆盖目录）
    python tools/update_catalog.py --write      # 抓全量并直接用本次结果覆盖 library/catalog.json
    # 已跑过默认模式的话，直接用 to_v2(cache/catalog/catalog_full.json) 覆盖目录即可，
    # 不必再抓一遍；曲库数据不在 APK 内，不需要重打 APK。

产物：
    cache/catalog/catalog_full.json      全量原始抓取（含 mtime/ctime/下载量等，供追溯）
    cache/catalog/catalog_diff.json      与现有 catalog.json 的差异明细
"""
import io
import json
import os
import sys
import time
import urllib.error
import urllib.request

TOOLS = os.path.dirname(os.path.abspath(__file__))
BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, TOOLS)
from local_config import get_local
API = 'https://api.lianqinba.com/magic_piano/v1'
ARGS = 'instrument=piano&area=38a08f66-f504-416f-9a24-bb058b85f3c0'
# 分页大小；接口不一定遵守。末页判断不能只看本页条数，还要靠“没有新增曲集”兜底。
PAGE_SIZE = 100
CACHE_DIR = os.path.join(BASE, 'cache', 'catalog')
CATALOG = os.path.join(BASE, 'library', 'catalog.json')


def token():
    """抓取脚本的服务端令牌只读项目根 local.env；离线 App 不使用它。"""
    t = get_local('MAGIC_PIANO_TOKEN')
    if t:
        return t
    raise SystemExit('拿不到令牌：把 MAGIC_PIANO_TOKEN 写进 local.env'
                     '（模板见 local.env.example）')


TOKEN = None


def auth_token():
    """第一次真正联网时才读令牌：`from tools.update_catalog import to_v2` 的离线用法不触发。"""
    global TOKEN
    if TOKEN is None:
        TOKEN = token()
    return TOKEN


def get(path, extra=ARGS, retry=3):
    url = '%s/%s?%s' % (API, path, extra)
    req = urllib.request.Request(url, headers={'x-access-token': auth_token(),
                                               'Accept': 'application/json'})
    for attempt in range(retry + 1):
        try:
            with urllib.request.urlopen(req, timeout=40) as f:
                return json.loads(f.read().decode('utf-8'))
        except urllib.error.HTTPError as e:
            body = e.read().decode('utf-8', 'replace')
            print('  !! HTTP %d %s: %s' % (e.code, path, body[:120]), file=sys.stderr)
            return None
        except Exception as e:
            if attempt == retry:
                print('  !! %s %s' % (e, path), file=sys.stderr)
                return None
            time.sleep(1.5 * (attempt + 1))
    return None


def must_get(path, extra=ARGS, what=''):
    """取一页数据；拿不到就中止整次抓取。

    接口失败与“这本曲集确实没有乐曲”是两回事：失败若按空列表处理，`--write` 会把整本曲集从目录里删掉，且看起来像一次正常更新。
    """
    r = get(path, extra)
    if r is None:
        raise SystemExit('抓取不完整，已中止（未写任何目录）：%s（%s）'
                         % (what or path, path))
    return r


def zh(ml, fallback=''):
    if isinstance(ml, dict):
        return ml.get('zh') or ml.get('base') or ml.get('en') or fallback
    return ml or fallback


def crawl():
    os.makedirs(CACHE_DIR, exist_ok=True)
    cats = get('library/categories')
    if not cats:
        raise SystemExit('拿不到分类（令牌失效？），退出')
    catalog = {'fetched_at': time.strftime('%F %T'), 'categories': []}
    for c in cats.get('category_list') or []:
        cu = c['uuid']
        cat = {'uuid': cu, 'name': zh(c.get('name_ML')), 'tags': []}
        print('[分类] %s' % cat['name'], flush=True)
        tg = must_get('albums_v3/tags/%s' % cu, what='分类「%s」的标签' % cat['name'])
        for t in tg.get('all_tags') or []:
            tu = t['uuid']
            tag = {'uuid': tu, 'name': zh(t.get('name_ML'), t.get('name', '')), 'albums': []}
            page = 1
            seen = set()
            expected_total = None
            while True:
                ab = must_get('albums_v3/albums/%s' % tu,
                              '%s&page=%d&page_size=%d' % (ARGS, page, PAGE_SIZE),
                              what='标签「%s」第 %d 页曲集' % (tag['name'], page))
                albums = ab.get('albums') or []
                total = ab.get('total')
                if total is not None:
                    if expected_total is None:
                        expected_total = int(total)
                    elif int(total) != expected_total:
                        raise SystemExit('分页 total 变化（%s）：%s -> %s'
                                         % (tag['name'], expected_total, total))
                added = 0
                for a in albums:
                    if a['uuid'] in seen:
                        continue
                    seen.add(a['uuid'])
                    d = a.get('data') or {}
                    tag['albums'].append({
                        'uuid': a['uuid'],
                        'name': zh(d.get('name_ML'), d.get('name', '')),
                        'name_zh': d.get('name_zh', ''),
                        'cover_uuid': d.get('cover_uuid') or d.get('cover_uuid_zh'),
                        'publisher': d.get('publisher_zh', ''),
                        'download_count': a.get('download_count', 0),
                        'mtime': a.get('mtime', ''),
                        'ctime': a.get('ctime', ''),
                    })
                    added += 1
                # 空页，或服务端忽略 page 并重复返回同一页：没有新曲集就结束
                if added == 0:
                    break
                if expected_total is not None:
                    if len(tag['albums']) >= expected_total:
                        break
                elif len(albums) < PAGE_SIZE:
                    # 接口没给 total 时，只能靠"本页没满"判断末页
                    break
                page += 1
                time.sleep(0.25)
            if expected_total is not None and len(tag['albums']) != expected_total:
                raise SystemExit('分页不完整（%s）：预期 %s 本，实际 %d 本'
                                 % (tag['name'], expected_total, len(tag['albums'])))
            print('   [标签] %-14s %d 本' % (tag['name'], len(tag['albums'])), flush=True)
            cat['tags'].append(tag)
            time.sleep(0.15)
        catalog['categories'].append(cat)

    # 每本曲集下的乐曲（ composers_zh / page_in_book / mtime 都在这里）
    n = 0
    for cat in catalog['categories']:
        for tag in cat['tags']:
            for alb in tag['albums']:
                sh = must_get('albums_v3/sheets/%s' % alb['uuid'],
                              what='曲集「%s」（%s）的乐曲' % (alb['name'], alb['uuid']))
                sheets = sh.get('sheets') or []
                alb['sheets'] = [{
                    'uuid': s['uuid'],
                    'name': zh((s.get('data') or {}).get('name_ML'),
                               (s.get('data') or {}).get('name', '')),
                    # 作曲家/编曲在**乐曲对象顶层**（composers_zh），不在 data 里：
                    # 从 data 取会整库作曲家全空、曲集页第二行消失
                    'composer': s.get('composers_zh') or s.get('composers_base') or '',
                    'page': (s.get('data') or {}).get('page_in_book', '') or '',
                    'mtime': s.get('mtime', ''),
                    'ctime': s.get('ctime', ''),
                } for s in sheets]
                n += len(sheets)
                time.sleep(0.12)
    print('乐曲接口抓完：%d 首' % n, flush=True)
    return catalog


def to_v2(c):
    """压成 App 内置格式：乐曲用 [uuid, name, composer, page, mtime] 五元数组。"""
    out = {'v': 2, 'categories': []}
    for cat in c['categories']:
        oc = {'uuid': cat['uuid'], 'name': cat['name'], 'tags': []}
        for tag in cat['tags']:
            ot = {'uuid': tag['uuid'], 'name': tag['name'], 'albums': []}
            for a in tag['albums']:
                ot['albums'].append({
                    'uuid': a['uuid'],
                    'name': a.get('name_zh') or a.get('name') or '',
                    'cover': a.get('cover_uuid'),
                    'publisher': a.get('publisher') or '',
                    'mtime': a.get('mtime', ''),
                    'sheets': [[s['uuid'], s['name'], s.get('composer', ''),
                                s.get('page', ''), s.get('mtime', '')]
                               for s in (a.get('sheets') or [])],
                })
            oc['tags'].append(ot)
        out['categories'].append(oc)
    return out


def index_v2(d):
    """把 catalog.json 摊平：albums[uuid]=..., sheets[uuid]=(专辑, mtime, name)"""
    albums, sheets = {}, {}
    for c in d['categories']:
        for t in c['tags']:
            for a in t['albums']:
                albums[a['uuid']] = a
                for s in a['sheets']:
                    sheets[s[0]] = (a['uuid'], s[4] if len(s) > 4 else '', s[1])
    return albums, sheets


def main():
    t0 = time.time()
    raw = crawl()
    raw_p = os.path.join(CACHE_DIR, 'catalog_full.json')
    with io.open(raw_p, 'w', encoding='utf-8') as f:
        json.dump(raw, f, ensure_ascii=False, indent=1)
    print('原始抓取已写 %s' % raw_p)

    new = to_v2(raw)
    new_albums, new_sheets = index_v2(new)
    print('新目录：曲集 %d / 乐曲 %d' % (len(new_albums), len(new_sheets)))

    diff = {'generated_at': time.strftime('%F %T')}
    if os.path.exists(CATALOG):
        old = json.load(open(CATALOG, encoding='utf-8'))
        old_albums, old_sheets = index_v2(old)
        print('旧目录：曲集 %d / 乐曲 %d' % (len(old_albums), len(old_sheets)))

        add_a = sorted(set(new_albums) - set(old_albums))
        del_a = sorted(set(old_albums) - set(new_albums))
        add_s = sorted(set(new_sheets) - set(old_sheets))
        del_s = sorted(set(old_sheets) - set(new_sheets))
        diff.update({
            'albums_added': [{'uuid': u, 'name': new_albums[u]['name'],
                              'mtime': new_albums[u].get('mtime', '')} for u in add_a],
            'albums_removed': [{'uuid': u, 'name': old_albums[u]['name']} for u in del_a],
            'sheets_added': [{'uuid': u, 'name': new_sheets[u][2],
                              'album': new_sheets[u][0]} for u in add_s],
            'sheets_removed': [{'uuid': u, 'name': old_sheets[u][2],
                                'album': old_sheets[u][0]} for u in del_s],
        })
        # mtime 变化的（内容有更新的）
        chg = []
        for u in set(new_albums) & set(old_albums):
            if (new_albums[u].get('mtime') or '') != (old_albums[u].get('mtime') or ''):
                chg.append({'uuid': u, 'name': new_albums[u]['name'],
                            'old': old_albums[u].get('mtime'), 'new': new_albums[u].get('mtime')})
        diff['albums_mtime_changed'] = sorted(chg, key=lambda x: x['new'] or '', reverse=True)
        schg = []
        for u in set(new_sheets) & set(old_sheets):
            if (new_sheets[u][1] or '') != (old_sheets[u][1] or ''):
                schg.append({'uuid': u, 'name': new_sheets[u][2],
                             'album': new_sheets[u][0],
                             'old': old_sheets[u][1], 'new': new_sheets[u][1]})
        diff['sheets_mtime_changed'] = sorted(schg, key=lambda x: x['new'] or '', reverse=True)

        print('\n===== 与现有 catalog.json 对比 =====')
        print('新增曲集 %d / 减少 %d ；新增乐曲 %d / 减少 %d'
              % (len(add_a), len(del_a), len(add_s), len(del_s)))
        print('曲集 mtime 变化 %d ；乐曲 mtime 变化 %d' % (len(chg), len(schg)))
        for u in add_a[:15]:
            print('  +曲集 %s  %s' % (new_albums[u]['name'], new_albums[u].get('mtime', '')))
        for it in diff['sheets_mtime_changed'][:15]:
            print('  ~乐曲 %s  %s -> %s' % (it['name'], (it['old'] or '')[:10],
                                            (it['new'] or '')[:10]))

        # 最近更新
        def newest(items, key):
            vals = sorted((x.get(key) or '' for x in items), reverse=True)
            return vals[:3]
        print('\n===== 最近更新 =====')
        print('乐曲 mtime 最新三个：', newest(new_albums and list(new_sheets.values()) and
              [{'mtime': v[1]} for v in new_sheets.values()], 'mtime'))
        print('曲集 mtime 最新三个：',
              newest([{'mtime': a.get('mtime', '')} for a in new_albums.values()], 'mtime'))
        for it in diff['sheets_mtime_changed'][:5]:
            print('  最近有内容更新的乐曲：%s（%s）' % (it['name'], (it['new'] or '')[:10]))
        for it in diff['albums_mtime_changed'][:5]:
            print('  最近有内容更新的曲集：%s（%s）' % (it['name'], (it['new'] or '')[:10]))

    diff_p = os.path.join(CACHE_DIR, 'catalog_diff.json')
    with io.open(diff_p, 'w', encoding='utf-8') as f:
        json.dump(diff, f, ensure_ascii=False, indent=1)
    print('\n差异已写 %s' % diff_p)

    if '--write' in sys.argv:
        with io.open(CATALOG, 'w', encoding='utf-8') as f:
            json.dump(new, f, ensure_ascii=False, separators=(',', ':'))
        print('已覆盖 %s（%.2f MB）' % (CATALOG, os.path.getsize(CATALOG) / 1048576.0))
    else:
        print('（未覆盖 library/catalog.json；加 --write 才写入）')

    print('\n耗时 %.1f 分钟' % ((time.time() - t0) / 60.0))


if __name__ == '__main__':
    main()
