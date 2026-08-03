package com.leadsquared.hr.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Everything configurable, bound from {@code application.yml} / environment. */
@ConfigurationProperties(prefix = "knowledge")
public record KnowledgeProperties(Ollama ollama, Qdrant qdrant, Migration migration) {

  /**
   * @param url the local Ollama daemon
   * @param embedModel wanted embedding model; resolved leniently against what is
   *     actually pulled, so a user who installed a different one still gets
   *     semantic search
   * @param chatModel wanted chat model, resolved the same way
   * @param keepAlive how long Ollama holds the weights in memory after a call, in
   *     its own duration format ({@code 30m}, {@code -1} for forever). The two
   *     latency knobs are here rather than hard-coded because tuning them is a
   *     measure-and-adjust exercise, and a rebuild per attempt is a poor loop.
   * @param maxAnswerTokens ceiling on generated tokens per answer
   * @param numContext context window to load the model with, in tokens. Must hold
   *     the prompt <i>and</i> the answer.
   */
  public record Ollama(
      String url,
      String embedModel,
      String chatModel,
      @DefaultValue("30m") String keepAlive,
      @DefaultValue("250") int maxAnswerTokens,
      @DefaultValue("4096") int numContext) {}

  /**
   * @param vectorSize dimensionality of the embedding model. 768 for
   *     {@code nomic-embed-text}. Changing the embedding model to one with a
   *     different width means dropping and rebuilding the collection.
   */
  public record Qdrant(
      String url,
      String apiKey,
      String chunkCollection,
      String docCollection,
      int vectorSize) {}

  /** @param legacyStore path to the Node implementation's {@code knowledge.json} */
  public record Migration(String legacyStore, boolean enabled) {}
}
