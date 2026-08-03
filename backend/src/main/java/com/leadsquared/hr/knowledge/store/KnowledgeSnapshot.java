package com.leadsquared.hr.knowledge.store;

import com.leadsquared.hr.knowledge.model.KnowledgeChunk;
import com.leadsquared.hr.knowledge.model.KnowledgeDoc;
import com.leadsquared.hr.knowledge.model.StoreStats;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * An immutable view of the corpus as the lexical layer sees it.
 *
 * <p>Chunks here never carry vectors — those live in Qdrant and are searched
 * there. What the JVM holds is the text and term frequencies BM25 needs, plus
 * {@code embeddedIds} so the console can report how much of the corpus is still
 * waiting on a rebuild.
 */
public record KnowledgeSnapshot(
    List<KnowledgeDoc> docs, List<KnowledgeChunk> chunks, Set<String> embeddedIds) {

  public static final KnowledgeSnapshot EMPTY = new KnowledgeSnapshot(List.of(), List.of(), Set.of());

  public StoreStats stats() {
    long bytes = docs.stream().mapToLong(KnowledgeDoc::bytes).sum();
    return new StoreStats(
        docs.size(), chunks.size(), embeddedIds.size(), chunks.size() - embeddedIds.size(), bytes);
  }

  /** Newest first — the order the admin console lists documents in. */
  public List<KnowledgeDoc> docsNewestFirst() {
    return docs.stream()
        .sorted(Comparator.comparing(KnowledgeDoc::uploadedAt, Comparator.nullsLast(Comparator.reverseOrder())))
        .toList();
  }

  public Map<String, KnowledgeDoc> docById() {
    return docs.stream().collect(Collectors.toMap(KnowledgeDoc::id, Function.identity(), (a, b) -> a));
  }

  public boolean isEmbedded(KnowledgeChunk chunk) {
    return embeddedIds.contains(chunk.id());
  }
}
