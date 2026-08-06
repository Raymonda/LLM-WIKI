import sys
import json
import argparse
import traceback
import re
import base64
import math
import logging as _logging
from pathlib import Path

if sys.stdout and hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
if sys.stderr and hasattr(sys.stderr, 'reconfigure'):
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')

_logging.basicConfig(
    level=_logging.INFO,
    format="%(asctime)s [%(name)s] %(levelname)s %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
    stream=sys.stderr,
)
logger = _logging.getLogger("doc_parser")

BLOCK_TEXT = "text"
BLOCK_CHART = "chart"

_DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode"
_OCR_BASE_URL = _DEFAULT_BASE_URL
_DIAGRAM_BASE_URL = _DEFAULT_BASE_URL


def _check_pdf_encryption(file_path):
    try:
        from pypdf import PdfReader
        reader = PdfReader(file_path)
        if reader.is_encrypted:
            try:
                reader.decrypt("")
            except Exception:
                raise RuntimeError("PDF 文件已加密，请先解密后再上传")
        return reader
    except ImportError:
        return None
    except RuntimeError:
        raise
    except Exception:
        return None


def _extract_metadata(reader):
    if reader is None:
        return {}
    try:
        meta = reader.metadata
        if meta is None:
            return {}
        return {k: str(v) for k, v in {
            "title": getattr(meta, "title", None),
            "author": getattr(meta, "author", None),
            "subject": getattr(meta, "subject", None),
            "creator": getattr(meta, "creator", None),
        }.items() if v}
    except Exception:
        return {}


def _extract_text_fitz(file_path):
    import fitz
    try:
        doc = fitz.open(file_path)
    except AssertionError:
        return None
    except Exception:
        return None

    use_markdown = True
    try:
        doc[0].get_text("markdown")
    except AssertionError:
        use_markdown = False
    except Exception:
        use_markdown = False

    pages = []
    low_text_pages = 0

    for i, page in enumerate(doc):
        text = ""
        if use_markdown:
            text = page.get_text("markdown")
        if len(text.strip()) < 50:
            text = page.get_text("text")
            if len(text.strip()) < 50:
                low_text_pages += 1
        pages.append(text)

    doc.close()

    content = "\n\n---\n\n".join(pages)
    if len(content.strip()) < 10:
        return None

    return {
        "content": content,
        "pageCount": len(pages),
        "lowTextPages": low_text_pages,
        "hasScanWarning": low_text_pages > len(pages) * 0.3,
        "extractionEngine": "fitz",
    }


def _extract_text_pypdf(file_path):
    try:
        from pypdf import PdfReader
        reader = PdfReader(file_path)
        pages = []
        for page in reader.pages:
            text = page.extract_text() or ""
            pages.append(text)
        content = "\n\n---\n\n".join(pages)
        if len(content.strip()) < 10:
            return None
        return {
            "content": content,
            "pageCount": len(reader.pages),
            "lowTextPages": 0,
            "hasScanWarning": False,
            "extractionEngine": "pypdf",
        }
    except ImportError:
        return None
    except Exception:
        return None


def _detect_columns_and_sort_blocks(page):
    """从 fitz dict 输出检测栏数并排序块到正确阅读顺序"""
    d = page.get_text("dict", sort=False)
    blocks = d.get("blocks", [])
    page_width = page.rect.width

    text_blocks = [b for b in blocks if b.get("type") == 0 and b.get("lines")]
    img_blocks = [b for b in blocks if b.get("type") == 1]

    for b in text_blocks:
        bbox = b["bbox"]
        b["x_center"] = (bbox[0] + bbox[2]) / 2
        b["y_top"] = bbox[1]

    left_count = sum(1 for b in text_blocks if b["x_center"] < page_width * 0.4)
    right_count = sum(1 for b in text_blocks if b["x_center"] > page_width * 0.6)
    center_count = len(text_blocks) - left_count - right_count

    is_multi_column = (left_count >= 3 and right_count >= 3
                       and center_count < left_count * 0.5)

    if is_multi_column:
        left_blocks = sorted([b for b in text_blocks if b["x_center"] < page_width * 0.55],
                             key=lambda b: b["y_top"])
        right_blocks = sorted([b for b in text_blocks if b["x_center"] >= page_width * 0.55],
                             key=lambda b: b["y_top"])
        sorted_text_blocks = left_blocks + right_blocks
    else:
        sorted_text_blocks = sorted(text_blocks, key=lambda b: b["y_top"])

    for ib in img_blocks:
        ib["y_top"] = ib["bbox"][1]

    all_blocks = sorted(sorted_text_blocks + img_blocks, key=lambda b: b["y_top"])

    return all_blocks, is_multi_column


def _compute_body_font_size(doc, sample_pages=20):
    """采样前N页，按字符数加权统计字号分布，返回正文字号（最高频字号）"""
    size_char_count = {}
    n = min(sample_pages, len(doc))
    for i in range(n):
        try:
            page = doc[i]
            d = page.get_text("dict")
            for block in d.get("blocks", []):
                if block.get("type") != 0:
                    continue
                for line in block.get("lines", []):
                    for span in line.get("spans", []):
                        text = span.get("text", "").strip()
                        if not text:
                            continue
                        size = round(span.get("size", 12.0), 1)
                        if size < 6.0 or size > 72.0:
                            continue
                        size_char_count[size] = size_char_count.get(size, 0) + len(text)
        except Exception:
            continue

    if not size_char_count:
        return 12.0

    body_size = max(size_char_count, key=size_char_count.get)
    return body_size


def _get_block_font_info(block):
    """获取 block 的加权平均字号和是否全粗体"""
    total_chars = 0
    weighted_size = 0.0
    all_bold = True
    has_text = False

    for line in block.get("lines", []):
        for span in line.get("spans", []):
            text = span.get("text", "").strip()
            if not text:
                continue
            has_text = True
            n = len(text)
            size = span.get("size", 12.0)
            weighted_size += size * n
            total_chars += n
            flags = span.get("flags", 0)
            if not (flags & 2 ** 4):
                all_bold = False

    if not has_text or total_chars == 0:
        return 0.0, False

    return weighted_size / total_chars, all_bold


def _block_to_markdown(block, body_font_size=None):
    """将单个 fitz dict 块转为 Markdown 文本（支持字号驱动标题检测）"""
    if block.get("type") == 1:
        xref = block.get("image", 0)
        y_top = block.get("y_top", 0)
        return f"[IMAGE_PLACEHOLDER:{xref}:{y_top}]"

    lines = block.get("lines", [])
    if not lines:
        return ""

    heading_level = 0
    if body_font_size and body_font_size > 0:
        block_size, all_bold = _get_block_font_info(block)
        if block_size > 0:
            plain_text = "".join(
                span.get("text", "")
                for line in lines
                for span in line.get("spans", [])
            ).strip()
            text_len = len(plain_text)
            if text_len > 0 and text_len < 120:
                size_diff = block_size - body_font_size
                if size_diff >= 6:
                    heading_level = 1
                elif size_diff >= 3:
                    heading_level = 2
                elif size_diff >= 1.5:
                    heading_level = 3
                elif size_diff >= 0.5 and all_bold and text_len < 80:
                    heading_level = 3

    if heading_level > 0:
        plain_text = "".join(
            span.get("text", "")
            for line in lines
            for span in line.get("spans", [])
        ).strip()
        if plain_text:
            return f"{'#' * heading_level} {plain_text}"

    parts = []
    for line in lines:
        spans = line.get("spans", [])
        line_parts = []
        for span in spans:
            text = span.get("text", "")
            if not text.strip():
                line_parts.append(text)
                continue

            flags = span.get("flags", 0)
            is_bold = flags & 2 ** 4
            is_italic = flags & 2 ** 1

            if is_bold and is_italic:
                line_parts.append(f"***{text}***")
            elif is_bold:
                line_parts.append(f"**{text}**")
            elif is_italic:
                line_parts.append(f"*{text}*")
            else:
                line_parts.append(text)

        parts.append("".join(line_parts))

    return "\n".join(parts)


def _rows_to_markdown_table(rows):
    """将 pdfplumber 的 Rows 转为 Markdown 表格"""
    if not rows or len(rows) < 2:
        return ""

    clean_rows = []
    for row in rows:
        clean_cells = [str(cell).strip() if cell is not None else "" for cell in row]
        clean_rows.append(clean_cells)

    col_count = max(len(r) for r in clean_rows)
    for row in clean_rows:
        while len(row) < col_count:
            row.append("")

    header = "| " + " | ".join(clean_rows[0]) + " |"
    sep = "| " + " | ".join(["---"] * col_count) + " |"
    body_lines = ["| " + " | ".join(r) + " |" for r in clean_rows[1:]]

    return header + "\n" + sep + "\n" + "\n".join(body_lines)


def _extract_tables_pdfplumber(file_path, max_tables_per_page=5):
    """用 pdfplumber 提取 PDF 中所有表格，返回 page_index 到 y_top+markdown 映射"""
    import pdfplumber
    tables_map = {}

    try:
        with pdfplumber.open(file_path) as pdf:
            for i, page in enumerate(pdf.pages):
                found = page.find_tables(table_settings={
                    "vertical_strategy": "lines",
                    "horizontal_strategy": "lines",
                    "intersection_tolerance": 3,
                })
                if not found:
                    found = page.find_tables(table_settings={
                        "vertical_strategy": "text",
                        "horizontal_strategy": "text",
                    })

                tables_on_page = []
                for table in found[:max_tables_per_page]:
                    rows = table.extract()
                    if not rows or len(rows) < 2:
                        continue
                    md = _rows_to_markdown_table(rows)
                    if not md.strip():
                        continue
                    y_top = table.bbox[1] if table.bbox else 0
                    tables_on_page.append((y_top, md))

                if tables_on_page:
                    tables_map[i] = tables_on_page
    except ImportError:
        pass
    except Exception as e:
        print(f"[PDFPLUMBER_WARN] table extraction failed: {type(e).__name__}: {e}", file=sys.stderr)

    return tables_map


def _extract_embedded_images_pdf(file_path, assets_dir, source_id, min_bytes=5120, max_images=50):
    """提取 PDF 内嵌图片，保存到 assets 目录。
    对浏览器不支持的格式（tiff/pbm/ppm/pgm 等）自动转换为 PNG。"""
    import fitz

    BROWSER_FRIENDLY_EXTS = {"png", "jpg", "jpeg", "gif", "webp", "svg"}

    doc = fitz.open(file_path)
    extracted = []

    assets_path = Path(assets_dir)
    assets_path.mkdir(parents=True, exist_ok=True)

    for page_idx in range(len(doc)):
        page = doc[page_idx]
        img_list = page.get_images(full=True)

        img_positions = {}
        d = page.get_text("dict")
        for b in d.get("blocks", []):
            if b.get("type") == 1:
                xref = b.get("image", 0)
                img_positions[xref] = b["bbox"]

        seen_xrefs = set()
        for img_info in img_list:
            xref = img_info[0]
            if xref in seen_xrefs:
                continue
            seen_xrefs.add(xref)

            try:
                img_item = doc.extract_image(xref)
                if not img_item:
                    continue
                img_bytes = img_item["image"]
                if len(img_bytes) < min_bytes:
                    continue

                ext = img_item.get("ext", "png")

                if ext.lower() not in BROWSER_FRIENDLY_EXTS:
                    try:
                        pix = fitz.Pixmap(doc, xref)
                        if pix.n > 4:
                            pix = fitz.Pixmap(fitz.csRGB, pix)
                        img_bytes = pix.tobytes("png")
                        ext = "png"
                    except Exception:
                        try:
                            from PIL import Image
                            import io
                            pil_img = Image.open(io.BytesIO(img_bytes))
                            buf = io.BytesIO()
                            pil_img.save(buf, format="PNG")
                            img_bytes = buf.getvalue()
                            ext = "png"
                        except Exception:
                            pass

                img_filename = f"{source_id}_img{xref}.{ext}"
                img_path = assets_path / img_filename
                img_path.write_bytes(img_bytes)

                bbox = img_positions.get(xref, (0, 0, 0, 0))
                y_top = bbox[1]
                description = f"第{page_idx + 1}页内嵌图片"

                extracted.append({
                    "xref": xref,
                    "page_index": page_idx,
                    "y_top": y_top,
                    "relative_path": img_filename,
                    "description": description,
                    "bytes": len(img_bytes),
                })

                if len(extracted) >= max_images:
                    break
            except Exception:
                continue

        if len(extracted) >= max_images:
            break

    doc.close()
    return extracted


OCR_PROMPT = (
        "请识别并提取这张图片中的所有文字内容，保持原文格式和段落结构。"
        "如果图中没有任何文字（例如照片、纯图），请描述图片的主要内容。"
        "如果是图表或截图，既提取文字也描述图表结构。"
    )

DIAGRAM_ANALYSIS_PROMPT = (
        "你是一个专业的文档结构分析器。这是一页 PDF 文档的渲染图，页面中可能包含文字、表格、流程图、架构图、数据图表等混合内容。\n\n"
        "## 你的任务\n"
        "请在整页中寻找任何结构化视觉元素（流程图、架构图、时序图、数据图表、思维导图、关系图等），并为其生成对应的结构化代码。\n"
        "如果页面中没有明显的结构化视觉元素（只有纯文字和表格），请返回 type=other。\n\n"
        "## 第一步：判断图片类型（严格从以下选项中选择一个）\n"
        "- \"flowchart\" — 流程图、算法流程、工作流图、决策树、**三方关系图**（如 A→B→C 的箭头连线结构）\n"
        "- \"org_chart\" — 组织架构图、层级关系图、树形结构图\n"
        "- \"sequence\" — 时序图、步骤序列图、时间线图\n"
        "- \"architecture\" — 系统架构图、组件关系图、网络拓扑图、**多方协作关系图**\n"
        "- \"data_chart\" — 柱状图、折线图、饼图、散点图、面积图等数据可视化图表\n"
        "- \"mindmap\" — 思维导图、概念图、脑图\n"
        "- \"table\" — 纯表格（无流程/关系线）\n"
        "- \"screenshot\" — 软件界面截图、系统截图\n"
        "- \"photo\" — 照片、插图、装饰图\n"
        "- \"other\" — 以上都不是（纯文字段落、文字为主的表格等）\n\n"
        "## 第二步：根据类型生成结构化代码\n"
        "- 如果是 flowchart → 生成 mermaid flowchart 代码。**注意**：对于方框+箭头连线的关系图（如 公司A→基金公司→公司B 的托管/管理关系），使用 `flowchart LR`（从左到右）或 `flowchart TD`（从上到下），方框用 `[]` 表示，箭头用 `-->` 表示方向。\n"
        "- 如果是 org_chart → 生成 mermaid graph TD 代码。用方括号表示职位/角色，箭头表示汇报关系。\n"
        "- 如果是 sequence → 生成 mermaid sequenceDiagram 代码。用 -> 表示同步消息，--> 表示异步消息。\n"
        "- 如果是 architecture → 生成 mermaid graph/flowchart 代码。用方括号表示组件/服务，箭头表示数据流或调用关系。\n"
        "- 如果是 data_chart → 生成 ECharts option JSON。必须包含 title、legend（若有图例）、xAxis、yAxis、series。\n"
        "- 如果是 mindmap → 生成 mermaid mindmap 代码。根节点缩进，子节点逐层缩进。\n"
        "- 如果是 table → 生成 Markdown 表格。\n"
        "- 如果是 screenshot/photo/other → code 字段留空字符串，仅在 description 中描述内容。\n\n"
        "## 输出格式（严格JSON，不要额外文字）\n"
        '{"type":"...","description":"...","code":"..."}'
    )

_MIN_DIAGRAM_IMAGE_BYTES = 5120  # 5KB — 跳过小图标/logo等装饰性图片


# ================================================================
# _detect_watermarks: 扫描所有页面查找重复水印文本
# ================================================================
def _detect_watermarks(doc, min_page_ratio=0.4, position_tolerance=0.12):
    line_map = {}
    total_pages = len(doc)
    disclaimer_patterns = [
        "仅供合格投资者参考",
        "不得转载或给第三方传阅",
    ]
    for page_idx in range(total_pages):
        page = doc[page_idx]
        pw, ph = page.rect.width, page.rect.height
        if pw <= 0 or ph <= 0:
            continue
        try:
            page_dict = page.get_text("dict")
        except Exception:
            continue
        for block in page_dict.get("blocks", []):
            if block.get("type") != 0:
                continue
            for ln in block.get("lines", []):
                line_text = ""
                colors = set()
                sizes = set()
                first_bbox = None
                for span in ln.get("spans", []):
                    text = span.get("text", "")
                    line_text += text
                    colors.add(span.get("color", -1))
                    sizes.add(span.get("size", 10))
                    if first_bbox is None:
                        first_bbox = span.get("bbox", [0, 0, 0, 0])
                line_text = line_text.strip()
                if not line_text or len(line_text) < 2:
                    continue
                if first_bbox is None:
                    continue
                nx = first_bbox[0] / pw
                ny = first_bbox[1] / ph
                if line_text not in line_map:
                    line_map[line_text] = []
                line_map[line_text].append({
                    "page": page_idx, "nx": nx, "ny": ny,
                    "colors": colors, "sizes": sizes,
                })
    watermark_texts = set()
    for text, entries in line_map.items():
        for dp in disclaimer_patterns:
            if dp in text:
                watermark_texts.add(text)
                break
        if text in watermark_texts:
            continue
        pages = set(e["page"] for e in entries)
        min_pages = min(total_pages, max(3, int(total_pages * min_page_ratio)))
        if len(pages) < min_pages:
            continue
        avg_nx = sum(e["nx"] for e in entries) / len(entries)
        avg_ny = sum(e["ny"] for e in entries) / len(entries)
        max_dx = max(abs(e["nx"] - avg_nx) for e in entries)
        max_dy = max(abs(e["ny"] - avg_ny) for e in entries)
        all_colors = set()
        all_sizes = set()
        for e in entries:
            all_colors.update(e["colors"])
            all_sizes.update(e["sizes"])
        max_size = max(all_sizes)
        is_watermark = False
        reason = ""
        has_light_color = any(c > 8421504 for c in all_colors if isinstance(c, int) and c >= 0)
        if has_light_color:
            is_watermark = True
            reason = "light_color"
        if not is_watermark and (max_dx >= position_tolerance or max_dy >= position_tolerance):
            per_page = {}
            for e in entries:
                per_page.setdefault(e["page"], set()).add(round(e["nx"], 2))
            multi_x_pages = {p for p, xs in per_page.items() if len(xs) >= 3}
            if len(multi_x_pages) >= min_pages:
                is_watermark = True
                reason = f"multi_x_pos({len(multi_x_pages)}pages)"
        if max_dx >= position_tolerance or max_dy >= position_tolerance:
            if is_watermark:
                watermark_texts.add(text)
            continue
        if not is_watermark:
            is_id_pattern = bool(re.match(r'^[\u4e00-\u9fff]{1,5}\s*[A-Za-z]{0,3}\d{3,}', text))
            if is_id_pattern and max_size <= 10 and 0.15 <= avg_ny <= 0.85:
                is_watermark = True
                reason = "id_pattern+small_font+mid_pos"
        if not is_watermark:
            if max_size <= 9.0 and 0.15 <= avg_ny <= 0.85:
                is_watermark = True
                reason = f"small_font({max_size}pt)+mid_pos(y={avg_ny:.2f})"
        if is_watermark:
            watermark_texts.add(text)
    return watermark_texts


