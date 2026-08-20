package com.leadsquared.hr.knowledge.rag;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Which questions are treated as carrying no subject of their own.
 *
 * <p>This is a two-sided boundary and both sides cost something real. Too narrow and a bare
 * follow-up is searched on its own: "what about x2?" one turn after a car lease answer matched the
 * referral bonus table on the single token they have in common, and was answered — confidently,
 * with citations, about a policy nobody had mentioned. Too wide and a question that names its own
 * subject gets the previous one folded in anyway: "what about maternity leave?" then retrieved the
 * car lease passages alongside the maternity ones and cited them under the answer.
 *
 * <p>So the cases below are the actual failures, kept as the specification of where the line sits.
 */
class RagServiceFollowUpTest {

  @DisplayName("a referent with no subject to embed needs the previous question")
  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "what about x1?",
        "what about x1",
        "and what about x2?",
        "What about X5?",
        "how about x11?",
        "ok what about x3?",
        "and x2?",
        "also for x4",
        "what if i resign",
      })
  void treatedAsBare(String question) {
    assertTrue(RagService.isBareFollowUp(question), "should need context: " + question);
  }

  @DisplayName("a question naming its own subject is searched as asked")
  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "what about maternity leave?",
        "what about the work from home policy?",
        "what about sick leave entitlement",
        "what is the car lease entitlement value for the employee at grade x7?",
        "how many days of work from home can i take?",
        "what is my ctc",
        "tell me about leave policy?",
      })
  void treatedAsSelfContained(String question) {
    assertFalse(RagService.isBareFollowUp(question), "should stand alone: " + question);
  }

  /**
   * Questions the corpus cannot answer or veto, because the answer is in the system prompt.
   *
   * <p>These were gated behind a passage-relevance score they had no way to earn. "who r u" scored
   * 0.436 and introduced itself; "hi, who r u" scored 0.303 and got the escalation offer written
   * for leave and pay questions. A greeting decided it.
   */
  @DisplayName("a question about the assistant is answered from the prompt, not the corpus")
  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "who r u",
        "hi, who r u",
        "hi who are you",
        "who are you?",
        "what are you",
        "hello, what can you do",
        "what can you help with",
        "are you a bot",
        "are you human",
        "what is your name",
        "what's your name",
        "so who am i talking to",
      })
  void aboutTheAssistant(String question) {
    assertTrue(RagService.isAboutAssistant(question), "should be an identity question: " + question);
  }

  /**
   * The other side of it, and the reason the match is length-bounded.
   *
   * <p>"My" and "I" belong to the employee — the system prompt is emphatic that those are a
   * different question, answered from their record. And "what can you do about my leave balance"
   * shares four words with "what can you do" while being a leave question; answering it with an
   * introduction would be worse than the bug this fixes.
   */
  @DisplayName("a question about the employee or about HR is not an identity question")
  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "what is my name",
        "who am i",
        "what is my employee code",
        "hi, what is the leave policy",
        "what can you tell me about maternity leave",
        "what can you do about my leave balance",
        "what is the car lease entitlement value for the employee at grade x7?",
      })
  void notAboutTheAssistant(String question) {
    assertFalse(
        RagService.isAboutAssistant(question), "should not be an identity question: " + question);
  }
}
