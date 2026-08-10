package com.leadsquared.hr.knowledge.claude;

/**
 * Whether answer writing is configured, and with what.
 *
 * <p>Deliberately <b>not</b> a live probe, unlike {@code OllamaStatus}. The admin
 * panel polls status on every page view; a probe would mean a billed API call each
 * time, to learn something a blank API key already tells us. A key that is present
 * but wrong surfaces where it actually matters — on the next question, as a
 * fallback to the extractive answer with the reason attached.
 *
 * @param configured an API key is present, so generation will be attempted
 * @param model the model id answers are written with, or null when not configured
 * @param effort the configured effort level, echoed for the admin panel
 * @param hint human-readable next step when something is missing; null when fine
 */
public record ClaudeStatus(boolean configured, String model, String effort, String hint) {

  public static ClaudeStatus notConfigured() {
    return new ClaudeStatus(
        false,
        null,
        null,
        "No Anthropic API key is set, so employees see the matching policy passage rather "
            + "than a written answer. Set `ANTHROPIC_API_KEY` (or `knowledge.claude.api-key` "
            + "in application-local.yml) and restart.");
  }
}
