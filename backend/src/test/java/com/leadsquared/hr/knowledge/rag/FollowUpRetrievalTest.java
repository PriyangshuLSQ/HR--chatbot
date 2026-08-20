package com.leadsquared.hr.knowledge.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.leadsquared.hr.knowledge.claude.ChatMessage;
import com.leadsquared.hr.knowledge.claude.ClaudeClient;
import com.leadsquared.hr.knowledge.model.KnowledgeAnswer;
import com.leadsquared.hr.knowledge.model.KnowledgeChunk;
import com.leadsquared.hr.knowledge.model.KnowledgeDoc;
import com.leadsquared.hr.knowledge.model.RetrievedChunk;
import com.leadsquared.hr.knowledge.model.SourceKind;
import com.leadsquared.hr.knowledge.ollama.OllamaClient;
import com.leadsquared.hr.knowledge.ollama.OllamaStatus;
import com.leadsquared.hr.knowledge.parse.ImageTextExtractor;
import com.leadsquared.hr.knowledge.retrieve.Retriever;
import com.leadsquared.hr.knowledge.store.KnowledgeSnapshot;
import com.leadsquared.hr.knowledge.store.KnowledgeStore;
import com.leadsquared.hr.knowledge.store.QdrantStore;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Retrieval for a question that cannot stand on its own.
 *
 * <p>From a real transcript. Asked "what do you know about leave policy?", Robin answered well,
 * and its answer included that more than 10 consecutive working days needs HRBP sign-off. Asked
 * "what if I take more than 10 days" one message later, it offered a menu — "How to apply for
 * leave / Maternity / paternity leave / Work-from-home policy" — because the follow-up names no
 * subject, retrieved nothing, and the frontend falls back to a clarifying question when the
 * documents come back empty. The answer was in the previous bubble.
 *
 * <p>The retry is deliberately conditional, so these tests pin the conditions as much as the
 * behaviour: search twice only after the first attempt has genuinely missed, and never let the
 * widened query become the question the model is asked to answer.
 */
class FollowUpRetrievalTest {

  private static final String EARLIER = "what do you know about leave policy?";
  private static final String FOLLOW_UP = "what if I take more than 10 days";
  private static final String WIDENED = EARLIER + " " + FOLLOW_UP;

  private KnowledgeStore store;
  private Retriever retriever;
  private ClaudeClient claude;
  private RagService rag;

  private final KnowledgeSnapshot snapshot =
      new KnowledgeSnapshot(List.of(doc()), List.of(chunk()), Set.of("chunk-1"));

  @BeforeEach
  void setUp() {
    store = mock(KnowledgeStore.class);
    retriever = mock(Retriever.class);
    claude = mock(ClaudeClient.class);
    QdrantStore qdrant = mock(QdrantStore.class);
    OllamaClient ollama = mock(OllamaClient.class);

    when(store.snapshot()).thenReturn(snapshot);
    // No embedding model: the dense half sits out, so these tests exercise the retry decision
    // without standing up a vector store to do it.
    when(ollama.getStatus()).thenReturn(new OllamaStatus(false, "http://localhost:11434", List.of(), null, null));
    when(claude.isConfigured()).thenReturn(true);
    when(claude.model()).thenReturn("claude-haiku-4-5");
    when(claude.chat(any())).thenReturn("Leave over 10 working days needs HRBP sign-off.");

    rag = new RagService(store, qdrant, retriever, ollama, claude, mock(ImageTextExtractor.class));
  }

  private static KnowledgeDoc doc() {
    return new KnowledgeDoc(
        "doc-1", "LeadSquared Leave Policy", SourceKind.MD, "Leave policy",
        "leave.md", 100L, "2026-08-01", "hr@leadsquared.com", 1, null, null);
  }

  private static KnowledgeChunk chunk() {
    return new KnowledgeChunk(
        "chunk-1",
        "doc-1",
        0,
        List.of("Applying for Leave"),
        "Leave of more than 10 consecutive working days requires HRBP sign-off in addition to"
            + " manager approval.",
        // docTitle: this test builds the record directly to pin an exact tf/length, so the title
        // is left off rather than recomputed. Retrieval behaviour under test does not read it.
        null,
        null,
        Map.of(),
        18);
  }

  private static Retriever.Result hit(double confidence) {
    return new Retriever.Result(
        List.of(new RetrievedChunk(chunk(), confidence, 0, confidence, doc())), confidence, false);
  }

