package com.leadsquared.hr.knowledge.ollama;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leadsquared.hr.knowledge.config.KnowledgeProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The streaming contract, against a stub Ollama.
 *
 * <p>A stub rather than the real daemon on purpose: these assertions are about
 * this class's own behaviour — that a sink can stop generation, that what arrived
 * before it stopped is still returned, and that the latency knobs actually reach
 * the wire — none of which need three gigabytes of weights to check, and all of
 * which would be flaky if they depended on a model's output.
 *
 * <p>What a stub cannot prove is that Ollama honours any of it. That was checked
 * against the live daemon: {@code keep_alive} moves the eviction deadline out
 * (verified via {@code ollama ps}), and hanging up mid-stream makes the server log
 * {@code srv stop: cancel task} and free the runner immediately rather than
 * finishing the answer nobody is reading.
 */
class ChatStreamTest {

  /** Enough fragments that a sink stopping early is unambiguous. */
  private static final int FRAGMENTS = 20;

  private HttpServer server;
  private String baseUrl;

  /** How many NDJSON lines the stub managed to write before the client hung up. */
  private final AtomicInteger linesWritten = new AtomicInteger();

  /** Endpoints the client actually called, so warm-up can be shown to touch both models. */
  private final List<String> hits = java.util.Collections.synchronizedList(new ArrayList<>());

  private volatile String lastRequestBody;
  private volatile String embedRequestBody;

  @BeforeEach
  void startStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

    server.createContext(
        "/api/chat",
        exchange -> {
          hits.add("/api/chat");
          lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          boolean streaming = lastRequestBody.contains("\"stream\":true");

          exchange.getResponseHeaders()
              .set("Content-Type", streaming ? "application/x-ndjson" : "application/json");
          exchange.sendResponseHeaders(200, 0);

          try (OutputStream out = exchange.getResponseBody()) {
            if (!streaming) {
              out.write(oneShotResponse().getBytes(StandardCharsets.UTF_8));
              return;
            }
            for (int i = 0; i < FRAGMENTS; i++) {
              // Slowly, so a client that stops after the first fragment closes the
              // connection long before the last line would have been written.
              out.write(fragment("word" + i + " ").getBytes(StandardCharsets.UTF_8));
              out.flush();
              linesWritten.incrementAndGet();
              Thread.sleep(25);
            }
            out.write(doneChunk().getBytes(StandardCharsets.UTF_8));
            out.flush();
          } catch (IOException | InterruptedException stopped) {
            // The client hung up. That is the behaviour under test, not a failure.
          }
        });

    server.createContext(
        "/api/tags",
        exchange -> {
          hits.add("/api/tags");
          respondJson(
              exchange,
              "{\"models\":[{\"name\":\"nomic-embed-text:latest\",\"model\":\"nomic-embed-text:latest\","
                  + "\"size\":274302450,\"digest\":\"0a109f42\"},"
                  + "{\"name\":\"qwen3.5:4b\",\"model\":\"qwen3.5:4b\",\"size\":3400000000}]}");
        });

    server.createContext(
        "/api/embed",
        exchange -> {
          hits.add("/api/embed");
          embedRequestBody =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          respondJson(exchange, "{\"embeddings\":[[0.1,0.2,0.3]]}");
        });

    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private static void respondJson(com.sun.net.httpserver.HttpExchange exchange, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  @AfterEach
  void stopStub() {
    server.stop(0);
  }

  // -------------------------------------------------------------------------
  // Streaming
  // -------------------------------------------------------------------------

  @Test
  void aSinkThatKeepsGoingReceivesEveryFragment() {
    List<String> received = new ArrayList<>();

    String whole =
        client().chatStream(
            List.of(ChatMessage.user("how much casual leave do I get?")),
            "qwen3.5:4b",
            piece -> {
              received.add(piece);
              return true;
            });

    assertThat(received).hasSize(FRAGMENTS);
    assertThat(whole).isEqualTo("word0 word1 word2 word3 word4 word5 word6 word7 word8 word9 "
        + "word10 word11 word12 word13 word14 word15 word16 word17 word18 word19");
  }

  /**
   * The refusal path: {@code RagService} recognises {@code NOT_IN_DOCUMENTS} within
   * the first few tokens and the employee will never see a word of the answer, so
   * there is nothing to buy by generating the rest of it.
   */
  @Test
  void aSinkThatStopsEndsGenerationEarly() {
    List<String> received = new ArrayList<>();

    String whole =
        client().chatStream(
            List.of(ChatMessage.user("what is the wifi password?")),
            "qwen3.5:4b",
            piece -> {
              received.add(piece);
              return false;
            });

    // The sink saw exactly one fragment and then declined to continue.
    assertThat(received).hasSize(1);
    // Everything that did arrive is still returned, because the caller re-checks
    // the whole string for the refusal marker before deciding what to do.
    assertThat(whole).isEqualTo("word0");
    // And the stub never got to write the rest — this is the latency saved.
    assertThat(linesWritten.get()).isLessThan(FRAGMENTS);
  }

  @Test
  void reasoningBlocksAreNeverHandedToTheSink() {
    // A <think> block split across fragments must not leak, mid-stream or at all.
    server.removeContext("/api/chat");
    server.createContext(
        "/api/chat",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream out = exchange.getResponseBody()) {
            for (String piece : List.of("<think>", "which leave type", "</think>", "You get **12** days.")) {
              out.write(fragment(piece).getBytes(StandardCharsets.UTF_8));
              out.flush();
            }
            out.write(doneChunk().getBytes(StandardCharsets.UTF_8));
            out.flush();
          }
        });

