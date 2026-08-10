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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
    this.maxImages = cfg == null ? 12 : cfg.maxImagesPerDocument();
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
      if (readable) {
        worthReading.add(image);
      } else {
        skipped++;
      }
    }
    if (worthReading.isEmpty()) return new Extraction(List.of(), 0, 0, skipped, 0);

    long startedAt = System.nanoTime();
    List<String> transcriptions = new ArrayList<>();
    int failed = 0;
    int withoutText = 0;

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<String>> futures = new ArrayList<>(worthReading.size());
      for (Image image : worthReading) {
        futures.add(executor.submit(() -> transcribe(image)));
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