  /** Below {@link Thresholds#RELEVANCE}, which is what ends a policy question. */
  private static Retriever.Result miss() {
    return new Retriever.Result(List.of(), 0.11, false);
  }

  private void stub(String query, Retriever.Result result) {
    when(retriever.retrieve(eq(query), any(), any(), anyBoolean(), anyInt())).thenReturn(result);
  }

  private KnowledgeAnswer askFollowUp() {
    return rag.ask(
        FOLLOW_UP,
        List.of(new RagService.Turn("user", EARLIER), new RagService.Turn("bot", "…HRBP sign-off.")),
        false,
        null);
  }

  @Test
  @DisplayName("A follow-up that finds nothing alone is retried with the earlier question")
  void retriesWithContext() {
    stub(FOLLOW_UP, miss());
    stub(WIDENED, hit(0.62));

    KnowledgeAnswer answer = askFollowUp();

    // Previously this was KnowledgeAnswer.none, which is what put the topic menu on screen.
    assertThat(answer.mode()).isEqualTo(KnowledgeAnswer.Mode.GENERATED);
    assertThat(answer.answer()).contains("HRBP");
    verify(retriever).retrieve(eq(WIDENED), any(), any(), anyBoolean(), anyInt());
  }

  @Test
  @DisplayName("The model is still asked the question the employee typed, not the widened one")
  void thePromptKeepsTheRealQuestion() {
    stub(FOLLOW_UP, miss());
    stub(WIDENED, hit(0.62));

    askFollowUp();

    ArgumentCaptor<List<ChatMessage>> prompt = ArgumentCaptor.captor();
    verify(claude).chat(prompt.capture());
    List<ChatMessage> messages = prompt.getValue();
    String finalTurn = messages.get(messages.size() - 1).content();

    // The widening is a search device. If it reached the prompt as the question, the model would
    // be free to answer "what do you know about leave policy?" a second time instead.
    assertThat(finalTurn).contains("Question: " + FOLLOW_UP);
    assertThat(finalTurn).doesNotContain("Question: " + WIDENED);
  }

  @Test
  @DisplayName("A question that retrieves well on its own is never searched twice")
  void noRetryWhenTheFirstAttemptSucceeds() {
    stub(FOLLOW_UP, hit(0.71));

    KnowledgeAnswer answer = askFollowUp();

    assertThat(answer.mode()).isEqualTo(KnowledgeAnswer.Mode.GENERATED);
    verify(retriever, times(1)).retrieve(any(), any(), any(), anyBoolean(), anyInt());
  }

  @Test
  @DisplayName("With nothing asked before it, a miss stays a miss")
  void noHistoryMeansNoRetry() {
    stub(FOLLOW_UP, miss());

    KnowledgeAnswer answer = rag.ask(FOLLOW_UP, List.of(), false, null);

    assertThat(answer.mode()).isEqualTo(KnowledgeAnswer.Mode.NONE);
    verify(retriever, times(1)).retrieve(any(), any(), any(), anyBoolean(), anyInt());
  }

  @Test
  @DisplayName("A retry that also finds nothing relevant does not rescue the answer")
  void aWeakRetryIsDiscarded() {
    stub(FOLLOW_UP, miss());
    // Better than the direct attempt, still under the relevance bar. Answering from this would
    // be the "confidently-worded reply built on a 30% match" the thresholds exist to refuse.
    stub(WIDENED, new Retriever.Result(List.of(new RetrievedChunk(chunk(), 0.2, 0, 0.2, doc())), 0.2, false));

    KnowledgeAnswer answer = askFollowUp();

    assertThat(answer.mode()).isEqualTo(KnowledgeAnswer.Mode.NONE);
    verify(retriever, times(2)).retrieve(any(), any(), any(), anyBoolean(), anyInt());
  }

  @Test
  @DisplayName("The transcript ending with the question being asked is not used as its own context")
  void doesNotUseItselfAsContext() {
    stub(FOLLOW_UP, miss());
    stub(WIDENED, hit(0.62));

    // The chat client sends the transcript including the turn just added.
    rag.ask(
        FOLLOW_UP,
        List.of(
            new RagService.Turn("user", EARLIER),
            new RagService.Turn("bot", "…HRBP sign-off."),
            new RagService.Turn("user", FOLLOW_UP)),
        false,
        null);

    // Skipped past the duplicate to the question before it — widening a query with itself would
    // change nothing and waste a search.
    verify(retriever).retrieve(eq(WIDENED), any(), any(), anyBoolean(), anyInt());
  }
}
