package com.leadsquared.hr.knowledge.web;

import com.fasterxml.jackson.annotation.JsonValue;
import com.leadsquared.hr.knowledge.model.KnowledgeAnswer;
import com.leadsquared.hr.knowledge.model.StoreStats;
import com.leadsquared.hr.knowledge.claude.ClaudeClient;
import com.leadsquared.hr.knowledge.claude.ClaudeStatus;
import com.leadsquared.hr.knowledge.ollama.OllamaClient;
import com.leadsquared.hr.knowledge.ollama.OllamaStatus;
import com.leadsquared.hr.knowledge.employee.EmployeeAnswerService;
import com.leadsquared.hr.knowledge.rag.RagService;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;
import com.leadsquared.hr.knowledge.store.KnowledgeStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Grounded answering, and whether the local AI backend can do it. */
@RestController
@RequestMapping("/api/ai")
public class AiController {

  private static final Logger log = LoggerFactory.getLogger(AiController.class);

  /** Longer than any real question; a longer body is a client bug or an attack. */
  private static final int MAX_QUESTION_CHARS = 2000;

  /** Prior turns the model is shown. More context crowds out the extracts. */
  private static final int MAX_HISTORY_TURNS = 6;

  /**
   * Virtual threads: a streamed answer is almost entirely spent blocked on the
   * Claude API, so the cost of one is a parked thread rather than a platform thread,
   * and concurrent chats are not capped by a pool size. This matters more now than
   * it did against a local model — one laptop could only generate one answer at a
   * time anyway, whereas a hosted API will happily serve every employee at once.
   */
  /**
   * Generation runs off the request thread, so the security context has to travel with it.
   *
   * <p>{@code SecurityContextHolder} is thread-local. Without this wrapper the streamed path
   * would resolve no signed-in user, and every question about the employee's own record would
   * be answered "please sign in" while the identical question on the non-streamed path worked
   * — the confusing half-failure that comes of identity being ambient. Wrapping keeps identity
   * ambient <em>and</em> correct, which is what lets {@code EmployeeDataService} take it from
   * the session rather than from a parameter.
   */
  private final ExecutorService streamExecutor =
      new DelegatingSecurityContextExecutorService(Executors.newVirtualThreadPerTaskExecutor());

  private final RagService rag;

  /** Owns the privacy routing. Questions go through here, never straight to {@code rag}. */
  private final EmployeeAnswerService answers;
  private final OllamaClient ollama;
  private final ClaudeClient claude;
  private final KnowledgeStore store;

  public AiController(
      RagService rag,
      EmployeeAnswerService answers,
      OllamaClient ollama,
      ClaudeClient claude,
      KnowledgeStore store) {
    this.rag = rag;
    this.answers = answers;
    this.ollama = ollama;
    this.claude = claude;
    this.store = store;
  }

  // -------------------------------------------------------------------------
  // Ask
  // -------------------------------------------------------------------------

  public record AskRequest(String question, List<HistoryTurn> history, Boolean extractiveOnly) {}

  public record HistoryTurn(String role, String text) {}

  /**
   * Grounded answer from HR's uploaded documents.
   *
   * <p>Called by the chat window only after the curated NLU has declined to
   * answer, so this handles the long tail: whatever HR uploaded that the
   * hand-written intents never covered.
   *
   * <p>A {@code mode: none} response is a normal, expected outcome — it means the
   * knowledge base does not cover the question, and the caller falls through to
   * its escalation path rather than surfacing anything.
   */
  @PostMapping("/ask")
  public ResponseEntity<?> ask(@RequestBody(required = false) AskRequest body) {
    if (body == null) return ApiErrors.badRequest("Expected a JSON body.");

    String question = body.question() == null ? "" : body.question().trim();
    if (question.isEmpty()) return ApiErrors.badRequest("A question is required.");
    if (question.length() > MAX_QUESTION_CHARS) return ApiErrors.badRequest("That question is too long.");

    List<RagService.Turn> history = new ArrayList<>();
    if (body.history() != null) {
      for (HistoryTurn turn : body.history()) {
        if (turn == null || turn.text() == null) continue;
        history.add(new RagService.Turn("user".equals(turn.role()) ? "user" : "bot", turn.text()));
      }
    }
    if (history.size() > MAX_HISTORY_TURNS) {
      history = history.subList(history.size() - MAX_HISTORY_TURNS, history.size());
    }

    try {
      return ResponseEntity.ok(
          answers.ask(question, history, Boolean.TRUE.equals(body.extractiveOnly()), null));
    } catch (RuntimeException e) {
      // Answering is best-effort — a failure here must not break the chat, so
      // report "nothing found" and let the caller escalate to a human.
      log.error("Ask failed", e);
      return ResponseEntity.ok(KnowledgeAnswer.none(0));
    }
  }

