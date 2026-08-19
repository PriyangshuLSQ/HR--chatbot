package com.leadsquared.hr.knowledge.claude;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.leadsquared.hr.knowledge.config.KnowledgeProperties;
import com.leadsquared.hr.knowledge.parse.ImageTextExtractor;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Reads text out of document images using the Claude vision API.
 *
 * <p>Separate from {@link ClaudeClient} on purpose, and the split is not cosmetic. That
 * client answers questions: it streams, it is on the critical path of a chat turn, and
 * its latency budget is measured against an employee waiting. This one transcribes
 * images at upload time: it does not stream, nobody is watching a cursor, and it can
 * afford a slower, more accurate model. They also run different models, so sharing one
 * client would mean threading a per-call model override through everything.
 *
 * <p><b>Transcription, not description.</b> The prompt below is the load-bearing part of
 * this class. A vision model asked "what is in this image" willingly writes "a table
 * showing leave entitlements by grade" — fluent, plausible, and a fabrication as far as
 * the knowledge base is concerned, because an employee asking about their entitlement
 * would then be answered from a summary no one wrote. Everything indexed here has to be
 * text that is literally present in the image.
 */
@Component
public class ClaudeVisionClient implements ImageTextExtractor {

  private static final Logger log = LoggerFactory.getLogger(ClaudeVisionClient.class);

  /**
   * Per-image ceiling. Shorter than the answer client's, because a transcription that
   * has not finished in this long is not a page of text — it is a model narrating.
   */
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

  private static final int MAX_RETRIES = 2;

  /**
   * Our ceiling on one image, well under the API's own 10 MB.
   *
   * <p>Lower for two reasons. The payload is base64, which is a third larger than the bytes
   * counted here, and the API rejects the encoded size — so a 9 MB image fails a 10 MB limit.
   * And a rejection is expensive in the wrong way: the 400 arrives after the upload has been
   * held open, and {@code PdfParser} re-encodes scans losslessly, so a six-page scanned policy
   * produced six 13-16 MB PNGs and six failures, then reported "no text could be extracted"
   * about a document whose every page was legible.
   */
  private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;

  /**
   * Longest edge sent, in pixels.
   *
   * <p>Not a quality decision — the models downsample past this themselves (1568 on
   * claude-haiku-4-5, 2576 on opus and sonnet), so anything larger is bytes spent to be thrown
   * away at the far end. Set to the largest any current model uses, so switching {@code
   * knowledge.ocr.model} up to opus for dense scans loses nothing.
   */
  private static final int MAX_EDGE_PX = 2576;

  /** Below this the page is no longer worth transcribing, so shrinking further is pointless. */
  private static final int MIN_EDGE_PX = 800;

  /** Tried in order. 0.85 is visually lossless on text; 0.55 is the last resort before scaling. */
  private static final float[] JPEG_QUALITIES = {0.85f, 0.7f, 0.55f};

  /**
   * How many transcriptions may be in flight at once.
   *
   * <p>Every image used to be submitted immediately, which was harmless while the per-document
   * cap was 12 and stops being harmless as it rises: sixty simultaneous requests draw rate-limit
   * responses, and a rate-limited page is retried twice and then reported as "could not be read".
   * That is a worse failure than the cap it replaced — a skipped page is deterministic and
   * flagged, whereas this varies run to run and looks like a bad scan.
   *
   * <p>Eight is chosen to keep the wall clock roughly proportional rather than minimal: a
   * sixty-page policy is around eight waves of a few seconds, so under a minute, and a large
   * document degrades in speed rather than in completeness. Not configurable on purpose — it is
   * a property of the API's limits, not of this deployment.
   */
  private static final int OCR_CONCURRENCY = 8;

  /**
   * The sentinel for "no text here".
   *
   * <p>Asking for an empty response does not work reliably — models fill silence with
   * "I don't see any text in this image", which would then be indexed as a passage and
   * could be retrieved as an answer. An explicit token is unambiguous to match on and
   * cheap to produce.
   */
  private static final String NO_TEXT = "NO_TEXT";

  private static final String SYSTEM_PROMPT =
      """
      You transcribe text from images taken out of HR documents.

      Reproduce the text that is visibly present, exactly as it appears. Preserve \
      reading order, and keep the structure of tables as Markdown tables so rows and \
      columns stay associated with each other.

      Rules, in order of importance:
      1. Never invent, complete, correct or infer text. If a word is cut off or \
      illegible, transcribe what is legible and nothing more.
      2. Never describe the image. Do not write "a table showing...", "a diagram \
      of...", "this image contains...". No preamble, no commentary, no summary.
      3. If the image contains no legible text at all — a logo, a photograph, a \
      decorative rule, a signature — reply with exactly NO_TEXT and nothing else.

      Output only the transcribed text.""";

