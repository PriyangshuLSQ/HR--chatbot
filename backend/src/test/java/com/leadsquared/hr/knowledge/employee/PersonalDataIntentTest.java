package com.leadsquared.hr.knowledge.employee;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Routing a question, and routing a follow-up to one.
 *
 * <p>The follow-up cases exist because the contextual exchange in the spec used to fail in the
 * worst available way. "And if I achieve 90%, what will I get?" names no field the vocabulary
 * recognises — {@code achieve} is not {@code achievement} — so it routed to policy retrieval
 * with no record and no matrix, while the model still held the variable target from its own
 * previous answer and a rule telling it never to calculate. The outcomes were a refusal, or a
 * payout the model multiplied out itself. The second is why these are tests and not a manual
 * check: it reads exactly like the correct answer.
 */
class PersonalDataIntentTest {

  private static final List<String> ASKED_TARGET = List.of("What is my variable pay target?");

  @Nested
  @DisplayName("The spec's contextual exchange")
  class ContextualExchange {

    @Test
    @DisplayName("Turn 1 loads the record but computes nothing")
    void turnOneReadsBack() {
      String q = "What is my variable pay target?";
      assertThat(PersonalDataIntent.fieldsFor(q))
          .containsExactly(PersonalDataIntent.VARIABLE_PAY);
      // Asked for a target, not a payout: nothing to compute, and nothing to ask them for.
      assertThat(PersonalDataIntent.wantsComputation(q)).isFalse();
    }

    @Test
    @DisplayName("Turn 2 resolves to a payout on the caller's own record, at the stated figure")
    void turnTwoComputes() {
      String q = "And if I achieve 90%, what will I get?";

      assertThat(PersonalDataIntent.fieldsFor(q, ASKED_TARGET))
          .containsExactly(PersonalDataIntent.VARIABLE_PAY);
      assertThat(PersonalDataIntent.wantsComputation(q, true)).isTrue();
      assertThat(PersonalDataIntent.statedAchievement(q, ASKED_TARGET))
          .contains(new BigDecimal("90"));
    }

    @Test
    @DisplayName("A follow-up with no figure of its own reuses the one stated earlier")
    void barefollowUpInheritsTheFigure() {
      List<String> prior = List.of("if I achieve 90%, what is my payout?");
      String q = "And what will I get?";

      // Judged alone this asks for nothing — no payroll word, no number.
      assertThat(PersonalDataIntent.fieldsFor(q)).isEmpty();

      assertThat(PersonalDataIntent.fieldsFor(q, prior))
          .containsExactly(PersonalDataIntent.VARIABLE_PAY);
      assertThat(PersonalDataIntent.wantsComputation(q, true)).isTrue();
      // 90, not last cycle's actual — answering off the appraisal record would quietly answer a
      // different question from the one asked.
      assertThat(PersonalDataIntent.statedAchievement(q, prior)).contains(new BigDecimal("90"));
    }

    @ParameterizedTest(name = "\"{0}\" continues the payout conversation")
    @ValueSource(
        strings = {
          "And at 95%?",
          "what about 110%?",
          "and if I hit 120%, what will I get?",
          "so what would I get at 70 percent?",
        })
    void shorthandFollowUpsStayOnTopic(String question) {
      assertThat(PersonalDataIntent.fieldsFor(question, ASKED_TARGET))
          .contains(PersonalDataIntent.VARIABLE_PAY);
      assertThat(PersonalDataIntent.wantsComputation(question, true)).isTrue();
    }
  }

  @Nested
  @DisplayName("Inheritance does not leak into unrelated questions")
  class NoOverReach {

    @ParameterizedTest(name = "\"{0}\" after a pay question is still policy-only")
    @ValueSource(
        strings = {
          "What is the WFH policy?",
          "How do I claim reimbursement?",
          // Opens with "and", so it reads as a continuation — but it names its own subject and
          // is not asking about the asker, which makes it a policy question either way.
          "And what does the leave policy say about sick leave?",
          "so how long is the notice period at this company?",
        })
    void newTopicsDoNotInherit(String question) {
      assertThat(PersonalDataIntent.fieldsFor(question, ASKED_TARGET)).isEmpty();
    }

    @Test
    @DisplayName("With no conversation behind it, a bare follow-up inherits nothing")
    void nothingToInheritFrom() {
      assertThat(PersonalDataIntent.fieldsFor("And what will I get?", List.of())).isEmpty();
      assertThat(PersonalDataIntent.fieldsFor("And what will I get?", null)).isEmpty();
    }

    @Test
    @DisplayName("Inheritance takes the most recent question that named a subject")
    void mostRecentSubjectWins() {
      List<String> prior =
          List.of("What is my variable pay target?", "What is my earned leave balance?");
      // The conversation moved to leave, so a bare follow-up is about leave, not pay.
      assertThat(PersonalDataIntent.fieldsFor("And what about mine?", prior))
          .containsExactly(PersonalDataIntent.LEAVE);
    }
  }

