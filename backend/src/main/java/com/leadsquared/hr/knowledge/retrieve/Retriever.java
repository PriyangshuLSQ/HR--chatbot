package com.leadsquared.hr.knowledge.retrieve;

import com.leadsquared.hr.knowledge.model.KnowledgeChunk;
import com.leadsquared.hr.knowledge.model.KnowledgeDoc;
import com.leadsquared.hr.knowledge.model.RetrievedChunk;
import com.leadsquared.hr.knowledge.store.KnowledgeSnapshot;
import com.leadsquared.hr.knowledge.text.TextPipeline;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Hybrid retrieval over the HR knowledge base.
 *
 * <p>Two signals, blended:
 *
 * <ul>
 *   <li><b>Dense</b> — cosine over the embeddings, computed by Qdrant. Catches
 *       meaning: "Can I take time off to look after my newborn?" finds the
 *       paternity section even though it shares no keywords with it.
 *   <li><b>Lexical</b> — BM25, computed here. Catches exact terms. Dense models
 *       routinely fumble "Form 16", "80C" and "₹5,00,000" — the tokens that
 *       matter most in HR policy — because they are rare and numeric.
 * </ul>
 *
 * <p>Either signal works alone. With no embeddings (Ollama absent, or a corpus
 * uploaded before a model was installed) this is a competent BM25 search, which
 * is why upload and chat still work on a machine that has never seen an LLM.
 */
@Component
public class Retriever {

  /** Standard BM25 constants: term-frequency saturation and length normalisation. */
  private static final double BM25_K1 = 1.4;
  private static final double BM25_B = 0.72;

  /**
   * Converts an unbounded BM25 score to 0-1. Chosen so a solid multi-term match
   * lands near 0.6-0.8 and a single incidental term match stays under 0.3 — the
   * range the relevance threshold is calibrated against.
   */
  private static final double BM25_SATURATION = 7;

  /** Cosine below this is noise for {@code nomic-embed-text}-class models. */
  public static final double COSINE_FLOOR = 0.32;
  /** Cosine at or above this is treated as a full-strength semantic match. */
  private static final double COSINE_CEILING = 0.82;

  private static final double DENSE_WEIGHT = 0.62;
  private static final double LEXICAL_WEIGHT = 0.38;

  /** Never return more than this many chunks from one document. */
  private static final int MAX_PER_DOC = 3;

  /** Scores below this are noise and never reach the ranking. */
  private static final double SCORE_FLOOR = 0.02;

  /**
   * @param usedEmbeddings true when dense vectors actually contributed; false
   *     means this was a BM25-only search
   */
  public record Result(List<RetrievedChunk> hits, double confidence, boolean usedEmbeddings) {
    public static final Result EMPTY = new Result(List.of(), 0, false);

    public boolean isEmpty() {
      return hits.isEmpty();
    }
  }

  /**
   * Corpus-wide lexical statistics.
   *
   * <p>Cached against the snapshot that produced it: IDF depends only on the
   * corpus, and rebuilding it on every query was affordable at 22 passages but
   * is the first thing to hurt as the knowledge base grows.
   */
  private record LexicalIndex(Map<String, Double> idf, double averageLength) {}

  private volatile KnowledgeSnapshot indexedSnapshot;
  private volatile LexicalIndex lexicalIndex;

  /**
   * @param denseScores raw cosine similarity by chunk id, as returned by Qdrant.
   *     Empty when no query vector was available.
   * @param hasQueryVector whether the question itself could be embedded — the
   *     blend weights only apply when both sides have a vector
   */
  public Result retrieve(
      String query,
      KnowledgeSnapshot snapshot,
      Map<String, Double> denseScores,
      boolean hasQueryVector,
      int topK) {

    List<KnowledgeChunk> chunks = snapshot.chunks();
    if (chunks.isEmpty()) return Result.EMPTY;

    List<String> queryTokens = TextPipeline.tokenize(query);
    if (queryTokens.isEmpty() && !hasQueryVector) return Result.EMPTY;

    LexicalIndex index = indexFor(snapshot);
    Map<String, KnowledgeDoc> docById = snapshot.docById();
    Set<String> uniqueTokens = new LinkedHashSet<>(queryTokens);

    List<RetrievedChunk> scored = new ArrayList<>(chunks.size());
    boolean anyDense = false;

    for (KnowledgeChunk chunk : chunks) {
      double lexical = bm25(uniqueTokens, chunk, index);

      double dense = 0;
      boolean chunkEmbedded = snapshot.isEmbedded(chunk);
      if (hasQueryVector && chunkEmbedded) {
        dense = rescaleCosine(denseScores.getOrDefault(chunk.id(), 0.0));
        if (dense > 0) anyDense = true;
      }

      // With no vector on either side, lexical carries the full weight rather
      // than being scaled to 38% of a score it is the only contributor to.
      double score =
          hasQueryVector && chunkEmbedded
              ? DENSE_WEIGHT * dense + LEXICAL_WEIGHT * lexical
              : lexical;

      if (score <= SCORE_FLOOR) continue;
      scored.add(new RetrievedChunk(chunk, score, dense, lexical, docById.get(chunk.docId())));
    }

    scored.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed());
    List<RetrievedChunk> hits = diversify(scored, topK);

