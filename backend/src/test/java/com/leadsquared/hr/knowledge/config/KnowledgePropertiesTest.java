package com.leadsquared.hr.knowledge.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * That the tuning knobs in {@code application.yml} actually reach the clients.
 *
 * <p>Worth a test specifically because a failure here is silent. Each of these
 * properties carries a {@code @DefaultValue} equal to the value the yaml sets, so a
 * mistyped or renamed key does not throw and does not log — it just quietly falls
 * back to a default that happens to look identical. The next person to change
 * {@code effort} in the yaml would see no effect and no reason why.
 */
class KnowledgePropertiesTest {

  @Test
  void theEmbedderKnobsBindFromKebabCaseKeys() {
    Map<String, Object> yaml = new LinkedHashMap<>();
    yaml.put("knowledge.ollama.url", "http://127.0.0.1:11434");
    yaml.put("knowledge.ollama.embed-model", "nomic-embed-text");
    // Deliberately not the default — a value that survives is proof of binding.
    yaml.put("knowledge.ollama.keep-alive", "17m");

    KnowledgeProperties.Ollama ollama =
        new Binder(new MapConfigurationPropertySource(yaml))
            .bind("knowledge.ollama", KnowledgeProperties.Ollama.class)
            .get();

    assertThat(ollama.embedModel()).isEqualTo("nomic-embed-text");
    assertThat(ollama.keepAlive()).isEqualTo("17m");
  }

  @Test
  void theAnswerModelKnobsBindFromKebabCaseKeys() {
    Map<String, Object> yaml = new LinkedHashMap<>();
    yaml.put("knowledge.claude.api-key", "sk-ant-example");
    yaml.put("knowledge.claude.model", "claude-haiku-4-5");
    yaml.put("knowledge.claude.max-answer-tokens", "1234");
    yaml.put("knowledge.claude.effort", "xhigh");
    yaml.put("knowledge.claude.cache-system-prompt", "false");

    KnowledgeProperties.Claude claude =
        new Binder(new MapConfigurationPropertySource(yaml))
            .bind("knowledge.claude", KnowledgeProperties.Claude.class)
            .get();

    assertThat(claude.apiKey()).isEqualTo("sk-ant-example");
    assertThat(claude.model()).isEqualTo("claude-haiku-4-5");
    assertThat(claude.maxAnswerTokens()).isEqualTo(1234);
    assertThat(claude.effort()).isEqualTo("xhigh");
    assertThat(claude.cacheSystemPrompt()).isFalse();
  }

  /**
   * A deployment that sets none of them still gets working values, so the service
   * boots on a machine with nothing but an API key configured.
   *
   * <p>The token ceiling is the one to keep an eye on. It bounds thinking and the
   * visible answer together, so a default sized to the answer alone — the 250 the
   * local model used to get — would truncate answers mid-sentence rather than fail.
   */
  @Test
  void omittingThemFallsBackToTheDocumentedDefaults() {
    Map<String, Object> yaml = new LinkedHashMap<>();
    yaml.put("knowledge.ollama.url", "http://127.0.0.1:11434");
    yaml.put("knowledge.ollama.embed-model", "nomic-embed-text");
    yaml.put("knowledge.claude.api-key", "sk-ant-example");

    Binder binder = new Binder(new MapConfigurationPropertySource(yaml));

    KnowledgeProperties.Ollama ollama =
        binder.bind("knowledge.ollama", KnowledgeProperties.Ollama.class).get();
    KnowledgeProperties.Claude claude =
        binder.bind("knowledge.claude", KnowledgeProperties.Claude.class).get();

    assertThat(ollama.keepAlive()).isEqualTo("30m");
    assertThat(claude.model()).isEqualTo("claude-opus-5");
    assertThat(claude.maxAnswerTokens()).isEqualTo(2000);
    assertThat(claude.effort()).isEqualTo("low");
    assertThat(claude.cacheSystemPrompt()).isTrue();
  }
}
