package com.leadsquared.hr.knowledge.model;

import com.leadsquared.hr.knowledge.text.TextPipeline;
import java.util.List;
import java.util.Map;

/**
 * A retrieval-sized passage of a document.
 *
 * <p>A 40-page leave policy is not a useful retrieval unit; a single section is.
 * Every chunk carries both a dense {@code embedding} (meaning) and the term
 * frequencies behind it (exact terms), which is what lets retrieval fall back to
 * pure keyword search when no embedding model is installed.
 *
 * @param headings the heading trail this chunk sits under, e.g.
 *     {@code ["Leave Policy", "Carry-forward"]}. Prepended to the text before
 *     embedding, so a passage reading "up to 30 days" is still findable.
 * @param embedding null when no embedding model was reachable at ingest time.
 *     Such chunks are stored in Qdrant without a vector and are still keyword
 *     searchable; {@code /api/knowledge/reindex} fills them in later.
 * @param tf term frequencies over {@link #embeddingText()}, for BM25
 * @param length total token count, for BM25 length normalisation
 */
public record KnowledgeChunk(
    String id,
    String docId,
    int index,
    List<String> headings,
    String text,
    List<Float> embedding,
    Map<String, Integer> tf,
    int length) {

  /**
   * Builds a chunk, deriving the lexical index from the text.
   *
   * <p>{@code tf} and {@code length} are always recomputed rather than stored in
   * Qdrant. That keeps the corpus statistics and the query tokens produced by
   * the same {@link TextPipeline} — the thing BM25 actually depends on — and
   * means a change to the tokeniser cannot leave stale term maps behind.
   */
  public static KnowledgeChunk of(
      String id, String docId, int index, List<String> headings, String text, List<Float> embedding) {
    List<String> tokens = TextPipeline.tokenize(embeddingText(headings, text));
    return new KnowledgeChunk(
        id,
        docId,
        index,
        List.copyOf(headings),
        text,
        embedding,
        TextPipeline.termFrequencies(tokens),
        tokens.size());
  }

  public KnowledgeChunk withEmbedding(List<Float> vector) {
    return new KnowledgeChunk(id, docId, index, headings, text, vector, tf, length);
  }

  public boolean isEmbedded() {
    return embedding != null && !embedding.isEmpty();
  }

  /** The text that gets embedded — heading trail included, same as the index. */
  public String embeddingText() {
    return embeddingText(headings, text);
  }

  private static String embeddingText(List<String> headings, String text) {
    return headings.isEmpty() ? text : String.join(" › ", headings) + "\n" + text;
  }
}
