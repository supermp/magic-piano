#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 classes.dex / lib / assets 追加进 aapt2 产出的 base.apk。

为什么不用 `jar uf`：
    Windows 下的 jar.exe 会按平台分隔符把条目名写成
    "assets/algo_assets\\ALGConf.json"，而 Android 的 AssetManager
    只认 "/" —— 结果 AssetManager.list() 返回空、子目录 open() 抛
    FileNotFoundException。Python zipfile 可以显式指定 arcname，
    从根上避免这个坑。

用法： pack.py <apk路径>
"""
import os
import sys
import zipfile

PROJ = os.path.dirname(os.path.abspath(__file__))

# 这些后缀本身就是压缩格式，再 deflate 纯属浪费 CPU
STORE_EXT = ('.jpg', '.jpeg', '.png', '.webp', '.gif', '.mp3', '.m4a',
             '.ogg', '.zip', '.gz', '.br', '.pdf',
             '.ttf', '.otf', '.woff', '.woff2', '.mp4')


# 打包前必须存在的本地资产。缺任何一个，App 起来就是硬故障：
# 引擎初始化失败、曲谱页白屏或字体/图标整片缺失，而且都到运行期才暴露。
REQUIRED_ASSETS = (
    'algo_assets/ALGConf.json',
    'algo_assets/sound',
    'algo_assets/computation',
    'fonts',
    'sheetShow/sheet_show.js',
    'sheetShow/sheet_show.html',
    'sheetShow/jsbridge.js',
    'ui',
)


def check_assets():
    """返回本地资产问题列表；空列表表示可以打包。"""
    bad = []
    for rel in REQUIRED_ASSETS:
        p = os.path.join(PROJ, 'assets', rel)
        if not os.path.exists(p):
            bad.append('缺失 %s' % rel)
        elif os.path.isfile(p) and os.path.getsize(p) == 0:
            bad.append('空文件 %s' % rel)
        elif os.path.isdir(p):
            with os.scandir(p) as it:          # 用完关掉句柄，否则 Windows 上后续删不动
                if not any(True for _ in it):
                    bad.append('空目录 %s' % rel)
    return bad


def decide_compress(arcname: str) -> int:
    return zipfile.ZIP_STORED if arcname.lower().endswith(STORE_EXT) \
        else zipfile.ZIP_DEFLATED


def add_tree(zf: zipfile.ZipFile, srcdir: str, prefix: str,
             skip_prefixes=(), skip_files=()) -> int:
    n = 0
    for dirpath, dirnames, filenames in os.walk(srcdir):
        dirnames.sort()
        for fn in sorted(filenames):
            if fn.endswith('.DS_Store'):
                continue
            full = os.path.join(dirpath, fn)
            rel = os.path.relpath(full, srcdir).replace(os.sep, '/')
            if rel in skip_files:
                continue
            if any(rel.startswith(x) for x in skip_prefixes):
                continue
            arcname = '%s/%s' % (prefix, rel)
            zf.write(full, arcname, decide_compress(arcname))
            n += 1
            if n % 20 == 0:
                print('    %4d  %s' % (n, arcname), flush=True)
    return n


def main() -> int:
    if len(sys.argv) < 2:
        print('用法: pack.py <base.apk>')
        return 1
    apk = sys.argv[1]

    bad = check_assets()
    if bad:
        print('!!! 本地资产不完整，拒绝打包：')
        for item in bad:
            print('  - ' + item)
        print('先补齐 app/assets 下的文件再构建。')
        return 1

    with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as zf:
        print('--- classes.dex ---')
        zf.write(os.path.join(PROJ, 'build', 'classes.dex'), 'classes.dex',
                 zipfile.ZIP_DEFLATED)
        print('    classes.dex')

        print('--- lib/ ---')
        add_tree(zf, os.path.join(PROJ, 'lib'), 'lib')

        print('--- assets/ ---')
        # 曲库数据（目录 + 封面）已经不在 assets/ 里了：它们的源在项目根的 library/，
        # 由 tools/make_bundle.py 打进资源包。这里直接整目录打包。
        add_tree(zf, os.path.join(PROJ, 'assets'), 'assets')

    print('打包完成：%s (%.1f MB)' % (apk, os.path.getsize(apk) / 1048576.0))
    return 0


if __name__ == '__main__':
    sys.exit(main())