  private final AnthropicClient client;
  private final String model;
  private final long maxAnswerTokens;
  private final int maxImages;
  private final int minImageBytes;
  private final boolean enabled;

  @Autowired
  public ClaudeVisionClient(KnowledgeProperties props) {
    this(props, null);
  }

  /** @param baseUrl overrides the API host. Package-private, for tests only. */
  ClaudeVisionClient(KnowledgeProperties props, String baseUrl) {
    KnowledgeProperties.Ocr cfg = props.ocr();
    KnowledgeProperties.Claude claude = props.claude();
    String apiKey = claude == null || claude.apiKey() == null ? "" : claude.apiKey().trim();

    this.model = cfg == null ? "claude-haiku-4-5" : cfg.model();
    this.maxAnswerTokens = cfg == null ? 1500 : cfg.maxAnswerTokens();
    // Kept in step with KnowledgeProperties.Ocr's own default. It only applies when no config is
    // bound at all, but a stale number here would silently cap a document at a limit no
    // configuration file mentions, which is a bad thing to debug.
    this.maxImages = cfg == null ? 100 : cfg.maxImagesPerDocument();
    this.minImageBytes = cfg == null ? 6144 : cfg.minImageBytes();
    boolean wanted = cfg == null || cfg.enabled();

    if (apiKey.isEmpty() || !wanted) {
      this.client = null;
      this.enabled = false;
      if (wanted) {
        log.info("Image OCR unavailable — no Anthropic API key. Images will be reported as skipped.");
      } else {
        log.info("Image OCR disabled by config. Images will be reported as skipped.");
      }
    } else {
      AnthropicOkHttpClient.Builder builder =
          AnthropicOkHttpClient.builder()
              .apiKey(apiKey)
              .timeout(REQUEST_TIMEOUT)
              .maxRetries(MAX_RETRIES);
      if (baseUrl != null) builder.baseUrl(baseUrl);
      this.client = builder.build();
      this.enabled = true;
      log.info(
          "Image OCR model={} max-tokens={} max-images/doc={} min-image-bytes={}",
          model,
          maxAnswerTokens,
          maxImages,
          minImageBytes);
    }
  }

  @Override
  public boolean isAvailable() {
    return enabled;
  }

  /**
   * Transcribes every image concurrently.
   *
   * <p>Concurrent because this sits inside an HTTP upload: a dozen images at a few
   * seconds each is a minute of a request hanging if run in sequence, which reads to HR
   * as a broken page rather than a thorough one. One virtual thread per image keeps the
   * wall clock at roughly the slowest single image regardless of count, and the caller
   * has already capped how many there can be.
   */
  @Override
  public Extraction extractText(List<Image> images) {
    if (images.isEmpty()) return Extraction.NONE;
    if (!enabled) return new Extraction(List.of(), 0, 0, images.size(), 0);

    // Worth reading, in document order. Everything filtered out here is counted as
    // skipped rather than dropped, so the note HR sees accounts for every image.
    List<Image> worthReading = new ArrayList<>();
    int skipped = 0;
    for (Image image : images) {
      boolean readable =
          image.bytes().length >= minImageBytes
              && mediaTypeFor(image.mediaType()) != null
              && worthReading.size() < maxImages;
      if (!readable) {
        skipped++;
        continue;
      }
      // Shrunk here rather than at the parser, so every source — PDF pages, .docx embeds —
      // is covered by one rule, and the rule lives with the client that knows the API's limit.
      Image fitted = fit(image);
      if (fitted == null) {
        skipped++;
        continue;
      }
      worthReading.add(fitted);
    }
    if (worthReading.isEmpty()) return new Extraction(List.of(), 0, 0, skipped, 0);

    long startedAt = System.nanoTime();
    List<String> transcriptions = new ArrayList<>();
    int failed = 0;
    int withoutText = 0;

    // Still one virtual thread per image — they are nearly free, and the point of the semaphore
    // is not to limit threads but to limit requests in flight at the API. Acquired inside the
    // task rather than before submitting, so the submitting thread never blocks and the results
    // are still collected in document order below.
    Semaphore inFlight = new Semaphore(OCR_CONCURRENCY);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<String>> futures = new ArrayList<>(worthReading.size());
      for (Image image : worthReading) {
        futures.add(
            executor.submit(
                () -> {
                  inFlight.acquire();
                  try {
                    return transcribe(image);
                  } finally {
                    inFlight.release();
                  }
                }));
      }

      for (int i = 0; i < futures.size(); i++) {
        String text;
        try {
          text = futures.get(i).get();
        } catch (ExecutionException e) {
          log.warn("OCR failed for {}: {}", worthReading.get(i).label(), e.getCause(), e.getCause());
          failed++;
          continue;
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          log.warn("OCR interrupted after {} of {} images", i, worthReading.size());
          // Everything not yet collected goes unread, not silently lost.
          failed += futures.size() - i;
          break;
        }
        if (text == null || text.isBlank()) {
          withoutText++;
        } else {
          transcriptions.add(text);
        }
      }
    }

