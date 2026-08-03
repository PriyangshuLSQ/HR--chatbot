package com.leadsquared.hr.knowledge.rag;

/**
 * Relevance gates.
 *
 * <p>These are duplicated in {@code lib/knowledge/types.ts} on the browser side,
 * where the chat client uses them to decide <i>whether to ask</i> at all. Keep
 * the two in step: lowering the bar here without lowering it there means the
 * backend answers questions the client never sends.
 */
public final class Thresholds {

  private Thresholds() {}

  /**
   * Below this retrieval confidence we do not answer from documents at all.
   * Tuned so an off-topic question ("what's the wifi password") falls through to
   * the existing HR escalation path instead of getting a confident non-answer.
   */
  public static final double RELEVANCE = 0.34;

  /**
   * Above this, a document match is strong enough to pre-empt the NLU's
   * clarifying question — HR's own policy text beats "which of these did you
   * mean?" when the passage plainly covers it.
   */
  public static final double STRONG_RELEVANCE = 0.55;
}