  /**
   * The same answer as {@code /ask}, streamed as the model writes it.
   *
   * <p>Server-sent events, three types: {@code token} carries the next fragment,
   * {@code answer} carries the complete {@link KnowledgeAnswer} — citations,
   * confidence, mode — and {@code error} reports a failure the client should treat
   * as "no answer". The final {@code answer} event is authoritative; a client can
   * ignore every token and still be correct, which is what keeps this endpoint
   * honest about the non-streaming contract.
   *
   * <p>Why the answer arrives last rather than first: whether there <em>is</em> an
   * answer is not known until the model has spoken. A refusal only becomes visible
   * partway through generation, and announcing "mode: generated" up front would
   * mean retracting it.
   */
  @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter askStream(@RequestBody(required = false) AskRequest body) {
    // No timeout of its own: the read timeout on the Ollama client already bounds
    // how long generation may take, and a shorter one here would cut off a slow
    // answer that was about to arrive.
    SseEmitter emitter = new SseEmitter(0L);

    String question = body == null || body.question() == null ? "" : body.question().trim();
    if (question.isEmpty() || question.length() > MAX_QUESTION_CHARS) {
      try {
        emitter.send(SseEmitter.event().name("error").data("A question is required."));
      } catch (IOException ignored) {
        // The client hung up before we could tell it why. Nothing to do.
      }
      emitter.complete();
      return emitter;
    }

    List<RagService.Turn> history = historyFrom(body);

    // Off the request thread: generation takes seconds, and holding a servlet
    // thread for the duration would cap concurrent chats at the pool size.
    streamExecutor.execute(
        () -> {
          try {
            KnowledgeAnswer answer =
                answers.askStreaming(
                    question,
                    history,
                    piece -> {
                      try {
                        emitter.send(SseEmitter.event().name("token").data(piece));
                      } catch (IOException e) {
                        // Employee closed the tab or navigated away mid-answer.
                        // Abort generation rather than writing into a dead socket.
                        throw new StreamClosed();
                      }
                    },
                    null);

            emitter.send(SseEmitter.event().name("answer").data(answer));
            emitter.complete();

          } catch (StreamClosed e) {
            emitter.complete();
          } catch (Exception e) {
            log.error("Streaming ask failed", e);
            try {
              // Same contract as /ask: a failure is reported as "nothing found" so
              // the conversation falls through to offering a human.
              emitter.send(SseEmitter.event().name("answer").data(KnowledgeAnswer.none(0)));
            } catch (IOException ignored) {
              // Already gone.
            }
            emitter.complete();
          }
        });

    return emitter;
  }

  /** The client went away mid-stream. Not an error worth logging. */
  private static class StreamClosed extends RuntimeException {
    StreamClosed() {
      super(null, null, false, false);
    }
  }

  private List<RagService.Turn> historyFrom(AskRequest body) {
    List<RagService.Turn> history = new ArrayList<>();
    if (body != null && body.history() != null) {
      for (HistoryTurn turn : body.history()) {
        if (turn == null || turn.text() == null) continue;
        history.add(new RagService.Turn("user".equals(turn.role()) ? "user" : "bot", turn.text()));
      }
    }
    return history.size() > MAX_HISTORY_TURNS
        ? history.subList(history.size() - MAX_HISTORY_TURNS, history.size())
        : history;
  }


  // -------------------------------------------------------------------------
  // Status
  // -------------------------------------------------------------------------

  /** What the employee actually gets right now. */
  public enum Capability {
    GENERATIVE("generative"),
    SEMANTIC("semantic"),
    KEYWORD("keyword");

    private final String wire;

    Capability(String wire) {
      this.wire = wire;
    }

    @JsonValue
    public String wire() {
      return wire;
    }
  }

  /**
   * Two backends, reported separately, because they fail independently and the fixes
   * are unrelated: {@code ollama} is the local embedder that makes the Qdrant index
   * searchable, {@code claude} is the hosted model that writes the answer. Losing
   * the first drops the assistant to keyword search; losing the second drops it to
   * quoting the passage.
   */
  public record AiStatus(
      Capability capability,
      OllamaStatus ollama,
      ClaudeStatus claude,
      StoreStats stats,
      String summary) {}

  /**
   * Drives the admin console's AI panel.
   *
   * <p>Deliberately reports the degraded states distinctly (embedder offline / no
   * embedding model pulled / nothing embedded yet / no API key) because each has a
   * different one-line fix.
   */
  @GetMapping("/status")
  public AiStatus status() {
    // Bypass the cache: this panel is how HR checks whether the model they just
    // installed has been picked up.
    OllamaStatus status = ollama.getStatus(true);
    ClaudeStatus claudeStatus = claude.getStatus();
    StoreStats stats = store.stats();

    Capability capability;
    if (claudeStatus.configured() && status.canEmbed() && stats.embeddedChunks() > 0) {
      capability = Capability.GENERATIVE;
    } else if (status.canEmbed() && stats.embeddedChunks() > 0) {
      capability = Capability.SEMANTIC;
    } else {
      capability = Capability.KEYWORD;
    }

    return new AiStatus(
        capability,
        status,
        claudeStatus,
        stats,
        summarise(capability, status.ok(), claudeStatus, stats.chunkCount()));
  }

  private static String summarise(
      Capability capability, boolean embedderOnline, ClaudeStatus claude, int chunks) {

    if (chunks == 0) {
      return "No documents uploaded yet. Add HR policies to give the assistant something to answer from.";
    }
    return switch (capability) {
      case GENERATIVE ->
          "Full AI: semantic search over your documents, with answers written by "
              + claude.model()
              + " and grounded in the source text.";
      case SEMANTIC ->
          "Semantic search is live, but no Anthropic API key is configured — employees see "
              + "the matching policy passage rather than a written answer.";
      case KEYWORD ->
          embedderOnline
              ? "Keyword search only. Pull the embedding model and rebuild the index to enable "
                  + "semantic matching."
              : "Keyword search only — the local embedder is offline. Everything still works; "
                  + "answers are quoted from your documents.";
    };
  }
}
