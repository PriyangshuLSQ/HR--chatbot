package com.leadsquared.hr.knowledge.rag;

import com.leadsquared.hr.knowledge.model.Citation;
import com.leadsquared.hr.knowledge.model.KnowledgeAnswer;
import com.leadsquared.hr.knowledge.model.KnowledgeChunk;
import com.leadsquared.hr.knowledge.model.KnowledgeDoc;
import com.leadsquared.hr.knowledge.model.RetrievedChunk;
import com.leadsquared.hr.knowledge.model.SourceKind;
import com.leadsquared.hr.knowledge.claude.ChatMessage;
import com.leadsquared.hr.knowledge.claude.ClaudeClient;
import com.leadsquared.hr.knowledge.ollama.OllamaClient;
import com.leadsquared.hr.knowledge.ollama.OllamaStatus;
import com.leadsquared.hr.knowledge.parse.DocumentParser;
import com.leadsquared.hr.knowledge.parse.ImageTextExtractor;
import com.leadsquared.hr.knowledge.parse.ParsedFile;
import com.leadsquared.hr.knowledge.parse.UnsupportedFileException;
import com.leadsquared.hr.knowledge.retrieve.Retriever;
import com.leadsquared.hr.knowledge.store.KnowledgeSnapshot;
import com.leadsquared.hr.knowledge.store.KnowledgeStore;
import com.leadsquared.hr.knowledge.store.QdrantStore;
import com.leadsquared.hr.knowledge.text.Chunker;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Retrieval-augmented generation over HR's uploaded documents.
 *
 * <p>This is the "actual AI" layer, and the important word is <i>grounded</i>:
 * the model never answers from its own training. It is handed the passages that
 * retrieval selected and told to answer from those alone, or to say it cannot.
 *
 * <p>That is a deliberate choice over fine-tuning. Fine-tuning teaches a model
 * tone, not facts — it would invent plausible-sounding leave allowances, could
 * not cite a source, and would need a retraining run every time HR edited a
 * policy. With RAG, an uploaded document is answerable the moment it lands, and
 * every answer carries the passage it came from.
 */
@Service
public class RagService {

  private static final Logger log = LoggerFactory.getLogger(RagService.class);

  /** How much retrieved text to hand the model. Keeps prompts inside a small model's context. */
  private static final int MAX_CONTEXT_CHARS = 6000;

  /** Passages to pull from Qdrant before fusing with BM25. */
  private static final int DENSE_CANDIDATES = 200;

  /** Passages handed to the model, and cited back to the employee. */
  private static final int TOP_K = 5;

  /** Embedding a large corpus in one request is slow and all-or-nothing. */
  private static final int EMBED_BATCH = 32;

  /**
   * Characters withheld before streamed output starts, so a NOT_IN_DOCUMENTS
   * refusal is never shown to the employee — and long enough to also catch a prose
   * decline, which opens with the give-away ("The provided documents do not contain
   * information about...") but needs more room than the bare token. Costs a beat of
   * latency before the first word appears; showing text and retracting it is worse.
   */
  private static final int REFUSAL_GUARD = 90;

  /**
   * A reply that says the documents do not cover the question, in prose instead of
   * the {@code NOT_IN_DOCUMENTS} token.
   *
   * <p>Asked where the company is located, the model answered "The HR documents do
   * not specify LeadSquared's location. You can check the company's official
   * website" — presented as an answer, with citations, over a 42% match. That is a
   * non-answer dressed as one: it tells the employee nothing and sends them
   * elsewhere, where the escalation path would have offered them a person.
   */
  private static final Pattern NON_ANSWER =
      Pattern.compile(
          "\\b(do(es)?\\s+not\\s+(specify|mention|state|contain|provide|include|say)"
              + "|not\\s+specified"
              + "|no\\s+information\\s+(on|about|regarding)"
              + "|does\\s+not\\s+have\\s+(any\\s+)?information"
              + "|is\\s+not\\s+(mentioned|specified|available)"
              + "|cannot\\s+find\\s+(any\\s+)?information)\\b",
          Pattern.CASE_INSENSITIVE);

  /**
   * Length under which a NON_ANSWER match means the whole reply was a non-answer.
   *
   * <p>Above it, the same phrase is usually the honest half of a partial answer —
   * "Casual leave is 12 days; the documents do not specify carry-forward" — which
   * is worth keeping. The distinction is content volume, so it is measured rather
   * than guessed at per phrase.
   */
  private static final int NON_ANSWER_MAX_CHARS = 320;

