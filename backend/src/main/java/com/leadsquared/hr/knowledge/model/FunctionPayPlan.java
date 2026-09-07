package com.leadsquared.hr.knowledge.model;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A revenue-function variable and incentive pay policy, as editable data.
 *
 * <p>Covers the three policies that pay on business metrics rather than on an appraisal rating:
 * the US team, India Sales, and PS &amp; CSM. The Non-Sales policy is deliberately <em>not</em>
 * modelled here — see {@link VariablePayPlan}. It computes from a company result and a performance
 * rating weighted by grade, which is a different shape entirely, and forcing both into one record
 * would make each of them harder to read than either is alone.
 *
 * <p><b>Why generic rather than three hand-written models.</b> These three differ in almost every
 * particular — components, slabs, kickers, currency, levels — but they are the same <i>kind</i> of
 * document: a role earns weighted components, each scored on a slab, with kickers on top. Encoding
 * that shape once means a fourth policy, or next year's revision, is data rather than a release.
 * The policies themselves change mid-year: v3.1 was a mid-year revision to v3.0.
 *
 * <p><b>What this cannot do, and why that is honest.</b> Everything here is organisation-level:
 * slabs, weightings, kicker rates, thresholds, base rates. The per-employee inputs these policies
 * need — MRR targets and achievement, GRR/NRR variance, billable utilisation, and the employee's
 * level — live in ACE, KAM Connect and the Sales Ops dashboards, and are in no HR extract. So a
 * payout under these policies is computed from figures the employee supplies, against the rules
 * stored here. When those dashboards become a data source, the same calculator takes the stored
 * figures instead; nothing here has to change.
 *
 * @param planKey which policy — {@code us}, {@code india-sales} or {@code ps-csm}
 * @param currency the policy's own unit. The US policy is denominated in dollars and the other two
 *     in rupees; quoting one in the other's symbol would be a wrong number about someone's pay.
 */
