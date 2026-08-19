package com.leadsquared.hr.knowledge.payroll;

import com.leadsquared.hr.knowledge.model.Employee;
import com.leadsquared.hr.knowledge.model.VariablePayPlan;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Computes a variable payout under Variable Pay Policy v2.0 (Non-Sales).
 *
 * <p><b>The arithmetic is here and not in the model.</b> A language model asked to weight two
 * components by grade, band two company metrics and apply a rating multiplier will usually be
 * right, and "usually" is the wrong standard for a number an employee will compare against their
 * payslip: the failure is a fluent, plausible figure with nothing to reconcile it against. Every
 * number here comes from {@link BigDecimal} arithmetic over a stored plan, and carries the policy
 * version and the inputs that produced it — so a disputed payout resolves to a band and a weighting
 * rather than to an opinion.
 *
 * <p>This replaced a department-slab calculator that took a single "achievement percent" and looked
 * it up in a table of invented slabs. That shape could not express this policy at all: there is no
 * one achievement figure, there are two company metrics banded independently and weighted 50/50,
 * plus an appraisal rating, and how much each side counts depends on the employee's grade. Feeding
 * the old calculator a percentage would have produced a confident number the policy does not
 * describe.
 *
 * <p>The formula, verified against all four worked examples in the published policy:
 *
 * <pre>
 *   payout = companyWeight × (revenueWeight × revenueFunding + grrWeight × grrFunding) × target
 *          + individualWeight × ratingPayout × target
 * </pre>
 */
@Service
public class VariablePayCalculator {

  private static final Logger log = LoggerFactory.getLogger(VariablePayCalculator.class);

  private static final BigDecimal HUNDRED = new BigDecimal("100");

  /**
   * Pulls the level out of a grade label.
   *
   * <p>Prefix-agnostic by design. The policy writes X7, the extract records L7, and payroll may yet
   * introduce a third spelling; they are one ladder and the number is the part that means anything.
   * Matching the letter as well is how a senior employee silently drops to the individual-only
   * weighting because their grade "did not match".
   */
  private static final Pattern GRADE_LEVEL = Pattern.compile("(\\d+)");

  private final VariablePayPlanService plans;

  public VariablePayCalculator(VariablePayPlanService plans) {
    this.plans = plans;
  }

  /** The plan currently in force, for the console and for explaining an answer. */
  public VariablePayPlan plan() {
    return plans.plan();
  }

  /**
   * Why this employee cannot be paid under this policy, or empty if they can.
   *
   * <p>Only the conditions the extract can actually decide. The policy also requires that the
   * employee not be on notice and not on a PIP at the time of payout, and that they hold a variable
   * component at all — the first two are absent from the extract entirely, so they are surfaced as
   * caveats by the answer layer rather than silently assumed to be satisfied. Asserting eligibility
   * this code cannot verify would be worse than declining to.
   */
  public Optional<String> ineligibility(Employee employee) {
    return ineligibility(employee, false);
  }

  private Optional<String> ineligibility(Employee employee, boolean targetSupposed) {
    if (employee == null) return Optional.of("No employee record.");

    VariablePayPlan plan = plans.plan();
    String vertical =
        employee.employment() == null ? null : employee.employment().businessUnit();

    if (vertical != null && plan.excludedVerticals() != null) {
      for (String excluded : plan.excludedVerticals()) {
        if (excluded != null && excluded.equalsIgnoreCase(vertical.trim())) {
          return Optional.of(
              vertical
                  + " is covered by its own variable pay policy, not the Non-Sales policy. Refer"
                  + " the employee to HR Operations for the applicable one.");
        }
      }
    }

    if (targetSupposed) return Optional.empty();

    if (employee.compensation() == null || employee.compensation().variableTargetAmount() == null) {
      return Optional.of("No variable pay component on this employee's compensation record.");
    }
    if (employee.compensation().variableTargetAmount().signum() <= 0) {
      return Optional.of("This employee's variable pay target is zero.");
    }
    return Optional.empty();
  }

