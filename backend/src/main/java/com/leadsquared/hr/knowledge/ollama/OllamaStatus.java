package com.leadsquared.hr.knowledge.ollama;

import java.util.List;

/**
 * Whether the local embedding backend is up.
 *
 * <p>Answer writing moved to the Claude API, so this no longer reports a chat model
 * — see {@code ClaudeStatus}. Ollama is still required, and still on the critical
 * path of every question: it produces the query vector that the existing Qdrant
 * collections are searchable by.
 *
 * @param models model names actually pulled on this machine
 * @param embedModel the embedding model we will use, or null if none is installed
 * @param hint human-readable next step when something is missing; null when
 *     everything needed is present
 */
public record OllamaStatus(
    boolean ok, String url, List<String> models, String embedModel, String hint) {

  public boolean canEmbed() {
    return ok && embedModel != null;
  }
}