# ================================================================
# Chart region detection helpers
# ================================================================
def _is_colored(color_tuple):
    if not color_tuple or len(color_tuple) < 3:
        return False
    rv, gv, bv = color_tuple[0], color_tuple[1], color_tuple[2]
    if rv == gv == bv:
        return False
    if max(abs(rv - gv), abs(rv - bv), abs(gv - bv)) > 0.08:
        return True
    if (rv + gv + bv) / 3 < 0.85:
        return True
    return False


def _rects_overlap(r1, r2, threshold=0.2):
    x0 = max(r1[0], r2[0])
    y0 = max(r1[1], r2[1])
    x1 = min(r1[2], r2[2])
    y1 = min(r1[3], r2[3])
    if x0 >= x1 or y0 >= y1:
        return False
    inter = (x1 - x0) * (y1 - y0)
    area = min(
        (r1[2] - r1[0]) * (r1[3] - r1[1]),
        (r2[2] - r2[0]) * (r2[3] - r2[1])
    )
    return area > 0 and inter / area > threshold


def _bbox_iou(b1, b2):
    x0 = max(b1[0], b2[0])
    y0 = max(b1[1], b2[1])
    x1 = min(b1[2], b2[2])
    y1 = min(b1[3], b2[3])
    if x0 >= x1 or y0 >= y1:
        return 0.0
    inter = (x1 - x0) * (y1 - y0)
    a1 = max((b1[2] - b1[0]) * (b1[3] - b1[1]), 1)
    a2 = max((b2[2] - b2[0]) * (b2[3] - b2[1]), 1)
    return inter / min(a1, a2)


def _add_region(regions, bbox, page_area, pw, ph, source, count,
                min_area_ratio=0.04, min_size_pt=60, max_area_ratio=0.7):
    pad_left = (bbox[2] - bbox[0]) * 0.15
    pad_right = (bbox[2] - bbox[0]) * 0.10
    pad_top = (bbox[3] - bbox[1]) * 0.10
    pad_bottom = (bbox[3] - bbox[1]) * 0.25
    final_bbox = (
        max(0, bbox[0] - pad_left), max(0, bbox[1] - pad_top),
        min(pw, bbox[2] + pad_right), min(ph, bbox[3] + pad_bottom),
    )
    bw = final_bbox[2] - final_bbox[0]
    bh = final_bbox[3] - final_bbox[1]
    area = bw * bh
    area_ratio = area / page_area
    if bw < min_size_pt or bh < min_size_pt:
        return False
    if area_ratio < min_area_ratio or area_ratio > max_area_ratio:
        return False
    for existing in regions:
        if _rects_overlap(existing["bbox"], final_bbox):
            return False
    regions.append({"bbox": final_bbox, "source": source, "primitive_count": count})
    return True


# ================================================================
# _detect_chart_regions: 基于 vector drawing 分析图表区域
# 6种策略: bar/line/pie/area/flowchart/scatter
# ================================================================
def _detect_chart_regions(page, page_num=0, min_area_ratio=0.04, min_size_pt=60):
    regions = []
    pw, ph = page.rect.width, page.rect.height
    page_area = pw * ph
    try:
        img_infos = page.get_image_info(xrefs=False)
        for info in img_infos:
            bbox = info.get("bbox")
            if not bbox:
                continue
            w = bbox[2] - bbox[0]
            h = bbox[3] - bbox[1]
            if w < min_size_pt or h < min_size_pt:
                continue
            if (w * h) / page_area < min_area_ratio:
                continue
            _add_region(regions, (bbox[0], bbox[1], bbox[2], bbox[3]),
                        page_area, pw, ph, "image", 1,
                        min_area_ratio=min_area_ratio, min_size_pt=min_size_pt)
    except Exception:
        pass
    try:
        drawings = page.get_drawings()
    except Exception:
        drawings = []
    colored_rects = []
    all_filled_curves = []
    colored_lines = []
    colored_curve_segments = []
    node_rects = []
    for d in drawings:
        fill = d.get("fill")
        stroke = d.get("color")
        items = d.get("items", [])
        for item in items:
            try:
                if item[0] == "re":
                    r = item[1]
                    rw = abs(float(r[2]) - float(r[0]))
                    rh = abs(float(r[3]) - float(r[1]))
                    if rw < 8 or rh < 8 or not fill:
                        continue
                    bbox_t = (float(r[0]), float(r[1]), float(r[2]), float(r[3]))
                    if _is_colored(fill):
                        colored_rects.append(bbox_t)
                    aspect = max(rw, rh) / max(min(rw, rh), 0.01)
                    if 0.3 < aspect < 3.0 and rw > 20 and rh > 10:
                        node_rects.append({"bbox": bbox_t, "fill": fill, "w": rw, "h": rh})
                elif item[0] == "c":
                    pts = [(item[i][0], item[i][1]) for i in range(1, min(len(item), 5))]
                    if len(pts) < 3:
                        continue
                    span_x = max(p[0] for p in pts) - min(p[0] for p in pts)
                    span_y = max(p[1] for p in pts) - min(p[1] for p in pts)
                    if span_x > 20 or span_y > 20:
                        entry = {"pts": pts, "fill": fill, "stroke": stroke}
                        all_filled_curves.append(entry)
                        if _is_colored(stroke):
                            colored_curve_segments.append({"pts": pts, "color": stroke})
                elif item[0] == "l":
                    p1, p2 = item[1], item[2]
                    length = ((p2[0] - p1[0]) ** 2 + (p2[1] - p1[1]) ** 2) ** 0.5
                    if length > 15 and _is_colored(stroke):
                        colored_lines.append({"p1": (float(p1[0]), float(p1[1])),
                                              "p2": (float(p2[0]), float(p2[1])),
                                              "color": stroke, "length": length})
            except Exception:
                continue
    # Strategy 1: Bar chart — colored filled rects
    if len(colored_rects) >= 3:
        primitives = []
        for r in colored_rects:
            rw = r[2] - r[0]
            rh = r[3] - r[1]
            cy = (r[1] + r[3]) / 2
            primitives.append({"cy": cy, "bbox": r, "w": rw, "h": rh})
        primitives.sort(key=lambda p: p["cy"])
        gap_threshold = max(30, ph * 0.05)
        clusters = []
        current_cluster = [primitives[0]]
        for p in primitives[1:]:
            if abs(p["cy"] - current_cluster[-1]["cy"]) > gap_threshold:
                clusters.append(current_cluster)
                current_cluster = [p]
            else:
                current_cluster.append(p)
        clusters.append(current_cluster)
        for cluster in clusters:
            if len(cluster) < 3:
                continue
            all_bbox = [99999, 99999, -99999, -99999]
            for p in cluster:
                b = p["bbox"]
                all_bbox[0] = min(all_bbox[0], b[0])
                all_bbox[1] = min(all_bbox[1], b[1])
                all_bbox[2] = max(all_bbox[2], b[2])
                all_bbox[3] = max(all_bbox[3], b[3])
            vertical_bars = sum(1 for p in cluster if p["h"] > p["w"])
            horizontal_cells = sum(1 for p in cluster if p["w"] > p["h"])
            heights = [p["h"] for p in cluster]
            avg_h = sum(heights) / len(heights)
            h_std = (sum((h - avg_h) ** 2 for h in heights) / len(heights)) ** 0.5
            h_cv = h_std / max(avg_h, 1)
            if vertical_bars > horizontal_cells and h_cv > 0.15:
                _add_region(regions, tuple(all_bbox), page_area, pw, ph,
                            "bar_chart", len(cluster))
    # Strategy 2: Line chart — colored lines + curves
    line_elements = []
    for ln in colored_lines:
        line_elements.append({"p1": ln["p1"], "p2": ln["p2"], "color": ln["color"]})
    for cv in colored_curve_segments:
        pts = cv["pts"]
        for i in range(len(pts) - 1):
            line_elements.append({"p1": pts[i], "p2": pts[i + 1], "color": cv.get("color")})
    if len(line_elements) >= 5:
        color_groups = {}
        for le in line_elements:
            c = le.get("color")
            if not c or len(c) < 3:
                continue
            key = (round(c[0], 2), round(c[1], 2), round(c[2], 2))
            color_groups.setdefault(key, []).append(le)
        group_bboxes = []
        for color_key, elems in color_groups.items():
            if len(elems) < 3:
                continue
            all_x, all_y = [], []
            for le in elems:
                all_x.extend([le["p1"][0], le["p2"][0]])
                all_y.extend([le["p1"][1], le["p2"][1]])
            gbb = (min(all_x), min(all_y), max(all_x), max(all_y))
            gw = gbb[2] - gbb[0]
            gh = gbb[3] - gbb[1]
            if gw > min_size_pt and gh > min_size_pt:
                group_bboxes.append({"bbox": gbb, "count": len(elems)})
        if len(group_bboxes) >= 2:
            merged = [99999, 99999, -99999, -99999]
            for gb in group_bboxes:
                b = gb["bbox"]
                merged[0] = min(merged[0], b[0])
                merged[1] = min(merged[1], b[1])
                merged[2] = max(merged[2], b[2])
                merged[3] = max(merged[3], b[3])
            _add_region(regions, tuple(merged), page_area, pw, ph,
                        "line_chart", sum(gb["count"] for gb in group_bboxes))
    # Strategy 3: Pie chart — multiple filled arcs with different colors
    filled_arcs = []
    for cv in all_filled_curves:
        fill = cv.get("fill")
        pts = cv["pts"]
        if fill and len(pts) >= 3:
            span_x = max(p[0] for p in pts) - min(p[0] for p in pts)
            span_y = max(p[1] for p in pts) - min(p[1] for p in pts)
            if span_x > 15 and span_y > 15:
                filled_arcs.append({"pts": pts, "fill": fill})
    if len(filled_arcs) >= 3:
        color_groups = {}
        for arc in filled_arcs:
            fill = arc["fill"]
            key = (round(fill[0], 2), round(fill[1], 2), round(fill[2], 2))
            color_groups.setdefault(key, []).append(arc)
        if len(color_groups) >= 3:
            all_x, all_y = [], []
            for arc in filled_arcs:
                for p in arc["pts"]:
                    all_x.append(p[0])
                    all_y.append(p[1])
            bbox = (min(all_x), min(all_y), max(all_x), max(all_y))
            bw = bbox[2] - bbox[0]
            bh = bbox[3] - bbox[1]
            aspect = max(bw, bh) / max(min(bw, bh), 0.01)
            if aspect < 2.5:
                _add_region(regions, bbox, page_area, pw, ph, "pie_chart", len(filled_arcs))
    # Strategy 4: Area chart
    if len(colored_curve_segments) >= 2:
        for cv in colored_curve_segments:
            pts = cv["pts"]
            stroke = cv.get("color")
            if not _is_colored(stroke):
                continue
            bbox_cv = (min(p[0] for p in pts), min(p[1] for p in pts),
                       max(p[0] for p in pts), max(p[1] for p in pts))
            bw = bbox_cv[2] - bbox_cv[0]
            bh = bbox_cv[3] - bbox_cv[1]
            if bw < min_size_pt * 2 or bh < min_size_pt * 2:
                continue
            fill_count = 0
            for fc in all_filled_curves:
                if fc.get("fill") and fc.get("fill") != (1, 1, 1):
                    fb = fc["pts"]
                    fx_min = min(p[0] for p in fb)
                    fx_max = max(p[0] for p in fb)
                    fy_min = min(p[1] for p in fb)
                    fy_max = max(p[1] for p in fb)
                    if (fx_min < bbox_cv[2] and fx_max > bbox_cv[0] and
                            fy_min < bbox_cv[3] and fy_max > bbox_cv[1]):
                        fill_count += 1
            if fill_count > 0:
                _add_region(regions, bbox_cv, page_area, pw, ph, "area_chart", fill_count)
    # Strategy 5: Flowchart/Architecture — node rects + connecting lines
    if len(node_rects) >= 3:
        node_cx = [(n["bbox"][0] + n["bbox"][2]) / 2 for n in node_rects]
        node_cy = [(n["bbox"][1] + n["bbox"][3]) / 2 for n in node_rects]
        spread_x = max(node_cx) - min(node_cx) if len(node_cx) > 1 else 0
        spread_y = max(node_cy) - min(node_cy) if len(node_cy) > 1 else 0
        if spread_x > min_size_pt * 2 or spread_y > min_size_pt * 2:
            connecting = 0
            for ln in colored_lines:
                p1, p2 = ln["p1"], ln["p2"]
                p1_near = p2_near = False
                for n in node_rects:
                    nb = n["bbox"]
                    margin = 20
                    if nb[0] - margin <= p1[0] <= nb[2] + margin and nb[1] - margin <= p1[1] <= nb[3] + margin:
                        p1_near = True
                    if nb[0] - margin <= p2[0] <= nb[2] + margin and nb[1] - margin <= p2[1] <= nb[3] + margin:
                        p2_near = True
                if p1_near or p2_near:
                    connecting += 1
            if connecting >= 2 or len(node_rects) >= 4:
                all_x, all_y = [], []
                for n in node_rects:
                    b = n["bbox"]
                    all_x.extend([b[0], b[2]])
                    all_y.extend([b[1], b[3]])
                bbox = (min(all_x), min(all_y), max(all_x), max(all_y))
                _add_region(regions, bbox, page_area, pw, ph, "flowchart",
                            len(node_rects) + connecting, max_area_ratio=0.85)
    # Strategy 6: Scatter plot
    small_marks = []
    for r in colored_rects:
        rw = abs(r[2] - r[0])
        rh = abs(r[3] - r[1])
        if 2 < rw < 12 and 2 < rh < 12:
            small_marks.append(r)
    if len(small_marks) >= 8:
        all_x, all_y = [], []
        for r in small_marks:
            all_x.extend([r[0], r[2]])
            all_y.extend([r[1], r[3]])
        bbox = (min(all_x), min(all_y), max(all_x), max(all_y))
        bw = bbox[2] - bbox[0]
        bh = bbox[3] - bbox[1]
        if bw > min_size_pt * 3 and bh > min_size_pt * 3:
            _add_region(regions, bbox, page_area, pw, ph, "scatter_plot", len(small_marks))
    return regions


def _is_valid_ocr_text(text):
    if not text or not text.strip():
        return False
    return True


# ================================================================
# Chart image extraction helpers
# ================================================================
def _padded_chart_bbox(bbox, pw, ph):
    bw = bbox[2] - bbox[0]
    bh = bbox[3] - bbox[1]
    pad_left = max(bw * 0.30, 130)
    pad_right = max(bw * 0.35, 80)
    pad_top = max(bh * 0.20, 70)
    pad_bottom = max(bh * 0.35, 40)
    return (
        max(0, bbox[0] - pad_left), max(0, bbox[1] - pad_top),
        min(pw, bbox[2] + pad_right), min(ph, bbox[3] + pad_bottom),
    )


def _extract_text_blocks_simple(page, watermark_texts=None):
    if watermark_texts is None:
        watermark_texts = set()
    blocks = []
    try:
        page_dict = page.get_text("dict")
    except Exception:
        return blocks
    for block in page_dict.get("blocks", []):
        if block.get("type") != 0:
            continue
        bbox = block.get("bbox", [0, 0, 0, 0])
        lines_text = []
        for ln in block.get("lines", []):
            line_text = ""
            for span in ln.get("spans", []):
                line_text += span.get("text", "")
            line_text_stripped = line_text.strip()
            if not line_text_stripped:
                continue
            is_wm = False
            for wm in watermark_texts:
                if wm in line_text_stripped or line_text_stripped in wm:
                    is_wm = True
                    break
            if is_wm:
                continue
            text_clean = re.sub(r'[\x00-\x08\x0b\x0c\x0e-\x1f]', '', line_text_stripped)
            if text_clean:
                lines_text.append(text_clean)
        text = "\n".join(lines_text).strip()
        if text:
            blocks.append({"type": BLOCK_TEXT, "text": text, "bbox": tuple(bbox), "y_top": bbox[1], "x_left": bbox[0]})
    return blocks


def _expand_bbox_with_text(page, bbox, watermark_texts=None, search_margin=100):
    try:
        text_blocks = _extract_text_blocks_simple(page, watermark_texts)
    except Exception:
        return bbox
    x1, y1, x2, y2 = bbox
    pw, ph = page.rect.width, page.rect.height
    bw = x2 - x1
    bh = y2 - y1
    label_margin = min(search_margin, max(bh * 0.4, 60))
    top_margin = min(30, label_margin)
    left_area = (max(0, x1 - search_margin), y1, x1, y2)
    right_area = (x2, y1, min(pw, x2 + search_margin), y2 + label_margin)
    top_area = (x1, max(0, y1 - top_margin), x2, y1)
    bottom_area = (max(0, x1 - bw * 0.1), y2, min(pw, x2 + bw * 0.5), min(ph, y2 + label_margin))
    new_x1, new_y1, new_x2, new_y2 = x1, y1, x2, y2
    for tb in text_blocks:
        tb_bbox = tb["bbox"]
        if len(tb.get("text", "")) > 20:
            continue
        if (tb_bbox[0] < left_area[2] and tb_bbox[2] > left_area[0] and
                tb_bbox[1] < left_area[3] and tb_bbox[3] > left_area[1]):
            new_x1 = min(new_x1, tb_bbox[0])
        if (tb_bbox[0] < right_area[2] and tb_bbox[2] > right_area[0] and
                tb_bbox[1] < right_area[3] and tb_bbox[3] > right_area[1]):
            new_x2 = max(new_x2, tb_bbox[2])
        if (tb_bbox[0] < top_area[2] and tb_bbox[2] > top_area[0] and
                tb_bbox[1] < top_area[3] and tb_bbox[3] > top_area[1]):
            new_y1 = min(new_y1, tb_bbox[1])
        if (tb_bbox[0] < bottom_area[2] and tb_bbox[2] > bottom_area[0] and
                tb_bbox[1] < bottom_area[3] and tb_bbox[3] > bottom_area[1]):
            new_y2 = max(new_y2, tb_bbox[3])
    pad = 10
    new_x1 = max(0, new_x1 - pad)
    new_y1 = max(0, new_y1 - pad)
    new_x2 = min(pw, new_x2 + pad)
    new_y2 = min(ph, new_y2 + pad)
    return (new_x1, new_y1, new_x2, new_y2)


