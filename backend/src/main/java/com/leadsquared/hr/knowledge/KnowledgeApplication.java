package com.leadsquared.hr.knowledge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * HR knowledge base backend.
 *
 * <p>Serves the five endpoints the Next.js admin console and chat window call:
 * document CRUD, upload, reindex, grounded answering and AI status. The browser
 * still talks to relative {@code /api/...} paths — {@code next.config.mjs}
 * rewrites them here — so nothing in the frontend knows this moved to Java.
 *
 * <p>Two external services, both optional in different ways:
 * <ul>
 *   <li><b>Qdrant</b> holds documents, passages and their vectors. Required.</li>
 *   <li><b>Ollama</b> provides embeddings and answer writing. If it is absent the
 *       product degrades to BM25 keyword search with verbatim passages rather
 *       than failing — the same three-mode behaviour the Node version had.</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class KnowledgeApplication {

  public static void main(String[] args) {
    SpringApplication.run(KnowledgeApplication.class, args);
  }
}
