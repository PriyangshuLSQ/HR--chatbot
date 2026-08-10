package com.leadsquared.hr.knowledge.employee;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Recognises attempts to read another employee's data.
 *
 * <p><b>This class is not the security boundary, and it is important to be clear about
 * that.</b> The boundary is {@link EmployeeDataService}, which can only ever load the
 * caller's own record — there is no method, and no parameter, that would let it load
 * anyone else's. So a colleague's salary cannot be answered because it is never in the
 * model's context, not because this class spotted the question.
 *
 * <p>What this adds is the two things the structural guarantee does not: the clear,
 * professional decline the rules require instead of a vague non-answer, and the log entry
 * rule 2 asks for when someone tries. Its failure mode is therefore a <em>worse message</em>
 * — a third-party question that slips past these patterns still cannot be answered, it
 * just gets the ordinary "not in the documents" reply rather than the privacy notice. A
 * false positive is a policy question wrongly declined, which is why the patterns below
 * are anchored on personal-data vocabulary rather than on names alone.
 *
 * <p>Deliberately deterministic. Asking the model to classify these would put the
 * compliance decision back inside the thing an adversarial prompt is trying to steer.
 */
public final class CrossEmployeeGuard {

  private CrossEmployeeGuard() {}

  /** The exact wording rule 6 requires. */
  public static final String DENIAL_MESSAGE =
      "I can only provide information related to your own employment record. "
          + "For any queries about another employee's data, please contact HR Ops directly.";

  /**
   * Fields that only exist per person. A question has to reach for one of these before any
   * of the patterns below can make it a privacy refusal — otherwise "who is in the Sales
   * team" or "what does the leave policy say" would be declined as a data breach attempt.
   */
  private static final Pattern PERSONAL_FIELD =
      Pattern.compile(
          "\\b(ctc|salary|salaries|compensation|pay|payout|package|increment|bonus|variable"
              + "|earn|earns|earning|earnings|paid"
              + "|grade|designation|band|leave balances?|balances?|leaves? left|attendance"
              + "|rating|ratings"
              + "|appraisal|performance|pms|tenure|joining date|probation|manager|reportee"
              + "|reporting to|employee (?:id|code)|offer)\\b",
          Pattern.CASE_INSENSITIVE);

  /** First person. Their own record is exactly what they are entitled to. */
  private static final Pattern FIRST_PERSON =
      Pattern.compile("\\b(my|mine|i|me|myself|i'm|im|i am)\\b", Pattern.CASE_INSENSITIVE);

  /**
   * An employee code. The clearest possible signal: the only reason to type one is to name
   * somebody, and there is no need to name yourself to a session that already knows you.
   */
  private static final Pattern EMPLOYEE_CODE =
      Pattern.compile("\\b(?:ls|emp|lsq)[-_ ]?\\d{3,}\\b", Pattern.CASE_INSENSITIVE);

  /** Third-person possessives and referring phrases. */
  private static final Pattern THIRD_PARTY_REFERENCE =
      Pattern.compile(
          "\\b(his|her|their|theirs|hers|someone|somebody|anyone|anybody|colleague|coworker"
              + "|co-worker|teammate|team ?mate|everyone|everybody|other (?:employees?|people)"
              + "|another (?:employee|person)|(?:my )?(?:manager|boss|lead|reportee|peer)'s"
              + "|person who|employee who|people who)\\b",
          Pattern.CASE_INSENSITIVE);

  /**
   * Comparative and ranked access, which the rules forbid outright — "who earns the most"
   * discloses a person without ever naming one.
   */
  private static final Pattern COMPARATIVE =
      Pattern.compile(
          "\\b(highest|lowest|top|bottom|most|least|maximum|minimum|max|min|richest"
              + "|best paid|highest[- ]paid|rank|ranked|ranking|compare|comparison"
              + "|versus|vs\\.?|than (?:me|mine)|more than i|less than i)\\b",
          Pattern.CASE_INSENSITIVE);

  /**
   * Aggregates over people. Forbidden even though no individual is named: in a team of
   * three, an average plus your own figure yields a colleague's exactly.
   *
   * <p>"Band" and "range" are absent on purpose — those are answerable from the policy
   * documents, which publish bands by design, and the rules explicitly permit that.
   */
  private static final Pattern AGGREGATE =
      Pattern.compile(
          "\\b(average|avg|mean|median|headcount|distribution|spread|percentile"
              + "|how many (?:people|employees)|everyone(?:'s)?|everybody(?:'s)?"
              + "|team's (?:total|average)|across the (?:team|department|bu))\\b",
          Pattern.CASE_INSENSITIVE);

