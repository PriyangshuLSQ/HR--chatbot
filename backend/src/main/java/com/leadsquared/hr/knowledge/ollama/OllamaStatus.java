package com.leadsquared.hr.knowledge.ollama;

import java.util.List;

/**
 * Whether the local AI backend is up, and what it can do.
 *
 * @param models model names actually pulled on this machine
 * @param embedModel the embedding model we will use, or null if none is installed
 * @param chatModel the chat model we will use, or null if none is installed
 * @param hint human-readable next step when something is missing; null when
 *     everything needed is present
 */
public record OllamaStatus(
    boolean ok, String url, List<String> models, String embedModel, String chatModel, String hint) {

  public boolean canEmbed() {
    return ok && embedModel != null;
  }

  public boolean canGenerate() {
    return ok && chatModel != null;
  }
}
