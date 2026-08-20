package com.leadsquared.hr.knowledge.web;

import com.leadsquared.hr.knowledge.audit.AdminAuditService;
import com.leadsquared.hr.knowledge.model.AdminAuditEvent;
import com.leadsquared.hr.knowledge.model.KnowledgeDoc;
import com.leadsquared.hr.knowledge.model.StoreStats;
import com.leadsquared.hr.knowledge.parse.UnsupportedFileException;
import com.leadsquared.hr.knowledge.rag.RagService;
import com.leadsquared.hr.knowledge.store.KnowledgeStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Knowledge base CRUD, upload and reindex.
 *
 * <p>The response shapes are a contract with {@code lib/knowledge/api.ts} in the
 * frontend — field names and status codes are what that client parses, so they
 * are not free to change.
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

  private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);

  /**
   * Per-file cap. A 15 MB .docx is already thousands of chunks to embed.
   *
   * <p>Must stay below {@code spring.servlet.multipart.max-file-size}, which is deliberately set
   * higher: Spring rejects an oversized part before this controller ever runs, and its error is a
   * 500-shaped multipart failure rather than the readable "Too large (18.2 MB), the limit is 15 MB"
   * an admin can act on. The headroom is what keeps the message useful.
   */
  private static final long MAX_BYTES = 15L * 1024 * 1024;

  private static final int MAX_FILES = 20;

  private final RagService rag;
  private final KnowledgeStore store;
  private final AdminAuditService audit;

  public KnowledgeController(RagService rag, KnowledgeStore store, AdminAuditService audit) {
    this.rag = rag;
    this.store = store;
    this.audit = audit;
  }

  // -------------------------------------------------------------------------
  // List
  // -------------------------------------------------------------------------

  public record Snapshot(List<KnowledgeDoc> docs, StoreStats stats) {}

  @GetMapping
  public Snapshot list() {
    return new Snapshot(store.listDocs(), store.stats());
  }

  // -------------------------------------------------------------------------
  // Add a typed-in entry (the FAQ form)
  // -------------------------------------------------------------------------

  public record AddEntryRequest(String title, String text, String category, String uploadedBy) {}

  @PostMapping
  public ResponseEntity<?> add(@RequestBody(required = false) AddEntryRequest body) {
    if (body == null) return ApiErrors.badRequest("Expected a JSON body.");

    String title = trimmed(body.title());
    String text = trimmed(body.text());
    if (title.isEmpty() || text.isEmpty()) {
      return ApiErrors.badRequest("Both a title and body text are required.");
    }

    RagService.IngestResult result =
        rag.ingestText(
            title,
            // The title is embedded as a heading so the entry retrieves on its
            // question wording, not just on the answer body.
            "# " + title + "\n\n" + text,
            orDefault(body.category(), "General"),
            orDefault(body.uploadedBy(), "HR admin"));

    audit.record(
        AdminAuditEvent.POLICY_UPLOADED,
        title,
        "Added the entry \"" + title + "\" under " + orDefault(body.category(), "General")
            + " — " + result.chunkCount() + " passage(s) indexed.");

    return ResponseEntity.status(HttpStatus.CREATED).body(result);
  }

  // -------------------------------------------------------------------------
  // Delete
  // -------------------------------------------------------------------------

  @DeleteMapping
  public ResponseEntity<?> delete(@RequestParam(name = "id", required = false) String id) {
    if (id == null || id.isBlank()) return ApiErrors.badRequest("Missing ?id.");

    // Read the title before removing it: afterwards there is nothing left to name in the trail,
    // and "deleted document 4f2c…" is not a reviewable entry.
    String title =
        store.listDocs().stream()
            .filter(d -> id.equals(d.id()))
            .map(KnowledgeDoc::title)
            .findFirst()
            .orElse(id);

    if (!store.removeDoc(id)) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "No such document."));
    }

    audit.record(
        AdminAuditEvent.POLICY_DELETED,
        title,
        "Removed \"" + title + "\" from the knowledge base. The assistant can no longer cite it.");

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("ok", true);
    body.put("stats", store.stats());
    return ResponseEntity.ok(body);
  }

  // -------------------------------------------------------------------------
  // Upload
  // -------------------------------------------------------------------------

  /** Per-file outcome. One bad document in a batch of ten must not lose the other nine. */
  public record UploadOutcome(
      String filename,
      boolean ok,
      String error,
      Integer chunkCount,
      Boolean embedded,
      List<String> notes,
      String docId) {

    static UploadOutcome failed(String filename, String error) {
      return new UploadOutcome(filename, false, error, null, null, null, null);
    }
  }

  public record UploadResponse(List<UploadOutcome> results, StoreStats stats) {}

  @PostMapping("/upload")
  public ResponseEntity<?> upload(
      @RequestParam(name = "files", required = false) List<MultipartFile> files,
      @RequestParam(name = "category", required = false) String category,
      @RequestParam(name = "uploadedBy", required = false) String uploadedBy) {

    if (files == null || files.isEmpty()) {
      return ApiErrors.badRequest("No files were attached.");
    }
    if (files.size() > MAX_FILES) {
      return ApiErrors.badRequest("Too many files at once — upload " + MAX_FILES + " or fewer.");
    }

    String resolvedCategory = orDefault(category, "General");
    String resolvedUploader = orDefault(uploadedBy, "HR admin");
    List<UploadOutcome> results = new ArrayList<>(files.size());

    // Sequential, not parallel: embedding is CPU-bound on a local Ollama, and
    // firing ten batches at once makes every one of them slower.
    for (MultipartFile file : files) {
      String filename = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();

      if (file.getSize() > MAX_BYTES) {
        results.add(
            UploadOutcome.failed(
                filename,
                "Too large (" + formatBytes(file.getSize()) + "). The limit is " + formatBytes(MAX_BYTES) + "."));
        continue;
      }
      if (file.getSize() == 0) {
        results.add(UploadOutcome.failed(filename, "The file is empty."));
        continue;
      }

      try {
        RagService.IngestResult result =
            rag.ingestFile(
                file.getBytes(), filename, file.getContentType(), resolvedCategory, resolvedUploader);

        results.add(
            new UploadOutcome(
                filename,
                true,
                null,
                result.chunkCount(),
                result.embedded(),
                result.notes(),
                result.doc().id()));

      } catch (UnsupportedFileException e) {
        results.add(UploadOutcome.failed(filename, e.getMessage()));
      } catch (IOException | RuntimeException e) {
        log.error("Upload failed for {}", filename, e);
        results.add(UploadOutcome.failed(filename, "Could not read that file. It may be corrupt."));
      }
    }

    // One entry for the batch, naming what landed. Per-file rows would bury an access change
    // under a ten-file drop, and the batch is the action the admin actually took.
    List<String> uploaded = results.stream().filter(UploadOutcome::ok).map(UploadOutcome::filename).toList();
    if (!uploaded.isEmpty()) {
      int failed = results.size() - uploaded.size();
      audit.record(
          AdminAuditEvent.POLICY_UPLOADED,
          String.join(", ", uploaded),
          "Uploaded " + uploaded.size() + " document(s) under " + resolvedCategory + ": "
              + String.join(", ", uploaded)
              + (failed > 0 ? " (" + failed + " rejected)" : ""));
    }

    return ResponseEntity.ok(new UploadResponse(results, store.stats()));
  }

  // -------------------------------------------------------------------------
  // Reindex
  // -------------------------------------------------------------------------

  /**
   * Computes embeddings for chunks that lack them.
   *
   * <p>The recovery path for the common case: HR uploaded documents before
   * installing Ollama, so everything is keyword-searchable but semantically
   * blind. Running this once turns the whole corpus semantic.
   *
   * <p>{@code ?force=1} re-embeds everything, for when the embedding model is
   * changed — vectors from different models are not comparable, and mixing them
   * silently degrades search.
   */
  @PostMapping("/reindex")
  public ResponseEntity<?> reindex(@RequestParam(name = "force", required = false) String force) {
    RagService.ReindexResult result = rag.reindex("1".equals(force));

    audit.record(
        AdminAuditEvent.KNOWLEDGE_REINDEXED,
        result.model(),
        ("1".equals(force) ? "Force re-embedded" : "Embedded") + " " + result.embedded()
            + " passage(s) with " + result.model() + ".");

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("embedded", result.embedded());
    body.put("skipped", result.skipped());
    body.put("model", result.model());
    body.put("message", result.message());
    body.put("stats", store.stats());
    return ResponseEntity.ok(body);
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private static String trimmed(String value) {
    return value == null ? "" : value.trim();
  }

  private static String orDefault(String value, String fallback) {
    String trimmed = trimmed(value);
    return trimmed.isEmpty() ? fallback : trimmed;
  }

  static String formatBytes(long bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return Math.round(bytes / 1024.0) + " KB";
    return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
  }
}