def _extract_chart_image(page, bbox, page_num, chart_idx, dpi=200, clip_rect=None):
    import fitz
    pw, ph = page.rect.width, page.rect.height
    if clip_rect is not None:
        padded = clip_rect
    else:
        padded = _padded_chart_bbox(bbox, pw, ph)
    clip = fitz.Rect(padded[0], padded[1], padded[2], padded[3])
    pix = page.get_pixmap(dpi=dpi, clip=clip)
    img_bytes = pix.tobytes("png")
    img_b64 = base64.b64encode(img_bytes).decode("ascii")
    fname = f"chart_p{page_num}_{chart_idx}.png"
    return {
        "fileName": fname, "imageBase64": img_b64, "imageMimeType": "image/png",
        "width": pix.width, "height": pix.height,
    }


def _compute_non_overlapping_bboxes(regions, page, pw, ph, watermark_texts=None):
    expanded_padded = []
    for r in regions:
        expanded = _expand_bbox_with_text(page, r["bbox"], watermark_texts)
        pad = 10
        expanded_padded.append((
            max(0, expanded[0] - pad), max(0, expanded[1] - pad),
            min(pw, expanded[2] + pad), min(ph, expanded[3] + pad),
        ))
    clipped = list(expanded_padded)
    for i in range(len(clipped)):
        for j in range(i + 1, len(clipped)):
            b1 = clipped[i]
            b2 = clipped[j]
            if b1[3] > b2[1] and b1[1] < b2[3] and b1[2] > b2[0] and b1[0] < b2[2]:
                o1 = regions[i]["bbox"]
                o2 = regions[j]["bbox"]
                if o1[1] < o2[1]:
                    mid = (o1[3] + o2[1]) / 2
                    clipped[i] = (b1[0], b1[1], b1[2], min(b1[3], mid))
                    clipped[j] = (b2[0], max(b2[1], mid), b2[2], b2[3])
                else:
                    mid = (o2[3] + o1[1]) / 2
                    clipped[j] = (b2[0], b2[1], b2[2], min(b2[3], mid))
                    clipped[i] = (b1[0], max(b1[1], mid), b1[2], b1[3])
    text_filter_bboxes = []
    for r in regions:
        padded = _padded_chart_bbox(r["bbox"], pw, ph)
        text_filter_bboxes.append({"bbox": padded})
    return clipped, text_filter_bboxes


# ================================================================
# _filter_text_in_regions: 移除图表区域内的文字块
# ================================================================
def _filter_text_in_regions(text_blocks, regions, iou_threshold=0.3):
    if not regions:
        return text_blocks
    filtered = []
    for block in text_blocks:
        bb = block.get("bbox", (0, 0, 0, 0))
        covered = False
        for region in regions:
            rb = region["bbox"] if isinstance(region, dict) else region
            if _bbox_iou(bb, rb) > iou_threshold:
                covered = True
                break
        if not covered:
            filtered.append(block)
    return filtered


# ================================================================
# _vl_describe_chart: VL模型结构化描述图表
# ================================================================
def _vl_describe_chart(img_b64, mime_type, api_key, model="kimi-k2.6"):
    """调用VL模型对图表生成结构化描述（JSON），包含标题、类型、数据表、关键指标、趋势。
    返回值: dict（结构化描述）或空字符串（失败时兼容旧格式）"""
    import urllib.request
    import urllib.error
    import time
    url = f"{_DIAGRAM_BASE_URL}/v1/chat/completions"
    system_prompt = (
        "你是一个精确的图表语义分析器。请仔细观察图片中的图表，输出结构化 JSON 描述。\n"
        "要求：\n"
        "1. 只输出图片中**明确可见**的信息，绝不推断或构造不存在的数据\n"
        "2. 如果某个字段无法从图片中获取，留空字符串或空数组\n"
        "3. dataTable 只在图表包含明确数值数据时填写（如表格、柱状图、折线图的数据点），"
        "对于流程图/架构图/组织架构图等不填写\n"
        "4. **如果图片中包含完整的数据表格（多行多列），请尽可能提取所有行的数据，不要省略任何行**\n"
        "5. **对于金融数据表格，数值精度至关重要，请保留原始数字格式（如百分比、小数位）**\n"
        "6. **chartType 为'表格'时，dataTable 必须包含图片中所有可见数据行**\n"
        "7. keyPoints 提取图表中最关键的事实（数字、名称、日期等），最多 6 条\n"
        "8. 只输出 JSON，不要输出其他内容\n\n"
        "输出格式：\n"
        "{\n"
        '  "title": "图表标题（如有）",\n'
        '  "chartType": "柱状图/折线图/饼图/表格/流程图/组织架构图/嵌入图片/其他",\n'
        '  "summary": "一到两句话总结图表核心内容",\n'
        '  "dataTable": {"headers": ["列1", "列2"], "rows": [["值1", "值2"]]},\n'
        '  "keyPoints": ["关键事实1", "关键事实2"],\n'
        '  "trend": "数据趋势或变化方向描述（如适用）"\n'
        "}"
    )
    payload = {
        "model": model,
        "messages": [{"role": "user", "content": [
            {"type": "text", "text": system_prompt},
            {"type": "image_url", "image_url": {"url": f"data:{mime_type};base64,{img_b64}"}}
        ]}],
        "max_tokens": 4096,
        "temperature": 0.1,
    }
    headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}
    max_retries = 3
    for attempt in range(max_retries):
        try:
            req = urllib.request.Request(url, data=json.dumps(payload).encode("utf-8"), headers=headers)
            with urllib.request.urlopen(req, timeout=60) as resp:
                result = json.loads(resp.read().decode("utf-8"))
                text = result["choices"][0]["message"]["content"].strip()
            return _parse_vl_structured(text)
        except Exception as e:
            if hasattr(e, 'code') and e.code in (429, 502, 503) and attempt < max_retries - 1:
                time.sleep(2 * (2 ** attempt))
                continue
            logger.warning(f"[VL] describe failed: {e}")
            return ""
    return ""


def _vl_describe_extracted_images(extracted_images, assets_dir, api_key, model, concurrency=4, max_images=15):
    """为所有提取的图片生成 VL 一句话描述，更新图片字典和 parsed 内容"""
    import base64
    from concurrent.futures import ThreadPoolExecutor, as_completed

    if not extracted_images or not assets_dir or not api_key:
        return 0

    assets_path = Path(assets_dir)
    if not assets_path.exists():
        return 0

    mime_map = {"jpg": "image/jpeg", "jpeg": "image/jpeg", "png": "image/png", "gif": "image/gif", "webp": "image/webp"}

    def _simple_vl_describe(img_b64, mime_type):
        """简化版 VL 描述：只返回一句话文本"""
        import urllib.request
        import urllib.error
        import time as _time
        url = f"{_DIAGRAM_BASE_URL}/v1/chat/completions"
        payload = {
            "model": model,
            "messages": [{"role": "user", "content": [
                {"type": "text", "text": "请用一句话简洁描述这张图片的核心内容，包括图片类型和主要信息。只需一句话，不要输出其他内容。"},
                {"type": "image_url", "image_url": {"url": f"data:{mime_type};base64,{img_b64}"}}
            ]}],
            "max_tokens": 256,
            "temperature": 0.1,
        }
        headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}
        for attempt in range(3):
            try:
                req = urllib.request.Request(url, data=json.dumps(payload).encode("utf-8"), headers=headers)
                with urllib.request.urlopen(req, timeout=60) as resp:
                    result = json.loads(resp.read().decode("utf-8"))
                    return result["choices"][0]["message"]["content"].strip()
            except Exception as e:
                if hasattr(e, 'code') and e.code in (429, 502, 503) and attempt < 2:
                    _time.sleep(2 * (2 ** attempt))
                    continue
                return ""
        return ""

    def describe_one(img):
        rel_path = img.get("path", "")
        img_file = assets_path / rel_path
        if not img_file.exists():
            return None
        ext = rel_path.rsplit(".", 1)[-1].lower() if "." in rel_path else "png"
        mime_type = mime_map.get(ext, "image/png")
        try:
            img_bytes = img_file.read_bytes()
            if len(img_bytes) < 100:
                return None
            img_b64 = base64.b64encode(img_bytes).decode("ascii")
            desc = _simple_vl_describe(img_b64, mime_type)
            return (img, desc) if desc else None
        except Exception as e:
            logger.warning(f"[VL_IMG] describe failed for {rel_path}: {e}")
            return None

    candidates = extracted_images[:max_images]
    updated = 0
    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = {executor.submit(describe_one, img): img for img in candidates}
        for future in as_completed(futures):
            try:
                result = future.result()
                if result:
                    img, desc = result
                    old_desc = img.get("description", "")
                    img["description"] = desc
                    img["_old_description"] = old_desc
                    updated += 1
            except Exception:
                pass

    logger.info(f"[VL_IMG] described {updated}/{len(candidates)} images")
    return updated


def _parse_vl_structured(text):
    """解析VL模型返回的结构化JSON，容错处理 + dataTable 格式校验"""
    if not text:
        return ""
    # 尝试提取JSON块（模型可能包裹在```json ... ```中）
    json_str = text
    if "```" in text:
        import re
        m = re.search(r'```(?:json)?\s*\n?(.*?)\n?```', text, re.DOTALL)
        if m:
            json_str = m.group(1).strip()
    try:
        obj = json.loads(json_str)
        if not isinstance(obj, dict):
            return text
        # 确保必需字段存在
        obj.setdefault("title", "")
        obj.setdefault("chartType", "")
        obj.setdefault("summary", "")
        obj.setdefault("dataTable", {})
        obj.setdefault("keyPoints", [])
        obj.setdefault("trend", "")
        # dataTable 格式校验：headers 数量 vs rows 每行列数一致性
        dt = obj.get("dataTable")
        if isinstance(dt, dict):
            headers = dt.get("headers") or []
            rows = dt.get("rows") or []
            if headers and rows:
                expected_cols = len(headers)
                valid_rows = []
                for row in rows:
                    if isinstance(row, list):
                        if len(row) == expected_cols:
                            valid_rows.append(row)
                        elif len(row) > expected_cols:
                            valid_rows.append(row[:expected_cols])
                        elif len(row) < expected_cols:
                            padded = list(row) + [""] * (expected_cols - len(row))
                            valid_rows.append(padded)
                dt["rows"] = valid_rows
        return obj
    except (json.JSONDecodeError, ValueError):
        return text


def _ocr_page_with_vl(file_path, page_index, api_key, model="qwen-vl-plus"):
    import fitz
    import base64
    import json
    import urllib.request

    doc = fitz.open(file_path)
    page = doc[page_index]
    pix = page.get_pixmap(dpi=150)
    img_bytes = pix.tobytes("png")
    img_b64 = base64.b64encode(img_bytes).decode("ascii")
    doc.close()

    payload = {
        "model": model,
        "messages": [
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": OCR_PROMPT},
                    {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{img_b64}"}}
                ]
            }
        ],
        "max_tokens": 4096,
    }

    url = f"{_OCR_BASE_URL}/v1/chat/completions"
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }

    req = urllib.request.Request(url, data=json.dumps(payload).encode("utf-8"), headers=headers)
    with urllib.request.urlopen(req, timeout=30) as resp:
        result = json.loads(resp.read().decode("utf-8"))
        return result["choices"][0]["message"]["content"]


def _save_low_text_page_images(file_path, result, assets_dir, source_id, dpi=100):
    """多模态主模型模式：保存低文字页为图片，供后续主模型直接查看"""
    low_text_pages = result.get("lowTextPages", 0)
    if low_text_pages <= 0:
        return result, []

    import fitz
    doc = fitz.open(file_path)
    low_indices = []
    for i, page in enumerate(doc):
        text = page.get_text("text")
        if len(text.strip()) < 50:
            low_indices.append(i)
    doc.close()

    assets_path = Path(assets_dir)
    assets_path.mkdir(parents=True, exist_ok=True)

    extracted_images = []
    image_markers = []
    for idx in low_indices:
        try:
            doc2 = fitz.open(file_path)
            page = doc2[idx]
            pix = page.get_pixmap(dpi=dpi)
            img_filename = f"{source_id}_p{idx + 1}.png"
            img_path = assets_path / img_filename
            pix.save(str(img_path))
            doc2.close()

            relative_path = img_filename
            description = "第 {} 页".format(idx + 1)
            extracted_images.append({"path": relative_path, "description": description})
            image_markers.append("\n\n![{}]({})".format(description, relative_path))
        except Exception:
            import traceback
            traceback.print_exc(file=sys.stderr)

    if image_markers:
        result["content"] = result["content"] + "\n".join(image_markers)
        result["extractedImageCount"] = len(extracted_images)

    return result, extracted_images


def _save_pptx_images_for_multimodal(file_path, result, assets_dir, source_id):
    """多模态主模型模式：保存PPTX中的图片，供后续主模型直接查看"""
    from pptx import Presentation
    from pptx.enum.shapes import MSO_SHAPE_TYPE

    prs = Presentation(file_path)
    image_targets = []

    for idx, slide in enumerate(prs.slides):
        text_shape_count = 0
        image_shapes = []
        for shape in slide.shapes:
            if shape.shape_type == MSO_SHAPE_TYPE.PICTURE:
                image_shapes.append(shape)
            elif shape.has_text_frame:
                if any(p.text.strip() for p in shape.text_frame.paragraphs):
                    text_shape_count += 1

        if not image_shapes:
            continue
        if text_shape_count > 2:
            continue
        for shape in image_shapes:
            image_targets.append((idx, shape))

    if not image_targets:
        return result, []

    assets_path = Path(assets_dir)
    assets_path.mkdir(parents=True, exist_ok=True)

    extracted_images = []
    image_markers = []
    for i, (slide_idx, shape) in enumerate(image_targets, 1):
        try:
            img_bytes = shape.image.blob
            ext = shape.image.content_type.split("/")[-1] if shape.image.content_type else "png"
            if ext == "jpeg":
                ext = "jpg"
            img_filename = "{}_{}_img{}.{}".format(source_id, "pptx", i, ext)
            img_path = assets_path / img_filename
            img_path.write_bytes(img_bytes)

            relative_path = img_filename
            description = "幻灯片 {} 图片".format(slide_idx + 1)
            extracted_images.append({"path": relative_path, "description": description})
            image_markers.append("\n\n![{}]({})".format(description, relative_path))
        except Exception:
            import traceback
            traceback.print_exc(file=sys.stderr)

    if image_markers:
        result["content"] = result["content"] + "\n\n## 文档图片\n\n" + "\n".join(image_markers)
        result["extractedImageCount"] = len(extracted_images)

    return result, extracted_images


def _ocr_low_text_pages(file_path, result, api_key, model="qwen-vl-plus", max_pages=20, threshold=0.3, skip_pages=None):
    low_text_pages = result.get("lowTextPages", 0)
    total_pages = result.get("pageCount", 1)
    if low_text_pages <= 0 or low_text_pages / total_pages < threshold:
        return result

    import fitz
    doc = fitz.open(file_path)
    low_indices = []
    skip_set = set(skip_pages or [])
    skipped_ocr = 0
    for i, page in enumerate(doc):
        if i in skip_set:
            skipped_ocr += 1
            continue
        text = page.get_text("text")
        if len(text.strip()) < 50:
            low_indices.append(i)
    doc.close()

    if len(low_indices) > max_pages:
        low_indices = low_indices[:max_pages]

    ocr_parts = []
    failed_pages = 0
    for idx in low_indices:
        try:
            ocr_text = _ocr_page_with_vl(file_path, idx, api_key, model)
            if _is_valid_ocr_text(ocr_text):
                ocr_parts.append(f"\n\n--- 第 {idx + 1} 页（OCR 识别）---\n{ocr_text.strip()}")
        except Exception as e:
            failed_pages += 1
            import traceback
            traceback.print_exc(file=sys.stderr)

    if ocr_parts:
        result["content"] = result["content"] + "\n".join(ocr_parts)
        result["ocrPages"] = len(low_indices)
        result["ocrFailedPages"] = failed_pages
        result["ocrEngine"] = model
    if skipped_ocr > 0:
        result["ocrSkippedPages"] = skipped_ocr

    return result


def _filter_watermarks_from_content(content, watermark_texts):
    """通用水印过滤：从 Markdown 内容中移除已知水印文本行"""
    if not watermark_texts or not content:
        return content
    lines = content.split("\n")
    filtered = []
    for ln in lines:
        ln_stripped = ln.strip()
        is_wm = False
        for wm in watermark_texts:
            if wm in ln_stripped:
                is_wm = True  # 正向匹配：水印文本出现在行内
                break
            # 反向匹配：行是水印文本的子串（仅对足够长的行生效，防止短行误删）
            if ln_stripped and len(ln_stripped) >= 20 and ln_stripped in wm:
                is_wm = True
                break
        if not is_wm:
            filtered.append(ln)
    return "\n".join(filtered)


