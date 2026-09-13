#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把游戏界面定义 layout.xml 1:1 还原为 HTML 预览页。

用法:
    python3 tools/layout2html.py

输出:
    layout_preview.html          自包含预览页（双击即可在浏览器打开）
    layout_preview_img/          可选：把游戏原图按文件名放进去，刷新页面即显示

实现的布局规则:
    - margin="左,上,右,下" 定义控件在父容器内的区域，缺省为 0
    - width/height 显式给定时作为控件尺寸，否则撑满 margin 区域
    - halign/valign(left/center/right, top/center/bottom) 在区域内对齐
    - texture 属性的贴图作为背景层，最先绘制
    - HGroup/VGroup 按 gap + hlayoutalign/vlayoutalign 横向/纵向排布
    - Repeater 按 itemwidth/itemheight/itemcount 排成网格
    - ScrollViewer 内容高度按子控件实际高度计算
    - PlaceHolder 通过 templateid 实例化 Template 模板
"""

import html as H
import json
import os
import re

SRC = "/Users/mrjoe_chen/Desktop/layout.xml"
HERE = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.dirname(HERE)          # 项目根目录
OUT = os.path.join(OUT_DIR, "layout_preview.html")
IMG_DIR = "layout_preview_img"           # 相对 HTML 的图片目录

STAGE_W, STAGE_H = 1280, 720             # 游戏逻辑分辨率（最大窗口 1270 宽佐证）

# 收集整个 layout 中每个图片出现过的显式尺寸，作为“未给尺寸时的默认尺寸”
IMG_SIZES = {}

FONT_PX = {
    "font_ascii": 16,
    "font_num_1": 14, "font_num_2": 16, "font_num_3": 18, "font_num_4": 22,
    "font_text_0": 12, "font_text_1": 14, "font_text_2": 16, "font_text_2b": 17,
    "font_text_3": 18, "font_text_3a": 20, "font_text_4": 22, "font_text_4b": 24,
    "font_text_5": 26, "font_text_lang": 16, "sysfont": 16,
}

TAG_RE = re.compile(r"<(/?)([A-Za-z][A-Za-z0-9_]*)((?:\s+[^<>]*?)?)(/?)>", re.S)
ATTR_RE = re.compile(r'([A-Za-z_][A-Za-z0-9_]*)\s*=\s*"([^"]*)"')


# ---------------------------------------------------------------------------
# 容错解析
# ---------------------------------------------------------------------------

def preprocess(s):
    # 属性之间漏空格：hlayoutalign="center"margin="..." -> 补一个空格
    s = re.sub(r'="([^"]*)"(?=[A-Za-z_])', r'="\1" ', s)
    # 裸 & 转义（保留已有实体）
    s = re.sub(r"&(?!(?:amp|lt|gt|quot|apos|#\d+);)", "&amp;", s)
    return s


def parse(s):
    s = preprocess(s)
    root = {"tag": "#root", "attrs": {}, "children": []}
    stack = [root]
    for m in TAG_RE.finditer(s):
        closer, tag, body, selfclose = m.groups()
        if tag.startswith("!") or tag == "?xml":
            continue
        attrs = dict(ATTR_RE.findall(body))
        if closer:
            # 容忍错位闭合：向上找匹配，中间未闭合的元素视为隐式结束
            idx = len(stack) - 1
            while idx > 0 and stack[idx]["tag"] != tag:
                idx -= 1
            if idx > 0:
                del stack[idx:]
        else:
            el = {"tag": tag, "attrs": attrs, "children": []}
            stack[-1]["children"].append(el)
            if not selfclose:
                stack.append(el)
    return root


# ---------------------------------------------------------------------------
# 几何计算
# ---------------------------------------------------------------------------

def parse_margin(v):
    if not v:
        return (0, 0, 0, 0)
    nums = [int(x) for x in re.findall(r"-?\d+", v)]
    if not nums:
        return (0, 0, 0, 0)
    if len(nums) == 1:
        return (nums[0],) * 4
    if len(nums) == 2:
        return (nums[0], nums[1], 0, 0)
    if len(nums) == 3:
        return (nums[0], nums[1], nums[2], 0)
    return tuple(nums[:4])


def area_box(el, px, py, pw, ph):
    l, t, r, b = parse_margin(el["attrs"].get("margin", ""))
    return px + l, py + t, pw - l - r, ph - t - b


def final_box(el, px, py, pw, ph, def_hal="left", def_val="top"):
    ax, ay, aw, ah = area_box(el, px, py, pw, ph)
    w, h = control_size(el, aw, ah)
    return positioned_box(el, pw, ph, w, h, def_hal, def_val)


def control_size(el, aw, ah):
    """图片/按钮的实际尺寸：
    - 显式 width/height 优先
    - 只给一维时，另一维用该图片在文件里出现过的尺寸补全（否则用默认）
    - 都不给时：texture 贴图/结构容器铺满；有图用已知尺寸或 100×100；
      无图叶子占位 64×64
    """
    a = el["attrs"]
    if el["tag"] not in ("Image", "Button"):
        return aw, ah
    w = el["attrs"].get("width")
    h = el["attrs"].get("height")
    src = a.get("image") or a.get("normalimage")
    info = IMG_SIZES.get(src.split("/")[-1], {}) if src else {}
    tex = "texture" in a
    if w is None and h is None:
        if tex or not src:
            if not src and not visible_kids(el):
                return 64, 64
            return aw, ah
        return info.get("w") or 100, info.get("h") or 100
    ww = int(w) if w is not None else (info.get("w") or (aw if tex else 100))
    hh = int(h) if h is not None else (info.get("h") or (ah if tex else 100))
    return ww, hh


def positioned_box(el, pw, ph, w, h, def_hal="left", def_val="top"):
    """在父容器 (pw×ph) 内按 margin 区域 + halign/valign 摆放 w×h 的控件"""
    ax, ay, aw, ah = area_box(el, 0, 0, pw, ph)
    hal = el["attrs"].get("halign", def_hal)
    val = el["attrs"].get("valign", def_val)
    x = ax
    if hal == "center":
        x = ax + (aw - w) / 2.0
    elif hal == "right":
        x = ax + aw - w
    y = ay
    if val == "center":
        y = ay + (ah - h) / 2.0
    elif val == "bottom":
        y = ay + ah - h
    return x, y, w, h


def flex_layout(el, box, axis):
    """HGroup/VGroup 子控件排布，返回 [(child, box), ...]"""
    px, py, pw, ph = box
    gap = int(el["attrs"].get("gap", "0") or 0)
    kids = [c for c in el["children"] if c["tag"] != "Template" and vis(c)]
    if not kids:
        return []
    if axis == "h":
        sizes, flex_n = [], 0
        for k in kids:
            if "width" in k["attrs"]:
                sizes.append(int(k["attrs"]["width"]))
            else:
                sizes.append(None)
                flex_n += 1
        fixed = sum(s for s in sizes if s is not None)
        rem = pw - fixed - gap * (len(kids) - 1)
        fw = max(rem / flex_n, 0) if flex_n else 0
        widths = [s if s is not None else fw for s in sizes]
        heights = [int(k["attrs"]["height"]) if "height" in k["attrs"] else ph
                   for k in kids]
        total = sum(widths) + gap * (len(kids) - 1)
        ha = el["attrs"].get("hlayoutalign", "left")
        start = px + {"left": 0, "center": (pw - total) / 2.0,
                      "right": pw - total}.get(ha, 0)
        x = start
        out = []
        for k, w, h in zip(kids, widths, heights):
            val = k["attrs"].get("valign", "top")
            y = py + {"top": 0, "center": (ph - h) / 2.0,
                      "bottom": ph - h}.get(val, 0)
            out.append((k, (x - px, y - py, w, h)))
            x += w + gap
        return out
    sizes, flex_n = [], 0
    for k in kids:
        if "height" in k["attrs"]:
            sizes.append(int(k["attrs"]["height"]))
        else:
            sizes.append(None)
            flex_n += 1
    fixed = sum(s for s in sizes if s is not None)
    rem = ph - fixed - gap * (len(kids) - 1)
    fh = max(rem / flex_n, 0) if flex_n else 0
    heights = [s if s is not None else fh for s in sizes]
    widths = [int(k["attrs"]["width"]) if "width" in k["attrs"] else pw
              for k in kids]
    total = sum(heights) + gap * (len(kids) - 1)
    va = el["attrs"].get("vlayoutalign", "top")
    start = py + {"top": 0, "center": (ph - total) / 2.0,
                  "bottom": ph - total}.get(va, 0)
    y = start
    out = []
    for k, h, w in zip(kids, heights, widths):
        hal = k["attrs"].get("halign", "left")
        x = px + {"left": 0, "center": (pw - w) / 2.0,
                  "right": pw - w}.get(hal, 0)
        out.append((k, (x - px, y - py, w, h)))
        y += h + gap
    return out


def repeater_cells(el, box):
    x, y, w, h = box
    iw = int(el["attrs"].get("itemwidth", w or 1))
    ih = int(el["attrs"].get("itemheight", h or 1))
    n = max(int(el["attrs"].get("itemcount", "0") or 0), 0)
    per_row = max(1, int(w // iw)) if iw else 1
    return [((i % per_row) * iw, (i // per_row) * ih, iw, ih)
            for i in range(n)]


def scroll_content(el, box):
    x, y, w, h = box
    cw = max(w - 14, 1)
    max_h = 0
    for c in el["children"]:
        if c["tag"] == "Template" or not vis(c):
            continue
        cx, cy, cw2, ch2 = final_box(c, 0, 0, cw, h)
        max_h = max(max_h, cy + ch2)
    return cw, max(h, max_h)


# ---------------------------------------------------------------------------
# HTML 生成
# ---------------------------------------------------------------------------

def esc(v):
    return H.escape(str(v), quote=True)


def fmt(v):
    if abs(v - round(v)) < 1e-6:
        return str(int(round(v)))
    return f"{v:.1f}"


def style_of(box):
    x, y, w, h = box
    return (f"left:{fmt(x)}px;top:{fmt(y)}px;"
            f"width:{fmt(w)}px;height:{fmt(h)}px;")


def vis(el):
    return el["attrs"].get("visible", "true").strip().lower() == "true"


def parse_color(v):
    if not v:
        return "rgba(255,255,255,1)"
    nums = [x.strip() for x in str(v).split(",")]
    try:
        r, g, b = (int(nums[0]), int(nums[1]), int(nums[2]))
        a = float(nums[3]) if len(nums) > 3 else 255
        return f"rgba({r},{g},{b},{a / 255:.2f})"
    except Exception:
        return "rgba(255,255,255,1)"


def tooltip(el, box, src=""):
    a = el["attrs"]
    parts = [a.get("id") or el["tag"], el["tag"], f"{fmt(box[2])}×{fmt(box[3])}"]
    if a.get("margin"):
        parts.append(f"margin={a['margin']}")
    if src:
        parts.append(f"img={src}")
    t = a.get("text") or a.get("string")
    if t:
        parts.append(f'text="{t}"')
    return " · ".join(parts)


def badge(el, extra=""):
    i = el["attrs"].get("id", "")
    if not i:
        return ""
    return f'<span class="badge">{esc(i)}{extra}</span>'


def bg_style(src, scale="", alpha=""):
    st = f"background-image:url('{IMG_DIR}/{esc(src)}');"
    st += "background-repeat:no-repeat;background-position:center;"
    st += "background-size:100% 100%;"
    if scale:
        st += f"transform:scale({esc(scale)});transform-origin:0 0;"
    if alpha:
        st += f"opacity:{esc(alpha)};"
    return st


def visible_kids(el):
    return [c for c in el["children"] if c["tag"] != "Template" and vis(c)]


def render_children(el, box, templates):
    kids = visible_kids(el)
    tex = [k for k in kids if "texture" in k["attrs"]]
    others = [k for k in kids if "texture" not in k["attrs"]]
    out = []
    for k in tex + others:
        kb = final_box(k, 0, 0, box[2], box[3])
        out.append(render_node(k, kb, templates))
    return "".join(out)


def render_node(el, box, templates):
    tag = el["tag"]
    if tag == "Template" or not vis(el):
        return ""
    if tag == "Image":
        return render_image(el, box, templates)
    if tag == "Button":
        return render_button(el, box, templates)
    if tag == "Label":
        return render_label(el, box, templates)
    if tag in ("GroupBox", "Group"):
        return render_group(el, box, templates)
    if tag in ("HGroup", "VGroup"):
        return render_flex(el, box, templates, tag)
    if tag == "SlideList":
        return render_flex(el, box, templates, "VGroup")
    if tag == "ScrollViewer":
        return render_scroll(el, box, templates)
    if tag == "Repeater":
        return render_repeater(el, box, templates)
    if tag == "PlaceHolder":
        return render_placeholder(el, box, templates)
    if tag == "ProgressBar":
        return render_progress(el, box)
    if tag == "Slider":
        return render_slider(el, box)
    if tag in ("EditBox", "TextBox"):
        return render_textbox(el, box, tag)
    if tag == "Animation":
        return render_animation(el, box)
    if tag in ("MotionalArmy", "MotionalSoldier", "Battlefield",
               "TacticalMap", "LoadingPhoto"):
        return render_special(el, box, tag)
    return render_group(el, box, templates)


def render_image(el, box, templates):
    src = el["attrs"].get("image") or el["attrs"].get("texture") or ""
    kids = visible_kids(el)
    explicit = "width" in el["attrs"] or "height" in el["attrs"]
    scale = el["attrs"].get("scale", "")
    alpha = el["attrs"].get("alpha", "")
    title = tooltip(el, box, src)
    if src and not explicit and not kids:
        st = f"left:{fmt(box[0])}px;top:{fmt(box[1])}px;"
        if scale:
            st += f"transform:scale({esc(scale)});transform-origin:0 0;"
        if alpha:
            st += f"opacity:{esc(alpha)};"
        return (f'<img class="ch ctl nat" src="{IMG_DIR}/{esc(src)}" '
                f'data-img="{esc(src)}" data-id="{esc(el["attrs"].get("id", ""))}" '
                f'data-w="{fmt(box[2])}" data-h="{fmt(box[3])}" '
                f'style="{st}" onerror="imgFail(this)" '
                f'title="{esc(title)}">')
    cls = "ch ctl bgimg" if src else "ch ctl noimg"
    st = style_of(box)
    if src:
        st += bg_style(src, scale, alpha)
    elif scale:
        st += f"transform:scale({esc(scale)});transform-origin:0 0;"
    inner = render_children(el, box, templates)
    return (f'<div class="{cls}" style="{st}" data-img="{esc(src)}" '
            f'data-id="{esc(el["attrs"].get("id", ""))}" '
            f'title="{esc(title)}">{badge(el)}{inner}</div>')


def render_button(el, box, templates):
    src = el["attrs"].get("normalimage", "")
    kids = visible_kids(el)
    scale = el["attrs"].get("scale", "")
    alpha = el["attrs"].get("alpha", "")
    cls = "ch ctl btn bgimg" if src else "ch ctl btn noimg"
    st = style_of(box)
    if src:
        st += bg_style(src, scale, alpha)
    inner = ""
    t = el["attrs"].get("text") or el["attrs"].get("string")
    if t:
        font = el["attrs"].get("font", "")
        size = int(el["attrs"].get("fontsize", FONT_PX.get(font, 16)))
        inner += (f'<span class="btn-text" style="font-size:{size}px;'
                  f'color:{parse_color(el["attrs"].get("color", ""))}">'
                  f"{esc(t)}</span>")
    inner += render_children(el, box, templates)
    return (f'<div class="{cls}" style="{st}" data-img="{esc(src)}" '
            f'data-id="{esc(el["attrs"].get("id", ""))}" '
            f'title="{esc(tooltip(el, box, src))}">{badge(el)}{inner}</div>')


def render_label(el, box, templates):
    t = el["attrs"].get("text") or el["attrs"].get("string") or ""
    font = el["attrs"].get("font", "")
    size = int(el["attrs"].get("fontsize", FONT_PX.get(font, 16)))
    ha = el["attrs"].get("htextalign", "center")
    va = el["attrs"].get("vtextalign", "center")
    bw = el["attrs"].get("breakwords", "").strip().lower() in ("true", "1")
    st = style_of(box)
    st += (f"display:flex;align-items:{va};justify-content:{ha};"
           f"font-size:{size}px;line-height:{size * 1.25:.0f}px;"
           f"color:{parse_color(el['attrs'].get('color', ''))};")
    bg = el["attrs"].get("backimage", "")
    cls = "ch ctl lbl"
    if bg:
        cls += " bgimg"
        st += bg_style(bg)
    span_st = "overflow:hidden;"
    if bw:
        span_st += "white-space:normal;word-break:break-all;"
    else:
        span_st += "white-space:nowrap;"
    inner = (f'<span style="{span_st}">{esc(t)}</span>'
             if t or bg else "")
    inner += render_children(el, box, templates)
    return (f'<div class="{cls}" style="{st}" data-img="{esc(bg)}" '
            f'data-id="{esc(el["attrs"].get("id", ""))}" '
            f'title="{esc(tooltip(el, box, bg))}">{badge(el)}{inner}</div>')


def render_group(el, box, templates):
    src = el["attrs"].get("image") or el["attrs"].get("backimage") or ""
    cls = "ch ctl group" + (" bgimg" if src else " gbox")
    st = style_of(box)
    if src:
        st += bg_style(src, el["attrs"].get("scale", ""),
                       el["attrs"].get("alpha", ""))
    inner = render_children(el, box, templates)
    return (f'<div class="{cls}" style="{st}" data-img="{esc(src)}" '
            f'data-id="{esc(el["attrs"].get("id", ""))}" '
            f'title="{esc(tooltip(el, box, src))}">{badge(el)}{inner}</div>')


def render_flex(el, box, templates, tag):
    axis = "h" if tag == "HGroup" else "v"
    items = flex_layout(el, box, axis)
    inner = "".join(render_node(k, kb, templates) for k, kb in items)
    cls = "ch ctl group gbox flex"
    st = style_of(box)
    return (f'<div class="{cls}" style="{st}" data-img="" '
            f'data-id="{esc(el["attrs"].get("id", ""))}" '
            f'title="{esc(tooltip(el, box))}">{badge(el)}{inner}</div>')


def render_scroll(el, box, templates):
    x, y, w, h = box
    cw, ch = scroll_content(el, box)
    kids = visible_kids(el)
    inner = "".join(
        render_node(k, final_box(k, 0, 0, cw, ch), templates)
        for k in kids)
    st = style_of(box)
    return (f'<div class="ch ctl scl" style="{st}" data-img="" '
            f'data-id="{esc(el["attrs"].get("id", ""))}" '
            f'title="{esc(tooltip(el, box))}">{badge(el)}'
            f'<div class="scl-content" style="width:{fmt(cw)}px;'
            f'height:{fmt(ch)}px;position:relative">{inner}</div></div>')


def render_repeater(el, box, templates):
    kids = visible_kids(el)
    cells = repeater_cells(el, box)
    inner = "".join(
        "".join(render_node(k, cell, templates) for k in kids)
        for cell in cells)
    st = style_of(box)
    return (f'<div class="ch ctl group gbox repeater" style="{st}" '
            f'data-img="" data-id="{esc(el["attrs"].get("id", ""))}" '
            f'title="{esc(tooltip(el, box))}">{badge(el)}{inner}</div>')


def render_placeholder(el, box, templates):
    tid = el["attrs"].get("templateid", "")
    tpl = templates.get(tid)
    st = style_of(box)
    if not tpl:
        return (f'<div class="ch ctl group gbox ph" style="{st}" '
                f'title="PlaceHolder → {esc(tid)}（未定义）">'
                f'<span class="ph-note">→ {esc(tid)} 未定义</span></div>')
    if "width" not in el["attrs"] and "width" in tpl["attrs"]:
        box = (box[0], box[1], int(tpl["attrs"]["width"]), box[3])
        st = style_of(box)
    if "height" not in el["attrs"] and "height" in tpl["attrs"]:
        box = (box[0], box[1], box[2], int(tpl["attrs"]["height"]))
        st = style_of(box)
    inner = "".join(render_node(k, final_box(k, 0, 0, box[2], box[3]), templates)
                    for k in visible_kids(tpl))
    return (f'<div class="ch ctl group gbox ph" style="{st}" data-img="" '
            f'data-id="{esc(el["attrs"].get("id", ""))}" '
            f'title="{esc(tooltip(el, box))} → {esc(tid)}">'
            f'{badge(el, "→" + esc(tid))}{inner}</div>')


def render_progress(el, box):
    try:
        pct = max(0, min(100, int(el["attrs"].get("value", "50"))))
    except Exception:
        pct = 50
    st = style_of(box)
    return (f'<div class="ch ctl pg" style="{st}" data-img="" '
            f'data-id="{esc(el["attrs"].get("id", ""))}" '
            f'title="{esc(tooltip(el, box))}">{badge(el)}'
            f'<div class="pg-fill" style="width:{pct}%"></div></div>')


def render_slider(el, box):
    try:
        pct = max(0, min(100, int(el["attrs"].get("value", "30"))))
    except Exception:
        pct = 30
    st = style_of(box)
    return (f'<div class="ch ctl sl" style="{st}" data-img="" '
            f'data-id="{esc(el["attrs"].get("id", ""))}" '
            f'title="{esc(tooltip(el, box))}">{badge(el)}'
            f'<div class="sl-track"><div class="sl-thumb" '
            f'style="left:{pct}%"></div></div></div>')


def render_textbox(el, box, tag):
    t = el["attrs"].get("text") or el["attrs"].get("string") or ""
    cls = "ch ctl eb" if tag == "EditBox" else "ch ctl tb"
    st = style_of(box)
    inner = f'<div class="tb-text">{esc(t)}</div>' if t else ""
    return (f'<div class="{cls}" style="{st}" data-img="" '
            f'data-id="{esc(el["attrs"].get("id", ""))}" '
            f'title="{esc(tooltip(el, box))}">{badge(el)}{inner}</div>')


def render_animation(el, box):
    res = el["attrs"].get("res", "")
    st = style_of(box)
    if el["attrs"].get("scale"):
        st += f"transform:scale({esc(el['attrs']['scale'])});transform-origin:0 0;"
    return (f'<div class="ch ctl anim" style="{st}" data-img="" '
            f'data-id="{esc(el["attrs"].get("id", ""))}" '
            f'title="{esc(tooltip(el, box))}">{badge(el)}'
            f'<span class="anim-tag">anim{(" · " + esc(res)) if res else ""}'
            f'</span></div>')


def render_special(el, box, tag):
    st = style_of(box)
    return (f'<div class="ch ctl special" style="{st}" data-img="" '
            f'data-id="{esc(el["attrs"].get("id", ""))}" '
            f'title="{esc(tooltip(el, box))}">{badge(el)}'
            f'<span class="anim-tag">{esc(tag)}</span></div>')


def render_window(el, templates):
    a = el["attrs"]
    has_wh = "width" in a or "height" in a
    w = int(a["width"]) if "width" in a else STAGE_W
    h = int(a["height"]) if "height" in a else STAGE_H
    stage_w = max(STAGE_W, w + 40)
    stage_h = max(STAGE_H, h + 40)
    if has_wh:
        hal = a.get("halign", "center")
        val = a.get("valign", "center")
        if hal == "center":
            x = (stage_w - w) / 2.0
        elif hal == "right":
            x = stage_w - w
        else:
            x = 0
        if val == "center":
            y = (stage_h - h) / 2.0
        elif val == "bottom":
            y = stage_h - h
        else:
            y = 0
    else:
        x, y = 0, 0
    box = (0, 0, w, h)
    inner = render_children(el, box, templates)
    frame = (f'<div class="win" style="left:{fmt(x)}px;top:{fmt(y)}px;'
             f'width:{fmt(w)}px;height:{fmt(h)}px;">{inner}</div>')
    label = (f'<div class="stage-label">{esc(a.get("id", ""))} · '
             f'{fmt(w)}×{fmt(h)} · 1280×720 画布</div>')
    return {"stage_w": stage_w, "stage_h": stage_h,
            "html": frame + label}


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

def main():
    src = open(SRC, encoding="utf-8").read()
    root = parse(src)
    layouts = root["children"][0] if root["children"] else root

    def collect_sizes(el):
        for key in ("image", "normalimage", "backimage", "texture"):
            s = el["attrs"].get(key)
            if not s:
                continue
            name = s.split("/")[-1]
            info = IMG_SIZES.setdefault(name, {})
            w = el["attrs"].get("width")
            h = el["attrs"].get("height")
            if w:
                info["w"] = max(info.get("w") or 0, int(w))
            if h:
                info["h"] = max(info.get("h") or 0, int(h))
        for c in el["children"]:
            collect_sizes(c)
    for el in layouts["children"]:
        collect_sizes(el)

    templates = {}
    for el in layouts["children"]:
        if el["tag"] == "Template" and el["attrs"].get("id"):
            templates[el["attrs"]["id"]] = el

    windows = [el for el in layouts["children"]
               if el["tag"] in ("Form", "TmpWindow", "Template")]
    forms = {}
    order = []
    for el in windows:
        wid = el["attrs"].get("id")
        if not wid:
            continue
        rendered = render_window(el, templates)
        forms[wid] = {
            "type": el["tag"],
            "stageW": rendered["stage_w"],
            "stageH": rendered["stage_h"],
            "html": rendered["html"],
        }
        order.append(wid)

    meta = {
        "source": SRC,
        "forms": len(forms),
        "stage": f"{STAGE_W}×{STAGE_H}",
        "imgDir": IMG_DIR,
    }
    payload = json.dumps({"meta": meta, "order": order, "forms": forms},
                         ensure_ascii=False)

    page = build_page(order, payload, meta)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write(page)
    print(f"OK -> {OUT}  ({len(forms)} 个界面, {len(payload) // 1024} KB JSON)")


def build_page(order, payload, meta):
    nav_items = "".join(
        f'<div class="nav" data-target="{esc(i)}">{esc(i)}'
        f'<span class="t"></span></div>' for i in order)

    css = CSS
    js = JS
    return f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>layout.xml 界面预览</title>
<style>{css}</style>
</head>
<body>
<header>
  <h1>layout.xml · 界面 1:1 预览</h1>
  <div class="tool">
    <input id="search" type="search" placeholder="搜索界面…">
  </div>
  <div class="tool">缩放
    <input id="zoom" type="range" min="20" max="200" value="60">
    <span id="zoomv">60%</span>
  </div>
  <div class="tool">
    <button id="prev">‹ 上一个</button>
    <button id="next">下一个 ›</button>
    <button id="badgeToggle" title="显示/隐藏控件名标注">控件名: 开</button>
  </div>
  <div class="tool hint">占位块 = 缺少原图；把游戏图片放进
    <b>{esc(IMG_DIR)}/</b> 后刷新即显示</div>
</header>
<div id="wrap">
  <aside>
    <div id="list">{nav_items}</div>
  </aside>
  <main>
    <div id="stage-wrap"><div id="stage"></div></div>
  </main>
</div>
<script id="forms-data" type="application/json">{payload}</script>
<script>{js}</script>
</body>
</html>
"""


