package com.leadsquared.hr.knowledge.claude;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadsquared.hr.knowledge.config.KnowledgeProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The streaming contract, against a stub Anthropic endpoint.
 *
 * <p>A stub rather than the real API on purpose: these assertions are about this
 * class's own behaviour — that a sink can stop generation, that what arrived before
 * it stopped is still returned, that a classifier refusal is not passed off as an
 * answer, and that the prompt is translated into the shape the API expects — none of
 * which need a billed call, and all of which would be flaky if they depended on a
 * model's output.
 *
 * <p>What a stub cannot prove is that Anthropic honours any of it. Two things to
 * verify against the live API after changing this class: that hanging up mid-stream
 * actually stops billing (compare {@code out=} in the generate log for a refused
 * question against an answered one), and that {@code cache=read} is non-zero on the
 * second of two back-to-back questions.
 */
class ClaudeStreamTest {

  /** Enough fragments that a sink stopping early is unambiguous. */
  private static final int FRAGMENTS = 20;

  private HttpServer server;
  private String baseUrl;
  private final AtomicReference<String> lastRequestBody = new AtomicReference<>("");
  private final AtomicReference<String> stopReason = new AtomicReference<>("end_turn");

  @BeforeEach
  void startStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/messages", this::respondWithStream);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stopStub() {
    if (server != null) server.stop(0);
  }

  /**
   * Emits the Server-Sent Events an Anthropic streaming response is made of.
   *
   * <p>Writes and flushes each event separately so that a client hanging up part
   * way through fails on a later write rather than after the whole body is already
   * buffered — otherwise "the sink stopped early" would pass whether or not the
   * stream was really cut.
   */
  private void respondWithStream(HttpExchange exchange) throws IOException {
    lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
    exchange.sendResponseHeaders(200, 0);

    try (OutputStream body = exchange.getResponseBody()) {
      send(body, "message_start",
          "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_stub\",\"type\":\"message\","
              + "\"role\":\"assistant\",\"content\":[],\"model\":\"claude-opus-5\","
              + "\"stop_reason\":null,\"stop_sequence\":null,"
              + "\"usage\":{\"input_tokens\":11,\"output_tokens\":0,"
              + "\"cache_read_input_tokens\":7,\"cache_creation_input_tokens\":0}}}");

      send(body, "content_block_start",
          "{\"type\":\"content_block_start\",\"index\":0,"
              + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");

      for (int i = 0; i < FRAGMENTS; i++) {
        send(body, "content_block_delta",
            "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"piece" + i + " \"}}");
      }

      send(body, "content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");
      send(body, "message_delta",
          "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"" + stopReason.get()
              + "\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":42}}");
      send(body, "message_stop", "{\"type\":\"message_stop\"}");
    } catch (IOException hungUp) {
      // Expected when a sink stops early — that is the behaviour under test.
    }
  }

  private static void send(OutputStream body, String event, String data) throws IOException {
    body.write(("event: " + event + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
    body.flush();
  }

  private ClaudeClient client() {
    return client("low", 2000, true);
  }

  private ClaudeClient client(String effort, int maxTokens, boolean cacheSystemPrompt) {
    KnowledgeProperties props =
        new KnowledgeProperties(
            new KnowledgeProperties.Ollama("http://127.0.0.1:11434", "nomic-embed-text", "30m"),
            new KnowledgeProperties.Claude(
                "sk-ant-stub", "claude-opus-5", maxTokens, effort, cacheSystemPrompt),
            new KnowledgeProperties.Ocr(false, "claude-haiku-4-5", 12, 6144, 1500),
            new KnowledgeProperties.Qdrant("http://127.0.0.1:6333", "", "hr_chunks", "hr_docs", 768),
            new KnowledgeProperties.Migration(null, false));

    return new ClaudeClient(props, baseUrl);
  }

  // -------------------------------------------------------------------------
  // Streaming
  // -------------------------------------------------------------------------

  @Test
  void aSinkThatKeepsGoingReceivesEveryFragment() {
    List<String> received = new ArrayList<>();
    String whole = client().chatStream(List.of(ChatMessage.user("hi")), piece -> {
      received.add(piece);
      return true;
    });

    assertThat(received).hasSize(FRAGMENTS);
    assertThat(whole).isEqualTo(String.join("", received).trim());
    assertThat(whole).startsWith("piece0").endsWith("piece19");
  }

  /**
   * The saving that pays for the refusal guard.
   *
   * <p>{@code RagService} withholds the first ninety characters so a NOT_IN_DOCUMENTS
   * refusal is never shown. Recognising one there is only worth doing if it also stops
   * the model — otherwise the several hundred tokens behind it are generated, billed,
   * and thrown away.
   */
  @Test
  void aSinkThatStopsEndsGenerationEarly() {
    List<String> received = new ArrayList<>();
    String whole = client().chatStream(List.of(ChatMessage.user("hi")), piece -> {
      received.add(piece);
      return received.size() < 3;
    });

    assertThat(received).hasSize(3);
    // What arrived before stopping is still returned, so the caller's refusal and
    // citation handling works on the whole string either way.
    assertThat(whole).isNotNull().startsWith("piece0").doesNotContain("piece19");
  }

  /**
   * A safety classifier declining is not an answer, and must not be handed on as one.
   *
   * <p>Distinct from the model deciding the extracts do not cover the question — that
   * arrives as NOT_IN_DOCUMENTS text and is the caller's business. This is the request
   * being refused outright, and returning the partial text would show an employee a
   * fragment of something that was never an answer.
   */
  @Test
  void aClassifierRefusalIsNotReturnedAsAnAnswer() {
    stopReason.set("refusal");

    String whole = client().chatStream(List.of(ChatMessage.user("hi")), piece -> true);

    assertThat(whole).isNull();
  }

  // -------------------------------------------------------------------------
  // Prompt translation
  // -------------------------------------------------------------------------

  /**
   * The system prompt must reach the API's own {@code system} parameter, not the
   * message array.
   *
   * <p>Both would produce an answer, which is what makes this worth pinning: as a
   * message it would sit inside the volatile part of the prompt, so the cache
   * breakpoint would move every turn and the grounding contract would be re-billed at
   * full price on every single question, silently.
   */
  @Test
  void theSystemPromptBecomesTheSystemParameterAndIsMarkedCacheable() {
    client()
        .chatStream(
            List.of(
                ChatMessage.system("GROUNDING CONTRACT"),
                ChatMessage.user("first"),
                ChatMessage.assistant("answer"),
                ChatMessage.user("second")),
            piece -> true);

    String body = lastRequestBody.get();
    assertThat(body).contains("\"system\"").contains("GROUNDING CONTRACT");
    assertThat(body).contains("cache_control").contains("ephemeral");
    // The conversation turns keep their roles and their order.
    assertThat(body.indexOf("\"first\"")).isLessThan(body.indexOf("\"answer\""));
    assertThat(body.indexOf("\"answer\"")).isLessThan(body.indexOf("\"second\""));
  }

  @Test
  void cachingCanBeTurnedOff() {
    client("low", 2000, false)
        .chatStream(List.of(ChatMessage.system("GROUNDING CONTRACT"), ChatMessage.user("hi")),
            piece -> true);

    assertThat(lastRequestBody.get()).contains("GROUNDING CONTRACT").doesNotContain("cache_control");
  }

  /**
   * The two knobs that are silent when wrong.
   *
   * <p>A {@code max_tokens} that never reached the wire truncates answers at the
   * API's own default; an {@code effort} that never reached it changes latency and
   * cost with no error and no log line. Both look like model behaviour rather than
   * config.
   */
  @Test
  void effortAndTheTokenCeilingReachTheWire() {
    client("xhigh", 1234, true).chatStream(List.of(ChatMessage.user("hi")), piece -> true);

    assertThat(lastRequestBody.get()).contains("1234").contains("xhigh");
  }

  // -------------------------------------------------------------------------
  // Not configured
  // -------------------------------------------------------------------------

  /**
   * A blank API key is a supported state, not a crash: the assistant falls back to
   * extractive answers exactly as it did when no local chat model was pulled.
   */
  @Test
  void aBlankApiKeyDisablesGenerationWithoutFailing() {
    KnowledgeProperties props =
        new KnowledgeProperties(
            new KnowledgeProperties.Ollama("http://127.0.0.1:11434", "nomic-embed-text", "30m"),
            new KnowledgeProperties.Claude("  ", "claude-opus-5", 2000, "low", true),
            new KnowledgeProperties.Ocr(false, "claude-haiku-4-5", 12, 6144, 1500),
            new KnowledgeProperties.Qdrant("http://127.0.0.1:6333", "", "hr_chunks", "hr_docs", 768),
            new KnowledgeProperties.Migration(null, false));

    ClaudeClient claude = new ClaudeClient(props, baseUrl);

    assertThat(claude.isConfigured()).isFalse();
    assertThat(claude.getStatus().configured()).isFalse();
    assertThat(claude.getStatus().hint()).contains("ANTHROPIC_API_KEY");
    assertThat(claude.chatStream(List.of(ChatMessage.user("hi")), piece -> true)).isNull();
  }
}
