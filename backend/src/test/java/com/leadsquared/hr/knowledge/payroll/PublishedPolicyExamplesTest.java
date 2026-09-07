package com.leadsquared.hr.knowledge.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.leadsquared.hr.knowledge.model.Employee;
import com.leadsquared.hr.knowledge.model.VariablePayPlan;
import com.leadsquared.hr.knowledge.store.VariablePayPlanRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The four worked examples printed in Variable Pay Policy v2.0 (Non-Sales).
 *
 * <p>These are the specification. HR published them with their arithmetic shown, so any change to
 * the formula, the weightings or the bands that stops one of them reconciling is a change that
 * disagrees with the document employees are reading — which is the failure this whole calculator
 * exists to prevent.
 *
 * <p>The policy writes grades as X4, X5, X7, X8; this organisation records L-grades. Same ladder,
 * so the examples are reproduced at the equivalent L level.
 */
class PublishedPolicyExamplesTest {

  private VariablePayPlanRepository repo;
  private VariablePayCalculator calculator;

  @BeforeEach
  void setUp() {
    repo = Mockito.mock(VariablePayPlanRepository.class);
    when(repo.findByPlanKey(VariablePayPlanService.NON_SALES)).thenReturn(Optional.empty());
    calculator = new VariablePayCalculator(new VariablePayPlanService(repo));
  }

  /** Declares a company result that lands in the named bands. */
  private void companyResult(String revenueCr, String grrPercent) {
    VariablePayPlan p = VariablePayPlanService.published();
    when(repo.findByPlanKey(VariablePayPlanService.NON_SALES))
        .thenReturn(
            Optional.of(
                new VariablePayPlan(
                    "1", p.planKey(), p.fyLabel(), p.policyVersion(),
                    revenueCr == null ? null : new BigDecimal(revenueCr),
                    grrPercent == null ? null : new BigDecimal(grrPercent),
                    p.revenueBands(), p.grrBands(),
                    p.revenueWeightPercent(), p.grrWeightPercent(),
                    p.gradeWeights(), p.ratingPayouts(), p.excludedVerticals(),
                    Instant.now().toString(), "test")));
  }

  private static Employee employee(String grade, String variableTarget, String rating) {
    return new Employee(
        "LS00001",
        List.of("someone@leadsquared.com"),
        "Test Employee",
        new Employee.Employment(
            grade, "Engineer", "Engineering", "Engineering", "Core", "Bengaluru",
            null, null, null, "confirmed", "M", "M2", "An HRBP"),
        new Employee.Compensation(
            new BigDecimal("2000000"), new BigDecimal(variableTarget), new BigDecimal("10"),
            new BigDecimal("140000"), new BigDecimal("130000"), null, "FY 2026-27", "INR",
                null, null, null, null, null, null, null, null, null),
        null,
        List.of(),
        List.of(new Employee.PmsRecord("FY 2025-26", rating, null, null)),
        Instant.now());
  }

  @Test
  @DisplayName("Example 1: L4 on 80,000, individual-only, Outstanding → 1,04,000")
  void example1() {
    // Revenue and GRR both met, and deliberately so: at L0–L4 the company carries no weight, so a
    // declared result must make no difference to the answer.
    companyResult("415", "86.7");
    var result = calculator.compute(employee("L4", "80000", "5"));

    assertThat(result).isPresent();
    assertThat(result.get().payoutAmount()).isEqualByComparingTo("104000");
    assertThat(result.get().companyComponent()).isEqualByComparingTo("0");
  }

  @Test
  @DisplayName("Example 2: L8 on 1,00,000, both metrics Near Target, Meets → 91,000")
  void example2() {
    companyResult("408", "85.5");
    var result = calculator.compute(employee("L8", "100000", "3"));

    assertThat(result).isPresent();
    assertThat(result.get().payoutAmount()).isEqualByComparingTo("91000");
    assertThat(result.get().companyComponent()).isEqualByComparingTo("21000");
    assertThat(result.get().individualComponent()).isEqualByComparingTo("70000");
  }