def _detect_multi_column_from_boxes(page_boxes, page_width=None):
    """利用 pymupdf4llm 的 page_boxes 布局信息判断是否为多栏页面"""
    if not page_boxes or len(page_boxes) < 4:
        return False
    text_boxes = [b for b in page_boxes if b.get("type") in ("text", "paragraph")]
    if len(text_boxes) < 4:
        return False
    if page_width is None:
        max_x1 = max(b.get("bbox", [0, 0, 0, 0])[2] for b in text_boxes if b.get("bbox"))
        page_width = max_x1 if max_x1 > 0 else 1.0
    mid = page_width * 0.5
    left_count = 0
    right_count = 0
    for b in text_boxes:
        bbox = b.get("bbox")
        if not bbox or len(bbox) < 4:
            continue
        x0, x1 = bbox[0], bbox[2]
        center = (x0 + x1) / 2
        width = x1 - x0
        if width < page_width * 0.6 and center < mid:
            left_count += 1
        elif width < page_width * 0.6 and center >= mid:
            right_count += 1
    return left_count >= 2 and right_count >= 2


def _postprocess_pymupdf4llm_content(text):
    """对 pymupdf4llm 原始输出做结构化后处理：
    1. 清除 'picture intentionally omitted' 占位符
    2. 清除低价值 picture-text 块（图表 OCR 轴标签）
    3. 清除 malformed table-fallback 块（大量 <br> 分隔的表格残留）
    4. 去重连续重复的大段内容（如多页重复的产品净值表格）
    5. 清理孤立表格分隔符和 || 残留行
    6. 清理图表 Y/X 轴散落标签
    """
    import re

    # 1) 删除 picture 占位符行
    text = re.sub(
        r'\*\*==>\s*picture\s*\[\d+\s*x\s*\d+\]\s*intentionally omitted\s*<==\*\*\s*\n?',
        '', text
    )

    # 2) 删除 picture-text 块（Start/End of picture text 之间的内容）
    text = re.sub(
        r'\*\*-{3,}\s*Start of picture text\s*-{3,}\*\*.*?\*\*-{3,}\s*End of picture text\s*-{3,}\*\*\s*(?:<br>\s*\n?)?',
        '', text, flags=re.DOTALL
    )

    # 3) 清除 malformed table-fallback 块 + || 残留行
    lines = text.split('\n')
    cleaned = []
    for line in lines:
        stripped = line.strip()
        # 检测 malformed table-fallback 行（含大量 <br> 的非 GFM 行）
        if stripped.startswith('|') and stripped.count('<br>') > 3 and '|---' not in stripped:
            continue
        # 清除以 || 开头的合并单元格残留行（pymupdf4llm layout 模式的 table-fallback）
        if stripped.startswith('||') and not stripped.startswith('|||'):
            continue
        cleaned.append(line)
    text = '\n'.join(cleaned)

    # 4) 去重连续重复的大段内容（阈值 500 字符）
    sections = re.split(r'\n\n---\n\n', text)
    seen_sections = []
    for sec in sections:
        sec_stripped = sec.strip()
        if not sec_stripped:
            continue
        is_dup = False
        if len(sec_stripped) > 500:
            fingerprint = sec_stripped[:200]
            for prev in seen_sections:
                if prev.strip().startswith(fingerprint):
                    is_dup = True
                    break
        if not is_dup:
            seen_sections.append(sec)
    text = '\n\n---\n\n'.join(seen_sections)

    # 5) 清理孤立表格分隔符（|---|---| 前面无表头行 → 孤立表格片段）
    lines = text.split('\n')
    cleaned = []
    for i, line in enumerate(lines):
        stripped = line.strip()
        if re.match(r'^\|[\s\-|]+\|$', stripped) and '|' in stripped and '---' in stripped:
            # 检查前面是否有合法表头行（以 | 开头且非分隔符的数据行）
            prev_is_header = False
            for j in range(i - 1, max(i - 3, -1), -1):
                if j >= 0 and lines[j].strip().startswith('|') and '---' not in lines[j]:
                    prev_is_header = True
                    break
            if not prev_is_header:
                continue  # 孤立分隔符（无表头），丢弃
        cleaned.append(line)
    text = '\n'.join(cleaned)

    # 6) 清理图表 Y/X 轴散落标签
    #    特征：连续多行都是独立的 数字/%/负号 组合（含空格分隔的多个数值），无上下文文字
    _axis_label_re = re.compile(r'^[\s\-−\d,\.%]+$')
    lines = text.split('\n')
    cleaned = []
    consecutive_axis = 0
    for line in lines:
        stripped = line.strip()
        if stripped and _axis_label_re.match(stripped) and len(stripped) < 60:
            consecutive_axis += 1
            if consecutive_axis >= 3:
                continue
        else:
            if consecutive_axis >= 3:
                while cleaned and _axis_label_re.match(cleaned[-1].strip()) and len(cleaned[-1].strip()) < 60:
                    cleaned.pop()
            consecutive_axis = 0
        cleaned.append(line)
    if consecutive_axis >= 3:
        while cleaned and _axis_label_re.match(cleaned[-1].strip()) and len(cleaned[-1].strip()) < 60:
            cleaned.pop()
    text = '\n'.join(cleaned)

    # 6a) 清理 bold 标记的百分比行（图表柱状图数值残留）
    #     特征：去除 ** 标记后全是数字/百分比/负号（如 **10.0%** 7.86% **8.0%** 6.03%）
    lines = text.split('\n')
    cleaned = []
    for line in lines:
        stripped = line.strip()
        if stripped and '**' in stripped and not stripped.startswith('#'):
            unbold = stripped.replace('**', '')
            if _axis_label_re.match(unbold) and len(unbold) > 15 and '%' in unbold:
                continue  # 全行都是百分比数值，图表轴标签
        cleaned.append(line)
    text = '\n'.join(cleaned)

    # 6b) 清理标题前后的散落单行数值（图表轴标签贴在标题附近）
    #     特征：单行只有一个数字/百分比（如 0.33%），且前后 2 行内有标题行
    _single_num_re = re.compile(r'^[\-−]?\d[\d,]*\.?\d*\s*%?$')
    lines = text.split('\n')
    cleaned = []
    for i, line in enumerate(lines):
        stripped = line.strip()
        if _single_num_re.match(stripped) and len(stripped) < 15:
            near_heading = False
            for j in range(max(0, i - 2), min(len(lines), i + 3)):
                if j != i and lines[j].strip().startswith('#'):
                    near_heading = True
                    break
            if near_heading:
                continue  # 标题附近的散落数值，图表轴标签
        cleaned.append(line)
    text = '\n'.join(cleaned)

    # 6c) 清理孤立 bold 数据行（无表头无分隔符的表格数据行）
    #     特征：以 | 开头，含 4+ 个 **bold** 值，前后 3 行内无 |--- 分隔符
    lines = text.split('\n')
    cleaned = []
    for i, line in enumerate(lines):
        stripped = line.strip()
        if (stripped.startswith('|') and stripped.count('**') >= 4
                and '---' not in stripped and not stripped.startswith('||')):
            has_sep = False
            for j in range(max(0, i - 3), min(len(lines), i + 4)):
                if j != i and '---' in lines[j] and '|' in lines[j]:
                    has_sep = True
                    break
            if not has_sep:
                continue  # 孤立的数据行（无表头上下文）
        cleaned.append(line)
    text = '\n'.join(cleaned)

    # 6d) 清理 1 列表格与前文段落重复（pdfplumber 把段落文本误识别为 1 列表格）
    #     特征：| --- | 格式的 1 列分隔符，表头是长文本（>30字符），可能是段落误拆
    lines = text.split('\n')
    cleaned = []
    _skip_until = -1
    for i, line in enumerate(lines):
        if i <= _skip_until:
            continue
        stripped = line.strip()
        if re.match(r'^\|\s*---+\s*\|$', stripped):
            # 前一行是表头（1 列：2 个 | 或 2 列含空列：3 个 |）
            if cleaned and cleaned[-1].strip().startswith('|') and cleaned[-1].strip().count('|') >= 2:
                header = cleaned[-1].strip()
                # 提取第一个非空单元格文本
                cells = [c.strip() for c in header.split('|')[1:-1]]
                header_cell = cells[0] if cells else ''
                if len(header_cell) > 30:
                    # 检查前文是否包含相同内容（段落重复）
                    preceding = '\n'.join(
                        lines[k].strip() for k in range(max(0, i - 20), i - 1)
                    )
                    if header_cell[:40] in preceding:
                        cleaned.pop()  # 删除表头
                        j = i + 1
                        while j < len(lines) and lines[j].strip().startswith('|'):
                            j += 1
                        _skip_until = j - 1
                        continue
                    # 即使前文无重复，1 列长文本表格也是段落误拆（如法律声明）
                    # 检测：数据行交替出现空行（|  |）→ 段落文本被拆为表格
                    data_rows = []
                    j = i + 1
                    while j < len(lines) and lines[j].strip().startswith('|'):
                        data_rows.append(lines[j].strip())
                        j += 1
                    empty_rows = sum(1 for r in data_rows if r in ('|  |', '| |'))
                    if len(data_rows) > 2 and empty_rows >= len(data_rows) * 0.3:
                        cleaned.pop()  # 删除表头
                        _skip_until = i + len(data_rows)
                        continue
        cleaned.append(line)
    text = '\n'.join(cleaned)

    # 6e) 清理连续日期行（图表 X 轴日期标签，如 2021-01-04, 2021-04-04 序列）
    _date_line_re = re.compile(r'^\d{4}[-/]\d{2}[-/]\d{2}$')
    lines = text.split('\n')
    cleaned = []
    consec_dates = 0
    for line in lines:
        stripped = line.strip()
        if _date_line_re.match(stripped):
            consec_dates += 1
            if consec_dates >= 3:
                continue
        else:
            if consec_dates >= 3:
                while cleaned and _date_line_re.match(cleaned[-1].strip()):
                    cleaned.pop()
            consec_dates = 0
        cleaned.append(line)
    if consec_dates >= 3:
        while cleaned and _date_line_re.match(cleaned[-1].strip()):
            cleaned.pop()
    text = '\n'.join(cleaned)

    # 6f) 清理 1 列长文本表格（段落误识别或法律声明重复表格）
    #     特征：| --- | 分隔符，表头 > 30 字符，且有交替空行 → 整块删除
    lines = text.split('\n')
    cleaned = []
    _skip_until = -1
    for i, line in enumerate(lines):
        if i <= _skip_until:
            continue
        stripped = line.strip()
        if re.match(r'^\|\s*---+\s*\|$', stripped):
            if cleaned and cleaned[-1].strip().startswith('|') and cleaned[-1].strip().count('|') >= 2:
                header = cleaned[-1].strip()
                cells = [c.strip() for c in header.split('|')[1:-1]]
                header_cell = cells[0] if cells else ''
                if len(header_cell) > 30:
                    # 检查数据行是否有交替空行模式（段落误识别特征）
                    data_rows = []
                    j = i + 1
                    while j < len(lines) and lines[j].strip().startswith('|'):
                        data_rows.append(lines[j].strip())
                        j += 1
                    empty_rows = sum(1 for r in data_rows if r in ('|  |', '| |', '| |'))
                    if len(data_rows) > 2 and empty_rows >= len(data_rows) * 0.3:
                        cleaned.pop()  # 删除表头
                        _skip_until = i + len(data_rows)
                        continue
                    # 无交替空行但表头很长，也删除（安全起见）
                    if len(header_cell) > 50 and len(data_rows) > 0:
                        cleaned.pop()
                        _skip_until = i + len(data_rows)
                        continue
        cleaned.append(line)
    text = '\n'.join(cleaned)

    # 7) 清理多余空行（>3 个连续空行 → 2 个）
    text = re.sub(r'\n{4,}', '\n\n\n', text)

    return text


def _is_garbage_table(md):
    """检测 pdfplumber 提取的表格是否为垃圾表格（空表格、图表误识别、文本误拆等）"""
    lines = [l.strip() for l in md.strip().split('\n') if l.strip()]
    if len(lines) < 3:
        return True
    data_lines = [l for l in lines if l.startswith('|') and '---' not in l]
    if not data_lines:
        return True
    total_cells = 0
    empty_cells = 0
    for line in data_lines:
        cells = [c.strip() for c in line.split('|')[1:-1]]
        total_cells += len(cells)
        empty_cells += sum(1 for c in cells if not c)
    if total_cells == 0:
        return True
    if empty_cells / total_cells > 0.7:
        return True
    first_cells = [c.strip() for c in data_lines[0].split('|')[1:-1]]
    num_cols = len(first_cells)
    # 只有 1-2 列且每行内容很短 → 可能是文本误识别
    if num_cols <= 2 and all(len(c) < 20 for c in first_cells):
        all_short = all(
            all(len(c.strip()) < 30 for c in l.split('|')[1:-1])
            for l in data_lines
        )
        if all_short and len(data_lines) > 5:
            return True
    # 只有 1 列且有多行长文本 → 段落文本被误识别为表格（如法律声明）
    if num_cols == 1 and len(data_lines) >= 4:
        long_text_rows_1col = sum(
            1 for line in data_lines
            if len([c.strip() for c in line.split('|')[1:-1]][0] if [c.strip() for c in line.split('|')[1:-1]] else '') > 20
        )
        if long_text_rows_1col >= 3:
            return True
    # 检测"段落文本被误拆为表格"：第二列为空或极短（<5字符），第一列是长句文本
    if num_cols <= 2 and len(data_lines) >= 4:
        long_text_rows = 0
        trivial_second_rows = 0
        for line in data_lines:
            cells = [c.strip() for c in line.split('|')[1:-1]]
            if len(cells) >= 2:
                if len(cells[0]) > 20 and len(cells[1]) < 5:
                    long_text_rows += 1
                if len(cells[1]) < 5:
                    trivial_second_rows += 1
            elif len(cells) == 1 and len(cells[0]) > 30:
                long_text_rows += 1
        if long_text_rows >= 3 and trivial_second_rows / max(len(data_lines), 1) > 0.3:
            return True
    # 检测"超大单元格"：单个单元格内容超长（多行数据被压缩进一个 cell）
    for line in data_lines:
        cells = [c.strip() for c in line.split('|')[1:-1]]
        for cell in cells:
            if len(cell) > 150:
                return True
    # 列数 <= 2 且超过 50% 的行包含中文标点 + 长文本 → 段落文本被误识别为表格
    if num_cols <= 2 and len(data_lines) >= 4:
        prose_rows = sum(
            1 for line in data_lines
            if len(line) > 40 and any(p in line for p in ('。', '；', '：', '，'))
        )
        if prose_rows / len(data_lines) > 0.4:
            return True
    # 检测"表头列数 vs 数据行列数"严重不匹配
    if num_cols >= 3 and len(data_lines) > 1:
        mismatch_rows = 0
        for line in data_lines[1:]:
            row_cols = len([c for c in line.split('|')[1:-1]])
            if row_cols != num_cols:
                mismatch_rows += 1
        if mismatch_rows / max(len(data_lines) - 1, 1) > 0.5:
            return True
    return False


def _supplement_tables_from_pdfplumber(content, file_path):
    """用 pdfplumber 提取表格，补充到 pymupdf4llm 输出中表格缺失的页面。
    返回 (增强后 content, 补充的表格页面列表)"""
    tables_map = _extract_tables_pdfplumber(file_path)
    if not tables_map:
        return content, []

    sections = re.split(r'\n\n---\n\n', content) if content else []
    supplemented_pages = []

    for page_idx, tables_list in tables_map.items():
        # 过滤垃圾表格
        valid_tables = [(y, md) for y, md in tables_list if not _is_garbage_table(md)]
        if not valid_tables:
            continue

        if page_idx >= len(sections):
            for y_top, md in valid_tables:
                sections.append(md)
            supplemented_pages.append(page_idx + 1)
            continue

        existing = sections[page_idx]
        # 检查该页面是否已有合法 GFM 表格（有 |--- 分隔符）
        gfm_count = existing.count('|---') + existing.count('| ---')
        if gfm_count >= len(valid_tables):
            continue

        # 追加 pdfplumber 提取的表格
        for y_top, md in valid_tables:
            if md.strip() and md.strip() not in existing:
                sections[page_idx] = existing + '\n\n' + md
                existing = sections[page_idx]
        supplemented_pages.append(page_idx + 1)

    content = '\n\n---\n\n'.join(sections)
    return content, supplemented_pages


_FITZ_WATERMARKS = [
    '本材料仅供持仓客户及公司内部员工使用',
    '严禁外传',
    '材料中涉及的产品历史业绩仅为存续产品回顾',
    '不作为宣传推介使用',
    '产品排期以发行公告为准',
    '基金过往业绩不代表未来表现',
    '基金过绩不代表未来表现',
    '市场有风险投资需谨慎',
    '市场有风险，投资需谨慎',
]

_AXIS_LABEL_RE_FITZ = re.compile(r'^[\s\-−\d,\.%]+$')


def _dedup_fitz_supplement_text(fitz_text, existing_section):
    """清理并去重 fitz 补充文本：
    1. 过滤水印/免责文本
    2. 过滤连续轴标签行（图表 Y/X 轴数值）
    3. 去除与 pymupdf4llm 已有内容重复的段落（基于归一化子串匹配）
    """
    # 1. 过滤水印行
    lines = fitz_text.split('\n')
    filtered = []
    for line in lines:
        stripped = line.strip()
        if not stripped:
            filtered.append(line)
            continue
        is_watermark = False
        for wm in _FITZ_WATERMARKS:
            if wm in stripped:
                is_watermark = True
                break
        if not is_watermark:
            filtered.append(line)
    fitz_text = '\n'.join(filtered)

    # 2. 过滤连续轴标签行（>=3 连续行都是纯数字/百分比 → 图表坐标轴）
    lines = fitz_text.split('\n')
    filtered = []
    consec = 0
    for line in lines:
        stripped = line.strip()
        if stripped and _AXIS_LABEL_RE_FITZ.match(stripped) and len(stripped) < 30:
            consec += 1
            if consec >= 3:
                continue
        else:
            if consec >= 3:
                while filtered and _AXIS_LABEL_RE_FITZ.match(filtered[-1].strip()) and len(filtered[-1].strip()) < 30:
                    filtered.pop()
            consec = 0
        filtered.append(line)
    if consec >= 3:
        while filtered and _AXIS_LABEL_RE_FITZ.match(filtered[-1].strip()) and len(filtered[-1].strip()) < 30:
            filtered.pop()
    fitz_text = '\n'.join(filtered)

    # 3. 段落级去重：将 fitz 文本按空行分段，与 existing 归一化文本做子串匹配
    #    使用激进归一化（仅保留 CJK + 字母数字），消除 fitz vs pymupdf4llm 的 Unicode 差异
    _aggressive_norm = lambda s: re.sub(r'[^\u4e00-\u9fff\u3000-\u303fa-zA-Z0-9]', '', s)

    fitz_paras = re.split(r'\n(?=\S)', fitz_text)

    existing_norm = _aggressive_norm(existing_section)

    kept = []
    for para in fitz_paras:
        para_norm = _aggressive_norm(para)
        if not para_norm or len(para_norm) < 8:
            continue

        # 用前 40 字符（归一化后）作为指纹，检查是否已存在于 pymupdf4llm 内容中
        key = para_norm[:min(40, len(para_norm))]
        if key in existing_norm:
            continue

        # 也检查短行（可能是轴标签或散落的类别名称）
        if len(para_norm) < 20 and _AXIS_LABEL_RE_FITZ.match(para.strip()):
            continue

        kept.append(para.strip())

    return '\n'.join(kept)


