package com.leadsquared.hr.knowledge.model;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The Variable Pay Policy, as data rather than as code.
 *
 * <p>Everything here comes out of <i>Variable Pay Policy v2.0 (Non-Sales)</i>, and everything here
 * changes: the revenue and GRR the company actually achieved change every year, the band
 * thresholds changed between v1.0 and v2.0, and the policy reserves the right to revise any clause
 * "depending upon the demand of business". A payout matrix compiled into a jar means a redeploy to
 * state last year's revenue, and — worse — a build whose numbers silently disagree with the PDF HR
 * is reading from.
 *
 * <p>So it is stored, edited in the admin console, and stamped with who changed it and when. The
 * seeded values reproduce the published policy exactly, and are checked against that document's own
 * four worked examples in {@code VariablePayCalculatorTest}.
 *
 * <p><b>Grades are L-grades.</b> The policy writes them as X0–X10+; the employee extract, the
 * payroll system and every other policy in this corpus use L1–L9. They are the same ladder under
 * two names, so the levels are stored as plain integers and read off whatever letter prefix the
 * grade carries. Storing "X7" here and matching it against an employee recorded as "L7" is how a
 * senior employee silently gets an individual-only payout.
 *
 * @param planKey which policy this is. Only {@code non-sales} exists today — Sales and the US
 *     entity have their own documents with different mechanics, and quietly applying this one to a
 *     Sales employee would produce a confident figure from the wrong policy.
 * @param revenueActualCr what the company actually achieved, in ₹ crore. Null until HR declares
 *     it, which is the normal state for most of a financial year — a payout cannot be computed
 *     from an undeclared result, and guessing zero would read as "you get nothing".
 * @param grrActualPercent gross revenue retention actually achieved, as a percentage. Null as
 *     above.
 */
@Document(collection = "variable_pay_plans")
public record VariablePayPlan(
    @Id String id,
    @Indexed(unique = true) String planKey,
    String fyLabel,
    String policyVersion,
    BigDecimal revenueActualCr,
    BigDecimal grrActualPercent,
    List<Band> revenueBands,
    List<Band> grrBands,
    BigDecimal revenueWeightPercent,
    BigDecimal grrWeightPercent,
    List<GradeWeight> gradeWeights,
    List<RatingPayout> ratingPayouts,
    List<String> excludedVerticals,
    String updatedAt,
    String updatedBy) {

  /**
   * One achievement band and what it funds.
   *
   * @param fromInclusive null for the open bottom band ("below 405 Cr")
   * @param toExclusive null for the open top band ("430 Cr and above")
   * @param funding the multiplier the policy calls a funding level — 0.00x through 1.20x
   */
  public record Band(String name, BigDecimal fromInclusive, BigDecimal toExclusive, BigDecimal funding) {

    public boolean covers(BigDecimal value) {
      if (value == null) return false;
      if (fromInclusive != null && value.compareTo(fromInclusive) < 0) return false;
      return toExclusive == null || value.compareTo(toExclusive) < 0;
    }
  }

  /**
   * How much of the payout rides on the company versus the individual, by grade.
   *
   * <p>The policy's own words for why this varies: "senior roles are more accountable for business
   * outcomes while individual contributors are primarily evaluated on personal performance". At
   * L0–L4 the company share is zero, so those employees are paid purely on their rating and a
   * missing company result does not block them.
   */
  public record GradeWeight(
      int fromLevel, int toLevel, BigDecimal companyPercent, BigDecimal individualPercent) {

    public boolean covers(int level) {
      return level >= fromLevel && level <= toLevel;
    }
  }

  /**
   * What an appraisal rating funds on the individual component.
   *
   * @param rating the numeric rating as the extract stores it. The policy labels them in its
   *     worked examples — "5 - Outstanding", "4 - Exceeds", "3 - Meets" — which is what makes a
   *     stored rating of 3 resolvable to a payout at all.
   * @param payoutPercent the policy says "up to" this figure, and leaves the final call to the
   *     organisation. Treated as the ceiling and reported as such; the alternative is presenting a
   *     discretionary maximum as a promise.
   */
  public record RatingPayout(int rating, String label, BigDecimal payoutPercent) {}
}