  @Test
  @DisplayName("Example 3: L5 on 1,00,000, both metrics at Target, Exceeds → 1,12,000")
  void example3() {
    companyResult("415", "86.7");
    var result = calculator.compute(employee("L5", "100000", "4"));

    assertThat(result).isPresent();
    assertThat(result.get().payoutAmount()).isEqualByComparingTo("112000");
    assertThat(result.get().companyComponent()).isEqualByComparingTo("20000");
    assertThat(result.get().individualComponent()).isEqualByComparingTo("92000");
  }

  @Test
  @DisplayName("Example 4: L7 on 1,00,000, revenue Near / GRR Above, Meets → 97,000")
  void example4() {
    companyResult("408", "87.2");
    var result = calculator.compute(employee("L7", "100000", "3"));

    assertThat(result).isPresent();
    assertThat(result.get().payoutAmount()).isEqualByComparingTo("97000");
    assertThat(result.get().companyComponent()).isEqualByComparingTo("27000");
  }

  @Test
  @DisplayName("An undeclared company result yields the individual share, flagged as partial")
  void undeclaredCompanyResult() {
    // The normal state for most of a financial year. Zero would read as "the company missed", and
    // refusing outright would withhold a figure the employee is entitled to know half of.
    companyResult(null, null);
    var result = calculator.compute(employee("L7", "100000", "3"));

    assertThat(result).isPresent();
    assertThat(result.get().companyDeclared()).isFalse();
    assertThat(result.get().payoutAmount()).isEqualByComparingTo("70000");
    assertThat(result.get().asFactBlock()).contains("NOT YET DECLARED");
  }

  @Test
  @DisplayName("Sales is on its own policy and is declined rather than computed")
  void salesIsExcluded() {
    companyResult("415", "86.7");
    Employee sales =
        new Employee(
            "LS00002", List.of("s@leadsquared.com"), "Sales Person",
            new Employee.Employment("L5", "AE", "Sales", "Sales", "Quota", "Bengaluru",
                null, null, null, "confirmed", "M", "M2", "HRBP"),
            new Employee.Compensation(new BigDecimal("2000000"), new BigDecimal("100000"),
                new BigDecimal("10"), null, null, null, "FY 2026-27", "INR",
                null, null, null, null, null, null, null, null, null),
            null, List.of(), List.of(new Employee.PmsRecord("FY 2025-26", "4", null, null)),
            Instant.now());

    assertThat(calculator.ineligibility(sales)).isPresent();
    assertThat(calculator.compute(sales)).isEmpty();
  }

  /**
   * Carried over from the slab calculator this replaced. The mechanics changed completely; these
   * four properties are the ones that were worth keeping.
   */
  @Test
  @DisplayName("A band boundary falls in the higher band, matching the policy's \"413 to <420\"")
  void bandBoundariesAreInclusiveUpward() {
    // 413 exactly is Target (1.00x), not Near Target (0.70x). The policy writes its bands as
    // "405 to <413" and "413 to <420", so the edge belongs to the band it opens.
    companyResult("413", "86.5");
    var result = calculator.compute(employee("L7", "100000", "3"));

    assertThat(result).isPresent();
    assertThat(result.get().revenueBand().name()).isEqualTo("Target");
    assertThat(result.get().grrBand().name()).isEqualTo("Target");
    assertThat(result.get().companyComponent()).isEqualByComparingTo("30000");
  }

  @Test
  @DisplayName("No variable target means no computation, not a zero")
  void missingTargetCannotBeComputed() {
    companyResult("415", "86.7");
    Employee noTarget =
        new Employee(
            "LS00003", List.of("n@leadsquared.com"), "No Target",
            new Employee.Employment("L5", "Analyst", "Operations", "Ops", "Support", "Noida",
                null, null, null, "confirmed", "M", "M2", "HRBP"),
            new Employee.Compensation(new BigDecimal("900000"), null, null, null, null, null,
                "FY 2026-27", "INR",
                null, null, null, null, null, null, null, null, null),
            null, List.of(), List.of(new Employee.PmsRecord("FY 2025-26", "4", null, null)),
            Instant.now());

    // Empty, so the caller routes to HR Ops rather than telling someone their payout is zero.
    assertThat(calculator.compute(noTarget)).isEmpty();
    assertThat(calculator.ineligibility(noTarget)).isPresent();
  }

