package com.leadsquared.hr.knowledge.payroll;

import java.math.BigDecimal;
import java.text.DecimalFormat;

/**
 * An itemised variable payout.
 *
 * <p>Itemised rather than a single figure because the spec asks for a clear, itemised answer,
 * and because an employee who disagrees with the number needs to see which step they
 * disagree with — the target, the achievement, or the slab.
 *
 * @param matrixVersion which matrix produced this, carried through to the answer text
 */
public record VariablePayResult(
    String department,
    String grade,
    BigDecimal variableTarget,
    BigDecimal achievementPercent,
    BigDecimal payoutPercentOfTarget,
    BigDecimal payoutAmount,
    boolean capped,
    VariablePayMatrix.Slab slabApplied,
    String matrixVersion,
    String currency) {

  private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");

  /**
   * The computed figures as lines of fact for the model to narrate.
   *
   * <p>Given to the model as pre-computed text so it has nothing left to calculate — the
   * numbers are already final, and its only remaining job is to read them back in a sentence.
   */
  public String asFactBlock() {
    StringBuilder out = new StringBuilder();
    String unit = currency == null ? "INR" : currency;
    out.append("Variable pay computation (authoritative — do not recalculate):\n");
    out.append("- Department matrix: ").append(department);
    if (grade != null) out.append(" (grade ").append(grade).append(")");
    out.append("\n");
    out.append("- Annual variable target: ").append(unit).append(" ")
        .append(MONEY.format(variableTarget)).append("\n");
    out.append("- Target achievement applied: ").append(trim(achievementPercent)).append("%\n");
    if (slabApplied != null) {
      out.append("- Slab matched: ")
          .append(trim(slabApplied.fromPercentInclusive()))
          .append("% to ")
          .append(
              slabApplied.toPercentInclusive() == null
                  ? "above"
                  : trim(slabApplied.toPercentInclusive()) + "%")
          .append(" achievement\n");
    } else {
      out.append("- Slab matched: none — achievement is below the threshold at which any"
          + " variable pays out\n");
    }
    out.append("- Payout rate: ").append(trim(payoutPercentOfTarget)).append("% of target");
    if (capped) out.append(" (capped at the department maximum)");
    out.append("\n");
    out.append("- Computed payout: ").append(unit).append(" ")
        .append(MONEY.format(payoutAmount)).append("\n");
    out.append("- Matrix version: ").append(matrixVersion).append("\n");
    out.append(
        "This is an indicative gross figure before tax and before any moderation applied at"
            + " appraisal. State that alongside the number.\n");
    return out.toString();
  }

  /** Drops a trailing {@code .00} so a whole percentage reads as "85%", not "85.00%". */
  private static String trim(BigDecimal value) {
    return value.stripTrailingZeros().toPlainString();
  }
}