    StringBuilder shown = new StringBuilder();
    String whole =
        client().chatStream(
            List.of(ChatMessage.user("casual leave?")),
            "qwen3.5:4b",
            piece -> {
              shown.append(piece);
              return true;
            });

    assertThat(shown.toString()).isEqualTo("You get **12** days.");
    assertThat(whole).isEqualTo("You get **12** days.");
  }

  // -------------------------------------------------------------------------
  // The latency knobs
  // -------------------------------------------------------------------------

  /**
   * {@code keep_alive} is the whole point of the change and it is invisible in the
   * answer, so it is asserted on the wire. Without it Ollama evicts the weights
   * after five minutes and the next question pays a multi-second reload — measured
   * at 4.65s for qwen3.5:4b on this machine.
   */
  @Test
  void everyGenerationCarriesKeepAliveAndTheTokenCeiling() {
    client().chatStream(List.of(ChatMessage.user("hi")), "qwen3.5:4b", piece -> true);

    assertThat(lastRequestBody).contains("\"keep_alive\":\"45m\"");
    assertThat(lastRequestBody).contains("\"num_predict\":123");
    assertThat(lastRequestBody).contains("\"num_ctx\":2048");
    // Still asking the model not to reason out loud.
    assertThat(lastRequestBody).contains("\"think\":false");
  }

  @Test
  void theOneShotPathCarriesThemToo() {
    String answer = client().chat(List.of(ChatMessage.user("hi")), "qwen3.5:4b");

    // Parsing succeeded despite the response carrying Ollama's timing counters and
    // fields this client does not model — a strict mapper here would turn every
    // generation into a silent fallback to an extractive answer.
    assertThat(answer).isEqualTo("Casual leave is **12** days.");
    assertThat(lastRequestBody).contains("\"keep_alive\":\"45m\"");
    assertThat(lastRequestBody).contains("\"num_predict\":123");
    assertThat(lastRequestBody).contains("\"stream\":false");
  }

  // -------------------------------------------------------------------------
  // Warm-up
  // -------------------------------------------------------------------------

  /**
   * {@code keep_alive} keeps weights resident between questions; nothing puts them
   * there in the first place. Without warm-up the first employee to ask anything
   * pays the load — 4.65s on this machine — inside their own question.
   */
  @Test
  void warmUpLoadsBothModels() {
    client().warmUp();

    assertThat(hits).containsExactly("/api/tags", "/api/embed", "/api/chat");
    // The embedding model is held resident too — a chat turn embeds the question
    // first, so an evicted embed model is latency on the same critical path.
    assertThat(embedRequestBody).contains("\"keep_alive\":\"45m\"");
    // One token: what is being pre-paid is the weight load, not any output.
    assertThat(lastRequestBody).contains("\"num_predict\":1");
    // The window must match what real turns ask for. Ollama keys a loaded model on
    // its context size, so warming at a different num_ctx would make the first real
    // question reload the weights and warm nothing.
    assertThat(lastRequestBody).contains("\"num_ctx\":2048");
  }

  @Test
  void warmUpSurvivesAnUnreachableOllama() {
    server.stop(0);

    // Ollama not running is a supported state — the product degrades to BM25 and
    // extractive answers. Warm-up must not turn that into a failed startup.
    client().warmUp();

    assertThat(hits).isEmpty();
  }

  // -------------------------------------------------------------------------
  // Fixtures
  // -------------------------------------------------------------------------

  /**
   * A deliberately <b>strict</b> mapper — the default {@code ObjectMapper}, which
   * throws on any field it does not model.
   *
   * <p>That is the point. Ollama sends {@code role} beside {@code content} on every
   * chunk, so a client whose wire records are not self-sufficient parses nothing
   * here. It used to pass only because the injected Spring Boot mapper happens to
   * be lenient; keeping a strict one in the test is what stops that global setting
   * from quietly becoming load-bearing again.
   */
  private OllamaClient client() {
    KnowledgeProperties props =
        new KnowledgeProperties(
            new KnowledgeProperties.Ollama(
                baseUrl, "nomic-embed-text", "qwen3.5:4b", "45m", 123, 2048),
            null,
            null);
    return new OllamaClient(props, new ObjectMapper());
  }

  private static String fragment(String content) {
    return "{\"model\":\"qwen3.5:4b\",\"message\":{\"role\":\"assistant\",\"content\":\""
        + content
        + "\"},\"done\":false}\n";
  }

  /** The final chunk, carrying the counters the timing log reports. */
  private static String doneChunk() {
    return "{\"model\":\"qwen3.5:4b\",\"message\":{\"role\":\"assistant\",\"content\":\"\"},"
        + "\"done\":true,\"done_reason\":\"stop\",\"load_duration\":4651174375,"
        + "\"prompt_eval_count\":19,\"prompt_eval_duration\":388857000,"
        + "\"eval_count\":10,\"eval_duration\":270970000,\"total_duration\":5318479292}\n";
  }

  private static String oneShotResponse() {
    return "{\"model\":\"qwen3.5:4b\",\"created_at\":\"2026-08-03T00:00:00Z\","
        + "\"message\":{\"role\":\"assistant\",\"content\":\"Casual leave is **12** days.\"},"
        + "\"done\":true,\"done_reason\":\"stop\",\"load_duration\":4651174375,"
        + "\"prompt_eval_count\":19,\"prompt_eval_duration\":388857000,"
        + "\"eval_count\":10,\"eval_duration\":270970000,\"total_duration\":5318479292}";
  }
}
