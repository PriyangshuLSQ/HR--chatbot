package com.leadsquared.hr.knowledge.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * That the tuning knobs in {@code application.yml} actually reach the client.
 *
 * <p>Worth a test specifically because a failure here is silent. Each of these
 * properties carries a {@code @DefaultValue} equal to the value the yaml sets, so a
 * mistyped or renamed key does not throw and does not log — it just quietly falls
 * back to a default that happens to look identical. The next person to change
 * {@code keep-alive} in the yaml would see no effect and no reason why.
 */
class KnowledgePropertiesTest {

  @Test
  void theLatencyKnobsBindFromKebabCaseKeys() {
    Map<String, Object> yaml = new LinkedHashMap<>();
    yaml.put("knowledge.ollama.url", "http://127.0.0.1:11434");
    yaml.put("knowledge.ollama.embed-model", "nomic-embed-text");
    yaml.put("knowledge.ollama.chat-model", "qwen3.5:4b");
    // Deliberately not the defaults — a value that survives is proof of binding.
    yaml.put("knowledge.ollama.keep-alive", "17m");
    yaml.put("knowledge.ollama.max-answer-tokens", "321");
    yaml.put("knowledge.ollama.num-context", "8192");

    KnowledgeProperties.Ollama ollama =
        new Binder(new MapConfigurationPropertySource(yaml))
            .bind("knowledge.ollama", KnowledgeProperties.Ollama.class)
            .get();

    assertThat(ollama.keepAlive()).isEqualTo("17m");
    assertThat(ollama.maxAnswerTokens()).isEqualTo(321);
    assertThat(ollama.numContext()).isEqualTo(8192);
  }

  /**
   * A deployment that sets none of them still gets working values, so the service
   * boots on a machine with nothing but the model names configured.
   */
  @Test
  void omittingThemFallsBackToTheDocumentedDefaults() {
    Map<String, Object> yaml = new LinkedHashMap<>();
    yaml.put("knowledge.ollama.url", "http://127.0.0.1:11434");
    yaml.put("knowledge.ollama.embed-model", "nomic-embed-text");
    yaml.put("knowledge.ollama.chat-model", "qwen3.5:4b");

    KnowledgeProperties.Ollama ollama =
        new Binder(new MapConfigurationPropertySource(yaml))
            .bind("knowledge.ollama", KnowledgeProperties.Ollama.class)
            .get();

    assertThat(ollama.keepAlive()).isEqualTo("30m");
    assertThat(ollama.maxAnswerTokens()).isEqualTo(250);
    assertThat(ollama.numContext()).isEqualTo(4096);
  }
}