  /**
   * Somebody named, rather than described.
   *
   * <p>Matched against the original casing, not the lower-cased copy — capitalisation is the
   * only signal separating "the CTC of Priya Sharma" from "the CTC of my grade". Deliberately
   * paired with {@link #PERSONAL_FIELD} below so a policy question mentioning a capitalised
   * insurer ("what does Oriental Insurance cover") is not read as naming a colleague.
   */
  private static final Pattern PROPER_NAME_TARGET =
      Pattern.compile(
          "\\b[A-Z][a-z]{2,}(?:\\s+[A-Z][a-z]+)?'s\\b" // Ramesh's, Priya Sharma's
              + "|\\b(?:of|for|about)\\s+[A-Z][a-z]{2,}\\b"); // of Priya, for Ramesh

  /**
   * A question about a published band rather than about a person.
   *
   * <p>Rule 2 explicitly permits "what does someone at L6 in Sales earn" to be answered with
   * policy-level band information. That phrasing contains "someone", so without this carve-out
   * the third-party pattern would refuse a question the rules allow — and refusing it makes the
   * assistant less useful without making it any safer, because band tables live in the policy
   * documents and contain nobody's actual pay.
   */
  private static final Pattern GRADE_OR_BAND =
      Pattern.compile("\\b(l\\d{1,2}|band|grade|level|designation)\\b", Pattern.CASE_INSENSITIVE);

  /**
   * Attribute-based identification — naming a person by a property instead of a name.
   * "The person who joined on 3 March in my team" is a lookup key, not a description.
   */
  private static final Pattern IDENTIFYING_PREDICATE =
      Pattern.compile(
          "\\b(who joined|who reports|reporting to me|in my team|on my team|in my bu"
              + "|in my department|under me|my team member|my direct)\\b",
          Pattern.CASE_INSENSITIVE);

  /** Why a question was refused. Recorded on the audit event so patterns are reviewable. */
  public enum Reason {
    NAMED_IDENTIFIER("an employee identifier"),
    THIRD_PARTY_REFERENCE("a reference to another person"),
    COMPARATIVE("a comparison or ranking across employees"),
    AGGREGATE("an aggregate over employee records"),
    IDENTIFYING_PREDICATE("a description identifying another person");

    private final String description;

    Reason(String description) {
      this.description = description;
    }

    public String description() {
      return description;
    }
  }

  /**
   * Why this question must be declined, or empty if it need not be.
   *
   * <p>Order matters only for the logged reason, not the outcome: a question matching two
   * patterns is refused once, and the first match is the one recorded.
   */
  public static List<Reason> reasonsToDecline(String question) {
    if (question == null || question.isBlank()) return List.of();
    String q = question.toLowerCase(Locale.ROOT);

    // An employee code stands alone. It is a lookup key with no innocent reading — there is no
    // need to identify yourself to a session that already knows who you are — so unlike every
    // pattern below it does not need pairing with a personal-data word.
    if (EMPLOYEE_CODE.matcher(q).find()) return List.of(Reason.NAMED_IDENTIFIER);

    boolean personalField = PERSONAL_FIELD.matcher(q).find();
    if (!personalField) return List.of();

    // Original casing: capitalisation is what distinguishes a person's name from a common noun.
    if (PROPER_NAME_TARGET.matcher(question).find()) return List.of(Reason.NAMED_IDENTIFIER);

    List<Reason> reasons = new java.util.ArrayList<>(3);
    if (AGGREGATE.matcher(q).find()) reasons.add(Reason.AGGREGATE);
    if (COMPARATIVE.matcher(q).find()) reasons.add(Reason.COMPARATIVE);
    if (IDENTIFYING_PREDICATE.matcher(q).find()) reasons.add(Reason.IDENTIFYING_PREDICATE);

    // Note what is NOT here: a first-person exemption. An earlier cut cleared any question
    // containing "my", which quietly permitted "what is the average salary in my BU?" — one of
    // the examples rule 1 names explicitly. First person says nothing about whose data is being
    // asked for; "how does my CTC compare to my teammate's" is first person and still a breach.
    if (THIRD_PARTY_REFERENCE.matcher(q).find()) {
      // A generic reference plus a band word and nothing else is the permitted policy-band
      // question. Anything already refused above stays refused — an aggregate or a comparison
      // is not rescued by mentioning a grade.
      boolean policyBandQuestion = reasons.isEmpty() && GRADE_OR_BAND.matcher(q).find();
      if (!policyBandQuestion) reasons.add(Reason.THIRD_PARTY_REFERENCE);
    }

    return List.copyOf(reasons);
  }

  public static boolean mustDecline(String question) {
    return !reasonsToDecline(question).isEmpty();
  }
}
