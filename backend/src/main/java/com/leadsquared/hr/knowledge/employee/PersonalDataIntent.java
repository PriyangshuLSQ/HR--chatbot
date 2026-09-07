package com.leadsquared.hr.knowledge.employee;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out whether a question needs the caller's own record, and which parts of it.
 *
 * <p>Over-triggering here is harmless — the only record that can be loaded is the caller's
 * own, so the cost of a false positive is a slightly larger prompt, not a disclosure. That
 * asymmetry is deliberate: this class is tuned to catch questions rather than to be precise,
 * and the thing that keeps it safe is {@link EmployeeDataService}, not this.
 *
 * <p>Field selection exists for the audit trail as much as for the prompt. Rule 5 asks what
 * data was queried, and "compensation" is a more useful answer than "the employee record".
 */
public final class PersonalDataIntent {

  private PersonalDataIntent() {}

  public static final String EMPLOYMENT = "employment";
  public static final String COMPENSATION = "compensation";
  public static final String VARIABLE_PAY = "variable_pay";
  public static final String LEAVE = "leave";
  public static final String ATTENDANCE = "attendance";
  public static final String PMS = "pms";

  private static final Pattern FIRST_PERSON =
      // `iam` is here because people type it. The product tells them typos are fine, and "if iam
      // from grade L8" was read as naming nobody, so it loaded no record.
      Pattern.compile("\\b(my|mine|me|i|i'm|im|iam|i am|myself)\\b", Pattern.CASE_INSENSITIVE);

  private static final Pattern EMPLOYMENT_WORDS =
      Pattern.compile(
          // `hrbp` and `hr business partner` sit with the managers because they are the same
          // question — who is the person I take this to — and were the one member of that set
          // missing here. "Who is my HRBP?" therefore loaded no record, retrieved policy
          // documents that of course do not name anyone's HRBP, and told the employee it was not
          // in the documents. The name was in the extract the whole time.
          "\\b(grade|designation|title|role|band|business unit|bu|department|function|location"
              + "|manager|reporting|l2|hrbp|hr business partner|joining|doj|date of joining"
              + "|tenure|probation|confirm"
              + "|confirmation|employment status|notice period)\\b",
          Pattern.CASE_INSENSITIVE);

