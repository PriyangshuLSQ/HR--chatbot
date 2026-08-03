package com.leadsquared.hr.knowledge.model;

/**
 * One line of the conversation attached to a ticket.
 *
 * @param role {@code user} or {@code bot} — the strings the chat UI renders on
 * @param text the wording as it was said, never paraphrased
 */
public record TranscriptTurn(String role, String text) {}