  /**
   * The payout, using the employee's own latest appraisal rating and the declared company result.
   *
   * @return empty when it cannot be computed — no record, wrong policy, no variable component, no
   *     rating on file, or a grade that matches no weighting band. The caller routes to HR Ops
   *     rather than estimating.
   */
  public Optional<VariablePayResult> compute(Employee employee) {
    return compute(employee, null, null);
  }

  /**
   * The payout for a rating the employee supposed, which is what makes "what if I get a 4?"
   * answerable.
   *
   * <p>Carries no cycle, deliberately. An earlier version stamped the employee's latest appraisal
   * cycle onto a hypothetical, and the answer came out as "your computation is already based on an
   * Outstanding rating from FY 2025-26" to somebody whose record says 3 — a supposition read back
   * as a fact about their file. A hypothetical belongs to no cycle.
   *
   * @param ratingValue the numeric appraisal rating, as the extract stores them
   */
  public Optional<VariablePayResult> computeForRating(Employee employee, int ratingValue) {
    return compute(employee, ratingValue, (BigDecimal) null);
  }

  /**
   * The payout, with either input optionally supposed rather than read from the record.
   *
   * <p>Both overrides exist for the same reason: an employee working out what a change would mean
   * to them. "If my variable pay is 10,000" and "what if I'm rated 4" are the two halves they can
   * vary, and answering either from the record instead would answer a different question.
   *
   * @param statedRating a rating the employee supposed, or null to use their latest appraisal
   * @param statedTarget a variable pay target they supposed, or null to use their compensation
   *     record. A supposed target also stands in for a missing one — somebody with no variable
   *     component can still ask what a given one would pay.
   */
  public Optional<VariablePayResult> compute(
      Employee employee, Integer statedRating, BigDecimal statedTarget) {
    return compute(employee, statedRating, statedTarget, null);
  }

  /**
   * The payout, with any of the three inputs supposed rather than read from the record.
   *
   * @param statedGradeLevel a grade the employee supposed — "if I was at L8". Changes the
   *     weighting, and so whether company performance counts at all, which is the point of asking.
   */
  public Optional<VariablePayResult> compute(
      Employee employee, Integer statedRating, BigDecimal statedTarget, Integer statedGradeLevel) {

    Integer ratingValue = statedRating != null ? statedRating : latestRating(employee).orElse(null);
    if (ratingValue == null) return Optional.empty();

    // A supposed rating belongs to no cycle. Stamping the employee's own appraisal cycle onto one
    // produced "your computation is already based on an Outstanding rating from FY 2025-26" for
    // somebody whose record says 3 — a supposition read back as a fact about their file.
    String cycle = statedRating != null ? null : latestRatingCycle(employee).orElse(null);

    return compute(employee, ratingValue, cycle, statedTarget, statedGradeLevel);
  }

