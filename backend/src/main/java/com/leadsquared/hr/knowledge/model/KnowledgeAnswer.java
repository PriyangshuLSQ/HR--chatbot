package com.leadsquared.hr.knowledge.model;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;

/**
 * What {@code POST /api/ai/ask} returns.
 *
 * <p>{@code mode: none} is a normal outcome, not an error: it means the knowledge
 * base does not cover the question, and the chat client falls through to its
 * escalation path rather than surfacing a guess.
 *
 * @param confidence retrieval confidence 0-1, which the caller uses to decide
 *     whether to trust this at all
 * @param model the model that wrote the prose, when mode is {@code generated}
 * @param warning set when generation was attempted and failed
 */
public record KnowledgeAnswer(
    Mode mode, String answer, List<Citation> citations, double confidence, String model, String warning) {

  public enum Mode {
    /** An LLM wrote it, grounded in the retrieved chunks. */
    GENERATED("generated"),
    /** No LLM available — the best passage, returned verbatim. */
    EXTRACTIVE("extractive"),
    /** Nothing in the knowledge base was relevant. */
    NONE("none");

    private final String wire;

    Mode(String wire) {
      this.wire = wire;
    }

    @JsonValue
    public String wire() {
      return wire;
    }
  }

  public static KnowledgeAnswer none(double confidence) {
    return new KnowledgeAnswer(Mode.NONE, "", List.of(), confidence, null, null);
  }
}