def _supplement_tables_from_fitz_structured(content, file_path):
    """用 fitz 提取原始文本块，补充 pymupdf4llm 文本/表格稀疏的页面。
    针对图表密集页面（pymupdf4llm 将表格数据渲染为 picture 而丢失），
    用 fitz 的原始文本提取补全数据。
    返回 (增强后 content, 补充的页面列表)"""
    import fitz

    if not content:
        return content, []

    raw_sections = content.split('\n\n---\n\n')

    # 计算页面偏移：raw_sections[i] 对应 doc[i - offset]
    # 当前导 section 为空时（如 page 0 只有水印被过滤），offset > 0
    page_offset = 0
    for sec in raw_sections:
        if not sec.strip():
            page_offset += 1
        else:
            break

    supplemented_pages = []

    try:
        doc = fitz.open(file_path)
    except Exception:
        return content, []

    for sec_idx, section in enumerate(raw_sections):
        page_idx = sec_idx - page_offset
        if page_idx < 0 or page_idx >= len(doc):
            continue

        pymupdf_len = len(section.strip())

        try:
            page = doc[page_idx]
            fitz_text = page.get_text("text").strip()
        except Exception:
            continue

        fitz_len = len(fitz_text)

        # fitz 提取的文本显著多于 pymupdf4llm（说明有大量内容被标记为 picture）
        if fitz_len > pymupdf_len * 2 and fitz_len - pymupdf_len > 200:
            fitz_text = re.sub(r'\n{3,}', '\n\n', fitz_text)
            # 用 fitz 自身文本做去重基准（避免 section 偏移问题）
            # 同时合并相邻 section 的上下文，防止 section 索引错位
            dedup_context = section
            if sec_idx > 0:
                dedup_context = raw_sections[sec_idx - 1] + '\n' + section
            if sec_idx < len(raw_sections) - 1:
                dedup_context = dedup_context + '\n' + raw_sections[sec_idx + 1]
            fitz_text = _dedup_fitz_supplement_text(fitz_text, dedup_context)
            if len(fitz_text.strip()) > 50:
                raw_sections[sec_idx] += '\n\n<!-- fitz-text-supplemented -->\n\n' + fitz_text
                supplemented_pages.append(page_idx + 1)

    doc.close()

    content = '\n\n---\n\n'.join(raw_sections)
    return content, supplemented_pages


def _extract_text_pymupdf4llm(file_path, assets_dir=None, source_id=None):
    """pymupdf4llm 引擎：布局感知 Markdown 提取 + 内置表格/标题/图片检测
    失败时返回 None，触发 parse_pdf 的降级链路"""
    try:
        import pymupdf4llm
    except ImportError:
        logger.info("[PYMUPDF4LLM] not installed, skipping")
        return None

    try:
        write_images = bool(assets_dir and source_id)
        image_path = str(assets_dir) if write_images else None

        chunks = pymupdf4llm.to_markdown(
            file_path,
            page_chunks=True,
            write_images=write_images,
            image_path=image_path,
            image_format="png",
            dpi=150,
            use_ocr=False,
            show_progress=False,
        )

        if not chunks:
            return None

        import fitz
        wm_doc = None
        watermark_texts = set()
        try:
            wm_doc = fitz.open(file_path)
            watermark_texts = _detect_watermarks(wm_doc)
            if watermark_texts:
                logger.info(f"[PYMUPDF4LLM][WATERMARK] filtering {len(watermark_texts)} patterns")
        except Exception as e:
            logger.debug(f"[PYMUPDF4LLM] watermark detection skipped: {e}")
        finally:
            if wm_doc is not None:
                try:
                    wm_doc.close()
                except Exception:
                    pass

        pages_content = []
        low_text_pages = 0
        multi_column_pages = []
        table_pages = []
        image_pages = []
        extracted_images = []

        for chunk in chunks:
            text = chunk.get("text", "")
            meta = chunk.get("metadata", {})
            page_num = meta.get("page", 0)

            if watermark_texts and text:
                text = _filter_watermarks_from_content(text, watermark_texts)

            if len(text.strip()) < 50:
                low_text_pages += 1

            tables = chunk.get("tables", [])
            if tables:
                table_pages.append(page_num)

            images = chunk.get("images", [])
            if images:
                image_pages.append(page_num)
                for img in images:
                    img_path_val = img.get("path", "")
                    if img_path_val:
                        extracted_images.append({
                            "page_index": page_num - 1,
                            "relative_path": Path(img_path_val).name,
                            "description": f"第{page_num}页内嵌图片",
                        })

            page_boxes = meta.get("page_boxes", [])
            if _detect_multi_column_from_boxes(page_boxes):
                multi_column_pages.append(page_num)

            pages_content.append(text)

        content = "\n\n---\n\n".join(pages_content)

        # 结构化后处理：清理噪音 + 去重
        content = _postprocess_pymupdf4llm_content(content)

        # 用 pdfplumber 补充缺失的 GFM 表格
        content, supplemented = _supplement_tables_from_pdfplumber(content, file_path)
        if supplemented:
            logger.info(f"[PYMUPDF4LLM] supplemented tables on pages: {supplemented}")
            table_pages = sorted(set(table_pages + supplemented))

        # 用 fitz 补充文本稀疏页面（图表密集区域被 pymupdf4llm 标记为 picture）
        content, fitz_supplemented = _supplement_tables_from_fitz_structured(content, file_path)
        if fitz_supplemented:
            logger.info(f"[PYMUPDF4LLM] fitz text supplemented on pages: {fitz_supplemented}")

        if len(content.strip()) < 10:
            return None

        page_count = len(chunks)
        result = {
            "content": content,
            "pageCount": page_count,
            "lowTextPages": low_text_pages,
            "hasScanWarning": low_text_pages > page_count * 0.3,
            "extractionEngine": "pymupdf4llm",
            "layoutInfo": {
                "multiColumnPages": multi_column_pages,
                "tablePages": table_pages,
                "imagePages": sorted(set(image_pages)),
            },
        }

        if extracted_images:
            result["extractedImages"] = [
                {"path": img["relative_path"], "description": img["description"]}
                for img in extracted_images
            ]
            result["extractedImageCount"] = len(extracted_images)

        return result

    except Exception as e:
        logger.warning(f"[PYMUPDF4LLM] extraction failed: {type(e).__name__}: {e}, falling back")
        return None


def _extract_text_fitz_structured(file_path, assets_dir=None, source_id=None):
    """结构化 PDF 提取：布局分析 + 块级拼装 + 图片提取 + 表格提取
    失败时返回 None，触发 parse_pdf 的降级链路"""
    import fitz

    # 预处理：pdfplumber 表格提取（内部已有异常处理）
    tables_map = _extract_tables_pdfplumber(file_path)

    # 预处理：内嵌图片提取（失败不阻断文本提取）
    extracted_images = []
    if assets_dir and source_id:
        try:
            extracted_images = _extract_embedded_images_pdf(file_path, assets_dir, source_id)
        except Exception as e:
            print(f"[FITZ_STRUCT_WARN] image extraction failed: {type(e).__name__}: {e}, continuing without images", file=sys.stderr)
            extracted_images = []
    img_lookup = {img["xref"]: img for img in extracted_images}

    try:
        doc = fitz.open(file_path)
    except AssertionError:
        return None
    except Exception:
        return None

    try:
        watermark_texts = _detect_watermarks(doc)
        if watermark_texts:
            logger.info(f"[WATERMARK] filtering {len(watermark_texts)} patterns from {len(doc)} pages")

        body_font_size = _compute_body_font_size(doc)
        logger.info(f"[FONT_STATS] body_font_size={body_font_size}pt, pages={len(doc)}")

        pages_content = []
        low_text_pages = 0
        page_count = len(doc)
        multi_column_pages = []
        table_pages = list(tables_map.keys())
        image_page_indices = set()

        for i, page in enumerate(doc):
            # 单页处理：失败时降级为简单文本提取
            try:
                sorted_blocks, is_multi_column = _detect_columns_and_sort_blocks(page)
                if is_multi_column:
                    multi_column_pages.append(i + 1)

                page_text_parts = []
                page_tables = tables_map.get(i, [])
                inserted_table_ys = set()

                prev_y = -1
                for block in sorted_blocks:
                    block_y = block.get("y_top", 0)

                    for t_y, t_md in page_tables:
                        if t_y not in inserted_table_ys and prev_y <= t_y <= block_y:
                            page_text_parts.append(f"\n\n{t_md}\n\n")
                            inserted_table_ys.add(t_y)

                    if block.get("type") == 0:
                        md_text = _block_to_markdown(block, body_font_size=body_font_size)
                        if md_text.strip():
                            if watermark_texts:
                                md_text = _filter_watermarks_from_content(md_text, watermark_texts)
                            if md_text.strip():
                                page_text_parts.append(md_text)
                    elif block.get("type") == 1:
                        xref = block.get("image", 0)
                        img_info = img_lookup.get(xref)
                        if img_info:
                            image_page_indices.add(i + 1)
                            marker = f"![{img_info['description']}]({img_info['relative_path']})"
                            page_text_parts.append(marker)

                    prev_y = block_y

                for t_y, t_md in page_tables:
                    if t_y not in inserted_table_ys:
                        page_text_parts.append(f"\n\n{t_md}\n\n")

                page_content = "\n\n".join(page_text_parts)
            except Exception as e:
                print(f"[FITZ_STRUCT_WARN] page {i + 1} structured extraction failed: {type(e).__name__}: {e}, using simple text", file=sys.stderr)
                page_content = page.get_text("text")

            raw_text = page.get_text("text")
            if len(raw_text.strip()) < 50:
                low_text_pages += 1

            if len(page_content.strip()) < 10 and len(raw_text.strip()) >= 10:
                page_content = raw_text

            pages_content.append(page_content)

        doc.close()

        content = "\n\n---\n\n".join(pages_content)
        if len(content.strip()) < 10:
            return None

        result = {
            "content": content,
            "pageCount": page_count,
            "lowTextPages": low_text_pages,
            "hasScanWarning": low_text_pages > page_count * 0.3,
            "extractionEngine": "fitz-structured",
            "layoutInfo": {
                "multiColumnPages": multi_column_pages,
                "tablePages": [p + 1 for p in table_pages],
                "imagePages": sorted(image_page_indices),
            },
        }

        if extracted_images:
            result["extractedImages"] = [{"path": img["relative_path"], "description": img["description"]} for img in extracted_images]
            result["extractedImageCount"] = len(extracted_images)

        return result

    except Exception as e:
        print(f"[FITZ_STRUCT_WARN] structured extraction failed entirely: {type(e).__name__}: {e}, returning None for fallback", file=sys.stderr)
        try:
            doc.close()
        except Exception:
            pass
        return None


def _fallback_scan_pdf(file_path, assets_dir=None, source_id=None):
    """纯扫描件兜底：PDF 无文字层时，将每页渲染为 PNG 保存到 assets，返回最小结果。
    后续 OCR 或多模态主模型链路可基于 lowTextPages + hasScanWarning 继续处理。"""
    import fitz
    try:
        doc = fitz.open(file_path)
    except Exception:
        return None

    page_count = len(doc)
    if page_count == 0:
        doc.close()
        return None

    image_markers = []
    extracted_images = []
    if assets_dir and source_id:
        assets_path = Path(assets_dir)
        assets_path.mkdir(parents=True, exist_ok=True)
        for i in range(page_count):
            try:
                page = doc[i]
                pix = page.get_pixmap(dpi=150)
                img_filename = f"{source_id}_p{i + 1}.png"
                img_path = assets_path / img_filename
                pix.save(str(img_path))
                relative_path = img_filename
                description = "第 {} 页".format(i + 1)
                extracted_images.append({"path": relative_path, "description": description})
                image_markers.append("\n\n![{}]({})".format(description, relative_path))
            except Exception:
                pass

    doc.close()

    content = "# 扫描文档\n\n该 PDF 文件为纯扫描图像，无可提取的文字层。\n"
    if image_markers:
        content += "\n## 页面图像\n" + "".join(image_markers)

    result = {
        "content": content,
        "pageCount": page_count,
        "lowTextPages": page_count,
        "hasScanWarning": True,
        "scanOnlyFallback": True,
    }
    if extracted_images:
        result["extractedImages"] = [{"path": img["path"], "description": img["description"]} for img in extracted_images]
        result["extractedImageCount"] = len(extracted_images)
    return result


def parse_pdf(file_path, assets_dir=None, source_id=None):
    pypdf_reader = _check_pdf_encryption(file_path)

    result = _extract_text_pymupdf4llm(file_path, assets_dir, source_id)

    if result is None:
        result = _extract_text_fitz_structured(file_path, assets_dir, source_id)

    if result is None:
        result = _extract_text_fitz(file_path)

    if result is None:
        result = _extract_text_pypdf(file_path)

    if result is None:
        result = _fallback_scan_pdf(file_path, assets_dir, source_id)

    if result is None:
        raise RuntimeError("PDF 文件无法解析（文件可能已损坏、加密或不包含可提取的文字层）")

    result["metadata"] = _extract_metadata(pypdf_reader)

    return result


def _extract_docx_metadata(doc):
    try:
        props = doc.core_properties
        result = {}
        for key in ("title", "author", "subject", "last_modified_by", "category"):
            val = getattr(props, key, None)
            if val:
                result[key] = str(val)
        return result
    except Exception:
        return {}


def _extract_text_pandoc_docx(file_path):
    import subprocess
    try:
        result = subprocess.run(
            ["pandoc", file_path, "-t", "markdown", "--wrap=none"],
            capture_output=True, text=True, encoding="utf-8", timeout=60
        )
        if result.returncode != 0:
            return None
        content = result.stdout.strip()
        if len(content) < 10:
            return None
        return {
            "content": content,
            "pageCount": 1,
            "lowTextPages": 0,
            "hasScanWarning": False,
            "extractionEngine": "pandoc",
        }
    except FileNotFoundError:
        return None
    except Exception:
        return None


def _extract_text_python_docx(file_path):
    try:
        from docx import Document
        doc = Document(file_path)
        blocks = []

        for para in doc.paragraphs:
            style = getattr(para, "style", None)
            style_name = getattr(style, "name", None) or ""
            text = para.text or ""

            if not text.strip():
                blocks.append("")
                continue

            if style_name.startswith("Heading"):
                parts = style_name.split()
                try:
                    level = min(int(parts[-1]), 6)
                except (ValueError, IndexError):
                    level = 2
                blocks.append(f"{'#' * level} {text}")
                continue

            if style_name.startswith("List"):
                blocks.append(f"- {text}")
                continue

            blocks.append(text)

        for i, table in enumerate(doc.tables):
            rows = []
            for row in table.rows:
                cells = [cell.text.replace("\n", " ") for cell in row.cells]
                rows.append("| " + " | ".join(cells) + " |")
            if rows:
                header = rows[0]
                sep = "| " + " | ".join(["---"] * len(table.rows[0].cells)) + " |"
                rows.insert(1, sep)
                blocks.append(f"\n### 表格 {i + 1}\n")
                blocks.append("\n".join(rows))

        content = "\n\n".join(blocks)
        if len(content.strip()) < 10:
            return None

        return {
            "content": content,
            "pageCount": 1,
            "lowTextPages": 0,
            "hasScanWarning": False,
            "extractionEngine": "python-docx",
        }
    except ImportError:
        return None
    except Exception:
        return None


# ── DOCX 结构化提取层 ──────────────────────────────────────────────

_DOCX_NS = {
    "w": "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
    "r": "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
    "a": "http://schemas.openxmlformats.org/drawingml/2006/main",
}


def _run_to_markdown(run):
    """将单个 run 转为 Markdown（加粗/斜体/代码）"""
    text = run.text or ""
    if not text:
        return ""
    bold = run.bold
    italic = run.italic
    if bold and italic:
        return f"***{text}***"
    if bold:
        return f"**{text}**"
    if italic:
        return f"*{text}*"
    return text


def _paragraph_to_markdown(para, image_lookup=None):
    """将段落转为 Markdown，支持行内图片检测和列表/标题样式"""
    style_name = (getattr(para.style, "name", None) or "") if para.style else ""
    text_parts = []
    image_markers = []

    for run in para.runs:
        drawing_els = run._element.findall(".//w:drawing", _DOCX_NS)
        if drawing_els and image_lookup:
            for drawing in drawing_els:
                blip = drawing.find(".//a:blip", _DOCX_NS)
                if blip is not None:
                    rId = blip.get("{%s}embed" % _DOCX_NS["r"])
                    if rId and rId in image_lookup:
                        info = image_lookup[rId]
                        image_markers.append(
                            "![{}]({})".format(info.get("description", "文档图片"), info["relative_path"])
                        )
        text_parts.append(_run_to_markdown(run))

    text = "".join(text_parts)
    has_text = bool(text.strip())

    if style_name.startswith("Heading"):
        parts = style_name.split()
        try:
            level = min(int(parts[-1]), 6)
        except (ValueError, IndexError):
            level = 2
        heading = "{} {}".format("#" * level, text.strip()) if has_text else ""
        return heading, image_markers

    if style_name.startswith("List") or style_name.startswith("list"):
        numPr = para._element.find(".//w:numPr", _DOCX_NS)
        ilvl_el = para._element.find(".//w:ilvl", _DOCX_NS)
        level = 0
        if ilvl_el is not None:
            level = int(ilvl_el.get("{%s}val" % _DOCX_NS["w"], "0"))
        indent = "  " * level
        prefix = "- " if (numPr is not None) else "- "
        list_text = "{}{}{}".format(indent, prefix, text.strip()) if has_text else ""
        return list_text, image_markers

    if style_name == "Quote" or style_name == "Intense Quote":
        quote_text = "> {}".format(text.strip()) if has_text else ""
        return quote_text, image_markers

    return text, image_markers