  /**
   * How much of the opening counts as "this reply begins by declining". Roughly a
   * first sentence — long enough to cover "The provided documents do not contain
   * information about X", short enough that a real answer's first clause cannot
   * trip it.
   */
  private static final int NON_ANSWER_OPENING_CHARS = 160;

  private static final Pattern WHITESPACE = Pattern.compile("\\s+");
  private static final Pattern NOT_IN_DOCUMENTS = Pattern.compile("\\bNOT_IN_DOCUMENTS\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern CITATION_MARKER =
      Pattern.compile("\\s*\\[(?:\\d+|source\\s*\\d*)]", Pattern.CASE_INSENSITIVE);
  private static final Pattern ANSWER_PREFIX =
      Pattern.compile("^\\s*(?:answer|response)\\s*:\\s*", Pattern.CASE_INSENSITIVE);
  private static final Pattern EXTENSION = Pattern.compile("\\.[^.]+$");
  private static final Pattern WORD_SEPARATORS = Pattern.compile("[_-]+");

  /**
   * Confidence reported for an answer built from the employee's own record.
   *
   * <p>Retrieval confidence is meaningless on that path — the figure came from the record, not
   * from a matched passage — and the chat client discards answers below its own threshold, so
   * carrying a low retrieval score through would silently drop a correct answer.
   */
  private static final double PERSONAL_ANSWER_CONFIDENCE = 1.0;

  private final KnowledgeStore store;
  private final QdrantStore qdrant;
  private final Retriever retriever;

  /**
   * Retrieval and answering are now served by two different backends, and the split
   * is not arbitrary: {@code ollama} produces the query vector that the existing
   * Qdrant collections are searchable by, and {@code claude} writes the answer from
   * whatever that retrieval returns. Only the second half is hosted.
   */
  private final OllamaClient ollama;

  private final ClaudeClient claude;

  /**
   * Reads text out of images at upload time. Ingest-only — it is never touched on the
   * path of a question, which is why it can afford a different model from {@code claude}.
   */
  private final ImageTextExtractor imageText;

  private final AtomicLong docSeq = new AtomicLong();

  public RagService(
      KnowledgeStore store,
      QdrantStore qdrant,
      Retriever retriever,
      OllamaClient ollama,
      ClaudeClient claude,
      ImageTextExtractor imageText) {
    this.store = store;
    this.qdrant = qdrant;
    this.retriever = retriever;
    this.ollama = ollama;
    this.claude = claude;
    this.imageText = imageText;
  }

  // -------------------------------------------------------------------------
  // Ingest
  // -------------------------------------------------------------------------

  public record IngestResult(KnowledgeDoc doc, int chunkCount, boolean embedded, List<String> notes) {}

  public IngestResult ingestFile(
      byte[] bytes, String filename, String mimeType, String category, String uploadedBy) {

    ParsedFile parsed = DocumentParser.parse(bytes, filename, mimeType, imageText);
    return persist(
        parsed.kind(),
        parsed.text(),
        titleFrom(filename),
        filename,
        bytes.length,
        category,
        uploadedBy,
        parsed.notes());
  }

  public IngestResult ingestText(String title, String text, String category, String uploadedBy) {
    if (text.isBlank()) throw new UnsupportedFileException("There is no text to add.");

    return persist(
        SourceKind.MANUAL,
        text,
        title,
        "Typed in the console",
        text.getBytes(StandardCharsets.UTF_8).length,
        category,
        uploadedBy,
        List.of());
  }

  private IngestResult persist(
      SourceKind kind,
      String text,
      String title,
      String filename,
      long bytes,
      String category,
      String uploadedBy,
      List<String> parseNotes) {

    String docId = newDocId();
    List<KnowledgeChunk> chunks = Chunker.chunkDocument(docId, text);
    if (chunks.isEmpty()) {
      throw new UnsupportedFileException("No indexable text was found in that document.");
    }

    // Embedding is best-effort. A failure here must not lose the upload — the
    // chunks are already searchable lexically, and /api/knowledge/reindex can
    // fill the vectors in once Ollama is available.
    List<String> notes = new ArrayList<>(parseNotes);
    String embeddingModel = null;

    OllamaStatus status = ollama.getStatus();
    if (status.canEmbed()) {
      List<List<Float>> vectors =
          ollama.embedBatch(chunks.stream().map(KnowledgeChunk::embeddingText).toList(), status.embedModel());

      if (vectors != null && vectors.size() == chunks.size()) {
        List<KnowledgeChunk> embedded = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) embedded.add(chunks.get(i).withEmbedding(vectors.get(i)));
        chunks = embedded;
        embeddingModel = status.embedModel();
      } else {
        notes.add(
            "Semantic embeddings failed — the document is searchable by keyword. "
                + "Use \"Rebuild index\" to retry.");
      }
    } else {
      notes.add(
          status.ok()
              ? "No embedding model installed — the document is searchable by keyword only."
              : "Ollama is offline — the document is searchable by keyword only.");
    }

    KnowledgeDoc doc =
        new KnowledgeDoc(
            docId,
            title,
            kind,
            category,
            filename,
            bytes,
            Instant.now().toString(),
            uploadedBy,
            chunks.size(),
            embeddingModel,
            preview(text));

    store.addDoc(doc, chunks);
    return new IngestResult(doc, chunks.size(), embeddingModel != null, notes);
  }

