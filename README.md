# PDFbolt

This is a deterministic PDF content-stream replacement prototype using Apache PDFBox.

It edits PDF text drawing operands such as `Tj` and `TJ`. It does not use the visual overlay/redaction trick.

## Requirements

- JDK 17+
- Maven

## Run Web App (Contact page + API)

The web app uses environment variables for SMTP (see `src/main/resources/application.properties`).

### Docker (recommended)

1. Create your env file:

```bash
cp .env.example .env
```

2. Edit `.env` and set:

- `SMTP_HOST`, `SMTP_PORT`
- `SMTP_USERNAME`, `SMTP_PASSWORD`
- `MAIL_FROM`

3. Start:

```bash
docker compose up --build
```

The app listens on `http://localhost:8080` by default.

## Run Replacement

```bash
mvn exec:java \
  -Dexec.mainClass=com.pdfreplace.TruePdfReplaceApp \
  -Dexec.args="input.pdf output.pdf 'Invoice Number' 'Bill Number'"
```

For the highest layout safety, use strict mode. In strict mode the search and replacement must have the same character length.

```bash
mvn exec:java \
  -Dexec.mainClass=com.pdfreplace.TruePdfReplaceApp \
  -Dexec.args="input.pdf output.pdf 'ABC123' 'XYZ789' --strict"
```

## Audit Text Before and After

```bash
mvn exec:java \
  -Dexec.mainClass=com.pdfreplace.PdfTextAudit \
  -Dexec.args="input.pdf"

mvn exec:java \
  -Dexec.mainClass=com.pdfreplace.PdfTextAudit \
  -Dexec.args="output.pdf"
```

## Automated Accuracy Check

```bash
mvn exec:java \
  -Dexec.mainClass=com.pdfreplace.AccuracyCheckApp \
  -Dexec.args="input.pdf output.pdf 'Invoice Number' 'Bill Number'"
```

The command exits with failure when:

- no match was replaced
- the old text is still extractable
- the replacement text is not extractable

Use the audit to verify:

- the old text is no longer extractable
- the replacement text is extractable
- the PDF still opens normally
- visual position and sizing remain acceptable

## What This Supports

- Real content-stream replacement, not overlay
- `Tj` text operators
- `TJ` text arrays
- matches split across multiple PDF text string operands
- font-specific decoding and encoding through PDFBox where the font supports it

## Known Limits

- Replacement text must be encodable by the original PDF font.
- Different-length replacements can change visual spacing.
- `--strict` is safest for IDs, dates, invoice numbers, codes, and same-length values.
- Complex ligatures, custom encodings, subset fonts without required glyphs, and Type3 fonts need later handling.
- This baseline does not yet recalculate `TJ` kerning numbers.

## Accuracy Test Matrix

Start with PDFs containing:

- simple same-length value changes, such as `12345` to `67890`
- same-width date changes, such as `2026-05-03` to `2026-06-10`
- multi-word label changes, such as `Invoice Number` to `Receipt Number`
- text inside tables
- text rendered with embedded fonts

The first production upgrade should add width measurement and `TJ` spacing correction.
