package com.leadsquared.hr.knowledge.payroll;

import com.leadsquared.hr.knowledge.model.FunctionPayPlan;
import com.leadsquared.hr.knowledge.model.FunctionPayPlan.Band;
import com.leadsquared.hr.knowledge.model.FunctionPayPlan.Component;
import com.leadsquared.hr.knowledge.model.FunctionPayPlan.Role;
import com.leadsquared.hr.knowledge.model.FunctionPayPlan.Slab;
import com.leadsquared.hr.knowledge.model.FunctionPayPlan.SlabKind;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Computes a payout under the US, India Sales or PS &amp; CSM policies.
 *
 * <p>Same principle as {@link VariablePayCalculator}: the arithmetic is here and not in the model.
 * These policies compound it — a role can carry four components, each on a different ladder, and
 * the MRR ladders multiply by achievement <em>as well as</em> selecting a band, so 200% of target
 * pays 300% of the component. Asked to do that in prose a model will usually be right, and
 * "usually" is the wrong standard for a number somebody will compare against their payslip.
 *
 * <p>The inputs come from the employee, because they are nowhere else. These policies measure MRR,
 * GRR, NRR, utilisation and pipeline conversion against per-employee targets held in ACE, KAM
 * Connect and the Sales Ops dashboards, none of which is in any HR extract. So this answers "what
 * would I earn at these figures", which is the question somebody can actually ask today, rather
 * than pretending to know their achievement.
 */
@Service
public class FunctionPayCalculator {

  private static final BigDecimal HUNDRED = new BigDecimal("100");
  private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");

  private final FunctionPayPlanService plans;

  public FunctionPayCalculator(FunctionPayPlanService plans) {
    this.plans = plans;
  }

  /**
   * One component's outcome.
   *
   * @param inputValue what was fed to the ladder — an achievement percentage, or a variance in
   *     points
   * @param workings the arithmetic written out, in the form the policies use for their own worked
   *     examples
   */
  public record ComponentResult(
      String name,
      BigDecimal weightPercent,
      BigDecimal componentVp,
      BigDecimal inputValue,
      Band band,
      SlabKind kind,
      BigDecimal payout,
      String workings,
      String note) {}

  /** A whole role's outcome. */
  public record Result(
      FunctionPayPlan plan,
      Role role,
      BigDecimal totalVp,
      List<ComponentResult> components,
      List<String> missing,
      BigDecimal total) {

    public boolean complete() {
      return missing.isEmpty();
    }
  }

  public FunctionPayPlan plan(String planKey) {
    return plans.plan(planKey);
  }

  /**
   * Computes what a role earns at the figures supplied.
   *
   * @param inputs component name (lower-cased, or its slab key) to the figure for it. A component
   *     with no figure is reported in {@link Result#missing()} rather than assumed to be zero —
   *     the employee is then asked for exactly what is outstanding, once.
   */
  public Optional<Result> compute(
      String planKey, String roleKey, BigDecimal totalVp, Map<String, BigDecimal> inputs) {

    FunctionPayPlan plan = plans.plan(planKey);
    if (plan == null) return Optional.empty();
    Role role = plan.role(roleKey);
    if (role == null || totalVp == null || totalVp.signum() <= 0) return Optional.empty();

    List<ComponentResult> results = new ArrayList<>();
    List<String> missing = new ArrayList<>();
    BigDecimal total = BigDecimal.ZERO;

    for (Component c : role.components()) {
      BigDecimal componentVp =
          totalVp.multiply(c.weightPercent()).divide(HUNDRED, 2, RoundingMode.HALF_UP);
      BigDecimal value = lookup(inputs, c);

      if (value == null) {
        missing.add(c.name());
        results.add(
            new ComponentResult(
                c.name(), c.weightPercent(), componentVp, null, null, null, null, null, c.note()));
        continue;
      }

      Slab slab = plan.slab(c.slabKey());
      Band band = slab == null ? null : slab.bandFor(value);
      if (band == null) {
        missing.add(c.name() + " (no band matched " + trim(value) + ")");
        results.add(
            new ComponentResult(
                c.name(), c.weightPercent(), componentVp, value, null, null, null, null, c.note()));
        continue;
      }

      BigDecimal factor = band.factorPercent().divide(HUNDRED);
      BigDecimal payout;
      String workings;

      if (slab.kind() == SlabKind.ACHIEVEMENT_MULTIPLIES) {
        // The achievement multiplies as well as selecting the band. Both halves are in the
        // policies' own examples — 200% achievement in the 150% band pays 300% of the component —
        // and dropping either is out by a factor, not by a rounding.
        BigDecimal achievement = value.divide(HUNDRED);
        payout = factor.multiply(achievement).multiply(componentVp).setScale(2, RoundingMode.HALF_UP);
        workings =
            trim(band.factorPercent())
                + "% x "
                + trim(value)
                + "% x "
                + MONEY.format(componentVp)
                + " = "
                + MONEY.format(payout);
      } else {
        // Every other ladder heads its column "Payout (% of VP Component)" — the factor stands
        // alone. 30% conversion in the 100% band pays the whole component, not 30% of it.
        payout = factor.multiply(componentVp).setScale(2, RoundingMode.HALF_UP);
        String reading =
            slab.kind() == SlabKind.VARIANCE_POINTS
                ? "variance " + trim(value) + " points vs target"
                : trim(value) + "% -> " + band.name();
        workings =
            trim(band.factorPercent())
                + "% x "
                + MONEY.format(componentVp)
                + " = "
                + MONEY.format(payout)
                + "  ("
                + reading
                + ")";
      }

      total = total.add(payout);
      results.add(
          new ComponentResult(
              c.name(), c.weightPercent(), componentVp, value, band, slab.kind(), payout, workings, c.note()));
    }

    return Optional.of(
        new Result(plan, role, totalVp, results, missing, total.setScale(2, RoundingMode.HALF_UP)));
  }

