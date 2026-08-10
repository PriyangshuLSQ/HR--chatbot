package com.leadsquared.hr.knowledge.parse;

import com.leadsquared.hr.knowledge.parse.ImageTextExtractor.Extraction;
import java.util.List;

/**
 * Folds OCR output into a parsed document, and says what happened.
 *
 * <p>Shared by the .docx and PDF parsers so the two do not drift: the same heading, the
 * same ordering, and the same accounting in the notes regardless of which format the
 * images came out of.
 */
final class ImageText {

  private ImageText() {}

  /**
   * The heading the transcriptions live under.
   *
   * <p>A heading rather than a raw append, because the chunker splits on Markdown
   * headings and uses them as the citation trail. Without one, text from a screenshot
   * would be glued onto whichever paragraph happened to precede it and cited as part of
   * it — so an employee would be told a sentence came from "Leave Policy › Applying for
   * Leave" when it actually came from a picture somewhere on that page. This way the
   * trail ends in something honest.
   */
  private static final String HEADING = "## Text from images in this document";

  /** Appends the transcriptions to {@code body}, or returns it unchanged if there are none. */
  static String append(String body, Extraction extraction) {
    if (extraction.isEmpty()) return body;

    StringBuilder out = new StringBuilder(body.length() + 256);
    if (!body.isBlank()) out.append(body).append("\n\n");
    out.append(HEADING).append("\n\n");
    out.append(String.join("\n\n", extraction.texts()));
    return DocumentParser.normaliseWhitespace(out.toString());
  }

  /**
   * Describes the outcome for HR, appending to {@code notes}.
   *
   * <p>Silence is the one thing not on offer. A document whose images were half-read is
   * a corpus with holes in it, and an assistant answering confidently out of half a
   * policy is the failure this whole channel exists to prevent — so anything less than
   * "all of it was read" gets said out loud.
   *
   * @param totalImages every image found in the document, read or not
   */
  static void describe(
      List<String> notes, int totalImages, Extraction extraction, boolean ocrAvailable) {
    if (totalImages == 0) return;

    if (!ocrAvailable) {
      notes.add(
          plural(totalImages)
              + " skipped — reading text from images is not configured, so only the "
              + "document's own text is indexed.");
      return;
    }

    if (extraction.transcribed() > 0) {
      notes.add(
          "Text was read from "
              + extraction.transcribed()
              + " of "
              + plural(totalImages)
              + " and indexed.");
    }
    if (extraction.withoutText() > 0) {
      notes.add(
          plural(extraction.withoutText()) + " had no readable text — likely logos or photos.");
    }
    if (extraction.skipped() > 0) {
      notes.add(
          plural(extraction.skipped())
              + " skipped as too small or too numerous to read; raise "
              + "knowledge.ocr.max-images-per-document to include more.");
    }
    if (extraction.failed() > 0) {
      notes.add(
          plural(extraction.failed())
              + " could not be read and "
              + (extraction.failed() == 1 ? "its" : "their")
              + " text is NOT indexed — re-upload to retry.");
    }
  }

  private static String plural(int count) {
    return count + (count == 1 ? " image" : " images");
  }
}
