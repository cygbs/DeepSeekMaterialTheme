#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
analyze_tokens.py —— 解出 DeepSeek 自研 token 体系（r53）的结构、色值，并推测每个 token 的语义。

背景（已经查明的结构）
----------------------
* `p3a.a` / `p3a.b` 是两个 `kotlin.Lazy`：分别持有浅色 / 深色 token 对象
* `p3a.c` 是分发 token 的 CompositionLocal
* token 容器是 `r53`，10 个字段各挂一组子 token（o53/b9b/v8/n53/pv/le2/o53/dvb/q53/epb）
* 两套 token 由 `pa6.C()`（浅色）/ `pa6.s()`（深色）构造

本脚本做三件事
--------------
1. 把两个构造方法里的 `new XXX(...)` 递归展开成树，`rj2.s(n)` / `rj2.q(n)` 解成 #AARRGGBB
2. 解析色板类（h37/u53/ga8/oa/em4 —— 它们的静态 long 字段就是原始色值）
3. 用法反推语义：把 `r53Var.<字段>` 的读取位置对应回最近的 source-info 锚点，
   于是可以知道「哪个组合函数在用这个 token」，据此猜测它对应 Material 3 的哪个 color role

输出：analysis/tokens.md、analysis/tokens.json
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from collections import Counter, defaultdict

COLOR_RE = re.compile(r"rj2\.([sq])\(\s*(-?\d+)L?\s*\)")
CONST_LONG_RE = re.compile(
    r"^\s*(?:public|private|protected)?\s*static\s+final\s+long\s+(\w+)\s*=\s*(.+);", re.M
)
VAR_DECL_RE = re.compile(
    r"^\s*(?:final\s+)?([A-Za-z_][\w.$<>\[\], ]*?)\s+(\w+)\s*=\s*(.+);\s*$"
)
NEW_RE = re.compile(r"^new\s+([\w.$]+)\s*\((.*)\)$", re.S)
CTOR_RE = re.compile(
    r"^\s*(?:public|private|protected)?\s*(\w+)\s*\(([^()]*)\)\s*\{"
)

# 已知的 token 容器结构
THEME_CLASS = "p3a"
TOKEN_CLASS = "r53"
BUILDER_CLASS = "pa6"
LIGHT_METHOD = "C"
DARK_METHOD = "s"

ROLE_HINTS = [
    (("destructive", "delete", "danger", "error"), "error / onError"),
    (("divider", "border", "outline", "stroke"), "outlineVariant"),
    (("topbar", "top_bar", "appbar", "app_bar", "selectiontopbar"), "surfaceContainer"),
    (("bottomsheet", "sheet", "modal"), "surfaceContainerHigh"),
    (("dialog", "alert"), "surfaceContainerHigh"),
    (("chip", "tab"), "secondaryContainer"),
    (("button", "primary", "brand", "accent"), "primary / onPrimary"),
    (("card", "surface", "background", "page", "root"), "surface / surfaceContainer"),
    (("placeholder", "hint", "sub", "secondary", "caption", "label"), "onSurfaceVariant"),
    (("disabled", "unselected", "inactive"), "onSurface / disabled"),
    (("text", "content", "title", "body"), "onSurface"),
    (("icon",), "onSurfaceVariant"),
]


def argb(v: int) -> str:
    return "#%08X" % (v & 0xFFFFFFFF)


def split_args(s: str):
    """按顶层逗号切分参数。"""
    out, depth, buf, quote = [], 0, [], None
    for ch in s:
        if quote:
            buf.append(ch)
            if ch == quote:
                quote = None
            continue
        if ch in "\"'":
            quote = ch
            buf.append(ch)
            continue
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if ch == "," and depth == 0:
            out.append("".join(buf).strip())
            buf = []
        else:
            buf.append(ch)
    if buf:
        out.append("".join(buf).strip())
    return [a for a in out if a != ""]


def find_method_body(lines, class_name, method_name):
    """返回方法体的行区间 [start, end)。"""
    for i, line in enumerate(lines):
        m = re.match(
            r"^\s*(?:public|private|protected)?\s*(?:static\s+)?(?:final\s+)?[\w.$<>\[\], ]+\s+"
            + re.escape(method_name)
            + r"\(",
            line,
        )
        if not m:
            continue
        if "class " in line:
            continue
        depth = 0
        started = False
        for j in range(i, len(lines)):
            depth += lines[j].count("{") - lines[j].count("}")
            if "{" in lines[j]:
                started = True
            if started and depth <= 0:
                return lines[i : j + 1]
    return []


def load_palette(src_dir):
    """色板类：public static final long X = rj2.s(<argb>)"""
    palette = {}
    for fn in os.listdir(src_dir):
        if not fn.endswith(".java"):
            continue
        cls = fn[:-5]
        try:
            with open(os.path.join(src_dir, fn), encoding="utf-8", errors="replace") as fh:
                text = fh.read()
        except OSError:
            continue
        for m in CONST_LONG_RE.finditer(text):
            field, init = m.group(1), m.group(2)
            cm = COLOR_RE.search(init)
            if cm:
                palette["%s.%s" % (cls, field)] = int(cm.group(2))
            else:
                lm = re.match(r"^(-?\d+)L?$", init.strip())
                if lm:
                    palette["%s.%s" % (cls, field)] = int(lm.group(1))
    return palette


