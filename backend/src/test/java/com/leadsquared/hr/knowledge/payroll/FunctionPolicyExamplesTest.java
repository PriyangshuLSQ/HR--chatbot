package com.leadsquared.hr.knowledge.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.leadsquared.hr.knowledge.store.FunctionPayPlanRepository;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The worked examples printed in the three revenue-function policies.
 *
 * <p>Each policy shows its own arithmetic in shaded boxes. Those examples are the specification: a
 * change to a slab, a weighting or the order of operations that stops one of them reconciling is a
 * change that disagrees with the document employees are reading.
 *
 * <p>The MRR ladders are the ones worth watching. Achievement both selects the band and multiplies
 * the payout, so 200% of target pays 300% of the component — an easy thing to implement once and
 * be wrong about by a whole factor.
 */
class FunctionPolicyExamplesTest {

  private FunctionPayCalculator calc;

  @BeforeEach
  void setUp() {
    FunctionPayPlanRepository repo = Mockito.mock(FunctionPayPlanRepository.class);
    when(repo.findByPlanKey(Mockito.anyString())).thenReturn(Optional.empty());
    calc = new FunctionPayCalculator(new FunctionPayPlanService(repo));
  }

  private static Map<String, BigDecimal> one(String v) {
    return Map.of("*", new BigDecimal(v));
  }

  // ---- US ------------------------------------------------------------------

  @Test
  @DisplayName("US Sales IC: $120k VP, 150% of Net New MRR target -> $270,000")
  void usSalesIcOverAchievement() {
    var r = calc.compute("us", "us-sales-ic", new BigDecimal("120000"), one("150"));
    assertThat(r).isPresent();
    assertThat(r.get().total()).isEqualByComparingTo("270000");
    assertThat(r.get().complete()).isTrue();
  }

  @Test
  @DisplayName("US Sales IC: 60% of target -> $72,000 (the >40-80% band pays 100%)")
  void usSalesIcBelowTarget() {
    var r = calc.compute("us", "us-sales-ic", new BigDecimal("120000"), one("60"));
    assertThat(r.get().total()).isEqualByComparingTo("72000");
  }

  @Test
  @DisplayName("US Sales IC: 200% of target -> $360,000")
  void usSalesIcDoubleTarget() {
    var r = calc.compute("us", "us-sales-ic", new BigDecimal("120000"), one("200"));
    assertThat(r.get().total()).isEqualByComparingTo("360000");
  }

  @Test
  @DisplayName("US KAM IC: GRR +2 points and NRR -1.5 points on $80k -> $68,000")
  void usKamRetention() {
    var r =
        calc.compute(
            "us",
            "us-kam-ic",
            new BigDecimal("80000"),
            Map.of("grr target", new BigDecimal("2"), "nrr target", new BigDecimal("-1.5")));
    assertThat(r.get().total()).isEqualByComparingTo("68000");
  }

  @Test
  @DisplayName("US Sales+Partnership TM: 96% / 96% / 100% on $150k -> $180,750")
  void usTeamManager() {
    var r =
        calc.compute(
            "us",
            "us-sales-partnership-tm",
            new BigDecimal("150000"),
            Map.of(
                "mrr through enterprise deals", new BigDecimal("96"),
                "mrr through new industry traction", new BigDecimal("96"),
                "partner pipeline origination", new BigDecimal("100")));
    assertThat(r.get().total()).isEqualByComparingTo("180750");
  }

  @Test
  @DisplayName("US Presales IC: Net MRR 110% and conversion 30% on $70k -> $91,000")
  void usPresales() {
    var r =
        calc.compute(
            "us",
            "us-presales-ic",
            new BigDecimal("70000"),
            Map.of("net mrr target", new BigDecimal("110"), "pipeline to closure conversion", new BigDecimal("30")));
    assertThat(r.get().total()).isEqualByComparingTo("91000");
  }

  @Test
  @DisplayName("US Partnership IC: 90% / 90% / 80% on $60k -> $65,550")
  void usPartnership() {
    var r =
        calc.compute(
            "us",
            "us-partnership-ic",
            new BigDecimal("60000"),
            Map.of(
                "partner pipeline origination target (mrr)", new BigDecimal("90"),
                "partner sourced & influenced revenue (mrr)", new BigDecimal("90"),
                "partner network addition (quantity)", new BigDecimal("80")));
    assertThat(r.get().total()).isEqualByComparingTo("65550");
  }

