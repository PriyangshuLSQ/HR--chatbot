package com.leadsquared.hr.knowledge.payroll;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The department-wise variable payout matrix, as data rather than as prose.
 *
 * <p>Kept out of the policy documents on purpose. The matrix in the Compensation policy is
 * what an employee reads; this is what the payout is computed from, and the two must not be
 * the same artifact — a number the model reads out of a retrieved passage is a number that
 * can be misread, whereas a slab looked up here is covered by a test.
 *
 * @param version stamped into every computed answer, so a disputed payout can be traced to
 *     the matrix that produced it rather than to "whatever the file said at the time"
 * @param departments one entry per department, plus a {@code default} entry used when an
 *     employee's department has no specific matrix
 */
public record VariablePayMatrix(String version, String effectiveFrom, List<Department> departments) {

  /** The name of the fallback entry. Every real matrix should still define it. */
  public static final String DEFAULT_DEPARTMENT = "default";

  /**
   * @param slabs achievement bands, each mapping a range of target achievement to a
   *     percentage of the variable target that pays out
   * @param maxPayoutPercentOfTarget the ceiling on overachievement, if the department caps
   *     it. Null means the highest slab's own percentage is the ceiling.
   */
  public record Department(
      String name, List<Slab> slabs, BigDecimal maxPayoutPercentOfTarget, String notes) {}

  /**
   * One achievement band.
   *
   * @param fromPercentInclusive lower bound of achievement, inclusive
   * @param toPercentInclusive upper bound, inclusive. Null means unbounded — the
   *     overachievement slab.
   * @param payoutPercentOfTarget what fraction of the variable target pays out in this band
   */
  public record Slab(
      BigDecimal fromPercentInclusive,
      BigDecimal toPercentInclusive,
      BigDecimal payoutPercentOfTarget) {

    boolean covers(BigDecimal achievement) {
      if (achievement.compareTo(fromPercentInclusive) < 0) return false;
      return toPercentInclusive == null || achievement.compareTo(toPercentInclusive) <= 0;
    }
  }

  /** The matrix for a department, falling back to {@code default}. */
  public Optional<Department> forDepartment(String department) {
    if (departments == null || departments.isEmpty()) return Optional.empty();
    String wanted = department == null ? "" : department.trim().toLowerCase(Locale.ROOT);

    Optional<Department> exact =
        departments.stream()
            .filter(d -> d.name() != null && d.name().toLowerCase(Locale.ROOT).equals(wanted))
            .findFirst();
    if (exact.isPresent()) return exact;

    return departments.stream()
        .filter(d -> DEFAULT_DEPARTMENT.equalsIgnoreCase(d.name()))
        .findFirst();
  }
}
