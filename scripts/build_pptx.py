#!/usr/bin/env python3
"""
Build FinalPresentation.pptx from docs/final/09_Presentation_Notes.md.
Dark theme. Speaker notes piped into each slide's notes pane.
Usage: python3 scripts/build_pptx.py
Output: docs/final/FinalPresentation.pptx
"""
import re
import os
from pptx import Presentation
from pptx.util import Inches, Pt, Emu
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN

# ── Paths ──────────────────────────────────────────────────────────────────────
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NOTES_FILE = os.path.join(REPO_ROOT, "docs", "final", "09_Presentation_Notes.md")
OUTPUT_FILE = os.path.join(REPO_ROOT, "docs", "final", "FinalPresentation.pptx")

# ── Theme colours ──────────────────────────────────────────────────────────────
BG_COLOR      = RGBColor(0x1a, 0x1a, 0x2e)   # deep navy
TITLE_COLOR   = RGBColor(0xe0, 0xe0, 0xff)   # soft lavender-white
BODY_COLOR    = RGBColor(0xcc, 0xcc, 0xcc)   # light grey
ACCENT_COLOR  = RGBColor(0x7c, 0xb9, 0xe8)   # steel blue accent
CODE_COLOR    = RGBColor(0xa8, 0xe6, 0xcf)   # mint green for code spans
SLIDE_W = Inches(13.33)
SLIDE_H = Inches(7.5)

# ── Parse notes file ───────────────────────────────────────────────────────────
def parse_notes(path):
    """Return list of dicts: {title, visual_lines, speaker_notes, raw_section}"""
    with open(path, encoding="utf-8") as f:
        content = f.read()

    # Split on ## Slide N headings
    sections = re.split(r'\n(?=## Slide \d+)', content.strip())
    slides = []
    for sec in sections:
        if not sec.startswith("## Slide"):
            continue
        lines = sec.split("\n")
        # Title from heading: "## Slide 1 — Title" → "Title"
        heading = lines[0]
        em_dash_parts = heading.split(" — ", 1)
        if len(em_dash_parts) == 2:
            slide_title = em_dash_parts[1].strip()
        else:
            slide_title = heading.replace("##", "").strip()

        # Extract Visual section
        visual_lines = []
        in_visual = False
        for line in lines[1:]:
            if line.strip() == "**Visual**":
                in_visual = True
                continue
            if line.startswith("**") and in_visual:
                in_visual = False
            if in_visual and line.strip().startswith("- "):
                visual_lines.append(line.strip()[2:])

        # Extract Speaker notes (text inside quotes after **Speaker notes**)
        speaker_notes = ""
        in_notes = False
        notes_lines = []
        for line in lines[1:]:
            if line.strip() == "**Speaker notes**":
                in_notes = True
                continue
            if in_notes:
                notes_lines.append(line)
        raw_notes = "\n".join(notes_lines).strip()
        # Strip surrounding quotes if present
        if raw_notes.startswith('"') and raw_notes.endswith('"'):
            speaker_notes = raw_notes[1:-1]
        else:
            speaker_notes = raw_notes

        slides.append({
            "title": slide_title,
            "visual_lines": visual_lines,
            "speaker_notes": speaker_notes,
        })
    return slides

# ── Helpers ────────────────────────────────────────────────────────────────────
def set_bg(slide, color):
    from pptx.oxml.ns import qn
    from lxml import etree
    bg = slide.background
    fill = bg.fill
    fill.solid()
    fill.fore_color.rgb = color

def add_textbox(slide, left, top, width, height):
    return slide.shapes.add_textbox(left, top, width, height)

def styled_run(para, text, bold=False, size_pt=18, color=None, font_name=None):
    run = para.add_run()
    run.text = text
    run.font.bold = bold
    run.font.size = Pt(size_pt)
    if color:
        run.font.color.rgb = color
    if font_name:
        run.font.name = font_name
    return run

def add_slide_title(slide, title_text):
    tb = add_textbox(slide, Inches(0.5), Inches(0.3), Inches(12.3), Inches(1.0))
    tf = tb.text_frame
    tf.word_wrap = True
    para = tf.paragraphs[0]
    para.alignment = PP_ALIGN.LEFT
    styled_run(para, title_text, bold=True, size_pt=36, color=TITLE_COLOR)

def add_body_bullets(slide, lines, top=Inches(1.5), left=Inches(0.7),
                     width=Inches(11.8), height=Inches(5.0)):
    if not lines:
        return
    tb = add_textbox(slide, left, top, width, height)
    tf = tb.text_frame
    tf.word_wrap = True
    for i, line in enumerate(lines):
        para = tf.add_paragraph() if i > 0 else tf.paragraphs[0]
        para.alignment = PP_ALIGN.LEFT
        # Detect inline code spans (`...`)
        parts = re.split(r'(`[^`]+`)', line)
        for part in parts:
            if part.startswith('`') and part.endswith('`'):
                styled_run(para, part[1:-1], size_pt=18, color=CODE_COLOR,
                           font_name="Courier New")
            else:
                styled_run(para, part, size_pt=18, color=BODY_COLOR)

def set_notes(slide, text):
    notes_slide = slide.notes_slide
    tf = notes_slide.notes_text_frame
    tf.text = text

