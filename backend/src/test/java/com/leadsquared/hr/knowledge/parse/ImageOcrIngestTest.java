package com.leadsquared.hr.knowledge.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;

/**
 * That text living inside an image in an upload ends up in the indexed text.
 *
 * <p>The point of the {@link ImageTextExtractor} seam is visible here: these run the real
 * .docx and PDF paths — real ZIP, real PDFBox, real image encoding — against a stub that
 * returns fixed strings. So the parsing is genuinely exercised with no network call and
 * no bill, and a regression in "did the image bytes actually reach OCR" fails loudly.
 */
class ImageOcrIngestTest {

  /** Records what it was handed and returns one transcription per image. */
  private static final class StubExtractor implements ImageTextExtractor {
    private final List<Image> seen = new ArrayList<>();
    private final String text;
    private final boolean available;

    StubExtractor(String text) {
      this(text, true);
    }

    StubExtractor(String text, boolean available) {
      this.text = text;
      this.available = available;
    }

    @Override
    public Extraction extractText(List<Image> images) {
      seen.addAll(images);
      if (!available || text == null) {
        return new Extraction(List.of(), 0, 0, images.size(), 0);
      }
      List<String> texts = images.stream().map(i -> text).toList();
      return new Extraction(texts, texts.size(), 0, 0, 0);
    }

    @Override
    public boolean isAvailable() {
      return available;
    }
  }

  // -------------------------------------------------------------------------
  // .docx
  // -------------------------------------------------------------------------

  @Test
  void textInsideADocxImageIsIndexedAlongsideTheBody() {
    byte[] docx =
        docx(
            paragraph("Leave is approved by your reporting manager."),
            List.of(pngEntry("word/media/image1.png")));

    StubExtractor ocr = new StubExtractor("Casual leave: 12 days per year.");
    ParsedFile parsed = DocumentParser.parse(docx, "policy.docx", null, ocr);

    // The image bytes actually reached OCR, rather than only being counted.
    assertThat(ocr.seen).hasSize(1);
    assertThat(ocr.seen.get(0).mediaType()).isEqualTo("image/png");
    assertThat(ocr.seen.get(0).bytes()).isNotEmpty();

    assertThat(parsed.text()).contains("Leave is approved by your reporting manager.");
    assertThat(parsed.text()).contains("Casual leave: 12 days per year.");
    assertThat(parsed.notes()).anyMatch(note -> note.contains("Text was read from 1"));
  }

  /**
   * The case this feature exists for: a policy that is a picture of a policy. Before OCR
   * this threw and HR was told to type it in by hand.
   */
  @Test
  void aDocxWhoseOnlyContentIsAnImageStillIngests() {
    byte[] docx = docx(drawingOnlyParagraph(), List.of(pngEntry("word/media/image1.png")));

    ParsedFile parsed =
        DocumentParser.parse(docx, "scan.docx", null, new StubExtractor("Notice period: 60 days."));

    assertThat(parsed.text()).contains("Notice period: 60 days.");
  }

  /** With OCR unavailable the old behaviour holds, and the message says why. */
  @Test
  void anImageOnlyDocxFailsClearlyWhenOcrIsUnavailable() {
    byte[] docx = docx(drawingOnlyParagraph(), List.of(pngEntry("word/media/image1.png")));

    assertThatThrownBy(
            () -> DocumentParser.parse(docx, "scan.docx", null, ImageTextExtractor.DISABLED))
        .isInstanceOf(UnsupportedFileException.class)
        .hasMessageContaining("reading text from images is not configured");
  }

  @Test
  void unsupportedMediaFormatsAreNotSentToOcr() {
    // .emf is what Word writes for a pasted Office drawing, and the vision API takes none
    // of it. It must be skipped rather than attempted and reported as a failure.
    byte[] docx =
        docx(
            paragraph("Body text."),
            List.of(new Entry("word/media/image1.emf", new byte[9000])));

    StubExtractor ocr = new StubExtractor("should never be produced");
    ParsedFile parsed = DocumentParser.parse(docx, "policy.docx", null, ocr);

    assertThat(ocr.seen).isEmpty();
    assertThat(parsed.text()).contains("Body text.");
  }

  @Test
  void imagesAreHandedOverInFilenameOrder() {
    // Word numbers media parts in insertion order, which is the only reading-order signal
    // available. Written to the archive backwards to prove the sort is real.
    byte[] docx =
        docx(
            paragraph("Body."),
            List.of(pngEntry("word/media/image2.png"), pngEntry("word/media/image1.png")));

    StubExtractor ocr = new StubExtractor("x");
    DocumentParser.parse(docx, "policy.docx", null, ocr);

    assertThat(ocr.seen.stream().map(ImageTextExtractor.Image::label))
        .containsExactly("word/media/image1.png", "word/media/image2.png");
  }

  // -------------------------------------------------------------------------
  // PDF
  // -------------------------------------------------------------------------

