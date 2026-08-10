package com.leadsquared.hr.knowledge.claude;

/**
 * One turn of a prompt, before it is shaped for any particular provider.
 *
 * <p>Role is {@code system}, {@code user} or {@code assistant}. Kept provider-neutral
 * on purpose: {@code RagService} builds the grounding prompt and should not know
 * whether the far end wants Ollama's flat message array or Anthropic's separate
 * system parameter. {@link ClaudeClient} does that translation.
 */
public record ChatMessage(String role, String content) {

  public static final String SYSTEM = "system";
  public static final String USER = "user";
  public static final String ASSISTANT = "assistant";

  public static ChatMessage system(String content) {
    return new ChatMessage(SYSTEM, content);
  }

  public static ChatMessage user(String content) {
    return new ChatMessage(USER, content);
  }

  public static ChatMessage assistant(String content) {
    return new ChatMessage(ASSISTANT, content);
  }

  public boolean isSystem() {
    return SYSTEM.equals(role);
  }

  public boolean isUser() {
    return USER.equals(role);
  }
}
