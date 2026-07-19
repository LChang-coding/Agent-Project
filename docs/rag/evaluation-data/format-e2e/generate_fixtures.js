const fs = require("fs");
const path = require("path");
const {
  AlignmentType,
  BorderStyle,
  Document,
  Footer,
  HeadingLevel,
  PageBreak,
  PageNumber,
  Packer,
  Paragraph,
  ShadingType,
  Table,
  TableCell,
  TableRow,
  TextRun,
  WidthType,
} = require("docx");

const outputDir = __dirname;
const fixture = JSON.parse(fs.readFileSync(path.join(outputDir, "fixture.json"), "utf8"));
const facts = fixture.facts;
const border = { style: BorderStyle.SINGLE, size: 1, color: "B8C4CE" };
const borders = { top: border, bottom: border, left: border, right: border };
const cell = (text, width, header = false) => new TableCell({
  width: { size: width, type: WidthType.DXA },
  borders,
  shading: header ? { fill: "DDEBF7", type: ShadingType.CLEAR } : undefined,
  margins: { top: 90, bottom: 90, left: 120, right: 120 },
  children: [new Paragraph({ children: [new TextRun({ text, bold: header })] })],
});

const doc = new Document({
  creator: "AI Agent Scaffold RAG Evaluation",
  title: fixture.title,
  description: "Deterministic DOCX fixture for RAG parser and retrieval evaluation",
  styles: {
    default: { document: { run: { font: "Arial", size: 22 } } },
    paragraphStyles: [
      { id: "Heading1", name: "Heading 1", basedOn: "Normal", next: "Normal", quickFormat: true,
        run: { font: "Arial", size: 32, bold: true, color: "17365D" },
        paragraph: { spacing: { before: 240, after: 160 }, outlineLevel: 0 } },
      { id: "Heading2", name: "Heading 2", basedOn: "Normal", next: "Normal", quickFormat: true,
        run: { font: "Arial", size: 28, bold: true, color: "365F91" },
        paragraph: { spacing: { before: 200, after: 120 }, outlineLevel: 1 } },
    ],
  },
  sections: [{
    properties: {
      page: {
        size: { width: 12240, height: 15840 },
        margin: { top: 1080, right: 1080, bottom: 1080, left: 1080 },
      },
    },
    footers: {
      default: new Footer({ children: [new Paragraph({
        alignment: AlignmentType.CENTER,
        children: [new TextRun("RAG format fixture | Page "), new TextRun({ children: [PageNumber.CURRENT] })],
      })] }),
    },
    children: [
      new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun(fixture.title)] }),
      new Paragraph({ children: [new TextRun("Fixture format: DOCX. This label is not part of the answer key.")] }),
      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("Identity and access")] }),
      new Paragraph({ children: [new TextRun(`The facility is named ${fixture.facility}. Its active access phrase is ${facts.activeAccessPhrase}.`)] }),
      new Paragraph({ children: [new TextRun(`The phrase ${facts.retiredAccessPhrase} is retired and must not be used. It is included only to test whether retrieval preserves negation and status.`)] }),
      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("Sensor limits")] }),
      new Table({
        width: { size: 10080, type: WidthType.DXA },
        columnWidths: [3360, 3360, 3360],
        rows: [
          new TableRow({ children: [cell("Sensor", 3360, true), cell("Emergency threshold", 3360, true), cell("Calibration interval", 3360, true)] }),
          new TableRow({ children: [cell(facts.northSensor, 3360), cell(`${facts.northThresholdCelsius} degrees Celsius`, 3360), cell(`${facts.northCalibrationDays} days`, 3360)] }),
          new TableRow({ children: [cell(facts.southSensor, 3360), cell(`${facts.southThresholdCelsius} degrees Celsius`, 3360), cell(`${facts.southCalibrationDays} days`, 3360)] }),
        ],
      }),
      new Paragraph({ children: [new PageBreak()] }),
      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("Emergency procedure")] }),
      new Paragraph({ children: [new TextRun(`When ${facts.northSensor} exceeds ${facts.northThresholdCelsius} degrees Celsius, ${facts.shutdownApprover} authorizes the emergency shutdown. The shutdown decision must be recorded within ${facts.shutdownDeadlineMinutes} minutes of the alarm.`)] }),
      new Paragraph({ children: [new TextRun("The table values and this procedure are authoritative. A nearby training note that mentions a 15-minute drill does not replace the 11-minute production deadline.")] }),
      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("Cross-page continuity")] }),
      new Paragraph({ children: [new TextRun("The backup calibration capsule is stored in locker")] }),
      new Paragraph({ children: [new PageBreak()] }),
      new Paragraph({ children: [new TextRun(`${facts.backupCapsuleLocker}. The locker identifier must remain attached to the phrase \"backup calibration capsule\" even when a page boundary separates nearby paragraphs.`)] }),
      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("Deliberate omissions")] }),
      new Paragraph({ children: [new TextRun("This manual does not state a launch mass, launch date, orbital altitude, or fuel capacity. A system using this document must not invent those values.")] }),
    ],
  }],
});

Packer.toBuffer(doc).then((buffer) => {
  fs.writeFileSync(path.join(outputDir, "format-fidelity.docx"), buffer);
});
