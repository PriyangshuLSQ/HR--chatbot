package com.leadsquared.hr.knowledge.payroll;

import com.leadsquared.hr.knowledge.model.FunctionPayPlan;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Answers questions about the three revenue-function pay policies, for anybody who asks.
 *
 * <p><b>Why this is not gated on the asker's own function.</b> "Suppose I'm a sales employee in the
 * US — how would my variable pay be calculated?" is a question about a published policy, worked
 * through with figures the asker invents. It discloses nothing about anybody: no record is read,
 * no target is looked up, and the numbers come from the question. An employee in Operations is as
 * entitled to understand the Sales plan as to read the leave policy, and refusing them would be
 * treating a policy document as if it were personal data.
 *
 * <p>The privacy boundary is untouched and sits where it always did — {@code EmployeeDataService}
 * can only ever load the caller's own record, and {@code CrossEmployeeGuard} still runs first. This
 * class reads no employee data at all, so there is nothing here for a question about a colleague to
 * reach.
 *
 * <p>What it emits is either a computed payout, when the question carries enough figures, or the
 * role's own component table so the model can explain the mechanism and ask for exactly what is
 * missing. It never asks twice and never guesses a figure.
 */
@Service
public class FunctionPayAdvisor {

  /** Names a covered function, which is what makes a question this class's business. */
  private static final Pattern FUNCTION_WORDS =
      Pattern.compile(
          "\\b(sales|kam|key account|account management|partnership|partner|presales|pre-sales"
              // `ps` bare, because that is how people write it — "PS manager India". Without it the
              // question named no function this class recognised, produced no context, and fell
              // through to policy retrieval, which answered with nothing at all.
              + "|consulting|solutions consulting|sdr|sales development|professional services"
              + "|\\bps\\b|ps team"
              + "|csm|customer success|client partner|\\bcp\\b)\\b",
          Pattern.CASE_INSENSITIVE);

  /** Names the subject — variable pay, incentives, or one of the metrics these policies pay on. */
  private static final Pattern PAY_WORDS =
      Pattern.compile(
          "\\b(variable pay|variable|incentive|kicker|payout|commission|quota"
              + "|mrr|arr|grr|nrr|utilisation|utilization|pipeline|conversion|churn|slab)\\b",
          Pattern.CASE_INSENSITIVE);

