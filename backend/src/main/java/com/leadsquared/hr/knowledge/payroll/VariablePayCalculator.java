package com.leadsquared.hr.knowledge.payroll;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leadsquared.hr.knowledge.model.Employee;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

/**
 * Computes a variable payout from an employee's own target and the department matrix.
 *
 * <p><b>The arithmetic is here and not in the model.</b> A language model asked to multiply a
 * target by a slab percentage will usually be right, and "usually" is the wrong standard for
 * a number an employee will compare against their payslip: the failure is a fluent, plausible
 * figure with nothing to reconcile it against. Every number this returns comes from
 * {@link BigDecimal} arithmetic over a matrix under test, and carries the matrix version that
 * produced it — so a disputed payout resolves to a slab and a test rather than to an opinion.
 *
 * <p>The model's job is the part it is good at: working out which department and achievement
 * figure the question implies, then reading this result back as prose.
 *
 * <p>This reverses, for this one path only, the grounding rule that the assistant must never
 * calculate. That rule exists to stop invented numbers and still applies everywhere else;
 * here the number is not invented, it is computed and traceable.
 */
@Service
public class VariablePayCalculator {

  private static final Logger log = LoggerFactory.getLogger(VariablePayCalculator.class);

  private static final BigDecimal HUNDRED = new BigDecimal("100");

  private final VariablePayMatrix matrix;

  public VariablePayCalculator(
      ResourceLoader resources,
      ObjectMapper mapper,
      @Value("${knowledge.payroll.matrix:classpath:variable-pay-matrix.json}") String location) {
    this.matrix = load(resources, mapper, location);
  }

  private static VariablePayMatrix load(
      ResourceLoader resources, ObjectMapper mapper, String location) {
    Resource resource = resources.getResource(location);
    if (!resource.exists()) {
      // Bootable without it: variable-pay questions then decline and route to HR Ops, which
      // is the correct degradation. Refusing to start would take the policy desk down too.
      log.warn(
          "No variable pay matrix at {} — payout computation is unavailable and those questions"
              + " will be routed to HR Ops.",
          location);
      return new VariablePayMatrix("none", null, List.of());
    }
    try (InputStream in = resource.getInputStream()) {
      // Unknown keys are tolerated deliberately. This file comes from HR Ops, and a matrix
      // carrying a comment, an owner or a revision note must not stop payout computation —
      // failing closed on an extra key would take the feature down over metadata.
      VariablePayMatrix loaded =
          mapper
              .copy()
              .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
              .readValue(in, VariablePayMatrix.class);
      log.info(
          "Variable pay matrix version={} effectiveFrom={} departments={}",
          loaded.version(),
          loaded.effectiveFrom(),
          loaded.departments().stream().map(VariablePayMatrix.Department::name).toList());
      return loaded;
    } catch (IOException e) {
      log.error("Could not read the variable pay matrix at {} — computation disabled", location, e);
      return new VariablePayMatrix("unreadable", null, List.of());
    }
  }

  public boolean isAvailable() {
    return !matrix.departments().isEmpty();
  }

  public String matrixVersion() {
    return matrix.version();
  }

  /** The slab table for an employee's own department, for "what is my payout matrix". */
  public Optional<VariablePayMatrix.Department> matrixFor(Employee employee) {
    if (employee == null || employee.employment() == null) return Optional.empty();
    return matrix.forDepartment(employee.employment().department());
  }

  /**
   * An itemised payout for a stated achievement.
   *
   * @param achievementPercent target achievement. Taken from the employee's question when
   *     they state one, and from their latest PMS record when they do not.
   * @return empty when no matrix covers the department, or when the record carries no
   *     variable target — both of which are "route to HR Ops", not "compute zero"
   */
  public Optional<VariablePayResult> compute(Employee employee, BigDecimal achievementPercent) {
    if (employee == null || achievementPercent == null) return Optional.empty();
    if (employee.compensation() == null || employee.compensation().variableTargetAmount() == null) {
      return Optional.empty();
    }

    Optional<VariablePayMatrix.Department> department = matrixFor(employee);
    if (department.isEmpty()) return Optional.empty();

    VariablePayMatrix.Department dept = department.get();
    Optional<VariablePayMatrix.Slab> slab =
        dept.slabs().stream().filter(s -> s.covers(achievementPercent)).findFirst();

    // No slab covers it: below the threshold at which anything pays out. Zero is the right
    // answer and a different thing from "cannot compute", so it is returned rather than empty.
    BigDecimal payoutPercent =
        slab.map(VariablePayMatrix.Slab::payoutPercentOfTarget).orElse(BigDecimal.ZERO);

    boolean capped = false;
    if (dept.maxPayoutPercentOfTarget() != null
        && payoutPercent.compareTo(dept.maxPayoutPercentOfTarget()) > 0) {
      payoutPercent = dept.maxPayoutPercentOfTarget();
      capped = true;
    }

    BigDecimal target = employee.compensation().variableTargetAmount();
    BigDecimal payout =
        target
            .multiply(payoutPercent)
            .divide(HUNDRED, 2, RoundingMode.HALF_UP);

    return Optional.of(
        new VariablePayResult(
            dept.name(),
            employee.employment() == null ? null : employee.employment().grade(),
            target,
            achievementPercent,
            payoutPercent,
            payout,
            capped,
            slab.orElse(null),
            matrix.version(),
            employee.compensation().currency()));
  }

  /** The achievement to use when the employee names none: their latest PMS record. */
  public Optional<BigDecimal> latestAchievement(Employee employee) {
    if (employee == null || employee.pms() == null || employee.pms().isEmpty()) {
      return Optional.empty();
    }
    return employee.pms().stream()
        .filter(p -> p.achievementPercent() != null)
        .reduce((first, second) -> second)
        .map(Employee.PmsRecord::achievementPercent);
  }
}