def _extract_table_markdown(table):
    """将 python-docx 表格转为 Markdown，处理合并单元格"""
    try:
        rows_data = []
        col_count = 0
        for row in table.rows:
            cells = []
            seen_tcs = []
            for cell in row.cells:
                tc = cell._tc
                if tc in seen_tcs:
                    cells.append("")
                else:
                    seen_tcs.append(tc)
                    cell_text = cell.text.replace("\n", " ").strip()
                    cells.append(cell_text)
            if cells:
                rows_data.append(cells)
                col_count = max(col_count, len(cells))

        if not rows_data or col_count == 0:
            return ""

        for row in rows_data:
            while len(row) < col_count:
                row.append("")

        header = "| " + " | ".join(rows_data[0]) + " |"
        sep = "| " + " | ".join(["---"] * col_count) + " |"
        body_lines = ["| " + " | ".join(r) + " |" for r in rows_data[1:]]

        return header + "\n" + sep + "\n" + "\n".join(body_lines)

    except Exception as e:
        print(f"[DOCX_STRUCT_WARN] table extraction failed: {type(e).__name__}: {e}", file=sys.stderr)
        return ""


def _extract_docx_images(file_path, assets_dir, source_id, min_bytes=5120, max_images=50):
    """提取 DOCX 中所有内嵌图片，保存到 assets 目录，返回 rId→info 映射"""
    try:
        from docx import Document
    except ImportError:
        return {}, []

    try:
        doc = Document(file_path)
    except Exception:
        return {}, []

    assets_path = Path(assets_dir) if assets_dir else None
    if assets_path:
        assets_path.mkdir(parents=True, exist_ok=True)

    rid_lookup = {}
    extracted = []
    img_counter = 0

    for rel in doc.part.rels.values():
        if "image" not in rel.reltype:
            continue
        try:
            img_bytes = rel.target_part.blob
            if len(img_bytes) < min_bytes:
                continue
            img_counter += 1
            if img_counter > max_images:
                break

            partname = rel.target_part.partname
            ext = partname.rsplit(".", 1)[-1].lower() if "." in partname else "png"
            if ext == "jpeg":
                ext = "jpg"
            mime_map = {"jpg": "image/jpeg", "jpeg": "image/jpeg", "png": "image/png", "gif": "image/gif", "webp": "image/webp"}
            mime_type = mime_map.get(ext, "image/png")

            relative_path = None
            if assets_path:
                img_filename = "{}_docx_img{}.{}".format(source_id, img_counter, ext)
                img_path = assets_path / img_filename
                img_path.write_bytes(img_bytes)
                relative_path = img_filename

            rid_lookup[rel.rId] = {
                "relative_path": relative_path or "",
                "description": "文档图片 #{}".format(img_counter),
                "bytes": len(img_bytes),
                "mime_type": mime_type,
                "blob": img_bytes,
                "index": img_counter,
            }
            extracted.append({
                "rId": rel.rId,
                "relative_path": relative_path or "",
                "description": "文档图片 #{}".format(img_counter),
                "bytes": len(img_bytes),
                "mime_type": mime_type,
                "blob": img_bytes,
                "index": img_counter,
            })
        except Exception:
            import traceback
            traceback.print_exc(file=sys.stderr)

    return rid_lookup, extracted


def _extract_text_docx_structured(file_path, assets_dir=None, source_id=None):
    """结构化 DOCX 提取：按文档对象模型顺序遍历 body 元素，
    段落/表格/图片按原文顺序拼装，支持行内图片标记、合并单元格表格、
    标题层级、列表嵌套、引用块。失败时返回 None 触发降级。"""
    try:
        from docx import Document
        from docx.oxml.ns import qn
    except ImportError:
        return None

    try:
        doc = Document(file_path)
    except Exception:
        return None

    metadata = _extract_docx_metadata(doc)

    image_lookup = {}
    extracted_images = []
    if assets_dir and source_id:
        try:
            image_lookup, extracted_images = _extract_docx_images(file_path, assets_dir, source_id)
        except Exception as e:
            print(f"[DOCX_STRUCT_WARN] image extraction failed: {type(e).__name__}: {e}", file=sys.stderr)
            image_lookup, extracted_images = {}, []

    try:
        body = doc.element.body
        blocks = []
        image_page_indices = set()
        table_count = 0
        paragraph_count = 0

        para_map = {id(p._element): p for p in doc.paragraphs}
        table_map = {id(t._element): t for t in doc.tables}

        for child in body:
            tag = child.tag

            if tag == qn("w:p"):
                para = para_map.get(id(child))
                if para is None:
                    continue
                paragraph_count += 1
                md_text, img_markers = _paragraph_to_markdown(para, image_lookup)
                if md_text and md_text.strip():
                    blocks.append(md_text)
                elif not md_text.strip():
                    if blocks and blocks[-1] != "":
                        blocks.append("")
                for marker in img_markers:
                    blocks.append(marker)
                    image_page_indices.add(paragraph_count)

            elif tag == qn("w:tbl"):
                table = table_map.get(id(child))
                if table is None:
                    continue
                table_count += 1
                table_md = _extract_table_markdown(table)
                if table_md:
                    blocks.append("")
                    blocks.append(table_md)
                    blocks.append("")

        content = "\n\n".join(b for b in blocks if b is not None)

        if len(content.strip()) < 10:
            return None

        result = {
            "content": content,
            "pageCount": 1,
            "lowTextPages": 0,
            "hasScanWarning": False,
            "extractionEngine": "python-docx-structured",
            "layoutInfo": {
                "tableCount": table_count,
                "paragraphCount": paragraph_count,
                "imageCount": len(extracted_images),
            },
        }

        if extracted_images:
            result["extractedImages"] = [
                {"path": img["relative_path"], "description": img["description"]}
                for img in extracted_images
            ]
            result["extractedImageCount"] = len(extracted_images)
            result["_preExtractedImages"] = extracted_images

        if metadata:
            result["metadata"] = metadata

        return result

    except Exception as e:
        print(f"[DOCX_STRUCT_WARN] structured extraction failed: {type(e).__name__}: {e}", file=sys.stderr)
        import traceback
        traceback.print_exc(file=sys.stderr)
        return None


def _save_docx_images_for_multimodal(file_path, result, assets_dir, source_id):
    """多模态主模型模式：保存DOCX中的图片，供后续主模型直接查看。
    若结构化提取阶段已保存图片，则直接复用，避免重复打开文件。"""
    already_extracted = result.get("extractedImages")
    if already_extracted:
        return result, already_extracted

    try:
        from docx import Document
    except ImportError:
        return result, []

    try:
        doc = Document(file_path)
    except Exception:
        return result, []

    assets_path = Path(assets_dir)
    assets_path.mkdir(parents=True, exist_ok=True)

    extracted_images = []
    image_markers = []
    img_counter = 0

    for rel in doc.part.rels.values():
        if "image" not in rel.reltype:
            continue
        try:
            img_bytes = rel.target_part.blob
            if len(img_bytes) < _MIN_DIAGRAM_IMAGE_BYTES:
                continue
            img_counter += 1
            partname = rel.target_part.partname
            ext = partname.rsplit(".", 1)[-1].lower() if "." in partname else "png"
            if ext == "jpeg":
                ext = "jpg"
            img_filename = "{}_docx_mm_img{}.{}".format(source_id, img_counter, ext)
            img_path = assets_path / img_filename
            img_path.write_bytes(img_bytes)

            relative_path = img_filename
            description = "文档图片 #{}".format(img_counter)
            extracted_images.append({"path": relative_path, "description": description})
            image_markers.append("\n\n![{}]({})".format(description, relative_path))
        except Exception:
            import traceback
            traceback.print_exc(file=sys.stderr)

    if image_markers:
        result["content"] = result["content"] + "\n\n## 文档图片\n\n" + "\n".join(image_markers)
        result["extractedImageCount"] = len(extracted_images)

    return result, extracted_images


def _ocr_docx_images(file_path, result, api_key, model="qwen-vl-plus", max_images=20, pre_extracted_images=None):
    """对 DOCX 中嵌入的图片进行 OCR 识别（用于图片包含关键文字内容的场景）。
    支持传入 pre_extracted_images 复用已提取的图片数据，避免重复打开文件。
    OCR 调用使用多线程并行，减少总体耗时。"""
    import base64
    import json
    import urllib.request
    from concurrent.futures import ThreadPoolExecutor, as_completed

    ocr_targets = []

    if pre_extracted_images:
        for img in pre_extracted_images[:max_images]:
            if "blob" in img and "mime_type" in img:
                ocr_targets.append((img.get("index", 0), img["blob"], img["mime_type"]))
    else:
        try:
            from docx import Document
        except ImportError:
            return result

        try:
            doc = Document(file_path)
        except Exception:
            return result

        img_counter = 0
        for rel in doc.part.rels.values():
            if "image" not in rel.reltype:
                continue
            try:
                img_bytes = rel.target_part.blob
                if len(img_bytes) < _MIN_DIAGRAM_IMAGE_BYTES:
                    continue
                img_counter += 1
                if img_counter > max_images:
                    break
                partname = rel.target_part.partname
                ext = partname.rsplit(".", 1)[-1].lower() if "." in partname else "png"
                mime_map = {"jpg": "image/jpeg", "jpeg": "image/jpeg", "png": "image/png", "gif": "image/gif", "webp": "image/webp"}
                mime_type = mime_map.get(ext, "image/png")
                ocr_targets.append((img_counter, img_bytes, mime_type))
            except Exception:
                continue

    if not ocr_targets:
        return result

    def _ocr_single(item):
        idx, img_bytes, mime_type = item
        try:
            img_b64 = base64.b64encode(img_bytes).decode("ascii")
            payload = {
                "model": model,
                "messages": [{
                    "role": "user",
                    "content": [
                        {"type": "text", "text": OCR_PROMPT},
                        {"type": "image_url", "image_url": {"url": f"data:{mime_type};base64,{img_b64}"}}
                    ]
                }],
                "max_tokens": 4096,
            }
            url = f"{_OCR_BASE_URL}/v1/chat/completions"
            headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}
            req = urllib.request.Request(url, data=json.dumps(payload).encode("utf-8"), headers=headers)
            with urllib.request.urlopen(req, timeout=30) as resp:
                ocr_text = json.loads(resp.read().decode("utf-8"))["choices"][0]["message"]["content"]
            return (idx, ocr_text, None)
        except Exception as e:
            return (idx, None, e)

    ocr_parts = []
    failed = 0
    workers = min(4, len(ocr_targets))
    with ThreadPoolExecutor(max_workers=workers) as executor:
        futures = {executor.submit(_ocr_single, target): target for target in ocr_targets}
        for future in as_completed(futures):
            idx, ocr_text, error = future.result()
            if error is not None:
                failed += 1
                print(f"[DOCX_OCR_WARN] image #{idx} OCR failed: {type(error).__name__}: {error}", file=sys.stderr)
                continue
            if ocr_text and _is_valid_ocr_text(ocr_text):
                quoted = ocr_text.strip().replace("\n", "\n> ")
                ocr_parts.append("> **文档图片 #{}:**\n> {}".format(idx, quoted))

    if ocr_parts:
        ocr_parts.sort(key=lambda s: int(s.split("#")[1].split(":")[0]))
        result["content"] = result["content"] + "\n\n## OCR 图片识别\n\n" + "\n\n".join(ocr_parts)
        result["ocrImageCount"] = len(ocr_targets)
        result["ocrFailedCount"] = failed
        result["ocrEngine"] = model

    return result


def parse_docx(file_path, assets_dir=None, source_id=None):
    result = _extract_text_docx_structured(file_path, assets_dir=assets_dir, source_id=source_id)

    if result is None:
        result = _extract_text_pandoc_docx(file_path)

    if result is None:
        raise RuntimeError("DOCX 文件无法解析（文件可能已损坏或格式异常）")

    return result


def _extract_xlsx_metadata(file_path):
    try:
        from openpyxl import load_workbook
        wb = load_workbook(file_path, read_only=True)
        props = wb.properties
        result = {}
        for key in ("title", "creator", "subject", "category", "description"):
            val = getattr(props, key, None)
            if val:
                result[key] = str(val)
        wb.close()
        return result
    except ImportError:
        return {}
    except Exception:
        return {}


def _extract_text_pandas_xlsx(file_path):
    try:
        import pandas as pd
        all_sheets = pd.read_excel(file_path, sheet_name=None, dtype=str)
        blocks = []
        for name, df in all_sheets.items():
            blocks.append(f"## {name}")
            blocks.append(df.fillna("").to_markdown(index=False))
            if len(df) > 500:
                blocks.append(f"\n*（仅展示前 500 行数据，共 {len(df)} 行）*")
        content = "\n\n".join(blocks)
        if len(content.strip()) < 10:
            return None
        return {
            "content": content,
            "pageCount": len(all_sheets),
            "lowTextPages": 0,
            "hasScanWarning": False,
            "extractionEngine": "pandas",
        }
    except ImportError:
        return None
    except Exception:
        return None


def _extract_text_openpyxl_xlsx(file_path):
    try:
        from openpyxl import load_workbook
        wb = load_workbook(file_path, read_only=True, data_only=True)
        sheets = []

        for name in wb.sheetnames:
            ws = wb[name]
            rows = []
            rows.append(f"## {name}")

            header = []
            for cell in next(ws.iter_rows(min_row=1, max_row=1), []):
                header.append(str(cell.value) if cell.value is not None else "")
            if any(header):
                rows.append("| " + " | ".join(header) + " |")
                rows.append("| " + " | ".join(["---"] * len(header)) + " |")

            row_count = 0
            for row in ws.iter_rows(min_row=2, values_only=True):
                if row_count >= 500:
                    rows.append(f"\n*（仅展示前 500 行数据，共 {ws.max_row} 行）*")
                    break
                cells = [str(cell) if cell is not None else "" for cell in row]
                if any(cells):
                    rows.append("| " + " | ".join(cells) + " |")
                    row_count += 1

            sheets.append("\n".join(rows))

        wb.close()

        content = "\n\n".join(sheets)
        if len(content.strip()) < 10:
            return None

        return {
            "content": content,
            "pageCount": len(wb.sheetnames),
            "lowTextPages": 0,
            "hasScanWarning": False,
            "extractionEngine": "openpyxl",
        }
    except ImportError:
        return None
    except Exception:
        return None


def parse_xlsx(file_path):
    metadata = _extract_xlsx_metadata(file_path)

    result = _extract_text_pandas_xlsx(file_path)

    if result is None:
        result = _extract_text_openpyxl_xlsx(file_path)

    if result is None:
        raise RuntimeError("XLSX 文件无法解析（文件可能已损坏或格式异常）")

    if metadata:
        result["metadata"] = metadata

    return result


def _extract_pptx_metadata(file_path):
    try:
        from pptx import Presentation
        prs = Presentation(file_path)
        props = prs.core_properties
        result = {}
        for key in ("title", "author", "subject", "last_modified_by", "category"):
            val = getattr(props, key, None)
            if val:
                result[key] = str(val)
        return result
    except ImportError:
        return {}
    except Exception:
        return {}


def _extract_text_pandoc_pptx(file_path):
    import subprocess
    try:
        result = subprocess.run(
            ["pandoc", file_path, "-t", "markdown", "--wrap=none"],
            capture_output=True, text=True, encoding="utf-8", timeout=60
        )
        if result.returncode != 0:
            return None
        content = result.stdout.strip()
        if len(content) < 10:
            return None
        return {
            "content": content,
            "pageCount": None,
            "lowTextPages": 0,
            "hasScanWarning": False,
            "extractionEngine": "pandoc",
        }
    except FileNotFoundError:
        return None
    except Exception:
        return None


def _extract_text_markitdown_pptx(file_path):
    import subprocess
    try:
        result = subprocess.run(
            ["python", "-m", "markitdown", file_path],
            capture_output=True, text=True, encoding="utf-8", timeout=60
        )
        if result.returncode != 0:
            return None
        content = result.stdout.strip()
        if len(content) < 10:
            return None
        return {
            "content": content,
            "pageCount": None,
            "lowTextPages": 0,
            "hasScanWarning": False,
            "extractionEngine": "markitdown",
        }
    except FileNotFoundError:
        return None
    except Exception:
        return None


def _extract_text_python_pptx(file_path):
    try:
        from pptx import Presentation
        prs = Presentation(file_path)
        blocks = []
        slide_count = len(prs.slides)

        for idx, slide in enumerate(prs.slides, 1):
            lines = []

            title_texts = []
            body_texts = []
            for shape in slide.shapes:
                if not shape.has_text_frame:
                    continue
                for para in shape.text_frame.paragraphs:
                    text = para.text.strip()
                    if not text:
                        continue
                    if shape.is_placeholder and shape.placeholder_format.idx == 0:
                        title_texts.append(text)
                    else:
                        level = getattr(para, "level", None) or 0
                        prefix = "  " * level + "- " if level > 0 else "- "
                        body_texts.append(f"{prefix}{text}")

            if title_texts:
                lines.append(f"## 幻灯片 {idx}: {title_texts[0]}")
            else:
                lines.append(f"## 幻灯片 {idx}")

            for t in body_texts:
                lines.append(t)

            try:
                notes_slide = slide.notes_slide
                notes_text = notes_slide.notes_text_frame.text.strip()
                if notes_text:
                    lines.append(f"\n> **备注:** {notes_text}")
            except Exception:
                pass

            blocks.append("\n".join(lines))

        content = "\n\n".join(blocks)
        if len(content.strip()) < 10:
            return None

        return {
            "content": content,
            "pageCount": slide_count,
            "lowTextPages": 0,
            "hasScanWarning": False,
            "extractionEngine": "python-pptx",
        }
    except ImportError:
        return None
    except Exception:
        return None


