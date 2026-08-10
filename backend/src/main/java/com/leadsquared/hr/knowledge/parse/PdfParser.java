package com.leadsquared.hr.knowledge.parse;

import com.leadsquared.hr.knowledge.model.SourceKind;
import com.leadsquared.hr.knowledge.parse.ImageTextExtractor.Extraction;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PDF to Markdown-flavoured text, plus the text inside its images.
 *
 * <p>Unlike the other parsers here, this one delegates: a PDF's text is a sequence of
 * glyph-positioning operators with no concept of a word or a line, so extracting it means
 * reconstructing reading order from coordinates. {@link PDFTextStripper} does that.
 *
 * <p><b>Headings are lost, and that is a real cost.</b> The .docx parser turns Word
 * heading styles into {@code ##}, which become the chunk boundaries and the citation
 * trail — the reason a leave-policy answer can cite "Leave Policy › Applying for Leave".
 * PDF has no equivalent: visual headings are just larger text, and recovering them means
 * guessing from font sizes. So PDF chunks are split on blank lines and cite the document
 * rather than the section. A policy that exists as both formats should be uploaded as
 * .docx.
 */
final class PdfParser {

  private PdfParser() {}

  private static final Logger log = LoggerFactory.getLogger(PdfParser.class);

  /** "%PDF-" — the header every PDF starts with. */
  static boolean hasPdfMagic(byte[] bytes) {
    return bytes.length >= 5
        && bytes[0] == '%'
        && bytes[1] == 'P'
        && bytes[2] == 'D'
        && bytes[3] == 'F'
        && bytes[4] == '-';
  }

  /**
   * Below this many characters, a page is treated as having no text layer.
   *
   * <p>Not zero, because a scanned page is rarely perfectly empty: the scanner's software
   * often leaves a header, a page number, or a few characters of stray OCR in the text
   * layer. Testing {@code > 0} would classify such a page as text-bearing and skip the
   * image that holds everything anyone actually wants to read.
   */
  private static final int MIN_TEXT_LAYER_CHARS = 32;

  static ParsedFile parse(byte[] bytes, ImageTextExtractor ocr) {
    try (PDDocument document = Loader.loadPDF(bytes)) {
      if (document.isEncrypted()) {
        // Reachable when a PDF is encrypted with an empty user password: PDFBox opens it
        // but extraction may be restricted by its permissions.
        throw new UnsupportedFileException(
            "That PDF is password-protected. Remove the protection and upload it again.");
      }

      String text = extractText(document);
      List<ImageTextExtractor.Image> images = extractImages(document);
      int pages = document.getNumberOfPages();

      Extraction extraction = images.isEmpty() ? Extraction.NONE : ocr.extractText(images);
      String combined = ImageText.append(text, extraction);

      if (combined.isBlank()) {
        throw new UnsupportedFileException(scannedPdfMessage(pages, images.size(), ocr));
      }

      List<String> notes = new ArrayList<>();
      ImageText.describe(notes, images.size(), extraction, ocr.isAvailable());

      // A PDF with almost no text layer but plenty of images is a scan. Say so even when
      // OCR rescued it, because it explains why the result reads less cleanly than the
      // original — and because "the pages are pictures" is the single most useful thing
      // to know when an answer from this document later looks wrong.
      if (text.length() < MIN_TEXT_LAYER_CHARS * Math.max(1, pages) && !images.isEmpty()) {
        notes.add(
            "This PDF has little or no selectable text — its pages appear to be scans, so "
                + "its content came from reading the images.");
      }

      return new ParsedFile(SourceKind.PDF, combined, notes);

    } catch (InvalidPasswordException e) {
      throw new UnsupportedFileException(
          "That PDF is password-protected. Remove the protection and upload it again.");
    } catch (IOException e) {
      throw new UnsupportedFileException(
          "That PDF could not be read — it may be corrupt or only partly downloaded.");
    }
  }

  private static String extractText(PDDocument document) throws IOException {
    PDFTextStripper stripper = new PDFTextStripper();
    // Position order, not the order operators happen to appear in the content stream.
    // Without this, a two-column policy interleaves its columns line by line and the
    // result is unreadable — and worse, plausible enough to be indexed and retrieved.
    stripper.setSortByPosition(true);
    stripper.setParagraphStart("\n");
    return DocumentParser.normaliseWhitespace(stripper.getText(document));
  }

  /**
   * Pulls every embedded raster image out, page by page.
   *
   * <p>Re-encoded to PNG rather than passed through: a PDF stores images in whatever
   * filter the producer chose — DCTDecode, JPXDecode, CCITTFaxDecode, FlateDecode — and
   * only some of those are formats the vision API accepts. Asking PDFBox for the decoded
   * {@code BufferedImage} and encoding once, losslessly, avoids having to map filters
   * onto media types and getting it subtly wrong for one scanner's output.
   *
   * <p>Form XObjects are recursed into, because a scanned page is frequently wrapped in
   * one rather than referenced directly — miss that and a scan looks like a PDF with no
   * images at all. Depth is bounded: a malformed file can make these cyclic.
   */
  private static List<ImageTextExtractor.Image> extractImages(PDDocument document) {
    List<ImageTextExtractor.Image> images = new ArrayList<>();
    int pageNumber = 0;
    for (PDPage page : document.getPages()) {
      pageNumber++;
      collect(page.getResources(), "page " + pageNumber, images, 0);
    }
    return images;
  }

  private static void collect(
      PDResources resources, String label, List<ImageTextExtractor.Image> out, int depth) {
    if (resources == null || depth > 4) return;

    for (COSName name : resources.getXObjectNames()) {
      PDXObject xobject;
      try {
        xobject = resources.getXObject(name);
      } catch (IOException e) {
        log.debug("Unreadable XObject {} on {}", name.getName(), label);
        continue;
      }

      if (xobject instanceof PDImageXObject image) {
        byte[] png = toPng(image, label);
        if (png != null) {
          out.add(new ImageTextExtractor.Image(png, "image/png", label));
        }
      } else if (xobject instanceof PDFormXObject form) {
        collect(form.getResources(), label, out, depth + 1);
      }
    }
  }

  private static byte[] toPng(PDImageXObject image, String label) {
    try {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      if (!ImageIO.write(image.getImage(), "png", buffer)) {
        log.debug("No PNG writer accepted the image on {}", label);
        return null;
      }
      return buffer.toByteArray();
    } catch (IOException | RuntimeException e) {
      // RuntimeException too: PDFBox throws unchecked on some malformed colour spaces and
      // JPEG2000 streams, and one bad image must not fail the upload.
      log.debug("Could not decode an image on {}: {}", label, e.toString());
      return null;
    }
  }

  private static String scannedPdfMessage(int pages, int images, ImageTextExtractor ocr) {
    if (images == 0) {
      return "No text could be extracted from that PDF, and it contains no images to read "
          + "either. It may be empty or use a font with no recoverable character mapping.";
    }
    return ocr.isAvailable()
        ? "No text could be extracted from that PDF's "
            + pages
            + " page(s) or its "
            + images
            + " image(s). If it is a scan, check that the pages are legible."
        : "That PDF has no selectable text — its pages appear to be scans. Reading text "
            + "from images is not configured, so there is nothing to index: set an "
            + "Anthropic API key, or upload a text-based version.";
  }
}