CSS = r"""
* { box-sizing: border-box; margin: 0; padding: 0; }
:root {
  --panel: #121826; --panel2: #0b1019; --line: #223047;
  --text: #dbe7ff; --muted: #8fa3c4; --accent: #5aa2ff;
}
html, body { height: 100%; }
body {
  display: flex; flex-direction: column; background: var(--panel2);
  color: var(--text); font: 13px/1.4 -apple-system, BlinkMacSystemFont,
  "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
  overflow: hidden;
}
header {
  display: flex; align-items: center; gap: 14px; flex-wrap: wrap;
  padding: 8px 14px; background: var(--panel);
  border-bottom: 1px solid var(--line); flex: 0 0 auto;
}
header h1 { font-size: 14px; font-weight: 600; }
.tool { display: flex; align-items: center; gap: 6px; color: var(--muted); }
.tool input[type=range] { width: 120px; }
.hint { font-size: 11px; }
.hint b { color: var(--accent); font-weight: 600; }
button {
  background: #1b2537; color: var(--text); border: 1px solid var(--line);
  border-radius: 6px; padding: 4px 10px; cursor: pointer; font-size: 12px;
}
button:hover { border-color: var(--accent); }
#wrap { display: flex; flex: 1 1 auto; min-height: 0; }
aside {
  width: 290px; flex: 0 0 auto; background: var(--panel);
  border-right: 1px solid var(--line);
  display: flex; flex-direction: column; min-height: 0;
}
#search {
  margin: 10px; padding: 6px 10px; border-radius: 6px;
  border: 1px solid var(--line); background: #0d1524; color: var(--text);
  font-size: 12px;
}
#list { flex: 1 1 auto; overflow: auto; padding: 0 8px 12px; }
.nav {
  display: block; width: 100%; text-align: left; margin: 1px 0;
  padding: 3px 8px; border: none; background: transparent; border-radius: 5px;
  font-size: 12px; color: var(--text); cursor: pointer; white-space: nowrap;
  overflow: hidden; text-overflow: ellipsis;
}
.nav:hover { background: #1c2740; }
.nav.on { background: #2b4a78; color: #fff; }
.nav .t { color: var(--muted); font-size: 10px; margin-left: 6px; }
main { flex: 1 1 auto; min-width: 0; overflow: auto; background: #0a0f18; }
#stage-wrap {
  min-width: 100%; min-height: 100%; display: flex;
  align-items: flex-start; justify-content: flex-start; padding: 20px;
}
#stage {
  position: relative; flex: 0 0 auto;
  background: radial-gradient(1200px 700px at 50% 30%, #172033, #0b1019 70%);
  box-shadow: 0 0 0 1px #1d2a40, 0 18px 50px rgba(0,0,0,.55);
}
.win {
  position: absolute; left: 0; top: 0; box-sizing: border-box;
  overflow: hidden;
}
.ctl { position: absolute; box-sizing: border-box; }
.ctl > .ch { z-index: 2; }
.ctl:hover { outline: 2px solid #ffc75e; outline-offset: -2px; z-index: 60; }
.group { border: 1px dashed transparent; }
.group.gbox, .group.ph, .group.flex, .group.repeater {
  border-color: rgba(120, 160, 220, .28);
  background: rgba(80, 110, 165, .08);
}
.badge {
  position: absolute; left: 2px; top: 2px; z-index: 6;
  font: 9px/1.2 ui-monospace, Menlo, monospace; color: #cfe3ff;
  background: rgba(8, 14, 26, .82); padding: 1px 4px; border-radius: 3px;
  pointer-events: none; max-width: 92%; overflow: hidden;
  text-overflow: ellipsis; white-space: nowrap;
}
.ctl > .badge { z-index: 7; }
body.no-badges .badge { display: none; }
.bgimg { background-repeat: no-repeat; background-position: center; }
.noimg, .bgimg.img-missing {
  background-color: rgba(48, 64, 92, .28) !important;
  background-image:
    repeating-linear-gradient(45deg, rgba(120,150,200,.12) 0 6px,
    rgba(40,55,82,.10) 6px 12px) !important;
  background-size: auto !important;
}
.noimg::after, .bgimg.img-missing::after {
  content: attr(data-img); position: absolute; inset: 0;
  display: flex; align-items: center; justify-content: center;
  color: #a9bcdc; font: 10px/1.3 ui-monospace, Menlo, monospace;
  text-align: center; padding: 2px; word-break: break-all;
  pointer-events: none; opacity: .9;
}
.noimg:not([data-img])::after,
.noimg[data-img=""]::after,
.bgimg.img-missing[data-img=""]::after { content: attr(data-id); }
.bgimg.img-missing::after { z-index: 1; opacity: .6; }
.btn {
  background-color: rgba(80, 110, 160, .22);
  border: 1px solid rgba(160, 195, 240, .45); border-radius: 8px;
  cursor: pointer;
}
.btn-text {
  position: absolute; inset: 0; display: flex; align-items: center;
  justify-content: center; color: #fff; font-weight: 600;
  text-shadow: 0 1px 2px #000; z-index: 3; padding: 2px;
  overflow: hidden; white-space: nowrap;
}
.lbl { overflow: hidden; }
.lbl > span { max-width: 100%; }
.pg {
  background: rgba(0, 0, 0, .35);
  border: 1px solid rgba(255, 255, 255, .25); border-radius: 3px;
  overflow: hidden;
}
.pg-fill {
  height: 100%; background: linear-gradient(180deg, #5fb0ff, #2d7dff);
}
.sl {
  background: rgba(0, 0, 0, .4);
  border: 1px solid rgba(255, 255, 255, .2); border-radius: 4px;
}
.sl-track {
  position: absolute; left: 6px; right: 6px; top: 50%; height: 4px;
  margin-top: -2px; background: #27405f; border-radius: 2px;
}
.sl-thumb {
  position: absolute; top: 50%; width: 12px; height: 16px;
  margin-top: -8px; transform: translateX(-50%);
  background: #9cc9ff; border: 1px solid #dff0ff; border-radius: 3px;
}
.eb, .tb {
  background: rgba(0, 0, 0, .25);
  border: 1px solid rgba(255, 255, 255, .3); border-radius: 4px;
  color: #dff0ff; padding: 4px; overflow: auto;
  font: 12px/1.4 ui-monospace, Menlo, monospace;
}
.tb-text { pointer-events: none; }
.anim {
  background: transparent;
  border: 1px dashed rgba(150, 190, 255, .5);
  animation: shimmer 2.2s linear infinite;
}
@keyframes shimmer {
  from { box-shadow: 0 0 0 rgba(120, 170, 255, .0); }
  50% { box-shadow: 0 0 18px rgba(120, 170, 255, .35); }
  to { box-shadow: 0 0 0 rgba(120, 170, 255, .0); }
}
.anim-tag {
  position: absolute; inset: 0; display: flex; align-items: center;
  justify-content: center; color: #cfe3ff;
  font: 10px ui-monospace, Menlo, monospace; z-index: 2;
}
.special {
  background: rgba(20, 30, 50, .7);
  border: 1px dashed rgba(255, 255, 255, .25);
}
.scl { overflow: auto; }
.scl-content { position: relative; }
.ph-note {
  position: absolute; inset: 0; display: flex; align-items: center;
  justify-content: center; color: var(--muted); font: 10px monospace;
  z-index: 2;
}
.stage-label {
  position: absolute; left: 8px; bottom: 6px; color: #5f7290;
  font: 10px ui-monospace, Menlo, monospace; z-index: 5;
  pointer-events: none;
}
"""


