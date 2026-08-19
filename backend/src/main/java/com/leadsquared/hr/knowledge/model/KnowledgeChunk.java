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
    /**
     * The document this chunk came from, by name, folded into {@link #embeddingText()}.
     *
     * <p>Not persisted and not shown: it is attached when the snapshot is assembled, from the doc
     * the chunk already belongs to. Kept out of {@link #headings} on purpose — that list is the
     * citation trail, and the console already prints the title beside it, so putting it there
     * would render "Car Lease Policy › Car Lease Policy › ENTITLEMENT".
     *
     * <p>Why it matters: a PDF has no heading structure to recover, so its chunks embed as bare
     * text. The car-lease entitlement table names grades and rupee amounts and never once says
     * "car" or "lease" — the words are only in the filename — so "car lease entitlement for grade
     * X4" could not retrieve the one passage that answers it, while a paragraph *about* the
     * entitlement scored 0.79. A document's own name is context every one of its passages has.
     */
    String docTitle,
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
    return of(id, docId, index, headings, text, null, embedding);
  }

  public static KnowledgeChunk of(
      String id,
      String docId,
      int index,
      List<String> headings,
      String text,
      String docTitle,
      List<Float> embedding) {
    List<String> tokens = TextPipeline.tokenize(embeddingText(docTitle, headings, text));
    return new KnowledgeChunk(
        id,
        docId,
        index,
        List.copyOf(headings),
        text,
        docTitle,
        embedding,
        TextPipeline.termFrequencies(tokens),
        tokens.size());
  }

  public KnowledgeChunk withEmbedding(List<Float> vector) {
    return new KnowledgeChunk(id, docId, index, headings, text, docTitle, vector, tf, length);
  }

  /**
   * The same chunk, told which document it belongs to.
   *
   * <p>Rebuilt through {@link #of} rather than copied, because the title joins the text BM25 is
   * scored over — {@code tf} and {@code length} have to be recomputed or the lexical index would
   * disagree with the embedded text.
   */
  public KnowledgeChunk withDocTitle(String title) {
    if (title == null || title.isBlank() || title.equals(docTitle)) return this;
    return of(id, docId, index, headings, text, title, embedding);
  }

  public boolean isEmbedded() {
    return embedding != null && !embedding.isEmpty();
  }

  /** The text that gets embedded — document title and heading trail included, same as the index. */
  public String embeddingText() {
    return embeddingText(docTitle, headings, text);
  }

  private static String embeddingText(String docTitle, List<String> headings, String text) {
    StringBuilder out = new StringBuilder();
    if (docTitle != null && !docTitle.isBlank()) out.append(docTitle).append('\n');
    if (!headings.isEmpty()) out.append(String.join(" › ", headings)).append('\n');
    return out.append(text).toString();
  }
}