  private String newDocId() {
    return "doc_"
        + Long.toString(System.currentTimeMillis(), 36)
        + Long.toString(docSeq.incrementAndGet(), 36);
  }

  private static String titleFrom(String filename) {
    String base = EXTENSION.matcher(filename).replaceAll("");
    base = WORD_SEPARATORS.matcher(base).replaceAll(" ");
    base = WHITESPACE.matcher(base).replaceAll(" ").trim();
    return base.isEmpty() ? base : Character.toUpperCase(base.charAt(0)) + base.substring(1);
  }

  /** Surfaced in the admin list so HR can sanity-check what was extracted. */
  private static String preview(String text) {
    String flat = WHITESPACE.matcher(text).replaceAll(" ");
    return flat.length() <= 240 ? flat : flat.substring(0, 240);
  }

  // -------------------------------------------------------------------------
  // Reindex
  // -------------------------------------------------------------------------

  public record ReindexResult(int embedded, int skipped, String model, String message) {}

  /** Embeds every chunk that has no vector yet. Safe to run repeatedly. */
  public ReindexResult reindex(boolean force) {
    // Fresh: this is usually invoked *because* the user just installed a model.
    OllamaStatus status = ollama.getStatus(true);
    if (!status.canEmbed()) {
      return new ReindexResult(
          0, 0, null, status.hint() != null ? status.hint() : "No embedding model is available.");
    }

    KnowledgeSnapshot snapshot = store.snapshot();
    List<KnowledgeChunk> pending =
        force
            ? snapshot.chunks()
            : snapshot.chunks().stream().filter(c -> !snapshot.isEmbedded(c)).toList();

    if (pending.isEmpty()) {
      return new ReindexResult(
          0, snapshot.chunks().size(), status.embedModel(), "Every chunk already has an embedding.");
    }

    // Batched so a large knowledge base does not put one enormous request through
    // the model, and so a partial failure still commits the batches that worked.
    Map<String, List<Float>> vectors = new LinkedHashMap<>();
    int failed = 0;

    for (int i = 0; i < pending.size(); i += EMBED_BATCH) {
      List<KnowledgeChunk> slice = pending.subList(i, Math.min(i + EMBED_BATCH, pending.size()));
      List<List<Float>> result =
          ollama.embedBatch(slice.stream().map(KnowledgeChunk::embeddingText).toList(), status.embedModel());

      if (result == null || result.size() != slice.size()) {
        failed += slice.size();
        continue;
      }
      for (int n = 0; n < slice.size(); n++) vectors.put(slice.get(n).id(), result.get(n));
    }

    int embedded = vectors.isEmpty() ? 0 : store.applyEmbeddings(vectors, status.embedModel());

    String message =
        failed > 0
            ? "Embedded " + embedded + " chunk(s); " + failed + " failed. Check that Ollama is still running."
            : "Embedded " + embedded + " chunk(s) with " + status.embedModel() + ".";

    return new ReindexResult(embedded, snapshot.chunks().size() - pending.size(), status.embedModel(), message);
  }

  // -------------------------------------------------------------------------
  // Ask
  // -------------------------------------------------------------------------

  /** One prior turn of the conversation. {@code role} is {@code user} or {@code bot}. */
  public record Turn(String role, String text) {
    public boolean isUser() {
      return "user".equals(role);
    }
  }

