package com.leadsquared.hr.knowledge.ollama;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Reasoning-block stripping.
 *
 * <p>Qwen3-family models emit {@code <think>} blocks. {@code think: false} should
 * stop them at the source, but this is the second line of defence and the one
 * worth testing: a leaked trace would be shown to an employee as their HR answer,
 * and — worse — would bury the NOT_IN_DOCUMENTS marker inside prose, turning a
 * refusal into something the client reads as a real answer.
 */
class VisibleTextTest {

  @Test
  void stripsACompleteBlock() {
    assertThat(OllamaClient.visibleText("<think>weighing the extracts</think>You get **12** days."))
        .isEqualTo("You get **12** days.");
  }

  @Test
  void stripsAcrossNewlines() {
    assertThat(
            OllamaClient.visibleText(
                "<think>\nthe passage says 12\nbut which leave type?\n</think>\nCasual Leave is **12** days."))
        .isEqualTo("\nCasual Leave is **12** days.");
  }

  @Test
  void truncatesAtAnUnclosedTag() {
    // Mid-stream: everything after the opening tag is still reasoning, so it must
    // not be emitted just because the closing tag has not arrived yet.
    assertThat(OllamaClient.visibleText("Partial answer <think>now reasoning about"))
        .isEqualTo("Partial answer ");
  }

  @Test
  void aRefusalInsideReasoningIsNotMistakenForAnAnswer() {
    // The failure this class exists to prevent: the marker survives as the whole
    // visible output, so the caller still recognises the refusal.
    String out =
        OllamaClient.visibleText("<think>The extracts cover leave, not wifi.</think>NOT_IN_DOCUMENTS");
    assertThat(out).isEqualTo("NOT_IN_DOCUMENTS");
  }

  @Test
  void leavesOrdinaryAnswersAlone() {
    String answer = "Gratuity equals last drawn basic salary multiplied by **15/26**.";
    assertThat(OllamaClient.visibleText(answer)).isEqualTo(answer);
  }

  @Test
  void isCaseInsensitiveAndHandlesTheThinkingSpelling() {
    assertThat(OllamaClient.visibleText("<THINKING>hmm</THINKING>Answer.")).isEqualTo("Answer.");
  }

  @Test
  void handlesSeveralBlocks() {
    assertThat(OllamaClient.visibleText("<think>a</think>One. <think>b</think>Two."))
        .isEqualTo("One. Two.");
  }
}
