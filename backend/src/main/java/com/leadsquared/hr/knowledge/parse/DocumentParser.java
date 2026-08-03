package com.leadsquared.hr.knowledge.parse;

import com.leadsquared.hr.knowledge.model.SourceKind;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * File parsers: .txt / .md / .csv / .docx to plain text, Markdown-flavoured.
 *
 * <p>Output is normalised to Markdown so one chunker handles every source: Word
 * heading styles become {@code ##}, CSV rows become definition lines.
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
      default -> {
        // fall through to the MIME check
      }
    }

    if (mimeType == null || mimeType.isBlank()) return null;
    if (mimeType.contains("wordprocessingml")) return SourceKind.DOCX;
    if (mimeType.equals("text/csv")) return SourceKind.CSV;
    if (mimeType.equals("text/markdown")) return SourceKind.MD;
    if (mimeType.startsWith("text/")) return SourceKind.TXT;
    return null;
  }

  public static ParsedFile parse(byte[] bytes, String filename, String mimeType) {
    SourceKind kind = kindFor(filename, mimeType);
    if (kind == null) {
      throw new UnsupportedFileException(
          filename + " is not a supported format. Upload .txt, .md, .csv or .docx.");
    }

    // A .doc renamed to .docx is the most common upload mistake, and it fails
    // deep inside the ZIP reader with an opaque error. Catch it up front.
    if (kind == SourceKind.DOCX && !DocxParser.hasZipMagic(bytes)) {
      throw new UnsupportedFileException(
          filename
              + " is not a real .docx file. Legacy .doc files are not supported — "
              + "open it in Word and use \"Save As → Word Document (.docx)\".");
    }

    return switch (kind) {
      case DOCX -> DocxParser.parse(bytes);
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