  @Test
  @DisplayName("The fact block hands the model a finished number and forbids recalculating")
  void factBlockIsAuthoritative() {
    companyResult("415", "86.7");
    var result = calculator.compute(employee("L5", "100000", "4"));

    assertThat(result).isPresent();
    String block = result.get().asFactBlock();
    assertThat(block).contains("do not recalculate");
    // Digit grouping is locale-dependent (112,000.00 or 1,12,000.00), and which one it is says
    // nothing about the calculation. Compare without separators.
    assertThat(block.replace(",", "")).contains("112000.00");
    assertThat(block).contains("discretion");
  }

  @Test
  @DisplayName("With no stated rating, the latest appraisal cycle's rating is used and named")
  void fallsBackToLatestAppraisal() {
    companyResult("415", "86.7");
    Employee history =
        new Employee(
            "LS00004", List.of("h@leadsquared.com"), "Has History",
            new Employee.Employment("L5", "Engineer", "Engineering", "Engineering", "Core", "Pune",
                null, null, null, "confirmed", "M", "M2", "HRBP"),
            new Employee.Compensation(new BigDecimal("2000000"), new BigDecimal("100000"),
                new BigDecimal("10"), null, null, null, "FY 2026-27", "INR",
                null, null, null, null, null, null, null, null, null),
            null, List.of(),
            List.of(
                new Employee.PmsRecord("FY 2023-24", "5", null, null),
                new Employee.PmsRecord("FY 2025-26", "3", null, null),
                new Employee.PmsRecord("FY 2024-25", "4", null, null)),
            Instant.now());

    assertThat(calculator.latestRating(history)).contains(3);
    var result = calculator.compute(history);
    assertThat(result).isPresent();
    assertThat(result.get().ratingCycle()).isEqualTo("FY 2025-26");
    assertThat(result.get().rating().label()).isEqualTo("Meets");
  }

  // -------------------------------------------------------------------------
  // A target the employee supposes, rather than the one on their record
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("A supposed target is used instead of the record's, and flagged as supposed")
  void supposedTargetOverridesTheRecord() {
    companyResult("415", "86.7");
    var result = calculator.compute(employee("L5", "100000", "4"), null, new BigDecimal("10000"));

    assertThat(result).isPresent();
    assertThat(result.get().variableTarget()).isEqualByComparingTo("10000");
    assertThat(result.get().targetSupposed()).isTrue();
    // 20% x 1.00 x 10,000 = 2,000 company; 80% x 115% x 10,000 = 9,200 individual.
    assertThat(result.get().payoutAmount()).isEqualByComparingTo("11200");
  }

  @Test
  @DisplayName("Both halves can be supposed at once")
  void supposedTargetAndRating() {
    companyResult("415", "86.7");
    var result = calculator.compute(employee("L5", "100000", "3"), 5, new BigDecimal("10000"));

    assertThat(result).isPresent();
    // 20% x 1.00 x 10,000 = 2,000; 80% x 130% x 10,000 = 10,400.
    assertThat(result.get().payoutAmount()).isEqualByComparingTo("12400");
    assertThat(result.get().ratingCycle()).isNull();
  }

  @Test
  @DisplayName("A supposed target stands in for a missing one, so the what-if is still answerable")
  void supposedTargetSubstitutesForAMissingComponent() {
    companyResult("415", "86.7");
    Employee noComponent =
        new Employee(
            "LS00005", List.of("n@leadsquared.com"), "No Component",
            new Employee.Employment("L3", "Analyst", "Operations", "Ops", "Support", "Pune",
                null, null, null, "confirmed", "M", "M2", "HRBP"),
            new Employee.Compensation(new BigDecimal("900000"), null, null, null, null, null,
                "FY 2026-27", "INR",
                null, null, null, null, null, null, null, null, null),
            null, List.of(), List.of(new Employee.PmsRecord("FY 2025-26", "3", null, null)),
            Instant.now());

    assertThat(calculator.compute(noComponent)).isEmpty();
    var supposed = calculator.compute(noComponent, null, new BigDecimal("10000"));
    assertThat(supposed).isPresent();
    assertThat(supposed.get().payoutAmount()).isEqualByComparingTo("10000");
  }

