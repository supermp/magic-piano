#!/usr/bin/env python3
"""项目清理检查：每次改完代码跑一次，**只报告不删除**。

用法：
    python tools/clean_check.py

检查项：
  1. 未使用的 Java import
  2. 未被引用的 private 方法（多半是删功能后的残留）
  3. app/assets/ui/images 里没被代码引用的图片
  4. 名字含不可见字符的目录
  5. tmp/ 下临时文件残留
  6. tmp/shots、tmp/namecheck 里的调试产物
  7. cache/seed/ 里的设备同步暂存

退出码：0 = 干净，1 = 有待处理项。
"""
import os
import re
import sys

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(BASE, 'app', 'src')
IMAGES = os.path.join(BASE, 'app', 'assets', 'ui', 'images')
CACHE = os.path.join(BASE, 'cache')
TMP = os.path.join(BASE, 'tmp')

SKIP_DIRS = {'dist', 'reference', 'cache', 'tmp', 'library', '.git',
             '.workbuddy', 'build', 'out', 'android-sdk', 'bundles',
             'pdfs', 'shots'}


def java_files():
    for root, dirs, files in os.walk(SRC):
        for f in files:
            if f.endswith('.java'):
                yield os.path.join(root, f)


def rel(p):
    return os.path.relpath(p, BASE).replace('\\', '/')


def check_unused_imports():
    issues = []
    for p in java_files():
        s = open(p, encoding='utf-8', errors='replace').read()
        lines = s.splitlines()
        body = '\n'.join(l for l in lines if not l.strip().startswith('import '))
        for l in lines:
            m = re.match(r'\s*import\s+(static\s+)?([\w.]+)\s*;', l)
            if not m or m.group(1):
                continue
            simple = m.group(2).split('.')[-1]
            if not re.search(r'\b' + re.escape(simple) + r'\b', body):
                issues.append('%s: 未使用 import %s' % (rel(p), m.group(2)))
    return issues


def check_unused_private():
    files = list(java_files())
    texts = {p: open(p, encoding='utf-8', errors='replace').read() for p in files}
    allsrc = '\n'.join(texts.values())
    found = {}
    for p, s in texts.items():
        for i, line in enumerate(s.splitlines(), 1):
            m = re.match(r'\s*private\s+(?:static\s+)?(?:final\s+)?[\w<>\[\],.\s]+?\s+(\w+)\s*\(', line)
            if not m:
                continue
            name = m.group(1)
            if name in ('if', 'for', 'while', 'switch', 'catch', 'return', 'new'):
                continue
            found.setdefault(name, []).append((p, i))
    issues = []
    for name, locs in found.items():
        total = len(re.findall(r'\b' + re.escape(name) + r'\b', allsrc))
        if total - len(locs) <= 0:
            issues.append('%s:%d: private 方法 %s() 未被引用'
                          % (rel(locs[0][0]), locs[0][1], name))
    return issues


def check_unused_images():
    if not os.path.isdir(IMAGES):
        return []
    used = set()
    for p in java_files():
        s = open(p, encoding='utf-8', errors='replace').read()
        for m in re.finditer(r'"([^"]+\.(?:png|jpg|webp))"', s):
            used.add(m.group(1))
    issues = []
    for f in sorted(os.listdir(IMAGES)):
        if f.lower().endswith(('.png', '.jpg', '.webp')) and f not in used:
            issues.append('app/assets/ui/images/%s: 未被代码引用' % f)
    return issues


def check_weird_dirs():
    issues = []
    for root, dirs, _ in os.walk(BASE):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for d in dirs:
            if any(ord(c) > 126 or ord(c) < 32 for c in d):
                issues.append('%s: 目录名含不可见字符 %r'
                              % (rel(os.path.join(root, d)), d))
    return issues


def check_temp_files():
    issues = []
    if os.path.isdir(TMP):
        for f in sorted(os.listdir(TMP)):
            if f.endswith('.py') or f.endswith('.tmp'):
                issues.append('tmp/%s: 临时文件残留' % f)
    return issues


def check_debug_artifacts():
    """验证用的截图 / logcat 转储 / 解密临时目录 —— 都是可再生的调试产物。"""
    issues = []
    for sub in ('shots', 'namecheck'):
        d = os.path.join(TMP, sub)
        if not os.path.isdir(d):
            continue
        n = size = 0
        for root, _, files in os.walk(d):
            for f in files:
                n += 1
                try:
                    size += os.path.getsize(os.path.join(root, f))
                except OSError:
                    pass
        if n:
            issues.append('tmp/%s: %d 个调试产物 / %.1f MB（截图与日志，可清）'
                          % (sub, n, size / 1048576.0))
    return issues


def check_staging_dirs():
    """deploy/unpack 的解压暂存用完就该清，脚本下次会自动重建。"""
    issues = []
    for sub in ('ext', 'part', 'full'):
        d = os.path.join(CACHE, 'seed', sub)
        if not os.path.isdir(d):
            continue
        n = size = 0
        for root, _, files in os.walk(d):
            for f in files:
                n += 1
                try:
                    size += os.path.getsize(os.path.join(root, f))
                except OSError:
                    pass
        if n:
            issues.append('cache/seed/%s: 铺设暂存 %d 个文件 / %.1f MB（用完可清，脚本会重建）'
                          % (sub, n, size / 1048576.0))
    return issues


def check_build_outputs():
    """app/build 里的 APK 中间产物：base.apk → aligned.apk → MagicPiano.apk(+idsig)，
    每次 build.sh 都会整文件覆盖重建；交付物只在 dist。三者各 130MB，留着白占 ~390MB。"""
    d = os.path.join(BASE, 'app', 'build')
    names, size = [], 0
    for f in ('base.apk', 'aligned.apk', 'MagicPiano.apk', 'MagicPiano.apk.idsig'):
        p = os.path.join(d, f)
        if os.path.isfile(p):
            names.append(f)
            try:
                size += os.path.getsize(p)
            except OSError:
                pass
    if not names:
        return []
    return ['app/build: %s 共 %.1f MB（构建中间产物，交付物在 dist，可清）'
            % (', '.join(names), size / 1048576.0)]


def main():
    checks = [
        ('未使用 import', check_unused_imports),
        ('未被引用的 private 方法', check_unused_private),
        ('未引用的图片', check_unused_images),
        ('异常目录名', check_weird_dirs),
        ('临时文件残留', check_temp_files),
        ('调试产物残留', check_debug_artifacts),
        ('铺设暂存残留', check_staging_dirs),
        ('构建中间产物', check_build_outputs),
    ]
    total = 0
    for title, fn in checks:
        try:
            issues = fn()
        except Exception as e:  # 单项失败不影响其它检查
            print('[跳过] %s: %s' % (title, e))
            continue
        if issues:
            print('\n[%s] %d 项' % (title, len(issues)))
            for i in issues:
                print('  -', i)
            total += len(issues)
        else:
            print('[OK] %s' % title)
    print('\n总计 %d 项%s' % (total, '（人工确认后再删）' if total else '，项目干净'))
    return 0 if total == 0 else 1


if __name__ == '__main__':
    sys.exit(main())
