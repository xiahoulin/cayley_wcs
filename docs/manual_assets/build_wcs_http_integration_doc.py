# -*- coding: utf-8 -*-
"""生成《礁盘工业WCS系统对接文档_http版本.docx》。

事实源：
1. docs/open-api-guide.md
2. backend /open/**、application、binding、webhook 实现
3. frontend ApplicationView / BindingGrantView / WebhookView

运行：
    python build_wcs_http_integration_doc.py
"""
from __future__ import annotations

import json
import math
import textwrap
from pathlib import Path

from docx import Document
from docx.enum.section import WD_SECTION
from docx.enum.table import WD_ALIGN_VERTICAL, WD_CELL_VERTICAL_ALIGNMENT, WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_BREAK
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Inches, Pt, RGBColor
from PIL import Image, ImageDraw, ImageFont


DOCS = Path(__file__).resolve().parents[1]
RENDER_DIR = DOCS / "manual_assets" / "rendered"
RENDER_DIR.mkdir(parents=True, exist_ok=True)
OUTPUT = DOCS / "礁盘工业WCS系统对接文档_http版本.docx"

BODY_FONT = "Microsoft YaHei"
MONO_FONT = "Consolas"
BLUE = RGBColor(46, 116, 181)
DARK_BLUE = RGBColor(31, 77, 120)
NAVY = RGBColor(11, 37, 69)
TEXT = RGBColor(31, 41, 55)
MUTED = RGBColor(75, 85, 99)
WHITE = RGBColor(255, 255, 255)
ORANGE = RGBColor(245, 124, 0)
GREEN = RGBColor(31, 157, 85)
RED = RGBColor(185, 28, 28)

# compact_reference_guide preset
PAGE_W_DXA = 12240
PAGE_H_DXA = 15840
MARGIN_DXA = 1440
TABLE_W_DXA = 9360
TABLE_INDENT_DXA = 120
TABLE_CELL_TB_DXA = 80
TABLE_CELL_LR_DXA = 120


# ---------- OOXML helpers ----------
def set_cell_shading(cell, fill: str) -> None:
    tc_pr = cell._tc.get_or_add_tcPr()
    shd = tc_pr.find(qn("w:shd"))
    if shd is None:
        shd = OxmlElement("w:shd")
        tc_pr.append(shd)
    shd.set(qn("w:fill"), fill)


def set_cell_margins(cell, top=TABLE_CELL_TB_DXA, start=TABLE_CELL_LR_DXA,
                     bottom=TABLE_CELL_TB_DXA, end=TABLE_CELL_LR_DXA) -> None:
    tc = cell._tc
    tc_pr = tc.get_or_add_tcPr()
    tc_mar = tc_pr.first_child_found_in("w:tcMar")
    if tc_mar is None:
        tc_mar = OxmlElement("w:tcMar")
        tc_pr.append(tc_mar)
    for m, v in (("top", top), ("start", start), ("bottom", bottom), ("end", end)):
        node = tc_mar.find(qn(f"w:{m}"))
        if node is None:
            node = OxmlElement(f"w:{m}")
            tc_mar.append(node)
        node.set(qn("w:w"), str(v))
        node.set(qn("w:type"), "dxa")


def set_cell_width(cell, width_dxa: int) -> None:
    tc_pr = cell._tc.get_or_add_tcPr()
    tc_w = tc_pr.find(qn("w:tcW"))
    if tc_w is None:
        tc_w = OxmlElement("w:tcW")
        tc_pr.append(tc_w)
    tc_w.set(qn("w:w"), str(width_dxa))
    tc_w.set(qn("w:type"), "dxa")


def set_table_borders(table, color="C9D4E0", size=4) -> None:
    tbl_pr = table._tbl.tblPr
    borders = tbl_pr.find(qn("w:tblBorders"))
    if borders is None:
        borders = OxmlElement("w:tblBorders")
        tbl_pr.append(borders)
    for edge in ("top", "start", "bottom", "end", "insideH", "insideV"):
        el = borders.find(qn(f"w:{edge}"))
        if el is None:
            el = OxmlElement(f"w:{edge}")
            borders.append(el)
        el.set(qn("w:val"), "single")
        el.set(qn("w:sz"), str(size))
        el.set(qn("w:space"), "0")
        el.set(qn("w:color"), color)


def remove_table_borders(table) -> None:
    tbl_pr = table._tbl.tblPr
    borders = tbl_pr.find(qn("w:tblBorders"))
    if borders is None:
        borders = OxmlElement("w:tblBorders")
        tbl_pr.append(borders)
    for edge in ("top", "start", "bottom", "end", "insideH", "insideV"):
        el = borders.find(qn(f"w:{edge}"))
        if el is None:
            el = OxmlElement(f"w:{edge}")
            borders.append(el)
        el.set(qn("w:val"), "nil")


def set_table_layout(table, widths: list[int], indent=TABLE_INDENT_DXA) -> None:
    if sum(widths) != TABLE_W_DXA:
        raise ValueError(f"table widths must sum to {TABLE_W_DXA}: {widths}")
    tbl = table._tbl
    tbl_pr = tbl.tblPr
    tbl_w = tbl_pr.find(qn("w:tblW"))
    if tbl_w is None:
        tbl_w = OxmlElement("w:tblW")
        tbl_pr.append(tbl_w)
    tbl_w.set(qn("w:w"), str(TABLE_W_DXA))
    tbl_w.set(qn("w:type"), "dxa")
    tbl_ind = tbl_pr.find(qn("w:tblInd"))
    if tbl_ind is None:
        tbl_ind = OxmlElement("w:tblInd")
        tbl_pr.append(tbl_ind)
    tbl_ind.set(qn("w:w"), str(indent))
    tbl_ind.set(qn("w:type"), "dxa")
    layout = tbl_pr.find(qn("w:tblLayout"))
    if layout is None:
        layout = OxmlElement("w:tblLayout")
        tbl_pr.append(layout)
    layout.set(qn("w:type"), "fixed")

    grid = tbl.tblGrid
    for child in list(grid):
        grid.remove(child)
    for w in widths:
        grid_col = OxmlElement("w:gridCol")
        grid_col.set(qn("w:w"), str(w))
        grid.append(grid_col)

    for row in table.rows:
        for idx, cell in enumerate(row.cells):
            set_cell_width(cell, widths[idx])
            set_cell_margins(cell)


def repeat_header(row) -> None:
    tr_pr = row._tr.get_or_add_trPr()
    tbl_header = OxmlElement("w:tblHeader")
    tbl_header.set(qn("w:val"), "true")
    tr_pr.append(tbl_header)


def keep_row_together(row) -> None:
    tr_pr = row._tr.get_or_add_trPr()
    cant_split = OxmlElement("w:cantSplit")
    tr_pr.append(cant_split)


def set_repeat_table_header(row) -> None:
    repeat_header(row)
    keep_row_together(row)


def set_run_font(run, name=BODY_FONT, size=11, bold=False, color=TEXT,
                 italic=False) -> None:
    run.font.name = name
    run._element.get_or_add_rPr().rFonts.set(qn("w:eastAsia"), BODY_FONT)
    run.font.size = Pt(size)
    run.font.bold = bold
    run.font.italic = italic
    run.font.color.rgb = color


def set_paragraph_border(paragraph, side="start", color="2E74B5", size=18, space=6) -> None:
    p_pr = paragraph._p.get_or_add_pPr()
    p_bdr = p_pr.find(qn("w:pBdr"))
    if p_bdr is None:
        p_bdr = OxmlElement("w:pBdr")
        p_pr.append(p_bdr)
    border = OxmlElement(f"w:{side}")
    border.set(qn("w:val"), "single")
    border.set(qn("w:sz"), str(size))
    border.set(qn("w:space"), str(space))
    border.set(qn("w:color"), color)
    p_bdr.append(border)


def add_page_field(paragraph) -> None:
    paragraph.add_run("第 ")
    fld_begin = OxmlElement("w:fldChar")
    fld_begin.set(qn("w:fldCharType"), "begin")
    instr = OxmlElement("w:instrText")
    instr.set(qn("xml:space"), "preserve")
    instr.text = " PAGE "
    fld_sep = OxmlElement("w:fldChar")
    fld_sep.set(qn("w:fldCharType"), "separate")
    fld_end = OxmlElement("w:fldChar")
    fld_end.set(qn("w:fldCharType"), "end")
    run = paragraph.add_run()
    run._r.append(fld_begin)
    run._r.append(instr)
    run._r.append(fld_sep)
    run._r.append(fld_end)
    paragraph.add_run(" 页")


def set_keep_with_next(paragraph, value=True) -> None:
    p_pr = paragraph._p.get_or_add_pPr()
    node = p_pr.find(qn("w:keepNext"))
    if node is None:
        node = OxmlElement("w:keepNext")
        p_pr.append(node)
    node.set(qn("w:val"), "1" if value else "0")


# ---------- document components ----------
def add_para(doc, text="", *, style=None, bold=False, color=TEXT, size=11,
             align=None, italic=False, before=0, after=6, line=1.25,
             keep_next=False):
    p = doc.add_paragraph(style=style)
    if align is not None:
        p.alignment = align
    p.paragraph_format.space_before = Pt(before)
    p.paragraph_format.space_after = Pt(after)
    p.paragraph_format.line_spacing = line
    if text:
        r = p.add_run(text)
        set_run_font(r, size=size, bold=bold, color=color, italic=italic)
    if keep_next:
        set_keep_with_next(p)
    return p


def add_rich_para(doc, parts, *, style=None, align=None, before=0, after=6, line=1.25):
    p = doc.add_paragraph(style=style)
    if align is not None:
        p.alignment = align
    p.paragraph_format.space_before = Pt(before)
    p.paragraph_format.space_after = Pt(after)
    p.paragraph_format.line_spacing = line
    for part in parts:
        if isinstance(part, str):
            part = {"text": part}
        r = p.add_run(part.get("text", ""))
        set_run_font(
            r,
            name=part.get("font", BODY_FONT),
            size=part.get("size", 11),
            bold=part.get("bold", False),
            color=part.get("color", TEXT),
            italic=part.get("italic", False),
        )
    return p


def add_heading(doc, text, level=1):
    p = doc.add_heading("", level=level)
    p.paragraph_format.keep_with_next = True
    if level == 1:
        size, color, before, after = 16, BLUE, 18, 10
    elif level == 2:
        size, color, before, after = 13, BLUE, 14, 7
    else:
        size, color, before, after = 12, DARK_BLUE, 10, 5
    p.paragraph_format.space_before = Pt(before)
    p.paragraph_format.space_after = Pt(after)
    p.paragraph_format.line_spacing = 1.15
    r = p.add_run(text)
    set_run_font(r, size=size, bold=True, color=color)
    return p


def add_bullet(doc, text, level=0):
    p = doc.add_paragraph(style="List Bullet" if level == 0 else "List Bullet 2")
    p.paragraph_format.space_after = Pt(4)
    p.paragraph_format.line_spacing = 1.25
    r = p.add_run(text)
    set_run_font(r)
    return p


def add_number(doc, text, level=0):
    p = doc.add_paragraph(style="List Number" if level == 0 else "List Number 2")
    p.paragraph_format.space_after = Pt(4)
    p.paragraph_format.line_spacing = 1.25
    r = p.add_run(text)
    set_run_font(r)
    return p


def _new_decimal_numbering(doc: Document) -> int:
    """Create a real single-level Word numbering definition that starts at 1."""
    numbering = doc.part.numbering_part.element
    abstract_ids = [
        int(node.get(qn("w:abstractNumId")))
        for node in numbering.findall(qn("w:abstractNum"))
        if node.get(qn("w:abstractNumId")) is not None
    ]
    num_ids = [
        int(node.get(qn("w:numId")))
        for node in numbering.findall(qn("w:num"))
        if node.get(qn("w:numId")) is not None
    ]
    abstract_id = max(abstract_ids or [0]) + 1
    num_id = max(num_ids or [0]) + 1

    abstract = OxmlElement("w:abstractNum")
    abstract.set(qn("w:abstractNumId"), str(abstract_id))
    nsid = OxmlElement("w:nsid")
    nsid.set(qn("w:val"), f"{abstract_id:08X}"[-8:])
    abstract.append(nsid)
    multi = OxmlElement("w:multiLevelType")
    multi.set(qn("w:val"), "singleLevel")
    abstract.append(multi)
    lvl = OxmlElement("w:lvl")
    lvl.set(qn("w:ilvl"), "0")
    start = OxmlElement("w:start")
    start.set(qn("w:val"), "1")
    lvl.append(start)
    num_fmt = OxmlElement("w:numFmt")
    num_fmt.set(qn("w:val"), "decimal")
    lvl.append(num_fmt)
    lvl_text = OxmlElement("w:lvlText")
    lvl_text.set(qn("w:val"), "%1.")
    lvl.append(lvl_text)
    lvl_jc = OxmlElement("w:lvlJc")
    lvl_jc.set(qn("w:val"), "left")
    lvl.append(lvl_jc)
    p_pr = OxmlElement("w:pPr")
    tabs = OxmlElement("w:tabs")
    tab = OxmlElement("w:tab")
    tab.set(qn("w:val"), "num")
    tab.set(qn("w:pos"), "270")
    tabs.append(tab)
    p_pr.append(tabs)
    ind = OxmlElement("w:ind")
    ind.set(qn("w:left"), "540")
    ind.set(qn("w:hanging"), "270")
    p_pr.append(ind)
    lvl.append(p_pr)
    abstract.append(lvl)
    numbering.append(abstract)

    num = OxmlElement("w:num")
    num.set(qn("w:numId"), str(num_id))
    abstract_ref = OxmlElement("w:abstractNumId")
    abstract_ref.set(qn("w:val"), str(abstract_id))
    num.append(abstract_ref)
    # Word may otherwise merge visually adjacent procedures even when their
    # abstract numbering definitions differ. An explicit level override makes
    # the restart contract unambiguous to Word and assistive technologies.
    lvl_override = OxmlElement("w:lvlOverride")
    lvl_override.set(qn("w:ilvl"), "0")
    start_override = OxmlElement("w:startOverride")
    start_override.set(qn("w:val"), "1")
    lvl_override.append(start_override)
    num.append(lvl_override)
    numbering.append(num)
    return num_id


def add_numbered_items(doc: Document, items: list[str]) -> None:
    """Add one independently restarting numbered procedure."""
    num_id = _new_decimal_numbering(doc)
    for text in items:
        p = doc.add_paragraph()
        p.paragraph_format.space_after = Pt(4)
        p.paragraph_format.line_spacing = 1.25
        p_pr = p._p.get_or_add_pPr()
        num_pr = OxmlElement("w:numPr")
        ilvl = OxmlElement("w:ilvl")
        ilvl.set(qn("w:val"), "0")
        num_id_node = OxmlElement("w:numId")
        num_id_node.set(qn("w:val"), str(num_id))
        num_pr.append(ilvl)
        num_pr.append(num_id_node)
        p_pr.append(num_pr)
        set_run_font(p.add_run(text))


def add_callout(doc, title, text, kind="tip"):
    palette = {
        "tip": ("E8F1FB", BLUE, "提示"),
        "warn": ("FFF4E5", ORANGE, "注意"),
        "danger": ("FDECEC", RED, "风险"),
        "ok": ("E7F4EC", GREEN, "建议"),
    }
    fill, accent, default_title = palette[kind]
    t = doc.add_table(rows=1, cols=1)
    t.alignment = WD_TABLE_ALIGNMENT.CENTER
    set_table_layout(t, [TABLE_W_DXA], indent=TABLE_INDENT_DXA)
    remove_table_borders(t)
    cell = t.cell(0, 0)
    set_cell_shading(cell, fill)
    set_cell_margins(cell, top=120, bottom=120, start=180, end=180)
    p = cell.paragraphs[0]
    p.paragraph_format.space_after = Pt(3)
    p.paragraph_format.line_spacing = 1.2
    r = p.add_run(f"{title or default_title}  ")
    set_run_font(r, size=10.5, bold=True, color=accent)
    r = p.add_run(text)
    set_run_font(r, size=10.5, color=TEXT)
    accent_hex = "".join(f"{channel:02X}" for channel in accent)
    set_paragraph_border(p, "start", accent_hex, 20, 8)
    add_para(doc, "", size=1, after=2)
    return t


def add_table(doc, headers, rows, widths=None, header_fill="E8EEF5",
              first_col_bold=False, font_size=9.5):
    cols = len(headers)
    if widths is None:
        base = TABLE_W_DXA // cols
        widths = [base] * cols
        widths[-1] += TABLE_W_DXA - sum(widths)
    table = doc.add_table(rows=1, cols=cols)
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    table.autofit = False
    set_table_layout(table, widths)
    set_table_borders(table)
    hdr = table.rows[0]
    set_repeat_table_header(hdr)
    for idx, label in enumerate(headers):
        cell = hdr.cells[idx]
        set_cell_shading(cell, header_fill)
        cell.vertical_alignment = WD_CELL_VERTICAL_ALIGNMENT.CENTER
        p = cell.paragraphs[0]
        p.alignment = WD_ALIGN_PARAGRAPH.CENTER
        p.paragraph_format.space_after = Pt(0)
        p.paragraph_format.line_spacing = 1.1
        r = p.add_run(str(label))
        set_run_font(r, size=font_size, bold=True, color=DARK_BLUE)
    for row_data in rows:
        row = table.add_row()
        keep_row_together(row)
        for idx, value in enumerate(row_data):
            cell = row.cells[idx]
            cell.vertical_alignment = WD_ALIGN_VERTICAL.CENTER
            p = cell.paragraphs[0]
            p.paragraph_format.space_after = Pt(0)
            p.paragraph_format.line_spacing = 1.15
            r = p.add_run("" if value is None else str(value))
            set_run_font(r, size=font_size, bold=(first_col_bold and idx == 0), color=TEXT)
    add_para(doc, "", size=1, after=2)
    return table