def ctor_param_names(cls_file, arity):
    """从类文件里找参数个数匹配的构造器，取其参数名（合并类可能多个 -> 返回 None）。"""
    try:
        with open(cls_file, encoding="utf-8", errors="replace") as fh:
            text = fh.read()
    except OSError:
        return None
    cands = []
    for m in CTOR_RE.finditer(text):
        params = [p.strip() for p in m.group(2).split(",") if p.strip()]
        if len(params) == arity:
            names = [p.split()[-1] for p in params]
            cands.append(names)
    if len(cands) == 1:
        return cands[0]
    return None


def render(expr, variables, palette, src_dir, depth=0, field_names=None):
    """把表达式渲染成可读字符串（递归展开 new）。"""
    expr = expr.strip()
    expr = re.sub(r"^\(byte\)\s*", "", expr)
    expr = re.sub(r"^\(long\)\s*", "", expr)

    cm = COLOR_RE.fullmatch(expr)
    if cm:
        return argb(int(cm.group(2)))

    nm = NEW_RE.match(expr)
    if nm:
        cls = nm.group(1)
        args = split_args(nm.group(2))
        names = ctor_param_names(os.path.join(src_dir, cls + ".java"), len(args))
        parts = []
        for idx, a in enumerate(args):
            label = names[idx] if names else "arg%d" % idx
            parts.append("%s=%s" % (label, render(a, variables, palette, src_dir, depth + 1)))
        return "%s{%s}" % (cls, ", ".join(parts))

    if expr in variables:
        return render(variables[expr], variables, palette, src_dir, depth + 1)

    if expr in palette:
        return "%s(%s)" % (argb(palette[expr]), expr)

    m = re.fullmatch(r"([A-Za-z_][\w$]*)\.(\w+)", expr)
    if m and m.group(0) in palette:
        return "%s(%s)" % (argb(palette[m.group(0)]), m.group(0))

    m = re.fullmatch(r"(-?\d+)L?", expr)
    if m:
        return m.group(1)

    return expr


def collect_vars(body):
    """收集方法体里的局部变量赋值（按出现顺序）。"""
    variables = {}
    for line in body:
        line = line.strip()
        m = VAR_DECL_RE.match(line)
        if not m:
            continue
        text, name, rhs = m.group(1).strip(), m.group(2), m.group(3).strip()
        if text.split()[-1] in ("if", "for", "while", "return"):
            continue
        if "->" in line or "new " not in rhs and "(" in rhs and ")" in rhs and "{\n" in rhs:
            pass
        variables[name] = rhs
    return variables


def extract_token_tree(body, src_dir, palette):
    """从构造方法里找到 `return new r53(...)` 并展开。"""
    variables = collect_vars(body)
    for line in body:
        if "return new %s(" % TOKEN_CLASS in line:
            expr = line.strip()
            expr = expr[expr.index("new "):].rstrip(";")
            return render(expr, variables, palette, src_dir), variables
    return None, variables


def guess_role(anchors):
    joined = " ".join(anchors).lower()
    for keys, role in ROLE_HINTS:
        if any(k in joined for k in keys):
            return role
    return "?"


def is_accessor(symbol: str) -> bool:
    """`<get-colorSchemeV2>` 这类访问器是 R8 内联出来的，不能用来判断语义。"""
    return "<get-" in symbol or "<set-" in symbol