# ── Special slide layouts ──────────────────────────────────────────────────────
def build_title_slide(prs, slide_data):
    slide = prs.slides.add_slide(prs.slide_layouts[6])  # blank
    set_bg(slide, BG_COLOR)
    # Big title
    tb = add_textbox(slide, Inches(1.0), Inches(1.8), Inches(11.3), Inches(1.6))
    tf = tb.text_frame
    tf.word_wrap = True
    p = tf.paragraphs[0]
    p.alignment = PP_ALIGN.CENTER
    styled_run(p, "Theremin Gloves", bold=True, size_pt=48, color=TITLE_COLOR)
    # Subtitle
    tb2 = add_textbox(slide, Inches(1.0), Inches(3.2), Inches(11.3), Inches(0.8))
    tf2 = tb2.text_frame
    p2 = tf2.paragraphs[0]
    p2.alignment = PP_ALIGN.CENTER
    styled_run(p2, "A Gesture-Controlled Wireless Instrument", size_pt=24, color=ACCENT_COLOR)
    # Team info
    tb3 = add_textbox(slide, Inches(1.0), Inches(4.2), Inches(11.3), Inches(1.8))
    tf3 = tb3.text_frame
    tf3.word_wrap = True
    for line in [
        "Team 5  ·  COEN 390 / ELEC 390  ·  Concordia University  ·  Winter 2026",
        "Niraj Patel  ·  Ayan Pirani  ·  Marie Ella Cambay  ·  Nirthika Ilaiyarajah  ·  Matei Moldovan",
    ]:
        p3 = tf3.add_paragraph() if tf3.paragraphs[0].text else tf3.paragraphs[0]
        p3.alignment = PP_ALIGN.CENTER
        styled_run(p3, line, size_pt=16, color=BODY_COLOR)
    set_notes(slide, slide_data["speaker_notes"])
    return slide

def build_demo_slide(prs, slide_data):
    slide = prs.slides.add_slide(prs.slide_layouts[6])
    set_bg(slide, BG_COLOR)
    tb = add_textbox(slide, Inches(1.5), Inches(2.5), Inches(10.3), Inches(2.0))
    tf = tb.text_frame
    p = tf.paragraphs[0]
    p.alignment = PP_ALIGN.CENTER
    styled_run(p, "Live Demo", bold=True, size_pt=54, color=TITLE_COLOR)
    tb2 = add_textbox(slide, Inches(1.5), Inches(4.5), Inches(10.3), Inches(0.8))
    tf2 = tb2.text_frame
    p2 = tf2.paragraphs[0]
    p2.alignment = PP_ALIGN.CENTER
    styled_run(p2, "Let's see it in action", size_pt=28, color=ACCENT_COLOR)
    set_notes(slide, slide_data["speaker_notes"])
    return slide

def build_qa_slide(prs, slide_data):
    slide = prs.slides.add_slide(prs.slide_layouts[6])
    set_bg(slide, BG_COLOR)
    tb = add_textbox(slide, Inches(1.5), Inches(2.5), Inches(10.3), Inches(1.6))
    tf = tb.text_frame
    p = tf.paragraphs[0]
    p.alignment = PP_ALIGN.CENTER
    styled_run(p, "Thank You — Q&A", bold=True, size_pt=48, color=TITLE_COLOR)
    tb2 = add_textbox(slide, Inches(1.5), Inches(4.3), Inches(10.3), Inches(1.8))
    tf2 = tb2.text_frame
    tf2.word_wrap = True
    for line in [
        "Niraj Patel  ·  Ayan Pirani  ·  Marie Ella Cambay",
        "Nirthika Ilaiyarajah  ·  Matei Moldovan",
        "COEN 390 / ELEC 390 — Concordia University — Winter 2026",
    ]:
        pp = tf2.add_paragraph() if tf2.paragraphs[0].text else tf2.paragraphs[0]
        pp.alignment = PP_ALIGN.CENTER
        styled_run(pp, line, size_pt=18, color=BODY_COLOR)
    set_notes(slide, slide_data["speaker_notes"])
    return slide

def build_standard_slide(prs, slide_data):
    slide = prs.slides.add_slide(prs.slide_layouts[6])
    set_bg(slide, BG_COLOR)
    add_slide_title(slide, slide_data["title"])
    # Accent underline bar
    from pptx.util import Emu
    shape = slide.shapes.add_shape(
        1,  # MSO_SHAPE_TYPE.RECTANGLE
        Inches(0.5), Inches(1.25), Inches(12.3), Emu(40000)
    )
    shape.fill.solid()
    shape.fill.fore_color.rgb = ACCENT_COLOR
    shape.line.fill.background()

    add_body_bullets(slide, slide_data["visual_lines"], top=Inches(1.55))
    set_notes(slide, slide_data["speaker_notes"])
    return slide

# ── Main ───────────────────────────────────────────────────────────────────────
def main():
    slides_data = parse_notes(NOTES_FILE)
    print(f"Parsed {len(slides_data)} slides from {NOTES_FILE}")

    prs = Presentation()
    prs.slide_width  = SLIDE_W
    prs.slide_height = SLIDE_H

    for i, sd in enumerate(slides_data):
        title_lower = sd["title"].lower()
        if i == 0:
            build_title_slide(prs, sd)
        elif "demo" in title_lower or "action" in title_lower:
            build_demo_slide(prs, sd)
        elif "q&a" in title_lower or "thank" in title_lower:
            build_qa_slide(prs, sd)
        else:
            build_standard_slide(prs, sd)
        print(f"  Slide {i+1}: {sd['title']}")

    os.makedirs(os.path.dirname(OUTPUT_FILE), exist_ok=True)
    prs.save(OUTPUT_FILE)
    print(f"\n✓ Saved → {OUTPUT_FILE}")
    print(f"  {len(slides_data)} slides, dark theme (#1a1a2e), speaker notes included")

if __name__ == "__main__":
    main()
