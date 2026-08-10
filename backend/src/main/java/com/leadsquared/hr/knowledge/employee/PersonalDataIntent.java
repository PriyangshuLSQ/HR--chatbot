package com.leadsquared.hr.knowledge.employee;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
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
      Pattern.compile("\\b(my|mine|me|i|i'm|im|i am|myself)\\b", Pattern.CASE_INSENSITIVE);

  private static final Pattern EMPLOYMENT_WORDS =
      Pattern.compile(
          "\\b(grade|designation|title|role|band|business unit|bu|department|function|location"
              + "|manager|reporting|l2|joining|doj|date of joining|tenure|probation|confirm"
              + "|confirmation|employment status|notice period)\\b",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern COMPENSATION_WORDS =
      Pattern.compile(
          "\\b(ctc|salary|compensation|package|fixed pay|gross|monthly|take home|in.?hand"
              + "|breakdown|earnings)\\b",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern VARIABLE_WORDS =
      Pattern.compile(
          "\\b(variable|vpay|incentive|bonus|payout|commission|target achievement|achievement"
              + "|matrix|quota)\\b",
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
   * An achievement percentage stated in the question — "at 85%", "achieved 92 percent".
   *
   * <p>Bounded at three digits so a rupee figure or a year cannot be mistaken for one, and
   * the unit is required: a bare "85" in "my 85 leaves" is not an achievement.
   */
  private static final Pattern ACHIEVEMENT =
      Pattern.compile(
          "(\\d{1,3}(?:\\.\\d{1,2})?)\\s*(?:%|percent|per cent|pc\\b)", Pattern.CASE_INSENSITIVE);

  /** Which parts of the caller's record this question needs. Empty means none — policy only. */
  public static Set<String> fieldsFor(String question) {
    if (question == null || question.isBlank()) return Set.of();
    String q = question.toLowerCase(Locale.ROOT);

    Set<String> fields = new LinkedHashSet<>();
    if (EMPLOYMENT_WORDS.matcher(q).find()) fields.add(EMPLOYMENT);
    if (COMPENSATION_WORDS.matcher(q).find()) fields.add(COMPENSATION);
    if (VARIABLE_WORDS.matcher(q).find()) fields.add(VARIABLE_PAY);
    if (LEAVE_WORDS.matcher(q).find()) fields.add(LEAVE);
    if (ATTENDANCE_WORDS.matcher(q).find()) fields.add(ATTENDANCE);
    if (PMS_WORDS.matcher(q).find()) fields.add(PMS);

    if (fields.isEmpty()) return Set.of();

    // A field word alone is not enough — "what does the leave policy say about sick leave" is
    // a policy question and needs no record. First person is what makes it personal. Variable
    // pay is the exception: "what is the payout matrix for my grade" needs the record to know
    // the grade, and a bare "payout matrix" question is answered from the matrix anyway.
    if (!FIRST_PERSON.matcher(q).find() && !fields.contains(VARIABLE_PAY)) return Set.of();

    return Set.copyOf(fields);
  }

  public static boolean needsOwnRecord(String question) {
    return !fieldsFor(question).isEmpty();
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

  /** Whether the question asks for a payout to be worked out rather than just read back. */
  public static boolean wantsComputation(String question) {
    if (question == null) return false;
    String q = question.toLowerCase(Locale.ROOT);
    if (!VARIABLE_WORDS.matcher(q).find() && !ACHIEVEMENT.matcher(q).find()) return false;
    return statedAchievement(question).isPresent()
        || Pattern.compile(
                "\\b(what will i get|how much will i|compute|calculate|work out|if i achieve"
                    + "|based on|expected payout|projected)\\b",
                Pattern.CASE_INSENSITIVE)
            .matcher(q)
            .find();
  }
}