  /**
   * The US geography, and deliberately not the word "us".
   *
   * <p>A bare {@code \\bus\\b} matches the pronoun. "Can you tell us how variable pay works" or
   * "what does the policy give us" then selected the US plan for an India employee — the wrong
   * slab table, presented with the same confidence as the right one. Every alternative here is
   * unambiguously a place, and the bare form is admitted only where a neighbouring word settles
   * it ("US team", "in the US", "US employee").
   */
  private static final Pattern US_WORDS =
      Pattern.compile(
          "\\b(u\\.s\\.a?\\.?|usa|united states|america|american)\\b"
              + "|\\bus\\s+(?:team|employee|employees|plan|policy|sales|role|roles|geo|entity|org)\\b"
              + "|\\b(?:in|of|for|from|the)\\s+us\\b",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern FUNCTION_HEAD =
      Pattern.compile("\\b(function head|fh|head of|business head)\\b", Pattern.CASE_INSENSITIVE);

  private static final Pattern MANAGER =
      Pattern.compile("\\b(team manager|manager|mgr|lead)\\b", Pattern.CASE_INSENSITIVE);

  /**
   * A money amount naming variable pay — "my VP is $120,000", "variable pay of 2 lakh".
   *
   * <p>Anchored to a variable-pay phrase for the same reason the Non-Sales extractor is: these
   * questions are full of numbers that are not the VP, and reading an MRR target as somebody's
   * variable pay would produce a confident answer to a question nobody asked.
   */
  private static final Pattern VP_AMOUNT =
      Pattern.compile(
          "\\b(?:variable(?:\\s+pay)?|vp|target\\s+vp|annual\\s+vp)\\b[^0-9?]{0,20}"
              + "(?:\\$|rs\\.?|inr|₹)?\\s*(\\d{1,3}(?:,\\d{2,3})*(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)"
              + "\\s*(lakhs?|lacs?|crores?|cr|k|m|million)?\\b",
          Pattern.CASE_INSENSITIVE);

  /** A percentage of target achieved. */
  private static final Pattern ACHIEVEMENT =
      Pattern.compile(
          "(\\d{1,3}(?:\\.\\d{1,2})?)\\s*(?:%|percent|per cent)\\s*(?:of\\s+)?(?:the\\s+)?"
              + "(?:annual\\s+|yearly\\s+|ytd\\s+)?(?:target|quota|achievement)?",
          Pattern.CASE_INSENSITIVE);

  /** A variance in points against a named retention metric — "GRR is 2% above target". */
  private static final Pattern VARIANCE =
      Pattern.compile(
          "\\b(grr|nrr|margin)\\b[^.?]{0,40}?(-?\\d{1,2}(?:\\.\\d{1,2})?)\\s*(?:%|points?|pts?)?"
              + "\\s*(above|below|under|over)?",
          Pattern.CASE_INSENSITIVE);

  /**
   * A request to be shown the mechanism with numbers rather than to have one's own worked out.
   *
   * <p>"Take some sample values and calculate" is how somebody asks to be taught, and the policies
   * themselves answer it that way — every section carries worked examples in shaded boxes. Refusing
   * it with "I need your actual figures" is wrong twice over: nothing here needs anybody's actual
   * figures, and the person was not claiming the numbers were theirs.
   */
  private static final Pattern EXAMPLE_REQUEST =
      Pattern.compile(
          "\\b(sample|example|examples|illustrat\\w*|assume|assuming|suppose|hypothetical"
              + "|dummy|any (?:value|values|number|numbers)|some (?:value|values|number|numbers)"
              + "|walk me through|show me how|demonstrate|for instance"
              // "imagine" was missing, and it is how people actually open a hypothetical.
              // "Imagine I'm in sales, what would my payout be at 85%" got a request for the
              // figures it had just been told to invent — the role and the slab were resolved
              // correctly, and the one thing standing between that and a worked example was
              // this list not containing the word.
              + "|imagine|consider|pretending|let'?s say|lets say|say (?:i|i'm|i am)"
              + "|what if|for argument|ballpark|roughly what)\\b",
          Pattern.CASE_INSENSITIVE);

  private final FunctionPayCalculator calculator;
  private final FunctionPayPlanService plans;

  public FunctionPayAdvisor(FunctionPayCalculator calculator, FunctionPayPlanService plans) {
    this.calculator = calculator;
    this.plans = plans;
  }

  /** Whether this question is about one of the three revenue-function policies. */
  /**
   * Whether this class has anything authoritative to say.
   *
   * <p>A function used to be mandatory, and that is what sent "I'm a US employee, how will the
   * variable pay calculation work" to policy retrieval instead: it names a geography and a
   * subject, but no function, so this returned false and the plan tables — which are right here,
   * loaded, and tested against HR's published examples — were never consulted. The answer came
   * back saying the role tables were unavailable while the calculator held them.
   *
   * <p>A named geography is enough on its own now. With no role resolved, {@code contextFor}
   * returns {@code describePlan}, which is the plan's own slabs and roles — the correct answer to
   * "how does this work", and better than anything retrieval can assemble from a 131-chunk PDF.
   */
  public boolean covers(String question) {
    if (question == null) return false;
    if (!PAY_WORDS.matcher(question).find()) return false;
    return FUNCTION_WORDS.matcher(question).find() || US_WORDS.matcher(question).find();
  }

  /**
   * Context for the model: a computed payout where the figures allow, otherwise the role's table.
   *
   * @param prior the asker's recent questions, so "and at 150%?" after naming a role still resolves
   */
  /**
   * Which plan a question is about, or null when it is not a pay question at all.
   *
   * <p>Exposed for retrieval, which uses it to stop one plan's policy being cited for another's
   * question. Same resolution as {@link #contextFor}, deliberately — two answers to "which plan
   * is this" would drift, and the one that decided the citations would be the one nobody tested.
   */
  public String planKeyFor(String question, List<String> prior) {
    String all = joined(question, prior);
    if (!covers(all)) return null;
    return US_WORDS.matcher(all).find() ? FunctionPayPlanService.US : indiaPlanFor(all);
  }

  public Optional<String> contextFor(String question, List<String> prior) {
    return contextFor(question, prior, null);
  }

  /**
   * @param ownVariableTarget the asker's own annual variable target, or null. Supplied only when
   *     they asked for their own figure to be used — a pure policy question still reaches this
   *     class with null, so "how does the US plan work" reads no record, which is the property the
   *     caller's comment protects.
   *     <p>A stated figure always wins over it: someone exploring "what if my VP were 2,00,000"
   *     means the number they typed, not the one on file.
   */
  public Optional<String> contextFor(
      String question, List<String> prior, BigDecimal ownVariableTarget) {
    String all = joined(question, prior);
    if (!covers(all)) return Optional.empty();

    String planKey = US_WORDS.matcher(all).find() ? FunctionPayPlanService.US : indiaPlanFor(all);
    FunctionPayPlan plan = plans.plan(planKey);
    if (plan == null) return Optional.empty();

    String roleKey = resolveRole(all, planKey);
    if (roleKey == null) return Optional.of(describePlan(plan));

    FunctionPayPlan.Role role = plan.role(roleKey);
    if (role == null) return Optional.of(describePlan(plan));

    // The record is a fallback, never an override.
    BigDecimal vp = statedVp(all).orElse(ownVariableTarget);
    boolean vpFromRecord = statedVp(all).isEmpty() && ownVariableTarget != null;
    Map<String, BigDecimal> inputs = statedInputs(all, role);

    // Asked for an illustration, supply one rather than asking again for figures the person has
    // already said they do not have. Anything they did give is kept; only the gaps are filled.
    boolean illustrative = false;
    if (EXAMPLE_REQUEST.matcher(all).find()) {
      if (vp == null) {
        vp = "USD".equalsIgnoreCase(plan.currency()) ? new BigDecimal("120000") : new BigDecimal("1000000");
        illustrative = true;
      }
      Map<String, BigDecimal> filled = new LinkedHashMap<>(inputs);
      for (FunctionPayPlan.Component c : role.components()) {
        String key = c.name().toLowerCase(Locale.ROOT);
        boolean have = filled.containsKey(key) || filled.containsKey("*");
        if (have) continue;
        FunctionPayPlan.Slab slab = plan.slab(c.slabKey());
        filled.put(key, illustrativeValue(slab));
        illustrative = true;
      }
      inputs = filled;
    }

    if (vp == null || inputs.isEmpty()) {
      return Optional.of(describeRole(plan, role, vp, inputs));
    }

    // Copied into finals: the two are reassigned above when illustrative figures are filled in,
    // and a lambda cannot capture a variable that changes.
    final boolean asExample = illustrative;
    final BigDecimal vpUsed = vp;
    final Map<String, BigDecimal> inputsUsed = inputs;

    return calculator
        .compute(planKey, roleKey, vpUsed, inputsUsed)
        .map(r -> calculator.asFactBlock(r, asExample))
        .map(block -> block + (vpFromRecord ? recordVpNote(vpUsed) : "") + hypotheticalNote())
        .or(() -> Optional.of(describeRole(plan, role, vpUsed, inputsUsed)));
  }

  /**
   * A figure to illustrate a component with.
   *
   * <p>Chosen to land in a band worth showing — an over-achievement on a multiplying ladder, so the
   * "factor x achievement" step is visible, and a small positive variance on a retention ladder,
   * where the interesting property is that the factor stands alone. A value that landed on 100%
   * everywhere would demonstrate nothing.
   */
  private static BigDecimal illustrativeValue(FunctionPayPlan.Slab slab) {
    if (slab == null) return new BigDecimal("100");
    return switch (slab.kind()) {
      case VARIANCE_POINTS -> new BigDecimal("2");
      case ACHIEVEMENT_MULTIPLIES -> new BigDecimal("150");
      // Banded ladders read all sorts of things — utilisation, conversion, churn, hours against
      // budget — so take the mid-point of a band that pays, from the ladder itself.
      case ACHIEVEMENT_BANDED -> midOfAPayingBand(slab);
    };
  }

  private static BigDecimal midOfAPayingBand(FunctionPayPlan.Slab slab) {
    for (FunctionPayPlan.Band b : slab.bands()) {
      if (b.factorPercent().signum() <= 0) continue;
      if (b.fromExclusive() != null && b.toInclusive() != null) {
        return b.fromExclusive().add(b.toInclusive()).divide(new BigDecimal("2"));
      }
      if (b.fromExclusive() != null) return b.fromExclusive().add(BigDecimal.ONE);
      if (b.toInclusive() != null) return b.toInclusive().subtract(BigDecimal.ONE);
    }
    return new BigDecimal("100");
  }

  /** India covers Sales-family and PS/CSM in two documents; pick by which function was named. */
  private static String indiaPlanFor(String q) {
    String lower = q.toLowerCase(Locale.ROOT);
    boolean psCsm =
        lower.contains("professional services")
            || lower.matches("(?s).*\\bps\\b.*")
            || lower.contains("ps team")
            || lower.contains("csm")
            || lower.contains("customer success")
            || lower.contains("utilisation")
            || lower.contains("utilization")
            || lower.contains("onboarding");
    return psCsm ? FunctionPayPlanService.PS_CSM : FunctionPayPlanService.INDIA_SALES;
  }

  /** Function plus level to one of the plan's roles. */
  private static String resolveRole(String q, String planKey) {
    String lower = q.toLowerCase(Locale.ROOT);
    boolean fh = FUNCTION_HEAD.matcher(q).find();
    boolean mgr = !fh && MANAGER.matcher(q).find();

    if (FunctionPayPlanService.US.equals(planKey)) {
      if (lower.contains("kam") || lower.contains("key account") || lower.contains("account management")) {
        return fh ? "us-kam-fh" : mgr ? "us-kam-manager" : "us-kam-ic";
      }
      if (lower.contains("presales") || lower.contains("pre-sales") || lower.contains("consulting")) {
        return mgr || fh ? "us-presales-manager" : "us-presales-ic";
      }
      // Order matters: the dual role names both sales and partnership, so it is tested before
      // either on its own or it can never be reached.
      if (lower.contains("partnership") && lower.contains("sales")) return "us-sales-partnership-tm";
      if (lower.contains("partnership") || lower.contains("partner")) return "us-partnership-ic";
      if (lower.contains("sales")) return fh ? "us-sales-fh" : "us-sales-ic";
      return null;
    }

    if (FunctionPayPlanService.PS_CSM.equals(planKey)) {
      if (lower.contains("csm") || lower.contains("customer success")) {
        return fh ? "csm-fh" : mgr ? "csm-manager" : "csm-ic";
      }
      return fh ? "ps-fh" : mgr ? "ps-manager" : "ps-ic";
    }

    if (lower.contains("sdr") || lower.contains("sales development")) return "in-sdr-ic";
    if (lower.contains("presales") || lower.contains("pre-sales") || lower.contains("solutions consulting")) {
      return "in-presales-ic";
    }
    if (lower.contains("partnership")) return "in-partnership-ic";
    if ((lower.contains("client partner") || lower.contains(" cp")) && lower.contains("sales")) {
      return "in-sales-cp-ic";
    }
    if (lower.contains("client partner") || lower.contains(" cp")) return "in-cp-ic";
    if (lower.contains("sales")) return fh ? "in-sales-fh" : mgr ? "in-sales-manager" : "in-sales-ic";
    return null;
  }

  private static Optional<BigDecimal> statedVp(String q) {
    Matcher m = VP_AMOUNT.matcher(q);
    if (!m.find()) return Optional.empty();
    try {
      BigDecimal amount = new BigDecimal(m.group(1).replace(",", ""));
      String unit = m.group(2);
      if (unit != null) {
        String u = unit.toLowerCase(Locale.ROOT);
        if (u.startsWith("l")) amount = amount.multiply(new BigDecimal("100000"));
        else if (u.startsWith("cr")) amount = amount.multiply(new BigDecimal("10000000"));
        else if (u.startsWith("k")) amount = amount.multiply(new BigDecimal("1000"));
        else if (u.startsWith("m")) amount = amount.multiply(new BigDecimal("1000000"));
      }
      // Under a thousand is a misparse — a percentage or a level that happened to sit beside the
      // phrase, not somebody's annual variable pay.
      return amount.compareTo(new BigDecimal("1000")) < 0 ? Optional.empty() : Optional.of(amount);
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  /**
   * Figures for the role's components.
   *
   * <p>A retention metric named with its variance wins over a bare percentage, because "GRR 2%
   * above target" and "150% of target" are different readings of the same-looking number and only
   * one of them belongs on a variance ladder.
   */
  private static Map<String, BigDecimal> statedInputs(String q, FunctionPayPlan.Role role) {
    Map<String, BigDecimal> out = new LinkedHashMap<>();

    Matcher v = VARIANCE.matcher(q);
    while (v.find()) {
      String metric = v.group(1).toLowerCase(Locale.ROOT);
      BigDecimal value = new BigDecimal(v.group(2));
      String direction = v.group(3);
      if (direction != null && direction.toLowerCase(Locale.ROOT).startsWith("b")) {
        value = value.negate();
      }
      if (direction != null
          && (direction.equalsIgnoreCase("under"))) {
        value = value.abs().negate();
      }
      out.put(metric, value);
    }

    // A single achievement percentage answers a single-component role outright.
    Matcher a = ACHIEVEMENT.matcher(q);
    List<BigDecimal> percents = new ArrayList<>();
    while (a.find()) percents.add(new BigDecimal(a.group(1)));
    if (!percents.isEmpty() && role.components().size() == 1 && out.isEmpty()) {
      out.put("*", percents.get(0));
    } else if (!percents.isEmpty() && out.isEmpty()) {
      // Several components and one figure: apply it to the first, and the rest come back as
      // missing so the employee is asked for exactly those.
      out.put(role.components().get(0).name().toLowerCase(Locale.ROOT), percents.get(0));
    }
    return out;
  }

  /** The plan's roles, when the question named a policy but no role within it. */
  private static String describePlan(FunctionPayPlan plan) {
    StringBuilder out = new StringBuilder();
    out.append("\n").append(plan.label()).append(" v").append(plan.policyVersion()).append(", ")
        .append(plan.fyLabel()).append(" — amounts in ").append(plan.currency()).append(".\n");
    out.append("Roles covered, with their variable pay components:\n");
    for (FunctionPayPlan.Role r : plan.roles()) {
      out.append("- ").append(r.function()).append(" — ").append(r.level()).append(": ");
      List<String> parts = new ArrayList<>();
      for (FunctionPayPlan.Component c : r.components()) {
        parts.add(c.name() + " " + trim(c.weightPercent()) + "%");
      }
      out.append(String.join(" · ", parts)).append("\n");
    }
    out.append(
        "Ask which role applies, then give the annual variable pay and the achievement figures to"
            + " compute a payout. These are published policy tables, not anybody's record.\n");
    return out.toString();
  }

  /** One role's mechanism, and precisely what is still needed to compute it. */
  private static String describeRole(
      FunctionPayPlan plan, FunctionPayPlan.Role role, BigDecimal vp, Map<String, BigDecimal> inputs) {

    StringBuilder out = new StringBuilder();
    out.append("\n").append(plan.label()).append(" v").append(plan.policyVersion()).append(", ")
        .append(plan.fyLabel()).append(" — ").append(role.function()).append(" — ")
        .append(role.level()).append(". Amounts in ").append(plan.currency()).append(".\n");

    out.append("Variable pay components (these always total 100%):\n");
    for (FunctionPayPlan.Component c : role.components()) {
      FunctionPayPlan.Slab slab = plan.slab(c.slabKey());
      out.append("- ").append(c.name()).append(" — ").append(trim(c.weightPercent())).append("%, ")
          .append(c.frequency()).append(", scored on: ").append(slab == null ? c.slabKey() : slab.label())
          .append("\n");
      if (slab != null) {
        for (FunctionPayPlan.Band b : slab.bands()) {
          out.append("    ").append(b.name()).append(" -> ").append(trim(b.factorPercent())).append("%\n");
        }
        if (slab.kind() == FunctionPayPlan.SlabKind.ACHIEVEMENT_MULTIPLIES) {
          out.append("    (this ladder multiplies: payout = factor x achievement% x component)\n");
        } else if (slab.kind() == FunctionPayPlan.SlabKind.VARIANCE_POINTS) {
          out.append("    (read as variance in percentage POINTS from target; factor applies alone)\n");
        } else {
          out.append("    (the factor applies to the component alone, not multiplied by the input)\n");
        }
      }
      if (c.note() != null && !c.note().isBlank()) out.append("    condition: ").append(c.note()).append("\n");
    }

    if (role.kickers() != null && !role.kickers().isEmpty()) {
      out.append("Incentive kickers, paid on top of variable pay:\n");
      for (FunctionPayPlan.Kicker k : role.kickers()) {
        out.append("- ").append(k.name()).append(": ").append(trim(k.ratePercent())).append("% of ")
            .append(k.basis()).append(" (").append(k.frequency()).append(")\n");
      }
    }

    List<String> needed = new ArrayList<>();
    if (vp == null) needed.add("the annual variable pay amount");
    for (FunctionPayPlan.Component c : role.components()) {
      if (!inputs.containsKey(c.name().toLowerCase(Locale.ROOT)) && !inputs.containsKey("*")) {
        needed.add(c.name());
      }
    }
    if (!needed.isEmpty()) {
      out.append("TO COMPUTE A FIGURE, still needed: ").append(String.join("; ", needed))
          .append(". Ask for all of them in ONE question, and do not attempt the arithmetic"
              + " yourself — state the mechanism and wait for the figures.\n");
    }
    out.append(hypotheticalNote());
    return out.toString();
  }

  /**
   * Says where the variable pay figure came from.
   *
   * <p>Named explicitly because the employee cannot otherwise tell, and the two sources mean
   * different things: a figure they typed is a what-if, a figure from their record is theirs. It
   * also stops the model claiming the whole payout was computed from their record when only the
   * VP amount was — the achievement is still whatever they said it was.
   */
  private static String recordVpNote(BigDecimal vp) {
    return "\n\nThe annual variable pay amount above ("
        + vp.stripTrailingZeros().toPlainString()
        + ") is the figure on the employee's own record. The achievement percentages are the ones"
        + " they supplied, not values read from any system.";
  }

  private static String hypotheticalNote() {
    return "This is the published policy applied to figures given in the question — it is not"
        + " anyone's record, and no employee's targets or achievement were read. Say so plainly,"
        + " and do not imply these are the asker's actual numbers unless they said they were.\n";
  }

  private static String joined(String question, List<String> prior) {
    if (prior == null || prior.isEmpty()) return question == null ? "" : question;
    return String.join(" ", prior) + " " + (question == null ? "" : question);
  }

  private static String trim(BigDecimal v) {
    return v == null ? "—" : v.stripTrailingZeros().toPlainString();
  }
}
