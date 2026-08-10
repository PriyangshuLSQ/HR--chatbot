package com.leadsquared.hr.knowledge.claude;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.TextBlockParam;
import com.leadsquared.hr.knowledge.config.KnowledgeProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Claude API client — the answer writer.
 *
 * <p>Replaces the local chat model that used to live in {@code OllamaClient}. The
 * job is unchanged: take the grounding prompt plus the passages retrieval selected
 * and write a short answer from them, or decline. What changed is where the tokens
 * are produced, and therefore which failure modes exist — an evicted 3.4 GB model
 * and a saturated laptop are gone, a network round trip and an API key that can be
 * wrong or rate-limited are new.
 *
 * <p><b>This client does not embed.</b> Anthropic has no embeddings endpoint, and
 * the Qdrant collections hold 768-dimensional {@code nomic-embed-text} vectors — a
 * query embedded by anything else lands in a different vector space and retrieval
 * silently returns nonsense. Query and ingest embedding therefore stay on Ollama.
 * That is the price of keeping the existing index rather than rebuilding it.
 *
 * <p>Like the client it replaces, every call fails soft: a missing key, a refusal,
 * a timeout or a rate limit all leave the caller with {@code null} and the product
 * degraded to an extractive answer, never broken.
 */
@Component
public class ClaudeClient {

  private static final Logger log = LoggerFactory.getLogger(ClaudeClient.class);