  // ---- India Sales ---------------------------------------------------------

  @Test
  @DisplayName("India Sales IC: 200% on a Rs 9,00,000 Net New MRR component -> Rs 27,00,000")
  void indiaSalesIc() {
    // The policy's example is the component alone at 90% weight of Rs 10,00,000 total VP.
    var r =
        calc.compute(
            "india-sales",
            "in-sales-ic",
            new BigDecimal("1000000"),
            Map.of("net new mrr target", new BigDecimal("200"), "service sales — blended hourly rate", new BigDecimal("0")));
    var mrr = r.get().components().get(0);
    assertThat(mrr.componentVp()).isEqualByComparingTo("900000");
    assertThat(mrr.payout()).isEqualByComparingTo("2700000");
  }

  @Test
  @DisplayName("India CP IC: NRR +2 points on Rs 1,00,000 -> Rs 1,20,000")
  void indiaCpNrr() {
    var r = calc.compute("india-sales", "in-cp-ic", new BigDecimal("100000"), one("2"));
    assertThat(r.get().total()).isEqualByComparingTo("120000");
  }

  @Test
  @DisplayName("India CP IC: NRR -3 points is below the floor and pays nothing")
  void indiaCpNrrBelowFloor() {
    var r = calc.compute("india-sales", "in-cp-ic", new BigDecimal("100000"), one("-3"));
    assertThat(r.get().total()).isEqualByComparingTo("0");
  }

  // ---- PS & CSM ------------------------------------------------------------

  @Test
  @DisplayName("PS Manager: utilisation 78%, on-time 92%, budgeted-hours 95% -> Rs 1,62,500 of the quarter")
  void psManagerQuarter() {
    var r =
        calc.compute(
            "ps-csm",
            "ps-manager",
            new BigDecimal("200000"),
            Map.of(
                "billable utilisation (team-aggregate)", new BigDecimal("78"),
                "on-time completion of new onboarding", new BigDecimal("92"),
                "all work delivered within budgeted hours", new BigDecimal("95")));
    // Churn is settled yearly and is not paid this quarter — the policy's example excludes it, and
    // it comes back as missing rather than as zero.
    assertThat(r.get().total()).isEqualByComparingTo("162500");
    assertThat(r.get().missing()).hasSize(1);
    assertThat(r.get().complete()).isFalse();
  }

  @Test
  @DisplayName("CSM IC: GRR +2 points on Rs 6,00,000 -> Rs 7,20,000")
  void csmIc() {
    var r = calc.compute("ps-csm", "csm-ic", new BigDecimal("600000"), one("2"));
    assertThat(r.get().total()).isEqualByComparingTo("720000");
  }

  @Test
  @DisplayName("A question naming a function is recognised however it is abbreviated")
  void functionNamesAreRecognised() {
    FunctionPayPlanRepository repo = Mockito.mock(FunctionPayPlanRepository.class);
    when(repo.findByPlanKey(Mockito.anyString())).thenReturn(Optional.empty());
    FunctionPayPlanService svc = new FunctionPayPlanService(repo);
    FunctionPayAdvisor advisor = new FunctionPayAdvisor(new FunctionPayCalculator(svc), svc);

    // "PS manager" is how people write it. Requiring "professional services" produced no context
    // at all, and the question fell through to policy retrieval and was answered with nothing.
    assertThat(advisor.covers("PS manager India, quarterly VP 200000, utilisation 78%")).isTrue();
    assertThat(advisor.covers("suppose iam a sales employee working in US, variable pay")).isTrue();
    assertThat(advisor.covers("CSM in india with 600000 VP and GRR 2% above target")).isTrue();
    assertThat(advisor.covers("what is the leave policy")).isFalse();
    assertThat(advisor.covers("who is my manager")).isFalse();
  }

  @Test
  @DisplayName("An unsupplied component is reported, never assumed to be zero")
  void missingComponentIsReported() {
    var r = calc.compute("us", "us-kam-ic", new BigDecimal("80000"), Map.of("grr target", new BigDecimal("2")));
    assertThat(r.get().complete()).isFalse();
    assertThat(r.get().missing()).containsExactly("NRR target");
    assertThat(calc.asFactBlock(r.get())).contains("PARTIAL TOTAL");
  }
}