def add_code(doc, code, language=""):
    t = doc.add_table(rows=1, cols=1)
    t.alignment = WD_TABLE_ALIGNMENT.CENTER
    t.autofit = False
    set_table_layout(t, [TABLE_W_DXA])
    set_table_borders(t, color="D5DCE5", size=4)
    keep_row_together(t.rows[0])
    cell = t.cell(0, 0)
    set_cell_shading(cell, "F6F8FA")
    set_cell_margins(cell, top=130, bottom=130, start=160, end=160)
    p = cell.paragraphs[0]
    p.paragraph_format.space_after = Pt(0)
    p.paragraph_format.line_spacing = 1.0
    if language:
        r = p.add_run(language.upper() + "\n")
        set_run_font(r, name=MONO_FONT, size=7.5, bold=True, color=MUTED)
    r = p.add_run(code.strip())
    set_run_font(r, name=MONO_FONT, size=8.2, color=NAVY)
    add_para(doc, "", size=1, after=3)
    return t


def add_picture(doc, path: Path, caption: str, width=6.35):
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.paragraph_format.space_after = Pt(3)
    run = p.add_run()
    run.add_picture(str(path), width=Inches(width))
    cap = doc.add_paragraph()
    cap.alignment = WD_ALIGN_PARAGRAPH.CENTER
    cap.paragraph_format.space_after = Pt(8)
    r = cap.add_run(caption)
    set_run_font(r, size=9, color=MUTED, italic=True)


def add_page_break(doc):
    # A standalone run-level page break can spill to an otherwise empty page
    # when the preceding content exactly fills a page. Starting a zero-height
    # paragraph on the next page is stable in Word and LibreOffice.
    p = doc.add_paragraph()
    p.paragraph_format.page_break_before = True
    p.paragraph_format.space_before = Pt(0)
    p.paragraph_format.space_after = Pt(0)
    p.paragraph_format.line_spacing = Pt(1)


def configure_document(doc: Document) -> None:
    section = doc.sections[0]
    section.page_width = Inches(8.5)
    section.page_height = Inches(11)
    section.top_margin = Inches(1.0)
    section.bottom_margin = Inches(1.0)
    section.left_margin = Inches(1.0)
    section.right_margin = Inches(1.0)
    section.header_distance = Inches(0.492)
    section.footer_distance = Inches(0.492)

    styles = doc.styles
    normal = styles["Normal"]
    normal.font.name = BODY_FONT
    normal._element.rPr.rFonts.set(qn("w:eastAsia"), BODY_FONT)
    normal.font.size = Pt(11)
    normal.font.color.rgb = TEXT
    normal.paragraph_format.alignment = WD_ALIGN_PARAGRAPH.LEFT
    normal.paragraph_format.space_after = Pt(6)
    normal.paragraph_format.line_spacing = 1.25

    for style_name in ("List Bullet", "List Bullet 2", "List Number", "List Number 2"):
        style = styles[style_name]
        style.font.name = BODY_FONT
        style._element.rPr.rFonts.set(qn("w:eastAsia"), BODY_FONT)
        style.font.size = Pt(11)
        style.paragraph_format.space_after = Pt(4)
        style.paragraph_format.line_spacing = 1.25
    for style_name in ("List Bullet", "List Number"):
        styles[style_name].paragraph_format.left_indent = Inches(0.375)
        styles[style_name].paragraph_format.first_line_indent = Inches(-0.188)
    for style_name in ("List Bullet 2", "List Number 2"):
        styles[style_name].paragraph_format.left_indent = Inches(0.625)
        styles[style_name].paragraph_format.first_line_indent = Inches(-0.188)

    # Quiet customer-pack running header/footer; no header rule.
    header = section.header
    hp = header.paragraphs[0]
    hp.alignment = WD_ALIGN_PARAGRAPH.LEFT
    hp.paragraph_format.space_after = Pt(0)
    r = hp.add_run("礁盘工业 WCS  ·  HTTP 系统对接")
    set_run_font(r, size=8.5, color=MUTED, bold=True)
    tab_stops = hp.paragraph_format.tab_stops
    tab_stops.add_tab_stop(Inches(6.45))
    r = hp.add_run("\tV1.0")
    set_run_font(r, size=8.5, color=MUTED)

    footer = section.footer
    fp = footer.paragraphs[0]
    fp.alignment = WD_ALIGN_PARAGRAPH.RIGHT
    fp.paragraph_format.space_before = Pt(0)
    fp.paragraph_format.space_after = Pt(0)
    add_page_field(fp)
    for run in fp.runs:
        set_run_font(run, size=8.5, color=MUTED)

    props = doc.core_properties
    props.title = "礁盘工业WCS系统对接文档_http版本"
    props.subject = "外部系统使用已发放 AppKey 对接码垛机、Webhook 与 REST 对账说明"
    props.author = "凯利智飞"
    props.keywords = "WCS, HTTP API, Webhook, HMAC, AppKey, WMS"
    props.comments = "依据 2026-07-23 仓库实现核对生成"


# ---------- diagrams ----------
C_BG = "#F5F7FA"
C_NAVY = "#0B2545"
C_BLUE = "#2E74B5"
C_BLUE_L = "#E8F1FB"
C_ORANGE = "#F57C00"
C_ORANGE_L = "#FFF0DF"
C_GREEN = "#1F9D55"
C_GREEN_L = "#E7F4EC"
C_RED = "#B91C1C"
C_RED_L = "#FDECEC"
C_GRAY = "#6B7280"
C_BORDER = "#C9D4E0"
C_WHITE = "#FFFFFF"


def pil_font(size, bold=False):
    candidates = []
    if bold:
        candidates.extend(["C:/Windows/Fonts/msyhbd.ttc", "C:/Windows/Fonts/simhei.ttf"])
    candidates.extend(["C:/Windows/Fonts/msyh.ttc", "C:/Windows/Fonts/simhei.ttf"])
    for item in candidates:
        try:
            return ImageFont.truetype(item, size)
        except OSError:
            pass
    return ImageFont.load_default()


def draw_center(draw, x, y, text, font, fill=C_NAVY):
    box = draw.textbbox((0, 0), text, font=font)
    draw.text((x - (box[2] - box[0]) / 2, y - (box[3] - box[1]) / 2),
              text, font=font, fill=fill)


def draw_box(draw, x, y, w, h, title, lines=(), fill=C_WHITE, border=C_BLUE,
             title_color=C_NAVY):
    draw.rounded_rectangle([x, y, x + w, y + h], radius=14, fill=fill,
                           outline=border, width=3)
    draw_center(draw, x + w / 2, y + 27, title, pil_font(21, True), title_color)
    yy = y + 57
    for line in lines:
        draw_center(draw, x + w / 2, yy, line, pil_font(15), C_GRAY)
        yy += 24


def draw_arrow(draw, x1, y1, x2, y2, color=C_GRAY, width=4):
    draw.line([x1, y1, x2, y2], fill=color, width=width)
    ang = math.atan2(y2 - y1, x2 - x1)
    size = 13
    pts = [
        (x2, y2),
        (x2 - size * math.cos(ang - 0.55), y2 - size * math.sin(ang - 0.55)),
        (x2 - size * math.cos(ang + 0.55), y2 - size * math.sin(ang + 0.55)),
    ]
    draw.polygon(pts, fill=color)


def render_integration_diagram():
    w, h = 1640, 720
    img = Image.new("RGB", (w, h), C_BG)
    d = ImageDraw.Draw(img)
    draw_center(d, w / 2, 40, "HTTP 对接整体关系", pil_font(32, True), C_NAVY)
    draw_center(d, w / 2, 78, "上位业务系统下发任务，WCS 负责编排与设备执行；Webhook 主动回告，REST 负责最终对账",
                pil_font(17), C_GRAY)
    draw_box(d, 70, 180, 300, 170, "WMS / ERP / MES",
             ("业务单据与任务号", "保存 AppKey / Secret", "接收 Webhook"), C_ORANGE_L, C_ORANGE)
    draw_box(d, 465, 180, 280, 170, "Cayley Gateway",
             ("可选统一入口", "/api/wcs/open/**", "重写至 /open/**"), C_WHITE, C_BORDER)
    draw_box(d, 840, 150, 360, 230, "礁盘工业 WCS",
             ("HMAC v2 鉴权", "授权校验 / 任务编排", "状态、报警、Webhook"), C_BLUE_L, C_BLUE)
    draw_box(d, 1300, 180, 280, 170, "PLC / 设备",
             ("堆垛机 / 输送线", "提升机 / 自动门", "OPC UA / Modbus / …"), C_GREEN_L, C_GREEN)

    draw_arrow(d, 370, 240, 465, 240, C_ORANGE)
    draw_arrow(d, 745, 240, 840, 240, C_BLUE)
    draw_arrow(d, 1200, 240, 1300, 240, C_GREEN)
    draw_center(d, 420, 218, "HTTPS", pil_font(14, True), C_ORANGE)
    draw_center(d, 790, 218, "HMAC", pil_font(14, True), C_BLUE)
    draw_center(d, 1250, 218, "设备协议", pil_font(14, True), C_GREEN)

    # Webhook reverse line
    d.rounded_rectangle([165, 470, 1100, 610], radius=18, fill=C_WHITE,
                        outline=C_BORDER, width=2)
    draw_center(d, 635, 498, "闭环通道", pil_font(20, True), C_NAVY)
    draw_arrow(d, 1030, 545, 260, 545, C_ORANGE, 4)
    draw_center(d, 645, 527, "Webhook：关键终态，至少一次投递", pil_font(16), C_ORANGE)
    draw_arrow(d, 260, 585, 1030, 585, C_BLUE, 4)
    draw_center(d, 645, 568, "REST query / snapshot：断线恢复与最终一致性", pil_font(16), C_BLUE)
    draw_center(d, w / 2, 670,
                "责任边界：上位系统决定“做什么”；WCS 决定“如何安全地下发并跟踪设备执行”",
                pil_font(17, True), C_NAVY)
    path = RENDER_DIR / "wcs-http-integration.png"
    img.save(path)
    return path


def render_config_diagram():
    w, h = 1640, 660
    img = Image.new("RGB", (w, h), C_WHITE)
    d = ImageDraw.Draw(img)
    draw_center(d, w / 2, 40, "管理员配置顺序：一条线走完", pil_font(30, True), C_NAVY)
    labels = [
        ("1", "登录管理端", "仅管理员操作", C_BLUE_L, C_BLUE),
        ("2", "创建上位应用", "direction=upstream", C_BLUE_L, C_BLUE),
        ("3", "安全交付凭据", "AppKey + Secret", C_ORANGE_L, C_ORANGE),
        ("4", "绑定授权设备", "上位 → 下位", C_GREEN_L, C_GREEN),
        ("5", "强制签名 v2", "min_sign_version=2", C_BLUE_L, C_BLUE),
        ("6", "配置 Webhook", "URL / Secret / 事件", C_ORANGE_L, C_ORANGE),
        ("7", "按清单验收", "幂等 / 重试 / 对账", C_GREEN_L, C_GREEN),
    ]
    x = 40
    y = 190
    box_w = 200
    gap = 28
    for idx, (num, title, sub, fill, border) in enumerate(labels):
        d.rounded_rectangle([x, y, x + box_w, y + 190], radius=18, fill=fill,
                            outline=border, width=3)
        d.ellipse([x + 72, y - 40, x + 128, y + 16], fill=border)
        draw_center(d, x + 100, y - 12, num, pil_font(25, True), C_WHITE)
        draw_center(d, x + 100, y + 65, title, pil_font(20, True), C_NAVY)
        draw_center(d, x + 100, y + 110, sub, pil_font(14), C_GRAY)
        if idx < len(labels) - 1:
            draw_arrow(d, x + box_w, y + 95, x + box_w + gap - 3, y + 95,
                       C_GRAY, 3)
        x += box_w + gap
    d.rounded_rectangle([185, 485, 1455, 575], radius=14, fill=C_BG,
                        outline=C_BORDER, width=2)
    draw_center(d, w / 2, 515,
                "最容易漏掉的是第 4 步：应用创建成功不等于拥有设备权限。",
                pil_font(18, True), C_RED)
    draw_center(d, w / 2, 548,
                "如果 dispatch 返回 403，先检查绑定授权，不要反复重置 Secret。",
                pil_font(16), C_GRAY)
    path = RENDER_DIR / "wcs-http-config-flow.png"
    img.save(path)
    return path


def render_sign_diagram():
    w, h = 1640, 780
    img = Image.new("RGB", (w, h), C_BG)
    d = ImageDraw.Draw(img)
    draw_center(d, w / 2, 40, "HMAC v2 签名：签的是“最终发送的字节”", pil_font(30, True), C_NAVY)
    steps = [
        (95, 170, 320, 155, "① 固定 JSON 字节", ("UTF-8", "计算后不再格式化"), C_ORANGE_L, C_ORANGE),
        (470, 170, 300, 155, "② SHA-256", ("bodyHash", "小写 hex"), C_BLUE_L, C_BLUE),
        (920, 135, 360, 225, "③ 规范串 canonical", ("AppKey", "timestamp / nonce", "POST / backendPath", "bodyHash"), C_WHITE, C_BLUE),
        (1350, 170, 230, 155, "④ HMAC", ("key=Secret", "X-Sign"), C_GREEN_L, C_GREEN),
    ]
    for x, y, bw, bh, title, lines, fill, border in steps:
        draw_box(d, x, y, bw, bh, title, lines, fill, border)
    draw_arrow(d, 415, 247, 470, 247, C_ORANGE)
    draw_arrow(d, 770, 247, 920, 247, C_BLUE)
    draw_arrow(d, 1280, 247, 1350, 247, C_GREEN)

    d.rounded_rectangle([140, 470, 1500, 680], radius=18, fill=C_WHITE,
                        outline=C_BORDER, width=2)
    draw_center(d, 820, 505, "路径规则", pil_font(21, True), C_NAVY)
    draw_center(d, 460, 555, "直连 URL", pil_font(16, True), C_BLUE)
    draw_center(d, 460, 590, "http://wcs:20021/open/task/dispatch", pil_font(15), C_GRAY)
    draw_center(d, 1180, 555, "经网关 URL", pil_font(16, True), C_ORANGE)
    draw_center(d, 1180, 590, "https://gateway/api/wcs/open/task/dispatch", pil_font(15), C_GRAY)
    draw_center(d, 820, 635, "两种方式的 canonicalPath 都是 /open/task/dispatch",
                pil_font(18, True), C_RED)
    path = RENDER_DIR / "wcs-http-hmac-v2.png"
    img.save(path)
    return path


def render_webhook_diagram():
    w, h = 1640, 720
    img = Image.new("RGB", (w, h), C_WHITE)
    d = ImageDraw.Draw(img)
    draw_center(d, w / 2, 40, "Webhook 交付与补偿闭环", pil_font(30, True), C_NAVY)
    draw_box(d, 70, 170, 270, 170, "WCS 状态变化",
             ("任务 / 链 / 报警", "扫描滞后默认 2 秒"), C_BLUE_L, C_BLUE)
    draw_box(d, 430, 170, 290, 170, "投递记录",
             ("唯一键去重", "pending / ok / failed"), C_WHITE, C_BORDER)
    draw_box(d, 810, 170, 300, 170, "签名并 POST",
             ("毫秒时间戳", "不跟随重定向"), C_ORANGE_L, C_ORANGE)
    draw_box(d, 1220, 140, 350, 230, "接收方",
             ("原始 body 验签", "event + bizId 幂等", "业务成功返回 2xx"), C_GREEN_L, C_GREEN)
    draw_arrow(d, 340, 255, 430, 255, C_BLUE)
    draw_arrow(d, 720, 255, 810, 255, C_ORANGE)
    draw_arrow(d, 1110, 255, 1220, 255, C_GREEN)

    d.rounded_rectangle([230, 470, 1410, 635], radius=18, fill=C_BG,
                        outline=C_BORDER, width=2)
    draw_arrow(d, 1310, 515, 470, 515, C_RED)
    draw_center(d, 890, 495, "非 2xx / 网络异常：指数退避，默认总计最多 6 次", pil_font(16, True), C_RED)
    draw_arrow(d, 470, 585, 1310, 585, C_BLUE)
    draw_center(d, 890, 565, "无论 Webhook 是否成功，REST 增量查询仍负责最终对账", pil_font(16, True), C_BLUE)
    path = RENDER_DIR / "wcs-http-webhook-flow.png"
    img.save(path)
    return path