  /**
   * The figure for a component.
   *
   * <p>Matched on the component name first, then on its slab key, so a caller can say "utilisation
   * 78" without reproducing "Billable Utilisation of available time (team-aggregate)" exactly.
   */
  private static BigDecimal lookup(Map<String, BigDecimal> inputs, Component c) {
    if (inputs == null || inputs.isEmpty()) return null;
    Map<String, BigDecimal> lower = new LinkedHashMap<>();
    inputs.forEach((k, v) -> lower.put(k.toLowerCase(java.util.Locale.ROOT).trim(), v));

    String name = c.name().toLowerCase(java.util.Locale.ROOT);
    if (lower.containsKey(name)) return lower.get(name);
    if (lower.containsKey(c.slabKey())) return lower.get(c.slabKey());

    // A single unlabelled figure answers a single-component role, which is the common case:
    // "I'm a Sales IC at 150% of target" names one number and the role has one component.
    if (lower.containsKey("*")) return lower.get("*");

    for (Map.Entry<String, BigDecimal> e : lower.entrySet()) {
      if (name.contains(e.getKey()) || e.getKey().contains(name)) return e.getValue();
    }
    return null;
  }

  /** The computed figures as lines of fact for the model to narrate, never to re-derive. */
  public String asFactBlock(Result r) {
    return asFactBlock(r, false);
  }

  /**
   * @param illustrative true when some figures were chosen to demonstrate the mechanism rather than
   *     supplied. Labelled emphatically: a worked example read back as somebody's payout is a
   *     wrong number about their pay, which is the one thing this class exists to prevent.
   */
  public String asFactBlock(Result r, boolean illustrative) {
    String unit = r.plan().currency();
    StringBuilder out = new StringBuilder();

    out.append("Variable pay computation (authoritative — do not recalculate):\n");
    out.append("- Policy: ").append(r.plan().label()).append(" v").append(r.plan().policyVersion())
        .append(", ").append(r.plan().fyLabel()).append("\n");
    out.append("- Role: ").append(r.role().function()).append(" — ").append(r.role().level()).append("\n");
    if (illustrative) {
      out.append(
          "- THESE ARE ILLUSTRATIVE FIGURES, chosen to demonstrate the mechanism. They are NOT the"
              + " employee's targets, achievement or pay. Say so in the first sentence, present it"
              + " as a worked example, and invite them to give their own figures for a real"
              + " calculation.\n");
    }
    out.append("- Annual variable pay used: ").append(unit).append(" ").append(MONEY.format(r.totalVp()))
        .append(illustrative ? "  (illustrative)" : "  (supplied by the employee)")
        .append(" — these policies measure targets held in the function's dashboard, which is not in"
            + " any HR record here\n");

    for (ComponentResult c : r.components()) {
      out.append("- ").append(c.name()).append(" (").append(trim(c.weightPercent())).append("% = ")
          .append(unit).append(" ").append(MONEY.format(c.componentVp())).append("): ");
      if (c.payout() == null) {
        out.append("NOT COMPUTED — no figure supplied for this component\n");
      } else {
        out.append(c.band().name()).append(" -> ").append(trim(c.band().factorPercent())).append("%\n");
        out.append("    ").append(c.workings()).append("\n");
      }
      if (c.note() != null && !c.note().isBlank()) {
        out.append("    condition: ").append(c.note()).append("\n");
      }
    }

    if (r.complete()) {
      out.append("- Total variable pay: ").append(unit).append(" ").append(MONEY.format(r.total())).append("\n");
    } else {
      out.append("- PARTIAL TOTAL so far: ").append(unit).append(" ").append(MONEY.format(r.total()))
          .append(" — this is NOT the full payout. Still needed: ")
          .append(String.join("; ", r.missing()))
          .append(". Ask for those figures in one question and say the total is incomplete.\n");
    }

    if (r.role().kickers() != null && !r.role().kickers().isEmpty()) {
      out.append("- Incentive kickers available to this role, paid ON TOP of the variable pay above"
          + " and not included in it:\n");
      for (FunctionPayPlan.Kicker k : r.role().kickers()) {
        out.append("    ").append(k.name()).append(": ").append(trim(k.ratePercent())).append("% of ")
            .append(k.basis()).append(" (").append(k.frequency()).append(")\n");
      }
    }

    out.append(
        "These figures follow the published slabs for the role. Variable pay is discretionary and"
            + " subject to the eligibility, gate and clawback rules in the policy — state that"
            + " alongside the number, and state that the inputs are the employee's own figures.\n");
    return out.toString();
  }

  private static String trim(BigDecimal v) {
    return v == null ? "—" : v.stripTrailingZeros().toPlainString();
  }
}