  /**
   * The same answer as {@link #ask}, with generated text handed over as it is
   * produced.
   *
   * <p>Streaming a grounded answer has one hazard worth naming: the model signals
   * "the extracts do not cover this" by emitting {@code NOT_IN_DOCUMENTS}, and a
   * naive stream would show the employee that token before anything could suppress
   * it. So output is withheld until enough has arrived to rule the refusal out —
   * {@link #REFUSAL_GUARD} characters, a few more than the token itself. In
   * practice that is one or two fragments, invisible to the reader.
   *
   * @param onToken called on the caller's thread with each fragment, only once the
   *     refusal guard has cleared
   * @return the complete answer, identical in shape to the non-streaming path, so
   *     citation stripping and refusal handling stay in one place
   */
  public KnowledgeAnswer askStreaming(
      String rawQuestion, List<Turn> history, Consumer<String> onToken) {
    return askStreaming(rawQuestion, history, onToken, null);
  }

  /** @param personalContext see {@link #ask(String, List, boolean, String)}. */
  public KnowledgeAnswer askStreaming(
      String rawQuestion, List<Turn> history, Consumer<String> onToken, String personalContext) {

    String question = rawQuestion.trim();
    KnowledgeSnapshot snapshot = store.snapshot();

    // A personal question is answerable with no policy documents at all — "what is my grade"
    // needs the record and nothing else.
    boolean personal = personalContext != null && !personalContext.isBlank();
    if (snapshot.chunks().isEmpty() && !personal) return KnowledgeAnswer.none(0);

    long startedAt = System.nanoTime();
    OllamaStatus status = ollama.getStatus();
    List<Float> queryVector =
        status.canEmbed() ? ollama.embedQuery(question, status.embedModel()) : null;
    long embeddedAt = System.nanoTime();

    Map<String, Double> denseScores =
        queryVector == null
            ? Map.of()
            : qdrant.searchDense(queryVector, DENSE_CANDIDATES, Retriever.COSINE_FLOOR);
    long searchedAt = System.nanoTime();

    Retriever.Result retrieval =
        retriever.retrieve(question, snapshot, denseScores, queryVector != null, TOP_K);
    long rankedAt = System.nanoTime();
    logRetrieval("ask/stream", startedAt, embeddedAt, searchedAt, rankedAt, retrieval);

    // Low retrieval confidence ends a POLICY question — there is nothing to ground an answer
    // in. It must not end a personal one: the record is already in hand, and its correctness
    // has nothing to do with whether any policy passage happened to match. Returning "none"
    // here is what made "LS02667 is from which department" fall through to escalation while
    // the record sat fetched and discarded a line above.
    if (!personal && (retrieval.isEmpty() || retrieval.confidence() < Thresholds.RELEVANCE)) {
      return KnowledgeAnswer.none(retrieval.confidence());
    }

    List<RetrievedChunk> hits = retrieval.hits();
    List<Citation> citations = hits.stream().map(RagService::toCitation).toList();

    // No answer model configured: there is nothing to stream. The caller still
    // gets a complete answer and simply renders it in one go.
    if (!claude.isConfigured()) {
      if (!canQuotePassage(hits)) {
        // Personal question, no model, no passage to quote. Saying so beats an empty answer.
        return new KnowledgeAnswer(
            KnowledgeAnswer.Mode.NONE,
            "",
            citations,
            reportedConfidence(personal, retrieval),
            null,
            "No Anthropic API key is configured, so your record cannot be written up as an"
                + " answer.");
      }
      return new KnowledgeAnswer(
          KnowledgeAnswer.Mode.EXTRACTIVE,
          extractiveAnswer(hits.get(0), retrieval.confidence()),
          citations,
          reportedConfidence(personal, retrieval),
          null,
          "No Anthropic API key is configured, so this is the matching policy passage "
              + "rather than a written answer.");
    }

    StringBuilder held = new StringBuilder();
    // One flag, flipped once, rather than re-testing the whole buffer per token.
    boolean[] released = {false};
    boolean[] refused = {false};
    long[] firstTokenAt = {0};

    String generated =
        claude.chatStream(
            buildPrompt(question, hits, history, personalContext),
            piece -> {
              if (released[0]) {
                onToken.accept(piece);
                return true;
              }
              held.append(piece);
              // Withhold on either signal. A prose non-answer starts with the
              // give-away ("The HR documents do not specify..."), which the guard
              // window is sized to cover, so nothing is shown and then retracted.
              if (NOT_IN_DOCUMENTS.matcher(held).find() || NON_ANSWER.matcher(held).find()) {
                // A refusal, and the employee will never see a word of it — the
                // caller turns this into the "not in the documents" path below.
                // So stop the model here rather than paying for hundreds more
                // tokens to build a string that gets thrown away.
                refused[0] = true;
                return false;
              }
              if (held.length() >= REFUSAL_GUARD) {
                released[0] = true;
                firstTokenAt[0] = System.nanoTime();
                onToken.accept(stripCitationMarkers(held.toString()));
              }
              return true;
            });

    if (refused[0]) {
      log.debug("Model declined to answer from the retrieved passages; generation stopped early");
      return KnowledgeAnswer.none(retrieval.confidence());
    }

    if (generated == null) {
      return new KnowledgeAnswer(
          KnowledgeAnswer.Mode.EXTRACTIVE,
          extractiveAnswer(hits.get(0), retrieval.confidence()),
          citations,
          retrieval.confidence(),
          null,
          "The answer model did not respond — showing the source passage instead.");
    }

    if (NOT_IN_DOCUMENTS.matcher(generated).find() || isNonAnswer(generated)) {
      log.debug("Model declined to answer from the retrieved passages");
      return KnowledgeAnswer.none(retrieval.confidence());
    }

    // A short answer may finish before the guard released. Flush it now so the
    // client is not left with an empty bubble it has to fill from the final value.
    if (!released[0] && !held.isEmpty()) {
      onToken.accept(stripCitationMarkers(held.toString()));
    }

    // The number that decides whether this feels fast: not how long the answer
    // took, but how long the employee watched a typing indicator before any of it
    // appeared. The refusal guard is deliberately part of it.
    log.info(
        "ask/stream: first shown token at {}ms, complete at {}ms, {} chars",
        firstTokenAt[0] == 0 ? OllamaClient.millisSince(startedAt) : (firstTokenAt[0] - startedAt) / 1_000_000,
        OllamaClient.millisSince(startedAt),
        generated.length());

    return new KnowledgeAnswer(
        KnowledgeAnswer.Mode.GENERATED,
        stripCitationMarkers(generated),
        citations,
        reportedConfidence(personal, retrieval),
        claude.model(),
        null);
  }

