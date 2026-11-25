#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""构建期校验渲染器 assets 的语法与 SheetView 的补丁锚点。

先用 node --check 校验 sheet_show.js / jsbridge.js 的语法（没装 node 就跳过），
再校验 buildHtml 依赖的锚点。语法错或锚点缺失都会让播放页白屏（异常栈在 logcat），
构建前先跑本脚本，任一失败直接让 build.sh 失败。

⚠ PATCHES 与 `SheetView.buildHtml()` 里的匹配串是**同一份事实的两处记录**：改 Java 那边的
补丁串，必须同步改本清单；否则校验查旧锚点照样通过，直到启动时才以空白页 + 异常暴露。

用法：
    python tools/check_sheet_patches.py
"""
from pathlib import Path
import shutil
import subprocess
import sys

BASE = Path(__file__).resolve().parents[1]
ASSETS = BASE / 'app' / 'assets' / 'sheetShow'
REQUIRED = ('sheet_show.js', 'jsbridge.js', 'sheet_show.html')

# (名称, 所在文件, 锚点, 至少出现次数)
PATCHES = [
    ('光标重建',
     'js',
     'x.cursorPreview||((x.cursorPreview=document.createElementNS(i,"g"))', 1),
    ('首行 top 滚动',
     'js',
     '(0,u.scrollToScreenPosition)(document.body,l.y+e,s,i,a,t)', 1),
    ('moveCursor2 wb_no_scroll 起点',
     'js',
     'try{q.GlobalState.shareInstance.lastCursors=[e],', 1),
    ('moveCursor2 wb_no_scroll 末尾',
     'js',
     '(0,ae.moveCursor)(e)}catch(e){B("Error - Move Cursor: "+e)}', 1),
    ('自动滚动冻结',
     'js',
     '!o&&u.GlobalState.shareInstance.lastTouchActiveDate'
     '&&+new Date-u.GlobalState.shareInstance.lastTouchActiveDate'
     '<1e3*u.GlobalState.shareInstance.doNotScrollAfterTouchActiveSecond', 2),
    ('渲染调度 setTimeout',
     'js',
     'O="undefined"!=typeof MessageChannel?new MessageChannel:null,I=[]', 1),
    ('分批绘制回调',
     'js',
     'F.MusicScoreController.shareInstance.setFinishDrawHandler(function(){'
     'P.callHandler("onSheetOverallLoaded"', 1),
    ('速度记号上移',
     'js',
     'h=e.getYForTopText(1)+this.shift_y', 1),
    ('结果符号红叉',
     'js',
     'drawPitchType=function(e,t,n,r,o){', 1),
    ('符头色板全黑',
     'js',
     '{"high":"#EA5353","low":"#35CEB3","resultFrameColor":"#F25B5B",'
     '"miss":"#FFBE20","extra":"#EA5353"}', 1),
    ('HTML <head> 注入',
     'html',
     '<head>', 1),
    ('HTML 内联渲染器脚本',
     'html',
     '<script type="text/javascript" src="sheet_show.js"></script>', 1),
    ('HTML </head> 样式注入',
     'html',
     '</head>', 1),
]


def check_js_syntax():
    """用 node --check 校验渲染器脚本语法；没装 node 就跳过并提示。

    语法错的 JS 会被 buildHtml 原样拼进页面，WebView 解析失败即白屏，
    而锚点校验只看文本匹配、发现不了语法问题。
    """
    node = shutil.which('node')
    if not node:
        print('（未找到 node，跳过 JS 语法检查；建议装 node 后再构建）')
        return []
    bad = []
    for name in ('sheet_show.js', 'jsbridge.js'):
        p = ASSETS / name
        if not p.is_file():
            continue            # 缺文件由 main 的 REQUIRED 检查报出
        r = subprocess.run([node, '--check', str(p)],
                           capture_output=True, text=True,
                           encoding='utf-8', errors='replace')
        if r.returncode != 0:
            detail = ' / '.join((r.stderr or '').strip().splitlines()[:3])
            bad.append('%s：%s' % (name, detail))
    return bad


def main() -> int:
    missing_assets = [n for n in REQUIRED if not (ASSETS / n).is_file()]
    if missing_assets:
        print('!!! 缺少渲染器 asset：')
        for n in missing_assets:
            p = ASSETS / n
            try:
                shown = p.relative_to(BASE).as_posix()
            except ValueError:
                shown = str(p)
            print('  - ' + shown)
        return 1

    syntax_bad = check_js_syntax()
    if syntax_bad:
        print('!!! 渲染器脚本语法错误：')
        for item in syntax_bad:
            print('  - ' + item)
        return 1

    texts = {}
    for kind, name in (('js', 'sheet_show.js'), ('html', 'sheet_show.html')):
        texts[kind] = (ASSETS / name).read_text(encoding='utf-8', errors='replace')

    missing = []
    for label, kind, anchor, need in PATCHES:
        count = texts[kind].count(anchor)
        if count < need:
            missing.append('%s（需要 %d 处，实到 %d）：%s'
                           % (label, need, count, anchor[:80]))

    if missing:
        print('!!! 渲染器补丁锚点缺失：')
        for item in missing:
            print('  - ' + item)
        print('构建中止：先核对 app/assets/sheetShow 的结构与 SheetView 的原位替换。')
        return 1

    print('渲染器语法与补丁锚点校验通过（%d 项）' % len(PATCHES))
    return 0


if __name__ == '__main__':
    sys.exit(main())