  /**
   * Wall-clock ceiling for one answer.
   *
   * <p>Shorter than the 90s the local model was given. That leash was sized for CPU
   * generation on a shared laptop; against a hosted API anything approaching it is a
   * stall rather than slow progress, and an employee is better served by the source
   * passage than by a spinner.
   */
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);

  /**
   * Retries inside the SDK, on 429 and 5xx with exponential backoff.
   *
   * <p>Two, not the SDK's default of two-plus-timeouts, and worth naming because
   * retries multiply against {@link #REQUEST_TIMEOUT}: the worst case an employee can
   * wait is roughly the timeout times attempts. Both numbers are chosen together.
   */
  private static final int MAX_RETRIES = 2;

  private final AnthropicClient client;
  private final String model;
  private final long maxAnswerTokens;
  private final OutputConfig.Effort effort;
  private final boolean sendEffort;
  private final boolean cacheSystemPrompt;
  private final ClaudeStatus status;

  /**
   * The constructor Spring uses. Annotated because the test-only overload below makes
   * two candidates, and with neither marked the container looks for a no-arg one and
   * fails to instantiate the bean at startup.
   */
  @Autowired
  public ClaudeClient(KnowledgeProperties props) {
    this(props, null);
  }

  /**
   * @param baseUrl overrides the API host. Package-private and for tests only —
   *     they point it at a stub that speaks the same Server-Sent Events, which is
   *     what lets the early-stop and prompt-translation behaviour be asserted
   *     without a network call or a bill.
   */
  ClaudeClient(KnowledgeProperties props, String baseUrl) {
    KnowledgeProperties.Claude cfg = props.claude();
    String apiKey = cfg == null || cfg.apiKey() == null ? "" : cfg.apiKey().trim();

    this.model = cfg == null ? "claude-opus-5" : cfg.model();
    this.maxAnswerTokens = cfg == null ? 2000 : cfg.maxAnswerTokens();
    this.effort = parseEffort(cfg == null ? "low" : cfg.effort());
    this.sendEffort = supportsEffort(this.model);
    this.cacheSystemPrompt = cfg == null || cfg.cacheSystemPrompt();

    if (apiKey.isEmpty()) {
      this.client = null;
      this.status = ClaudeStatus.notConfigured();
      log.warn(
          "No Anthropic API key configured — answers will be extractive. "
              + "Set ANTHROPIC_API_KEY or knowledge.claude.api-key.");
    } else {
      AnthropicOkHttpClient.Builder builder =
          AnthropicOkHttpClient.builder()
              .apiKey(apiKey)
              .timeout(REQUEST_TIMEOUT)
              .maxRetries(MAX_RETRIES);
      if (baseUrl != null) builder.baseUrl(baseUrl);
      this.client = builder.build();
      String reportedEffort = sendEffort ? effort.toString().toLowerCase(Locale.ROOT) : null;
      this.status = new ClaudeStatus(true, model, reportedEffort, null);
      log.info(
          "Claude answer model={} effort={} max-tokens={} system-prompt-caching={}",
          model,
          reportedEffort == null ? "n/a (unsupported on this model)" : reportedEffort,
          maxAnswerTokens,
          cacheSystemPrompt);
    }
  }

  /**
   * Effort as an enum, defaulting rather than throwing.
   *
   * <p>A typo in a config string must not stop the service booting — an HR assistant
   * that will not start is worse than one running at the wrong effort, and the
   * logged warning says which happened.
   */
  /**
   * Whether {@code model} accepts the {@code effort} parameter.
   *
   * <p>It is not universal. Haiku 4.5 and Sonnet 4.5 predate {@code effort} and reject
   * it with a 400, and this client would otherwise send it on every request — so
   * following the cost advice in {@code application.yml} and switching the model to
   * {@code claude-haiku-4-5} would fail every generation. Because generation fails
   * soft, the symptom would not be an error anyone notices: it would be every answer
   * silently degrading to an extractive quote.
   */
  private static boolean supportsEffort(String model) {
    String value = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
    return !value.startsWith("claude-haiku-4-5") && !value.startsWith("claude-sonnet-4-5");
  }

  private static OutputConfig.Effort parseEffort(String raw) {
    String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    return switch (value) {
      case "low" -> OutputConfig.Effort.LOW;
      case "medium" -> OutputConfig.Effort.MEDIUM;
      case "high" -> OutputConfig.Effort.HIGH;
      case "xhigh" -> OutputConfig.Effort.XHIGH;
      case "max" -> OutputConfig.Effort.MAX;
      default -> {
        log.warn("Unknown knowledge.claude.effort '{}' — falling back to low.", raw);
        yield OutputConfig.Effort.LOW;
      }
    };
  }

  public boolean isConfigured() {
    return client != null;
  }

  public String model() {
    return model;
  }

  public ClaudeStatus getStatus() {
    return status;
  }

  // -------------------------------------------------------------------------
  // Generation
  // -------------------------------------------------------------------------

  /**
   * Receives generated fragments as they arrive.
   *
   * <p>Returns whether generation should continue. Answering {@code false} closes the
   * stream — which is how a refusal recognised in the first ninety characters avoids
   * paying for the several hundred tokens behind it. The saving is now money as well
   * as time.
   */
  public interface TokenSink {
    boolean onToken(String piece);
  }

  /**
   * One-shot completion, or null if no answer was produced.
   *
   * <p>Implemented over the streaming path with a sink that never stops, rather than
   * as a second request shape. Two request builders drifting apart is how a fix
   * applied to the streamed answer quietly fails to reach the non-streamed one —
   * and the two must stay identical, because the grounding contract is enforced on
   * the finished string in both.
   */
  public String chat(List<ChatMessage> messages) {
    return chatStream(messages, piece -> true);
  }

  /**
   * Streams an answer, handing each fragment to {@code onToken} as it arrives.
   *
   * @return the text generated before the stream ended, or null if generation failed
   *     or was never attempted. A sink that stopped early still gets what arrived, so
   *     the caller's refusal and citation handling works on the whole string either
   *     way — unchanged from the contract the local model had.
   */
  public String chatStream(List<ChatMessage> messages, TokenSink onToken) {
    if (client == null) return null;

    StringBuilder whole = new StringBuilder();
    int[] emitted = {0};
    boolean stoppedEarly = false;
    long started = System.nanoTime();
    long firstTokenNanos = 0;
    long inputTokens = 0;
    long outputTokens = 0;
    long cacheReadTokens = 0;
    long cacheWriteTokens = 0;
    String stopReason = null;

    try (StreamResponse<RawMessageStreamEvent> stream =
        client.messages().createStreaming(params(messages))) {

      Iterator<RawMessageStreamEvent> events = stream.stream().iterator();
      while (events.hasNext()) {
        RawMessageStreamEvent event = events.next();

        // The opening event carries the prompt-side counters, including how much
        // of the system prompt was served from cache rather than re-billed.
        if (event.messageStart().isPresent()) {
          var usage = event.messageStart().get().message().usage();
          inputTokens = usage.inputTokens();
          cacheReadTokens = usage.cacheReadInputTokens().orElse(0L);
          cacheWriteTokens = usage.cacheCreationInputTokens().orElse(0L);
        }

        // Only text deltas reach the employee. Thinking arrives as its own block
        // type and is never forwarded — which is why the <think>-tag stripping the
        // local model needed is gone rather than ported: there are no tags to strip,
        // and no way for a reasoning trace to bury the NOT_IN_DOCUMENTS marker.
        var delta = event.contentBlockDelta();
        if (delta.isPresent()) {
          var text = delta.get().delta().text();
          if (text.isPresent()) {
            String piece = text.get().text();
            if (!piece.isEmpty()) {
              whole.append(piece);
              if (whole.length() > emitted[0]) {
                if (firstTokenNanos == 0) firstTokenNanos = System.nanoTime();
                boolean keepGoing = onToken.onToken(whole.substring(emitted[0]));
                emitted[0] = whole.length();
                if (!keepGoing) {
                  stoppedEarly = true;
                  break;
                }
              }
            }
          }
        }

        if (event.messageDelta().isPresent()) {
          var messageDelta = event.messageDelta().get();
          outputTokens = messageDelta.usage().outputTokens();
          stopReason =
              messageDelta.delta().stopReason().map(Object::toString).orElse(null);
        }
      }
    } catch (AnthropicServiceException e) {
      log.warn(
          "Claude generation failed (model={} type={}): {}",
          model,
          e.errorType().map(Object::toString).orElse("unknown"),
          e.getMessage());
      String partial = whole.toString().trim();
      return partial.isEmpty() ? null : partial;
    } catch (RuntimeException | Error e) {
      log.warn("Claude generation failed with model {}: {}", model, e.toString());
      String partial = whole.toString().trim();
      return partial.isEmpty() ? null : partial;
    }

    logTiming(
        stoppedEarly,
        started,
        firstTokenNanos,
        inputTokens,
        outputTokens,
        cacheReadTokens,
        cacheWriteTokens,
        stopReason);

    // A safety classifier declined the request outright. Distinct from the model
    // deciding the extracts do not answer the question — that arrives as
    // NOT_IN_DOCUMENTS text and is the caller's business. This is not an answer at
    // all, so it must not be handed on as one.
    if ("refusal".equalsIgnoreCase(stopReason)) {
      log.warn("Claude refused the request outright (stop_reason=refusal)");
      return null;
    }

    String content = whole.toString().trim();
    return content.isEmpty() ? null : content;
  }

  /**
   * Translates the neutral prompt into an Anthropic request.
   *
   * <p>Two shape differences from the local model's flat message array are worth
   * naming. The system prompt is a separate parameter, not a first message — and it
   * carries a cache breakpoint, because it is byte-identical on every turn and sits
   * ahead of everything volatile, which is exactly the prefix worth caching. And
   * {@code maxTokens} bounds thinking plus visible text together, so it is set far
   * above the length of any answer we actually want.
   *
   * <p>Thinking is never configured here, only inherited from the model, and that one
   * omission means two different things. On Claude Opus 5 the default is adaptive, and
   * leaving it on is deliberate: disabling it is the tempting latency win and the wrong
   * one, because it risks internal reasoning tags leaking into the visible response —
   * for this product, a reasoning trace shown to an employee as their HR answer. On
   * Haiku 4.5 the default is no thinking at all, which is why that model is both the
   * cheap option and the fast one: there is no pre-answer thinking phase to wait
   * through, and no thinking tokens inside {@link #maxAnswerTokens} to pay for.
   *
   * <p>{@code effort} is the latency lever on the models that have one, and is omitted
   * on the models that reject it — see {@link #supportsEffort}.
   */
  private MessageCreateParams params(List<ChatMessage> messages) {
    MessageCreateParams.Builder builder =
        MessageCreateParams.builder().model(model).maxTokens(maxAnswerTokens);
    if (sendEffort) {
      builder.outputConfig(OutputConfig.builder().effort(effort).build());
    }

    List<String> systemParts = new ArrayList<>();
    for (ChatMessage message : messages) {
      if (message.isSystem()) {
        systemParts.add(message.content());
      } else if (message.isUser()) {
        builder.addUserMessage(message.content());
      } else {
        builder.addAssistantMessage(message.content());
      }
    }

    if (!systemParts.isEmpty()) {
      TextBlockParam.Builder system =
          TextBlockParam.builder().text(String.join("\n\n", systemParts));
      if (cacheSystemPrompt) {
        system.cacheControl(CacheControlEphemeral.builder().build());
      }
      builder.systemOfTextBlockParams(List.of(system.build()));
    }

    return builder.build();
  }

  // -------------------------------------------------------------------------
  // Timing
  // -------------------------------------------------------------------------

  public static long millisSince(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }

  /**
   * What the turn actually cost, in the two currencies that matter.
   *
   * <p>The local model's log split load / prompt-eval / generate because each had a
   * different local fix. None of those exist now, so this reports the two things that
   * replaced them: <b>time to first token</b>, which is what makes a turn feel fast
   * and is dominated by the network round trip plus whatever thinking the effort level
   * bought; and <b>tokens</b>, which is the bill. {@code cache=} is the one to watch
   * after a config change — a system prompt that stops being served from cache is a
   * silent multiplication of input cost, and shows up here as a read count of zero.
   */
  private void logTiming(
      boolean stoppedEarly,
      long started,
      long firstTokenNanos,
      long inputTokens,
      long outputTokens,
      long cacheReadTokens,
      long cacheWriteTokens,
      String stopReason) {

    if (!log.isInfoEnabled()) return;

    log.info(
        "generate model={} effort={} first-token={}ms total={}ms in={}tok out={}tok "
            + "cache=read {}/write {} stop={}{}",
        model,
        effort,
        firstTokenNanos == 0 ? -1 : (firstTokenNanos - started) / 1_000_000,
        millisSince(started),
        inputTokens,
        outputTokens,
        cacheReadTokens,
        cacheWriteTokens,
        stopReason == null ? "n/a" : stopReason,
        stoppedEarly ? " (stopped early by caller)" : "");
  }
}
