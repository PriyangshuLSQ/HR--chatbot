package com.leadsquared.hr.knowledge.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadsquared.hr.knowledge.model.Employee;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.DefaultResourceLoader;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The payout matrix, scenario by scenario.
 *
 * <p>These exist because the spec asks for correct output on <em>all</em> matrix scenarios, and
 * that is a promise only deterministic code can keep — a model asked to multiply a target by a
 * slab percentage produces a number with nothing to reconcile it against. Every case here pins
 * one cell of the matrix, so a disputed payout resolves to a test rather than to an opinion.
 *
 * <p>They run against the placeholder matrix shipped in resources. When HR Ops supplies the real
 * one, the expected values below change with it — that is the intended maintenance, and it is the
 * point: the matrix cannot change without someone updating a test.
 */
class VariablePayCalculatorTest {

  private final VariablePayCalculator calculator =
      new VariablePayCalculator(
          new DefaultResourceLoader(), new ObjectMapper(), "classpath:variable-pay-matrix.json");

  private static Employee employee(String department, String target) {
    return new Employee(
        "LS00001",
        List.of("someone@leadsquared.com"),
        "Test Employee",
        new Employee.Employment(
            "L6", "AE", "Revenue", department, "Quota", "Bengaluru",
            null, null, null, "confirmed", "A Manager", "Another Manager"),
        new Employee.Compensation(
            new BigDecimal("2000000"),
            new BigDecimal(target),
            new BigDecimal("20"),
            new BigDecimal("133333"),
            null,
            "FY 2025-26",
            "INR"),
        null,
        List.of(),
        List.of(new Employee.PmsRecord("FY 2025-26", "Exceeds", new BigDecimal("95"), null)),
        Instant.now());
  }

  @Test
  void theMatrixLoads() {
    assertThat(calculator.isAvailable()).isTrue();
    // The version is quoted in every computed answer, so a placeholder figure is identifiable.
    assertThat(calculator.matrixVersion()).isEqualTo("PLACEHOLDER-0");
  }

  /**
   * The spec's own worked example is the first row: Sales, 85% achievement. The 80–89.99 slab
   * pays 85% of target, so 400,000 × 0.85 = 340,000.
   */
  @ParameterizedTest(name = "{0} at {1}% of target {2} pays {3}")
  @CsvSource({
    // department, achievement, target, expected payout
    "Sales,        85,   400000, 340000.00",
    "Sales,        100,  400000, 400000.00",
    "Sales,        90,   400000, 380000.00",
    "Sales,        70,   400000, 280000.00",
    "Sales,        60,   400000, 200000.00",
    "Engineering,  85,   300000, 240000.00",
    "Engineering,  100,  300000, 300000.00",
    "Marketing,    95,   250000, 225000.00", // falls through to the default matrix
  })
  @DisplayName("Each slab pays exactly what the matrix says")
  void slabsPayCorrectly(String department, String achievement, String target, String expected) {
    var result = calculator.compute(employee(department, target), new BigDecimal(achievement));

    assertThat(result).isPresent();
    assertThat(result.get().payoutAmount()).isEqualByComparingTo(new BigDecimal(expected));
  }

  @Test
  @DisplayName("Below the lowest slab pays zero, and says so rather than failing")
  void belowThresholdPaysZero() {
    var result = calculator.compute(employee("Sales", "400000"), new BigDecimal("40"));

    // Zero is an answer. Empty would mean "cannot compute", which would route to HR Ops and
    // waste their time on a question that has a definite answer.
    assertThat(result).isPresent();
    assertThat(result.get().payoutAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(result.get().slabApplied()).isNull();
  }

  @Test
  @DisplayName("Overachievement is capped at the department maximum")
  void overachievementIsCapped() {
    // Sales caps at 150%. The top slab already pays 150, so 300% achievement must not pay 300%.
    var result = calculator.compute(employee("Sales", "400000"), new BigDecimal("300"));

    assertThat(result).isPresent();
    assertThat(result.get().payoutAmount()).isEqualByComparingTo(new BigDecimal("600000.00"));
    assertThat(result.get().payoutPercentOfTarget()).isEqualByComparingTo(new BigDecimal("150"));
  }

  @Test
  @DisplayName("Slab boundaries land in the lower band, not the higher one")
  void boundariesAreInclusiveDownward() {
    // 89.99 is the top of the 85% slab; 90 is the bottom of the 95% slab. An off-by-one here
    // is a real payout dispute, which is why it is pinned rather than assumed.
    var lower = calculator.compute(employee("Sales", "400000"), new BigDecimal("89.99"));
    var upper = calculator.compute(employee("Sales", "400000"), new BigDecimal("90"));

    assertThat(lower.orElseThrow().payoutPercentOfTarget()).isEqualByComparingTo(new BigDecimal("85"));
    assertThat(upper.orElseThrow().payoutPercentOfTarget()).isEqualByComparingTo(new BigDecimal("95"));
  }

  @Test
  @DisplayName("No variable target means no computation, not a zero")
  void missingTargetCannotBeComputed() {
    Employee noTarget =
        new Employee(
            "LS00002", List.of("x@leadsquared.com"), "No Target",
            new Employee.Employment("L4", "Analyst", "Ops", "Sales", "Support", "Noida",
                null, null, null, "confirmed", "M", "M2"),
            new Employee.Compensation(
                new BigDecimal("900000"), null, null, null, null, null, "INR"),
            null, List.of(), List.of(), Instant.now());

    // Empty, so the caller routes to HR Ops rather than telling someone their payout is zero.
    assertThat(calculator.compute(noTarget, new BigDecimal("100"))).isEmpty();
  }

  @Test
  @DisplayName("The fact block hands the model a finished number and forbids recalculating")
  void factBlockIsAuthoritative() {
    String facts =
        calculator
            .compute(employee("Sales", "400000"), new BigDecimal("85"))
            .orElseThrow()
            .asFactBlock();

    assertThat(facts).contains("do not recalculate");
    assertThat(facts).contains("340,000.00");
    assertThat(facts).contains("85%");
    assertThat(facts).contains("PLACEHOLDER-0");
    // The indicative-and-pre-tax caveat has to travel with the number, not be left to the model.
    assertThat(facts).contains("before tax");
  }

  @Test
  @DisplayName("With no stated figure, the latest appraisal achievement is used")
  void fallsBackToLatestAppraisal() {
    assertThat(calculator.latestAchievement(employee("Sales", "400000")))
        .contains(new BigDecimal("95"));
  }
}
