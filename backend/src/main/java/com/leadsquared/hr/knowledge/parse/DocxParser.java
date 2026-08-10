package com.leadsquared.hr.knowledge.parse;

import com.leadsquared.hr.knowledge.model.SourceKind;
import com.leadsquared.hr.knowledge.parse.ImageTextExtractor.Extraction;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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

  /** Where Word keeps embedded images, whatever their original format. */
  private static final String MEDIA_PREFIX = "word/media/";

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

  static ParsedFile parse(byte[] bytes, ImageTextExtractor ocr) {
    Contents contents = read(bytes);
    if (contents.xml() == null) {
      throw new UnsupportedFileException(
          "That .docx has no readable document body. It may be corrupt or password-protected.");
    }

    String text = toMarkdown(contents.xml());

    // How many images the body actually references. Counted from <w:drawing> rather than
    // from word/media, because media also holds parts nothing in the body points at — a
    // header logo, or an image left behind by an earlier edit.
    int drawings = (int) DRAWING.matcher(contents.xml()).results().count();
    int totalImages = Math.max(drawings, contents.images().size());

    Extraction extraction =
        contents.images().isEmpty() ? Extraction.NONE : ocr.extractText(contents.images());

    String combined = ImageText.append(text, extraction);

    // A .docx whose content is entirely a scan used to be a hard failure. It is now only
    // a failure if OCR could not rescue it either, so the message distinguishes "images
    // cannot be read here" from "they were read and held nothing".
    if (combined.isBlank()) {
      throw new UnsupportedFileException(
          ocr.isAvailable()
              ? "No text could be extracted from that .docx, including from its "
                  + totalImages
                  + " image(s). If it is a scan, check that the pages are legible."
              : "No text could be extracted from that .docx. Its content appears to be "
                  + "images or scans, and reading text from images is not configured — set "
                  + "an Anthropic API key, or type the text in by hand.");
    }

    List<String> notes = new ArrayList<>();
    ImageText.describe(notes, totalImages, extraction, ocr.isAvailable());

    return new ParsedFile(SourceKind.DOCX, combined, notes);
  }

  /** The body XML plus every embedded image, from a single pass over the archive. */
  private record Contents(String xml, List<ImageTextExtractor.Image> images) {}

  /**
   * Reads the body and the media parts in one pass.
   *
   * <p>One pass because {@link ZipInputStream} is forward-only — it cannot seek back to
   * an earlier entry, so reading each part separately would mean re-inflating everything
   * ahead of it.
   *
   * <p>Images are sorted by entry name at the end. Word numbers media parts in the order
   * they were added to the document ({@code image1.png}, {@code image2.png}), which is
   * usually reading order and is the only ordering signal available — the archive's own
   * entry order carries none.
   */
  private static Contents read(byte[] bytes) {
    String xml = null;
    List<ImageTextExtractor.Image> images = new ArrayList<>();

    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        String name = entry.getName();
        if (BODY_PART.equals(name)) {
          xml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
        } else if (name.startsWith(MEDIA_PREFIX) && !entry.isDirectory()) {
          String mediaType = mediaTypeFor(name);
          if (mediaType != null) {
            images.add(new ImageTextExtractor.Image(zip.readAllBytes(), mediaType, name));
          }
        }
      }
    } catch (IOException e) {
      // Truncated or corrupt archive: keep whatever was read before the failure, so a
      // document damaged only at the end still ingests.
      return new Contents(xml, sortedByName(images));
    }
    return new Contents(xml, sortedByName(images));
  }

  private static List<ImageTextExtractor.Image> sortedByName(
      List<ImageTextExtractor.Image> images) {
    return images.stream().sorted(Comparator.comparing(ImageTextExtractor.Image::label)).toList();
  }

  /**
   * Maps a media part's extension onto an IANA type, or null if it is not an image the
   * vision API accepts.
   *
   * <p>{@code .emf} and {@code .wmf} — what Word writes when a drawing or chart is
   * pasted from another Office app — and {@code .svg} land here regularly. Returning
   * null leaves them counted as skipped rather than attempted and failed, which is the
   * honest description: nothing tried to read them.
   */
  private static String mediaTypeFor(String name) {
    String lower = name.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".png")) return "image/png";
    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
    if (lower.endsWith(".gif")) return "image/gif";
    if (lower.endsWith(".webp")) return "image/webp";
    return null;
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
