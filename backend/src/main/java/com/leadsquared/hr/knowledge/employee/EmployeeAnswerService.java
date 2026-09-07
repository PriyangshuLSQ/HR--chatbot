package com.leadsquared.hr.knowledge.employee;

import com.leadsquared.hr.knowledge.model.Employee;
import com.leadsquared.hr.knowledge.model.KnowledgeAnswer;
import com.leadsquared.hr.knowledge.model.VariablePayPlan;
import com.leadsquared.hr.knowledge.payroll.FunctionPayAdvisor;
import com.leadsquared.hr.knowledge.payroll.VariablePayCalculator;
import com.leadsquared.hr.knowledge.payroll.VariablePayResult;
import com.leadsquared.hr.knowledge.rag.RagService;
import com.leadsquared.hr.knowledge.security.CurrentUser;
import com.leadsquared.hr.knowledge.security.SignedInUser;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.regex.Pattern;
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
 *
 * <p>Routing reads the conversation, not just the question. "And if I achieve 90%, what will I
 * get?" is a payout question only in light of the turn before it; judged alone it named nothing
 * and fell through to policy retrieval, where the model held the target from its own previous
 * answer, no matrix, and a rule forbidding arithmetic — so the employee got a refusal, or a
 * number the model worked out itself. Inheriting the subject cannot widen whose data is
 * reachable: the record is still resolved from the session, and the cross-employee guard still
 * runs against the current question.
 */
@Service
public class EmployeeAnswerService {

  private static final Logger log = LoggerFactory.getLogger(EmployeeAnswerService.class);

  /**
   * How many of the employee's own recent questions a follow-up may inherit its subject from.
   *
   * <p>Three, because this is a recency heuristic and not a memory: the question two turns back
   * is plausibly still the topic, the one six turns back is a different conversation.
   */
  private static final int MAX_FOLLOW_UP_TURNS = 3;

  /**
   * Concepts unique to the Non-Sales policy, which is how a question is known to be about the
   * employee's own plan rather than a revenue function's.
   */
  private static final java.util.regex.Pattern OWN_PLAN_WORDS =
      java.util.regex.Pattern.compile(
          "\\b(appraisal|rating|ratings|pms|grade|my variable pay|my payout|my ctc)\\b",
          java.util.regex.Pattern.CASE_INSENSITIVE);

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
  private final FunctionPayAdvisor functionPay;

  public EmployeeAnswerService(
      RagService rag,
      CurrentUser currentUser,
      EmployeeDataService employees,
      VariablePayCalculator variablePay,
      FunctionPayAdvisor functionPay,
      AdminEmployeeQueryService adminQueries) {
    this.functionPay = functionPay;
    this.rag = rag;
    this.adminQueries = adminQueries;
    this.currentUser = currentUser;
    this.employees = employees;
    this.variablePay = variablePay;
  }

