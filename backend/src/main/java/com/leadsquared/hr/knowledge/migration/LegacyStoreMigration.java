package com.leadsquared.hr.knowledge.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leadsquared.hr.knowledge.config.KnowledgeProperties;
import com.leadsquared.hr.knowledge.model.KnowledgeChunk;
import com.leadsquared.hr.knowledge.model.KnowledgeDoc;
import com.leadsquared.hr.knowledge.model.SourceKind;
import com.leadsquared.hr.knowledge.store.KnowledgeStore;
import com.leadsquared.hr.knowledge.store.QdrantStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * One-time import of the Node implementation's {@code data/knowledge.json}.
 *
 * <p>That file holds documents, passages <i>and</i> their vectors, so nothing has
 * to be re-embedded — the import is a straight copy into Qdrant. It runs only
 * when the vector database is empty, so a second boot is a no-op and a corpus
 * grown since the migration is never overwritten.
 *
 * <p>The JSON file is left untouched. It is the rollback path.
 */
@Component
public class LegacyStoreMigration implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(LegacyStoreMigration.class);

  private final KnowledgeProperties props;
  private final KnowledgeStore store;
  private final QdrantStore qdrant;
  private final ObjectMapper mapper;

  public LegacyStoreMigration(
      KnowledgeProperties props, KnowledgeStore store, QdrantStore qdrant, ObjectMapper mapper) {
    this.props = props;
    this.store = store;
    this.qdrant = qdrant;
    this.mapper = mapper;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!props.migration().enabled()) return;

    if (store.stats().chunkCount() > 0) {
      log.debug("Qdrant already holds passages — skipping legacy import");
      return;
    }

    Path path = Path.of(props.migration().legacyStore());
    if (!Files.isReadable(path)) {
      log.info("No legacy store at {} — starting with an empty knowledge base", path.toAbsolutePath());
      return;
    }

    try {
      LegacyStore legacy = mapper.readValue(path.toFile(), LegacyStore.class);
      importInto(legacy, path);
    } catch (IOException e) {
      // A malformed legacy file must not stop the service from starting; the
      // knowledge base simply starts empty and HR can re-upload.
      log.error("Could not read the legacy store at {} — starting empty", path.toAbsolutePath(), e);
    }
  }

  private void importInto(LegacyStore legacy, Path path) {
    if (legacy == null || legacy.chunks() == null || legacy.chunks().isEmpty()) {
      log.info("Legacy store at {} has no passages — nothing to import", path.toAbsolutePath());
      return;
    }

    List<KnowledgeChunk> chunks = new ArrayList<>(legacy.chunks().size());
    int withVectors = 0;

    for (LegacyChunk chunk : legacy.chunks()) {
      if (chunk.id() == null || chunk.text() == null) continue;
      boolean hasVector = chunk.embedding() != null && !chunk.embedding().isEmpty();
      if (hasVector) withVectors++;

      // tf and length are recomputed from the text rather than copied: the Java
      // tokeniser is what queries will run through, so the stored term maps must
      // come from it too.
      chunks.add(
          KnowledgeChunk.of(
              chunk.id(),
              chunk.docId(),
              chunk.index(),
              chunk.headings() == null ? List.of() : chunk.headings(),
              chunk.text(),
              hasVector ? chunk.embedding() : null));
    }

    qdrant.upsertChunks(chunks);

    int docCount = 0;
    if (legacy.docs() != null) {
      for (LegacyDoc doc : legacy.docs()) {
        if (doc.id() == null) continue;
        qdrant.upsertDoc(
            new KnowledgeDoc(
                doc.id(),
                doc.title(),
                SourceKind.fromWire(doc.kind()),
                doc.category(),
                doc.filename(),
                doc.bytes(),
                doc.uploadedAt(),
                doc.uploadedBy(),
                doc.chunkCount(),
                doc.embeddingModel(),
                doc.preview()));
        docCount++;
      }
    }

    store.refresh();
    log.info(
        "Imported {} document(s) and {} passage(s) ({} with vectors) from {}",
        docCount,
        chunks.size(),
        withVectors,
        path.toAbsolutePath());
  }

  // -------------------------------------------------------------------------
  // The on-disk shape written by lib/knowledge/store.ts
  // -------------------------------------------------------------------------

  private record LegacyStore(int version, List<LegacyDoc> docs, List<LegacyChunk> chunks) {}

  private record LegacyDoc(
      String id,
      String title,
      String kind,
      String category,
      String filename,
      long bytes,
      String uploadedAt,
      String uploadedBy,
      int chunkCount,
      String embeddingModel,
      String preview) {}

  private record LegacyChunk(
      String id, String docId, int index, List<String> headings, String text, List<Float> embedding) {}
}