@Document(collection = "function_pay_plans")
public record FunctionPayPlan(
    @Id String id,
    @Indexed(unique = true) String planKey,
    String label,
    String fyLabel,
    String policyVersion,
    String currency,
    List<Slab> slabs,
    List<Role> roles,
    List<OrgValue> orgValues,
    /**
     * Knowledge-base document ids this plan is the authoritative version of.
     *
     * <p>Retrieval uses it to stop one plan's policy being cited for another's question. An India
     * Sales question was answered with six of eight extracts from the US policy, because that
     * document happens to contain sentences shaped exactly like the question ("Pays out example
     * (over-achievement): A Sales Executive has a total annual VP of $120,000…") while the India
     * Sales policy phrases the same rule differently. Similarity picked the wrong country and the
     * employee was shown its citations.
     *
     * <p>Recorded explicitly rather than inferred. Guessing geography from a filename works until
     * someone renames a file, and guessing from currency symbols in the text works until a policy
     * quotes a figure in the other currency — both are the kind of inference that has already
     * produced three separate bugs in this codebase. Empty means "owns no document", which is
     * neutral: unowned documents are never demoted.
     */
    List<String> sourceDocumentIds,
    String updatedAt,
    String updatedBy) {

  /**
   * How a slab reads the number it is given, and what it does with it.
   *
   * <p>The distinction between the first two is easy to miss and expensive to get wrong. The MRR
   * ladders head their column "Payout factor (multiplier on VP)" and their worked examples read
   * 150% x 200% x component — the achievement both selects the band and multiplies. Every other
   * ladder heads its column "Payout (% of VP Component)": 30% conversion in the 100% band pays the
   * whole component, not 30% of it. Treating them alike understated a Presales payout by $9,800
   * and a PS Manager's quarter by Rs 18,975.
   */
  public enum SlabKind {
    /** Achievement selects the band <em>and</em> multiplies: 200% in a 150% band pays 300%. */
    ACHIEVEMENT_MULTIPLIES,
    /** Achievement selects the band only; the factor is applied to the component alone. */
    ACHIEVEMENT_BANDED,
    /**
     * Bands over variance in percentage <em>points</em> from target, used by GRR, NRR and PS
     * margin. The factor stands alone, as with {@link #ACHIEVEMENT_BANDED}; what differs is how
     * the input reads — "+2 points above target", not "2% of target".
     */
    VARIANCE_POINTS
  }

  /**
   * A payout ladder.
   *
   * @param key referenced by {@link Component#slabKey()}
   * @param buTargetGates the 50% tier pays only if the business-unit target is also met — an India
   *     condition with no US equivalent. Stored per slab rather than assumed, because the same
   *     GRR ladder appears in both geographies with and without it.
   */
  public record Slab(String key, String label, SlabKind kind, boolean buTargetGates, List<Band> bands) {

    public Band bandFor(BigDecimal value) {
      if (value == null || bands == null) return null;
      return bands.stream().filter(b -> b.covers(value)).findFirst().orElse(null);
    }
  }

  /**
   * One rung.
   *
   * @param fromExclusive null on the open bottom band. Exclusive because the policies write their
   *     ladders as "&gt;40–80%" — 40 itself belongs to the band below.
   * @param toInclusive null on the open top band
   * @param factorPercent the payout factor, as a percentage: 125 means 125%
   */
  public record Band(String name, BigDecimal fromExclusive, BigDecimal toInclusive, BigDecimal factorPercent) {

    public boolean covers(BigDecimal value) {
      if (value == null) return false;
      if (fromExclusive != null && value.compareTo(fromExclusive) <= 0) return false;
      return toInclusive == null || value.compareTo(toInclusive) <= 0;
    }
  }

  /**
   * A function at a level — "US Sales, Executive" — and what it earns.
   *
   * <p>Keyed by function <em>and</em> level because the level decides the weightings and every
   * kicker rate. That is the axis these policies turn on, and it is not the grade the Non-Sales
   * policy uses: an L5 tells you nothing about whether somebody is an IC or a Function Head.
   */
  public record Role(
      String key, String function, String level, List<Component> components, List<Kicker> kickers) {}

  /**
   * A weighted KPI.
   *
   * @param weightPercent components within a role always total 100
   * @param slabKey which ladder scores it
   * @param note gates and conditions the calculator cannot evaluate — a partner-sourced pipeline
   *     requirement, an hourly floor — carried through to the answer so the employee is told what
   *     could still zero it
   */
  public record Component(
      String name, BigDecimal weightPercent, String slabKey, String frequency, String note) {}

  /**
   * Pay over and above variable pay.
   *
   * @param ratePercent applied to {@code basis}, not to variable pay, except where the basis says
   *     otherwise — the H1 accelerator is a flat percentage of VP
   */
  public record Kicker(String name, BigDecimal ratePercent, String basis, String frequency) {}

  /**
   * A number HR maintains: a threshold, an hourly floor, a base rate, a target-met flag.
   *
   * <p>Free-form on purpose. These are the values that differ per team and change per year, and a
   * typed field per threshold would mean a schema change every time a policy adds one.
   *
   * @param value null where HR has not declared it yet — not the same as zero
   */
  public record OrgValue(String key, String label, BigDecimal value, String unit, String note) {}

  public Role role(String roleKey) {
    if (roles == null || roleKey == null) return null;
    return roles.stream().filter(r -> roleKey.equalsIgnoreCase(r.key())).findFirst().orElse(null);
  }

  public Slab slab(String slabKey) {
    if (slabs == null || slabKey == null) return null;
    return slabs.stream().filter(s -> slabKey.equalsIgnoreCase(s.key())).findFirst().orElse(null);
  }

  public OrgValue orgValue(String key) {
    if (orgValues == null || key == null) return null;
    return orgValues.stream().filter(v -> key.equalsIgnoreCase(v.key())).findFirst().orElse(null);
  }
}
