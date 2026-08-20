package com.leadsquared.hr.knowledge.payroll;

import com.leadsquared.hr.knowledge.model.VariablePayPlan;
import java.math.BigDecimal;
import java.text.DecimalFormat;

/**
 * An itemised variable payout, computed under Variable Pay Policy v2.0 (Non-Sales).
 *
 * <p>Itemised because the policy's payout is a sum of two independently-weighted components, and an
 * employee who disagrees with the total needs to see which step they disagree with — their grade's
 * weighting, the company's achieved revenue and GRR, or their own rating. A single number invites
 * "that's not what I calculated" with nothing to reconcile against.
 *
 * @param companyComponent the company share: grade weight × (revenue weight × funding + GRR weight
 *     × funding) × target. Zero at L0–L4, where the policy assigns the company no weight at all.
 * @param individualComponent the individual share: grade weight × rating payout × target
 * @param companyDeclared false when HR has not entered this year's revenue and GRR. The company
 *     component is then omitted rather than treated as zero — an undeclared result is not a missed
 *     one, and reporting a partial figure as the payout would understate it.
 */
public record VariablePayResult(
    String grade,
    int gradeLevel,
    BigDecimal variableTarget,
    BigDecimal companyWeightPercent,
    BigDecimal individualWeightPercent,
    /** How the company share splits between the two metrics — 50/50 in the published policy. */
    BigDecimal revenueWeightPercent,
    BigDecimal grrWeightPercent,
    VariablePayPlan.Band revenueBand,
    VariablePayPlan.Band grrBand,
    BigDecimal revenueActualCr,
    BigDecimal grrActualPercent,
    VariablePayPlan.RatingPayout rating,
    String ratingCycle,
    BigDecimal companyComponent,
    BigDecimal individualComponent,
    BigDecimal payoutAmount,
    boolean companyDeclared,
    /**
     * Whether the target was supposed by the employee rather than read from their record.
     *
     * <p>Reported because the two answer different questions. "If my variable pay is 10,000" is a
     * what-if; presenting its result as their payout would tell somebody on ₹76,500 that they are
     * getting ₹10,000.
     */
    boolean targetSupposed,
    /** Whether the grade was supposed — "if I was at L8" — rather than the employee's own. */
    boolean gradeSupposed,
    String fyLabel,
    String policyVersion,
    String currency) {

  private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");

  private static final BigDecimal TWELVE = new BigDecimal("12");

  private static String trim(BigDecimal value) {
    return value == null ? "—" : value.stripTrailingZeros().toPlainString();
  }

  /**
   * The computed figures as lines of fact for the model to narrate.
   *
   * <p>Handed over pre-computed so the model has nothing left to calculate. Its remaining job is to
   * read these back in a sentence, which is the one thing it is reliably better at than arithmetic.
   */
  public String asFactBlock() {
    String unit = currency == null ? "INR" : currency;
    StringBuilder out = new StringBuilder();

    out.append("Variable pay computation (authoritative — do not recalculate):\n");
    out.append("- Policy: Variable Pay Policy v")
        .append(policyVersion)
        .append(" (Non-Sales), ")
        .append(fyLabel)
        .append("\n");
    out.append("- Grade: ")
        .append(grade)
        .append(" (level ")
        .append(gradeLevel)
        .append(")")
        .append(gradeSupposed ? "  (SUPPOSED BY THE EMPLOYEE, not their own grade)" : "")
        .append("\n");
    out.append("- Annual variable pay target: ")
        .append(unit)
        .append(" ")
        .append(MONEY.format(variableTarget))
        .append(targetSupposed ? "  (SUPPOSED BY THE EMPLOYEE, not from their record)" : "")
        .append("\n");
    out.append("- Weighting at this grade: company ")
        .append(trim(companyWeightPercent))
        .append("%, individual ")
        .append(trim(individualWeightPercent))
        .append("%\n");

    // The declared result is printed whether or not it counts at this grade. Suppressing it at
    // L0–L4 left the model with no company figures in context at all, so when the same employee
    // asked "and if I was at L8?" it answered that it did not have this year's achievement —
    // while revenue and GRR sat declared in the plan.
    if (revenueBand != null && grrBand != null) {
      out.append("- Company result this year: revenue ")
          .append(trim(revenueActualCr))
          .append(" Cr → ")
          .append(revenueBand.name())
          .append(" (")
          .append(trim(revenueBand.funding()))
          .append("x), GRR ")
          .append(trim(grrActualPercent))
          .append("% → ")
          .append(grrBand.name())
          .append(" (")
          .append(trim(grrBand.funding()))
          .append("x)\n");
    }

    if (companyWeightPercent.signum() == 0) {
      out.append(
          "- Company performance: carries no weight at this grade, so the payout rests entirely on"
              + " the individual rating\n");
    } else if (!companyDeclared) {
      out.append(
          "- Company performance: NOT YET DECLARED for this year, so the company share cannot be"
              + " computed. The figure below is the individual share only and is therefore a"
              + " PARTIAL payout — say so plainly and do not present it as the total.\n");
    } else {
      out.append("- Company component: ")
          .append(unit)
          .append(" ")
          .append(MONEY.format(companyComponent))
          .append("\n");
    }

    out.append("- Appraisal rating: ")
        .append(rating.rating())
        .append(" — ")
        .append(rating.label())
        .append(" (up to ")
        .append(trim(rating.payoutPercent()))
        .append("% of the individual share)");
    if (ratingCycle != null && !ratingCycle.isBlank()) {
      out.append(", from ").append(ratingCycle);
    }
    out.append("\n");
    out.append("- Individual component: ")
        .append(unit)
        .append(" ")
        .append(MONEY.format(individualComponent))
        .append("\n");
    out.append("- Computed payout: ")
        .append(unit)
        .append(" ")
        .append(MONEY.format(payoutAmount))
        .append(companyDeclared || companyWeightPercent.signum() == 0 ? "" : " (individual share only)")
        .append("\n");

    // The arithmetic, laid out the way the policy lays out its own worked examples. An employee
    // who disagrees with the total needs to see which line they disagree with, and a number with
    // no derivation invites "that is not what I calculated" with nothing to reconcile against.
    // Handed over as finished text so the model narrates it rather than re-deriving it.
    out.append("- How this was calculated:\n");
    if (companyWeightPercent.signum() != 0 && companyDeclared) {
      out.append("    company share = ")
          .append(trim(companyWeightPercent))
          .append("% x (")
          .append(trim(revenueWeightPercent))
          .append("% x ")
          .append(trim(revenueBand.funding()))
          .append(" + ")
          .append(trim(grrWeightPercent))
          .append("% x ")
          .append(trim(grrBand.funding()))
          .append(") x ")
          .append(MONEY.format(variableTarget))
          .append(" = ")
          .append(MONEY.format(companyComponent))
          .append("\n");
    } else if (companyWeightPercent.signum() == 0) {
      out.append("    company share = 0% at this grade, so nothing from company performance\n");
    } else {
      out.append("    company share = not computable until the year's revenue and GRR are declared\n");
    }
    out.append("    individual share = ")
        .append(trim(individualWeightPercent))
        .append("% x ")
        .append(trim(rating.payoutPercent()))
        .append("% x ")
        .append(MONEY.format(variableTarget))
        .append(" = ")
        .append(MONEY.format(individualComponent))
        .append("\n");
    out.append("    total = ")
        .append(MONEY.format(companyComponent))
        .append(" + ")
        .append(MONEY.format(individualComponent))
        .append(" = ")
        .append(MONEY.format(payoutAmount))
        .append("\n");
    out.append(
        "Show these steps to the employee — they asked how it is worked out, and a figure without"
            + " its derivation is one they cannot check.\n");

    // A monthly equivalent, because employees ask for one and the model must not divide. Labelled
    // as arithmetic rather than as a schedule: the policy pays this annually, in one payout after
    // the financial year closes, so a monthly figure that reads like a salary line would be a
    // wrong answer dressed as a helpful one.
    out.append("- Monthly equivalent (arithmetic only — this is NOT paid monthly): ")
        .append(unit)
        .append(" ")
        .append(MONEY.format(payoutAmount.divide(TWELVE, 2, java.math.RoundingMode.HALF_UP)))
        .append(" a month, if the annual figure above were spread over 12 months. The policy pays"
            + " variable pay ANNUALLY, as one payout after the financial year closes — say so"
            + " whenever quoting this.\n");

    // The policy says "up to" each rating figure and leaves the final call to the organisation:
    // "Final payout % against individual performance metric is at the discretion of the
    // organisation". Stating a discretionary ceiling as a promise is the failure mode here.
    out.append(
        "This is the maximum indicated by the policy, before tax. The policy sets the individual"
            + " figures as \"up to\" and leaves the final percentage to the organisation's"
            + " discretion, and HR and Leadership may revise, pause or withhold payouts. State that"
            + " alongside the number.\n");
    return out.toString();
  }
}