  @Test
  void pdfTextLayerIsExtracted() throws IOException {
    byte[] pdf = pdf("Notice period is 60 days for confirmed employees.", false);

    ParsedFile parsed =
        DocumentParser.parse(pdf, "policy.pdf", null, ImageTextExtractor.DISABLED);

    assertThat(parsed.kind().wire()).isEqualTo("pdf");
    assertThat(parsed.text()).contains("Notice period is 60 days");
  }

  @Test
  void textInsideAPdfImageIsIndexedAlongsideTheTextLayer() throws IOException {
    byte[] pdf = pdf("Leave policy, page 1.", true);

    StubExtractor ocr = new StubExtractor("Sick leave needs a certificate after 3 days.");
    ParsedFile parsed = DocumentParser.parse(pdf, "policy.pdf", null, ocr);

    assertThat(ocr.seen).hasSize(1);
    assertThat(ocr.seen.get(0).mediaType()).isEqualTo("image/png");
    assertThat(ocr.seen.get(0).label()).isEqualTo("page 1");

    assertThat(parsed.text()).contains("Leave policy, page 1.");
    assertThat(parsed.text()).contains("Sick leave needs a certificate after 3 days.");
  }

  /** A scan: an image-only page. Nothing to extract without OCR, everything with it. */
  @Test
  void aScannedPdfIngestsViaOcrAndIsFlaggedAsAScan() throws IOException {
    byte[] pdf = imageOnlyPdf();

    ParsedFile parsed =
        DocumentParser.parse(
            pdf, "scan.pdf", null, new StubExtractor("Maternity leave: 26 weeks."));

    assertThat(parsed.text()).contains("Maternity leave: 26 weeks.");
    assertThat(parsed.notes()).anyMatch(note -> note.contains("appear to be scans"));
  }

  @Test
  void aScannedPdfFailsClearlyWhenOcrIsUnavailable() throws IOException {
    byte[] pdf = imageOnlyPdf();

    assertThatThrownBy(
            () -> DocumentParser.parse(pdf, "scan.pdf", null, ImageTextExtractor.DISABLED))
        .isInstanceOf(UnsupportedFileException.class)
        .hasMessageContaining("no selectable text");
  }

  @Test
  void somethingRenamedToPdfIsRejectedByItsHeaderNotByPdfbox() {
    byte[] notAPdf = "This is a text file called policy.pdf".getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(
            () -> DocumentParser.parse(notAPdf, "policy.pdf", null, ImageTextExtractor.DISABLED))
        .isInstanceOf(UnsupportedFileException.class)
        .hasMessageContaining("not a real PDF");
  }

  @Test
  void pdfIsRecognisedByMimeTypeWhenTheExtensionIsMissing() {
    assertThat(DocumentParser.kindFor("scan", "application/pdf").wire()).isEqualTo("pdf");
  }

  // -------------------------------------------------------------------------
  // Fixtures
  // -------------------------------------------------------------------------

  private record Entry(String name, byte[] bytes) {}

  private static Entry pngEntry(String name) {
    return new Entry(name, png(120, 60));
  }

  /** A real PNG, big enough that a size filter would keep it. */
  private static byte[] png(int width, int height) {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    g.setColor(Color.WHITE);
    g.fillRect(0, 0, width, height);
    g.setColor(Color.BLACK);
    g.drawString("text in an image", 6, height / 2);
    g.dispose();
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ImageIO.write(image, "png", out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  private static String paragraph(String text) {
    return "<w:p><w:r><w:t>" + text + "</w:t></w:r></w:p>";
  }

  /** A paragraph holding only a drawing — what an image-only Word document looks like. */
  private static String drawingOnlyParagraph() {
    return "<w:p><w:r><w:drawing><wp:inline/></w:drawing></w:r></w:p>";
  }

  private static byte[] docx(String bodyXml, List<Entry> media) {
    String xml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
            + "<w:body>"
            + bodyXml
            + "</w:body></w:document>";
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (ZipOutputStream zip = new ZipOutputStream(out)) {
        zip.putNextEntry(new ZipEntry("word/document.xml"));
        zip.write(xml.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        for (Entry entry : media) {
          zip.putNextEntry(new ZipEntry(entry.name()));
          zip.write(entry.bytes());
          zip.closeEntry();
        }
      }
      return out.toByteArray();
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  private static byte[] pdf(String text, boolean withImage) throws IOException {
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage();
      document.addPage(page);
      try (PDPageContentStream content = new PDPageContentStream(document, page)) {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        content.newLineAtOffset(72, 700);
        content.showText(text);
        content.endText();
        if (withImage) {
          content.drawImage(
              LosslessFactory.createFromImage(
                  document, ImageIO.read(new java.io.ByteArrayInputStream(png(200, 80)))),
              72,
              500);
        }
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.save(out);
      return out.toByteArray();
    }
  }

  /** A page that is nothing but an image — a scan. */
  private static byte[] imageOnlyPdf() throws IOException {
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage();
      document.addPage(page);
      try (PDPageContentStream content = new PDPageContentStream(document, page)) {
        content.drawImage(
            LosslessFactory.createFromImage(
                document, ImageIO.read(new java.io.ByteArrayInputStream(png(400, 550)))),
            40,
            40);
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.save(out);
      return out.toByteArray();
    }
  }
}
