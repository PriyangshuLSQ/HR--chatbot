package com.leadsquared.hr.knowledge.ollama;

import com.leadsquared.hr.knowledge.config.HttpClients;
import com.leadsquared.hr.knowledge.config.KnowledgeProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Ollama client — <b>embeddings only</b>.
 *
 * <p>Answer writing used to happen here too and now runs on the Claude API; see
 * {@code ClaudeClient}. What stayed is the half that could not move: Anthropic has
 * no embeddings endpoint, and both Qdrant collections hold 768-dimensional
 * {@code nomic-embed-text} vectors. Embedding a query with any other model puts it
 * in a different vector space, so cosine similarity against the stored vectors
 * becomes noise — retrieval would not error, it would quietly return the wrong
 * passages, which for an HR answer is the worst available failure.
 *
 * <p>So this daemon remains a dependency, but a much smaller one: roughly 0.3 GB of
 * embedding weights instead of that plus 3.4 GB of chat weights, no context-window
 * or output-token tuning, and nothing on the machine competing for RAM with the
 * answer. Removing it entirely means choosing a hosted embedding provider and
 * re-embedding every chunk into a new collection — a corpus migration, not a config
 * change.
 *
 * <p>Every call is written to fail soft. If Ollama is not installed or not running,
 * callers get {@code ok = false} and fall back to lexical (BM25) retrieval — the
 * product degrades, it does not break.
 */
@Component
public class OllamaClient {

