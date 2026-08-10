package com.leadsquared.hr.knowledge.employee;

import com.leadsquared.hr.knowledge.model.Employee;
import com.leadsquared.hr.knowledge.model.KnowledgeAnswer;
import com.leadsquared.hr.knowledge.payroll.VariablePayCalculator;
import com.leadsquared.hr.knowledge.payroll.VariablePayMatrix;
import com.leadsquared.hr.knowledge.payroll.VariablePayResult;
import com.leadsquared.hr.knowledge.rag.RagService;
import com.leadsquared.hr.knowledge.security.CurrentUser;
import com.leadsquared.hr.knowledge.security.SignedInUser;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Routes a question to policy retrieval, to the caller's own record, or to a refusal.
 *
 * <p>Sits in front of {@link RagService} so that class never touches employee data — one
 * place fetches it, and that place is {@link EmployeeDataService}. The three outcomes:
 *
 * <ol>
 *   <li><b>Refused.</b> The question reaches for someone else. No lookup, no model call, the
 *       fixed privacy message, and an audit entry.
 *   <li><b>Personal.</b> The question needs the caller's own record. It is loaded by session
 *       identity, rendered to facts, a payout computed when one is asked for, and the whole
 *       lot handed to the model alongside the policy extracts.
 *   <li><b>Policy.</b> Everything else, unchanged — retrieval and generation as before, with
 *       no employee data in the prompt at all.
 * </ol>
 *
 * <p>Ordering is deliberate: the refusal check runs before authentication is even considered,
 * so an unauthenticated caller asking about a colleague gets the privacy message rather than a
 * login prompt that implies signing in would help.
 */
@Service
public class EmployeeAnswerService {

  private static final Logger log = LoggerFactory.getLogger(EmployeeAnswerService.class);

  /** Shown when a data question arrives with no verified session. Rule 3. */
  private static final String NEEDS_SIGN_IN =
      "I can only look up your employment details once you're signed in with your work "
          + "account. Policy questions I can answer either way.";

  /** Shown when the extract has no row for this identity. */
  private static final String NOT_IN_EXTRACT =
      "I couldn't find an employment record for your account in the current HR data extract. "
          + "This can happen for new joiners and contractors — please contact HR Ops and they "
          + "can confirm your details.";

  private final RagService rag;
  private final AdminEmployeeQueryService adminQueries;
  private final CurrentUser currentUser;
  private final EmployeeDataService employees;
  private final VariablePayCalculator variablePay;

  public EmployeeAnswerService(
      RagService rag,
      CurrentUser currentUser,
      EmployeeDataService employees,
      VariablePayCalculator variablePay,
      AdminEmployeeQueryService adminQueries) {
    this.rag = rag;
    this.adminQueries = adminQueries;
    this.currentUser = currentUser;
    this.employees = employees;
    this.variablePay = variablePay;
  }

  /** What the routing decided: either a finished answer, or context to answer with. */
  private record Routing(KnowledgeAnswer shortCircuit, String personalContext) {
    static Routing answerNow(KnowledgeAnswer answer) {
      return new Routing(answer, null);
    }

    static Routing continueWith(String context) {
      return new Routing(null, context);
    }

    static final Routing POLICY_ONLY = new Routing(null, null);
  }

  public KnowledgeAnswer ask(
      String question, List<RagService.Turn> history, boolean extractiveOnly, String threadId) {
    Routing routing = route(question, threadId);
    if (routing.shortCircuit() != null) return routing.shortCircuit();
    return rag.ask(question, history, extractiveOnly, routing.personalContext());
  }

  public KnowledgeAnswer askStreaming(
      String question, List<RagService.Turn> history, Consumer<String> onToken, String threadId) {
    Routing routing = route(question, threadId);
    if (routing.shortCircuit() != null) {
      // Streamed so the client's rendering path is identical either way; a refusal that
      // arrived through a different channel would need its own handling in the UI.
      onToken.accept(routing.shortCircuit().answer());
      return routing.shortCircuit();
    }
    return rag.askStreaming(question, history, onToken, routing.personalContext());
  }

