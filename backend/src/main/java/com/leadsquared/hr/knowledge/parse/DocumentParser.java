package com.leadsquared.hr.knowledge.parse;

import com.leadsquared.hr.knowledge.model.SourceKind;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * File parsers: .txt / .md / .csv / .docx / .pdf to plain text, Markdown-flavoured.
 *
 * <p>Output is normalised to Markdown so one chunker handles every source: Word
 * heading styles become {@code ##}, CSV rows become definition lines.
 *
 * <p>For .docx and PDF, text found <i>inside</i> images is appended under its own
 * heading — see {@link ImageTextExtractor}. That covers the cases a text-only parser
 * silently loses everything on: a policy pasted in as a screenshot, a scanned and
 * re-saved circular, a table of entitlements exported as a picture.
 */
public final class DocumentParser {

  private DocumentParser() {}

  private static final Pattern CRLF = Pattern.compile("\\r\\n?");
  private static final Pattern TRAILING_SPACE = Pattern.compile("[ \\t]+$", Pattern.MULTILINE);
  private static final Pattern BLANK_RUN = Pattern.compile("\\n{3,}");

  /** Resolves the format from the extension, falling back to the browser's MIME type. */
  public static SourceKind kindFor(String filename, String mimeType) {
    String lower = filename == null ? "" : filename.toLowerCase();
    int dot = lower.lastIndexOf('.');
    String ext = dot >= 0 ? lower.substring(dot + 1) : "";

    switch (ext) {
      case "txt", "text" -> {
        return SourceKind.TXT;
      }
      case "md", "markdown" -> {
        return SourceKind.MD;
      }
      case "csv", "tsv" -> {
        return SourceKind.CSV;
      }
      case "docx" -> {
        return SourceKind.DOCX;
      }
      case "pdf" -> {
        return SourceKind.PDF;
      }
      default -> {
        // fall through to the MIME check
      }
    }

    if (mimeType == null || mimeType.isBlank()) return null;
    if (mimeType.contains("wordprocessingml")) return SourceKind.DOCX;
    if (mimeType.equals("application/pdf")) return SourceKind.PDF;
    if (mimeType.equals("text/csv")) return SourceKind.CSV;
    if (mimeType.equals("text/markdown")) return SourceKind.MD;
    if (mimeType.startsWith("text/")) return SourceKind.TXT;
    return null;
  }

  /**
   * Parses without reading any images — text-only, the behaviour before OCR existed.
   *
   * <p>Kept so the formats that cannot contain images ({@code .txt}, {@code .md},
   * {@code .csv}) have a call that says so at the call site, and so tests of the text
   * paths need no stub.
   */
  public static ParsedFile parse(byte[] bytes, String filename, String mimeType) {
    return parse(bytes, filename, mimeType, ImageTextExtractor.DISABLED);
  }

  /**
   * @param ocr reads text out of images embedded in .docx and PDF uploads. Given
   *     {@link ImageTextExtractor#DISABLED}, images are counted and reported as skipped
   *     rather than read — the same outcome as having no API key configured.
   */
  public static ParsedFile parse(
      byte[] bytes, String filename, String mimeType, ImageTextExtractor ocr) {
    SourceKind kind = kindFor(filename, mimeType);
    if (kind == null) {
      throw new UnsupportedFileException(
          filename + " is not a supported format. Upload .pdf, .docx, .txt, .md or .csv.");
    }

    // A .doc renamed to .docx is the most common upload mistake, and it fails
    // deep inside the ZIP reader with an opaque error. Catch it up front.
    if (kind == SourceKind.DOCX && !DocxParser.hasZipMagic(bytes)) {
      throw new UnsupportedFileException(
          filename
              + " is not a real .docx file. Legacy .doc files are not supported — "
              + "open it in Word and use \"Save As → Word Document (.docx)\".");
    }

    // Same failure shape one format over: something renamed to .pdf reaches PDFBox and
    // comes back as a parse error that says nothing about the actual mistake.
    if (kind == SourceKind.PDF && !PdfParser.hasPdfMagic(bytes)) {
      throw new UnsupportedFileException(
          filename + " is not a real PDF file — check it is not a renamed Word or image file.");
    }

    return switch (kind) {
      case DOCX -> DocxParser.parse(bytes, ocr);
      case PDF -> PdfParser.parse(bytes, ocr);
      case CSV -> CsvParser.parse(decodeText(bytes), filename.toLowerCase().endsWith(".tsv"));
      default -> new ParsedFile(kind, normaliseWhitespace(decodeText(bytes)), List.of());
    };
  }

  /** Strips a UTF-8 BOM, which otherwise becomes a stray glyph in the first heading. */
  static String decodeText(byte[] bytes) {
    String text = new String(bytes, StandardCharsets.UTF_8);
    return !text.isEmpty() && text.charAt(0) == '﻿' ? text.substring(1) : text;
  }

  static String normaliseWhitespace(String text) {
    String t = CRLF.matcher(text).replaceAll("\n");
    t = TRAILING_SPACE.matcher(t).replaceAll("");
    return BLANK_RUN.matcher(t).replaceAll("\n\n").trim();
  }
}
