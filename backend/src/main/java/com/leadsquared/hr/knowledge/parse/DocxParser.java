package com.leadsquared.hr.knowledge.parse;

import com.leadsquared.hr.knowledge.model.SourceKind;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Word .docx to Markdown.
 *
 * <p>A .docx is a ZIP archive whose {@code word/document.xml} holds the body.
 * The Node implementation had to hand-roll a ZIP central-directory reader;
 * {@link ZipInputStream} handles the same cases (including entries whose sizes
 * live in a trailing data descriptor) so that whole layer disappears here.
 *
 * <p>Extraction is paragraph-by-paragraph rather than a flat {@code <w:t>}
 * sweep, so heading styles survive. Those headings become the chunk boundaries
 * and the citation trail, which is most of what makes retrieval over a long
 * policy readable.
 */
final class DocxParser {

  private DocxParser() {}

  private static final String BODY_PART = "word/document.xml";

  private static final Pattern BODY =
      Pattern.compile("<w:body\\b[^>]*>(.*)</w:body>", Pattern.DOTALL);
  private static final Pattern PARAGRAPH =
      Pattern.compile("<w:p\\b[^>]*>.*?</w:p>|<w:p\\b[^>]*/>", Pattern.DOTALL);
  private static final Pattern HEADING_STYLE =
      Pattern.compile("<w:pStyle\\s+w:val=\"(?:Heading|heading)\\s*(\\d)\"");
  private static final Pattern NUMBERED = Pattern.compile("<w:numPr\\b");
  private static final Pattern DRAWING = Pattern.compile("<w:drawing[\\s>]");
  private static final Pattern RUN_TOKEN =
      Pattern.compile("<w:t(?:\\s[^>]*)?>(.*?)</w:t>|<w:tab\\s*/>|<w:br\\s*/>", Pattern.DOTALL);
  private static final Pattern TAG = Pattern.compile("<[^>]*>");
  private static final Pattern HEX_ENTITY = Pattern.compile("&#x([0-9a-fA-F]+);");
  private static final Pattern DEC_ENTITY = Pattern.compile("&#(\\d+);");

  /** "PK\x03\x04" — the local file header every real ZIP starts with. */
  static boolean hasZipMagic(byte[] bytes) {
    return bytes.length >= 4
        && bytes[0] == 'P'
        && bytes[1] == 'K'
        && bytes[2] == 3
        && bytes[3] == 4;
  }

  static ParsedFile parse(byte[] bytes) {
    String xml = readEntry(bytes);
    if (xml == null) {
      throw new UnsupportedFileException(
          "That .docx has no readable document body. It may be corrupt or password-protected.");
    }

    String text = toMarkdown(xml);
    if (text.isBlank()) {
      throw new UnsupportedFileException(
          "No text could be extracted from that .docx. If the content is images or scans, "
              + "the text needs to be typed or OCR-ed first.");
    }

    // Images and embedded objects are silently dropped; say so rather than let
    // HR assume a diagram-heavy policy was fully ingested.
    List<String> notes = new ArrayList<>();
    long images = DRAWING.matcher(xml).results().count();
    if (images > 0) {
      notes.add(images + " image" + (images > 1 ? "s" : "") + " skipped — only text is indexed.");
    }

    return new ParsedFile(SourceKind.DOCX, text, notes);
  }

  private static String readEntry(byte[] bytes) {
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (BODY_PART.equals(entry.getName())) {
          return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
        }
      }
      return null;
    } catch (IOException e) {
      return null;
    }
  }

  private static String toMarkdown(String xml) {
    // Only the body — headers, footers and footnotes live in separate parts, and
    // the <w:sectPr> at the end carries page setup we don't want.
    Matcher bodyMatch = BODY.matcher(xml);
    String body = bodyMatch.find() ? bodyMatch.group(1) : xml;

    List<String> lines = new ArrayList<>();
    Matcher paragraphs = PARAGRAPH.matcher(body);

    while (paragraphs.find()) {
      String paragraph = paragraphs.group();
      String text = extractRunText(paragraph);

      if (text.isBlank()) {
        // Preserve the blank line — it is the paragraph separator downstream.
        if (!lines.isEmpty() && !lines.get(lines.size() - 1).isEmpty()) lines.add("");
        continue;
      }

      Matcher heading = HEADING_STYLE.matcher(paragraph);
      if (heading.find()) {
        int level = Math.min(Integer.parseInt(heading.group(1)), 6);
        if (level < 1) level = 1;
        if (!lines.isEmpty() && !lines.get(lines.size() - 1).isEmpty()) lines.add("");
        lines.add("#".repeat(level) + " " + text.trim());
        lines.add("");
        continue;
      }

      // Numbered and bulleted paragraphs carry <w:numPr>; Word stores the marker
      // itself in a separate numbering part, so emit a generic bullet.
      if (NUMBERED.matcher(paragraph).find()) {
        lines.add("- " + text.trim());
        continue;
      }

      lines.add(text.trim());
    }

    return DocumentParser.normaliseWhitespace(String.join("\n", lines));
  }

  /** Pulls the visible text out of one paragraph, honouring tabs and line breaks. */
  private static String extractRunText(String paragraphXml) {
    StringBuilder out = new StringBuilder();
    Matcher matcher = RUN_TOKEN.matcher(paragraphXml);

    while (matcher.find()) {
      if (matcher.group(1) != null) {
        out.append(decodeEntities(stripTags(matcher.group(1))));
      } else if (matcher.group().startsWith("<w:tab")) {
        out.append(' ');
      } else {
        out.append('\n');
      }
    }
    return out.toString();
  }

  /**
   * Removes any residual markup from a captured run.
   *
   * <p>Well-formed Word output never nests an element inside {@code <w:t>}, so
   * this is belt-and-braces — but a document produced by a third-party exporter
   * can be odd, and the failure mode without it is a literal {@code <w:t>}
   * appearing in an answer shown to an employee. Runs before entity decoding, so
   * an escaped {@code &lt;b&gt;} in the source survives as visible text.
   */
  private static String stripTags(String s) {
    return s.indexOf('<') >= 0 ? TAG.matcher(s).replaceAll("") : s;
  }

  private static String decodeEntities(String s) {
    String t = replaceCodePoints(HEX_ENTITY, s, 16);
    t = replaceCodePoints(DEC_ENTITY, t, 10);
    t = t.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'");
    // Ampersand last, so "&amp;lt;" decodes to "&lt;" and not "<".
    return t.replace("&amp;", "&");
  }

  private static String replaceCodePoints(Pattern pattern, String input, int radix) {
    Matcher matcher = pattern.matcher(input);
    StringBuilder out = new StringBuilder();
    while (matcher.find()) {
      matcher.appendReplacement(out, Matcher.quoteReplacement(safeCodePoint(matcher.group(1), radix)));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  private static String safeCodePoint(String digits, int radix) {
    try {
      int code = Integer.parseInt(digits, radix);
      return Character.isValidCodePoint(code) ? new String(Character.toChars(code)) : "";
    } catch (RuntimeException e) {
      return "";
    }
  }
}
