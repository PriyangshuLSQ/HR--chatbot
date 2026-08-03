package com.leadsquared.hr.knowledge.model;

/**
 * A passage that matched, with the signals behind it kept separate.
 *
 * @param score blended 0-1 score, the one that ranks
 * @param dense rescaled semantic contribution — 0 when the passage has no vector
 * @param lexical BM25 contribution
 */
public record RetrievedChunk(
    KnowledgeChunk chunk, double score, double dense, double lexical, KnowledgeDoc doc) {

  /** Document title and heading trail, as shown above a cited passage. */
  public String label() {
    StringBuilder out = new StringBuilder();
    if (doc != null && doc.title() != null && !doc.title().isBlank()) out.append(doc.title());
    for (String heading : chunk.headings()) {
      if (heading == null || heading.isBlank()) continue;
      if (!out.isEmpty()) out.append(" › ");
      out.append(heading);
    }
    return out.toString();
  }
}