    log.info(
        "OCR model={} {} of {} image(s) in {}ms: {} with text, {} with none, {} failed",
        model,
        worthReading.size(),
        images.size(),
        (System.nanoTime() - startedAt) / 1_000_000,
        transcriptions.size(),
        withoutText,
        failed);
    return new Extraction(transcriptions, transcriptions.size(), withoutText, skipped, failed);
  }

  /** @return the transcribed text, or null when the image holds none */
  private String transcribe(Image image) {
    Base64ImageSource.MediaType mediaType = mediaTypeFor(image.mediaType());
    if (mediaType == null) {
      log.debug("Skipping {} — unsupported media type {}", image.label(), image.mediaType());
      return null;
    }

    MessageCreateParams params =
        MessageCreateParams.builder()
            .model(model)
            .maxTokens(maxAnswerTokens)
            .systemOfTextBlockParams(List.of(TextBlockParam.builder().text(SYSTEM_PROMPT).build()))
            .addUserMessageOfBlockParams(
                List.of(
                    ContentBlockParam.ofImage(
                        ImageBlockParam.builder()
                            .source(
                                Base64ImageSource.builder()
                                    .mediaType(mediaType)
                                    .data(Base64.getEncoder().encodeToString(image.bytes()))
                                    .build())
                            .build()),
                    ContentBlockParam.ofText(
                        TextBlockParam.builder()
                            .text("Transcribe the text in this image.")
                            .build())))
            .build();

    Message message = client.messages().create(params);
    String text = visibleText(message);
    if (text == null) return null;

    String trimmed = text.trim();
    // Substring rather than equality: the sentinel occasionally arrives wrapped in
    // punctuation or a stray newline, and a document indexed with the literal string
    // "NO_TEXT" as a passage is worse than one indexed with nothing.
    if (trimmed.isEmpty() || trimmed.contains(NO_TEXT)) return null;
    return trimmed;
  }

  /**
   * Brings an image under {@link #MAX_IMAGE_BYTES}, or returns null if it cannot.
   *
   * <p>Untouched when it already fits, which is the common case — logos, diagrams and anything
   * from a text-based document. Only scans reach the re-encoding path.
   *
   * <p>Re-encoded as JPEG rather than PNG: the input is a photograph of a page, which is exactly
   * what lossless compression is worst at. The same scan that was a 16 MB PNG is a few hundred
   * kilobytes of JPEG at a quality no OCR model can tell apart from the original.
   *
   * <p>Quality is stepped down before dimensions are, because sharp edges at full size read
   * better than a clean render of smaller type — and text is what this is for.
   */
  private static Image fit(Image image) {
    // Bytes are not the only ceiling, and assuming they were left a page failing after the rest
    // were fixed: a well-compressed 8269x11694 scan came in under 4 MB and was rejected with
    // "at least one of the image dimensions exceed max allowed size: 8000 pixels". Either test
    // failing is enough to re-encode.
    int[] size = dimensions(image.bytes());
    boolean tooLarge = size != null && Math.max(size[0], size[1]) > MAX_EDGE_PX;
    if (image.bytes().length <= MAX_IMAGE_BYTES && !tooLarge) return image;

    BufferedImage source;
    try {
      source = ImageIO.read(new ByteArrayInputStream(image.bytes()));
    } catch (IOException | RuntimeException e) {
      source = null;
    }
    if (source == null) {
      // No decoder, so no way to shrink it. Skipping beats sending it to a certain 400 after
      // holding the upload open for the round trip.
      log.warn(
          "Cannot resize {} ({}) for OCR — no decoder for {}; skipping",
          image.label(),
          formatBytes(image.bytes().length),
          image.mediaType());
      return null;
    }

    int edge = Math.min(Math.max(source.getWidth(), source.getHeight()), MAX_EDGE_PX);
    while (true) {
      BufferedImage scaled = scaleToLongEdge(source, edge);
      for (float quality : JPEG_QUALITIES) {
        byte[] jpeg = toJpeg(scaled, quality);
        if (jpeg != null && jpeg.length <= MAX_IMAGE_BYTES) {
          log.info(
              "Resized {} for OCR: {} {}x{} -> {} {}x{} jpeg q{}",
              image.label(),
              formatBytes(image.bytes().length),
              source.getWidth(),
              source.getHeight(),
              formatBytes(jpeg.length),
              scaled.getWidth(),
              scaled.getHeight(),
              quality);
          return new Image(jpeg, "image/jpeg", image.label());
        }
      }
      if (edge <= MIN_EDGE_PX) {
        log.warn(
            "Could not bring {} under {} even at {}px — skipping",
            image.label(),
            formatBytes(MAX_IMAGE_BYTES),
            MIN_EDGE_PX);
        return null;
      }
      edge = Math.max(MIN_EDGE_PX, edge * 2 / 3);
    }
  }

  /**
   * Width and height from the file header, without decoding the pixels.
   *
   * <p>Header-only because this runs on every image including the logos and bullet glyphs that
   * make up most of a policy document, and decoding a few hundred of those to learn they were
   * already small is work with no result.
   *
   * @return {@code {width, height}}, or null if no reader recognises the format
   */
  private static int[] dimensions(byte[] bytes) {
    try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      if (stream == null) return null;
      Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReaders(stream);
      if (!readers.hasNext()) return null;

      javax.imageio.ImageReader reader = readers.next();
      try {
        reader.setInput(stream);
        return new int[] {reader.getWidth(0), reader.getHeight(0)};
      } finally {
        reader.dispose();
      }
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  private static BufferedImage scaleToLongEdge(BufferedImage source, int edge) {
    int longest = Math.max(source.getWidth(), source.getHeight());
    double factor = (double) edge / longest;
    int width = Math.max(1, (int) Math.round(source.getWidth() * factor));
    int height = Math.max(1, (int) Math.round(source.getHeight() * factor));

    // TYPE_INT_RGB, and the white fill under it, because JPEG has no alpha: a transparent
    // scan drawn straight onto the default buffer comes out black on black.
    BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = target.createGraphics();
    try {
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
      g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      g.setColor(Color.WHITE);
      g.fillRect(0, 0, width, height);
      g.drawImage(source, 0, 0, width, height, null);
    } finally {
      g.dispose();
    }
    return target;
  }

  /** @return the encoded bytes, or null when no JPEG writer is available */
  private static byte[] toJpeg(BufferedImage image, float quality) {
    Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
    if (!writers.hasNext()) return null;

    ImageWriter writer = writers.next();
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (ImageOutputStream out = ImageIO.createImageOutputStream(buffer)) {
      ImageWriteParam params = writer.getDefaultWriteParam();
      if (params.canWriteCompressed()) {
        params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        params.setCompressionQuality(quality);
      }
      writer.setOutput(out);
      writer.write(null, new IIOImage(image, null, null), params);
    } catch (IOException | RuntimeException e) {
      log.debug("JPEG encode failed at quality {}: {}", quality, e.toString());
      return null;
    } finally {
      writer.dispose();
    }
    return buffer.toByteArray();
  }

  private static String formatBytes(long bytes) {
    return bytes < 1024 * 1024
        ? Math.round(bytes / 1024.0) + " KB"
        : String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
  }

  /** Concatenates the text blocks, ignoring thinking and anything else non-visible. */
  private static String visibleText(Message message) {
    StringBuilder out = new StringBuilder();
    message
        .content()
        .forEach(block -> block.text().ifPresent(text -> out.append(text.text())));
    return out.isEmpty() ? null : out.toString();
  }

  /** Maps an IANA type onto what the vision API accepts, or null if it accepts none. */
  private static Base64ImageSource.MediaType mediaTypeFor(String mediaType) {
    if (mediaType == null) return null;
    return switch (mediaType.toLowerCase(java.util.Locale.ROOT)) {
      case "image/png" -> Base64ImageSource.MediaType.IMAGE_PNG;
      case "image/jpeg", "image/jpg" -> Base64ImageSource.MediaType.IMAGE_JPEG;
      case "image/gif" -> Base64ImageSource.MediaType.IMAGE_GIF;
      case "image/webp" -> Base64ImageSource.MediaType.IMAGE_WEBP;
      default -> null;
    };
  }
}
