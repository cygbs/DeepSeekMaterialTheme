#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
gen_anchor_index.py —— 从 jadx 反编译产物里生成「原始符号 -> 混淆类/方法」索引。

原理
----
DeepSeek 用 R8 full mode 混淆：类名/方法名全被改写、包结构被抹平（11736 个类落进默认包）、
R8 还会把多个类横向合并成一个类。**但是** Compose 编译器生成的 source information 字符串
是明文留在 dex 里的：

    if (sn2.d()) { sn2.f("com.deepseek.chat.ui.theme.DeepSeekTheme (Theme.kt:28)"); }

于是我们可以：找到字符串 -> 往上找最近的「方法声明行」-> 得到该方法在混淆后的类名与方法名。
这就是本脚本做的事。有了这张表，LSPosed 模块才知道该 hook 谁 / 该替换哪个静态字段。

用法
----
    python3 tools/gen_anchor_index.py                        # 用默认路径
    python3 tools/gen_anchor_index.py --sources X --out Y    # 指定路径
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from collections import Counter, defaultdict

# sn2.f("xxxx (File.kt:12)")  —— 注意 jadx 也可能输出 sn2.f("...") 用单行形式
ANCHOR_RE = re.compile(r'sn2\.f\("([^"]+)"\)')
ANCHOR_STR_RE = re.compile(r"^(?P<symbol>.+?)\s*\((?P<file>[A-Za-z0-9_$.\-]+\.(?:kt|java)):(?P<line>\d+)\)$")

# 方法声明（jadx 里类成员统一 4 空格缩进）
MODIFIERS = r"(?:public|private|protected|static|final|abstract|native|synchronized|default|strictfp)"
METHOD_RE = re.compile(
    r"^    (?:" + MODIFIERS + r"\s+)*[\w.$\[\]<>,? ]+\s+(?P<name>[\w$<>]+)\s*\((?P<params>[^()]*)\)"
    r"(?:\s*throws [\w., $]+)?\s*\{"
)
CTOR_RE = re.compile(
    r"^    (?:" + MODIFIERS + r"\s+)*(?P<name>[\w$]+)\s*\((?P<params>[^()]*)\)\s*\{"
)
CLASS_RE = re.compile(
    r"^(?:(?:public|final|abstract|static)\s+)*(?:class|interface|enum)\s+(?P<name>[\w$]+)"
)
PKG_RE = re.compile(r"^package\s+(?P<pkg>[\w.]+);")
STATIC_FIELD_RE = re.compile(
    r"^    public static (?:final )?(?P<type>[\w.$<>\[\], ?]+)\s+(?P<name>[\w$]+)\s*=\s*(?P<init>.+?);$"
)


BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/")


def clean(line):
    """去掉 jadx 插在声明中间的注释，例如 `public final /* synthetic */ class h61`。"""
    return BLOCK_COMMENT_RE.sub("", line)


def find_enclosing(file_lines, idx):
    """从 idx 行往上找最近的类名 / 方法声明。"""
    cls = None
    method = None
    for i in range(idx, -1, -1):
        line = clean(file_lines[i])
        if method is None:
            m = METHOD_RE.match(line)
            if m:
                # 排除 if/for/while 之类的误匹配
                if m.group("name") not in ("if", "for", "while", "switch", "catch", "return", "synchronized"):
                    method = (m.group("name"), " ".join(m.group("params").split()))
                    continue
            c = CTOR_RE.match(line)
            if c and cls and c.group("name") == cls:
                method = ("<init>", " ".join(c.group("params").split()))
                continue
        if cls is None:
            cm = CLASS_RE.match(line)
            if cm:
                cls = cm.group("name")
        if cls is not None and method is not None:
            break
    return cls, method


