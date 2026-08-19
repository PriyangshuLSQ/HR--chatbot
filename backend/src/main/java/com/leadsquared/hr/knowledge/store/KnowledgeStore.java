package com.leadsquared.hr.knowledge.store;

import com.leadsquared.hr.knowledge.model.KnowledgeChunk;
import com.leadsquared.hr.knowledge.model.KnowledgeDoc;
import com.leadsquared.hr.knowledge.model.SourceKind;
import com.leadsquared.hr.knowledge.model.StoreStats;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The knowledge base, as the rest of the application sees it.
 *
 * <p>Qdrant is the source of truth. This holds a read-through snapshot of
 * everything except the vectors, because the lexical half of retrieval needs
 * corpus-wide statistics (IDF over every passage) that no per-query database
 * call can provide cheaply.
 *
 * <p>Writes are serialised and refresh the snapshot atomically. Two uploads
 * landing together would otherwise both compute their supersede set from the
 * same stale view, and the second would resurrect a document the first replaced.
 */
@Service
public class KnowledgeStore {

  private static final Logger log = LoggerFactory.getLogger(KnowledgeStore.class);

  private final QdrantStore qdrant;
  private final Object writeLock = new Object();

  private volatile KnowledgeSnapshot snapshot = KnowledgeSnapshot.EMPTY;

  public KnowledgeStore(QdrantStore qdrant) {
    this.qdrant = qdrant;
  }

  @PostConstruct
  void start() {
    qdrant.ensureCollections();
    refresh();
    StoreStats stats = snapshot.stats();
    log.info(
        "Knowledge base ready: {} document(s), {} passage(s), {} embedded",
        stats.docCount(),
        stats.chunkCount(),
        stats.embeddedChunks());
  }

  public KnowledgeSnapshot snapshot() {
    return snapshot;
  }

  public StoreStats stats() {
    return snapshot.stats();
  }

  public List<KnowledgeDoc> listDocs() {
    return snapshot.docsNewestFirst();
  }

  /**
   * Stores a document and its passages.
   *
   * <p>Re-uploading the same filename <b>replaces</b> the previous version rather
   * than stacking a stale copy that competes with the current one in retrieval.
   * Typed-in entries are exempt: they all share the filename "Typed in the
   * console" and are genuinely separate documents.
   */
  public void addDoc(KnowledgeDoc doc, List<KnowledgeChunk> chunks) {
    synchronized (writeLock) {
      if (doc.kind() != SourceKind.MANUAL) {
        for (KnowledgeDoc existing : snapshot.docs()) {
          if (existing.filename().equals(doc.filename())
              && existing.kind() == doc.kind()
              && existing.kind() != SourceKind.MANUAL) {
            log.info("Replacing previous version of {} ({})", existing.filename(), existing.id());
            qdrant.deleteDoc(existing.id());
          }
        }
      }

      qdrant.upsertChunks(chunks);
      qdrant.upsertDoc(doc);
      refresh();
    }
  }

  public boolean removeDoc(String docId) {
    synchronized (writeLock) {
      boolean exists = snapshot.docs().stream().anyMatch(d -> d.id().equals(docId));
      if (!exists) return false;

      qdrant.deleteDoc(docId);
      refresh();
      return true;
    }
  }

  /**
   * Attaches freshly computed vectors and records which model produced them.
   *
   * @return how many passages actually gained a vector
   */
  public int applyEmbeddings(Map<String, List<Float>> vectors, String model) {
    if (vectors.isEmpty()) return 0;

    synchronized (writeLock) {
      qdrant.applyEmbeddings(vectors);

      // Stamp the model onto every document that gained a vector, so the console
      // can show what a given document was embedded with.
      Set<String> touchedDocs = new LinkedHashSet<>();
      for (KnowledgeChunk chunk : snapshot.chunks()) {
        if (vectors.containsKey(chunk.id())) touchedDocs.add(chunk.docId());
      }
      for (KnowledgeDoc doc : snapshot.docs()) {
        if (touchedDocs.contains(doc.id())) qdrant.upsertDoc(doc.withEmbeddingModel(model));
      }

      refresh();
      return vectors.size();
    }
  }

  /** Re-reads documents and passages from Qdrant. */
  public void refresh() {
    List<KnowledgeDoc> docs = qdrant.loadDocs();
    List<QdrantStore.LoadedChunk> loaded = qdrant.loadChunks();

    // The title is attached here rather than stored on the chunk in Qdrant, because it is
    // already stored — on the document — and duplicating it would let a renamed document keep an
    // old name in its own passages. It matters because it joins the text each chunk is embedded
    // and keyword-scored over: a PDF loses its headings, so without this a passage carries no
    // trace of which policy it came from. See KnowledgeChunk.docTitle.
    Map<String, String> titles = new HashMap<>();
    for (KnowledgeDoc doc : docs) titles.put(doc.id(), doc.title());

    List<KnowledgeChunk> chunks = new ArrayList<>(loaded.size());
    Set<String> embedded = new HashSet<>();
    for (QdrantStore.LoadedChunk entry : loaded) {
      chunks.add(entry.chunk().withDocTitle(titles.get(entry.chunk().docId())));
      if (entry.embedded()) embedded.add(entry.chunk().id());
    }

    snapshot = new KnowledgeSnapshot(List.copyOf(docs), List.copyOf(chunks), Set.copyOf(embedded));
  }
}