# ---------- content ----------
def build_document():
    diagrams = {
        "integration": render_integration_diagram(),
        "config": render_config_diagram(),
        "sign": render_sign_diagram(),
        "webhook": render_webhook_diagram(),
    }

    doc = Document()
    configure_document(doc)

    # Cover: customer_pack pattern
    add_para(doc, "第三方系统接入说明", size=11, bold=True, color=ORANGE,
             before=36, after=18, keep_next=True)
    p = add_para(doc, "", after=6)
    set_paragraph_border(p, "start", "2E74B5", 42, 14)
    r = p.add_run("礁盘工业WCS系统\n对接文档")
    set_run_font(r, size=28, bold=True, color=NAVY)
    add_para(doc, "HTTP 版本", size=18, bold=True, color=BLUE, after=8)
    add_para(doc, "面向 WMS / ERP / MES / 第三方调度系统", size=12.5,
             color=MUTED, after=28)
    add_callout(
        doc,
        "先说结论",
        "这份文档从“WCS 管理员如何把应用配置好”开始，一直讲到“调用方如何签名、下发任务、接收 Webhook、断线后如何对账”。第一次接入也可以从第 1 章按顺序往下做。",
        "tip",
    )
    metadata = [
        ("文档版本", "V1.0"),
        ("实现核对日期", "2026-07-23"),
        ("适用系统", "礁盘工业 WCS（CayleyWCS）"),
        ("默认后端端口", "20021"),
        ("开放接口前缀", "/open/**"),
        ("机器可读契约", "/v3/api-docs/open"),
    ]
    t = doc.add_table(rows=len(metadata), cols=2)
    set_table_layout(t, [2100, 7260], indent=TABLE_INDENT_DXA)
    remove_table_borders(t)
    for idx, (k, v) in enumerate(metadata):
        c0, c1 = t.rows[idx].cells
        for c in (c0, c1):
            set_cell_margins(c, top=70, bottom=70, start=80, end=80)
        p0, p1 = c0.paragraphs[0], c1.paragraphs[0]
        p0.paragraph_format.space_after = Pt(0)
        p1.paragraph_format.space_after = Pt(0)
        set_run_font(p0.add_run(k), size=9.5, bold=True, color=MUTED)
        set_run_font(p1.add_run(v), size=9.5, color=TEXT)
    add_para(doc, "", after=10)
    add_para(doc, "凯利智飞 · CayleyWCS", size=10, bold=True, color=NAVY)
    add_para(doc, "本文中的账号、域名、AppKey、Secret、应用 ID 均为示例，请替换为项目实际值。",
             size=9, color=MUTED, italic=True)
    add_page_break(doc)

    add_heading(doc, "阅读导航", 1)
    add_para(doc, "如果你时间不多，可以先根据角色找到最短阅读路径；需要排障时再回到对应章节查细节。")
    add_table(
        doc,
        ["你是谁", "建议先看", "看完能做什么"],
        [
            ("WCS 管理员 / 实施", "第 2～5、11、16 章", "配置应用、授权、Webhook 和生产安全参数"),
            ("WMS / ERP 开发", "第 1、6～13 章", "完成 HMAC v2、接口调用、幂等和对账"),
            ("测试 / 验收", "第 14～15 章", "按正向、异常、重试、断线场景验收"),
            ("运维 / 安全", "第 11、13、16 章", "配置白名单、密钥轮换、告警和排障"),
        ],
        widths=[2100, 2500, 4760],
        first_col_bold=True,
    )
    add_heading(doc, "目录", 2)
    toc_rows = [
        ("1", "五分钟接入路线"),
        ("2", "整体架构与责任边界"),
        ("3", "环境、地址与接入前检查"),
        ("4", "配置调用方应用"),
        ("5", "配置设备绑定授权"),
        ("6", "HTTP 通用约定"),
        ("7", "AppKey / HMAC v2 请求鉴权"),
        ("8", "开放接口总览"),
        ("9", "单任务下发"),
        ("10", "任务链下发"),
        ("11", "增量查询与断线快照"),
        ("12", "Webhook 配置、验签与重试"),
        ("13", "状态、错误码、重试和幂等"),
        ("14", "端到端联调与验收"),
        ("15", "常见问题与排障"),
        ("16", "生产上线安全清单"),
        ("附录", "管理端 API、字段风格与示例代码"),
    ]
    add_table(doc, ["章节", "主题"], toc_rows, widths=[1300, 8060], first_col_bold=True)
    add_callout(doc, "文档边界",
                "本文重点是第三方系统经 HTTP 接入。WCS 到 PLC 的 OPC UA、Modbus TCP、S7、MQTT、TCP 等设备协议不在本文展开。",
                "tip")

    add_page_break(doc)
    add_heading(doc, "1. 五分钟接入路线", 1)
    add_para(doc, "不想一上来就陷进所有字段里？先把下面这条主线跑通。只要这 8 步成功，HTTP 接入的骨架就已经闭环。")
    add_numbered_items(doc, [
        "WCS 管理员登录管理端，创建一个代表 WMS/ERP 的“上位应用”。",
        "记录系统生成的 AppKey 和 AppSecret，并通过安全渠道交付给调用方后端。",
        "在“绑定授权”页面，把上位应用勾选到允许指挥的设备应用。",
        "双方同步系统时间；调用方使用 HMAC v2 调用 /open/task/dispatch。",
        "用相同 taskNo 再发一次，确认返回同一个 WCS 任务 ID。",
        "调用 /open/task/query 查看任务从 pending 推进到 completed/failed。",
        "配置 Webhook，接收 task.completed 等终态事件并完成验签与幂等。",
        "模拟断网或回调失败，再通过 query/snapshot 补齐状态。",
    ])
    add_picture(doc, diagrams["config"], "图 1-1  管理员与调用方共同完成的一条接入主线")
    add_callout(doc, "请记住",
                "“应用建好了”只代表有身份；“绑定授权做好了”才代表它能指挥具体设备。dispatch 返回 403 时，优先检查绑定。",
                "warn")

    add_heading(doc, "1.1 双方交付物", 2)
    add_table(
        doc,
        ["提供方", "交付内容", "建议形式"],
        [
            ("WCS 管理员", "环境 Base URL、AppKey、AppSecret、可用 appId、WCS 时区", "加密文件或密码管理工具；Secret 与文档分开交付"),
            ("调用方", "出口 IP、Webhook URL、Webhook Secret、联系人与告警渠道", "实施联调表；Webhook Secret 由接收方生成"),
            ("双方", "任务号规则、业务单据号映射、重试与对账频率、上线窗口", "联调纪要与验收清单"),
        ],
        widths=[1800, 4200, 3360],
        first_col_bold=True,
    )

    add_heading(doc, "2. 整体架构与责任边界", 1)
    add_picture(doc, diagrams["integration"], "图 2-1  HTTP 下发、设备执行、Webhook 回告和 REST 对账闭环")
    add_heading(doc, "2.1 谁负责什么", 2)
    add_table(
        doc,
        ["角色", "主要责任", "不要混淆"],
        [
            ("上位业务系统", "产生业务任务号、调用开放 API、保存 WCS 返回 ID、接收回调并最终对账", "不要把 AppSecret 放到浏览器或移动端"),
            ("Cayley Gateway（可选）", "统一域名、TLS、路由、限流；将 /api/wcs/ 重写到 WCS", "外部 URL 前缀不一定等于签名 path"),
            ("礁盘工业 WCS", "鉴权、授权、幂等、任务编排、设备适配、状态与报警、Webhook 投递", "WCS 不替上位系统决定业务单据是否有效"),
            ("设备 / PLC", "执行实际动作并回报状态、故障码", "设备协议字段不等于 HTTP 请求字段"),
        ],
        widths=[2000, 4300, 3060],
        first_col_bold=True,
    )
    add_heading(doc, "2.2 两条消息通道", 2)
    add_bullet(doc, "下行：WMS/ERP 通过 POST /open/** 下发任务或查询状态，使用 AppKey/HMAC v2 鉴权。")
    add_bullet(doc, "上行：WCS 通过 Webhook 主动回调关键终态，使用独立 Webhook Secret 验签。")
    add_bullet(doc, "最终一致性：Webhook 降低延迟，但 REST query/snapshot 仍是断线恢复和查漏补缺的依据。")
    add_callout(doc, "实用理解",
                "把 Webhook 当作“快递员的到件提醒”，把 REST 对账当作“订单系统里的最终物流记录”。提醒可能重复或暂时没收到，但最终记录必须能补回来。",
                "tip")

    add_heading(doc, "3. 环境、地址与接入前检查", 1)
    add_heading(doc, "3.1 Base URL 与签名路径", 2)
    add_table(
        doc,
        ["场景", "实际请求 URL 示例", "HMAC canonicalPath"],
        [
            ("直连 WCS 后端", "http://127.0.0.1:20021/open/task/dispatch", "/open/task/dispatch"),
            ("经 Cayley Gateway", "https://gateway.example.com/api/wcs/open/task/dispatch", "/open/task/dispatch"),
        ],
        widths=[1900, 4850, 2610],
        first_col_bold=True,
    )
    add_callout(doc, "网关接入最常见的 4012",
                "经网关时，浏览器/客户端请求的是 /api/wcs/open/**，但当前网关会把 /api/wcs/ 重写为后端 /。所以签名规范串里仍写 /open/**。如果部署方修改了重写规则，以 WCS 后端实际收到的 requestURI 为准。",
                "warn")
    add_heading(doc, "3.2 接口自描述地址", 2)
    add_table(
        doc,
        ["地址", "用途", "暴露建议"],
        [
            ("GET /v3/api-docs/open", "开放接口 OpenAPI JSON", "仅内网或受控访问"),
            ("/swagger-ui.html（选择 open 分组）", "浏览接口结构", "不要因此暴露全部管理端接口"),
            ("POST /health", "基础健康检查", "可由网关或监控系统访问"),
        ],
        widths=[3500, 3300, 2560],
    )
    add_heading(doc, "3.3 接入前检查表", 2)
    for item in [
        "确认调用方后端能访问 WCS/Gateway；开放 API 不应由前端浏览器直接调用。",
        "确认 WCS 与调用方 NTP 校时正常，时差最好小于 30 秒，默认最大容忍 300 秒。",
        "确认调用方拿到的 appId 是“设备应用 ID”，不是 app_code、AppKey 或协议 ID。",
        "确认上位应用已启用、未删除，并已绑定所有可能出现在请求中的目标设备。",
        "确认 Content-Type 为 application/json，字符集使用 UTF-8。",
        "确认生产使用 HMAC v2，签名基于最终发送的原始 body 字节。",
        "确认调用方数据库能保存 taskNo/chainNo、WCS data.id、状态和 last_update_time。",
        "确认 Webhook URL 是 WCS 后端网络可达地址，不只是管理员浏览器能打开。",
    ]:
        add_bullet(doc, item)

    add_heading(doc, "4. 配置调用方应用", 1)
    add_para(doc, "应用是调用方在 WCS 中的身份。推荐每个接入系统、每个环境分别创建应用，例如 WMS-TEST、WMS-UAT、WMS-PROD，不要跨环境共用同一对密钥。")
    add_heading(doc, "4.1 通过管理界面创建（推荐）", 2)
    add_numbered_items(doc, [
        "使用 WCS 管理员账号登录系统。",
        "进入“应用管理”，点击“新建应用”。",
        "填写应用编码、应用名称；方向选择 upstream，设备类型选择 none。",
        "协议选择任一已存在协议作为占位。上位应用不会建立设备连接，protocol_id 只是满足当前数据模型的必填关联。",
        "连接参数填写空对象 {}；心跳间隔可保留默认值；保持“启用”。",
        "保存后打开应用详情，记录 appId、AppKey、AppSecret。",
        "进入下一章完成绑定授权，再开始 dispatch 联调。",
    ])
    add_heading(doc, "4.2 上位应用字段怎么填", 2)
    add_table(
        doc,
        ["字段", "推荐值", "说明"],
        [
            ("app_code", "WMS-PROD", "项目内唯一、便于识别；建议包含系统和环境"),
            ("app_name", "生产 WMS 主系统", "给实施和运维看的友好名称"),
            ("direction", "upstream", "标识这是上位调用方；真正权限仍由绑定表控制"),
            ("device_type", "none", "上位系统不参加设备调度"),
            ("protocol_id", "选择任一现有协议", "对上位应用无实际连接含义，但当前字段非空"),
            ("conn_params", "{}", "上位应用不由 WCS 主动建连"),
            ("heartbeat_interval_ms", "5000 或默认值", "对纯 HTTP 上位调用方无实际作用"),
            ("enabled", "true", "停用后 AppKey 鉴权失败"),
            ("min_sign_version", "2", "生产强制 body 纳签，防止任务内容被篡改"),
            ("remark", "负责人/环境/用途", "不要在备注中写 Secret"),
        ],
        widths=[2200, 2500, 4660],
        first_col_bold=True,
    )
    add_callout(doc, "界面现状",
                "当前应用界面没有单独展示 min_sign_version。生产可由管理员通过管理端 API 更新，或由项目运维按变更流程设置；调用方仍应从第一天就使用 v2。",
                "warn")
    add_heading(doc, "4.3 管理端 API 创建应用", 2)
    add_para(doc, "管理端 API 使用 JWT，不使用 AppKey。通常只供 WCS 管理员或实施工具调用，不建议暴露给第三方业务系统。")
    add_code(doc, """
POST /login
Content-Type: application/json

{"user_name":"admin","password":"1"}
""", "http")
    add_para(doc, "本地种子环境默认 admin / 1 仅为联调便利。生产必须修改或停用默认凭据，并替换 JWT Secret。", bold=True, color=RED)
    add_code(doc, """
POST /application/create
Authorization: Bearer <access_token>
Content-Type: application/json

{
  "app_code": "WMS-PROD",
  "app_name": "生产 WMS 主系统",
  "protocol_id": 1,
  "direction": "upstream",
  "device_type": "none",
  "conn_params": {},
  "heartbeat_interval_ms": 5000,
  "max_retry": 5,
  "min_sign_version": 2,
  "enabled": true,
  "remark": "生产上位调用方"
}
""", "http")
    add_para(doc, "如果 app_key/app_secret 留空，WCS 会自动生成。创建响应里的 app_key 和 app_secret 应立即纳入密钥管理。")
    add_heading(doc, "4.4 更新最低签名版本", 2)
    add_para(doc, "为了避免管理端全实体更新遗漏字段，建议先调用 /application/detail 读取完整对象，合并 min_sign_version=2 后再调用 /application/update。不要只发送 id 和 min_sign_version。")
    add_code(doc, """
POST /application/detail
Authorization: Bearer <access_token>
Content-Type: application/json

{"id":7}
""", "http")
    add_code(doc, """
# 将 detail 返回对象中的 min_sign_version 改为 2，
# 保留 app_code/app_name/protocol_id/direction/device_type/conn_params/enabled 等字段后：
POST /application/update
Authorization: Bearer <access_token>
Content-Type: application/json

{ ...完整应用对象..., "min_sign_version": 2 }
""", "http")
    add_callout(doc, "密钥更新规则",
                "普通 /application/update 不会修改 app_key 或 app_secret。需要换密钥时调用 /application/reset-secret；重置成功后旧 Secret 立即失效，必须安排双方切换窗口。",
                "warn")
    add_code(doc, """
POST /application/reset-secret
Authorization: Bearer <access_token>
Content-Type: application/json

{"id":7}
""", "http")
    add_heading(doc, "4.5 凭据交付与保管", 2)
    for item in [
        "AppKey 可以用于识别调用方，但仍不建议公开；AppSecret 按密码级别保护。",
        "只放在调用方后端的 Secret Manager、环境变量或受控配置中心，不进入 Git、日志、前端包和截图。",
        "测试、预生产、生产使用不同密钥；不同业务系统也不要共用。",
        "轮换前先准备新 Secret 的部署窗口。当前重置没有新旧并存期，切换时应暂停下发或安排短时维护。",
        "签名排障时可记录 canonical 的字段名和 hash，不要打印明文 Secret 或完整敏感 payload。",
    ]:
        add_bullet(doc, item)

    add_heading(doc, "5. 配置设备绑定授权", 1)
    add_para(doc, "绑定关系表达“哪个上位应用可以指挥哪些下位设备”。direction 是分类提示，真正的下发权限由绑定记录决定。")
    add_heading(doc, "5.1 通过管理界面授权", 2)
    add_numbered_items(doc, [
        "进入“绑定授权”页面。",
        "选择刚创建的上位应用，例如 WMS-PROD。",
        "勾选允许该系统下发任务的设备应用。",
        "点击保存。页面按“声明式集合”处理：新勾选的增加授权，取消勾选的撤销授权。",
        "重新进入该上位应用，确认勾选状态正确。",
        "联调时用一个已授权 appId 做成功用例，再用一个未授权 appId 验证 403。",
    ])
    add_callout(doc, "任务链要特别注意",
                "链里每个固定 appId，以及 candidateAppIds 中的每一个候选设备，都必须已授权。任意一个没授权，整条链会被拒绝。",
                "warn")
    add_heading(doc, "5.2 管理端 API 批量授权", 2)
    add_code(doc, """
POST /binding/granted
Authorization: Bearer <access_token>
Content-Type: application/json

{"upstreamAppId":7}
""", "http")
    add_code(doc, """
POST /binding/grant
Authorization: Bearer <access_token>
Content-Type: application/json

{
  "upstreamAppId": 7,
  "downstreamAppIds": [5, 6],
  "scope": "dispatch"
}
""", "http")
    add_para(doc, "grant 是整批设置：列表里缺失的旧授权会被撤销，已存在的会恢复启用，自己绑定自己会被忽略。执行前请先读取 granted 并确认完整目标集合。")
    add_heading(doc, "5.3 开放接口的可见范围", 2)
    add_table(
        doc,
        ["接口/动作", "权限规则"],
        [
            ("单任务下发", "调用方 AppKey 对目标 appId 必须有活动绑定"),
            ("任务链下发", "固定设备和所有候选设备逐一校验；任一失败整链拒绝"),
            ("任务/报警查询（指定 appId）", "只能查自身或已授权设备"),
            ("任务/报警查询（不传 appId）", "返回自身 + 已授权设备范围，不会返回全库"),
            ("任务链查询", "只返回当前 AppKey 下发的链"),
            ("设备快照", "只能获取自身或已授权设备"),
        ],
        widths=[3000, 6360],
        first_col_bold=True,
    )

    add_heading(doc, "6. HTTP 通用约定", 1)
    add_heading(doc, "6.1 请求方式", 2)
    add_code(doc, """
POST /open/...
Content-Type: application/json
""", "http")
    add_para(doc, "开放业务接口统一使用 JSON/UTF-8。当前接口没有 query 参数；HMAC path 不含协议、域名和 query string。")
    add_heading(doc, "6.2 统一响应包装", 2)
    add_code(doc, """
{
  "isSuccess": true,
  "code": 200,
  "errorMessage": "",
  "data": {}
}
""", "json")
    add_code(doc, """
{
  "isSuccess": false,
  "code": 403,
  "errorMessage": "应用未获授权指挥目标设备",
  "data": null
}
""", "json")
    add_callout(doc, "不要只看 HTTP 状态码",
                "WCS 业务层通常会用 HTTP 200 返回统一包装，业务是否成功必须判断 isSuccess。网关限流、代理故障、连接超时等基础设施错误仍可能返回非 200。",
                "danger")
    add_heading(doc, "6.3 字段命名风格", 2)
    add_table(
        doc,
        ["位置", "当前风格", "例子"],
        [
            ("开放接口请求 DTO", "camelCase", "appId、taskNo、sinceMillis"),
            ("实体响应 data/rows", "snake_case", "app_id、task_no、last_update_time"),
            ("Webhook 管理实体", "snake_case", "event_types、scope_app_ids"),
            ("Webhook 回调事件", "camelCase + 固定 event", "bizId、taskNo、faultCode"),
        ],
        widths=[2800, 2600, 3960],
        first_col_bold=True,
    )

    add_heading(doc, "7. AppKey / HMAC v2 请求鉴权", 1)
    add_picture(doc, diagrams["sign"], "图 7-1  HMAC v2 的计算顺序与网关路径规则")
    add_heading(doc, "7.1 必需请求头", 2)
    add_table(
        doc,
        ["Header", "必填", "说明"],
        [
            ("X-App-Key", "是", "WCS 分配的调用方 AppKey"),
            ("X-Timestamp", "是", "Unix 秒时间戳；注意不是毫秒"),
            ("X-Nonce", "是", "每次请求唯一随机串；同 AppKey 窗口内不可复用"),
            ("X-Sign", "是", "小写十六进制 HMAC-SHA256"),
            ("X-Sign-Version", "强烈建议", "固定写 2；不传或非法值按 v1 处理"),
        ],
        widths=[2100, 1300, 5960],
        first_col_bold=True,
    )
    add_para(doc, "服务器默认允许时间偏差 300 秒。相同 appKey + nonce 在防重放窗口内只能使用一次。")
    add_heading(doc, "7.2 v2 规范串", 2)
    add_code(doc, """
bodyHash = lowercase_hex(SHA256(rawRequestBodyBytes))

canonical = appKey
          + "\\n" + timestamp
          + "\\n" + nonce
          + "\\n" + UPPERCASE_HTTP_METHOD
          + "\\n" + backendRequestPath
          + "\\n" + bodyHash

sign = lowercase_hex(HMAC_SHA256(appSecret, UTF8(canonical)))
""", "text")
    add_code(doc, """
ak_wms_main
1784793600
8e9485866d4348e8a8df9050990d44af
POST
/open/task/dispatch
9ea7b9b3f93d9d533685fddbba1f7fd34e6c06b0aede0db0da47d81ecfa742ca
""", "text")
    add_heading(doc, "7.3 四条硬规则", 2)
    for item in [
        "bodyHash 必须基于最终实际发送的原始字节，而不是一个“等价 JSON 对象”。",
        "计算签名后不得再次美化、压缩、排序字段或改变转义方式。",
        "HTTP 方法必须大写；path 必须是 WCS 后端实际收到的路径。",
        "v2 请求体默认上限 1 MiB；超出返回业务码 400。",
    ]:
        add_bullet(doc, item)
    add_heading(doc, "7.4 Python 完整调用示例", 2)
    add_code(doc, """
import hashlib, hmac, json, secrets, time, urllib.request

base_url = "http://127.0.0.1:20021"
canonical_path = "/open/task/dispatch"
request_url = base_url + canonical_path
app_key = "由 WCS 分配"
app_secret = "由 WCS 分配"

payload = {
    "appId": 999001,
    "taskNo": "WMS-20260723-0001",
    "taskType": "1",
    "takeRow": 1, "takeColumn": 2, "takeFloor": 3,
    "putRow": 2, "putColumn": 5, "putFloor": 6,
    "portNum": 1, "priority": 10,
    "wmsRef": "IN-20260723-0001"
}
body = json.dumps(payload, ensure_ascii=False,
                  separators=(",", ":")).encode("utf-8")
timestamp = str(int(time.time()))
nonce = secrets.token_hex(16)
body_hash = hashlib.sha256(body).hexdigest()
canonical = "\\n".join([
    app_key, timestamp, nonce, "POST",
    canonical_path, body_hash
])
sign = hmac.new(
    app_secret.encode("utf-8"),
    canonical.encode("utf-8"),
    hashlib.sha256
).hexdigest()
""", "python")
    add_code(doc, """
request = urllib.request.Request(
    request_url, data=body, method="POST",
    headers={
        "Content-Type": "application/json",
        "X-App-Key": app_key,
        "X-Timestamp": timestamp,
        "X-Nonce": nonce,
        "X-Sign-Version": "2",
        "X-Sign": sign
    }
)
with urllib.request.urlopen(request, timeout=30) as response:
    result = json.loads(response.read().decode("utf-8"))

if not result["isSuccess"]:
    raise RuntimeError(
        f'WCS error {result["code"]}: {result["errorMessage"]}'
    )
print(result["data"])
""", "python")
    add_heading(doc, "7.5 4012 签名排查顺序", 2)
    add_numbered_items(doc, [
        "打印并逐行对照 canonical（不要打印 Secret）。",
        "确认 X-Timestamp 是秒；Webhook 的时间戳才是毫秒。",
        "确认实际发送 body 与计算 bodyHash 的 byte[] 完全相同。",
        "确认网关场景 canonicalPath 仍为后端 /open/**。",
        "确认 X-Sign-Version 确实为字符串 2，且应用 min_sign_version 没有高于客户端版本。",
        "确认 Secret 没有复制到多余空格/换行，且没有发生 reset-secret。",
    ])

    add_heading(doc, "8. 开放接口总览", 1)
    add_table(
        doc,
        ["接口", "用途", "幂等/可见范围"],
        [
            ("POST /open/task/dispatch", "下发一个设备任务", "appId + taskNo 幂等"),
            ("POST /open/chain/dispatch", "下发串行任务链", "同租户 chainNo 幂等"),
            ("POST /open/task/query", "增量查询任务", "自身及已授权设备"),
            ("POST /open/alarm/query", "增量查询报警", "自身及已授权设备"),
            ("POST /open/chain/query", "增量查询任务链", "仅当前 AppKey 下发的链"),
            ("POST /open/snapshot", "获取单设备重连快照", "自身或已授权设备"),
        ],
        widths=[3300, 3100, 2960],
        first_col_bold=True,
    )
    add_callout(doc, "调用建议",
                "业务主链路使用 dispatch；状态更新优先消费 Webhook；每个资源类型都保留周期性 query；出现断线或断号时先 snapshot，再从持久化水位线继续 query。",
                "ok")

    add_heading(doc, "9. 单任务下发", 1)
    add_heading(doc, "9.1 POST /open/task/dispatch", 2)
    add_table(
        doc,
        ["字段", "类型", "必填", "说明"],
        [
            ("appId", "integer", "是", "目标设备应用 ID，必须已授权"),
            ("taskNo", "string", "是", "调用方任务号；同一设备内的幂等键"),
            ("taskType", "string", "是", "设备任务类型；标准堆垛机使用字符串数字"),
            ("takeRow/takeColumn/takeFloor", "integer", "条件", "取货排/列/层"),
            ("putRow/putColumn/putFloor", "integer", "条件", "放货排/列/层"),
            ("portNum", "integer", "条件", "取放货口"),
            ("priority", "integer", "否", "默认 0；数值越大越优先"),
            ("wmsRef", "string", "否", "WMS/ERP 业务单据引用"),
            ("payload", "object", "条件", "非堆垛机设备专用参数或业务透传"),
        ],
        widths=[2700, 1400, 1200, 4060],
        first_col_bold=True,
        font_size=9.2,
    )
    add_callout(doc, "生产必填",
                "当前服务层虽只显式检查 appId，但数据库和执行链要求 taskNo、taskType 有效。生产调用请把 appId、taskNo、taskType 都视为必填。",
                "warn")
    add_heading(doc, "9.2 标准堆垛机任务类型", 2)
    add_table(
        doc,
        ["值", "语义", "备注"],
        [
            ('"0"', "无任务", "通常不由上位系统主动下发"),
            ('"1"', "入库", ""),
            ('"2"', "出库", ""),
            ('"3"', "移库", ""),
            ('"4"', "移动", ""),
            ('"5"', "急停", "高风险动作，按项目权限控制"),
            ('"6"', "解警", "确认现场安全后使用"),
            ('"7"', "充电", "当前仅预留"),
        ],
        widths=[1300, 2400, 5660],
    )
    add_para(doc, "标准种子协议坐标范围：排 1～2、列 1～7、层 1～9、货口 1～2。实际项目以设备协议点位配置为准。")
    add_heading(doc, "9.3 内置设备 payload", 2)
    add_table(
        doc,
        ["device_type", "必填参数", "示例"],
        [
            ("conveyor", "toStation；fromStation 可选", '{"toStation":7,"fromStation":1}'),
            ("lift", "targetFloor", '{"targetFloor":3}'),
            ("auto_door", "action，只能 open/close", '{"action":"open"}'),
        ],
        widths=[2100, 3400, 3860],
        first_col_bold=True,
    )
    add_para(doc, "auto_door.action 当前没有默认值，缺失或非法会使任务失败。")
    add_heading(doc, "9.4 请求示例", 2)
    add_code(doc, """
{
  "appId": 999001,
  "taskNo": "WMS-20260723-0001",
  "taskType": "1",
  "takeRow": 1,
  "takeColumn": 2,
  "takeFloor": 3,
  "putRow": 2,
  "putColumn": 5,
  "putFloor": 6,
  "portNum": 1,
  "priority": 10,
  "wmsRef": "IN-20260723-0001",
  "payload": {"containerCode":"LPN-000001"}
}
""", "json")
    add_heading(doc, "9.5 成功响应示例", 2)
    add_code(doc, """
{
  "isSuccess": true,
  "code": 200,
  "errorMessage": "",
  "data": {
    "id": 1234,
    "task_no": "WMS-20260723-0001",
    "app_id": 999001,
    "wms_ref": "IN-20260723-0001",
    "task_type": "1",
    "priority": 10,
    "status": "pending",
    "handshake_step": "CREATED",
    "error_code": 0,
    "chain_id": null,
    "seq_no": null,
    "retry_count": 0,
    "version": 0,
    "create_time": "2026-07-23T16:40:00",
    "last_update_time": "2026-07-23T16:40:00"
  }
}
""", "json")
    add_heading(doc, "9.6 幂等规则", 2)
    add_para(doc, "同一 appId + taskNo 重复下发独立任务时，WCS 返回第一次创建的任务，不会重复建任务，也不会比较新旧请求体。")
    for item in [
        "网络超时重试必须复用原 taskNo。",
        "不得用同一个 taskNo 发送不同坐标、任务类型或 payload。",
        "超时后先用同 taskNo 重试或查询，不能立即生成新任务号。",
        "调用方应保存首次返回的 data.id，并对重复响应做一致性检查。",
    ]:
        add_bullet(doc, item)

    add_heading(doc, "10. 任务链下发", 1)
    add_para(doc, "任务链用于多个设备按段串行执行。上一段完成后，WCS 才会物化下一段任务，适合“堆垛机出库 → 输送线搬运 → 提升机换层”这类跨设备流程。")
    add_heading(doc, "10.1 POST /open/chain/dispatch", 2)
    add_table(
        doc,
        ["链字段", "类型", "必填", "说明"],
        [
            ("chainNo", "string", "是", "幂等键，最大 100 字符"),
            ("priority", "integer", "否", "默认 0"),
            ("selectStrategy", "string", "否", "idle_first / round_robin；默认 idle_first"),
            ("wmsRef", "string", "否", "上位业务引用"),
            ("payload", "object", "否", "链级透传信息"),
            ("segments", "array", "是", "至少一段"),
        ],
        widths=[2200, 1500, 1200, 4460],
        first_col_bold=True,
    )
    add_table(
        doc,
        ["段字段", "必填", "说明"],
        [
            ("seq", "否", "正整数且链内唯一；缺省按数组顺序从 1 编号"),
            ("appId", "二选一", "固定目标设备"),
            ("candidateAppIds", "二选一", "候选设备组；不能与 appId 同时出现"),
            ("taskType", "是", "设备任务类型"),
            ("坐标/portNum", "条件", "与单任务一致"),
            ("payload", "条件", "与单任务一致"),
        ],
        widths=[2600, 1600, 5160],
        first_col_bold=True,
    )
    add_heading(doc, "10.2 选机策略", 2)
    add_table(
        doc,
        ["策略", "行为", "适合场景"],
        [
            ("idle_first", "只在连接 RUNNING 且工作模式 normal 的候选中选择；优先空闲，否则选未完成任务最少者", "优先可用性与负载"),
            ("round_robin", "按 chainId + seq 确定性轮询，不感知当前连接；设备未连接时任务可能排队", "固定均衡、可接受等待"),
        ],
        widths=[1700, 5200, 2460],
        first_col_bold=True,
    )
    add_heading(doc, "10.3 固定设备链示例", 2)
    add_code(doc, """
{
  "chainNo": "WMS-CHAIN-20260723-0001",
  "priority": 10,
  "selectStrategy": "idle_first",
  "wmsRef": "OUT-20260723-0008",
  "payload": {"containerCode":"LPN-000001"},
  "segments": [
    {
      "seq": 1, "appId": 999001, "taskType": "2",
      "takeRow": 1, "takeColumn": 2, "takeFloor": 3,
      "putRow": 1, "putColumn": 1, "putFloor": 1,
      "portNum": 1
    },
    {
      "seq": 2, "appId": 999002, "taskType": "move",
      "payload": {"toStation":7}
    },
    {
      "seq": 3, "appId": 999003, "taskType": "move",
      "payload": {"targetFloor":3}
    }
  ]
}
""", "json")
    add_heading(doc, "10.4 候选设备组示例", 2)
    add_code(doc, """
{
  "chainNo": "WMS-CHAIN-20260723-0002",
  "selectStrategy": "idle_first",
  "segments": [
    {
      "candidateAppIds": [999101, 999102],
      "taskType": "1",
      "takeRow": 1, "takeColumn": 1, "takeFloor": 1,
      "putRow": 2, "putColumn": 2, "putFloor": 2,
      "portNum": 1
    }
  ]
}
""", "json")
    add_heading(doc, "10.5 链幂等与状态", 2)
    add_para(doc, "同租户相同 chainNo 重复下发时返回已有链，不比较新旧段定义。调用方不得复用 chainNo 修改流程。")
    add_code(doc, """
pending -> running -> completed
                   \\-> failed
pending/running ----> cancelled
""", "text")
    add_table(
        doc,
        ["字段", "说明"],
        [
            ("id", "WCS 内部链 ID"),
            ("chain_no", "调用方链号"),
            ("upstream_app_id", "下发该链的上位应用 ID"),
            ("segments", "规范化后的段定义"),
            ("select_strategy", "实际使用的选机策略"),
            ("status", "pending/running/completed/failed/cancelled"),
            ("error_message", "失败原因"),
            ("finish_time", "终态时间"),
            ("last_update_time", "增量查询水位字段"),
        ],
        widths=[2800, 6560],
        first_col_bold=True,
    )

    add_heading(doc, "11. 增量查询与断线快照", 1)
    add_heading(doc, "11.1 三个增量查询接口", 2)
    add_code(doc, """
POST /open/task/query
{"appId":999001,"sinceMillis":0,"limit":200}

POST /open/alarm/query
{"appId":999001,"sinceMillis":0,"limit":200}

POST /open/chain/query
{"sinceMillis":0,"limit":200}
""", "http")
    add_table(
        doc,
        ["字段", "说明"],
        [
            ("appId", "任务/报警查询可选；指定时必须是自身或已授权设备"),
            ("sinceMillis", "Unix 毫秒水位线；0 表示从最早记录开始"),
            ("limit", "<=0 默认 200，最大 2000"),
        ],
        widths=[2300, 7060],
        first_col_bold=True,
    )
    add_code(doc, """
{
  "isSuccess": true,
  "code": 200,
  "errorMessage": "",
  "data": {
    "serverTimeMillis": 1784793600123,
    "count": 2,
    "rows": []
  }
}
""", "json")
    add_heading(doc, "11.2 水位线算法（必须照这个做）", 2)
    add_numbered_items(doc, [
        "按响应顺序处理 rows。",
        "用 resourceType + id + last_update_time 做幂等去重。",
        "下次 sinceMillis 取本批最后一行 last_update_time 对应的 Unix 毫秒。",
        "服务端查询使用 >=，边界行会再次出现；调用方按 ID 和更新时间去重。",
        "当 count == limit 时立即继续拉下一页。",
        "每种资源（task/alarm/chain）维护独立、持久化的水位线。",
    ])
    add_callout(doc, "不能用 serverTimeMillis 当下次水位线",
                "当结果被 limit 截断时，serverTimeMillis 可能已经晚于尚未返回的记录。直接跳到服务器当前时间会永久漏数据。",
                "danger")
    add_heading(doc, "11.3 时区约定", 2)
    add_para(doc, "实体 last_update_time 当前是没有时区偏移的 ISO 本地时间，例如 2026-07-23T16:45:00.123456。转换 Unix 毫秒时必须按 WCS 主机时区解释。生产上线前请把 WCS 时区、数据库时区、调用方解析规则写进联调纪要。")
    add_heading(doc, "11.4 POST /open/snapshot", 2)
    add_code(doc, """
{"appId":999001}
""", "json")
    add_code(doc, """
{
  "isSuccess": true,
  "code": 200,
  "errorMessage": "",
  "data": {
    "serverTimeMillis": 1784793600123,
    "connection": {
      "appId": 999001,
      "appCode": "PALLET_SIM",
      "appName": "码垛机 OPC UA 仿真",
      "deviceType": "palletizer",
      "protocolType": "opcua",
      "state": "RUNNING",
      "lastHeartbeatAt": 1784793599900,
      "retryCount": 0,
      "latest": {}
    },
    "alarms": [],
    "tasks": []
  }
}
""", "json")
    for item in [
        "设备未打开连接时 connection 为 null。",
        "alarms 是当前活动报警。",
        "tasks 当前最多 50 条，并非完整历史。",
        "快照后仍要从持久化水位线继续 task/query、alarm/query、chain/query。",
    ]:
        add_bullet(doc, item)
    add_heading(doc, "11.5 推荐恢复流程", 2)
    add_code(doc, """
检测到网络重连 / Webhook 空窗 / 实时流断号
  -> 对关心的设备调用 /open/snapshot
  -> 恢复连接态、活动报警和最近任务
  -> 从本地持久化水位线继续 task/query、alarm/query、chain/query
  -> 按 id + last_update_time 去重
  -> 直到 count < limit，再回到正常周期
""", "text")

    add_heading(doc, "12. Webhook 配置、验签与重试", 1)
    add_picture(doc, diagrams["webhook"], "图 12-1  Webhook 至少一次投递与 REST 最终对账")
    add_para(doc, "Webhook 用于让 WCS 主动通知任务、链和报警的关键终态。管理接口 /webhook/** 使用管理员 JWT；回调本身使用独立的 Webhook Secret。")
    add_heading(doc, "12.1 配置前先准备", 2)
    for item in [
        "由接收方提供一个 WCS 后端可访问的 HTTP/HTTPS URL，例如 https://wms.example.com/wcs/hook。",
        "由接收方生成至少 32 字节高强度随机 Secret，并通过安全渠道交给 WCS 管理员。",
        "确认网络、防火墙、DNS、TLS 证书和出口策略允许 WCS 访问该 URL。",
        "接收服务必须能读取原始 body 字节，不能先反序列化再重新序列化后验签。",
        "接收服务先做验签和防重放，再做业务幂等；成功返回任意 2xx。",
    ]:
        add_bullet(doc, item)
    add_callout(doc, "网络可达性",
                "Webhook 是 WCS 后端主动发出。管理员电脑能打开回调 URL，不代表部署 WCS 的服务器也能访问。请从 WCS 宿主机或 Pod 所在网络做一次实际连通性测试。",
                "warn")
    add_heading(doc, "12.2 通过管理界面创建", 2)
    add_numbered_items(doc, [
        "进入“Webhook 订阅”，点击“新建 Webhook”。",
        "填写名称、回调 URL 和签名密钥 Secret。",
        "至少勾选一个事件类型。",
        "选择作用域应用；留空表示全部。任务/报警按设备 appId 匹配，任务链按 upstreamAppId 匹配。",
        "保持启用，填写备注后保存。",
        "触发一条测试任务，进入“投递记录”查看状态、尝试次数、下次重投和最近响应。",
    ])
    add_heading(doc, "12.3 通过管理端 API 创建", 2)
    add_code(doc, """
POST /webhook/create
Authorization: Bearer <access_token>
Content-Type: application/json

{
  "name": "wms-callback",
  "url": "https://wms.example.com/wcs/hook",
  "secret": "由接收方生成的高强度随机密钥",
  "event_types": [
    "task.completed", "task.failed", "task.cancelled",
    "chain.completed", "chain.failed", "chain.cancelled",
    "alarm.raised", "alarm.cleared"
  ],
  "scope_app_ids": [],
  "enabled": true,
  "remark": "WMS 生产回调"
}
""", "http")
    add_para(doc, "管理实体字段使用 snake_case。界面要求名称、URL 和至少一个事件；后端还会校验 URL 只能是 http/https，并执行 host 白名单/私网规则。")
    add_heading(doc, "12.4 可订阅事件", 2)
    add_table(
        doc,
        ["事件", "含义", "推荐处理"],
        [
            ("task.completed", "单任务完成", "更新业务任务为完成"),
            ("task.failed", "单任务失败", "记录 errorCode，触发人工/策略处理"),
            ("task.cancelled", "单任务取消", "同步取消状态"),
            ("chain.completed", "任务链完成", "更新整链业务状态"),
            ("chain.failed", "任务链失败", "结合 query 查明失败段"),
            ("chain.cancelled", "任务链取消", "同步整链取消"),
            ("alarm.raised", "报警产生", "告警、关联设备并查询详情"),
            ("alarm.cleared", "报警清除", "关闭或恢复告警"),
        ],
        widths=[2400, 3000, 3960],
        first_col_bold=True,
    )
    add_heading(doc, "12.5 回调请求头与验签", 2)
    add_code(doc, """
POST <订阅 URL>
Content-Type: application/json
X-Webhook-Timestamp: 1784793600123
X-Webhook-Nonce: 91d25d8b0cb398d05d9aaad5
X-Webhook-Sign: <lowercase hex hmac-sha256>
""", "http")
    add_callout(doc, "时间戳单位别混",
                "Webhook 的 X-Webhook-Timestamp 是毫秒；调用 /open/** 的 X-Timestamp 是秒。",
                "warn")
    add_code(doc, """
bodyHash = lowercase_hex(SHA256(rawRequestBodyBytes))
canonical = timestampMillis + "\\n" + nonce + "\\n" + bodyHash
sign = lowercase_hex(HMAC_SHA256(webhookSecret, UTF8(canonical)))
""", "text")
    add_heading(doc, "12.6 Python 验签示例", 2)
    add_code(doc, """
import hashlib, hmac, time

def verify_webhook(headers, raw_body: bytes, secret: str,
                   max_skew_ms: int = 300_000) -> bool:
    ts = headers["X-Webhook-Timestamp"]
    nonce = headers["X-Webhook-Nonce"]
    received = headers["X-Webhook-Sign"].lower()

    if abs(int(time.time() * 1000) - int(ts)) > max_skew_ms:
        return False
    body_hash = hashlib.sha256(raw_body).hexdigest()
    canonical = "\\n".join([ts, nonce, body_hash])
    expected = hmac.new(
        secret.encode("utf-8"),
        canonical.encode("utf-8"),
        hashlib.sha256
    ).hexdigest()
    return hmac.compare_digest(expected, received)
""", "python")
    add_para(doc, "验签成功后，还要把 timestamp + nonce 或 event + bizId 放入带过期时间的防重放/幂等存储。不要只验签，不做重复保护。")
    add_heading(doc, "12.7 事件 payload", 2)
    add_code(doc, """
{
  "event": "task.completed",
  "bizId": 1234,
  "timestamp": 1784793600123,
  "taskNo": "WMS-20260723-0001",
  "appId": 999001,
  "status": "completed",
  "errorCode": 0,
  "chainId": null,
  "wmsRef": "IN-20260723-0001"
}
""", "json")
    add_code(doc, """
{
  "event": "chain.completed",
  "bizId": 88,
  "timestamp": 1784793600123,
  "chainNo": "WMS-CHAIN-20260723-0001",
  "upstreamAppId": 7,
  "status": "completed"
}
""", "json")
    add_code(doc, """
{
  "event": "alarm.raised",
  "bizId": 901,
  "timestamp": 1784793600123,
  "appId": 999001,
  "faultCode": 8,
  "level": "error",
  "message": "设备故障",
  "status": "raised"
}
""", "json")
    add_heading(doc, "12.8 接收端处理顺序", 2)
    add_numbered_items(doc, [
        "读取原始 body 与三个签名头。",
        "校验时间窗口，并用常量时间比较签名。",
        "检查 nonce 防重放。",
        "解析 JSON，以 event + bizId 作为业务幂等键。",
        "在本地事务中更新业务状态并写入事件处理记录。",
        "业务真正落库成功后返回 2xx；处理失败返回非 2xx 让 WCS 重试。",
        "异步重任务可先可靠入队后返回 2xx，不要在回调线程里长时间阻塞。",
    ])
    add_heading(doc, "12.9 投递与重试规则", 2)
    add_table(
        doc,
        ["规则", "当前实现"],
        [
            ("交付语义", "至少一次；接收方必须幂等"),
            ("成功条件", "任意 HTTP 2xx；响应 body 不参与判断"),
            ("失败条件", "非 2xx、连接/读取异常、URL 策略拒绝"),
            ("默认尝试次数", "总计最多 6 次"),
            ("退避", "30s 起始指数退避，最大 30min"),
            ("请求连接超时", "默认 5s；单次请求总超时 10s"),
            ("重定向", "不跟随，避免绕过 URL 白名单"),
            ("新订阅历史", "从创建时刻开始，不回放更早记录"),
        ],
        widths=[2600, 6760],
        first_col_bold=True,
    )
    add_heading(doc, "12.10 查看投递记录", 2)
    add_code(doc, """
POST /webhook/deliveries
Authorization: Bearer <access_token>
Content-Type: application/json

{"webhookId":1,"limit":50}
""", "http")
    add_para(doc, "limit 默认 50，最大建议 500。重点看 status、attempts、next_retry_time、last_response。status 为 failed 的记录当前不会自动永久重试，需要先解决根因，再通过 REST 对账补业务状态。")
    add_heading(doc, "12.11 Webhook 运行参数", 2)
    add_table(
        doc,
        ["环境变量", "默认值", "说明"],
        [
            ("CAYLEYWCS_WEBHOOK_SCAN_INTERVAL_MS", "3000", "事件扫描间隔"),
            ("CAYLEYWCS_WEBHOOK_SCAN_BATCH", "200", "每源每批扫描条数"),
            ("CAYLEYWCS_WEBHOOK_SCAN_LAG_MS", "2000", "状态稳定滞后窗口"),
            ("CAYLEYWCS_WEBHOOK_DELIVER_INTERVAL_MS", "2000", "待投递扫描间隔"),
            ("CAYLEYWCS_WEBHOOK_MAX_ATTEMPTS", "6", "最大总尝试次数"),
            ("CAYLEYWCS_WEBHOOK_BACKOFF_BASE_MS", "30000", "首次退避"),
            ("CAYLEYWCS_WEBHOOK_BACKOFF_MAX_MS", "1800000", "最大退避"),
            ("CAYLEYWCS_WEBHOOK_CONNECT_TIMEOUT_MS", "5000", "连接超时"),
            ("CAYLEYWCS_WEBHOOK_HOST_ALLOWLIST", "空（不限）", "逗号分隔、host 精确匹配"),
            ("CAYLEYWCS_WEBHOOK_BLOCK_PRIVATE_HOSTS", "false", "是否拒绝私网/环回/链路本地地址"),
        ],
        widths=[4400, 1600, 3360],
        font_size=8.7,
    )
    add_callout(doc, "生产 URL 策略",
                "建议配置精确 host allowlist。如果回调服务本身就在私网，开启 BLOCK_PRIVATE_HOSTS=true 会直接拒绝私网地址；此时应由安全团队采用 allowlist、DNS 管控、网络 ACL 和专用出口等方式保护，而不是盲目开启该开关。",
                "danger")
    add_heading(doc, "12.12 Webhook 的已知边界", 2)
    for item in [
        "scope_app_ids 为空表示全部；task/alarm 按设备 appId，chain 按 upstreamAppId。",
        "事件来自可变状态表扫描。极短时间内 alarm.raised → alarm.cleared，可能只看到最终 cleared。",
        "Webhook 不是完整 append-only 事件日志，关键业务必须保留 REST 对账。",
        "新订阅不回放创建前历史；首次启用前可通过 query/snapshot 建立基线。",
    ]:
        add_bullet(doc, item)

    add_heading(doc, "13. 状态、错误码、重试和幂等", 1)
    add_heading(doc, "13.1 任务状态", 2)
    add_table(
        doc,
        ["状态", "说明", "是否终态"],
        [
            ("pending", "待调度", "否"),
            ("waiting", "链段等待激活", "否"),
            ("dispatched", "已下发", "否"),
            ("executing", "设备执行中", "否"),
            ("completed", "完成", "是"),
            ("failed", "失败", "是"),
            ("cancelled", "已取消", "是"),
        ],
        widths=[2300, 4500, 2560],
        first_col_bold=True,
    )
    add_heading(doc, "13.2 报警状态", 2)
    add_table(
        doc,
        ["状态", "说明"],
        [("raised", "已产生"), ("ack", "已确认"), ("cleared", "已清除")],
        widths=[2500, 6860],
        first_col_bold=True,
    )
    add_heading(doc, "13.3 业务错误码", 2)
    add_table(
        doc,
        ["code", "名称", "含义", "调用方处理"],
        [
            ("200", "SUCCESS", "成功", "处理 data"),
            ("400", "BAD_REQUEST", "JSON、字段或请求体大小不合法", "修正请求，不盲目重试"),
            ("4011", "APPKEY_INVALID", "头缺失、AppKey 无效或应用停用", "检查凭据和应用状态"),
            ("4012", "SIGNATURE_INVALID", "签名错误或版本过低", "核对 body、path、Secret、版本"),
            ("4013", "REPLAY", "时间戳过期/非法或 nonce 重放", "校时并生成新签名"),
            ("403", "FORBIDDEN", "目标设备未授权", "申请/检查绑定"),
            ("404", "NOT_FOUND", "资源不存在", "检查 ID"),
            ("409", "CONFLICT", "资源状态冲突", "查询最新状态"),
            ("4241", "TASK_INVALID", "任务或任务链参数非法", "修正任务内容"),
            ("4242", "TASK_DUPLICATED", "任务号冲突", "查询已有任务"),
            ("4243", "TASK_STATE_ILLEGAL", "当前状态不允许操作", "先查询最新状态"),
            ("500", "INTERNAL_ERROR", "未处理异常", "告警并有限重试"),
        ],
        widths=[1000, 2200, 3160, 3000],
        font_size=8.7,
    )
    add_heading(doc, "13.4 推荐超时与重试", 2)
    add_table(
        doc,
        ["场景", "建议"],
        [
            ("HTTP 客户端", "连接超时 3～5 秒，单次总超时 15～30 秒"),
            ("网络错误/超时", "最多 3 次指数退避 + 随机抖动；复用原 taskNo/chainNo"),
            ("HTTP 200 + isSuccess=false", "按业务 code 分类，不要统一自动重试"),
            ("4013", "校时，生成新的 timestamp/nonce/sign 后再试"),
            ("500", "有限重试并告警；保持原幂等键"),
            ("Webhook 重复", "event + bizId 幂等"),
            ("查询边界重复", "resourceType + id + last_update_time 幂等"),
        ],
        widths=[2900, 6460],
        first_col_bold=True,
    )
    add_heading(doc, "13.5 重试决策表", 2)
    add_table(
        doc,
        ["结果", "自动重试？", "关键动作"],
        [
            ("连接失败/超时", "可以，有限次数", "保留幂等键；新 timestamp/nonce/sign"),
            ("4011", "通常不自动", "检查 AppKey、enabled、是否被删除"),
            ("4012", "不盲重试", "修复签名算法/Secret/path"),
            ("4013", "修复后可重试", "校时，nonce 必须更新"),
            ("403", "不自动", "管理员修复绑定"),
            ("400/4241", "不自动", "修正参数"),
            ("500", "可以，有限次数", "保留上下文并告警"),
        ],
        widths=[2600, 2200, 4560],
        first_col_bold=True,
    )

    add_heading(doc, "14. 端到端联调与验收", 1)
    add_heading(doc, "14.1 推荐联调顺序", 2)
    add_numbered_items(doc, [
        "创建测试上位应用，交付测试 AppKey/Secret。",
        "绑定一个仿真设备或安全的真实测试设备。",
        "双方校时，确认 canonicalPath。",
        "用 HMAC v2 调 /open/task/dispatch，确认 isSuccess=true。",
        "用相同 taskNo 重发，确认 data.id 不变。",
        "调 /open/task/query，观察状态推进到终态。",
        "调 /open/snapshot，确认连接、活动报警和最近任务结构。",
        "创建 Webhook，验证验签、幂等和正常 2xx。",
        "让接收端临时返回 500，确认投递记录进入重试。",
        "验证未授权 appId 返回 403。",
        "验证旧时间戳、重复 nonce、篡改 body 分别返回 4013、4013、4012。",
        "模拟回调中断，再通过 query 从水位线补齐状态。",
    ])
    add_heading(doc, "14.2 最小验收用例", 2)
    add_table(
        doc,
        ["编号", "场景", "预期"],
        [
            ("A01", "合法 v2 单任务下发", "成功，返回 pending 任务"),
            ("A02", "同 appId + taskNo 重发", "返回相同 data.id，不重复建任务"),
            ("A03", "篡改 body 后不重签", "4012"),
            ("A04", "重复 timestamp + nonce", "第二次 4013"),
            ("A05", "未授权 appId", "403"),
            ("A06", "任务链含一个未授权候选", "整链拒绝"),
            ("A07", "query limit 截断", "循环翻页无遗漏，边界重复被去重"),
            ("A08", "Webhook 正常", "验签成功、落库、2xx、投递 ok"),
            ("A09", "Webhook 返回 500", "指数退避，attempts 增加"),
            ("A10", "Webhook 同事件重复", "业务只处理一次"),
            ("A11", "snapshot 设备未连接", "connection=null，接口仍成功"),
            ("A12", "回调断线后 REST 补偿", "最终状态与 WCS 一致"),
        ],
        widths=[900, 3900, 4560],
        font_size=9,
    )
    add_heading(doc, "14.3 上线前双方签字项", 2)
    for item in [
        "生产 Base URL、canonicalPath、证书链已确认。",
        "应用与授权设备清单已确认。",
        "任务号/链号规则和复用禁令已确认。",
        "Webhook URL、Secret、事件、作用域已确认。",
        "WCS 主机时区与 sinceMillis 转换已确认。",
        "超时、重试、幂等、补偿查询策略已压测。",
        "密钥保存、轮换和泄露应急流程已确认。",
        "监控告警和双方联系人已进入值班体系。",
    ]:
        add_bullet(doc, "□ " + item)

    add_heading(doc, "15. 常见问题与排障", 1)
    faq = [
        ("Q1：HTTP 是 200，为什么业务仍失败？",
         "开放接口使用统一 ApiResponse，必须看 isSuccess 和 code。HTTP 200 只说明请求到达业务层。"),
        ("Q2：一直 4011 怎么办？",
         "检查五项：请求头是否齐全、AppKey 是否复制正确、应用 enabled 是否为 true、应用是否被删除、开放接口 AppKey 鉴权是否启用。"),
        ("Q3：直连正常，经网关 4012？",
         "优先核对 canonicalPath。当前外部 /api/wcs/open/** 会重写为后端 /open/**，签名 path 应使用后者。"),
        ("Q4：代码看起来一样，还是 4012？",
         "把最终发送的 body 以十六进制或 SHA-256 记录下来，对照签名时的 bodyHash。常见原因是 HTTP 客户端再次序列化、追加换行或改变转义。"),
        ("Q5：偶发 4013？",
         "检查 NTP、容器时区、timestamp 是否误用毫秒，以及并发重试是否复用了 nonce。每一次尝试都生成新 timestamp/nonce/sign。"),
        ("Q6：dispatch 返回 403？",
         "不是密钥问题。检查当前 AppKey 对应的上位应用，是否通过 binding 授权到目标 appId；任务链还要检查所有候选。"),
        ("Q7：相同 taskNo 为什么返回旧任务？",
         "这是幂等行为。WCS 不比较新旧请求体。调用方不能复用任务号修改坐标或 taskType。"),
        ("Q8：Webhook 没收到？",
         "先看订阅 enabled、事件类型和 scope；再看 WCS 服务器到 URL 的 DNS/TLS/防火墙；最后查 /webhook/deliveries 的 last_response。"),
        ("Q9：Webhook 反复收到同一事件？",
         "这是至少一次投递的正常特性。用 event + bizId 做唯一键，重复回调直接返回 2xx。"),
        ("Q10：投递记录 failed 后会怎样？",
         "默认达到最大次数后停止自动重试。修复接收端后，通过 REST query/snapshot 对账补齐；不要依赖 failed 记录无限重投。"),
        ("Q11：为什么 query 出现重复行？",
         "查询使用 last_update_time >= sinceMillis，边界行必然可能重复。按 id + last_update_time 去重，不要把 >= 改成业务侧跳过。"),
        ("Q12：为什么 query 漏数据？",
         "最常见原因是把 serverTimeMillis 当水位线，或 count==limit 时没有继续翻页。正确水位取本批最后一行 last_update_time。"),
        ("Q13：snapshot 为什么只有 50 条任务？",
         "snapshot 只用于快速恢复当前态和最近任务，不是历史接口。完整变化继续走增量 query。"),
        ("Q14：Webhook URL 是内网地址，开启私网封禁后失败？",
         "BLOCK_PRIVATE_HOSTS=true 会拒绝私网/环回地址。私网回调应使用精确 host allowlist、网络 ACL、专用 DNS/出口等控制，并保留安全评审记录。"),
    ]
    for q, a in faq:
        add_heading(doc, q, 3)
        add_para(doc, a)

    add_heading(doc, "16. 生产上线安全清单", 1)
    add_heading(doc, "16.1 WCS 管理员", 2)
    for item in [
        "□ 修改/停用本地默认 admin / 1，替换 CAYLEYWCS_JWT_SECRET。",
        "□ 生产应用 min_sign_version=2，保持 CAYLEYWCS_OPEN_APPKEY_ENABLED=true。",
        "□ 每个系统、每个环境使用独立应用和 Secret。",
        "□ 绑定授权遵循最小权限，只授权实际需要的设备。",
        "□ 管理端 API 与 Swagger 不向不受信网络暴露。",
        "□ 网关启用 HTTPS、限流、访问日志和来源控制。",
        "□ Webhook 配置 HTTPS、精确 host allowlist 和适合部署网络的 SSRF 策略。",
        "□ 监控 4011/4012/4013、403、500、Webhook failed、任务长期 pending。",
    ]:
        add_bullet(doc, item)
    add_heading(doc, "16.2 调用方开发与运维", 2)
    for item in [
        "□ AppSecret/Webhook Secret 只存在服务端受控密钥存储。",
        "□ 日志不打印 Secret、Authorization、完整签名规范串中的敏感业务内容。",
        "□ HMAC 使用最终原始 body；签名比较使用常量时间函数。",
        "□ Nonce 不复用，时间同步有监控。",
        "□ taskNo/chainNo 规则全局可追溯，重试复用，业务变更不复用。",
        "□ Webhook 处理使用 event + bizId 幂等，并校验时间窗口/nonce。",
        "□ query 水位持久化，支持进程重启、分页和边界去重。",
        "□ 生产演练密钥轮换、回调故障、网络断线和补偿恢复。",
    ]:
        add_bullet(doc, item)
    add_callout(doc, "上线原则",
                "安全和可靠性不是“接口通了以后再补”。签名、最小授权、幂等、对账、告警这五项应在联调阶段一起验收。",
                "ok")

    add_page_break(doc)
    add_heading(doc, "附录 A：管理端 API 速查（JWT）", 1)
    add_para(doc, "以下接口用于 WCS 管理配置。除 /login 外均应携带 Authorization: Bearer <access_token>，不应与 /open/** 的 AppKey 鉴权混用。")
    add_table(
        doc,
        ["模块", "接口", "用途"],
        [
            ("认证", "POST /login", "管理员登录"),
            ("认证", "POST /refresh-token", "刷新访问令牌"),
            ("应用", "POST /application/list", "分页列表"),
            ("应用", "POST /application/all", "全部有效应用"),
            ("应用", "POST /application/detail", "详情，body {id}"),
            ("应用", "POST /application/create", "创建并自动生成缺省 AppKey/Secret"),
            ("应用", "POST /application/update", "更新普通字段，不改 AppKey/Secret"),
            ("应用", "POST /application/delete", "软删除"),
            ("应用", "POST /application/reset-secret", "立即重置 Secret"),
            ("绑定", "POST /binding/granted", "查询某上位已授权设备 ID"),
            ("绑定", "POST /binding/grant", "整批设置授权集合"),
            ("Webhook", "POST /webhook/list", "订阅分页"),
            ("Webhook", "POST /webhook/detail", "订阅详情"),
            ("Webhook", "POST /webhook/create", "创建订阅"),
            ("Webhook", "POST /webhook/update", "更新订阅"),
            ("Webhook", "POST /webhook/delete", "软删除订阅"),
            ("Webhook", "POST /webhook/deliveries", "最近投递记录"),
        ],
        widths=[1500, 3800, 4060],
        font_size=8.8,
    )

    add_heading(doc, "附录 B：完整 Webhook 接收伪代码", 1)
    add_code(doc, """
POST /wcs/hook:
  rawBody = readRawBytes()
  ts = header("X-Webhook-Timestamp")
  nonce = header("X-Webhook-Nonce")
  sign = header("X-Webhook-Sign")

  if timestampOutsideWindow(ts): return 401
  if !constantTimeVerify(secret, ts, nonce, rawBody, sign): return 401
  if nonceAlreadyUsed(ts, nonce): return 409

  event = parseJson(rawBody)
  idempotencyKey = event.event + ":" + event.bizId

  begin transaction
    if idempotencyKey already processed:
      commit
      return 204
    update local business state
    save processed idempotencyKey
    save nonce with expiry
  commit
  return 204
""", "text")
    add_para(doc, "如果更新业务状态需要调用多个慢服务，推荐在本地事务里可靠入队，随后返回 2xx，再由异步消费者处理。关键是“已经可靠接住”之后才确认成功。")

    add_heading(doc, "附录 C：联调记录模板", 1)
    add_table(
        doc,
        ["项目", "填写内容"],
        [
            ("环境", "TEST / UAT / PROD"),
            ("请求 Base URL", ""),
            ("canonicalPath 规则", "/open/**"),
            ("WCS 主机时区", ""),
            ("调用方 app_code / appId", ""),
            ("授权设备 appId", ""),
            ("Webhook 名称 / URL", ""),
            ("订阅事件 / scope", ""),
            ("taskNo / chainNo 规则", ""),
            ("query 周期 / limit", ""),
            ("验收日期 / 双方人员", ""),
            ("遗留问题", ""),
        ],
        widths=[3000, 6360],
        first_col_bold=True,
    )

    add_heading(doc, "附录 D：事实来源与版本说明", 1)
    add_para(doc, "本文按 2026-07-23 仓库实现核对，主要事实来源如下：")
    for item in [
        "后端 /open/task、/open/chain、/open/task/query、/open/alarm/query、/open/snapshot 控制器与服务实现。",
        "AppKeyAuthFilter 的 HMAC v1/v2、时间偏差、nonce 防重放与请求体大小限制。",
        "ApplicationController / ApplicationServiceImpl 的应用生成、更新和 Secret 重置规则。",
        "BindingController / BindingService 的整批授权与权限范围。",
        "WebhookController / WebhookScanner / WebhookDeliveryWorker 的订阅、作用域、签名、重试和 URL 安全策略。",
        "前端 ApplicationView、BindingGrantView、WebhookView 的当前配置流程。",
        "仓库 docs/open-api-guide.md 与 CayleyWCS-WMS-Loop.postman_collection.json。",
    ]:
        add_bullet(doc, item)
    add_callout(doc, "版本变更",
                "接口、字段或安全参数升级后，应以部署版本的 /v3/api-docs/open 与对应发布说明为准，并同步更新本对接文档。",
                "tip")

    add_para(doc, "", before=24, after=8)
    p = add_para(doc, "—— 祝联调顺利。遇到问题时，先保留 taskNo/chainNo、调用时间、业务 code、canonicalPath 和投递记录，通常很快就能定位。——",
                 size=10, color=MUTED, italic=True, align=WD_ALIGN_PARAGRAPH.CENTER)

    doc.save(OUTPUT)
    return OUTPUT