  @Test
  @DisplayName("Sales stays on its own policy however large a figure is supposed")
  void supposingATargetDoesNotBypassThePolicyBoundary() {
    companyResult("415", "86.7");
    Employee sales =
        new Employee(
            "LS00006", List.of("s2@leadsquared.com"), "Sales Person",
            new Employee.Employment("L5", "AE", "Sales", "Sales", "Quota", "Bengaluru",
                null, null, null, "confirmed", "M", "M2", "HRBP"),
            new Employee.Compensation(new BigDecimal("2000000"), new BigDecimal("100000"),
                null, null, null, null, "FY 2026-27", "INR",
                null, null, null, null, null, null, null, null, null),
            null, List.of(), List.of(new Employee.PmsRecord("FY 2025-26", "4", null, null)),
            Instant.now());

    assertThat(calculator.compute(sales, null, new BigDecimal("500000"))).isEmpty();
  }

  @Test
  @DisplayName("A supposed grade re-weights the payout — \"if I was at L8\"")
  void supposedGradeChangesTheWeighting() {
    companyResult("415", "86.7");
    // The employee is L3, where company performance carries nothing. Asked about L8 the answer has
    // to change shape, not just size: 30/70 instead of 0/100.
    var atL3 = calculator.compute(employee("L3", "50000", "3"), null, null, null);
    var atL8 = calculator.compute(employee("L3", "50000", "3"), null, null, 8);

    assertThat(atL3).isPresent();
    assertThat(atL3.get().companyComponent()).isEqualByComparingTo("0");
    assertThat(atL3.get().payoutAmount()).isEqualByComparingTo("50000");

    assertThat(atL8).isPresent();
    assertThat(atL8.get().gradeSupposed()).isTrue();
    assertThat(atL8.get().grade()).isEqualTo("L8");
    // 30% x 1.00 x 50,000 = 15,000 company; 70% x 100% x 50,000 = 35,000 individual.
    assertThat(atL8.get().companyComponent()).isEqualByComparingTo("15000");
    assertThat(atL8.get().payoutAmount()).isEqualByComparingTo("50000");
  }

  @Test
  @DisplayName("The declared company result is stated even where it carries no weight")
  void declaredResultIsAlwaysVisible() {
    // Suppressing it at L0-L4 left the model with no company figures at all, so the same employee
    // asking "and if I was at L8?" was told this year's achievement was unknown - while it sat
    // declared in the plan.
    companyResult("415", "86.7");
    var result = calculator.compute(employee("L3", "50000", "3"));

    assertThat(result).isPresent();
    String block = result.get().asFactBlock();
    assertThat(block).contains("Company result this year");
    assertThat(block).contains("415");
    assertThat(block).contains("86.7");
    assertThat(block).contains("carries no weight at this grade");
  }

  @Test
  @DisplayName("The fact block shows its working, so the employee can check the figure")
  void factBlockShowsItsWorking() {
    companyResult("408", "87.2");
    var result = calculator.compute(employee("L7", "100000", "3"));

    assertThat(result).isPresent();
    String block = result.get().asFactBlock().replace(",", "");
    assertThat(block).contains("How this was calculated");
    assertThat(block).contains("30% x (50% x 0.7 + 50% x 1.1) x 100000.00 = 27000.00");
    assertThat(block).contains("70% x 100% x 100000.00 = 70000.00");
    assertThat(block).contains("total = 27000.00 + 70000.00 = 97000.00");
  }

  @Test
  @DisplayName("The policy's X-grades and this company's L-grades are the same ladder")
  void gradePrefixIsIgnored() {
    assertThat(VariablePayCalculator.levelOf("L7")).isEqualTo(7);
    assertThat(VariablePayCalculator.levelOf("X7")).isEqualTo(7);
    assertThat(VariablePayCalculator.levelOf("l10")).isEqualTo(10);
    assertThat(VariablePayCalculator.levelOf("")).isNull();
  }
}
