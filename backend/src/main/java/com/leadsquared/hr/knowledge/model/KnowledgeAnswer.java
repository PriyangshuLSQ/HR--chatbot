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
    NONE("none"),
    /**
     * Refused on privacy grounds — the question reached for another employee's data.
     *
     * <p>A separate mode from {@link #NONE} because the two must not be handled alike: none
     * means "look elsewhere" and falls through to the escalation path, while this is a
     * definitive answer that already tells the employee where to go. Rendering a decline as
     * a failed lookup would invite the client to retry it some other way.
     */
    DECLINED("declined");

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

  /**
   * A privacy refusal, carrying no data and no citations.
   *
   * <p>Confidence is 1.0 deliberately. It is not a retrieval score here — there was no
   * retrieval — and the client drops answers below its confidence threshold, so a decline
   * scored 0 would be discarded and replaced by a vaguer fallback. We are certain of this
   * refusal, and the employee needs to see the reason rather than a non-answer.
   */
  public static KnowledgeAnswer declined(String message) {
    return new KnowledgeAnswer(Mode.DECLINED, message, List.of(), 1.0, null, null);
  }
}
