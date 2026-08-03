package com.leadsquared.hr.knowledge.model;

/**
 * One thing HR uploaded or typed in: a policy .docx, a .csv of FAQs, a note.
 *
 * <p>Field names are the wire contract with {@code lib/knowledge/api.ts} — the
 * admin console reads every one of them, so they are not free to rename.
 *
 * @param uploadedAt ISO-8601 instant, sorted lexicographically by the console
 * @param embeddingModel model used for this document's vectors, or null when it
 *     was ingested with no embedding model available and is lexical-only
 * @param preview first 240 characters, so HR can sanity-check what was extracted
 */
public record KnowledgeDoc(
    String id,
    String title,
    SourceKind kind,
    String category,
    String filename,
    long bytes,
    String uploadedAt,
    String uploadedBy,
    int chunkCount,
    String embeddingModel,
    String preview) {

  public KnowledgeDoc withEmbeddingModel(String model) {
    return new KnowledgeDoc(
        id, title, kind, category, filename, bytes, uploadedAt, uploadedBy, chunkCount, model,
        preview);
  }
}
