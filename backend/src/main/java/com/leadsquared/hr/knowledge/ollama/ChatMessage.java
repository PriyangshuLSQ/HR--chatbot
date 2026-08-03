package com.leadsquared.hr.knowledge.ollama;

/** One turn in an Ollama chat request. Role is {@code system}, {@code user} or {@code assistant}. */
public record ChatMessage(String role, String content) {

  public static ChatMessage system(String content) {
    return new ChatMessage("system", content);
  }

  public static ChatMessage user(String content) {
    return new ChatMessage("user", content);
  }

  public static ChatMessage assistant(String content) {
    return new ChatMessage("assistant", content);
  }
}