def scan_sources(sources_dir):
    anchors = []
    fields_by_class = defaultdict(list)
    java_files = 0
    for root, _dirs, files in os.walk(sources_dir):
        for fn in files:
            if not fn.endswith(".java"):
                continue
            java_files += 1
            path = os.path.join(root, fn)
            try:
                with open(path, "r", encoding="utf-8", errors="replace") as fh:
                    lines = fh.readlines()
            except OSError:
                continue
            # 顺带收集静态字段（用于推断主题类里的 Lazy / CompositionLocal 字段）
            for line in lines:
                fm = STATIC_FIELD_RE.match(clean(line))
                if fm:
                    fields_by_class[fn[:-5]].append(
                        {
                            "name": fm.group("name"),
                            "type": fm.group("type").strip(),
                            "init": fm.group("init").strip(),
                        }
                    )
            for i, line in enumerate(lines):
                if "sn2.f(" not in line:
                    continue
                for m in ANCHOR_RE.finditer(line):
                    raw = m.group(1)
                    symbol = raw
                    src_file = ""
                    src_line = 0
                    sm = ANCHOR_STR_RE.match(raw)
                    if sm:
                        symbol = sm.group("symbol")
                        src_file = sm.group("file")
                        src_line = int(sm.group("line"))
                    cls, method = find_enclosing(lines, i)
                    anchors.append(
                        {
                            "symbol": symbol,
                            "src_file": src_file,
                            "src_line": src_line,
                            "obf_class": cls or "",
                            "obf_method": method[0] if method else "",
                            "obf_params": method[1] if method else "",
                            "sources_file": os.path.relpath(path, sources_dir),
                            "sources_line": i + 1,
                        }
                    )
    return anchors, fields_by_class, java_files


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    proj = os.path.dirname(here)
    ap = argparse.ArgumentParser()
    ap.add_argument("--sources", default=os.path.join(proj, "..", "DeepSeek-2.5.3-jadx", "sources"))
    ap.add_argument("--out", default=os.path.join(proj, "analysis"))
    args = ap.parse_args()

    sources_dir = os.path.abspath(args.sources)
    out_dir = os.path.abspath(args.out)
    os.makedirs(out_dir, exist_ok=True)
    os.makedirs(os.path.join(out_dir, "generated"), exist_ok=True)

    if not os.path.isdir(sources_dir):
        sys.exit("找不到反编译源码目录: %s" % sources_dir)

    print("[*] 扫描 %s ..." % sources_dir)
    anchors, fields_by_class, java_files = scan_sources(sources_dir)
    print("[*] .java 文件 %d 个，抓到 %d 条 source-info 锚点" % (java_files, len(anchors)))

    anchors.sort(key=lambda a: (a["obf_class"], a["obf_method"], a["src_file"], a["src_line"]))

    with open(os.path.join(out_dir, "anchors.json"), "w", encoding="utf-8") as fh:
        json.dump(anchors, fh, ensure_ascii=False, indent=1)

    with open(os.path.join(out_dir, "anchors.tsv"), "w", encoding="utf-8") as fh:
        fh.write("orig_symbol\tsrc_file\tsrc_line\tobf_class\tobf_method\tobf_params\n")
        for a in anchors:
            fh.write("\t".join([
                a["symbol"], a["src_file"], str(a["src_line"]),
                a["obf_class"], a["obf_method"], a["obf_params"],
            ]) + "\n")

    # ---- 统计 ----
    classes = Counter(a["obf_class"] for a in anchors)
    srcfiles = Counter(a["src_file"] for a in anchors)
    print("\n=== 承载锚点最多的混淆类 (top 15) ===")
    for cls, n in classes.most_common(15):
        print("  %-10s %d" % (cls, n))
    print("\n=== 锚点最多的原始源文件 (top 15) ===")
    for sf, n in srcfiles.most_common(15):
        print("  %-40s %d" % (sf, n))

    # ---- 主题相关子集 ----
    theme = [a for a in anchors if a["src_file"] in ("Theme.kt", "Type.kt", "DarkThemePreference.kt")
             or a["symbol"].startswith("com.deepseek.chat.ui.theme")]
    theme.sort(key=lambda a: (a["src_file"], a["src_line"]))
    with open(os.path.join(out_dir, "theme-targets.json"), "w", encoding="utf-8") as fh:
        json.dump(theme, fh, ensure_ascii=False, indent=1)

    print("\n=== 主题相关锚点（%d 条）===" % len(theme))
    for a in theme[:40]:
        print("  %-8s %-5s %-58s %s.%s(%s)" % (
            a["src_file"], a["src_line"], a["symbol"], a["obf_class"], a["obf_method"], a["obf_params"][:40]))
    if len(theme) > 40:
        print("  ... 其余见 analysis/theme-targets.json")

    # ---- 主题类里的静态字段（Lazy / CompositionLocal 的候选项）----
    theme_classes = sorted({a["obf_class"] for a in theme if a["obf_class"]})
    report = {"theme_classes": {}, "notes": []}
    print("\n=== 主题类里的静态字段（推断 LIGHT/DARK/LOCAL）===")
    for cls in theme_classes:
        flds = fields_by_class.get(cls, [])
        report["theme_classes"][cls] = flds
        if flds:
            print("  [%s]" % cls)
            for f in flds:
                print("      %-8s %-8s = %s" % (f["name"], f["type"], f["init"][:60]))

    with open(os.path.join(out_dir, "generated", "theme-classes.json"), "w", encoding="utf-8") as fh:
        json.dump(report, fh, ensure_ascii=False, indent=1)

    print("\n[+] 输出：")
    print("    %s" % os.path.join(out_dir, "anchors.json"))
    print("    %s" % os.path.join(out_dir, "anchors.tsv"))
    print("    %s" % os.path.join(out_dir, "theme-targets.json"))
    print("    %s" % os.path.join(out_dir, "generated", "theme-classes.json"))


if __name__ == "__main__":
    main()