def usage_map(sources_dir, anchors_path):
    """路径 -> {symbol: 次数}，路径形如 d.c（r53 的字段链）。"""
    with open(anchors_path, encoding="utf-8") as fh:
        anchors = json.load(fh)
    by_file = defaultdict(list)
    for a in anchors:
        by_file[a["sources_file"]].append((a["sources_line"], a["symbol"], a["src_file"]))
    for v in by_file.values():
        v.sort()

    result = defaultdict(Counter)
    srcfile_hist = defaultdict(Counter)
    token_vars_cache = {}
    for rel, entries in by_file.items():
        path = os.path.join(sources_dir, rel)
        try:
            with open(path, encoding="utf-8", errors="replace") as fh:
                lines = fh.readlines()
        except OSError:
            continue
        token_vars = token_vars_cache.get(rel)
        if token_vars is None:
            token_vars = set(re.findall(r"\b%s\s+(\w+)" % TOKEN_CLASS, "".join(lines)))
            token_vars_cache[rel] = token_vars
        if not token_vars:
            continue

        cur = None
        cur_src = ""
        idx = 0
        for i, line in enumerate(lines, start=1):
            while idx < len(entries) and entries[idx][0] <= i:
                sym, src = entries[idx][1], entries[idx][2]
                if not is_accessor(sym):
                    cur, cur_src = sym, src
                idx += 1
            if cur is None or cur_src == "":
                continue
            for var in token_vars:
                for m in re.finditer(r"\b%s\.(\w+)(?:\.(\w+))?" % re.escape(var), line):
                    key = m.group(1) + ("." + m.group(2) if m.group(2) else "")
                    result[key][cur] += 1
                    srcfile_hist[key][cur_src] += 1
    return result, srcfile_hist


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

    pkg = os.path.join(sources_dir, "defpackage")
    if not os.path.isdir(pkg):
        sys.exit("找不到 %s" % pkg)

    print("[*] 解析色板类 ...")
    palette = load_palette(pkg)
    print("    共 %d 个色值常量" % len(palette))

    def read(name):
        with open(os.path.join(pkg, name), encoding="utf-8", errors="replace") as fh:
            return fh.readlines()

    builder_lines = read(BUILDER_CLASS + ".java")
    result = {}
    for label, meth in (("LIGHT", LIGHT_METHOD), ("DARK", DARK_METHOD)):
        body = find_method_body(builder_lines, BUILDER_CLASS, meth)
        print("[*] %s: %s.%s() 共 %d 行" % (label, BUILDER_CLASS, meth, len(body)))
        tree, variables = extract_token_tree(body, pkg, palette)
        if not tree:
            print("    !! 没找到 return new %s(...)" % TOKEN_CLASS)
            continue
        result[label] = {"tree": tree, "variables": len(variables)}

    print("[*] 统计 token 用法（反推语义）...")
    usage, usage_src = usage_map(sources_dir, os.path.join(out_dir, "anchors.json"))

    # ---------------- 输出 markdown ----------------
    md = []
    md.append("# DeepSeek 2.5.3 主题 token 分析\n")
    md.append("> 由 `tools/analyze_tokens.py` 自动生成。类名是 R8 混淆后的名字（默认包）。\n")
    md.append("## 1. 结构\n")
    md.append("```")
    md.append("p3a.a : kotlin.Lazy  -> 浅色 token (r53)")
    md.append("p3a.b : kotlin.Lazy  -> 深色 token (r53)")
    md.append("p3a.c : CompositionLocal -> 把 r53 发给整棵 composition")
    md.append("r53   : token 容器，10 个字段，每个字段是一组子 token")
    md.append("pa6.C() : 构造浅色 r53")
    md.append("pa6.s() : 构造深色 r53")
    md.append("```\n")

    for label in ("LIGHT", "DARK"):
        if label not in result:
            continue
        md.append("## 2.%s %s token 构造（色值已解码为 #AARRGGBB）\n" % ("1" if label == "LIGHT" else "2", label))
        md.append("```")
        md.append(result[label]["tree"])
        md.append("```\n")

    md.append("## 3. token 用法 -> 语义推测\n")
    md.append("| token 路径 | 读取次数 | 主要源文件（.kt） | 主要组合函数 | 推测对应的 M3 角色 |")
    md.append("|---|---|---|---|---|")
    for key, counter in sorted(usage.items(), key=lambda kv: -sum(kv[1].values())):
        total = sum(counter.values())
        tops = [s.split(".")[-1] for s, _ in counter.most_common(4)]
        srcs = [s for s, _ in usage_src[key].most_common(4)]
        md.append("| `%s` | %d | %s | %s | %s |" % (
            key, total, ", ".join(srcs), ", ".join(tops[:4]), guess_role(srcs + tops)))

    md.append("\n## 4. 色板常量（原始色值）\n")
    md.append("| 常量 | 色值 |")
    md.append("|---|---|")
    for k, v in sorted(palette.items()):
        md.append("| `%s` | %s |" % (k, argb(v)))

    out_md = os.path.join(out_dir, "tokens.md")
    with open(out_md, "w", encoding="utf-8") as fh:
        fh.write("\n".join(md) + "\n")

    with open(os.path.join(out_dir, "tokens.json"), "w", encoding="utf-8") as fh:
        json.dump(
            {
                "struct": {
                    "theme_class": THEME_CLASS,
                    "token_class": TOKEN_CLASS,
                    "builder_class": BUILDER_CLASS,
                    "light_method": LIGHT_METHOD,
                    "dark_method": DARK_METHOD,
                },
                "builders": result,
                "palette": palette,
                "usage": {k: dict(v) for k, v in usage.items()},
                "usage_srcfile": {k: dict(v) for k, v in usage_src.items()},
            },
            fh,
            ensure_ascii=False,
            indent=1,
        )

    print("\n[+] 输出：")
    print("    %s" % out_md)
    print("    %s" % os.path.join(out_dir, "tokens.json"))

    print("\n=== token 路径 top 25（按读取次数）===")
    for key, counter in sorted(usage.items(), key=lambda kv: -sum(kv[1].values()))[:25]:
        srcs = ", ".join(s for s, _ in usage_src[key].most_common(3))
        print("  %-8s %5d   %s" % (key, sum(counter.values()), srcs))


if __name__ == "__main__":
    main()