  /**
   * Where a turn's pre-generation time went.
   *
   * <p>Worth separating from the model's own counters because the fixes differ:
   * {@code embed} is a round trip to Ollama and a reload if the embedding model was
   * evicted, {@code search} is Qdrant, and {@code rank} is BM25 over the whole
   * corpus in this process — the one that grows with the knowledge base.
   */
  private static void logRetrieval(
      String stage,
      long startedAt,
      long embeddedAt,
      long searchedAt,
      long rankedAt,
      Retriever.Result retrieval) {

    if (!log.isInfoEnabled()) return;
    log.info(
        "{}: embed={}ms search={}ms rank={}ms hits={} confidence={} dense={}",
        stage,
        (embeddedAt - startedAt) / 1_000_000,
        (searchedAt - embeddedAt) / 1_000_000,
        (rankedAt - searchedAt) / 1_000_000,
        retrieval.hits().size(),
        String.format("%.2f", retrieval.confidence()),
        retrieval.usedEmbeddings());
  }

  public KnowledgeAnswer ask(String rawQuestion, List<Turn> history, boolean extractiveOnly) {
    return ask(rawQuestion, history, extractiveOnly, null);
  }

  /**
   * @param personalContext the caller's own record, already rendered, or null for a policy
   *     question. Never built here — {@code EmployeeAnswerService} owns that, so this class
   *     has no access to employee data and cannot become a second place it is fetched.
   */
  public KnowledgeAnswer ask(
      String rawQuestion, List<Turn> history, boolean extractiveOnly, String personalContext) {
    String question = rawQuestion.trim();
    KnowledgeSnapshot snapshot = store.snapshot();

    boolean personal = personalContext != null && !personalContext.isBlank();
    if (snapshot.chunks().isEmpty() && !personal) return KnowledgeAnswer.none(0);

    long startedAt = System.nanoTime();
    OllamaStatus status = ollama.getStatus();

    // Dense retrieval runs in Qdrant; the query vector is the only thing we need
    // the model for at this stage.
    List<Float> queryVector = status.canEmbed() ? ollama.embedQuery(question, status.embedModel()) : null;
    long embeddedAt = System.nanoTime();

    Map<String, Double> denseScores =
        queryVector == null
            ? Map.of()
            : qdrant.searchDense(queryVector, DENSE_CANDIDATES, Retriever.COSINE_FLOOR);
    long searchedAt = System.nanoTime();

    Retriever.Result retrieval =
        retriever.retrieve(question, snapshot, denseScores, queryVector != null, TOP_K);
    long rankedAt = System.nanoTime();
    logRetrieval("ask", startedAt, embeddedAt, searchedAt, rankedAt, retrieval);

    // Low retrieval confidence ends a POLICY question — there is nothing to ground an answer
    // in. It must not end a personal one: the record is already in hand, and its correctness
    // has nothing to do with whether any policy passage happened to match. Returning "none"
    // here is what made "LS02667 is from which department" fall through to escalation while
    // the record sat fetched and discarded a line above.
    if (!personal && (retrieval.isEmpty() || retrieval.confidence() < Thresholds.RELEVANCE)) {
      return KnowledgeAnswer.none(retrieval.confidence());
    }

    List<RetrievedChunk> hits = retrieval.hits();
    List<Citation> citations = hits.stream().map(RagService::toCitation).toList();
    // Nullable now: a personal question can retrieve no passages at all.
    RetrievedChunk best = canQuotePassage(hits) ? hits.get(0) : null;

    if (extractiveOnly || !claude.isConfigured()) {
      if (best == null) {
        return new KnowledgeAnswer(
            KnowledgeAnswer.Mode.NONE,
            "",
            citations,
            reportedConfidence(personal, retrieval),
            null,
            "There is no policy passage to quote, and no answer model configured to write up"
                + " your record.");
      }
      return new KnowledgeAnswer(
          KnowledgeAnswer.Mode.EXTRACTIVE,
          extractiveAnswer(best, retrieval.confidence()),
          citations,
          reportedConfidence(personal, retrieval),
          null,
          extractiveOnly
              ? null
              : "No Anthropic API key is configured, so this is the matching policy passage "
                  + "rather than a written answer.");
    }

    String generated = claude.chat(buildPrompt(question, hits, history, personalContext));

    if (generated == null) {
      return new KnowledgeAnswer(
          best == null ? KnowledgeAnswer.Mode.NONE : KnowledgeAnswer.Mode.EXTRACTIVE,
          best == null ? "" : extractiveAnswer(best, retrieval.confidence()),
          citations,
          reportedConfidence(personal, retrieval),
          null,
          "The answer model did not respond"
              + (best == null ? "." : " — showing the source passage instead."));
    }

    // The model is instructed to emit this token when the passages do not cover
    // the question. Honouring it is what keeps the bot from bluffing.
    if (NOT_IN_DOCUMENTS.matcher(generated).find() || isNonAnswer(generated)) {
      log.debug("Model declined to answer from the retrieved passages");
      return KnowledgeAnswer.none(retrieval.confidence());
    }

    return new KnowledgeAnswer(
        KnowledgeAnswer.Mode.GENERATED,
        stripCitationMarkers(generated),
        citations,
        reportedConfidence(personal, retrieval),
        claude.model(),
        null);
  }