def _ocr_pptx_images(file_path, result, api_key, model="qwen-vl-plus", max_images=20, skip_slides=None):
    from pptx import Presentation
    from pptx.enum.shapes import MSO_SHAPE_TYPE
    import base64
    import json
    import urllib.request

    prs = Presentation(file_path)
    ocr_targets = []
    skip_set = set(skip_slides or [])

    for idx, slide in enumerate(prs.slides):
        if idx in skip_set:
            continue
        text_shape_count = 0
        image_shapes = []
        for shape in slide.shapes:
            if shape.shape_type == MSO_SHAPE_TYPE.PICTURE:
                image_shapes.append(shape)
            elif shape.has_text_frame:
                if any(p.text.strip() for p in shape.text_frame.paragraphs):
                    text_shape_count += 1

        if not image_shapes:
            continue

        if text_shape_count > 2:
            continue

        for shape in image_shapes:
            if len(ocr_targets) >= max_images:
                break
            ocr_targets.append((idx, shape))

        if len(ocr_targets) >= max_images:
            break

    if not ocr_targets:
        return result

    ocr_parts = []
    failed = 0
    for slide_idx, shape in ocr_targets:
        try:
            img_bytes = shape.image.blob
            img_b64 = base64.b64encode(img_bytes).decode("ascii")
            mime_type = shape.image.content_type or "image/png"

            payload = {
                "model": model,
                "messages": [{
                    "role": "user",
                    "content": [
                        {"type": "text", "text": OCR_PROMPT},
                        {"type": "image_url", "image_url": {"url": f"data:{mime_type};base64,{img_b64}"}}
                    ]
                }],
                "max_tokens": 4096,
            }

            url = f"{_OCR_BASE_URL}/v1/chat/completions"
            headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}
            req = urllib.request.Request(url, data=json.dumps(payload).encode("utf-8"), headers=headers)
            with urllib.request.urlopen(req, timeout=30) as resp:
                ocr_text = json.loads(resp.read().decode("utf-8"))["choices"][0]["message"]["content"]

            if _is_valid_ocr_text(ocr_text):
                quoted = ocr_text.strip().replace("\n", "\n> ")
                ocr_parts.append(f"> **幻灯片 {slide_idx + 1} 图片:**\n> {quoted}")

        except Exception as e:
            failed += 1
            import traceback
            traceback.print_exc(file=sys.stderr)

    if ocr_parts:
        result["content"] = result["content"] + "\n\n## OCR 图片识别\n\n" + "\n\n".join(ocr_parts)
        result["ocrImageCount"] = len(ocr_targets)
        result["ocrFailedCount"] = failed
        result["ocrEngine"] = model

    return result


def _analyze_diagram_with_vl(img_bytes, mime_type, api_key, model, page_label=""):
    """调用VL模型分析图片中的图表/流程图，返回结构化JSON（含重试）"""
    import base64
    import json as _json
    import urllib.request
    import urllib.error
    import time

    img_b64 = base64.b64encode(img_bytes).decode("ascii")
    label_hint = f"（图片位置：{page_label}）" if page_label else ""

    url = f"{_DIAGRAM_BASE_URL}/v1/chat/completions"

    payload = {
        "model": model,
        "messages": [{
            "role": "user",
            "content": [
                {"type": "text", "text": DIAGRAM_ANALYSIS_PROMPT + label_hint},
                {"type": "image_url", "image_url": {"url": f"data:{mime_type};base64,{img_b64}"}}
            ]
        }],
        "max_tokens": 4096,
        "temperature": 0.1,
    }

    headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}

    # 重试逻辑: 429/502/503 自动指数退避（3次）
    max_retries = 3
    for attempt in range(max_retries):
        try:
            req = urllib.request.Request(url, data=_json.dumps(payload).encode("utf-8"), headers=headers)
            with urllib.request.urlopen(req, timeout=60) as resp:
                result = _json.loads(resp.read().decode("utf-8"))
                raw_content = result["choices"][0]["message"]["content"]
            break  # 成功则跳出重试
        except urllib.error.HTTPError as e:
            if e.code in (429, 502, 503) and attempt < max_retries - 1:
                delay = 2 * (2 ** attempt)
                print(f"[DIAGRAM_RETRY] {e.code}, attempt {attempt+1}/{max_retries}, retry in {delay}s", file=sys.stderr)
                time.sleep(delay)
                continue
            raise

    raw_content = raw_content.strip()
    json_start = raw_content.find("{")
    json_end = raw_content.rfind("}")
    if json_start >= 0 and json_end > json_start:
        parsed = _json.loads(raw_content[json_start:json_end + 1])
    else:
        parsed = {"type": "other", "description": raw_content[:200], "code": ""}

    diagram_type = parsed.get("type", "other")
    description = parsed.get("description", "")
    code = parsed.get("code", "")

    code_language = "mermaid"
    if diagram_type == "data_chart":
        code_language = "echarts"

    if diagram_type in ("screenshot", "photo", "other") or not code:
        return None

    if code_language == "mermaid":
        first_line = code.strip().split("\n")[0].strip().rstrip("{")
        valid_starters = ("flowchart", "graph", "sequenceDiagram", "classDiagram",
                          "erDiagram", "mindmap", "gantt", "pie", "stateDiagram",
                          "journey", "gitGraph", "timeline", "quadrantChart")
        if not any(first_line.startswith(s) for s in valid_starters):
            return None

    if code_language == "echarts":
        if not code.strip().startswith("{"):
            code = "{" + code

    return {
        "type": diagram_type,
        "description": description,
        "code": code,
        "codeLanguage": code_language,
    }


def _get_diagram_candidate_pages_pdf(file_path, score_threshold=5.0,
                                       large_drawing_ratio=0.05,
                                       significant_image_ratio=0.05):
    """快速启发式检测，返回需要优先走Diagram分析的页面索引集合（0-based）。
    供OCR阶段跳过这些页面，避免重复VL分析。"""
    try:
        import fitz
        doc = fitz.open(file_path)
    except Exception:
        return set()

    candidates = set()
    for idx in range(len(doc)):
        try:
            page = doc[idx]
            drawings = page.get_drawings()
            images = page.get_images()
            text = page.get_text("text")
            text_len = len(text.strip())
            page_area = page.rect.width * page.rect.height

            total_draw_area = sum(d["rect"].width * d["rect"].height for d in drawings if d.get("rect"))
            draw_coverage = total_draw_area / page_area if page_area > 0 else 0
            filled_drawings = [d for d in drawings if d.get("fill")]
            multi_path_drawings = [d for d in drawings if len(d.get("items", [])) >= 2]

            score = 0

            if len(filled_drawings) >= 3 and len(multi_path_drawings) >= 1:
                score += 10
            elif draw_coverage > 0.1 and len(multi_path_drawings) >= 1:
                score += 7
            elif draw_coverage > 0.3 and len(filled_drawings) >= 3:
                score += 4

            image_area_total = 0
            try:
                img_infos = page.get_image_info(xrefs=True)
                for info in img_infos:
                    transform = info.get("transform", (1, 0, 0, 1, 0, 0))
                    img_w = abs(transform[0]) if len(transform) > 0 else 0
                    img_h = abs(transform[3]) if len(transform) > 3 else 0
                    image_area_total += img_w * img_h
            except Exception:
                if images:
                    image_area_total = page_area * 0.1 * len(images)

            image_area_ratio = image_area_total / page_area if page_area > 0 else 0
            if image_area_ratio > 0.15:
                score += 6
            elif image_area_ratio > significant_image_ratio:
                score += 3

            if text_len < 100 and (len(drawings) >= 1 or len(images) >= 1):
                score += 4

            if score >= score_threshold:
                candidates.add(idx)
        except Exception:
            pass

    doc.close()
    return candidates


def _extract_and_analyze_diagrams_pdf(file_path, api_key, model, max_images=15,
                                        dpi=200, jpeg_quality=85, concurrency=4,
                                        score_threshold=5.0, large_drawing_ratio=0.05,
                                        significant_image_ratio=0.05, payload_gate_mb=2,
                                        pdfplumber_table_pages=None,
                                        assets_dir=None, source_id=None,
                                        watermark_texts=None):
    """区域级图表检测 + 原图裁剪 + VL一句话描述，替代旧的整页代码生成策略"""
    import fitz
    from concurrent.futures import ThreadPoolExecutor, as_completed

    _table_page_set = set(pdfplumber_table_pages or [])
    if watermark_texts is None:
        watermark_texts = set()

    try:
        doc = fitz.open(file_path)
    except Exception:
        return [], 0

    # 准备 assets 目录
    charts_dir = None
    charts_rel_prefix = None
    if assets_dir and source_id:
        charts_dir = Path(assets_dir) / "charts"
        charts_dir.mkdir(parents=True, exist_ok=True)
        charts_rel_prefix = f"charts/"

    # 阶段 1: 逐页区域级图表检测
    # 两遍扫描：先处理非表格页（高优先级），再处理表格页（低优先级）
    # 表格页上的独立图表（柱状图/折线图等）仍需检测，但排在后面
    all_chart_tasks = []
    page_order = [idx for idx in range(len(doc)) if (idx + 1) not in _table_page_set]
    page_order += [idx for idx in range(len(doc)) if (idx + 1) in _table_page_set]

    for idx in page_order:
        try:
            page = doc[idx]
            chart_regions = _detect_chart_regions(page, page_num=idx + 1)
            if not chart_regions:
                continue
            chart_regions.sort(key=lambda r: (r["bbox"][1], r["bbox"][0]))
            pw, ph = page.rect.width, page.rect.height
            clipped_bboxes, text_filter_bboxes = _compute_non_overlapping_bboxes(
                chart_regions, page, pw, ph, watermark_texts)
            for ci, region in enumerate(chart_regions):
                if len(all_chart_tasks) >= max_images:
                    break
                try:
                    clip_rect = clipped_bboxes[ci]
                    img_info = _extract_chart_image(
                        page, region["bbox"], idx + 1, ci + 1,
                        dpi=dpi, clip_rect=clip_rect)
                    all_chart_tasks.append({
                        "page_idx": idx,
                        "source": region["source"],
                        "imageBase64": img_info["imageBase64"],
                        "imageMimeType": img_info["imageMimeType"],
                        "fileName": img_info["fileName"],
                        "width": img_info["width"],
                        "height": img_info["height"],
                    })
                except Exception as e:
                    logger.warning(f"[CHART] P{idx+1} extract failed: {e}")
        except Exception as e:
            logger.warning(f"[CHART_DETECT] P{idx+1} exception: {type(e).__name__}: {e}")
        if len(all_chart_tasks) >= max_images:
            break

    doc.close()

    if not all_chart_tasks:
        return [], 0

    # 阶段 2: 并行VL结构化描述
    charts = []
    failed = 0
    _source_type_map = {
        "bar_chart": "data_chart", "line_chart": "data_chart",
        "pie_chart": "data_chart", "area_chart": "data_chart",
        "scatter_plot": "data_chart", "image": "other",
        "flowchart": "flowchart",
    }

    def _describe_task(task):
        desc = _vl_describe_chart(task["imageBase64"], task["imageMimeType"], api_key, model)
        return desc

    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = {executor.submit(_describe_task, t): i for i, t in enumerate(all_chart_tasks)}
        descriptions = [""] * len(all_chart_tasks)
        for future in as_completed(futures):
            task_idx = futures[future]
            try:
                descriptions[task_idx] = future.result(timeout=90)
            except Exception:
                failed += 1
                logger.warning(f"[VL] describe task {task_idx} failed")

    # 阶段 3: 组装结果 + 保存图表文件
    for i, task in enumerate(all_chart_tasks):
        desc = descriptions[i]
        chart_type = _source_type_map.get(task["source"], "other")
        page_num = task["page_idx"] + 1
        relative_path = None
        if charts_dir is not None:
            fname = f"{source_id}_chart_p{page_num}_{i + 1}.png"
            fpath = charts_dir / fname
            try:
                img_bytes = base64.b64decode(task["imageBase64"])
                fpath.write_bytes(img_bytes)
                relative_path = f"{charts_rel_prefix}{fname}"
            except Exception as e:
                logger.warning(f"[CHART] save failed: {e}")
        # 处理结构化描述：dict → 提取 summary 作为兼容 description
        if isinstance(desc, dict):
            desc_summary = desc.get("summary", "")
            chart_entry = {
                "type": chart_type,
                "description": desc_summary,
                "structuredDescription": desc,
                "source": task["source"],
                "pageRef": page_num,
                "imageBase64": task["imageBase64"],
                "imageMimeType": task["imageMimeType"],
                "width": task["width"],
                "height": task["height"],
            }
        else:
            chart_entry = {
                "type": chart_type,
                "description": desc,
                "source": task["source"],
                "pageRef": page_num,
                "imageBase64": task["imageBase64"],
                "imageMimeType": task["imageMimeType"],
                "width": task["width"],
                "height": task["height"],
            }
        if relative_path:
            chart_entry["relativePath"] = relative_path
        charts.append(chart_entry)

    logger.info(f"[CHART] detected {len(charts)} chart regions, {failed} VL failures")
    return charts, failed


def _format_diagram_block(d, page_num):
    """格式化单个图表/图表为Markdown块（支持代码、结构化描述和原图嵌入）"""
    diagram_type = d.get("type", "other")
    label_map = {
        "flowchart": "流程图", "org_chart": "组织架构图", "sequence": "时序图",
        "architecture": "架构图", "data_chart": "数据图表", "mindmap": "思维导图",
        "table": "表格", "bar_chart": "柱状图", "line_chart": "折线图",
        "pie_chart": "饼图", "area_chart": "面积图", "scatter_plot": "散点图",
    }
    source_label_map = {
        "image": "嵌入图片", "bar_chart": "柱状图", "line_chart": "折线图",
        "pie_chart": "饼图", "area_chart": "面积图", "scatter_plot": "散点图",
        "flowchart": "流程图/架构图",
    }
    description = d.get("description", "")
    structured_desc = d.get("structuredDescription")
    code = d.get("code", "")
    code_lang = d.get("codeLanguage", "")
    source = d.get("source", "")
    relative_path = d.get("relativePath", "")
    image_b64 = d.get("imageBase64", "")
    mime = d.get("imageMimeType", "image/png")
    ref_str = f"（第{page_num}页）" if page_num else ""

    if code and code_lang:
        label = label_map.get(diagram_type, "图表")
        header = f"\n\n**{label}**{ref_str}"
        if description:
            header += f" — {description}"
        header += f"\n\n```{code_lang}\n{code}\n```"
        return header

    label = source_label_map.get(source, label_map.get(diagram_type, "图表"))

    # 结构化描述渲染：表格 + 要点 + 趋势
    if structured_desc and isinstance(structured_desc, dict):
        return _render_structured_chart(structured_desc, label, ref_str, relative_path)

    # 旧格式兼容：纯文本 description
    header = f"\n\n**{label}**{ref_str}"
    if description:
        header += f" — {description}"
    if relative_path:
        header += f"\n\n![{label}]({relative_path})"
    return header


def _render_structured_chart(desc, label, ref_str, relative_path):
    """将结构化 VL 描述渲染为丰富 Markdown（标题+摘要+数据表+要点+趋势+图片引用）"""
    parts = []
    title = desc.get("title", "")
    chart_type = desc.get("chartType", "")
    summary = desc.get("summary", "")
    data_table = desc.get("dataTable") or {}
    key_points = desc.get("keyPoints") or []
    trend = desc.get("trend", "")

    # 标题行
    display_title = title if title else label
    type_suffix = f"（{chart_type}）" if chart_type and chart_type != label else ""
    parts.append(f"\n\n**{display_title}**{type_suffix}{ref_str}")

    # 摘要
    if summary:
        parts.append(summary)

    # 数据表格
    headers = data_table.get("headers") or []
    rows = data_table.get("rows") or []
    if headers and rows:
        table_lines = ["| " + " | ".join(str(h) for h in headers) + " |"]
        table_lines.append("| " + " | ".join("---" for _ in headers) + " |")
        for row in rows:
            table_lines.append("| " + " | ".join(str(c) for c in row) + " |")
        parts.append("\n" + "\n".join(table_lines))

    # 关键要点
    if key_points:
        points_text = "\n".join(f"- {p}" for p in key_points if p)
        if points_text:
            parts.append(f"\n**关键信息：**\n{points_text}")

    # 趋势描述
    if trend:
        parts.append(f"\n**数据趋势：** {trend}")

    # 图片引用
    if relative_path:
        parts.append(f"\n![{display_title}]({relative_path})")

    return "\n".join(parts)


def _merge_diagram_blocks(content, diagrams):
    """将图表/图表按页号精准插入到Markdown对应位置"""
    if not diagrams:
        return content

    by_page = {}
    for d in diagrams:
        page_ref = d.get("pageRef") or d.get("slideRef") or 0
        if page_ref not in by_page:
            by_page[page_ref] = []
        by_page[page_ref].append(d)

    pages = content.split("\n\n---\n\n")
    if len(pages) <= 1:
        pages = content.split("\n\n\n")

    if len(pages) <= 1:
        return _append_diagram_blocks(content, diagrams)

    result_pages = list(pages)

    for page_num in sorted(by_page.keys(), reverse=True):
        if 0 < page_num <= len(result_pages):
            insert_idx = page_num - 1
            blocks = []
            for d in by_page[page_num]:
                blocks.append(_format_diagram_block(d, page_num))
            result_pages[insert_idx] = result_pages[insert_idx] + "".join(blocks)

    return "\n\n---\n\n".join(result_pages)


def _append_diagram_blocks(content, diagrams):
    """回退方案：将图表/图表追加到内容末尾"""
    blocks = []
    for d in diagrams:
        page_ref = d.get("pageRef") or d.get("slideRef") or 0
        blocks.append(_format_diagram_block(d, page_ref))
    return content + "\n".join(blocks)


