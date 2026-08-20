package com.leadsquared.hr.knowledge.payroll;

import com.leadsquared.hr.knowledge.model.VariablePayPlan;
import com.leadsquared.hr.knowledge.store.VariablePayPlanRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Loads and edits the variable pay plan.
 *
 * <p>Seeded once from <i>Variable Pay Policy v2.0 (Non-Sales)</i> and thereafter owned by whoever
 * maintains it in the console. Seeded rather than re-asserted on every boot, which is the opposite
 * of how the IAM roles work and deliberately so: a role's permissions are a property of the build,
 * while last year's revenue is a fact somebody entered. Overwriting that on restart would throw
 * away the only copy of it.
 */
@Service
public class VariablePayPlanService {

  private static final Logger log = LoggerFactory.getLogger(VariablePayPlanService.class);

  /** The only plan that exists. Sales and the US entity are separate policies, not variants. */
  public static final String NON_SALES = "non-sales";

  private final VariablePayPlanRepository plans;

  public VariablePayPlanService(VariablePayPlanRepository plans) {
    this.plans = plans;
  }

  /**
   * The plan, or the published defaults when the store has nothing or cannot be reached.
   *
   * <p>Falling back to defaults rather than failing: the seeded values <i>are</i> the policy, so an
   * unreachable Mongo degrades to answering from the published document rather than to refusing
   * every payout question. What it cannot invent is {@code revenueActualCr} and {@code
   * grrActualPercent} — those are null in the defaults, and the calculator declines the company
   * component without them instead of assuming a result.
   */
  public VariablePayPlan plan() {
    try {
      return plans.findByPlanKey(NON_SALES).orElseGet(VariablePayPlanService::published);
    } catch (RuntimeException e) {
      log.warn("Could not read the variable pay plan, using the published defaults: {}", e.toString());
      return published();
    }
  }

  /** Stores an edited plan, stamped with who changed it. */
  public VariablePayPlan save(VariablePayPlan edited, String actor) {
    VariablePayPlan current = plan();

    VariablePayPlan toSave =
        new VariablePayPlan(
            current.id(),
            NON_SALES,
            blankTo(edited.fyLabel(), current.fyLabel()),
            blankTo(edited.policyVersion(), current.policyVersion()),
            // Nullable on purpose, and passed through as given: clearing the achieved revenue is a
            // legitimate edit ("this year is not declared yet"), so an absent value here means
            // absent, not unchanged.
            edited.revenueActualCr(),
            edited.grrActualPercent(),
            emptyTo(edited.revenueBands(), current.revenueBands()),
            emptyTo(edited.grrBands(), current.grrBands()),
            edited.revenueWeightPercent() == null
                ? current.revenueWeightPercent()
                : edited.revenueWeightPercent(),
            edited.grrWeightPercent() == null ? current.grrWeightPercent() : edited.grrWeightPercent(),
            emptyTo(edited.gradeWeights(), current.gradeWeights()),
            emptyTo(edited.ratingPayouts(), current.ratingPayouts()),
            edited.excludedVerticals() == null
                ? current.excludedVerticals()
                : edited.excludedVerticals(),
            Instant.now().toString(),
            actor);

    VariablePayPlan saved = plans.save(toSave);
    log.info(
        "Variable pay plan updated by {} — {} revenue={} GRR={}",
        actor,
        saved.fyLabel(),
        saved.revenueActualCr(),
        saved.grrActualPercent());
    return saved;
  }

  /** Writes the published policy into an empty store, so the console has something to edit. */
  @EventListener(ApplicationReadyEvent.class)
  void seed() {
    try {
      if (plans.findByPlanKey(NON_SALES).isPresent()) return;
      VariablePayPlan seeded = plans.save(published());
      log.info(
          "Seeded the {} variable pay plan from the published policy ({}). Revenue and GRR results"
              + " are unset — enter them in the admin console when HR declares them.",
          NON_SALES,
          seeded.policyVersion());
    } catch (RuntimeException e) {
      log.warn("Could not seed the variable pay plan — is MongoDB reachable? {}", e.toString());
    }
  }

  private static String blankTo(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private static <T> List<T> emptyTo(List<T> value, List<T> fallback) {
    return value == null || value.isEmpty() ? fallback : value;
  }

  private static BigDecimal bd(String v) {
    return new BigDecimal(v);
  }

  /**
   * Variable Pay Policy v2.0 (Non-Sales), as published for FY 2026-27.
   *
   * <p>Transcribed from the document rather than rounded or simplified. The four worked examples in
   * that PDF are the test for this: if a value here is wrong, at least one of them stops
   * reconciling.
   */
  public static VariablePayPlan published() {
    List<VariablePayPlan.Band> revenue =
        List.of(
            new VariablePayPlan.Band("Below Threshold", null, bd("405"), bd("0.00")),
            new VariablePayPlan.Band("Near Target", bd("405"), bd("413"), bd("0.70")),
            new VariablePayPlan.Band("Target", bd("413"), bd("420"), bd("1.00")),
            new VariablePayPlan.Band("Above Target", bd("420"), bd("430"), bd("1.10")),
            new VariablePayPlan.Band("Exceeds Target", bd("430"), null, bd("1.20")));

    List<VariablePayPlan.Band> grr =
        List.of(
            new VariablePayPlan.Band("Below Threshold", null, bd("85"), bd("0.00")),
            new VariablePayPlan.Band("Near Target", bd("85"), bd("86.5"), bd("0.70")),
            new VariablePayPlan.Band("Target", bd("86.5"), bd("87"), bd("1.00")),
            new VariablePayPlan.Band("Above Target", bd("87"), bd("87.5"), bd("1.10")),
            new VariablePayPlan.Band("Exceeds Target", bd("87.5"), null, bd("1.20")));

    // X0–X4, X5–X6, X7–X9, X10 and above in the policy; L-grades here, same ladder. The top band
    // runs to 99 rather than stopping at 10 so a future L11 is covered rather than unmatched.
    List<VariablePayPlan.GradeWeight> grades =
        List.of(
            new VariablePayPlan.GradeWeight(0, 4, bd("0"), bd("100")),
            new VariablePayPlan.GradeWeight(5, 6, bd("20"), bd("80")),
            new VariablePayPlan.GradeWeight(7, 9, bd("30"), bd("70")),
            new VariablePayPlan.GradeWeight(10, 99, bd("40"), bd("60")));

    List<VariablePayPlan.RatingPayout> ratings =
        List.of(
            new VariablePayPlan.RatingPayout(5, "Outstanding", bd("130")),
            new VariablePayPlan.RatingPayout(4, "Exceeds", bd("115")),
            new VariablePayPlan.RatingPayout(3, "Meets", bd("100")),
            new VariablePayPlan.RatingPayout(2, "Below", bd("0")),
            new VariablePayPlan.RatingPayout(1, "Poor", bd("0")));

    return new VariablePayPlan(
        null,
        NON_SALES,
        "FY 2026-27",
        "2.0",
        // Undeclared. See the record's javadoc — a null result is the normal state for most of a
        // year, and is not the same as a result of zero.
        null,
        null,
        revenue,
        grr,
        bd("50"),
        bd("50"),
        grades,
        ratings,
        // Sales has its own policy with different mechanics. Configurable because the boundary is
        // an organisational fact, not a law: a vertical renamed or a function moved out of scope
        // should be a console edit rather than a release.
        List.of("Sales"),
        Instant.now().toString(),
        "system:published-policy");
  }
}