  /**
   * A reply whose substance is "the documents do not say" is not an answer.
   *
   * <p>Two ways to qualify, because models decline at two different lengths. A
   * short reply matching anywhere is a bare non-answer. A long one counts only if
   * it <em>opens</em> that way — everything after is padding, and qwen3.5:4b pads
   * generously: asked for a wifi password it declined and then listed what the
   * corpus does cover, sailing past a length gate alone at 340 characters.
   *
   * <p>Opening-position matters rather than mere presence, so a genuine partial
   * answer — "Casual leave is 12 days; the documents do not specify carry-forward"
   * — still reaches the employee.
   */
  private static boolean isNonAnswer(String text) {
    if (NON_ANSWER.matcher(text).find() && text.length() <= NON_ANSWER_MAX_CHARS) return true;

    String opening = text.length() <= NON_ANSWER_OPENING_CHARS
        ? text
        : text.substring(0, NON_ANSWER_OPENING_CHARS);
    return NON_ANSWER.matcher(opening).find();
  }


  /**
   * The confidence to report. Retrieval score for a policy answer; a fixed high value for a
   * personal one, where the number came from the record rather than from a matched passage.
   */
  private static double reportedConfidence(boolean personal, Retriever.Result retrieval) {
    return personal ? PERSONAL_ANSWER_CONFIDENCE : retrieval.confidence();
  }

  /**
   * Whether an extractive fallback is possible at all.
   *
   * <p>It needs a passage to quote. A personal question can legitimately retrieve none — "what
   * is my grade" matches no policy text — so on that path there is nothing to fall back to and
   * the caller has to say so instead of indexing into an empty list.
   */
  private static boolean canQuotePassage(List<RetrievedChunk> hits) {
    return !hits.isEmpty();
  }

