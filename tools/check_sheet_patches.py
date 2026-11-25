#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""构建期校验 SheetView 对渲染器 assets 的补丁锚点。

SheetView.buildHtml() 用字符串替换给原版渲染器打补丁；锚点缺失时 buildHtml 抛异常，
播放页只剩空白（异常栈在 logcat）。构建前先跑本脚本，锚点缺失直接让 build.sh 失败。

⚠ PATCHES 与 `SheetView.buildHtml()` 里的匹配串是**同一份事实的两处记录**：改 Java 那边的
补丁串，必须同步改本清单；否则校验查旧锚点照样通过，直到启动时才以空白页 + 异常暴露。

用法：
    python tools/check_sheet_patches.py
"""
from pathlib import Path
import sys

BASE = Path(__file__).resolve().parents[1]
ASSETS = BASE / 'app' / 'assets' / 'sheetShow'

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


def main() -> int:
    texts = {}
    for kind, name in (('js', 'sheet_show.js'), ('html', 'sheet_show.html')):
        p = ASSETS / name
        if not p.is_file():
            print('!!! 缺少渲染器 asset：%s' % p.relative_to(BASE))
            return 1
        texts[kind] = p.read_text(encoding='utf-8', errors='replace')

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

    print('渲染器补丁锚点校验通过（%d 项）' % len(PATCHES))
    return 0


if __name__ == '__main__':
    sys.exit(main())
