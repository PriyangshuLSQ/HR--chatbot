package com.leadsquared.hr.knowledge.config;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * RestClients for the two backing services.
 *
 * <p>They get very different timeouts on purpose. Qdrant answers in milliseconds
 * and a slow response means something is wrong. A local model on CPU can take a
 * minute to write its first answer, and cutting that off would turn a working
 * setup into a broken-looking one — so Ollama's own per-call timeouts are set at
 * the call site instead.
 */
@Configuration
public class HttpClients {

  @Bean
  RestClient qdrantClient(KnowledgeProperties props) {
    RestClient.Builder builder =
        RestClient.builder()
            .baseUrl(props.qdrant().url())
            .requestFactory(factory(Duration.ofSeconds(3), Duration.ofSeconds(30)));

    String apiKey = props.qdrant().apiKey();
    if (apiKey != null && !apiKey.isBlank()) {
      builder.defaultHeader("api-key", apiKey);
    }
    return builder.build();
  }

  /**
   * Ollama gets no shared bean: a status probe and a 90-second generation need
   * very different read timeouts, and RestClient fixes those on the request
   * factory. {@code OllamaClient} builds one client per call class instead.
   */
  public static ClientHttpRequestFactory factory(Duration connect, Duration read) {
    return ClientHttpRequestFactoryBuilder.detect()
        .build(ClientHttpRequestFactorySettings.defaults().withConnectTimeout(connect).withReadTimeout(read));
  }
}