  private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);

  /** Model names that produce embeddings. Anything else is not a candidate. */
  private static final Pattern EMBED_ONLY =
      Pattern.compile(
          "(^|[/:-])(embed|bge|gte|e5|minilm|nomic-embed|mxbai-embed|snowflake-arctic-embed)",
          Pattern.CASE_INSENSITIVE);

  private static final Duration OK_TTL = Duration.ofSeconds(30);
  private static final Duration FAIL_TTL = Duration.ofSeconds(8);

  private final String url;
  private final String wantedEmbedModel;
  private final String keepAlive;

  /**
   * One client per call class. A status probe must give up in seconds; embedding a
   * whole uploaded document may legitimately need two minutes.
   */
  private final RestClient probeClient;

  private final RestClient embedClient;
  private final RestClient queryEmbedClient;

  private volatile CachedStatus cache;

  private record CachedStatus(OllamaStatus status, Instant expiresAt) {}

  public OllamaClient(KnowledgeProperties props) {
    this.url = props.ollama().url().replaceAll("/+$", "");
    this.wantedEmbedModel = props.ollama().embedModel();
    this.keepAlive = props.ollama().keepAlive();

    this.probeClient = client(Duration.ofSeconds(2), Duration.ofMillis(2500));
    this.embedClient = client(Duration.ofSeconds(3), Duration.ofSeconds(120));
    // Embedding the employee's question sits in the critical path of a chat
    // turn, so it gets a much shorter leash than bulk ingest embedding.
    this.queryEmbedClient = client(Duration.ofSeconds(3), Duration.ofSeconds(20));
  }

  private RestClient client(Duration connect, Duration read) {
    return RestClient.builder()
        .baseUrl(url)
        .requestFactory(HttpClients.factory(connect, read))
        .build();
  }

  // -------------------------------------------------------------------------
  // Status
  // -------------------------------------------------------------------------

  public OllamaStatus getStatus() {
    return getStatus(false);
  }

  /**
   * Cached because answering calls this on every employee message, and a probe
   * against a machine with no Ollama installed costs the full connection
   * timeout. Without the cache, every chat turn would stall for seconds on
   * exactly the setup where nothing is going to answer anyway.
   *
   * <p>The negative result is cached for less time so that installing Ollama
   * shows up quickly, and the admin panel passes {@code fresh} to bypass it.
   */
  public OllamaStatus getStatus(boolean fresh) {
    CachedStatus cached = cache;
    if (!fresh && cached != null && Instant.now().isBefore(cached.expiresAt())) {
      return cached.status();
    }

    OllamaStatus status = probe();
    cache = new CachedStatus(status, Instant.now().plus(status.ok() ? OK_TTL : FAIL_TTL));
    return status;
  }

  private OllamaStatus probe() {
    try {
      TagsResponse tags = probeClient.get().uri("/api/tags").retrieve().body(TagsResponse.class);

      List<String> models = new ArrayList<>();
      if (tags != null && tags.models() != null) {
        for (TagsResponse.Model model : tags.models()) {
          String name = model.name() != null ? model.name() : model.model();
          if (name != null && !name.isBlank()) models.add(name);
        }
      }

      String embedModel = resolveModel(models, wantedEmbedModel);
      return new OllamaStatus(true, url, models, embedModel, hintFor(embedModel));

    } catch (RuntimeException e) {
      return new OllamaStatus(
          false,
          url,
          List.of(),
          null,
          "Ollama is not reachable at "
              + url
              + ". Semantic search needs it for query embedding — install it from ollama.com, "
              + "then run `ollama pull "
              + wantedEmbedModel
              + "`. No chat model is needed; answers are written by the Claude API.");
    }
  }

  private static boolean isEmbedModel(String name) {
    return EMBED_ONLY.matcher(name).find();
  }

  /**
   * Resolves the configured model name against what is actually installed.
   *
   * <p>Ollama reports tagged names ({@code nomic-embed-text:latest}), so an exact
   * match on the untagged name the user configured usually fails — match on the
   * prefix first.
   *
   * <p>The last resort — any installed embedding model — is deliberately narrower
   * than it looks. It exists so a machine with {@code mxbai-embed-large} pulled
   * instead of our default still gets semantic search on a fresh index. It is
   * <i>not</i> safe against an index already built with a different model: widths
   * differ (Qdrant rejects the search) or, worse, match while the vector spaces do
   * not. If {@code embedModel} in the log is not what built the collection, reindex.
   */
  private static String resolveModel(List<String> models, String wanted) {
    String base = wanted.split(":")[0].toLowerCase();

    for (String model : models) {
      if (model.equalsIgnoreCase(wanted)) return model;
    }
    for (String model : models) {
      if (model.split(":")[0].toLowerCase().equals(base)) return model;
    }
    for (String model : models) {
      if (isEmbedModel(model)) return model;
    }
    return null;
  }

  private String hintFor(String resolved) {
    if (resolved != null) return null;
    return "Ollama is running, but the embedding model is not pulled yet: `ollama pull "
        + wantedEmbedModel
        + "`.";
  }

  // -------------------------------------------------------------------------
  // Embeddings
  // -------------------------------------------------------------------------

  /** Embeds one query. Shorter timeout — this runs while an employee waits. */
  public List<Float> embedQuery(String text, String model) {
    List<List<Float>> result = embed(List.of(text), model, queryEmbedClient);
    return result == null || result.isEmpty() ? null : result.get(0);
  }

  /**
   * Embeds a batch of texts. Returns null on any failure, so ingest can store the
   * chunks lexical-only and re-embed later rather than rejecting the upload.
   */
  public List<List<Float>> embedBatch(List<String> texts, String model) {
    return embed(texts, model, embedClient);
  }

  /**
   * Tries the modern batch endpoint first and falls back to the legacy per-item
   * one, because {@code /api/embed} only exists on Ollama 0.3.4+.
   */
  private List<List<Float>> embed(List<String> texts, String model, RestClient client) {
    if (texts.isEmpty()) return List.of();

    try {
      EmbedBatchResponse res =
          client
              .post()
              .uri("/api/embed")
              .body(Map.of("model", model, "input", texts, "keep_alive", keepAlive))
              .retrieve()
              .body(EmbedBatchResponse.class);

      if (res != null && res.embeddings() != null && res.embeddings().size() == texts.size()) {
        return res.embeddings();
      }
    } catch (RuntimeException e) {
      log.debug("Batch embed endpoint unavailable, falling back to /api/embeddings", e);
    }

    try {
      List<List<Float>> out = new ArrayList<>(texts.size());
      for (String text : texts) {
        EmbedSingleResponse res =
            client
                .post()
                .uri("/api/embeddings")
                .body(Map.of("model", model, "prompt", text, "keep_alive", keepAlive))
                .retrieve()
                .body(EmbedSingleResponse.class);

        if (res == null || res.embedding() == null || res.embedding().isEmpty()) return null;
        out.add(res.embedding());
      }
      return out;
    } catch (RuntimeException e) {
      log.warn("Embedding failed with model {}: {}", model, e.toString());
      return null;
    }
  }

  // -------------------------------------------------------------------------
  // Warm-up
  // -------------------------------------------------------------------------

  /**
   * Puts the embedding model in memory at startup.
   *
   * <p>{@code keep_alive} keeps weights resident <i>between</i> questions, but
   * nothing puts them there to begin with — so without this the first employee to
   * ask anything pays the load inside their own question. Paying it here costs
   * nobody's wait.
   *
   * <p>Cheaper than it used to be, now that only the embedding model is local: this
   * loads a few hundred megabytes rather than several gigabytes, so a cold start is
   * no longer the dominant term in the first question of the day.
   *
   * <p>On a virtual thread and failure-tolerant on purpose: Ollama may not be
   * running at all, and a service that degrades to BM25 must still boot.
   */
  @EventListener(ApplicationReadyEvent.class)
  void warmUpOnStartup() {
    Thread.ofVirtual().name("ollama-warm-up").start(this::warmUp);
  }

  void warmUp() {
    OllamaStatus status = getStatus(true);
    if (!status.ok()) {
      log.info("Skipping embedding warm-up — {}", status.hint());
      return;
    }
    if (status.embedModel() == null) {
      log.info("Skipping embedding warm-up — {}", status.hint());
      return;
    }

    long started = System.nanoTime();
    boolean ok = embed(List.of("warm up"), status.embedModel(), embedClient) != null;
    log.info(
        "Warm-up: embedding model {} {} after {}ms",
        status.embedModel(),
        ok ? "resident" : "FAILED",
        millisSince(started));
  }

  // -------------------------------------------------------------------------
  // Timing
  // -------------------------------------------------------------------------

  public static long millisSince(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }

  // -------------------------------------------------------------------------
  // Wire shapes
  // -------------------------------------------------------------------------

  private record TagsResponse(List<Model> models) {
    private record Model(String name, String model) {}
  }

  private record EmbedBatchResponse(List<List<Float>> embeddings) {}

  private record EmbedSingleResponse(List<Float> embedding) {}
}
