package com.leadsquared.hr.knowledge.store;

import com.leadsquared.hr.knowledge.config.KnowledgeProperties;
import com.leadsquared.hr.knowledge.model.KnowledgeChunk;
import com.leadsquared.hr.knowledge.model.KnowledgeDoc;
import com.leadsquared.hr.knowledge.model.SourceKind;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Qdrant persistence — the vector database layer.
 *
 * <p>Two collections:
 *
 * <ul>
 *   <li>{@code hr_chunks} — one point per passage, carrying a named {@code dense}
 *       vector and the passage payload. Points may be stored <b>without</b> a
 *       vector: that is a passage ingested while Ollama was offline. Qdrant keeps
 *       them, scroll returns them (so they stay keyword-searchable) and vector
 *       search skips them, which is exactly the degraded mode the product wants.
 *   <li>{@code hr_docs} — document metadata. It declares a vector so the schema is
 *       valid, but never populates one; these points exist only to be scrolled.
 * </ul>
 *
 * <p>Point IDs must be UUIDs or unsigned integers, and our natural keys are
 * strings like {@code doc_ab12#3}. Hashing them to a deterministic UUID keeps
 * upserts idempotent — re-ingesting the same chunk id overwrites rather than
 * duplicates — and the original id travels in the payload.
 */
@Component
public class QdrantStore {

  private static final Logger log = LoggerFactory.getLogger(QdrantStore.class);

  /** The named vector on the chunk collection. */
  private static final String DENSE = "dense";
  /** Qdrant caps a scroll page; anything larger is paginated by the offset cursor. */
  private static final int SCROLL_PAGE = 256;

  private final RestClient qdrant;
  private final String chunkCollection;
  private final String docCollection;
  private final int vectorSize;

  public QdrantStore(RestClient qdrantClient, KnowledgeProperties props) {
    this.qdrant = qdrantClient;
    this.chunkCollection = props.qdrant().chunkCollection();
    this.docCollection = props.qdrant().docCollection();
    this.vectorSize = props.qdrant().vectorSize();
  }

  // -------------------------------------------------------------------------
  // Schema
  // -------------------------------------------------------------------------

  /** Creates the collections if they are missing. Safe to call on every boot. */
  public void ensureCollections() {
    createCollection(chunkCollection, DENSE, vectorSize);
    // Size 1 is a placeholder: this collection is never vector-searched, but
    // Qdrant requires a vector configuration to create one.
    createCollection(docCollection, "meta", 1);

    // Deleting a document removes its passages by filter, which wants an index.
    createPayloadIndex(chunkCollection, "docId");
  }

  private void createCollection(String name, String vectorName, int size) {
    if (collectionExists(name)) return;

    Map<String, Object> vector = Map.of("size", size, "distance", "Cosine");
    try {
      qdrant
          .put()
          .uri("/collections/{name}", name)
          .body(Map.of("vectors", Map.of(vectorName, vector)))
          .retrieve()
          .toBodilessEntity();
      log.info("Created Qdrant collection {} ({}-dim '{}')", name, size, vectorName);
    } catch (RuntimeException e) {
      // A racing instance may have created it between the check and the call.
      if (!collectionExists(name)) throw e;
    }
  }

  private boolean collectionExists(String name) {
    try {
      qdrant.get().uri("/collections/{name}", name).retrieve().toBodilessEntity();
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  private void createPayloadIndex(String collection, String field) {
    try {
      qdrant
          .put()
          .uri("/collections/{c}/index?wait=true", collection)
          .body(Map.of("field_name", field, "field_schema", "keyword"))
          .retrieve()
          .toBodilessEntity();
    } catch (RuntimeException e) {
      log.debug("Payload index on {}.{} already present", collection, field);
    }
  }

  // -------------------------------------------------------------------------
  // Writes
  // -------------------------------------------------------------------------

  public void upsertDoc(KnowledgeDoc doc) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", doc.id());
    payload.put("title", doc.title());
    payload.put("kind", doc.kind().wire());
    payload.put("category", doc.category());
    payload.put("filename", doc.filename());
    payload.put("bytes", doc.bytes());
    payload.put("uploadedAt", doc.uploadedAt());
    payload.put("uploadedBy", doc.uploadedBy());
    payload.put("chunkCount", doc.chunkCount());
    payload.put("embeddingModel", doc.embeddingModel());
    payload.put("preview", doc.preview());

    Map<String, Object> point = new LinkedHashMap<>();
    point.put("id", pointId(doc.id()));
    point.put("vector", Map.of()); // metadata only — never vector-searched
    point.put("payload", payload);

    upsert(docCollection, List.of(point));
  }

  public void upsertChunks(List<KnowledgeChunk> chunks) {
    if (chunks.isEmpty()) return;

    List<Map<String, Object>> points = new ArrayList<>(chunks.size());
    for (KnowledgeChunk chunk : chunks) {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("chunkId", chunk.id());
      payload.put("docId", chunk.docId());
      payload.put("index", chunk.index());
      payload.put("headings", chunk.headings());
      payload.put("text", chunk.text());
      // Mirrors vector presence so the lexical scroll can report pending work
      // without paying to transfer every vector back out of the database.
      payload.put("embedded", chunk.isEmbedded());

      Map<String, Object> point = new LinkedHashMap<>();
      point.put("id", pointId(chunk.id()));
      point.put("vector", chunk.isEmbedded() ? Map.of(DENSE, chunk.embedding()) : Map.of());
      point.put("payload", payload);
      points.add(point);
    }

    upsert(chunkCollection, points);
  }

  /** Attaches freshly computed vectors to chunks already stored. */
  public void applyEmbeddings(Map<String, List<Float>> vectors) {
    if (vectors.isEmpty()) return;

    List<Map<String, Object>> points = new ArrayList<>(vectors.size());
    List<Map<String, Object>> flags = new ArrayList<>(vectors.size());

    for (Map.Entry<String, List<Float>> entry : vectors.entrySet()) {
      String id = pointId(entry.getKey());
      points.add(Map.of("id", id, "vector", Map.of(DENSE, entry.getValue())));
      flags.add(Map.of("id", id));
    }

    qdrant
        .put()
        .uri("/collections/{c}/points/vectors?wait=true", chunkCollection)
        .body(Map.of("points", points))
        .retrieve()
        .toBodilessEntity();

    // Keep the payload flag consistent with the vector we just wrote.
    qdrant
        .post()
        .uri("/collections/{c}/points/payload?wait=true", chunkCollection)
        .body(
            Map.of(
                "payload",
                Map.of("embedded", true),
                "points",
                flags.stream().map(p -> p.get("id")).toList()))
        .retrieve()
        .toBodilessEntity();
  }

  private void upsert(String collection, List<Map<String, Object>> points) {
    qdrant
        .put()
        .uri("/collections/{c}/points?wait=true", collection)
        .body(Map.of("points", points))
        .retrieve()
        .toBodilessEntity();
  }

  /** Removes a document and every passage belonging to it. */
  public void deleteDoc(String docId) {
    qdrant
        .post()
        .uri("/collections/{c}/points/delete?wait=true", chunkCollection)
        .body(Map.of("filter", Map.of("must", List.of(match("docId", docId)))))
        .retrieve()
        .toBodilessEntity();

    qdrant
        .post()
        .uri("/collections/{c}/points/delete?wait=true", docCollection)
        .body(Map.of("points", List.of(pointId(docId))))
        .retrieve()
        .toBodilessEntity();
  }

  private static Map<String, Object> match(String key, String value) {
    return Map.of("key", key, "match", Map.of("value", value));
  }

  // -------------------------------------------------------------------------
  // Reads
  // -------------------------------------------------------------------------

  public List<KnowledgeDoc> loadDocs() {
    List<KnowledgeDoc> docs = new ArrayList<>();
    for (ScrollPoint point : scrollAll(docCollection)) {
      Map<String, Object> p = point.payload();
      if (p == null || p.get("id") == null) continue;
      docs.add(
          new KnowledgeDoc(
              str(p, "id"),
              str(p, "title"),
              SourceKind.fromWire(str(p, "kind")),
              str(p, "category"),
              str(p, "filename"),
              num(p, "bytes"),
              str(p, "uploadedAt"),
              str(p, "uploadedBy"),
              (int) num(p, "chunkCount"),
              str(p, "embeddingModel"),
              str(p, "preview")));
    }
    return docs;
  }

  /**
   * Loads every passage <b>without</b> its vector.
   *
   * <p>The vectors deliberately stay in Qdrant: dense retrieval is Qdrant's job,
   * and the in-process copy only needs text and term frequencies for BM25. On a
   * 768-dimension model that is the difference between holding 3 KB per passage
   * in the JVM heap and holding none of it.
   */
  public List<LoadedChunk> loadChunks() {
    List<LoadedChunk> chunks = new ArrayList<>();

    for (ScrollPoint point : scrollAll(chunkCollection)) {
      Map<String, Object> p = point.payload();
      if (p == null || p.get("chunkId") == null) continue;

      @SuppressWarnings("unchecked")
      List<String> headings =
          p.get("headings") instanceof List<?> raw
              ? raw.stream().map(String::valueOf).toList()
              : List.<String>of();

      KnowledgeChunk chunk =
          KnowledgeChunk.of(
              str(p, "chunkId"),
              str(p, "docId"),
              (int) num(p, "index"),
              headings,
              str(p, "text"),
              null);

      chunks.add(new LoadedChunk(chunk, Boolean.TRUE.equals(p.get("embedded"))));
    }

    chunks.sort(
        java.util.Comparator.comparing((LoadedChunk c) -> c.chunk().docId())
            .thenComparingInt(c -> c.chunk().index()));
    return chunks;
  }

  /** A passage as stored, plus whether Qdrant currently holds a vector for it. */
  public record LoadedChunk(KnowledgeChunk chunk, boolean embedded) {}

  /**
   * Approximate nearest neighbours for a query vector.
   *
   * <p>{@code scoreThreshold} is applied by Qdrant so passages that are merely
   * "not unrelated" never cross the wire — embedding models put unrelated
   * sentences around 0.3 cosine, not 0.
   *
   * @return chunk id to raw cosine similarity
   */
  public Map<String, Double> searchDense(List<Float> queryVector, int limit, double scoreThreshold) {
    SearchResponse response =
        qdrant
            .post()
            .uri("/collections/{c}/points/search", chunkCollection)
            .body(
                Map.of(
                    "vector", Map.of("name", DENSE, "vector", queryVector),
                    "limit", limit,
                    "score_threshold", scoreThreshold,
                    "with_payload", Map.of("include", List.of("chunkId"))))
            .retrieve()
            .body(SearchResponse.class);

    if (response == null || response.result() == null) return Map.of();

    Map<String, Double> scores = new LinkedHashMap<>();
    for (ScoredPoint hit : response.result()) {
      if (hit.payload() == null) continue;
      Object id = hit.payload().get("chunkId");
      if (id != null) scores.put(String.valueOf(id), hit.score());
    }
    return scores;
  }

  // -------------------------------------------------------------------------
  // Scroll
  // -------------------------------------------------------------------------

  private List<ScrollPoint> scrollAll(String collection) {
    List<ScrollPoint> all = new ArrayList<>();
    Object offset = null;

    while (true) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("limit", SCROLL_PAGE);
      body.put("with_payload", true);
      body.put("with_vector", false);
      if (offset != null) body.put("offset", offset);

      ScrollResponse response =
          qdrant
              .post()
              .uri("/collections/{c}/points/scroll", collection)
              .body(body)
              .retrieve()
              .body(ScrollResponse.class);

      if (response == null || response.result() == null) break;
      if (response.result().points() != null) all.addAll(response.result().points());

      offset = response.result().nextPageOffset();
      if (offset == null) break;
    }

    return Collections.unmodifiableList(all);
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  /** Deterministic UUID for a natural key, so upserts overwrite instead of duplicating. */
  private static String pointId(String naturalKey) {
    return UUID.nameUUIDFromBytes(naturalKey.getBytes(StandardCharsets.UTF_8)).toString();
  }

  private static String str(Map<String, Object> payload, String key) {
    Object value = payload.get(key);
    return value == null ? null : String.valueOf(value);
  }

  private static long num(Map<String, Object> payload, String key) {
    Object value = payload.get(key);
    return value instanceof Number n ? n.longValue() : 0L;
  }

  // -------------------------------------------------------------------------
  // Wire shapes
  // -------------------------------------------------------------------------

  private record ScrollResponse(Result result) {
    private record Result(
        List<ScrollPoint> points,
        @com.fasterxml.jackson.annotation.JsonProperty("next_page_offset") Object nextPageOffset) {}
  }

  private record ScrollPoint(Object id, Map<String, Object> payload) {}

  private record SearchResponse(List<ScoredPoint> result) {}

  private record ScoredPoint(Object id, double score, Map<String, Object> payload) {}
}
