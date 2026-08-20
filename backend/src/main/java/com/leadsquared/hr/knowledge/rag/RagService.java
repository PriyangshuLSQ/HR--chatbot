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
import java.util.Collections;
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

  /**
   * Passages handed to the model, and cited back to the employee.
   *
   * <p>8, not 5, because a table and the prose that refers to it are separate chunks and the
   * prose usually wins: "what is the car lease entitlement at grade X7" ranked the paragraph
   * reading "the vehicle value entitlement <i>as stated above</i>" first at 0.806, and the chunk
   * actually holding the X7 row seventh at 0.703. At 5 the model never saw the figure and
   * correctly answered NOT_IN_DOCUMENTS, which reaches the employee as "I don't have a reliable
   * answer" about a number sitting in the corpus.
   *
   * <p>Not raised further because {@link #MAX_CONTEXT_CHARS} is the binding limit, not this:
   * these chunks run 600–1,000 characters, so the budget is exhausted around the seventh either
   * way. Raising this alone adds candidates that {@link #contextBlock} then drops; genuinely
   * widening the window means raising both, and paying for it on every question.
   */
  private static final int TOP_K = 8;

  /**
   * How much of the previous question a follow-up retry may carry.
   *
   * <p>Enough for a question, short enough that it cannot swamp the follow-up it is there to
   * give context to — the point is to restore a missing subject, not to search for the old
   * question again.
   */
  private static final int FOLLOW_UP_CONTEXT_CHARS = 200;

  /**
   * How much of the conversation the model is shown, measured in characters rather than turns.
   *
   * <p>It was twenty turns, which sounds generous and is not: a session of short questions burns
   * through it in a few minutes, and the assistant then denies having been told something it was
   * told in the same conversation. "My project code name is ORCHID-7" survived eight turns and was
   * gone by twenty-six — not summarised, not hedged, but answered with "you haven't mentioned a
   * project code name to me in this thread", which is worse than forgetting because it is a
   * confident denial.
   *
   * <p>A budget rather than a count because turns are not comparable: fifty one-line exchanges are
   * a smaller prompt than five long ones, and only one of those shapes should be truncated. At
   * 60,000 characters this is roughly 15k tokens — under a tenth of the 200k window, alongside a
   * 6,000-character extract block — which covers any realistic session end to end while still
   * bounding one that has run for days.
   *
   * <p>What it costs, since it is paid on every message: the history is not cacheable (it changes
   * each turn), so a long session bills its whole transcript per question. At Haiku 4.5 input
   * pricing a full 60k-character history is about two US cents a message, against a fifth of that
   * before. The other cost is subtler and is why this is bounded at all — prior answers in this
   * application carry the employee's own compensation and leave, so a long history is a growing
   * pile of personal data in every request, and it gives the model more to answer from that is not
   * a cited document.
   */
  private static final int MAX_HISTORY_CHARS = 60_000;

  /** Per turn, so one enormous message cannot consume the whole budget on its own. */
  private static final int MAX_HISTORY_TURN_CHARS = 2000;

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
   * A question about the assistant itself, which no passage can answer or fail to answer.
   *
   * <p>The system prompt has always had an identity exception, and it was unreachable whenever
   * retrieval scored badly — because the model is only consulted once retrieval clears
   * {@link Thresholds#RELEVANCE}, and an identity question has nothing to retrieve. Its
   * confidence is therefore noise: "who r u" scored 0.436 and answered, while "hi, who r u"
   * scored 0.303 and was refused with the escalation offer meant for leave and pay questions.
   * A greeting in front of the same three words is what decided it.
   *
   * <p>Matched in code rather than left to the prompt because the prompt never runs in the failing
   * case. Same carve-out the personal path already has a few lines below, for the same reason: the
   * answer does not come from the corpus, so the corpus cannot veto it.
   *
   * <p>Scoped to "you" and "your", never "my" or "I" — the prompt is emphatic that a question about
   * the employee is a different question, and this must not widen that. A false positive is cheap
   * anyway: it only means the model is asked, and every grounding rule still applies to what it
   * says, so a policy question caught here still declines rather than inventing.
   */
  private static final Pattern ABOUT_ASSISTANT =
      Pattern.compile(
          "\\b(who|what)\\s+(are|r)\\s+(you|u)\\b"
              + "|\\bwhat\\s+(can|do)\\s+(you|u)\\s+(do|help)\\b"
              + "|\\bwhat(?:'s|\\s+is)\\s+your\\s+(name|purpose|job|role)\\b"
              + "|\\bare\\s+(you|u)\\s+(a\\s+|an\\s+)?(bot|human|ai|real|person|robot)\\b"
              + "|\\bwho\\s+am\\s+i\\s+(talking|speaking)\\s+to\\b",
          Pattern.CASE_INSENSITIVE);

  /**
   * A question that names no subject of its own — "what about x1?", "and x2?", "what if I do".
   *
   * <p>These are the questions {@link #retryWithContext} exists for, and the reason it needs a
   * trigger beyond "the direct search found nothing": a bare follow-up does not retrieve
   * <i>nothing</i>, it retrieves <i>noise</i>, and noise clears the relevance floor often enough
   * to look like success. Asked "what about x1?" one turn after a car lease answer, the direct
   * search scored 0.39 on the Acceptable Usage and Grievance policies — above the floor, so no
   * retry ran and the employee was told the grade was not in the documents. "And what about x2?"
   * scored 0.52 on the referral bonus table, and was answered: a confident, cited answer about
   * an entirely different policy from the one under discussion, which is the worst of the three
   * outcomes because nothing about it looks wrong.
   *
   * <p>Deliberately narrow: the opener alone is not enough, what follows it has to be a single
   * short token. "What about maternity leave?" opens identically and names its subject outright —
   * it retrieves well on its own and must be left to, because forcing the previous question in
   * front of it retrieved the car lease passages alongside the maternity ones and cited them
   * under the answer. The text stayed right; an answer about maternity leave sourced to the Car
   * Lease Policy is still something an employee reads as the assistant being confused.
   *
   * <p>So this matches "what about x1?" and "and x2?" and not much else, which is the whole
   * population it needs to catch — a referent with nothing to embed, standing in for a subject
   * named a turn or two ago.
   */
  private static final Pattern BARE_FOLLOW_UP =
      Pattern.compile(
          "^\\s*(and|but|so|ok|okay)?\\s*(what|how)\\s+about\\s+(the\\s+)?[^\\s?]{1,12}\\s*\\??$"
              + "|^\\s*(and|but|also)\\s+(for|at|in|with)?\\s*[^\\s?]{1,12}\\s*\\??$"
              + "|^\\s*what\\s+if\\b",
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
    // Titled here as well as on load, so a freshly uploaded document is embedded with its own
    // name in the text from the start rather than only after the next reindex.
    List<KnowledgeChunk> chunks =
        Chunker.chunkDocument(docId, text).stream().map(c -> c.withDocTitle(title)).toList();
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

    // Request-level clock, kept separate from the per-pass timings inside retrieveOnce: what
    // matters below is how long the employee waited, retries included.
    long startedAt = System.nanoTime();

    Retriever.Result retrieval = retrieveOnce(question, snapshot, "ask/stream");
    if (!personal && (missedRetrieval(retrieval) || weakBareFollowUp(question, retrieval))) {
      retrieval = retryWithContext(question, history, snapshot, retrieval, "ask/stream");
    }

    // Low retrieval confidence ends a POLICY question — there is nothing to ground an answer
    // in. It must not end a personal one: the record is already in hand, and its correctness
    // has nothing to do with whether any policy passage happened to match. Returning "none"
    // here is what made "LS02667 is from which department" fall through to escalation while
    // the record sat fetched and discarded a line above.
    //
    // Nor a question about the assistant, for the same reason: the answer is in the system
    // prompt, so no passage can supply it and none can veto it. "who r u" cleared this bar by
    // luck at 0.436 and "hi, who r u" did not at 0.303, which is how a greeting came to decide
    // whether the assistant would say its own name.
    if (!personal && !isAboutAssistant(question) && missedRetrieval(retrieval)) {
      return KnowledgeAnswer.none(retrieval.confidence());
    }

    // An identity answer comes from the system prompt, so it is grounded in no passage and must
    // cite none. Left alone, whatever the search happened to return got listed underneath: "I'm
    // Robin, LeadSquared's HR assistant" sourced to the Variable & Incentive Pay Policy, which
    // tells the employee the assistant does not know where its own answers come from. Dropping
    // them also keeps the extracts out of the prompt, so there is nothing there to distract from
    // the one paragraph that should answer this.
    List<RetrievedChunk> hits =
        isAboutAssistant(question) ? List.<RetrievedChunk>of() : retrieval.hits();
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

    // Which passages, not just how many. A refusal logged as "hits=8 confidence=0.80" is
    // indistinguishable from a coverage gap, and the difference is the whole diagnosis: the
    // figure the employee asked for was in the corpus but ranked ninth. Debug rather than info
    // because it is one line per hit.
    if (log.isDebugEnabled()) {
      for (RetrievedChunk hit : retrieval.hits()) {
        log.debug(
            "  hit {} score={} (dense={} lexical={}) {}",
            hit.chunk().id(),
            String.format("%.4f", hit.score()),
            String.format("%.4f", hit.dense()),
            String.format("%.4f", hit.lexical()),
            hit.label());
      }
    }
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

    Retriever.Result retrieval = retrieveOnce(question, snapshot, "ask");
    if (!personal && (missedRetrieval(retrieval) || weakBareFollowUp(question, retrieval))) {
      retrieval = retryWithContext(question, history, snapshot, retrieval, "ask");
    }

    // Low retrieval confidence ends a POLICY question — there is nothing to ground an answer
    // in. It must not end a personal one: the record is already in hand, and its correctness
    // has nothing to do with whether any policy passage happened to match. Returning "none"
    // here is what made "LS02667 is from which department" fall through to escalation while
    // the record sat fetched and discarded a line above.
    //
    // Nor a question about the assistant, for the same reason: the answer is in the system
    // prompt, so no passage can supply it and none can veto it. "who r u" cleared this bar by
    // luck at 0.436 and "hi, who r u" did not at 0.303, which is how a greeting came to decide
    // whether the assistant would say its own name.
    if (!personal && !isAboutAssistant(question) && missedRetrieval(retrieval)) {
      return KnowledgeAnswer.none(retrieval.confidence());
    }

    // Cited nothing, for the reason given at the same point in the streaming path above: an
    // identity answer is grounded in the system prompt, not in the corpus.
    List<RetrievedChunk> hits =
        isAboutAssistant(question) ? List.<RetrievedChunk>of() : retrieval.hits();
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
   * One retrieval pass: embed the query, search Qdrant, rank against the snapshot.
   *
   * <p>Extracted so a follow-up can be retried with context — see {@link #retryWithContext}.
   * {@code ollama.getStatus()} is cached, so a second call costs another embed and search, not
   * another probe of a machine that may have no Ollama on it.
   */
  private Retriever.Result retrieveOnce(String query, KnowledgeSnapshot snapshot, String label) {
    long startedAt = System.nanoTime();
    OllamaStatus status = ollama.getStatus();

    // Dense retrieval runs in Qdrant; the query vector is the only thing we need
    // the model for at this stage.
    List<Float> queryVector =
        status.canEmbed() ? ollama.embedQuery(query, status.embedModel()) : null;
    long embeddedAt = System.nanoTime();

    Map<String, Double> denseScores =
        queryVector == null
            ? Map.of()
            : qdrant.searchDense(queryVector, DENSE_CANDIDATES, Retriever.COSINE_FLOOR);
    long searchedAt = System.nanoTime();

    Retriever.Result retrieval =
        retriever.retrieve(query, snapshot, denseScores, queryVector != null, TOP_K);
    long rankedAt = System.nanoTime();
    logRetrieval(label, startedAt, embeddedAt, searchedAt, rankedAt, retrieval);
    return retrieval;
  }

  /** Nothing found, or nothing relevant enough to ground an answer in. */
  private static boolean missedRetrieval(Retriever.Result retrieval) {
    return retrieval.isEmpty() || retrieval.confidence() < Thresholds.RELEVANCE;
  }

  /**
   * A subjectless follow-up whose own search did not go convincingly well.
   *
   * <p>The retry used to be reserved for a search that found nothing, on the reasoning that a
   * question retrieving well should never be pulled off course by an earlier topic. Sound, except
   * that a bare follow-up does not fail by finding nothing — it fails by finding noise, and noise
   * clears the answer floor often enough to look like success. "What about x1?" after a car lease
   * answer scored 0.39 against the Acceptable Usage and Grievance policies; "and what about x2?"
   * scored 0.52 against the referral bonus table and was answered from it.
   *
   * <p>So the bar for "retrieved well" here is {@link Thresholds#STRONG_RELEVANCE} rather than
   * {@link Thresholds#RELEVANCE}: strong enough to pre-empt a clarifying question is strong enough
   * to trust without context. Above it nothing changes and the question is searched exactly once,
   * which is what keeps the original property — and its test — intact.
   */
  private static boolean weakBareFollowUp(String question, Retriever.Result retrieval) {
    return isBareFollowUp(question) && retrieval.confidence() < Thresholds.STRONG_RELEVANCE;
  }

  /**
   * Whether this asks about the assistant rather than about HR. See {@link #ABOUT_ASSISTANT}.
   *
   * <p>The word cap is what keeps a compound question out. "What can you do?" is an identity
   * question; "what can you do about my leave balance" contains the same four words and is a leave
   * question, and treating it as the former would answer an introduction to someone asking about
   * their own balance. Identity questions are short by nature — every phrasing worth catching here
   * fits in six words — so length is a cheap and honest discriminator.
   */
  static boolean isAboutAssistant(String question) {
    if (question == null || !ABOUT_ASSISTANT.matcher(question).find()) return false;
    return question.trim().split("\\s+").length <= 6;
  }

  /**
   * Whether this question needs the previous one to mean anything. See {@link #BARE_FOLLOW_UP}.
   *
   * <p>Package-private rather than private so the boundary can be tested directly. Which side of
   * it a question falls on decides whether an unrelated policy can be pulled into the answer, and
   * the line between "what about x1?" and "what about maternity leave?" is one character class.
   */
  static boolean isBareFollowUp(String question) {
    return question != null && BARE_FOLLOW_UP.matcher(question).find();
  }

  /**
   * Searches again with the previous question folded in, for a follow-up that cannot stand alone.
   *
   * <p>"What if I take more than 10 days" names no subject — no leave, no policy, nothing for
   * either the embedding or the lexical scorer to hold onto. It retrieved nothing, so the
   * employee was asked to pick a topic from a menu one message after being told that more than
   * 10 days needs HRBP sign-off. Putting their previous question in front of it restores the
   * subject the sentence left out.
   *
   * <p>A retry rather than widening every query, and that is what keeps it safe: a question that
   * already retrieves well never gets here, so no working answer can be pulled off course by an
   * earlier topic. The widened text is used for <b>search only</b> — the prompt still asks what
   * the employee actually typed, and rule 5 is what stops the model answering the previous
   * question instead of this one.
   *
   * @param direct the failed first attempt, returned unchanged unless the retry beats it
   */
  private Retriever.Result retryWithContext(
      String question,
      List<Turn> history,
      KnowledgeSnapshot snapshot,
      Retriever.Result direct,
      String label) {

    String widened = withPreviousQuestion(question, history);
    if (widened == null) return direct;

    Retriever.Result contextual = retrieveOnce(widened, snapshot, label + "/follow-up");

    // Must clear the bar on its own account and beat what it replaces. On a tie the direct
    // result wins: the question as asked is the truer description of what was asked.
    //
    // Left strict deliberately. Preferring the widened search on a bare follow-up unless it was
    // clearly worse also fired on "what about maternity leave?" — which opens like a bare
    // follow-up but names its own subject perfectly well. The answer stayed correct, because the
    // grounding rules make the model answer the question asked, but the car lease passages came
    // along and were cited under it: an answer about maternity leave sourced to the Car Lease
    // Policy, which an employee has every reason to read as the assistant being confused. A
    // question that retrieves well on its own should keep its own passages.
    if (missedRetrieval(contextual) || contextual.confidence() <= direct.confidence()) {
      return direct;
    }

    log.info(
        "Follow-up retrieval rescued a question that found nothing on its own —"
            + " confidence {} -> {}",
        String.format("%.2f", direct.confidence()),
        String.format("%.2f", contextual.confidence()));
    return contextual;
  }

  /**
   * The question with the employee's own previous question in front of it, or null if none.
   *
   * <p>Their previous question rather than the bot's previous answer: an answer runs to several
   * sentences and would dominate the embedding, retrieving whatever it was about instead of what
   * is being asked about it now.
   *
   * <p>And the previous question that <b>names a subject</b>, not simply the previous one. Down a
   * run of follow-ups the immediate predecessor is itself a follow-up: asked X7, then "what about
   * x1?", then "and what about x2?", folding in the predecessor gives "what about x1? and what
   * about x2?" — two subjectless questions, no more retrievable than one. The X1 answer worked
   * and X2 went back to failing, which reads as the fix being flaky rather than as the second
   * question inheriting from the wrong turn. Skipping bare follow-ups walks back to the car lease
   * question, which is what every question in the run is really about.
   */
  private static String withPreviousQuestion(String question, List<Turn> history) {
    if (history == null || history.isEmpty()) return null;

    for (int i = history.size() - 1; i >= 0; i--) {
      Turn turn = history.get(i);
      if (!turn.isUser() || turn.text() == null || turn.text().isBlank()) continue;

      String prior = turn.text().trim();
      // The transcript usually already ends with the question being asked.
      if (prior.equals(question.trim())) continue;
      // Carries no subject to lend. Keep walking back for one that does.
      if (isBareFollowUp(prior)) continue;

      return (prior.length() > FOLLOW_UP_CONTEXT_CHARS
              ? prior.substring(0, FOLLOW_UP_CONTEXT_CHARS)
              : prior)
          + " "
          + question;
    }
    return null;
  }

  /**
   * The grounding contract.
   *
   * <p>The identity exception sits <b>above</b> the numbered rules rather than
   * inside them, and that placement is the whole point: the rules are declared to
   * be in order of importance, so an identity question reaching rule 8 gets
   * NOT_IN_DOCUMENTS. Asked its name, the model answered "I am not listed as a
   * person with authority to answer questions in the provided documents" — it had
   * no way to know it was anything other than a document lookup. Anything stated
   * as a lower-numbered rule would have been outranked by rule 1 instead.
   *
   * <p>Scoped tightly to questions about Robin itself. It is the one carve-out
   * from "extracts only", and widening it — to greetings, to small talk, to
   * "general HR knowledge" — would reopen exactly the ungrounded-answer hole the
   * other nine rules exist to close.
   *
   * <p>The paragraph after it exists because that scoping did not survive contact
   * with the obvious question. Asked "tell my name" — the employee's name, sitting
   * in the record block right there in the prompt — the model matched "your name"
   * in the exception and introduced itself instead. Same for "who am I". Being the
   * one rule that outranks the others, it wins that ambiguity every time, so the
   * pronoun has to be made explicit rather than left to context.
   */
  private static final String SYSTEM_PROMPT =
      """
      You are Robin, the HR assistant for LeadSquared. Employees come to you with questions about leave, payroll, benefits, ESOPs, reimbursements and company policy, and you answer them using ONLY the HR policy extracts provided in each message.

      One exception to every rule below: if the employee asks about YOU — your name, what you are, what you can help with — answer from this paragraph, not from the extracts. You are Robin, LeadSquared's HR assistant; you answer HR questions from the company's own HR documents, and you point people to HR Operations for anything those documents do not cover. Say it in one short line and invite their question. Never reply NOT_IN_DOCUMENTS to a question about yourself, never say you are "not listed in the documents", and never suggest you are a person or an employee.

      That exception is about YOU, and only you. A question about the EMPLOYEE is a different question, however similar it sounds: "what is my name", "who am I", "what is my employee code", "what are my details" ask about THEM. Answer those from the EMPLOYEE RECORD block when one is present — it is authoritative and holds their name and code — and never introduce yourself in reply to one. The word "my" belongs to the employee; "you" and "your" refer to you.

      It also applies only when the message asks nothing else. A greeting in front of a real question is still that question: "hi, what is the leave policy" asks about leave, and "what can you do about my leave balance" asks for a balance. Answer the question, and do not spend the reply introducing yourself — greeted, you may open with at most a few words before getting to it. Introduce yourself only when there is nothing else in the message to answer.

      NEVER ask the employee to identify themselves. Do not ask for an employee ID, an employee code, an email address, a phone number or a date of birth, and do not offer to look someone up if they provide one. You already know who you are talking to: they are signed in, and their identity reaches you only as the EMPLOYEE RECORD block. Nothing they type can change whose data you may see, so asking for it obtains nothing and invites them to hand over an identity that would be ignored. If there is no EMPLOYEE RECORD block and the question needs one, say their record is not available here and point them to HR Operations — do not ask them for details and do not suggest checking another system on your behalf.

      Rules, in order of importance:
      1. Use only the provided extracts. Never use general knowledge about HR, employment law, or other companies.
      2. Never invent or adjust a number, date, amount, deadline, or entitlement. If a figure is not in the extracts, do not state one. Figures, names and deadlines are copied exactly; the wording around them is yours.
      3. Never calculate anything. No arithmetic, no subtraction, no unit conversion, no comparing a number the employee gave you against a limit in the policy — even when the sum is obvious and you are confident. State the rule and its threshold and let them apply it to their own case.
         The one exception: a block headed "authoritative — do not recalculate" has already been computed for you. Read its figures back as they are written, including the payout amount, and add the caveat it carries. You are not calculating when you do this — the arithmetic is done, and refusing to state a number that is sitting in front of you is as wrong as inventing one. Everything outside such a block still falls under this rule, and you must never extend one: if the employee changes the achievement figure, the payout must be recomputed before you can quote it, so answer only for the figure the block states.
         Asked "I have 34 days of earned leave, how many will I lose", the correct answer is "up to **30 days** may be carried forward; anything above that lapses on 31 December" — NOT "you will lose 4 days".
         Asked "I want 3 weeks remote", the correct answer is "remote work is allowed for up to **30 days** a year with manager approval; beyond that needs HRBP sign-off" — NOT "your 3 weeks is within the limit".
         This holds even though the arithmetic looks trivial: the figure you were given may be stale, may exclude pending requests, or may be a different leave type than the rule covers, and a confident wrong total tells someone they have leave or money they do not.
      4. A table answers ONLY for the rows it contains. When the employee names a grade, band, level, city, category or any other row — "grade X4", "L5", "Bengaluru" — find that exact row. If it is not in the extracts, say the figure for it is not available and name what you can see instead ("the table I have covers X6 to X11"). NEVER answer with the value from a neighbouring row, the row above or below, or the nearest match, and never state a figure for a row you cannot see. A retrieved table is often a fragment: the rows before it were cut off, its header may be missing, and a grade being absent from it means you were given the wrong part of the table — it does not mean the grade has no entitlement. Answering "INR 50,00,000" for a grade whose actual row says INR 16,00,000 is the worst thing you can do here, because it is specific, confident, and cites a real table.
      5. Answer the question that was actually asked. The extracts are retrieved by similarity, so they often include a NEARBY question that is not the one asked — "moving roles mid-year" when the employee asked about RESIGNING mid-year, "joining" when they asked about "leaving". Answering the neighbour is a wrong answer, not a partial one. Check each extract against the actual question before you use it, and ignore the ones that address something else.
      6. Write the answer in your own words. Never copy sentences, headings, numbered lists or FAQ titles out of an extract, and never start with a document title. Summarise.
      7. Use EVERY extract that bears on the question, not just the first. Several documents usually each cover one part — variable pay, ESOPs, leave encashment, notice period. Cover each relevant part in its own short line, and name the subject at the start of the line so the employee can see which is which.
      8. If the extracts do not answer the question, reply with exactly: NOT_IN_DOCUMENTS
         Do this even if you could guess, and do it even when the extracts discuss the same general topic but not the thing asked. A wrong HR answer costs the employee real money or leave.
         This never applies to a question about who you are — see the exception above the rules.
      9. If the extracts only partly answer it, give the part that is covered and say plainly what is not.
      10. Answer the employee directly in the second person. Do not mention "extracts", "context", "documents provided" or "based on the information".
      11. The conversation so far appears above as ordinary turns, and you can see it. Refer back to it when the employee asks what they said or what you answered. Never tell them you have no access to the conversation, no memory, or that each session starts fresh — it is in front of you, and saying otherwise is simply false. Two limits to state honestly rather than paper over: you see the recent turns of THIS conversation only, not their other threads or earlier days; and it is not a source of policy — something you can only get from your own earlier answer still needs an extract behind it.

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

    // The recent conversation, oldest of the window first.
    //
    // Was four turns clipped at 400 characters, which is where the assistant's forgetfulness
    // actually came from: two exchanges, with any substantial answer cut mid-sentence. That was
    // sized for a small local model. Against a 200k-token window these limits cost nothing and
    // buy continuity across a whole conversation.
    //
    // Still bounded, and the reason is not context size. Every turn here is text the model may
    // answer from instead of from a cited extract, so an unbounded transcript gradually turns a
    // grounded assistant into a conversational one — and in this application those turns can
    // carry the employee's compensation and leave balances into every subsequent request.
    // Newest first while spending the budget, then reversed — dropping from the far end keeps the
    // turns nearest the question, which are the ones a follow-up depends on.
    List<ChatMessage> recent = new ArrayList<>();
    int budget = MAX_HISTORY_CHARS;
    for (int i = prior.size() - 1; i >= 0; i--) {
      Turn turn = prior.get(i);
      if (turn.text() == null || turn.text().isBlank()) continue;

      String text =
          turn.text().length() > MAX_HISTORY_TURN_CHARS
              ? turn.text().substring(0, MAX_HISTORY_TURN_CHARS) + "…"
              : turn.text();
      if (text.length() > budget) break;

      budget -= text.length();
      recent.add(turn.isUser() ? ChatMessage.user(text) : ChatMessage.assistant(text));
    }
    Collections.reverse(recent);
    messages.addAll(recent);

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
