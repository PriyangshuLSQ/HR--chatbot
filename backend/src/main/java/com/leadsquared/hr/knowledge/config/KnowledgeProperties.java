package com.leadsquared.hr.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Everything configurable, bound from {@code application.yml} / environment. */
@ConfigurationProperties(prefix = "knowledge")
public record KnowledgeProperties(
    Ollama ollama, Claude claude, Ocr ocr, Qdrant qdrant, Migration migration) {

  /**
   * The local Ollama daemon — <b>embeddings only</b>.
   *
   * <p>Answer writing moved to the Claude API; Ollama stayed because the Qdrant
   * collections are built from {@code nomic-embed-text} and Anthropic has no
   * embeddings endpoint. A query embedded by any other model lands in a different
   * vector space, so every stored vector would have to be rebuilt to remove this.
   * Keeping the existing index means keeping this daemon — but only the ~0.3 GB
   * embedding model, not the multi-gigabyte chat weights.
   *
   * @param url the local Ollama daemon
   * @param embedModel wanted embedding model; resolved leniently against what is
   *     actually pulled. Changing it invalidates every vector in Qdrant.
   * @param keepAlive how long Ollama holds the weights in memory after a call, in
   *     its own duration format ({@code 30m}, {@code -1} for forever)
   */
  public record Ollama(
      String url, String embedModel, @DefaultValue("30m") String keepAlive) {}

  /**
   * The Claude API — answer writing.
   *
   * @param apiKey Anthropic API key. Blank disables generation entirely and the
   *     service falls back to extractive answers, exactly as it did when Ollama
   *     was not running. It is read from the environment rather than committed:
   *     put it in {@code application-local.yml} or export {@code ANTHROPIC_API_KEY}.
   * @param model model id, e.g. {@code claude-opus-5}. Lower latency and cost are
   *     one line away — {@code claude-haiku-4-5} is the fastest current model and
   *     is a reasonable fit for this task, which is restating retrieved text
   *     rather than reasoning from scratch. Measure both against the grounding
   *     cases in {@code docs/AI-SETUP.md} before choosing.
   * @param maxAnswerTokens ceiling on a response, covering thinking <i>and</i> the
   *     visible answer. Much larger than the 250 the local model was given: with
   *     adaptive thinking on, most of this budget can be spent before a single
   *     visible character is produced, and a ceiling sized to the answer alone
   *     truncates it. Generation is billed on what is used, not on the ceiling.
   * @param effort how hard the model works: {@code low}, {@code medium},
   *     {@code high}, {@code xhigh}, {@code max}. {@code low} is the default here
   *     because the task is bounded — restate five retrieved passages in a few
   *     sentences, or decline — and effort is the main latency lever.
   * @param cacheSystemPrompt whether to mark the system prompt as cacheable. It is
   *     identical on every turn and comfortably over the 512-token minimum, so
   *     this turns a per-turn full-price prefix into a cache read after the first
   *     call within the (5 minute) window.
   */
  public record Claude(
      String apiKey,
      @DefaultValue("claude-opus-5") String model,
      @DefaultValue("2000") int maxAnswerTokens,
      @DefaultValue("low") String effort,
      @DefaultValue("true") boolean cacheSystemPrompt) {}

  /**
   * Reading text out of images inside uploads — scanned pages, screenshots of tables,
   * org charts, a policy pasted in as a picture.
   *
   * <p>Runs on the Claude vision API rather than a local OCR engine. Tesseract would be
   * free per image but would put back exactly the kind of local-model dependency this
   * service just removed — a native binary plus language data on every machine that
   * runs it — and it is tuned for clean scanned prose, where much of what actually
   * turns up in an HR document is a screenshot of a table or a diagram with labels.
   *
   * <p>The cost is bounded by <i>when</i> this runs: once per upload, never per
   * question. It is off the hot path entirely, which is why it can use a different (and
   * if need be more expensive) model than the one answering questions.
   *
   * @param enabled master switch. Off means images are counted and reported as skipped,
   *     which is the behaviour that existed before OCR.
   * @param model the vision model. Deliberately separate from {@link Claude#model()}:
   *     answering is a per-question cost and wants the cheapest adequate model, while
   *     OCR is a per-upload cost where accuracy matters more. Note {@code
   *     claude-haiku-4-5} downsamples to 1568px on the long edge, against 2576px on
   *     {@code claude-opus-5} / {@code claude-sonnet-5} — on a dense scan that is the
   *     difference between small print being legible and being guessed at.
   * @param maxImagesPerDocument hard cap per upload. A 200-slide deck exported to .docx
   *     would otherwise mean 200 vision calls in one HTTP request.
   * @param minImageBytes images smaller than this are skipped unread. Logos, bullet
   *     glyphs and signature squiggles are the overwhelming majority of images in a
   *     policy document and none of them carry text worth indexing; paying to discover
   *     that on every upload is waste.
   * @param maxAnswerTokens ceiling on one transcription. A dense page of A4 is roughly
   *     700-900 tokens of text, so this is sized for a full page plus headroom.
   */
  public record Ocr(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("claude-haiku-4-5") String model,
      @DefaultValue("12") int maxImagesPerDocument,
      @DefaultValue("6144") int minImageBytes,
      @DefaultValue("1500") int maxAnswerTokens) {}

  /**
   * @param vectorSize dimensionality of the embedding model. 768 for
   *     {@code nomic-embed-text}. Changing the embedding model to one with a
   *     different width means dropping and rebuilding the collection.
   */
  public record Qdrant(
      String url,
      String apiKey,
      String chunkCollection,
      String docCollection,
      int vectorSize) {}

  /** @param legacyStore path to the Node implementation's {@code knowledge.json} */
  public record Migration(String legacyStore, boolean enabled) {}
}