  private Routing route(String question, String threadId) {
    SignedInUser user = currentUser.get().orElse(null);

    // The admin exception runs FIRST, and deliberately not inside the decline branch below.
    //
    // It was there originally, which coupled admin access to the guard's sensitivity: a question
    // the guard failed to flag ("Aishwarya Gandhi's leave balances" — plural, which the field
    // vocabulary missed) skipped the admin lookup entirely and fell through to policy retrieval.
    // Whether an admin may look a named record up has nothing to do with whether an employee
    // would have been refused for the same phrasing, so the two checks are now independent.
    //
    // Employees are unaffected: isAvailableTo() fails on the role before any query is issued.
    if (adminQueries.isAvailableTo(user)) {
      List<Employee> others = adminQueries.findNamedIn(question, user);
      if (!others.isEmpty()) {
        employees.recordAdminCrossEmployeeRead(
            user, question, others.stream().map(Employee::employeeCode).toList(), threadId);
        return Routing.continueWith(adminQueries.renderForAdmin(others, user));
      }
      // Named nobody this class can resolve — an aggregate, a ranking, someone described rather
      // than named. Falls through, so it is refused like anyone else's would be: the exception
      // covers looking a named record up and nothing wider.
    }

    List<CrossEmployeeGuard.Reason> declineReasons = CrossEmployeeGuard.reasonsToDecline(question);
    if (!declineReasons.isEmpty()) {
      employees.recordDenial(user, question, declineReasons, threadId);
      return Routing.answerNow(KnowledgeAnswer.declined(CrossEmployeeGuard.DENIAL_MESSAGE));
    }

    Set<String> fields = PersonalDataIntent.fieldsFor(question);
    if (fields.isEmpty()) return Routing.POLICY_ONLY;

    if (user == null) {
      employees.readForQuestion(null, question, intentOf(fields), List.copyOf(fields), threadId);
      return Routing.answerNow(KnowledgeAnswer.declined(NEEDS_SIGN_IN));
    }

    Optional<Employee> found =
        employees.readForQuestion(
            user, question, intentOf(fields), List.copyOf(fields), threadId);
    if (found.isEmpty()) {
      return Routing.answerNow(KnowledgeAnswer.declined(NOT_IN_EXTRACT));
    }

    Employee employee = found.get();
    StringBuilder context = new StringBuilder(EmployeeFacts.render(employee, fields));

    if (fields.contains(PersonalDataIntent.VARIABLE_PAY)) {
      appendVariablePay(context, employee, question);
    }

    return Routing.continueWith(context.toString());
  }

  /**
   * Adds the payout matrix and, when a payout was asked for, the computed figure.
   *
   * <p>The computation happens here rather than being left to the model. What the model
   * receives is a finished number it is told not to recalculate — see
   * {@link VariablePayResult#asFactBlock()}.
   */
  private void appendVariablePay(StringBuilder context, Employee employee, String question) {
    if (!variablePay.isAvailable()) {
      context.append(
          "\nNo variable pay matrix is loaded, so a payout cannot be computed. Tell the employee"
              + " their target and refer the calculation to HR Ops.\n");
      return;
    }

    variablePay
        .matrixFor(employee)
        .ifPresent(
            dept -> {
              context.append("\nVariable payout matrix for ").append(dept.name()).append(":\n");
              for (VariablePayMatrix.Slab slab : dept.slabs()) {
                context
                    .append("- ")
                    .append(slab.fromPercentInclusive().stripTrailingZeros().toPlainString())
                    .append("% to ")
                    .append(
                        slab.toPercentInclusive() == null
                            ? "above"
                            : slab.toPercentInclusive().stripTrailingZeros().toPlainString() + "%")
                    .append(" achievement pays ")
                    .append(slab.payoutPercentOfTarget().stripTrailingZeros().toPlainString())
                    .append("% of target\n");
              }
              if (dept.notes() != null) context.append("Matrix note: ").append(dept.notes()).append("\n");
            });

    if (!PersonalDataIntent.wantsComputation(question)) return;

    // The figure the employee stated, or their latest appraisal achievement when they only
    // asked "what will I get" — a follow-up question after a payout answer carries no number
    // of its own, and re-asking for one is exactly the re-prompting the spec rules out.
    Optional<BigDecimal> achievement =
        PersonalDataIntent.statedAchievement(question)
            .or(() -> variablePay.latestAchievement(employee));

    if (achievement.isEmpty()) {
      context.append(
          "\nNo target achievement figure is available, and none was stated. Ask the employee"
              + " for their achievement percentage — one question, nothing else.\n");
      return;
    }

    Optional<VariablePayResult> result = variablePay.compute(employee, achievement.get());
    if (result.isEmpty()) {
      context.append(
          "\nA payout could not be computed from the available data. Refer the employee to"
              + " HR Ops for the calculation rather than estimating it.\n");
      return;
    }

    context.append("\n").append(result.get().asFactBlock());
    log.info(
        "Variable pay computed for {} — achievement {}%, matrix {}",
        employee.employeeCode(),
        achievement.get().stripTrailingZeros().toPlainString(),
        result.get().matrixVersion());
  }

  private static String intentOf(Set<String> fields) {
    return "own_record:" + String.join(",", fields);
  }
}