def _extract_and_analyze_diagrams_docx(file_path, api_key, model, max_images=15,
                                        assets_dir=None, source_id=None):
    """从DOCX提取内嵌图片，用VL模型分析图表，并持久化原图到assets目录"""
    try:
        from docx import Document
    except ImportError:
        return [], 0

    try:
        doc = Document(file_path)
    except Exception:
        return [], 0

    assets_path = Path(assets_dir) if assets_dir else None
    if assets_path:
        assets_path.mkdir(parents=True, exist_ok=True)

    diagrams = []
    failed = 0
    img_count = 0

    for rel in doc.part.rels.values():
        if img_count >= max_images:
            break
        if "image" not in rel.reltype:
            continue
        try:
            img_bytes = rel.target_part.blob
            if len(img_bytes) < _MIN_DIAGRAM_IMAGE_BYTES:
                continue
            partname = rel.target_part.partname
            ext = partname.rsplit(".", 1)[-1].lower() if "." in partname else "png"
            if ext == "jpeg":
                ext = "jpg"
            mime_map = {"jpg": "image/jpeg", "jpeg": "image/jpeg", "png": "image/png", "gif": "image/gif", "webp": "image/webp"}
            mime_type = mime_map.get(ext, "image/png")
            img_count += 1

            relative_path = None
            if assets_path and source_id:
                img_filename = f"{source_id}_docx_chart{img_count}.{ext}"
                img_path = assets_path / img_filename
                img_path.write_bytes(img_bytes)
                relative_path = img_filename

            result = _analyze_diagram_with_vl(
                img_bytes, mime_type, api_key, model, f"DOCX图片#{img_count}"
            )
            if result:
                result["imageRef"] = img_count
                if relative_path:
                    result["relativePath"] = relative_path
                diagrams.append(result)
        except Exception:
            failed += 1
            import traceback
            traceback.print_exc(file=sys.stderr)

    return diagrams, failed


def _get_diagram_candidate_slides_pptx(file_path):
    """检测PPTX中有图片形状的幻灯片索引集合（0-based），供OCR阶段跳过。"""
    try:
        from pptx import Presentation
        from pptx.enum.shapes import MSO_SHAPE_TYPE
        prs = Presentation(file_path)
    except Exception:
        return set()

    candidates = set()
    for idx, slide in enumerate(prs.slides):
        for shape in slide.shapes:
            if shape.shape_type == MSO_SHAPE_TYPE.PICTURE:
                try:
                    if len(shape.image.blob) >= _MIN_DIAGRAM_IMAGE_BYTES:
                        candidates.add(idx)
                        break
                except Exception:
                    pass
    return candidates


def _extract_and_analyze_diagrams_pptx(file_path, api_key, model, max_images=15,
                                        assets_dir=None, source_id=None):
    """从PPTX提取图片形状，用VL模型分析图表，并持久化原图到assets目录"""
    try:
        from pptx import Presentation
        from pptx.enum.shapes import MSO_SHAPE_TYPE
    except ImportError:
        return [], 0

    try:
        prs = Presentation(file_path)
    except Exception:
        return [], 0

    assets_path = Path(assets_dir) if assets_dir else None
    if assets_path:
        assets_path.mkdir(parents=True, exist_ok=True)

    diagrams = []
    failed = 0
    img_count = 0

    for idx, slide in enumerate(prs.slides):
        if img_count >= max_images:
            break
        for shape in slide.shapes:
            if img_count >= max_images:
                break
            if shape.shape_type != MSO_SHAPE_TYPE.PICTURE:
                continue
            try:
                img_bytes = shape.image.blob
                if len(img_bytes) < _MIN_DIAGRAM_IMAGE_BYTES:
                    continue
                mime_type = shape.image.content_type or "image/png"
                img_count += 1

                ext = "png"
                if "jpeg" in mime_type or "jpg" in mime_type:
                    ext = "jpg"
                elif "gif" in mime_type:
                    ext = "gif"
                elif "webp" in mime_type:
                    ext = "webp"

                relative_path = None
                if assets_path and source_id:
                    img_filename = f"{source_id}_pptx_chart{img_count}.{ext}"
                    img_path = assets_path / img_filename
                    img_path.write_bytes(img_bytes)
                    relative_path = img_filename

                result = _analyze_diagram_with_vl(
                    img_bytes, mime_type, api_key, model, f"幻灯片{idx + 1}"
                )
                if result:
                    result["slideRef"] = idx + 1
                    if relative_path:
                        result["relativePath"] = relative_path
                    diagrams.append(result)
            except Exception:
                failed += 1
                import traceback
                traceback.print_exc(file=sys.stderr)

    return diagrams, failed


def parse_pptx(file_path):
    metadata = _extract_pptx_metadata(file_path)

    result = _extract_text_pandoc_pptx(file_path)

    if result is None:
        result = _extract_text_markitdown_pptx(file_path)

    if result is None:
        result = _extract_text_python_pptx(file_path)

    if result is None:
        raise RuntimeError("PPTX 文件无法解析（文件可能已损坏或格式异常）")

    if result["pageCount"] is None:
        result["pageCount"] = 1

    if metadata:
        result["metadata"] = metadata

    return result


def main():
    parser = argparse.ArgumentParser(description="文档解析工具")
    parser.add_argument("--input", required=True, help="输入文件路径")
    parser.add_argument("--format", required=True, help="文件格式: pdf/docx/doc/xlsx/pptx/ppt/md/txt")
    parser.add_argument("--ocr-enable", default=False, action="store_true", help="启用OCR识别扫描件页面")
    parser.add_argument("--ocr-api-key", default="", help="OCR 模型的 API Key（OCR启用时必填）")
    parser.add_argument("--ocr-base-url", default="", help="OCR 模型的 API Base URL（默认使用 DashScope 兼容模式）")
    parser.add_argument("--ocr-model", default="qwen-vl-ocr", help="OCR识别模型（推荐qwen-vl-ocr专用模型）")
    parser.add_argument("--ocr-max-pages", type=int, default=20, help="单次OCR最大页数")
    parser.add_argument("--ocr-threshold", type=float, default=0.3, help="触发OCR的低文字页比例阈值")
    parser.add_argument("--multimodal-main", default=False, action="store_true", help="多模态主模型模式：保存图片供主模型直接查看，跳过OCR")
    parser.add_argument("--assets-dir", default="", help="资源保存目录（图表裁剪图片、多模态图片等）")
    parser.add_argument("--diagram-enable", default=False, action="store_true", help="启用图表检测与原图嵌入（区域检测+裁剪PNG+VL描述）")
    parser.add_argument("--diagram-api-key", default="", help="图表分析的VL模型API Key")
    parser.add_argument("--diagram-base-url", default="", help="图表分析的VL模型 API Base URL（默认使用 DashScope 兼容模式）")
    parser.add_argument("--diagram-model", default="kimi-k2.6", help="图表分析的VL模型名称（需强多模态能力，推荐kimi-k2.6或qwen-vl-max）")
    parser.add_argument("--diagram-max-images", type=int, default=15, help="单文档最多分析的图表数量")
    parser.add_argument("--diagram-dpi", type=int, default=200, help="图表裁剪渲染DPI（200保证清晰度）")
    parser.add_argument("--diagram-jpeg-quality", type=int, default=85, help="JPEG压缩质量(85推荐，平衡画质与传输) ")
    parser.add_argument("--diagram-concurrency", type=int, default=4, help="并行VL调用线程数")
    parser.add_argument("--diagram-score-threshold", type=float, default=5.0, help="候选页评分阈值（>=此值才发送VL分析）")
    parser.add_argument("--diagram-large-drawing-ratio", type=float, default=0.05, help="大绘图元素面积占比阈值")
    parser.add_argument("--diagram-image-area-ratio", type=float, default=0.05, help="显著图片面积占比阈值")
    parser.add_argument("--diagram-payload-gate-mb", type=float, default=2.0, help="payload大小门控(MB)")
    parser.add_argument("--image-desc-model", default="", help="嵌入图片VL描述模型（默认 fallback 到 diagram-model，推荐 qwen3.6-flash）")
    args = parser.parse_args()

    global _OCR_BASE_URL, _DIAGRAM_BASE_URL
    if args.ocr_base_url:
        _OCR_BASE_URL = args.ocr_base_url.rstrip("/")
    if args.diagram_base_url:
        _DIAGRAM_BASE_URL = args.diagram_base_url.rstrip("/")

    file_path = Path(args.input)
    if not file_path.exists():
        print(json.dumps({"error": f"文件不存在: {args.input}"}))
        sys.exit(1)

    real_path = file_path.resolve()
    allowed_dirs = [
        Path.cwd().resolve(),
        Path.cwd().resolve() / "wiki-data",
    ]
    if not any(str(real_path).startswith(str(d)) for d in allowed_dirs):
        print(json.dumps({"error": "文件路径不在允许的目录范围内"}))
        sys.exit(1)

    fmt = args.format.lower()

    extracted_images = None

    try:
        if fmt == "pdf":
            source_id = file_path.stem
            assets_dir_val = args.assets_dir if args.assets_dir else ""
            result = parse_pdf(str(real_path), assets_dir=assets_dir_val, source_id=source_id)
            if result.get("extractedImages"):
                extracted_images = result["extractedImages"]
            if args.multimodal_main and args.assets_dir and result.get("hasScanWarning"):
                if not result.get("scanOnlyFallback"):
                    result, multimodal_images = _save_low_text_page_images(str(real_path), result, args.assets_dir, source_id)
                    if extracted_images is None:
                        extracted_images = multimodal_images
                    else:
                        extracted_images = list(extracted_images) + multimodal_images
            elif args.ocr_enable and args.ocr_api_key and result.get("hasScanWarning"):
                skip_ocr_pages = None
                if args.diagram_enable and args.diagram_api_key:
                    skip_ocr_pages = _get_diagram_candidate_pages_pdf(
                        str(real_path), args.diagram_score_threshold,
                        args.diagram_large_drawing_ratio, args.diagram_image_area_ratio)
                result = _ocr_low_text_pages(str(real_path), result, args.ocr_api_key,
                    args.ocr_model, args.ocr_max_pages, args.ocr_threshold,
                    skip_pages=skip_ocr_pages)
                if args.assets_dir and not extracted_images:
                    _, scan_page_images = _save_low_text_page_images(str(real_path), result, args.assets_dir, source_id)
                    if scan_page_images:
                        extracted_images = scan_page_images
        elif fmt in ("docx", "doc"):
            source_id = file_path.stem
            assets_dir_val = args.assets_dir if args.assets_dir else ""
            result = parse_docx(str(real_path), assets_dir=assets_dir_val, source_id=source_id)
            if result.get("extractedImages"):
                extracted_images = result["extractedImages"]
            if args.multimodal_main and args.assets_dir:
                result, multimodal_images = _save_docx_images_for_multimodal(str(real_path), result, args.assets_dir, source_id)
                if extracted_images is None:
                    extracted_images = multimodal_images
                else:
                    extracted_images = list(extracted_images) + multimodal_images
            elif args.ocr_enable and args.ocr_api_key:
                pre_imgs = result.pop("_preExtractedImages", None)
                result = _ocr_docx_images(str(real_path), result, args.ocr_api_key,
                    args.ocr_model, args.ocr_max_pages, pre_extracted_images=pre_imgs)
        elif fmt in ("xlsx", "xls"):
            result = parse_xlsx(str(real_path))
        elif fmt in ("pptx", "ppt"):
            result = parse_pptx(str(real_path))
            if args.multimodal_main and args.assets_dir:
                source_id = file_path.stem
                result, extracted_images = _save_pptx_images_for_multimodal(str(real_path), result, args.assets_dir, source_id)
            elif args.ocr_enable and args.ocr_api_key:
                skip_ocr_slides = None
                if args.diagram_enable and args.diagram_api_key:
                    skip_ocr_slides = _get_diagram_candidate_slides_pptx(str(real_path))
                result = _ocr_pptx_images(str(real_path), result, args.ocr_api_key, args.ocr_model, skip_slides=skip_ocr_slides)
        elif fmt in ("md", "txt"):
            content = real_path.read_text(encoding="utf-8", errors="replace")
            result = {
                "content": content,
                "pageCount": 1,
                "lowTextPages": 0,
                "hasScanWarning": False,
            }
        else:
            print(json.dumps({"error": f"不支持的格式: {fmt}"}))
            sys.exit(1)

        # VL 描述所有提取的图片（使用独立的多模态快速模型，解耦于图表分析）
        _image_desc_model = args.image_desc_model if args.image_desc_model else args.diagram_model
        if extracted_images and args.diagram_api_key and assets_dir_val:
            _vl_count = _vl_describe_extracted_images(
                extracted_images, assets_dir_val, args.diagram_api_key, _image_desc_model,
                concurrency=args.diagram_concurrency if hasattr(args, 'diagram_concurrency') else 4,
                max_images=15)
            if _vl_count > 0:
                _content = result.get("content", "")
                for _img in extracted_images:
                    _old_desc = _img.get("_old_description", "")
                    _new_desc = _img.get("description", "")
                    _rel_path = _img.get("path", "")
                    if _old_desc and _new_desc and _old_desc != _new_desc and _rel_path:
                        _old_marker = f"![{_old_desc}]({_rel_path})"
                        _new_marker = f"![{_new_desc}]({_rel_path})"
                        _content = _content.replace(_old_marker, _new_marker)
                result["content"] = _content

        diagrams = []
        diagram_failed = 0
        if args.diagram_enable and args.diagram_api_key:
            if fmt == "pdf":
                _table_pages = result.get("layoutInfo", {}).get("tablePages", [])
                _chart_assets_dir = assets_dir_val if assets_dir_val else str(Path(real_path).parent / "assets")
                _watermark_texts_for_diagram = set()
                try:
                    import fitz as _fitz_for_wm
                    _wm_doc = _fitz_for_wm.open(str(real_path))
                    _watermark_texts_for_diagram = _detect_watermarks(_wm_doc)
                    _wm_doc.close()
                    if _watermark_texts_for_diagram:
                        logger.info(f"[DIAGRAM] detected {len(_watermark_texts_for_diagram)} watermark patterns for chart filtering")
                except Exception as _wm_err:
                    logger.debug(f"[DIAGRAM] watermark detection skipped: {_wm_err}")
                diagrams, diagram_failed = _extract_and_analyze_diagrams_pdf(
                    str(real_path), args.diagram_api_key, args.diagram_model, args.diagram_max_images,
                    dpi=args.diagram_dpi, jpeg_quality=args.diagram_jpeg_quality,
                    concurrency=args.diagram_concurrency, score_threshold=args.diagram_score_threshold,
                    large_drawing_ratio=args.diagram_large_drawing_ratio,
                    significant_image_ratio=args.diagram_image_area_ratio,
                    payload_gate_mb=args.diagram_payload_gate_mb,
                    pdfplumber_table_pages=_table_pages,
                    assets_dir=_chart_assets_dir, source_id=source_id,
                    watermark_texts=_watermark_texts_for_diagram)
                # 去重：VL 返回 type=table 且与 pdfplumber 表格同页 → 只保留 pdfplumber 版本
                if _table_pages and diagrams:
                    table_page_set = set(_table_pages)
                    diagrams = [d for d in diagrams if d.get("type") != "table" or d.get("pageRef") not in table_page_set]
            elif fmt in ("docx", "doc"):
                _chart_assets_dir = assets_dir_val if assets_dir_val else str(Path(real_path).parent / "assets")
                diagrams, diagram_failed = _extract_and_analyze_diagrams_docx(
                    str(real_path), args.diagram_api_key, args.diagram_model, args.diagram_max_images,
                    assets_dir=_chart_assets_dir, source_id=source_id)
            elif fmt in ("pptx", "ppt"):
                _chart_assets_dir = assets_dir_val if assets_dir_val else str(Path(real_path).parent / "assets")
                diagrams, diagram_failed = _extract_and_analyze_diagrams_pptx(
                    str(real_path), args.diagram_api_key, args.diagram_model, args.diagram_max_images,
                    assets_dir=_chart_assets_dir, source_id=source_id)
            if diagrams:
                result["content"] = _merge_diagram_blocks(result["content"], diagrams)

        content = result["content"]
        if len(content.strip()) < 10:
            print(json.dumps({"error": "文档内容为空或无法提取有效文本"}))
            sys.exit(1)

        result.pop("_preExtractedImages", None)

        output = {
            "content": content,
            "pageCount": result["pageCount"],
        }

        metadata = result.get("metadata")
        if metadata:
            output["metadata"] = metadata

        engine = result.get("extractionEngine")
        if engine:
            output["extractionEngine"] = engine

        ocr_pages = result.get("ocrPages")
        if ocr_pages:
            output["ocrPages"] = ocr_pages
        ocr_failed_pages = result.get("ocrFailedPages")
        if ocr_failed_pages is not None:
            output["ocrFailedPages"] = ocr_failed_pages
        ocr_image_count = result.get("ocrImageCount")
        if ocr_image_count:
            output["ocrImageCount"] = ocr_image_count
        ocr_failed_count = result.get("ocrFailedCount")
        if ocr_failed_count is not None:
            output["ocrFailedCount"] = ocr_failed_count
        ocr_engine = result.get("ocrEngine")
        if ocr_engine:
            output["ocrEngine"] = ocr_engine
        ocr_skipped = result.get("ocrSkippedPages")
        if ocr_skipped:
            output["ocrSkippedPages"] = ocr_skipped

        if result.get("hasScanWarning"):
            output["warning"] = "scan_warning"
            output["warningMessage"] = "检测到此 PDF 文字内容较少，可能为扫描件。AI 分析准确度可能降低。"

        layout_info = result.get("layoutInfo")
        if layout_info:
            output["layoutInfo"] = layout_info

        if extracted_images:
            output["extractedImages"] = extracted_images

        if diagrams:
            diagrams_for_output = []
            for d in diagrams:
                d_out = {k: v for k, v in d.items() if k != "imageBase64"}
                diagrams_for_output.append(d_out)
            output["diagrams"] = diagrams_for_output
            output["diagramCount"] = len(diagrams)
            chart_images = [d for d in diagrams if d.get("imageBase64")]
            if chart_images:
                output["chartImages"] = [{
                    "fileName": d.get("fileName", ""),
                    "relativePath": d.get("relativePath", ""),
                    "description": d.get("description", ""),
                    "source": d.get("source", ""),
                    "page": d.get("pageRef", 0),
                } for d in chart_images]
                output["chartCount"] = len(chart_images)
        if diagram_failed > 0:
            output["diagramFailedCount"] = diagram_failed
        if args.diagram_enable:
            output["diagramEngine"] = args.diagram_model

        print(json.dumps(output, ensure_ascii=False))

    except Exception as e:
        err_msg = str(e) if str(e) else repr(e)
        if not err_msg.strip():
            err_msg = f"{type(e).__name__}（无错误消息）"
        else:
            err_msg = f"{type(e).__name__}: {err_msg}"
        print(json.dumps({"error": f"文档解析失败: {err_msg}"}))
        traceback.print_exc(file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