  /**
   * The asker wanting their own variable pay figure used, rather than one they type.
   *
   * <p>Two shapes, and both were asked in the same conversation: naming the field possessively
   * ("what will <em>my</em> variable payout be") and asking for it outright ("take my variable pay
   * amount <em>from records</em>"). Anything vaguer is left alone — a policy question must stay
   * answerable without reading anybody's record.
   */
  private static final Pattern OWN_FIGURES_REQUEST =
      Pattern.compile(
          "\\bmy\\s+(?:annual\\s+)?(?:variable(?:\\s+pay)?|vp|payout|incentive)\\b"
              + "|\\bfrom\\s+(?:my\\s+)?records?\\b"
              + "|\\b(?:take|use|pull|fetch)\\s+my\\b"
              + "|\\bas\\s+per\\s+my\\s+record",
          Pattern.CASE_INSENSITIVE);

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
    Routing routing = route(question, history, threadId);
    if (routing.shortCircuit() != null) return routing.shortCircuit();
    return rag.ask(question, history, extractiveOnly, routing.personalContext());
  }

  public KnowledgeAnswer askStreaming(
      String question, List<RagService.Turn> history, Consumer<String> onToken, String threadId) {
    Routing routing = route(question, history, threadId);
    if (routing.shortCircuit() != null) {
      // Streamed so the client's rendering path is identical either way; a refusal that
      // arrived through a different channel would need its own handling in the UI.
      onToken.accept(routing.shortCircuit().answer());
      return routing.shortCircuit();
    }
    return rag.askStreaming(question, history, onToken, routing.personalContext());
  }

  private Routing route(String question, List<RagService.Turn> history, String threadId) {
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

    // Read in context: a follow-up like "and if I achieve 90%?" names no field of its own and
    // would otherwise route to policy retrieval, leaving the model to do the arithmetic from
    // whatever figure survived in the transcript — the exact ungrounded number the calculator
    // exists to prevent.
    List<String> prior = priorQuestions(history, question);

    // The revenue-function policies — US, India Sales, PS & CSM — are answered for anybody who
    // asks, whatever function they are in. "Suppose I'm a US sales employee, how would my variable
    // pay be worked out?" is a question about a published document applied to figures the asker
    // invents; it reads no record and names no colleague, so refusing it would treat a policy as
    // if it were personal data. The cross-employee guard has already run above, and this path
    // touches no employee data at all.
    // ... with one exception, added because refusing it was the wrong kind of correct. Asked
    // "what will my variable payout be" and then, plainly, "take my variable pay amount from
    // records", the assistant said it could not — while the figure sat on the record it had
    // already been given permission to read. Nobody else's data is involved: it is the asker's
    // own annual variable target, used as the multiplicand for achievement figures they typed
    // themselves.
    //
    // Narrow on purpose. The record is read only when the question asks for their own figure, so
    // "how does the US plan work" still touches nothing — which is what keeps a published policy
    // explorable by anyone.
    BigDecimal ownVariableTarget = null;
    if (user != null && OWN_FIGURES_REQUEST.matcher(question).find()) {
      ownVariableTarget =
          employees
              .myRecord(user)
              .map(Employee::compensation)
              .map(Employee.Compensation::variableTargetAmount)
              .orElse(null);
    }

    Optional<String> functionPayContext =
        functionPay.contextFor(question, prior, ownVariableTarget);

    // Which of the two the turn is about, decided by what only one of them has.
    //
    // Appraisal ratings and L-grades exist in the Non-Sales policy and nowhere in the other three —
    // those pay on MRR, GRR and utilisation and have no concept of a rating. So a question naming
    // one is about the employee's own plan however the conversation got here, and a question naming
    // neither, in a conversation already on a function policy, is continuing that thread.
    //
    // Both cruder rules failed in opposite directions. Suppressing whenever the conversation had
    // ever touched a function policy silenced "consider the appraisal rating is 3 and calculate"
    // for the rest of the session. Suppressing only on the current question left "take some sample
    // values and calculate" holding two computations, and the model answered the wrong one.
    boolean ownPlanSignal =
        PersonalDataIntent.statedRating(question).isPresent()
            || OWN_PLAN_WORDS.matcher(question).find();
    boolean askingAboutAnotherPolicy = functionPayContext.isPresent() && !ownPlanSignal;

    Set<String> fields = PersonalDataIntent.fieldsFor(question, prior);
    if (fields.isEmpty()) {
      return functionPayContext.map(Routing::continueWith).orElse(Routing.POLICY_ONLY);
    }

    // Recorded in the audit trail: rule 5 asks what was queried and why, and "because the
    // previous turn was about variable pay" is a materially different justification from
    // "because this question asked for it".
    boolean inherited = PersonalDataIntent.fieldsFor(question).isEmpty();
    String intent = intentOf(fields, inherited);

    if (user == null) {
      employees.readForQuestion(null, question, intent, List.copyOf(fields), threadId);
      return Routing.answerNow(KnowledgeAnswer.declined(NEEDS_SIGN_IN));
    }

    Optional<Employee> found =
        employees.readForQuestion(user, question, intent, List.copyOf(fields), threadId);
    if (found.isEmpty()) {
      return Routing.answerNow(KnowledgeAnswer.declined(NOT_IN_EXTRACT));
    }

    Employee employee = found.get();

    // A question about another function's policy gets that policy and nothing else. Rendering the
    // employee's own record beside it gave the model two sources for one question and it blended
    // them — quoting the asker's INR 76,500 target under a US Sales role, and once refusing to use
    // illustrative figures at all because it had "real" ones to hand. Their record answers nothing
    // here: the figures come from the question, and the policy comes from the plan.
    if (askingAboutAnotherPolicy) {
      return Routing.continueWith(functionPayContext.orElse(""));
    }

    StringBuilder context = new StringBuilder(EmployeeFacts.render(employee, fields));

    // Not both, and judged on THIS question rather than on the conversation.
    //
    // Two answers to one question confuse the model: "my variable pay is $120,000 and I'm at 150%
    // of target" names a US Sales figure, and the Non-Sales calculator reads the same sentence as
    // a supposed target and computes a second payout at the asker's own grade. So where a question
    // names another function's policy, that policy is the subject.
    //
    // But the context above is built from the conversation, so that anaphora works — "and at
    // 150%?" after naming a role still resolves. Using its presence to suppress the employee's own
    // computation meant one question about US Sales silenced their own payout for the rest of the
    // session: "consider the appraisal rating is 3 and calculate" came back with "I can't do the
    // arithmetic myself — that's your role", about a figure this service exists to compute, and
    // with a weighting the model invented because it had the other policy's tables and not its own.

    if (!askingAboutAnotherPolicy && fields.contains(PersonalDataIntent.VARIABLE_PAY)) {
      // Whether variable pay is the subject *because of the conversation* rather than because this
      // question said so. That is the condition the computation gate actually wants, and it is not
      // the same as "this question named nothing": "if iam from grade L8 then?" names a grade, so
      // it named something — and was then refused a computation it was plainly asking for, with
      // "you're asking about a grade that isn't yours".
      boolean topicFromContext =
          !PersonalDataIntent.fieldsFor(question).contains(PersonalDataIntent.VARIABLE_PAY)
              && fields.contains(PersonalDataIntent.VARIABLE_PAY);
      // Prior turns about another function's policy are excluded from the look-back. A supposed
      // figure carries forward — "suppose my variable pay is 2 lakh" then "and at an Outstanding
      // rating?" — but it must not carry ACROSS policies: after a US Sales scenario at $120,000,
      // "consider the appraisal rating is 3 and calculate" computed the employee's own Non-Sales
      // payout on that $120,000 instead of the ₹76,500 on their record. The model declined to
      // state the result, which was the right call about a figure that was quietly wrong.
      List<String> ownPolicyPrior = prior.stream().filter(p -> !functionPay.covers(p)).toList();
      appendVariablePay(context, employee, question, ownPolicyPrior, inherited || topicFromContext);
    }

    // Both, where the question is about their own record and about a function policy — an
    // employee comparing their own plan with another one gets each answered from its own source.
    functionPayContext.ifPresent(context::append);

    return Routing.continueWith(context.toString());
  }

  /**
   * The employee's own earlier questions, oldest first, for resolving a follow-up.
   *
   * <p>Taken from the request rather than the stored thread. The stored thread would look like
   * the more trustworthy source, but {@code ThreadController} writes it wholesale from the same
   * client, so it carries no more authority — and reading it here would add a database round
   * trip to the answer path plus a race against the sync that saves the turn being followed up
   * on. Nothing here is trusted with identity in any case: the record comes from the session.
   *
   * <p>Bounded at {@link #MAX_FOLLOW_UP_TURNS} because inheritance is a recency heuristic.
   * Reaching back further starts pulling in a topic the employee has already left.
   */
  private static List<String> priorQuestions(List<RagService.Turn> history, String question) {
    if (history == null || history.isEmpty()) return List.of();

    List<String> asked = new ArrayList<>();
    for (RagService.Turn turn : history) {
      if (turn.isUser() && turn.text() != null && !turn.text().isBlank()) asked.add(turn.text());
    }

    // The transcript usually already ends with the question being routed — RagService drops the
    // same duplicate before prompting. Left in, a question would count as its own predecessor.
    String current = question == null ? "" : question.trim();
    if (!asked.isEmpty() && asked.get(asked.size() - 1).trim().equals(current)) {
      asked.remove(asked.size() - 1);
    }

    return asked.size() > MAX_FOLLOW_UP_TURNS
        ? asked.subList(asked.size() - MAX_FOLLOW_UP_TURNS, asked.size())
        : asked;
  }

  /**
   * Adds the payout matrix and, when a payout was asked for, the computed figure.
   *
   * <p>The computation happens here rather than being left to the model. What the model
   * receives is a finished number it is told not to recalculate — see
   * {@link VariablePayResult#asFactBlock()}.
   */
  private void appendVariablePay(
      StringBuilder context,
      Employee employee,
      String question,
      List<String> prior,
      boolean inherited) {

    // Why this employee cannot be paid under this policy, when that is the case. Checked before
    // anything is described, because explaining the Non-Sales bands to a Sales employee and then
    // declining is a worse answer than declining first.
    Optional<String> blocked = variablePay.ineligibility(employee);
    if (blocked.isPresent()) {
      context.append("\nVariable pay: ").append(blocked.get()).append("\n");
      return;
    }

    VariablePayPlan plan = variablePay.plan();
    context
        .append("\nVariable Pay Policy v")
        .append(plan.policyVersion())
        .append(" (Non-Sales), ")
        .append(plan.fyLabel())
        .append(" — payout is a company share plus an individual share, weighted by grade:\n");
    for (VariablePayPlan.GradeWeight w : plan.gradeWeights()) {
      context
          .append("- L")
          .append(w.fromLevel())
          .append("–L")
          .append(w.toLevel())
          .append(": company ")
          .append(w.companyPercent().stripTrailingZeros().toPlainString())
          .append("%, individual ")
          .append(w.individualPercent().stripTrailingZeros().toPlainString())
          .append("%\n");
    }
    // The declared result belongs in the preamble, not only inside a computed block. Asked "don't
    // you have company performance?" one turn after a correct L8 figure, the assistant apologised,
    // retracted the figure and said it would need revenue and GRR to compute it — with both
    // declared in the plan. Retracting a right answer is worse than never giving it: the employee
    // now distrusts the number they were correctly told.
    if (plan.revenueActualCr() != null && plan.grrActualPercent() != null) {
      context
          .append("- Company result declared for ")
          .append(plan.fyLabel())
          .append(": revenue ")
          .append(plan.revenueActualCr().stripTrailingZeros().toPlainString())
          .append(" Cr, GRR ")
          .append(plan.grrActualPercent().stripTrailingZeros().toPlainString())
          .append("%. These are known — never say they are unavailable.\n");
    } else {
      context.append(
          "- Company result for this year is NOT YET DECLARED. Say so plainly when it matters;"
              + " do not treat it as zero.\n");
    }
    for (VariablePayPlan.RatingPayout r : plan.ratingPayouts()) {
      context
          .append("- Rating ")
          .append(r.rating())
          .append(" (")
          .append(r.label())
          .append("): up to ")
          .append(r.payoutPercent().stripTrailingZeros().toPlainString())
          .append("% of the individual share\n");
    }

    if (!PersonalDataIntent.wantsComputation(question, inherited)) return;

    // The rating the employee named, or the one on their record. A follow-up after a payout answer
    // carries no rating of its own, and re-asking for one is the re-prompting the spec rules out.
    Optional<Integer> stated = PersonalDataIntent.statedRating(question, prior);
    Optional<Integer> rating = stated.or(() -> variablePay.latestRating(employee));

    // A target the employee supposed — "if my variable pay is 10,000". Absent one, the figure on
    // their compensation record is used, which is the ordinary case.
    Optional<BigDecimal> statedTarget = PersonalDataIntent.statedVariableTarget(question, prior);

    // A grade they supposed — "if I was at L8". Changes the weighting, and so whether company
    // performance counts at all, which is usually the whole point of asking.
    Optional<Integer> statedGrade = PersonalDataIntent.statedGradeLevel(question, prior);

    if (rating.isEmpty()) {
      context.append(
          "\nNo appraisal rating is on file and none was stated, so the individual share cannot be"
              + " computed. Ask the employee for their rating — one question, nothing else.\n");
      return;
    }

    // Two calls, because where the rating came from changes what the result means. A rating on
    // file belongs to a cycle and is stated as such; one the employee supposed belongs to no cycle
    // and must not be dressed up as their record.
    Optional<VariablePayResult> result =
        variablePay.compute(
            employee, stated.orElse(null), statedTarget.orElse(null), statedGrade.orElse(null));
    if (result.isEmpty()) {
      context.append(
          "\nA payout could not be computed from the available data. Refer the employee to"
              + " HR Ops for the calculation rather than estimating it.\n");
      return;
    }

    context.append("\n").append(result.get().asFactBlock());

    // One instruction, not one per supposed input. Emitted separately they stacked — a
    // what-if-target caveat under a what-if-rating caveat — and the model read the pile as a
    // reason to be careful rather than to answer: given a finished figure for a supposed 2 lakh
    // at an Outstanding rating, it replied that the answer "depends on which grade you are at",
    // with the grade printed three lines above it. An earlier single version failed the other
    // way, treating the difference from the record as a discrepancy to resolve — "your record
    // shows 76,500, not 2 lakh. Are you asking about a different figure?" — which is asking
    // somebody to confirm the number they typed one message ago.
    if (stated.isPresent() || statedTarget.isPresent() || statedGrade.isPresent()) {
      context.append("This is a what-if on figures the employee supplied");
      if (stated.isPresent()) context.append(" (rating ").append(stated.get()).append(")");
      if (statedTarget.isPresent()) {
        context.append(" (variable pay target ").append(statedTarget.get()).append(")");
      }
      if (statedGrade.isPresent()) context.append(" (grade L").append(statedGrade.get()).append(")");
      context.append(
          ". The weighting for that grade was applied, and the payout above is already computed"
              + " and final. ANSWER IT DIRECTLY: give the figure, show the steps, and say once"
              + " that it is a what-if. Do not ask them to confirm the numbers they just gave, do"
              + " not say the answer depends on their grade, and do not correct them with what is"
              + " on their record.\n");
    } else {
      // Where the rating came from changes what the answer means. Someone who asked "what will I
      // get" and was answered off last year's appraisal needs to know it is last cycle's rating
      // and not a projection of this one, or they will read it as a forecast.
      context.append(
          "The rating above is the one on the employee's most recent appraisal record, not a figure"
              + " they gave. Say which cycle it is from when stating the payout.\n");
    }

    // Eligibility conditions the extract cannot see. Stated as caveats rather than assumed
    // satisfied: the payout is void if either is true at payout time, and a confident figure that
    // quietly assumes otherwise is the expensive kind of wrong.
    context.append(
        "Eligibility not verifiable from the record: the policy also requires that the employee is"
            + " not serving notice and not on a PIP at the time of payout. Mention both as"
            + " conditions rather than asserting they are met.\n");

    log.info(
        "Variable pay computed for {} — grade {} ({}), target {} ({}), rating {} ({}),"
            + " companyDeclared={}, intentInherited={}",
        employee.employeeCode(),
        result.get().grade(),
        result.get().gradeSupposed() ? "stated" : "record",
        result.get().variableTarget(),
        statedTarget.isPresent() ? "stated" : "record",
        rating.get(),
        stated.isPresent() ? "stated" : "pms_record",
        result.get().companyDeclared(),
        inherited);
  }

  private static String intentOf(Set<String> fields, boolean inherited) {
    return (inherited ? "own_record(follow_up):" : "own_record:") + String.join(",", fields);
  }
}