JS = r"""
const DATA = JSON.parse(document.getElementById("forms-data").textContent);
const ORDER = DATA.order;
const URLP = new URLSearchParams(location.search);
let cur = URLP.get("form") && DATA.forms[URLP.get("form")]
  ? URLP.get("form")
  : (ORDER.includes("form_main") ? "form_main" : ORDER[0]);
let zoom = 0.6;
const stage = document.getElementById("stage");
const wrap = document.getElementById("stage-wrap");
const listEl = document.getElementById("list");
const zoomEl = document.getElementById("zoom");
const zoomV = document.getElementById("zoomv");

const TYPE_NAME = { Form: "主界面", TmpWindow: "弹窗", Template: "模板" };

function curForm() { return DATA.forms[cur]; }

function setZoom(z) {
  zoom = Math.max(0.2, Math.min(2, z));
  stage.style.zoom = zoom;
  zoomEl.value = Math.round(zoom * 100);
  zoomV.textContent = Math.round(zoom * 100) + "%";
}

function fitZoom() {
  const f = curForm();
  const z = Math.min((wrap.clientWidth - 48) / f.stageW,
                     (wrap.clientHeight - 48) / f.stageH);
  setZoom(Math.max(0.2, Math.min(1.25, z)));
}

function show(id, refit) {
  if (!DATA.forms[id]) return;
  cur = id;
  const f = curForm();
  stage.style.width = f.stageW + "px";
  stage.style.height = f.stageH + "px";
  stage.innerHTML = f.html;
  if (refit) fitZoom(); else setZoom(zoom);
  document.querySelectorAll(".nav").forEach(n =>
    n.classList.toggle("on", n.dataset.target === cur));
  scanImages();
}

function imgFail(el) {
  const d = document.createElement("div");
  d.className = "ch ctl noimg";
  d.style.cssText = el.style.cssText;
  d.style.width = (el.dataset.w || 0) + "px";
  d.style.height = (el.dataset.h || 0) + "px";
  d.setAttribute("data-img", el.getAttribute("data-img") || "");
  d.setAttribute("data-id", el.getAttribute("data-id") || "");
  d.title = el.title;
  el.replaceWith(d);
}

const imgCache = {};
function scanImages() {
  stage.querySelectorAll(".bgimg").forEach(el => {
    const name = el.getAttribute("data-img");
    if (!name) return;
    if (imgCache[name] === false) {
      el.classList.add("img-missing");
      return;
    }
    if (imgCache[name] === true) return;
    const im = new Image();
    im.onload = () => { imgCache[name] = true; el.classList.remove("img-missing"); };
    im.onerror = () => { imgCache[name] = false; el.classList.add("img-missing"); };
    im.src = IMGURL(name);
  });
}
function IMGURL(name) {
  return DATA.meta.imgDir + "/" + name;
}

function buildNav() {
  listEl.innerHTML = "";
  let lastType = "";
  ORDER.forEach(id => {
    const f = DATA.forms[id];
    if (f.type !== lastType) {
      const h = document.createElement("div");
      h.className = "nav-head";
      const n = ORDER.filter(i => DATA.forms[i].type === f.type).length;
      h.textContent = TYPE_NAME[f.type] + " · " + n;
      h.style.cssText =
        "position:sticky;top:0;background:#121826;color:#8fa3c4;" +
        "font-size:11px;padding:8px 8px 3px;z-index:3";
      listEl.appendChild(h);
      lastType = f.type;
    }
    const b = document.createElement("button");
    b.className = "nav";
    b.dataset.target = id;
    const label = document.createElement("span");
    label.textContent = id;
    const t = document.createElement("span");
    t.className = "t";
    t.textContent = f.type;
    b.appendChild(label);
    b.appendChild(t);
    b.onclick = () => show(id, true);
    listEl.appendChild(b);
  });
}

const searchEl = document.getElementById("search");
searchEl.addEventListener("input", () => {
  const q = searchEl.value.trim().toLowerCase();
  document.querySelectorAll(".nav").forEach(n => {
    n.style.display = (!q || n.dataset.target.includes(q)) ? "" : "none";
  });
});

function step(d) {
  const i = ORDER.indexOf(cur);
  show(ORDER[(i + d + ORDER.length) % ORDER.length], true);
}
document.getElementById("prev").onclick = () => step(-1);
document.getElementById("next").onclick = () => step(1);
document.addEventListener("keydown", e => {
  if (e.target.tagName === "INPUT") return;
  if (e.key === "ArrowLeft") step(-1);
  if (e.key === "ArrowRight") step(1);
});

zoomEl.addEventListener("input", () => setZoom(zoomEl.value / 100));

const badgeBtn = document.getElementById("badgeToggle");
badgeBtn.onclick = () => {
  const off = document.body.classList.toggle("no-badges");
  badgeBtn.textContent = "控件名: " + (off ? "关" : "开");
};

window.addEventListener("resize", () => fitZoom());
buildNav();
show(cur, URLP.get("fit") !== "0");
if (URLP.get("debug")) {
  const r = el => {
    const b = el.getBoundingClientRect();
    return [Math.round(b.left), Math.round(b.top),
            Math.round(b.width), Math.round(b.height)];
  };
  const pick = ["gbox_modes", "btn_campaign", "btn_empire",
                "img_background", "group_facilities", "group_airport",
                "btn_close", "sclv_items", "rpt_generals"];
  const rects = {};
  pick.forEach(id => {
    const el = stage.querySelector(`[data-id="${id}"]`);
    if (el) {
      const cs = getComputedStyle(el);
      rects[id] = {
        rect: r(el),
        css: [cs.left, cs.top, cs.width, cs.height],
        parent: el.parentElement && el.parentElement.getAttribute("data-id"),
        offset: [el.offsetLeft, el.offsetTop]
      };
    }
  });
  document.title = JSON.stringify({
    zoom: zoom,
    stage: r(stage),
    wrap: r(wrap),
    rects: rects
  });
}
"""


if __name__ == "__main__":
    main()