def build_external_document():
    """Build the customer-facing guide for an already-provisioned external caller."""
    diagrams = {
        "integration": render_integration_diagram(),
        "sign": render_sign_diagram(),
        "webhook": render_webhook_diagram(),
    }

    doc = Document()
    configure_document(doc)

    # Cover
    add_para(doc, "外部系统接入说明", size=11, bold=True, color=ORANGE,
             before=36, after=18, keep_next=True)
    p = add_para(doc, "", after=6)
    set_paragraph_border(p, "start", "2E74B5", 42, 14)
    r = p.add_run("礁盘工业 WCS 系统\n对接文档")
    set_run_font(r, size=28, bold=True, color=NAVY)
    add_para(doc, "HTTP 版本", size=18, bold=True, color=BLUE, after=8)
    add_para(doc, "面向 WMS / ERP / MES / 第三方调度系统", size=12.5,
             color=MUTED, after=24)
    add_callout(
        doc,
        "这份文档解决什么问题",
        "WCS 已经为你方创建好调用身份、设备权限和密钥。本手册只讲调用方如何拿到 "
        "Base URL、AppKey、AppSecret 与码垛机 appId 后，完成签名、下发码垛任务、"
        "接收完成/失败回调，并用 REST 增量查询做最终对账。",
        "tip",
    )
    add_table(
        doc,
        ["文档信息", "内容"],
        [
            ("版本", "V1.0"),
            ("实现核对日期", "2026-07-23"),
            ("适用系统", "礁盘工业 WCS（CayleyWCS）"),
            ("默认后端端口", "20021"),
            ("开放接口前缀", "/open/**"),
            ("推荐签名版本", "HMAC-SHA256 v2"),
        ],
        widths=[2300, 7060],
        first_col_bold=True,
    )
    add_para(doc, "说明：示例中的域名、AppKey、Secret、appId、坐标与业务单号均为占位值，请替换为 WCS 项目组实际交付的参数。",
             size=9.5, color=MUTED, italic=True, before=8)

    add_page_break(doc)

    # Reading map and scope
    add_heading(doc, "阅读导航", 1)
    add_para(doc, "如果你第一次接入，建议从第 1 章顺着做；如果已经能成功签名，可直接跳到第 5 章。")
    add_table(
        doc,
        ["你现在要做的事", "重点章节", "完成标志"],
        [
            ("准备环境与凭据", "第 1～2 章", "拿到 Base URL、AppKey、AppSecret、码垛机 appId"),
            ("先打通一个请求", "第 3～4 章", "HMAC v2 鉴权通过，isSuccess=true"),
            ("下发码垛任务", "第 5～7 章", "任务状态从 pending 推进到 completed/failed"),
            ("接收结果回告", "第 8 章", "Webhook 验签、幂等和 2xx 确认正常"),
            ("保证不漏账", "第 9～10 章", "断线后能用 query/snapshot 补齐状态"),
            ("上线验收", "第 11～13 章", "幂等、重试、告警和安全检查全部通过"),
        ],
        widths=[2600, 2200, 4560],
    )
    add_heading(doc, "本文范围", 2)
    add_bullet(doc, "包含：开放 HTTP API、HMAC v2、单任务/任务链、Webhook 接收、任务与报警对账、断线恢复。")
    add_bullet(doc, "不包含：WCS 管理端创建应用、设备协议配置、PLC 点位配置、内部绑定和管理端 JWT API。")
    add_bullet(doc, "前提：WCS 项目组已完成上述内部配置，并已把调用参数安全交付给你方。")
    add_callout(
        doc,
        "最重要的判断",
        "dispatch 返回成功只表示“WCS 已接单”，不表示“码垛已完成”。只有任务最终状态为 completed，"
        "才可以在你方业务系统中确认码垛完成；failed/cancelled 必须进入异常流程。",
        "warn",
    )

    add_heading(doc, "1. 接入前，先向 WCS 项目组拿到这些内容", 1)
    add_para(doc, "建议双方用一张交付单确认。少任何一项都可能出现“签名能过，但任务下不去”或“任务完成了，却没收到结果”的情况。")
    add_table(
        doc,
        ["交付项", "示例", "你方用途"],
        [
            ("环境名称", "TEST / UAT / PROD", "避免把测试任务发到生产"),
            ("请求 Base URL", "https://gateway.example.com/api/wcs", "拼接开放接口地址"),
            ("canonicalPath 规则", "/open/**", "参与 HMAC v2 签名"),
            ("AppKey", "ak_wms_prod_xxx", "请求身份；放入 X-App-Key"),
            ("AppSecret", "仅通过安全渠道交付", "HMAC 密钥，不直接发送"),
            ("签名版本", "2", "放入 X-Sign-Version"),
            ("码垛机 appId", "999001", "请求体中的目标设备"),
            ("任务类型/坐标规则", "1=入库；坐标范围按现场表", "生成业务请求"),
            ("WCS 主机时区", "Asia/Shanghai", "解释 last_update_time"),
            ("Webhook Secret", "独立于 AppSecret", "验证 WCS 回调"),
            ("回调订阅确认", "URL、事件、设备作用域", "确认 WCS 已配置并启用"),
            ("联系人与变更窗口", "双方负责人", "密钥轮换、故障升级"),
        ],
        widths=[2100, 2900, 4360],
        first_col_bold=True,
    )
    add_callout(
        doc,
        "密钥怎么放",
        "AppSecret 和 Webhook Secret 请放在服务端密钥管理或受控环境变量中，不写进前端、移动端、"
        "代码仓库、日志、截图或工单正文。两类 Secret 用途不同，不能混用。",
        "danger",
    )

    add_heading(doc, "1.1 你方需要提供给 WCS 的 Webhook 信息", 2)
    add_para(doc, "Webhook 本身由 WCS 项目组配置；你方只需要把接收要求整理清楚并交付给对方：")
    add_table(
        doc,
        ["项目", "建议值/说明"],
        [
            ("回调 URL", "生产使用 HTTPS，例如 https://wms.example.com/integration/wcs/webhook"),
            ("Webhook Secret", "至少 32 字节随机值；由一方生成后走安全渠道共享"),
            ("订阅事件", "task.completed / task.failed / task.cancelled；需要时再加 alarm.*、chain.*"),
            ("设备作用域", "仅订阅项目组交付的码垛机 appId"),
            ("网络条件", "WCS 节点可解析域名并访问目标端口；防火墙已放通"),
            ("成功确认", "你方可靠落库后返回任意 2xx，推荐 204"),
            ("失败语义", "未可靠接住时返回非 2xx，让 WCS 进行重试"),
        ],
        widths=[2200, 7160],
        first_col_bold=True,
    )

    add_heading(doc, "2. 整体业务闭环", 1)
    add_picture(doc, diagrams["integration"], "图 2-1  外部系统、WCS、码垛机与结果对账闭环")
    add_table(
        doc,
        ["步骤", "谁来做", "发生了什么"],
        [
            ("1. 生成任务", "WMS/ERP/MES", "生成唯一 taskNo，准备任务类型、坐标、货物标识"),
            ("2. 签名调用", "WMS/ERP/MES", "对最终 JSON 原始字节做 HMAC v2，调用 task/dispatch"),
            ("3. WCS 接单", "WCS", "鉴权、权限校验、幂等建单，返回 WCS 任务 id 与 pending"),
            ("4. 执行设备任务", "WCS + 码垛机", "WCS 驱动设备握手并持续更新任务状态"),
            ("5. 主动回告", "WCS", "任务进入 completed/failed/cancelled 后发送 Webhook"),
            ("6. 最终对账", "WMS/ERP/MES", "周期调用 task/query；断线时用 snapshot 补齐"),
        ],
        widths=[1800, 2100, 5460],
    )
    add_para(doc, "Webhook 负责“尽快知道”，REST query 负责“最终不漏”。两条通道同时保留，才是完整闭环。")

    add_heading(doc, "3. 请求地址与通用约定", 1)
    add_heading(doc, "3.1 Base URL 与签名路径不是一回事", 2)
    add_table(
        doc,
        ["接入方式", "实际请求 URL 示例", "参与签名的 canonicalPath"],
        [
            ("直连 WCS", "http://wcs-host:20021/open/task/dispatch", "/open/task/dispatch"),
            ("经 Cayley Gateway", "https://gateway.example.com/api/wcs/open/task/dispatch", "/open/task/dispatch"),
        ],
        widths=[1700, 4860, 2800],
    )
    add_callout(
        doc,
        "常见签名错误",
        "经网关调用时，浏览器或 HTTP 客户端看到的 URL 可能带 /api/wcs；但后端验签使用重写后的 "
        "/open/** 路径。除非项目组另有书面说明，canonicalPath 不要带域名、查询串或网关前缀。",
        "warn",
    )
    add_heading(doc, "3.2 通用 HTTP 约定", 2)
    add_table(
        doc,
        ["项目", "约定"],
        [
            ("Method", "本文开放接口全部为 POST"),
            ("Content-Type", "application/json; charset=utf-8"),
            ("请求字段", "camelCase，例如 appId、taskNo、sinceMillis"),
            ("实体响应字段", "当前为 snake_case，例如 task_no、last_update_time"),
            ("HTTP 状态", "业务错误通常仍返回 HTTP 200；必须检查 isSuccess 与 code"),
            ("请求体大小", "默认最大 1 MiB，超出返回 BAD_REQUEST"),
            ("时间戳", "AppKey 请求用 Unix 秒；Webhook 用 Unix 毫秒"),
        ],
        widths=[2100, 7260],
        first_col_bold=True,
    )
    add_code(doc, """
{
  "isSuccess": true,
  "code": 200,
  "errorMessage": "",
  "data": {}
}
""", "json")

    add_heading(doc, "4. HMAC v2：如何使用 WCS 发放的 API 与 Key", 1)
    add_picture(doc, diagrams["sign"], "图 4-1  HMAC v2 计算顺序")
    add_heading(doc, "4.1 每次请求都要带的请求头", 2)
    add_table(
        doc,
        ["Header", "示例", "说明"],
        [
            ("X-App-Key", "ak_wms_prod_xxx", "WCS 发放的 AppKey"),
            ("X-Timestamp", "1784793600", "当前 Unix 秒，默认允许误差 ±300 秒"),
            ("X-Nonce", "550e8400-e29b-41d4-a716-446655440000", "每次请求唯一，禁止复用"),
            ("X-Sign-Version", "2", "推荐并按生产要求使用 v2"),
            ("X-Sign", "小写 hex", "HMAC-SHA256 结果"),
        ],
        widths=[2100, 3900, 3360],
    )
    add_heading(doc, "4.2 v2 规范串", 2)
    add_code(doc, """
bodyHash = lowercase_hex(SHA256(rawRequestBodyBytes))

canonical = appKey
          + "\\n" + timestampSeconds
          + "\\n" + nonce
          + "\\n" + methodUppercase
          + "\\n" + canonicalPath
          + "\\n" + bodyHash

X-Sign = lowercase_hex(HMAC_SHA256(appSecret, UTF8(canonical)))
""", "text")
    add_callout(
        doc,
        "签名后不要再改 JSON",
        "bodyHash 计算的是最终发送的原始字节。签名以后如果又发生缩进、美化、字段重排、字符集变化，"
        "哪怕 JSON 语义相同，WCS 也会判定签名失败。",
        "danger",
    )

    add_heading(doc, "4.3 可直接运行的 Python 签名客户端", 2)
    add_code(doc, r'''
import hashlib
import hmac
import json
import time
import uuid
import requests

BASE_URL = "https://gateway.example.com/api/wcs"
APP_KEY = "replace-with-app-key"
APP_SECRET = "replace-with-app-secret"

def post_open(path: str, payload: dict, timeout=(5, 20)):
    # separators 固定为紧凑 JSON；签名和发送必须复用同一份 body 字节
    body = json.dumps(
        payload, ensure_ascii=False, separators=(",", ":")
    ).encode("utf-8")
    timestamp = str(int(time.time()))
    nonce = str(uuid.uuid4())
    body_hash = hashlib.sha256(body).hexdigest()
    canonical = "\n".join([
        APP_KEY, timestamp, nonce, "POST", path, body_hash
    ])
    sign = hmac.new(
        APP_SECRET.encode("utf-8"),
        canonical.encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()

    response = requests.post(
        BASE_URL + path,
        data=body,
        headers={
            "Content-Type": "application/json; charset=utf-8",
            "X-App-Key": APP_KEY,
            "X-Timestamp": timestamp,
            "X-Nonce": nonce,
            "X-Sign-Version": "2",
            "X-Sign": sign,
        },
        timeout=timeout,
    )
    response.raise_for_status()
    result = response.json()
    if not result.get("isSuccess"):
        raise RuntimeError(
            f"WCS business error: code={result.get('code')}, "
            f"message={result.get('errorMessage')}"
        )
    return result["data"]
''', "python")
    add_para(doc, "生产实现建议把签名封装成统一 HTTP 客户端拦截器，并在日志中只记录 AppKey 尾号、canonicalPath、timestamp、nonce、bodyHash 和业务 code，绝不记录 Secret。")

    add_heading(doc, "5. 开放 HTTP 接口总览", 1)
    add_table(
        doc,
        ["接口", "用途", "码垛对接中的位置"],
        [
            ("POST /open/task/dispatch", "下发单个设备任务", "主接口：下发一次码垛/入库/出库任务"),
            ("POST /open/task/query", "按水位线增量查询任务", "主对账：补齐 completed/failed/cancelled"),
            ("POST /open/alarm/query", "按水位线增量查询报警", "异常对账：同步设备故障与恢复"),
            ("POST /open/snapshot", "获取单台设备当前快照", "断线恢复：连接态、活动报警、最近任务"),
            ("POST /open/chain/dispatch", "下发串行任务链", "可选：多设备、多步骤编排"),
            ("POST /open/chain/query", "增量查询任务链", "可选：任务链最终状态对账"),
        ],
        widths=[3200, 2700, 3460],
        first_col_bold=True,
    )
    add_para(doc, "纯码垛机项目通常先打通 task/dispatch + task/query + snapshot；Webhook 用于低延迟回告，alarm/query 用于异常闭环。")

    add_heading(doc, "6. 下发码垛任务：POST /open/task/dispatch", 1)
    add_heading(doc, "6.1 请求字段", 2)
    add_table(
        doc,
        ["字段", "类型", "必填", "说明"],
        [
            ("appId", "integer", "是", "WCS 交付的目标码垛机应用 ID"),
            ("taskNo", "string", "是", "你方生成的幂等任务号；同一设备内唯一"),
            ("taskType", "string", "是", "任务类型；标准协议使用字符串数字"),
            ("takeRow / Column / Floor", "integer", "条件", "取货坐标，按现场协议填写"),
            ("putRow / Column / Floor", "integer", "条件", "放货坐标，按现场协议填写"),
            ("portNum", "integer", "条件", "取放货口编号，按现场协议填写"),
            ("priority", "integer", "否", "默认 0；数值越大越优先"),
            ("wmsRef", "string", "建议", "业务单据号/波次号/托盘业务引用"),
            ("payload", "object", "建议", "货物、托盘、批次等透传信息"),
        ],
        widths=[2600, 1500, 1100, 4160],
    )
    add_heading(doc, "6.2 标准任务类型与坐标范围", 2)
    add_table(
        doc,
        ["taskType", "语义", "填写提示"],
        [
            ('"0"', "无任务", "业务系统不要主动下发"),
            ('"1"', "入库", "填写取货口与目标库位"),
            ('"2"', "出库", "填写来源库位与出货口"),
            ('"3"', "移库", "填写来源库位与目标库位"),
            ('"4"', "移动", "按现场协议填写定位坐标"),
            ('"5"', "急停", "安全动作，是否开放由项目组确认"),
            ('"6"', "解警", "恢复动作，是否开放由项目组确认"),
            ('"7"', "充电（预留）", "当前不作为标准码垛任务使用"),
        ],
        widths=[1400, 1800, 6160],
    )
    add_para(doc, "仓库内标准点位范围是：排 1～2、列 1～7、层 1～9、货口 1～2；实际生产必须以 WCS 项目组交付的现场坐标表为准，不要把示例范围当作所有项目的固定规则。")

    add_heading(doc, "6.3 入库/码垛请求示例", 2)
    add_code(doc, """
{
  "appId": 999001,
  "taskNo": "WMS-PALLET-20260723-000001",
  "taskType": "1",
  "takeRow": 1,
  "takeColumn": 1,
  "takeFloor": 1,
  "putRow": 2,
  "putColumn": 5,
  "putFloor": 6,
  "portNum": 1,
  "priority": 10,
  "wmsRef": "IN-20260723-000088",
  "payload": {
    "palletCode": "PLT-000001",
    "materialCode": "SKU-10086",
    "lotNo": "LOT-20260723",
    "quantity": 48
  }
}
""", "json")
    add_heading(doc, "6.4 成功响应怎么理解", 2)
    add_code(doc, """
{
  "isSuccess": true,
  "code": 200,
  "errorMessage": "",
  "data": {
    "id": 1234,
    "task_no": "WMS-PALLET-20260723-000001",
    "app_id": 999001,
    "wms_ref": "IN-20260723-000088",
    "task_type": "1",
    "priority": 10,
    "status": "pending",
    "handshake_step": "CREATED",
    "error_code": 0,
    "payload": {"palletCode": "PLT-000001"},
    "last_update_time": "2026-07-23T16:40:00"
  }
}
""", "json")
    add_table(
        doc,
        ["字段", "你方应如何使用"],
        [
            ("data.id", "保存为 WCS 任务 ID，便于排障与 Webhook bizId 关联"),
            ("task_no", "与你方 taskNo 核对；作为主要业务关联键"),
            ("wms_ref", "关联入库单、出库单或波次单"),
            ("status=pending", "只表示已接单，等待 WCS 调度"),
            ("handshake_step", "设备处理内部步骤，用于排障，不作为业务完成依据"),
            ("error_code", "0 表示当前未记录设备故障；非 0 进入异常处理"),
        ],
        widths=[2600, 6760],
        first_col_bold=True,
    )

    add_heading(doc, "7. 幂等、状态机与“什么时候算完成”", 1)
    add_heading(doc, "7.1 taskNo 是你的防重复保险", 2)
    add_para(doc, "相同 appId + taskNo 重复下发独立任务时，WCS 返回第一次创建的任务，不重复建单。")
    add_table(
        doc,
        ["场景", "正确做法", "不要这样做"],
        [
            ("dispatch 超时，不知道 WCS 是否收到", "使用原 taskNo、原业务内容重试", "立即生成新 taskNo 再发"),
            ("同一任务人工补发", "查询旧任务；确认需要新动作后再生成新 taskNo", "复用旧 taskNo 修改坐标"),
            ("客户端自动重试", "保持 taskNo 不变，每次重算 timestamp/nonce/sign", "复用 nonce 或复用旧签名"),
            ("幂等命中", "以返回的既有 data.id 和状态为准", "假设 WCS 会比较新旧请求体"),
        ],
        widths=[2300, 3650, 3410],
    )
    add_callout(
        doc,
        "同一 taskNo 不可变更业务内容",
        "WCS 幂等命中后直接返回已有任务，不会拿新请求逐字段比较。你方必须把 taskNo 与任务类型、坐标、"
        "托盘号等内容绑定保存；要改变动作，必须生成新的 taskNo。",
        "warn",
    )
    add_heading(doc, "7.2 任务状态", 2)
    add_table(
        doc,
        ["状态", "含义", "你方业务动作"],
        [
            ("pending", "WCS 已接单，等待调度", "保持“处理中”"),
            ("waiting", "任务链中的后续段等待激活", "保持“处理中”"),
            ("dispatched", "已把命令下发给设备", "保持“处理中”"),
            ("executing", "码垛机正在执行", "保持“处理中”"),
            ("completed", "设备任务完成", "确认码垛完成并记账"),
            ("failed", "执行失败", "冻结自动完成，记录 error_code 并告警"),
            ("cancelled", "任务已取消", "按取消业务处理，不计完成"),
        ],
        widths=[1700, 3700, 3960],
    )
    add_para(doc, "推荐本地业务表至少保存：task_no、wms_ref、wcs_task_id、target_app_id、request_hash、wcs_status、error_code、last_update_time、completed_at、last_reconcile_at。")

    add_heading(doc, "8. Webhook：让 WCS 主动告诉你结果", 1)
    add_picture(doc, diagrams["webhook"], "图 8-1  Webhook 至少一次投递与 REST 补偿")
    add_heading(doc, "8.1 WCS 会回调哪些关键事件", 2)
    add_table(
        doc,
        ["事件", "含义", "建议处理"],
        [
            ("task.completed", "单任务完成", "幂等更新本地任务为完成"),
            ("task.failed", "单任务失败", "记录 errorCode，生成异常/人工处理单"),
            ("task.cancelled", "单任务取消", "记录取消，不做完成记账"),
            ("alarm.raised", "设备报警产生", "生成设备异常提示"),
            ("alarm.cleared", "设备报警清除", "关闭或更新异常提示"),
            ("chain.completed / failed / cancelled", "任务链终态", "仅使用任务链时处理"),
        ],
        widths=[3300, 3000, 3060],
        first_col_bold=True,
    )
    add_heading(doc, "8.2 回调请求与签名", 2)
    add_code(doc, """
POST /integration/wcs/webhook HTTP/1.1
Content-Type: application/json
X-Webhook-Timestamp: 1784793600123
X-Webhook-Nonce: 91d25d8b0cb398d05d9aaad5
X-Webhook-Sign: <lowercase hex hmac-sha256>
""", "http")
    add_code(doc, """
bodyHash = lowercase_hex(SHA256(rawRequestBodyBytes))
canonical = timestampMillis + "\\n" + nonce + "\\n" + bodyHash
expectedSign = lowercase_hex(HMAC_SHA256(webhookSecret, UTF8(canonical)))
""", "text")
    add_callout(
        doc,
        "两个时间戳单位不同",
        "你方调用 WCS 时 X-Timestamp 是 Unix 秒；WCS 回调的 X-Webhook-Timestamp 是 Unix 毫秒。"
        "这是联调中很容易写错的一处。",
        "warn",
    )
    add_heading(doc, "8.3 任务完成事件示例", 2)
    add_code(doc, """
{
  "event": "task.completed",
  "bizId": 1234,
  "timestamp": 1784793600123,
  "taskNo": "WMS-PALLET-20260723-000001",
  "appId": 999001,
  "status": "completed",
  "errorCode": 0,
  "chainId": null,
  "wmsRef": "IN-20260723-000088"
}
""", "json")
    add_heading(doc, "8.4 Python 验签示例", 2)
    add_code(doc, r'''
import hashlib
import hmac
import json
import time

def verify_webhook(headers, raw_body: bytes, secret: str,
                   max_skew_ms: int = 300_000):
    ts = headers["X-Webhook-Timestamp"]
    nonce = headers["X-Webhook-Nonce"]
    actual = headers["X-Webhook-Sign"]

    if abs(int(time.time() * 1000) - int(ts)) > max_skew_ms:
        raise ValueError("webhook timestamp expired")

    body_hash = hashlib.sha256(raw_body).hexdigest()
    canonical = f"{ts}\n{nonce}\n{body_hash}"
    expected = hmac.new(
        secret.encode("utf-8"),
        canonical.encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()
    if not hmac.compare_digest(expected, actual):
        raise ValueError("invalid webhook signature")

    event = json.loads(raw_body)
    return event, nonce
''', "python")
    add_heading(doc, "8.5 接收端正确处理顺序", 2)
    add_table(
        doc,
        ["顺序", "处理动作"],
        [
            ("步骤 1", "读取原始 body 字节，不要先解析再重新序列化"),
            ("步骤 2", "校验毫秒时间戳窗口、nonce 防重放和 HMAC 签名"),
            ("步骤 3", "用 event + bizId 作为业务幂等键"),
            ("步骤 4", "在本地事务中可靠落库或可靠入队"),
            ("步骤 5", "已可靠接住后返回 2xx；推荐 204"),
            ("步骤 6", "处理失败或未可靠落库时返回非 2xx，让 WCS 重试"),
        ],
        widths=[1800, 7560],
        first_col_bold=True,
    )
    add_para(doc, "Webhook 是至少一次投递，重复回调是正常情况。接收端不能因为重复事件再次扣减库存、重复完成业务单或重复触发下游动作。")

    add_heading(doc, "9. REST 增量对账：保证任务最终不漏", 1)
    add_heading(doc, "9.1 POST /open/task/query", 2)
    add_code(doc, """
{
  "appId": 999001,
  "sinceMillis": 0,
  "limit": 200
}
""", "json")
    add_code(doc, """
{
  "isSuccess": true,
  "code": 200,
  "errorMessage": "",
  "data": {
    "serverTimeMillis": 1784793600123,
    "count": 2,
    "rows": []
  }
}
""", "json")
    add_table(
        doc,
        ["字段", "说明"],
        [
            ("appId", "指定 WCS 交付的码垛机 appId；也可按项目约定省略以查询全部可见设备"),
            ("sinceMillis", "Unix 毫秒水位线；0 表示从最早记录开始"),
            ("limit", "<=0 时默认 200，最大 2000；生产建议 200～500"),
            ("serverTimeMillis", "服务端响应时间，不可直接当作下一次水位线"),
            ("rows", "按 last_update_time、id 升序返回的任务记录"),
        ],
        widths=[2300, 7060],
        first_col_bold=True,
    )
    add_heading(doc, "9.2 正确的水位线算法", 2)
    add_table(
        doc,
        ["顺序", "动作", "为什么"],
        [
            ("步骤 1", "从本地持久化水位线调用 query", "进程重启后仍能续拉"),
            ("步骤 2", "按响应顺序处理 rows", "与 WCS 排序保持一致"),
            ("步骤 3", "按 id + last_update_time 幂等更新", "接口使用 >=，边界记录会重复"),
            ("步骤 4", "把最后一行 last_update_time 转为 Unix 毫秒并保存", "只推进到实际处理完的位置"),
            ("步骤 5", "count == limit 时立即继续拉", "说明本批可能被截断"),
            ("步骤 6", "count < limit 后进入正常轮询周期", "本轮已追平"),
        ],
        widths=[1500, 3900, 3960],
    )
    add_callout(
        doc,
        "不要用 serverTimeMillis 推水位",
        "如果结果被 limit 截断，而你直接把 serverTimeMillis 保存成下一次 sinceMillis，尚未返回的记录会被永久跳过。"
        "水位只能推进到本批最后一条已成功处理的 last_update_time。",
        "danger",
    )
    add_para(doc, "last_update_time 当前是无时区偏移的 ISO 本地时间。双方必须书面确认 WCS 主机时区；解析时按该时区转换为 Unix 毫秒。")

    add_heading(doc, "9.3 推荐对账频率", 2)
    add_table(
        doc,
        ["场景", "建议频率"],
        [
            ("Webhook 正常", "task/query 每 30～60 秒兜底一次"),
            ("Webhook 暂不可用", "task/query 每 3～10 秒；结合任务量调整"),
            ("存在在途任务", "可提高到 2～5 秒，任务全部终态后恢复低频"),
            ("网络恢复后", "先 snapshot，再从持久化水位线连续 query 到追平"),
            ("大量历史补拉", "limit 取 500～2000，count == limit 立即翻页"),
        ],
        widths=[2600, 6760],
    )

    add_heading(doc, "10. 断线恢复、设备快照与报警", 1)
    add_heading(doc, "10.1 POST /open/snapshot", 2)
    add_code(doc, """
{"appId": 999001}
""", "json")
    add_code(doc, """
{
  "isSuccess": true,
  "code": 200,
  "errorMessage": "",
  "data": {
    "serverTimeMillis": 1784793600123,
    "connection": {
      "appId": 999001,
      "appCode": "PALLET01",
      "appName": "礁盘工业码垛机",
      "deviceType": "palletizer",
      "protocolType": "opcua",
      "state": "RUNNING",
      "lastHeartbeatAt": 1784793599900,
      "retryCount": 0,
      "latest": {}
    },
    "alarms": [],
    "tasks": []
  }
}
""", "json")
    add_para(doc, "connection 为 null 表示设备连接当前未打开；alarms 是活动报警；tasks 最多 50 条，只用于快速恢复现场，不是完整历史。")
    add_heading(doc, "10.2 推荐恢复流程", 2)
    add_table(
        doc,
        ["顺序", "动作"],
        [
            ("步骤 1", "发现网络恢复、回调长时间中断或本地服务重启"),
            ("步骤 2", "调用 /open/snapshot 恢复设备连接态、活动报警与最近任务"),
            ("步骤 3", "从本地持久化水位线调用 /open/task/query 连续追平"),
            ("步骤 4", "调用 /open/alarm/query 连续追平报警变化"),
            ("步骤 5", "若使用任务链，再调用 /open/chain/query"),
            ("步骤 6", "按幂等键合并 Webhook 与 REST 结果，恢复正常轮询"),
        ],
        widths=[1800, 7560],
        first_col_bold=True,
    )
    add_heading(doc, "10.3 POST /open/alarm/query", 2)
    add_code(doc, """
{
  "appId": 999001,
  "sinceMillis": 0,
  "limit": 200
}
""", "json")
    add_table(
        doc,
        ["报警字段", "说明"],
        [
            ("id", "WCS 报警 ID，建议作为本地幂等键的一部分"),
            ("app_id", "发生报警的码垛机 appId"),
            ("fault_code", "设备故障码；-1 通常表示通讯断开类"),
            ("level", "报警级别"),
            ("message / suggestion", "故障描述与处理建议"),
            ("status", "raised / ack / cleared"),
            ("raised_time / cleared_time", "产生与清除时间"),
            ("last_update_time", "报警对账水位字段"),
        ],
        widths=[2700, 6660],
        first_col_bold=True,
    )

    add_heading(doc, "11. 可选：多步骤任务链", 1)
    add_para(doc, "如果项目只对接一台码垛机，可以跳过本章。只有 WCS 项目组明确要求由 WCS 编排多个设备或多个步骤时，才使用任务链。")
    add_heading(doc, "11.1 POST /open/chain/dispatch", 2)
    add_code(doc, """
{
  "chainNo": "WMS-CHAIN-20260723-0001",
  "priority": 10,
  "selectStrategy": "idle_first",
  "wmsRef": "OUT-20260723-0008",
  "payload": {"palletCode": "PLT-000001"},
  "segments": [
    {
      "seq": 1,
      "appId": 999001,
      "taskType": "1",
      "takeRow": 1,
      "takeColumn": 1,
      "takeFloor": 1,
      "putRow": 2,
      "putColumn": 5,
      "putFloor": 6,
      "portNum": 1
    }
  ]
}
""", "json")
    add_para(doc, "chainNo 是链级幂等键，最大 100 字符；同一 chainNo 重发返回既有链。segments 至少一段，固定设备使用 appId，候选设备组使用 candidateAppIds，两者必须二选一。")
    add_heading(doc, "11.2 POST /open/chain/query", 2)
    add_code(doc, """
{"sinceMillis": 0, "limit": 200}
""", "json")
    add_para(doc, "链查询只返回当前 AppKey 下发的链。链终态为 completed、failed、cancelled；对账算法与 task/query 相同。")

    add_heading(doc, "12. 错误码、超时与重试", 1)
    add_table(
        doc,
        ["code", "含义", "调用方处理"],
        [
            ("200", "成功", "处理 data"),
            ("400", "请求格式/大小错误", "修正 JSON，不盲目重试"),
            ("4011", "AppKey 无效或缺头", "核对环境、AppKey、应用状态"),
            ("4012", "签名错误/版本过低", "核对 body 字节、路径、Secret、版本"),
            ("4013", "时间戳或 nonce 重放", "校时，生成新 timestamp/nonce/sign"),
            ("403", "没有目标设备权限", "把 appId、AppKey、环境交给 WCS 项目组核对"),
            ("404", "资源不存在", "核对 ID"),
            ("409", "状态冲突", "先 query 最新状态再决定"),
            ("4241", "任务参数非法", "核对 taskNo、taskType、坐标和 segments"),
            ("4242", "任务号冲突", "查询既有任务并核对幂等内容"),
            ("4243", "任务状态不允许操作", "查询最新状态"),
            ("500", "WCS 内部错误", "保留 taskNo/nonce/时间，有限重试并告警"),
        ],
        widths=[1200, 2900, 5260],
    )
    add_heading(doc, "12.1 推荐 HTTP 参数", 2)
    add_table(
        doc,
        ["项目", "建议"],
        [
            ("连接超时", "3～5 秒"),
            ("单次请求总超时", "15～30 秒"),
            ("自动重试次数", "最多 3 次，指数退避并加随机抖动"),
            ("可重试条件", "网络错误、超时、明确可重试的 5xx"),
            ("下发重试", "始终复用原 taskNo/chainNo；每次重新生成 timestamp、nonce、sign"),
            ("业务错误", "HTTP 200 但 isSuccess=false 时按 code 处理，不统一重试"),
        ],
        widths=[2500, 6860],
        first_col_bold=True,
    )

    add_heading(doc, "13. 联调与上线验收清单", 1)
    add_para(doc, "建议按下面顺序验证，每一项都留一条可追溯记录。")
    add_table(
        doc,
        ["序号", "验收项", "通过标准"],
        [
            ("01", "环境与校时", "Base URL 正确；双方时间误差小于 300 秒"),
            ("02", "HMAC v2", "正常请求成功；篡改 body 返回 4012"),
            ("03", "nonce 防重放", "相同 nonce 重复请求返回 4013"),
            ("04", "单任务下发", "isSuccess=true，返回 data.id 和 pending"),
            ("05", "幂等重发", "相同 appId + taskNo 返回同一 data.id"),
            ("06", "设备执行", "状态依次推进并最终到 completed 或可解释的 failed"),
            ("07", "Webhook 验签", "原始 body 验签通过，伪造签名被拒绝"),
            ("08", "Webhook 幂等", "同一 event + bizId 重复回调不重复记账"),
            ("09", "Webhook 重试", "接收端返回非 2xx 后可观察到再次投递"),
            ("10", "任务对账", "task/query 能补回未消费的终态"),
            ("11", "水位线翻页", "count == limit 时连续拉取，无漏行、可容忍边界重复"),
            ("12", "断线恢复", "snapshot + query 能恢复当前连接态和业务状态"),
            ("13", "报警闭环", "alarm.raised 与 alarm.cleared 可回调/对账"),
            ("14", "密钥保护", "日志、仓库、前端和截图中无 Secret"),
            ("15", "故障升级", "能凭 taskNo、wcs id、时间、code 快速定位"),
        ],
        widths=[900, 3000, 5460],
    )
    add_callout(
        doc,
        "上线门槛",
        "不要把“能收到一次 completed 回调”当作全部验收。真正能上线的标准是：重复请求不重复码垛、"
        "重复回调不重复记账、丢回调后 REST 能补齐、进程重启后水位线还能续跑。",
        "ok",
    )

    add_heading(doc, "附录 A：外部系统配置模板", 1)
    add_table(
        doc,
        ["配置项", "实际值"],
        [
            ("环境", ""),
            ("Base URL", ""),
            ("canonicalPath 前缀", "/open"),
            ("AppKey", ""),
            ("AppSecret 存储位置（不填明文）", ""),
            ("签名版本", "2"),
            ("码垛机 appId", ""),
            ("任务类型与坐标表版本", ""),
            ("WCS 主机时区", ""),
            ("Webhook URL", ""),
            ("Webhook Secret 存储位置（不填明文）", ""),
            ("订阅事件", "task.completed, task.failed, task.cancelled"),
            ("task/query 周期与 limit", ""),
            ("taskNo 生成规则", ""),
            ("业务负责人 / 技术联系人", ""),
        ],
        widths=[3600, 5760],
        first_col_bold=True,
    )

    add_heading(doc, "附录 B：排障时一次性提供这些信息", 1)
    add_table(
        doc,
        ["信息", "说明"],
        [
            ("环境与 Base URL", "TEST/UAT/PROD；可隐藏域名敏感部分"),
            ("请求时间", "带时区，精确到秒"),
            ("canonicalPath", "例如 /open/task/dispatch"),
            ("AppKey", "只提供前后各 4 位，不提供 Secret"),
            ("taskNo / wmsRef", "业务定位主键"),
            ("WCS data.id / bizId", "WCS 内部任务定位键"),
            ("HTTP 状态 + code + errorMessage", "完整保留"),
            ("timestamp / nonce / bodyHash", "可用于复核签名；不要提供 Secret"),
            ("请求体", "脱敏后提供，并确认与签名字节一致"),
            ("最近一次 query 水位线", "定位漏账或时区问题"),
        ],
        widths=[2800, 6560],
        first_col_bold=True,
    )

    add_heading(doc, "附录 C：实现依据与契约提醒", 1)
    add_bullet(doc, "本文按 2026-07-23 仓库中的 /open/task、/open/chain、/open/task/query、/open/alarm/query、/open/snapshot 实现核对。")
    add_bullet(doc, "HMAC v2 依据 AppKeyAuthFilter、AppKeySigner 与 AppKeySignV2Test；请求体按原始字节纳签。")
    add_bullet(doc, "Webhook 依据 WebhookScanner 与 WebhookDeliveryWorker；终态事件至少一次投递，默认最多尝试 6 次。")
    add_bullet(doc, "开放接口请求 DTO 使用 camelCase，实体响应当前使用 snake_case。")
    add_bullet(doc, "业务错误通常使用 HTTP 200 包装；调用方必须检查 isSuccess 与 code。")
    add_bullet(doc, "部署版本升级后，以该环境实际开放契约与 WCS 项目组发布说明为准。")

    add_para(doc, "", before=20)
    add_para(
        doc,
        "—— 祝联调顺利。先把 taskNo、签名和对账水位线设计稳，后面的现场联调会轻松很多。——",
        size=10, color=MUTED, italic=True, align=WD_ALIGN_PARAGRAPH.CENTER,
    )

    doc.save(OUTPUT)
    return OUTPUT


if __name__ == "__main__":
    output = build_external_document()
    print(output)
