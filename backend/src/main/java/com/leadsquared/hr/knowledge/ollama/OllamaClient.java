package com.leadsquared.hr.knowledge.ollama;

import com.leadsquared.hr.knowledge.config.HttpClients;
import com.leadsquared.hr.knowledge.config.KnowledgeProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Ollama client — the open-source AI backend.
 *
 * <p>Everything here is plain HTTP against a local Ollama daemon, which is why
 * there is no ML dependency in the POM: Ollama serves both the embedding model
 * (semantic search) and the chat model (grounded answer writing), and both are
 * open-source weights running on the user's own machine. No HR data leaves the
 * host.
 *
 * <p>Every call is written to fail soft. If Ollama is not installed or not
 * running, callers get {@code ok = false} and fall back to lexical retrieval plus
 * extractive answers — the product degrades, it does not break.
 */
@Component
public class OllamaClient {

  private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);

  /** Model names that only produce embeddings and must never be used for chat. */
  private static final Pattern EMBED_ONLY =
      Pattern.compile(
          "(^|[/:-])(embed|bge|gte|e5|minilm|nomic-embed|mxbai-embed|snowflake-arctic-embed)",
          Pattern.CASE_INSENSITIVE);

  /** A complete reasoning block, across newlines. */
  private static final Pattern THINK_BLOCK =
      Pattern.compile("<(think|thinking)>.*?</\\1>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

  /** An opening tag with no close yet — the stream is mid-reasoning. */
  private static final Pattern THINK_OPEN =
      Pattern.compile("<(think|thinking)>", Pattern.CASE_INSENSITIVE);

  private static final Duration OK_TTL = Duration.ofSeconds(30);
  private static final Duration FAIL_TTL = Duration.ofSeconds(8);

  private final String url;
  private final String wantedEmbedModel;
  private final String wantedChatModel;
  private final String keepAlive;
  private final int maxAnswerTokens;
  private final int numContext;

  /**
   * One client per call class. A status probe must give up in seconds; a local
   * model writing an answer on CPU may legitimately need a minute and a half.
   */
  private final RestClient probeClient;

  /**
   * For the NDJSON lines of a streaming reply.
   *
   * <p>Spring Boot's mapper, injected rather than constructed here, so it picks up
   * whatever the application configures. Leniency about unknown fields is no longer
   * assumed of it — {@link StreamChunk} declares that itself, because relying on a
   * global Jackson setting made every generated answer one config flag away from
   * silently becoming an extractive one.
   */
  private final ObjectMapper mapper;
  private final RestClient embedClient;
  private final RestClient queryEmbedClient;
  private final RestClient chatClient;

  private volatile CachedStatus cache;

  private record CachedStatus(OllamaStatus status, Instant expiresAt) {}

  public OllamaClient(KnowledgeProperties props, ObjectMapper mapper) {
    this.mapper = mapper;
    this.url = props.ollama().url().replaceAll("/+$", "");
    this.wantedEmbedModel = props.ollama().embedModel();
    this.wantedChatModel = props.ollama().chatModel();
    this.keepAlive = props.ollama().keepAlive();
    this.maxAnswerTokens = props.ollama().maxAnswerTokens();
    this.numContext = props.ollama().numContext();

    this.probeClient = client(Duration.ofSeconds(2), Duration.ofMillis(2500));
    this.embedClient = client(Duration.ofSeconds(3), Duration.ofSeconds(120));
    // Embedding the employee's question sits in the critical path of a chat
    // turn, so it gets a much shorter leash than bulk ingest embedding.
    this.queryEmbedClient = client(Duration.ofSeconds(3), Duration.ofSeconds(20));
    this.chatClient = client(Duration.ofSeconds(3), Duration.ofSeconds(90));
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

      return new OllamaStatus(
          true,
          url,
          models,
          resolveModel(models, wantedEmbedModel, OllamaClient::isEmbedOnly),
          resolveModel(models, wantedChatModel, name -> !isEmbedOnly(name)),
          hintFor(models));

    } catch (RuntimeException e) {
      return new OllamaStatus(
          false,
          url,
          List.of(),
          null,
          null,
          "Ollama is not reachable at "
              + url
              + ". Install it from ollama.com, then run `ollama pull "
              + wantedEmbedModel
              + "` and `ollama pull "
              + wantedChatModel
              + "`.");
    }
  }

  private static boolean isEmbedOnly(String name) {
    return EMBED_ONLY.matcher(name).find();
  }

  /**
   * Resolves a configured model name against what is actually installed.
   *
   * <p>Ollama reports tagged names ({@code llama3.2:latest}), so an exact match
   * on the untagged name the user configured usually fails — match on the prefix,
   * then fall back to any installed model of the right class so the feature works
   * without the user having to pull our exact defaults.
   */
  private static String resolveModel(List<String> models, String wanted, Predicate<String> rightClass) {
    String base = wanted.split(":")[0].toLowerCase();

    for (String model : models) {
      if (model.equalsIgnoreCase(wanted)) return model;
    }
    for (String model : models) {
      if (model.split(":")[0].toLowerCase().equals(base)) return model;
    }
    for (String model : models) {
      if (rightClass.test(model)) return model;
    }
    return null;
  }

  private String hintFor(List<String> models) {
    List<String> missing = new ArrayList<>();
    if (resolveModel(models, wantedEmbedModel, OllamaClient::isEmbedOnly) == null) {
      missing.add(wantedEmbedModel);
    }
    if (resolveModel(models, wantedChatModel, name -> !isEmbedOnly(name)) == null) {
      missing.add(wantedChatModel);
    }
    if (missing.isEmpty()) return null;

    return "Ollama is running, but these models are not pulled yet: "
        + String.join(" and ", missing.stream().map(m -> "`ollama pull " + m + "`").toList())
        + ".";
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
  // Chat
  // -------------------------------------------------------------------------

  /**
   * One-shot chat completion, or null if the model did not answer.
   *
   * <p>Temperature is pinned low: this model's job is to restate retrieved HR
   * policy faithfully, not to be creative about it.
   */
  public String chat(List<ChatMessage> messages, String model) {
    return chat(messages, model, maxAnswerTokens, "chat");
  }

  private String chat(List<ChatMessage> messages, String model, int maxTokens, String stage) {
    try {
      ChatResponse res =
          chatClient
              .post()
              .uri("/api/chat")
              .body(chatBody(messages, model, false, maxTokens))
              .retrieve()
              .body(ChatResponse.class);

      if (res == null || res.message() == null || res.message().content() == null) return null;
      logTiming(stage, model, res.load(), res.promptTokens(), res.promptNanos(),
          res.outputTokens(), res.outputNanos(), res.totalNanos());

      String content = visibleText(res.message().content()).trim();
      return content.isEmpty() ? null : content;
    } catch (RuntimeException e) {
      log.warn("Generation failed with model {}: {}", model, e.toString());
      return null;
    }
  }

  /**
   * The request body shared by the streaming and one-shot paths.
   *
   * <p>{@code keep_alive} is the one that matters for latency. Ollama's default
   * evicts the weights after five minutes idle, so without it the first question
   * after any lull pays a multi-gigabyte reload from disk before its first token —
   * invisible when testing back-to-back, and the dominant cost in real use.
   *
   * <p>{@code num_predict} is a ceiling, not a target. Generation is token-serial,
   * so every token the model writes past what the system prompt asked for ("2-5
   * short sentences") is wall-clock the employee spends waiting. Raise
   * {@code knowledge.ollama.max-answer-tokens} if answers start arriving clipped.
   *
   * <p>{@code num_ctx} is sent on <b>every</b> call, warm-up included, and that is
   * not incidental: Ollama keys a loaded model on its context size, so a warm-up
   * that asked for a different window would make the first real question reload the
   * weights it was supposed to have pre-loaded — a warm-up that warms nothing.
   */
  private Map<String, Object> chatBody(
      List<ChatMessage> messages, String model, boolean stream, int maxTokens) {

    Map<String, Object> options = new LinkedHashMap<>();
    options.put("temperature", 0.1);
    options.put("top_p", 0.9);
    options.put("num_predict", maxTokens);
    options.put("num_ctx", numContext);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", model);
    body.put("messages", messages);
    body.put("stream", stream);
    // Qwen3-family models are hybrid-reasoning and emit <think> blocks by
    // default. Ollama understands this flag (0.9+) and ignores it for models
    // that cannot reason, so it is safe to send unconditionally.
    body.put("think", false);
    body.put("keep_alive", keepAlive);
    body.put("options", options);
    return body;
  }

  // -------------------------------------------------------------------------
  // Warm-up
  // -------------------------------------------------------------------------

  /**
   * Puts both models in memory at startup.
   *
   * <p>{@code keep_alive} keeps weights resident <i>between</i> questions, but
   * nothing puts them there to begin with — so without this the first employee to
   * ask anything pays the load inside their own question. Paying it here costs
   * nobody's wait.
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
      log.info("Skipping model warm-up — {}", status.hint());
      return;
    }

    if (status.embedModel() != null) {
      long started = System.nanoTime();
      boolean ok = embed(List.of("warm up"), status.embedModel(), embedClient) != null;
      log.info("Warm-up: embedding model {} {} after {}ms",
          status.embedModel(), ok ? "resident" : "FAILED", millisSince(started));
    }

    if (status.chatModel() != null) {
      long started = System.nanoTime();
      // One token: what is being pre-paid is the weight load, not any output.
      boolean ok = chat(List.of(ChatMessage.user("hi")), status.chatModel(), 1, "warm-up") != null;
      log.info("Warm-up: chat model {} {} after {}ms",
          status.chatModel(), ok ? "resident" : "FAILED", millisSince(started));
    }
  }

  // -------------------------------------------------------------------------
  // Timing
  // -------------------------------------------------------------------------

  public static long millisSince(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }

  /**
   * Reports what the model actually spent, from Ollama's own counters.
   *
   * <p>Split three ways because each part has a different fix: a non-zero
   * <b>load</b> means the weights had been evicted and {@code keep-alive} needs
   * raising; slow <b>prompt</b> eval means too much context is being sent (the
   * system prompt plus retrieved extracts); slow <b>generate</b> means too many
   * output tokens, or a model too large for this machine.
   */
  private static void logTiming(
      String stage,
      String model,
      Long loadNanos,
      Integer promptTokens,
      Long promptNanos,
      Integer outputTokens,
      Long outputNanos,
      Long totalNanos) {

    if (!log.isInfoEnabled() || totalNanos == null) return;

    log.info(
        "{} model={} load={}ms prompt={}ms/{}tok generate={}ms/{}tok ({} tok/s) total={}ms",
        stage,
        model,
        millis(loadNanos),
        millis(promptNanos),
        count(promptTokens),
        millis(outputNanos),
        count(outputTokens),
        tokensPerSecond(outputTokens, outputNanos),
        millis(totalNanos));
  }

  private static long millis(Long nanos) {
    return nanos == null ? 0 : nanos / 1_000_000;
  }

  private static int count(Integer value) {
    return value == null ? 0 : value;
  }

  /**
   * Tokens per second, or {@code ?} when the sample is too small to mean anything.
   *
   * <p>The warm-up asks for a single token and Ollama reported an {@code
   * eval_duration} of one microsecond for it, which divides out to "1000000.0
   * tok/s" — a number that is not wrong so much as meaningless, in a log whose only
   * job is to be read and trusted.
   */
  private static String tokensPerSecond(Integer tokens, Long nanos) {
    if (tokens == null || nanos == null || tokens < 2 || nanos < 1_000_000) return "n/a";
    return String.format("%.1f", tokens * 1_000_000_000.0 / nanos);
  }

  // -------------------------------------------------------------------------
  // Wire shapes
  // -------------------------------------------------------------------------

  private record TagsResponse(List<Model> models) {
    private record Model(String name, String model) {}
  }

  private record EmbedBatchResponse(List<List<Float>> embeddings) {}

  private record EmbedSingleResponse(List<Float> embedding) {}

  /**
   * Receives generated fragments as they arrive.
   *
   * <p>Returns whether generation should continue. Answering {@code false} closes
   * the connection and stops the model — which is how a refusal recognised at
   * token twenty avoids paying for the remaining few hundred.
   */
  public interface TokenSink {
    boolean onToken(String piece);
  }

  /**
   * Streams a reply, handing each token to {@code onToken} as it arrives.
   *
   * <p>Ollama answers a streaming request with NDJSON — one JSON object per line,
   * each carrying the next fragment — so this reads the body as a stream rather
   * than deserialising a whole response.
   *
   * @return the text generated before the stream ended, or null if generation
   *     failed. A sink that stopped early still gets what arrived, so the
   *     non-streaming contract (refusal detection, citation stripping) holds
   *     unchanged — the caller re-checks the whole string either way.
   */
  public String chatStream(List<ChatMessage> messages, String model, TokenSink onToken) {
    StringBuilder whole = new StringBuilder();
    // How much visible text has already gone to the caller. Recomputing the
    // visible prefix each chunk is what makes a tag arriving in pieces safe.
    int[] emitted = {0};
    boolean[] stopped = {false};
    long started = System.nanoTime();

    try {
      chatClient
          .post()
          .uri("/api/chat")
          .body(chatBody(messages, model, true, maxAnswerTokens))
          .exchange(
              (request, response) -> {
                try (BufferedReader reader =
                    new BufferedReader(
                        new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                  String line;
                  while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    StreamChunk chunk = mapper.readValue(line, StreamChunk.class);
                    if (chunk.message() != null && chunk.message().content() != null) {
                      String piece = chunk.message().content();
                      if (!piece.isEmpty()) {
                        whole.append(piece);
                        String visible = visibleText(whole);
                        if (visible.length() > emitted[0]) {
                          boolean keepGoing = onToken.onToken(visible.substring(emitted[0]));
                          emitted[0] = visible.length();
                          if (!keepGoing) {
                            // Leaving the loop closes the body, which drops the
                            // connection and tells Ollama to abandon the rest.
                            stopped[0] = true;
                            break;
                          }
                        }
                      }
                    }
                    if (Boolean.TRUE.equals(chunk.done())) {
                      logTiming("generate", model, chunk.load(), chunk.promptTokens(),
                          chunk.promptNanos(), chunk.outputTokens(), chunk.outputNanos(),
                          chunk.totalNanos());
                      break;
                    }
                  }
                }
                return null;
              });
    } catch (RuntimeException | Error e) {
      log.warn("Streaming generation failed with model {}: {}", model, e.toString());
      // Partial output is still worth returning: the caller has already shown it.
      String partial = visibleText(whole).trim();
      return partial.isEmpty() ? null : partial;
    }

    if (stopped[0]) {
      // No counters to report: the done chunk that carries them never arrived,
      // which is the point — this is the saving, measured from our side.
      log.info("generate model={} stopped early by caller after {}ms/{}chars",
          model, millisSince(started), whole.length());
    }

    String content = visibleText(whole).trim();
    return content.isEmpty() ? null : content;
  }

  /**
   * Everything outside a reasoning block.
   *
   * <p>Belt and braces alongside {@code think: false}: a reasoning trace shown to
   * an employee as their HR answer would be bad on its own, and worse than that it
   * would bury the NOT_IN_DOCUMENTS marker the grounding contract depends on —
   * a refusal would read as an answer.
   *
   * <p>An unterminated opening tag truncates rather than passing the tail through,
   * because mid-stream that tag means everything after it is still reasoning.
   */
  static String visibleText(CharSequence raw) {
    String text = THINK_BLOCK.matcher(raw).replaceAll("");
    java.util.regex.Matcher open = THINK_OPEN.matcher(text);
    return open.find() ? text.substring(0, open.start()) : text;
  }

  /**
   * One NDJSON line of a streaming reply.
   *
   * <p>The counters are populated only on the final chunk ({@code done: true}) and
   * are nanoseconds. Named explicitly rather than relying on a snake_case naming
   * strategy, and boxed because a missing counter must read as absent rather than
   * as a real zero — "load=0ms" is a claim the model was already resident, which is
   * exactly what these logs exist to answer.
   *
   * <p>{@code ignoreUnknown} on the record itself, not left to the mapper: Ollama
   * sends {@code role} beside {@code content} on every chunk and adds fields
   * between versions. This used to work only because the injected mapper happens to
   * be lenient, which made a global Jackson setting silently load-bearing — flip
   * {@code spring.jackson.deserialization.fail-on-unknown-properties} and every
   * answer would quietly degrade to an extractive one.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record StreamChunk(
      Message message,
      Boolean done,
      @JsonProperty("load_duration") Long load,
      @JsonProperty("prompt_eval_count") Integer promptTokens,
      @JsonProperty("prompt_eval_duration") Long promptNanos,
      @JsonProperty("eval_count") Integer outputTokens,
      @JsonProperty("eval_duration") Long outputNanos,
      @JsonProperty("total_duration") Long totalNanos) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Message(String content) {}
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ChatResponse(
      Message message,
      @JsonProperty("load_duration") Long load,
      @JsonProperty("prompt_eval_count") Integer promptTokens,
      @JsonProperty("prompt_eval_duration") Long promptNanos,
      @JsonProperty("eval_count") Integer outputTokens,
      @JsonProperty("eval_duration") Long outputNanos,
      @JsonProperty("total_duration") Long totalNanos) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Message(String content) {}
  }
}