  private static Citation toCitation(RetrievedChunk hit) {
    String snippet = WHITESPACE.matcher(hit.chunk().text()).replaceAll(" ");
    if (snippet.length() > 300) snippet = snippet.substring(0, 300);

    KnowledgeDoc doc = hit.doc();
    return new Citation(
        hit.chunk().docId(),
        doc != null && doc.title() != null ? doc.title() : "Uploaded document",
        doc != null && doc.filename() != null ? doc.filename() : "",
        hit.chunk().headings(),
        snippet,
        Math.round(hit.score() * 100) / 100.0);
  }

  // -------------------------------------------------------------------------
  // Prompting
  // -------------------------------------------------------------------------

  /**
   * The grounding contract.
   *
   * <p>The identity exception sits <b>above</b> the numbered rules rather than
   * inside them, and that placement is the whole point: the rules are declared to
   * be in order of importance, so an identity question reaching rule 7 gets
   * NOT_IN_DOCUMENTS. Asked its name, the model answered "I am not listed as a
   * person with authority to answer questions in the provided documents" — it had
   * no way to know it was anything other than a document lookup. Anything stated
   * as a lower-numbered rule would have been outranked by rule 1 instead.
   *
   * <p>Scoped tightly to questions about Robin itself. It is the one carve-out
   * from "extracts only", and widening it — to greetings, to small talk, to
   * "general HR knowledge" — would reopen exactly the ungrounded-answer hole the
   * other nine rules exist to close.
   */
  private static final String SYSTEM_PROMPT =
      """
      You are Robin, the HR assistant for LeadSquared. Employees come to you with questions about leave, payroll, benefits, ESOPs, reimbursements and company policy, and you answer them using ONLY the HR policy extracts provided in each message.

      One exception to every rule below: if the employee asks about YOU — your name, what you are, what you can help with — answer from this paragraph, not from the extracts. You are Robin, LeadSquared's HR assistant; you answer HR questions from the company's own HR documents, and you point people to HR Operations for anything those documents do not cover. Say it in one short line and invite their question. Never reply NOT_IN_DOCUMENTS to a question about yourself, never say you are "not listed in the documents", and never suggest you are a person or an employee.

      Rules, in order of importance:
      1. Use only the provided extracts. Never use general knowledge about HR, employment law, or other companies.
      2. Never invent or adjust a number, date, amount, deadline, or entitlement. If a figure is not in the extracts, do not state one. Figures, names and deadlines are copied exactly; the wording around them is yours.
      3. Never calculate anything. No arithmetic, no subtraction, no unit conversion, no comparing a number the employee gave you against a limit in the policy — even when the sum is obvious and you are confident. State the rule and its threshold and let them apply it to their own case.
         Asked "I have 34 days of earned leave, how many will I lose", the correct answer is "up to **30 days** may be carried forward; anything above that lapses on 31 December" — NOT "you will lose 4 days".
         Asked "I want 3 weeks remote", the correct answer is "remote work is allowed for up to **30 days** a year with manager approval; beyond that needs HRBP sign-off" — NOT "your 3 weeks is within the limit".
         This holds even though the arithmetic looks trivial: the figure you were given may be stale, may exclude pending requests, or may be a different leave type than the rule covers, and a confident wrong total tells someone they have leave or money they do not.
      4. Answer the question that was actually asked. The extracts are retrieved by similarity, so they often include a NEARBY question that is not the one asked — "moving roles mid-year" when the employee asked about RESIGNING mid-year, "joining" when they asked about "leaving". Answering the neighbour is a wrong answer, not a partial one. Check each extract against the actual question before you use it, and ignore the ones that address something else.
      5. Write the answer in your own words. Never copy sentences, headings, numbered lists or FAQ titles out of an extract, and never start with a document title. Summarise.
      6. Use EVERY extract that bears on the question, not just the first. Several documents usually each cover one part — variable pay, ESOPs, leave encashment, notice period. Cover each relevant part in its own short line, and name the subject at the start of the line so the employee can see which is which.
      7. If the extracts do not answer the question, reply with exactly: NOT_IN_DOCUMENTS
         Do this even if you could guess, and do it even when the extracts discuss the same general topic but not the thing asked. A wrong HR answer costs the employee real money or leave.
         This never applies to a question about who you are — see the exception above the rules.
      8. If the extracts only partly answer it, give the part that is covered and say plainly what is not.
      9. Answer the employee directly in the second person. Do not mention "extracts", "context", "documents provided" or "based on the information".

      Style: 2-5 short sentences, or one short line per subject when several apply. Use **bold** for figures and deadlines. No preamble, no sign-off.""";