    return new Result(hits, confidenceOf(hits, uniqueTokens), anyDense);
  }

  // -------------------------------------------------------------------------
  // Scoring
  // -------------------------------------------------------------------------

  private LexicalIndex indexFor(KnowledgeSnapshot snapshot) {
    LexicalIndex cached = lexicalIndex;
    if (cached != null && indexedSnapshot == snapshot) return cached;

    LexicalIndex built = buildIndex(snapshot.chunks());
    indexedSnapshot = snapshot;
    lexicalIndex = built;
    return built;
  }

  /** Inverse document frequency across chunks, so "leave" counts less than "gratuity". */
  private static LexicalIndex buildIndex(List<KnowledgeChunk> chunks) {
    Map<String, Integer> df = new HashMap<>();
    long totalLength = 0;

    for (KnowledgeChunk chunk : chunks) {
      totalLength += chunk.length();
      for (String term : chunk.tf().keySet()) df.merge(term, 1, Integer::sum);
    }

    int n = chunks.size();
    Map<String, Double> idf = new HashMap<>(df.size() * 2);
    for (Map.Entry<String, Integer> entry : df.entrySet()) {
      // Standard BM25 IDF, floored so a term appearing in most chunks still
      // contributes a little rather than going negative.
      double freq = entry.getValue();
      idf.put(entry.getKey(), Math.max(0.05, Math.log(1 + (n - freq + 0.5) / (freq + 0.5))));
    }

    return new LexicalIndex(idf, chunks.isEmpty() ? 0 : (double) totalLength / chunks.size());
  }

  private static double bm25(Set<String> queryTerms, KnowledgeChunk chunk, LexicalIndex index) {
    double raw = 0;
    double norm = 1 - BM25_B + BM25_B * (chunk.length() / Math.max(1, index.averageLength()));

    for (String term : queryTerms) {
      Integer tf = chunk.tf().get(term);
      if (tf == null) continue;

      double weight = index.idf().getOrDefault(term, 1.2);
      raw += weight * ((tf * (BM25_K1 + 1)) / (tf + BM25_K1 * norm));
    }

    return raw / (raw + BM25_SATURATION);
  }

  /**
   * Embedding models put unrelated sentences around 0.3-0.4 cosine, not 0 — so
   * raw cosine massively overstates relevance. Rescaling against an observed
   * floor and ceiling is what makes the confidence threshold meaningful.
   */
  private static double rescaleCosine(double cosine) {
    if (cosine <= COSINE_FLOOR) return 0;
    return Math.min(1, (cosine - COSINE_FLOOR) / (COSINE_CEILING - COSINE_FLOOR));
  }

  /** Caps how many chunks any one document can contribute to the result set. */
  private static List<RetrievedChunk> diversify(List<RetrievedChunk> sorted, int topK) {
    Map<String, Integer> perDoc = new HashMap<>();
    List<RetrievedChunk> out = new ArrayList<>(topK);

    for (RetrievedChunk hit : sorted) {
      if (out.size() >= topK) break;
      int used = perDoc.getOrDefault(hit.chunk().docId(), 0);
      if (used >= MAX_PER_DOC) continue;
      perDoc.put(hit.chunk().docId(), used + 1);
      out.add(hit);
    }
    return out;
  }

  /**
   * Blends the top score with query-term coverage.
   *
   * <p>Top score alone is a poor guide: a long chunk can score respectably on one
   * incidental keyword. Requiring that the retrieved passages actually contain
   * most of what was asked about is what stops the bot confidently answering a
   * question the knowledge base never covered.
   */
  private static double confidenceOf(List<RetrievedChunk> hits, Set<String> uniqueTokens) {
    if (hits.isEmpty()) return 0;
    double top = hits.get(0).score();
    if (uniqueTokens.isEmpty()) return Math.min(1, top);

    List<RetrievedChunk> pool = hits.subList(0, Math.min(3, hits.size()));
    Set<String> covered = new HashSet<>();
    for (String token : uniqueTokens) {
      for (RetrievedChunk hit : pool) {
        if (hit.chunk().tf().containsKey(token)) {
          covered.add(token);
          break;
        }
      }
    }

    double coverage = (double) covered.size() / uniqueTokens.size();
    return Math.min(1, 0.7 * top + 0.3 * coverage);
  }
}