  private Optional<VariablePayResult> compute(
      Employee employee,
      int ratingValue,
      String cycle,
      BigDecimal statedTarget,
      Integer statedGradeLevel) {

    // A supposed target substitutes for a missing one, so the "no variable component" check is
    // skipped when the employee supplied a figure — they are asking what it would pay, not
    // claiming to have one. The policy check is not skipped: a Sales employee is on another
    // policy whatever number they name.
    if (ineligibility(employee, statedTarget != null).isPresent()) return Optional.empty();

    VariablePayPlan plan = plans.plan();
    Employee.Employment job = employee.employment();
    BigDecimal target =
        statedTarget != null
            ? statedTarget
            : employee.compensation() == null
                ? null
                : employee.compensation().variableTargetAmount();
    if (target == null || target.signum() <= 0) return Optional.empty();

    Integer level =
        statedGradeLevel != null ? statedGradeLevel : levelOf(job == null ? null : job.grade());
    if (level == null) {
      log.warn(
          "No grade level could be read for {}, so no weighting applies — payout not computed",
          employee.employeeCode());
      return Optional.empty();
    }

    Optional<VariablePayPlan.GradeWeight> weight =
        plan.gradeWeights().stream().filter(w -> w.covers(level)).findFirst();
    if (weight.isEmpty()) {
      log.warn("Grade level {} matches no weighting band in the plan — payout not computed", level);
      return Optional.empty();
    }

    Optional<VariablePayPlan.RatingPayout> rating =
        plan.ratingPayouts().stream().filter(r -> r.rating() == ratingValue).findFirst();
    if (rating.isEmpty()) {
      log.warn("Rating {} is not in the plan's rating table — payout not computed", ratingValue);
      return Optional.empty();
    }

    BigDecimal companyWeight = weight.get().companyPercent();
    BigDecimal individualWeight = weight.get().individualPercent();

    // Individual side first: it needs nothing but the rating, which is why an L0–L4 employee and an
    // undeclared company year are both still answerable.
    BigDecimal individual =
        share(individualWeight).multiply(share(rating.get().payoutPercent())).multiply(target);

    VariablePayPlan.Band revenueBand = bandFor(plan.revenueBands(), plan.revenueActualCr());
    VariablePayPlan.Band grrBand = bandFor(plan.grrBands(), plan.grrActualPercent());
    boolean noCompanyWeight = companyWeight.signum() == 0;
    boolean declared = revenueBand != null && grrBand != null;

    BigDecimal company = BigDecimal.ZERO;
    if (!noCompanyWeight && declared) {
      BigDecimal funding =
          share(plan.revenueWeightPercent())
              .multiply(revenueBand.funding())
              .add(share(plan.grrWeightPercent()).multiply(grrBand.funding()));
      company = share(companyWeight).multiply(funding).multiply(target);
    }

    BigDecimal payout = company.add(individual).setScale(2, RoundingMode.HALF_UP);

    return Optional.of(
        new VariablePayResult(
            statedGradeLevel != null ? "L" + statedGradeLevel : (job == null ? null : job.grade()),
            level,
            target,
            companyWeight,
            individualWeight,
            plan.revenueWeightPercent(),
            plan.grrWeightPercent(),
            revenueBand,
            grrBand,
            plan.revenueActualCr(),
            plan.grrActualPercent(),
            rating.get(),
            cycle,
            company.setScale(2, RoundingMode.HALF_UP),
            individual.setScale(2, RoundingMode.HALF_UP),
            payout,
            // "Declared" is only meaningful where the company share counts. At L0–L4 it does not,
            // so the payout is complete either way and must not be labelled partial.
            noCompanyWeight || declared,
            statedTarget != null,
            statedGradeLevel != null,
            plan.fyLabel(),
            plan.policyVersion(),
            employee.compensation() == null ? "INR" : employee.compensation().currency()));
  }

  /** The most recent appraisal rating on file, as a number. */
  public Optional<Integer> latestRating(Employee employee) {
    return latestPms(employee).flatMap(p -> parseRating(p.rating()));
  }

  /** Which cycle that rating came from, so an answer can say "from FY 2025-26" rather than imply now. */
  public Optional<String> latestRatingCycle(Employee employee) {
    return latestPms(employee).map(Employee.PmsRecord::cycle);
  }

  private static Optional<Employee.PmsRecord> latestPms(Employee employee) {
    if (employee == null || employee.pms() == null) return Optional.empty();
    return employee.pms().stream()
        .filter(p -> p != null && p.rating() != null && !p.rating().isBlank())
        // Cycles are "FY 2023-24" style, which sorts chronologically as text.
        .max(Comparator.comparing(p -> p.cycle() == null ? "" : p.cycle()));
  }

  private static Optional<Integer> parseRating(String raw) {
    if (raw == null) return Optional.empty();
    Matcher m = GRADE_LEVEL.matcher(raw);
    return m.find() ? Optional.of(Integer.parseInt(m.group(1))) : Optional.empty();
  }

  static Integer levelOf(String grade) {
    if (grade == null || grade.isBlank()) return null;
    Matcher m = GRADE_LEVEL.matcher(grade);
    return m.find() ? Integer.parseInt(m.group(1)) : null;
  }

  private static VariablePayPlan.Band bandFor(java.util.List<VariablePayPlan.Band> bands, BigDecimal value) {
    if (value == null || bands == null) return null;
    return bands.stream().filter(b -> b.covers(value)).findFirst().orElse(null);
  }

  /** A percentage as a fraction. Kept in BigDecimal throughout; 100 divides exactly, so no scale. */
  private static BigDecimal share(BigDecimal percent) {
    return percent == null ? BigDecimal.ZERO : percent.divide(HUNDRED);
  }
}