  /**
   * Who the asker is: their name and their employee code.
   *
   * <p>Its own pattern, resolving to {@link #EMPLOYMENT}, because these two are not employment
   * *details* — {@link EmployeeFacts} prints both in the header of every fact block, whatever
   * fields were selected. Nothing here changes what is rendered; it exists solely to make the
   * question load a record at all.
   *
   * <p>Both were missing, and the failure was the same each time: "what is my emp id?" and "tell
   * my name" name no field the vocabulary recognised, so no record was loaded and the question
   * was answered from the policy corpus — which of course holds neither — with "check your
   * Darwinbox profile or contact HR Ops". The data was in the database throughout. It is the
   * shape of wrong answer that is hardest to catch, because a refusal reads as careful.
   *
   * <p>The phrases are first-person by construction ({@code my name}, not {@code name}) even
   * though {@link #ownFields} tests for first person separately. A bare {@code name} would make
   * every policy question mentioning one — a nominee, a manager, a document's title — load the
   * asker's record, and while that discloses nothing, it would write a misleading line into the
   * audit trail on questions that never touched employee data.
   */
  private static final Pattern IDENTITY_WORDS =
      Pattern.compile(
          "\\b(employee code|emp code|employee id|emp id|empid|employee number|emp number"
              + "|employee no|emp no|staff id|associate id|payroll number|my id|my code"
              + "|my name|my full name|my legal name|name on record|who am i|whoami"
              + "|my details|my profile|my record|my records|my information|my info|my data"
              + "|about myself)\\b",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern COMPENSATION_WORDS =
      Pattern.compile(
          "\\b(ctc|salary|compensation|package|fixed pay|gross|monthly|take home|in.?hand"
              + "|breakdown|earnings"
              // The salary structure. Without these "what is my HRA" matched no personal field,
              // so no EMPLOYEE RECORD block was built and the question went to policy retrieval —
              // which has no policy stating one person's HRA, and correctly said so. The figures
              // were in the extract; nothing asked for them.
              + "|hra|house rent|basic|special allowance|allowance|provident fund|\\bpf\\b"
              + "|professional tax|\\bptax\\b|gratuity|deduction|deductions|structure"
              + "|components?)\\b",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern VARIABLE_WORDS =
      Pattern.compile(
          // `revenue`, `grr` and `company performance` are here because they are half of what
          // decides a payout under this policy. Without them "don't you have company performance?"
          // matched no payroll word, loaded no plan, and the model answered from the policy PDF —
          // then retracted a figure it had computed correctly a turn earlier.
          "\\b(variable|vpay|incentive|bonus|payout|commission|target achievement|achievement"
              + "|achieve|achieved|achieving|attain|attained|matrix|quota"
              + "|revenue|grr|company performance|company result|company targets)\\b",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern LEAVE_WORDS =
      Pattern.compile(
          "\\b(leave|leaves|holiday|vacation|earned|el\\b|sick|sl\\b|casual|cl\\b|maternity"
              + "|paternity|encash|carry forward|balance)\\b",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern ATTENDANCE_WORDS =
      Pattern.compile(
          "\\b(attendance|present|absent|wfh|work from home|days worked|swipe|late marks?)\\b",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern PMS_WORDS =
      Pattern.compile(
          "\\b(pms|appraisal|performance|rating|review cycle|last cycle|kra|kpi)\\b",
          Pattern.CASE_INSENSITIVE);

  /**
   * Asking about yourself without saying so — "tell name", "show my details" pared down to
   * "details?", "employee code".
   *
   * <p>People drop the pronoun when they are typing quickly at something they already think of
   * as personal, and the first-person gate has no way to see it: "tell name" contains no
   * {@code my}, no {@code I}, nothing. It was answered from the policy corpus with an invented
   * instruction to supply an employee ID — see the prompt rule added alongside this.
   *
   * <p>Two narrow shapes, both of which a policy question does not take. An imperative aimed at
   * an identity noun ({@code tell name}, {@code show me employee code}), and that noun standing
   * alone as the entire message ({@code name?}, {@code emp id}). "What is the employee code
   * format" and "what is the name of the leave policy" match neither, which is what keeps this
   * from dragging the asker's record into questions that never touched employee data.
   */
  private static final Pattern ELLIPTICAL_SELF =
      Pattern.compile(
          "^\\s*(?:(?:tell|show|give|get|display|fetch|find|print)\\s+(?:me\\s+)?(?:the\\s+)?"
              + "(?:name|full name|employee code|emp code|employee id|emp id|empid"
              + "|employee number|emp number|staff id|details|record|profile|designation|grade)\\b"
              + "|(?:name|full name|employee code|emp code|employee id|emp id|empid"
              + "|employee number|emp number|staff id|details|profile)\\s*[?.!]*\\s*$)",
          Pattern.CASE_INSENSITIVE);

  /**
   * An achievement percentage stated in the question — "at 85%", "achieved 92 percent".
   *
   * <p>Bounded at three digits so a rupee figure or a year cannot be mistaken for one, and
   * the unit is required: a bare "85" in "my 85 leaves" is not an achievement.
   */
  private static final Pattern ACHIEVEMENT =
      Pattern.compile(
          "(\\d{1,3}(?:\\.\\d{1,2})?)\\s*(?:%|percent|per cent|pc\\b)", Pattern.CASE_INSENSITIVE);

  /**
   * Asks for a figure to be worked out rather than read back.
   *
   * <p>Hoisted to a constant so {@link #fieldsFor} can consult it: a question that wants a
   * payout computed necessarily needs the record the computation runs on, and the two used to
   * disagree — {@code wantsComputation} returned true for "if I achieve 90%, what will I get?"
   * while {@code fieldsFor} returned nothing, so the class wanted a computation it would never
   * be asked to make.
   */
  private static final Pattern COMPUTATION_VERBS =
      Pattern.compile(
          // Scoped by the caller to questions already about variable pay, so these can be broad
          // without catching unrelated ones. Added when the payout became computable from data on
          // file: under the old model a figure had to be stated, so "what will my variable payout
          // be" genuinely could not be answered and matching it would have been noise. Now the
          // rating and the company result are both on record, and that is the plainest way to ask.
          "\\b(what will i get|what would i get|how much will i|how much would i|compute"
              + "|calculate|work out|if i achieve|if i hit|based on|expected payout|projected"
              + "|what will my|what would my|what is my payout|whats my payout|how much variable"
              + "|how much payout|payout be|payout will be|do i get)\\b",
          Pattern.CASE_INSENSITIVE);

  /**
   * Marks a question as continuing the previous one rather than opening a topic.
   *
   * <p>Only consulted when the question names no field of its own, and only to inherit the
   * <em>previous</em> question's fields. "And what will I get?" carries no payroll vocabulary
   * at all; without this it routes to policy retrieval and the employee is asked to repeat
   * themselves, which the spec rules out.
   */
  private static final Pattern CONTINUATION =
      Pattern.compile(
          "^\\s*(and|so|then|also|but|ok|okay|what about|how about)\\b"
              + "|\\bwhat about\\b|\\bhow about\\b",
          Pattern.CASE_INSENSITIVE);

  /** Which parts of the caller's record this question needs. Empty means none — policy only. */
  public static Set<String> fieldsFor(String question) {
    return fieldsFor(question, List.of());
  }

  /**
   * Which parts of the caller's record this question needs, read in the context of the ones
   * before it.
   *
   * <p>A follow-up inherits the previous question's fields when it names none of its own and
   * reads as a continuation. "And if I achieve 90%, what will I get?" is a payout question only
   * because "what is my variable pay target?" came first; judged alone it names no field, routes
   * to policy retrieval, and the employee gets asked what they already said.
   *
   * <p>Inheritance cannot widen who is visible, only which of the caller's own fields are
   * loaded — {@code EmployeeDataService} resolves the record from the session and takes no
   * identity from here, and {@code CrossEmployeeGuard} still runs against the current question.
   * So the cost of inheriting wrongly is a larger prompt and a broader audit line, the same
   * asymmetry this class already relies on. That is why the prior turns being caller-supplied
   * does not matter: forged history buys the caller their own record, which they can have for
   * the asking.
   *
   * @param priorUserQuestions earlier questions from this conversation, oldest first. The
   *     caller trims the window; the most recent one naming a field is the one inherited from.
   */
  public static Set<String> fieldsFor(String question, List<String> priorUserQuestions) {
    Set<String> own = ownFields(question);

    /*
     * A grade, rating or target the employee supposed is a parameter of the conversation already
     * running, not a new subject — so it neither disqualifies the question from inheriting nor
     * settles it on its own.
     *
     * That distinction is the whole of this branch. "If iam from grade L8 then?" names `grade`,
     * which made `own` non-empty, so the method returned {employment} and stopped: variable pay
     * was never inherited, no payout was computed, and the employee asking what a promotion would
     * pay was told "your grade is L3, not L8 — contact your HRBP if that is wrong". Answering the
     * question they did not ask, in place of the one they did.
     *
     * Merged rather than replaced, because both halves matter: the supposed grade needs the
     * employment fields it names AND the variable pay topic it is a parameter of.
     */
    boolean supposesAParameter =
        statedGradeLevel(question).isPresent()
            || statedRating(question).isPresent()
            || statedVariableTarget(question).isPresent();

    if (!own.isEmpty() && !supposesAParameter) return own;
    if (priorUserQuestions == null || priorUserQuestions.isEmpty()) return own;

    // A question that named a subject and was still rejected is a policy question — "and what
    // does the leave policy say about sick leave?" names leave but not the asker. It must not
    // inherit: opening with "and" would otherwise drag the previous turn's compensation data
    // into a prompt that has no use for it. Only a question naming no subject at all — or one
    // naming nothing beyond a supposition — is a candidate for continuation.
    if (!supposesAParameter && !vocabularyFields(question).isEmpty()) return own;
    if (!isContinuation(question)) return own;

    for (int i = priorUserQuestions.size() - 1; i >= 0; i--) {
      Set<String> prior = ownFields(priorUserQuestions.get(i));
      if (!prior.isEmpty()) {
        Set<String> merged = new LinkedHashSet<>(own);
        merged.addAll(prior);
        return merged;
      }
    }
    return own;
  }

  /** Fields this question names on its own, ignoring anything said before it. */
  private static Set<String> ownFields(String question) {
    Set<String> fields = vocabularyFields(question);
    if (fields.isEmpty()) return Set.of();

    String q = question.toLowerCase(Locale.ROOT);

    // A field word alone is not enough — "what does the leave policy say about sick leave" is
    // a policy question and needs no record. First person is what makes it personal. Variable
    // pay is the exception: "what is the payout matrix for my grade" needs the record to know
    // the grade, and a bare "payout matrix" question is answered from the matrix anyway.
    if (!FIRST_PERSON.matcher(q).find()
        && !fields.contains(VARIABLE_PAY)
        && !ELLIPTICAL_SELF.matcher(q).find()) {
      return Set.of();
    }

    return Set.copyOf(fields);
  }

  /**
   * The subjects this question names, before any test of whether it asks about the asker.
   *
   * <p>Split out from {@link #ownFields} so a rejected question can be told apart from a silent
   * one. Both produce no fields, but only the silent one may inherit from the turn before it.
   */
  private static Set<String> vocabularyFields(String question) {
    if (question == null || question.isBlank()) return Set.of();
    String q = question.toLowerCase(Locale.ROOT);

    Set<String> fields = new LinkedHashSet<>();
    // ELLIPTICAL_SELF is consulted here as well as in ownFields because the pronoun it stands in
    // for is also the subject: "tell name" names no field the other patterns recognise, so
    // without this it produces nothing to gate in the first place.
    if (EMPLOYMENT_WORDS.matcher(q).find()
        || IDENTITY_WORDS.matcher(q).find()
        || ELLIPTICAL_SELF.matcher(q).find()) {
      fields.add(EMPLOYMENT);
    }
    if (COMPENSATION_WORDS.matcher(q).find()) fields.add(COMPENSATION);
    if (VARIABLE_WORDS.matcher(q).find()) fields.add(VARIABLE_PAY);
    if (LEAVE_WORDS.matcher(q).find()) fields.add(LEAVE);
    if (ATTENDANCE_WORDS.matcher(q).find()) fields.add(ATTENDANCE);
    if (PMS_WORDS.matcher(q).find()) fields.add(PMS);

    // A payout cannot be computed without the record it is computed from, so a question asking
    // for one needs the variable-pay fields whether or not it uses the vocabulary above.
    if (wantsComputation(question)) fields.add(VARIABLE_PAY);

    return fields;
  }

  /** Whether this reads as carrying on from the previous question rather than starting over. */
  private static boolean isContinuation(String question) {
    if (question == null || question.isBlank()) return false;
    return CONTINUATION.matcher(question).find()
        || COMPUTATION_VERBS.matcher(question).find()
        || statedAchievement(question).isPresent()
        // The three what-if parameters. Each is a whole follow-up on its own — "if I was at L8",
        // "what if I'm rated 4", "suppose it were 2 lakh" — and none names a subject.
        || statedGradeLevel(question).isPresent()
        || statedRating(question).isPresent()
        || statedVariableTarget(question).isPresent();
  }

  public static boolean needsOwnRecord(String question) {
    return !fieldsFor(question).isEmpty();
  }

  /**
   * A percentage stated in this question, or failing that in a recent earlier one.
   *
   * <p>Preferring an earlier stated figure over the appraisal record matters: after "if I
   * achieve 90%", the follow-up "and what will I get?" is asking about 90%, and answering it
   * from last cycle's actual achievement would silently answer a different question.
   */
  public static Optional<BigDecimal> statedAchievement(
      String question, List<String> priorUserQuestions) {
    Optional<BigDecimal> here = statedAchievement(question);
    if (here.isPresent() || priorUserQuestions == null) return here;
    for (int i = priorUserQuestions.size() - 1; i >= 0; i--) {
      Optional<BigDecimal> earlier = statedAchievement(priorUserQuestions.get(i));
      if (earlier.isPresent()) return earlier;
    }
    return Optional.empty();
  }

  /** A percentage the employee stated, if any. */
  public static Optional<BigDecimal> statedAchievement(String question) {
    if (question == null) return Optional.empty();
    Matcher matcher = ACHIEVEMENT.matcher(question);
    if (!matcher.find()) return Optional.empty();
    try {
      BigDecimal value = new BigDecimal(matcher.group(1));
      // A percentage over 200 is far more likely a misparse than a real overachievement.
      return value.compareTo(new BigDecimal("200")) > 0 ? Optional.empty() : Optional.of(value);
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  /**
   * A grade the employee supposed — "if I was at L8", "at grade 5".
   *
   * <p>The third thing they can vary, and the one that changes the shape of the answer rather than
   * just its size: grade decides how much of the payout rides on company performance at all. "If I
   * was at L8, what would I get?" is a promotion question, and answering it from their current
   * grade answers the opposite of what was asked.
   *
   * <p>Bounded to two digits and refused beside "manager", because {@code L2 Manager} is a field on
   * every employee record and "who is my L2 manager" is not a question about grade 2.
   */
  public static Optional<Integer> statedGradeLevel(
      String question, List<String> priorUserQuestions) {
    Optional<Integer> here = statedGradeLevel(question);
    if (here.isPresent() || priorUserQuestions == null) return here;
    for (int i = priorUserQuestions.size() - 1; i >= 0; i--) {
      Optional<Integer> earlier = statedGradeLevel(priorUserQuestions.get(i));
      if (earlier.isPresent()) return earlier;
    }
    return Optional.empty();
  }

  /** A grade named in this question, as a level. */
  public static Optional<Integer> statedGradeLevel(String question) {
    if (question == null) return Optional.empty();
    Matcher m = GRADE_MENTION.matcher(question);
    if (!m.find()) return Optional.empty();
    String value = m.group(1) != null ? m.group(1) : m.group(2);
    try {
      int level = Integer.parseInt(value);
      return level > 99 ? Optional.empty() : Optional.of(level);
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  private static final Pattern GRADE_MENTION =
      Pattern.compile(
          "\\b[lx]\\s?(\\d{1,2})\\b(?!\\s*(?:manager|mgr))"
              + "|\\b(?:grade|level|band)\\s*[:=]?\\s*(\\d{1,2})\\b",
          Pattern.CASE_INSENSITIVE);

  /**
   * A variable pay target the employee supposed, or failing that one supposed in a recent earlier
   * question.
   *
   * <p>"If my variable pay is 10,000, what would I get?" is a question about a figure that is not
   * on their record, and answering it from the record instead would answer a different question.
   * Absent one of these, the target comes from the stored compensation record.
   *
   * <p>Same look-back as the other two: after "suppose my variable pay is 2 lakh", the follow-up
   * "and at an Outstanding rating?" is still about 2 lakh.
   */
  public static Optional<BigDecimal> statedVariableTarget(
      String question, List<String> priorUserQuestions) {
    Optional<BigDecimal> here = statedVariableTarget(question);
    if (here.isPresent() || priorUserQuestions == null) return here;
    for (int i = priorUserQuestions.size() - 1; i >= 0; i--) {
      Optional<BigDecimal> earlier = statedVariableTarget(priorUserQuestions.get(i));
      if (earlier.isPresent()) return earlier;
    }
    return Optional.empty();
  }

  /**
   * An amount named as the variable pay in this question.
   *
   * <p>Anchored to a variable-pay phrase rather than matching any number, because a payroll
   * question is full of numbers that are not the target: a rating, a grade, a percentage, a
   * headcount, a year. "If my variable pay is 10000" states one; "how much of my 12 lakh CTC is
   * variable" does not.
   *
   * <p>Indian units are spelled out often enough to be worth reading — a target is far more likely
   * to be typed as "2 lakh" than as "200000", and misreading that as ₹2 would be a wrong number
   * about someone's pay rather than a missed match.
   */
  public static Optional<BigDecimal> statedVariableTarget(String question) {
    if (question == null) return Optional.empty();

    Matcher m = VARIABLE_TARGET.matcher(question);
    if (!m.find()) return Optional.empty();

    String digits = m.group(1).replace(",", "");
    String unit = m.group(2);
    try {
      BigDecimal amount = new BigDecimal(digits);
      if (unit != null) {
        String u = unit.toLowerCase(Locale.ROOT);
        if (u.startsWith("l")) amount = amount.multiply(new BigDecimal("100000"));
        else if (u.startsWith("cr")) amount = amount.multiply(new BigDecimal("10000000"));
        else if (u.startsWith("k")) amount = amount.multiply(new BigDecimal("1000"));
      }
      // A target under a thousand rupees is a misparse — a rating, a percentage or a grade that
      // happened to sit beside the phrase. Declining beats computing a payout off ₹5.
      return amount.compareTo(new BigDecimal("1000")) < 0 ? Optional.empty() : Optional.of(amount);
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  private static final Pattern VARIABLE_TARGET =
      Pattern.compile(
          "\\b(?:variable(?:\\s+pay|\\s+component)?|vpay|target|incentive|bonus)\\b"
              + "[^0-9?]{0,24}"
              + "(?:rs\\.?|inr|₹)?\\s*"
              + "(\\d{1,3}(?:,\\d{2,3})*(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)"
              + "\\s*(lakhs?|lacs?|crores?|cr|k)?\\b",
          Pattern.CASE_INSENSITIVE);

  /**
   * An appraisal rating stated in this question, or failing that in a recent earlier one.
   *
   * <p>The counterpart to {@link #statedAchievement}, and what replaced it as the figure that drives
   * a payout. Variable Pay Policy v2.0 has no single "achievement percentage" to state: the
   * individual share is driven by the appraisal rating, so "what if I get a 4?" is the question an
   * employee actually asks about their own half of the payout.
   *
   * <p>Same look-back reasoning as the percentage version: after "what if I'm rated 4", the
   * follow-up "and what would I get?" is still about a 4, and answering it from the record would
   * silently answer a different question.
   */
  public static Optional<Integer> statedRating(String question, List<String> priorUserQuestions) {
    Optional<Integer> here = statedRating(question);
    if (here.isPresent() || priorUserQuestions == null) return here;
    for (int i = priorUserQuestions.size() - 1; i >= 0; i--) {
      Optional<Integer> earlier = statedRating(priorUserQuestions.get(i));
      if (earlier.isPresent()) return earlier;
    }
    return Optional.empty();
  }

  /**
   * A rating the employee stated, as a number.
   *
   * <p>Requires a rating word beside the digit. A bare number in a payroll question is far more
   * likely to be an amount, a grade or a month than a rating — "what is 4 months of variable pay"
   * must not be read as a rating of 4.
   *
   * <p>The words are the ones the policy's worked examples use ("5 - Outstanding", "3 - Meets").
   * The authoritative labels live in the stored plan; these exist only to recognise what someone
   * typed, so a plan renaming a band does not change what a question means.
   */
  public static Optional<Integer> statedRating(String question) {
    if (question == null) return Optional.empty();

    Matcher numeric = RATING_NUMBER.matcher(question);
    if (numeric.find()) {
      String value = numeric.group(1) != null ? numeric.group(1) : numeric.group(2);
      return Optional.of(Integer.parseInt(value));
    }

    // A label only counts beside a rating word: "meets" and "below" are ordinary English, and
    // "does this meet the policy" is not a statement about anyone's appraisal.
    Matcher named = RATING_LABEL.matcher(question);
    if (RATING_CONTEXT.matcher(question).find() && named.find()) {
      return switch (named.group(1).toLowerCase(Locale.ROOT)) {
        case "outstanding" -> Optional.of(5);
        case "exceeds" -> Optional.of(4);
        case "meets" -> Optional.of(3);
        case "below" -> Optional.of(2);
        case "poor" -> Optional.of(1);
        default -> Optional.empty();
      };
    }
    return Optional.empty();
  }

  private static final Pattern RATING_NUMBER =
      Pattern.compile(
          "\\b(?:rating|rated|rate)\\s*(?:of|is|was|:|=)?\\s*([1-5])\\b"
              + "|\\b([1-5])\\s*[-–]?\\s*(?:rating|star)\\b",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern RATING_LABEL =
      Pattern.compile("\\b(outstanding|exceeds|meets|below|poor)\\b", Pattern.CASE_INSENSITIVE);

  private static final Pattern RATING_CONTEXT =
      Pattern.compile("\\b(rating|rated|appraisal|review)\\b", Pattern.CASE_INSENSITIVE);

  /** Whether the question asks for a payout to be worked out rather than just read back. */
  public static boolean wantsComputation(String question) {
    return wantsComputation(question, false);
  }

  /**
   * Whether the question asks for a payout to be worked out rather than just read back.
   *
   * @param variablePayContext true when the conversation is already on variable pay because an
   *     earlier turn put it there. "And what will I get?" names no payroll subject and states no
   *     figure, so on its own it asks for nothing computable; in context it is the whole
   *     question. Without this the follow-up gets the slab table and no number.
   */
  public static boolean wantsComputation(String question, boolean variablePayContext) {
    if (question == null) return false;
    String q = question.toLowerCase(Locale.ROOT);
    // A rating names the individual half of the payout as squarely as a percentage named the old
    // achievement figure, so "what if my rating is outstanding?" is a payout question even with no
    // payroll word in it.
    if (!variablePayContext
        && !VARIABLE_WORDS.matcher(q).find()
        && !ACHIEVEMENT.matcher(q).find()
        && statedRating(question).isEmpty()) {
      return false;
    }
    return statedAchievement(question).isPresent()
        || statedRating(question).isPresent()
        || statedVariableTarget(question).isPresent()
        // A grade on its own is a whole question: "if I was at L8 then?" asks what a promotion
        // would pay. Without this it named no figure the gate recognised and fell through to the
        // NLU, which offered a menu of Car lease scheme and Provident fund.
        || statedGradeLevel(question).isPresent()
        || COMPUTATION_VERBS.matcher(q).find();
  }
}
