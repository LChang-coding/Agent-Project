import json
from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER
from reportlab.lib.pagesizes import letter
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import inch
from reportlab.platypus import PageBreak, Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle


ROOT = Path(__file__).resolve().parent
fixture = json.loads((ROOT / "fixture.json").read_text(encoding="utf-8"))
facts = fixture["facts"]
styles = getSampleStyleSheet()
styles.add(ParagraphStyle(name="FixtureTitle", parent=styles["Title"], alignment=TA_CENTER,
                          fontName="Helvetica-Bold", fontSize=18, leading=22, textColor=colors.HexColor("#17365D")))
styles.add(ParagraphStyle(name="FixtureH2", parent=styles["Heading2"], fontName="Helvetica-Bold",
                          fontSize=14, leading=18, textColor=colors.HexColor("#365F91"), spaceBefore=10, spaceAfter=6))
styles.add(ParagraphStyle(name="FixtureBody", parent=styles["BodyText"], fontName="Helvetica",
                          fontSize=10.5, leading=15, spaceAfter=8))


def footer(canvas, document):
    canvas.saveState()
    canvas.setFont("Helvetica", 8)
    canvas.drawCentredString(letter[0] / 2, 0.45 * inch,
                             f"RAG format fixture | Page {document.page}")
    canvas.restoreState()


story = [
    Paragraph(fixture["title"], styles["FixtureTitle"]),
    Spacer(1, 8),
    Paragraph("Fixture format: PDF. This label is not part of the answer key.", styles["FixtureBody"]),
    Paragraph("Identity and access", styles["FixtureH2"]),
    Paragraph(f"The facility is named <b>{fixture['facility']}</b>. Its active access phrase is <b>{facts['activeAccessPhrase']}</b>.", styles["FixtureBody"]),
    Paragraph(f"The phrase <b>{facts['retiredAccessPhrase']}</b> is retired and must not be used. It is included only to test whether retrieval preserves negation and status.", styles["FixtureBody"]),
    Paragraph("Sensor limits", styles["FixtureH2"]),
]
table = Table([
    ["Sensor", "Emergency threshold", "Calibration interval"],
    [facts["northSensor"], f"{facts['northThresholdCelsius']} degrees Celsius", f"{facts['northCalibrationDays']} days"],
    [facts["southSensor"], f"{facts['southThresholdCelsius']} degrees Celsius", f"{facts['southCalibrationDays']} days"],
], colWidths=[1.35 * inch, 2.25 * inch, 2.15 * inch], repeatRows=1)
table.setStyle(TableStyle([
    ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
    ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#DDEBF7")),
    ("FONTNAME", (0, 1), (-1, -1), "Helvetica"),
    ("FONTSIZE", (0, 0), (-1, -1), 9.5),
    ("GRID", (0, 0), (-1, -1), 0.5, colors.HexColor("#B8C4CE")),
    ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
    ("TOPPADDING", (0, 0), (-1, -1), 6),
    ("BOTTOMPADDING", (0, 0), (-1, -1), 6),
]))
story.extend([
    table,
    PageBreak(),
    Paragraph("Emergency procedure", styles["FixtureH2"]),
    Paragraph(f"When {facts['northSensor']} exceeds {facts['northThresholdCelsius']} degrees Celsius, {facts['shutdownApprover']} authorizes the emergency shutdown. The shutdown decision must be recorded within {facts['shutdownDeadlineMinutes']} minutes of the alarm.", styles["FixtureBody"]),
    Paragraph("The table values and this procedure are authoritative. A nearby training note that mentions a 15-minute drill does not replace the 11-minute production deadline.", styles["FixtureBody"]),
    Paragraph("Cross-page continuity", styles["FixtureH2"]),
    Paragraph("The backup calibration capsule is stored in locker", styles["FixtureBody"]),
    PageBreak(),
    Paragraph(f"{facts['backupCapsuleLocker']}. The locker identifier must remain attached to the phrase &quot;backup calibration capsule&quot; even when a page boundary separates nearby paragraphs.", styles["FixtureBody"]),
    Paragraph("Deliberate omissions", styles["FixtureH2"]),
    Paragraph("This manual does not state a launch mass, launch date, orbital altitude, or fuel capacity. A system using this document must not invent those values.", styles["FixtureBody"]),
])

SimpleDocTemplate(str(ROOT / "format-fidelity.pdf"), pagesize=letter,
                  rightMargin=0.75 * inch, leftMargin=0.75 * inch,
                  topMargin=0.7 * inch, bottomMargin=0.7 * inch,
                  title=fixture["title"], author="AI Agent Scaffold RAG Evaluation").build(
    story, onFirstPage=footer, onLaterPages=footer
)