  @Nested
  @DisplayName("Existing routing is unchanged")
  class Regressions {

    @Test
    @DisplayName("A policy question with no first person needs no record")
    void policyQuestionsStayPolicy() {
      assertThat(PersonalDataIntent.needsOwnRecord("what does the leave policy say about sick leave"))
          .isFalse();
      assertThat(PersonalDataIntent.fieldsFor("how much maternity leave does the company give"))
          .isEmpty();
    }

    @ParameterizedTest(name = "\"{0}\" needs the caller's own record")
    @ValueSource(
        strings = {
          "What is my leave balance?",
          "What is my CTC?",
          "what is my grade and designation?",
          "how many days was I present last month?",
          "what was my last appraisal rating?",
        })
    void firstPersonQuestionsLoadTheRecord(String question) {
      assertThat(PersonalDataIntent.needsOwnRecord(question)).isTrue();
    }

    @Test
    @DisplayName("Without context, a bare 'what will I get' still asks for nothing computable")
    void computationGateHoldsWithoutContext() {
      // The one-argument form is the pre-existing contract and several callers rely on it.
      assertThat(PersonalDataIntent.wantsComputation("And what will I get?")).isFalse();
      assertThat(PersonalDataIntent.wantsComputation("what is my variable pay target?")).isFalse();
      assertThat(PersonalDataIntent.wantsComputation("if I achieve 90% what is my payout?"))
          .isTrue();
    }

    @Test
    @DisplayName("A stated figure in the question beats one from an earlier turn")
    void currentFigureWins() {
      assertThat(
              PersonalDataIntent.statedAchievement(
                  "and at 75%?", List.of("if I achieve 90% what is my payout?")))
          .contains(new BigDecimal("75"));
    }

    @Test
    @DisplayName("A rupee figure or a year is not an achievement percentage")
    void bogusPercentagesAreIgnored() {
      assertThat(PersonalDataIntent.statedAchievement("my target is 400000")).isEmpty();
      assertThat(PersonalDataIntent.statedAchievement("what about FY 2026-27")).isEmpty();
      assertThat(PersonalDataIntent.statedAchievement("I achieved 250%")).isEmpty();
    }
  }

  /**
   * Asking who you are — your name, your employee code.
   *
   * <p>Regression, twice over: neither vocabulary existed, so "what is my emp id?" and "tell my
   * name" named no field, loaded no record, and were answered from the policy corpus — "check
   * your Darwinbox profile, or contact HR Ops". Both values were in the database throughout, and
   * both are printed in every fact block's header. It is the failure shape hardest to catch,
   * because a refusal reads as careful.
   */
  @Nested
  @DisplayName("Who the asker is")
  class Identity {

    @ParameterizedTest
    @ValueSource(
        strings = {
          "what is my emp id?",
          "what is my employee id",
          "whats my employee code",
          "my emp code please",
          "can you tell me my empid",
          "what's my employee number?",
          "what is my staff id",
          "tell my name",
          "what is my name",
          "do you know my name?",
          "who am i",
          "show me my details",
          "what does my record say",
          // Pronoun dropped entirely. "tell name" used to reach the policy corpus, where the
          // model invented an instruction to supply an employee ID — the one input this system
          // is built never to trust.
          "tell name",
          "tell me name",
          "show employee code",
          "emp id",
          "name?",
        })
    @DisplayName("Every phrasing of the question loads the record")
    void loadsTheRecord(String question) {
      assertThat(PersonalDataIntent.needsOwnRecord(question)).isTrue();
      assertThat(PersonalDataIntent.fieldsFor(question))
          .contains(PersonalDataIntent.EMPLOYMENT);
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
          "what is the employee code format",
          "what is Lata Thakur's employee id",
          "whose name goes on the nominee form",
          "what is the name of the leave policy document",
          // The elliptical form is an imperative aimed at an identity noun, or that noun alone.
          // A policy question takes neither shape, including these near misses.
          "what name should I put on the reimbursement form",
          "tell me about the referral policy",
        })
    @DisplayName("A question about someone else, or about a document, stays a policy question")
    void notFirstPersonStaysPolicy(String question) {
      // The first-person gate is what makes a field word personal, and it still applies. Who may
      // ask about another employee at all is CrossEmployeeGuard's decision, not this class's —
      // this only declines to treat the question as self-service.
      //
      // The last two matter for the audit trail rather than for disclosure: a bare `name` in the
      // vocabulary would have made every policy question mentioning one record a query against
      // the asker's employee data, which is a false entry in the log Rule 5 asks for.
      assertThat(PersonalDataIntent.needsOwnRecord(question)).isFalse();
    }
  }
}