  /**
   * @param personalContext the caller's own employee record and any pre-computed payout,
   *     already rendered — see {@code EmployeeFacts}. Null for a pure policy question.
   *     Carried in the user turn rather than appended to {@link #SYSTEM_PROMPT} so the
   *     system prefix stays byte-identical across every request and remains cacheable.
   */
  private static List<ChatMessage> buildPrompt(
      String question, List<RetrievedChunk> hits, List<Turn> history, String personalContext) {

    List<ChatMessage> messages = new ArrayList<>();
    messages.add(ChatMessage.system(SYSTEM_PROMPT));

    // The caller's transcript already ends with the question being asked, and it
    // is appended again below with the extracts — drop the duplicate so the model
    // does not see the same question twice and answer the first, context-free one.
    List<Turn> prior = new ArrayList<>(history == null ? List.of() : history);
    if (!prior.isEmpty()) {
      Turn last = prior.get(prior.size() - 1);
      if (last.isUser() && last.text().trim().equals(question.trim())) {
        prior.remove(prior.size() - 1);
      }
    }

    // A couple of prior turns, trimmed — enough to resolve a follow-up like
    // "and for sick leave?" without pushing the extracts out of a small context.
    List<Turn> recent = prior.subList(Math.max(0, prior.size() - 4), prior.size());
    for (Turn turn : recent) {
      String text = turn.text().length() > 400 ? turn.text().substring(0, 400) : turn.text();
      messages.add(turn.isUser() ? ChatMessage.user(text) : ChatMessage.assistant(text));
    }

    // The record goes ahead of the extracts: it is the more specific source, and when the two
    // disagree — a policy stating the standard notice period against a record carrying this
    // employee's actual one — the employee's own record is the answer.
    String personal = personalContext == null || personalContext.isBlank() ? "" : personalContext + "\n\n";
    messages.add(ChatMessage.user(personal + contextBlock(hits) + "\n\nQuestion: " + question));
    return messages;
  }

  private static String contextBlock(List<RetrievedChunk> hits) {
    List<String> parts = new ArrayList<>();
    int budget = MAX_CONTEXT_CHARS;

    for (RetrievedChunk hit : hits) {
      String label = hit.label();
      String text = hit.chunk().text();
      String body = text.substring(0, Math.min(text.length(), Math.max(0, budget)));
      if (body.isEmpty()) break;

      parts.add("--- " + (label.isEmpty() ? "HR document" : label) + " ---\n" + body);
      budget -= body.length() + label.length() + 12;
      if (budget <= 200) break;
    }

    return "HR policy extracts:\n\n" + String.join("\n\n", parts);
  }

  /** Small models sometimes emit "[1]" markers despite not being asked to. */
  private static String stripCitationMarkers(String text) {
    String out = CITATION_MARKER.matcher(text).replaceAll("");
    return ANSWER_PREFIX.matcher(out).replaceAll("").trim();
  }

  // -------------------------------------------------------------------------
  // Extractive fallback
  // -------------------------------------------------------------------------

  /**
   * The no-LLM path: return the matching passage verbatim, framed honestly.
   *
   * <p>Deliberately not paraphrased. Without a model there is nothing to
   * paraphrase <i>with</i>, and quoting HR's own wording is both accurate and
   * auditable.
   */
  private static String extractiveAnswer(RetrievedChunk hit, double confidence) {
    String where = hit.label();
    String lead =
        confidence >= Thresholds.STRONG_RELEVANCE
            ? "Here's the relevant section from the HR knowledge base"
            : "This looks like the closest match in the HR knowledge base";

    return lead
        + (where.isEmpty() ? "" : " — **" + where + "**")
        + ":\n\n"
        + trimToSentence(hit.chunk().text(), 900);
  }

  private static String trimToSentence(String text, int limit) {
    if (text.length() <= limit) return text;

    String cut = text.substring(0, limit);
    int boundary = Math.max(cut.lastIndexOf(". "), cut.lastIndexOf('\n'));
    String kept = boundary > limit * 0.5 ? cut.substring(0, boundary + 1) : cut;
    return kept.trim() + "…";
  }
}
