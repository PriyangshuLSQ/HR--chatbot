package com.leadsquared.hr.knowledge.payroll;

import com.leadsquared.hr.knowledge.model.FunctionPayPlan;
import com.leadsquared.hr.knowledge.model.FunctionPayPlan.Band;
import com.leadsquared.hr.knowledge.model.FunctionPayPlan.Component;
import com.leadsquared.hr.knowledge.model.FunctionPayPlan.Kicker;
import com.leadsquared.hr.knowledge.model.FunctionPayPlan.OrgValue;
import com.leadsquared.hr.knowledge.model.FunctionPayPlan.Role;
import com.leadsquared.hr.knowledge.model.FunctionPayPlan.Slab;
import com.leadsquared.hr.knowledge.model.FunctionPayPlan.SlabKind;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The three revenue-function policies as published, used to seed an empty store.
 *
 * <p>Transcribed from the PDFs rather than summarised: <i>Variable &amp; Incentive Pay Policy — US
 * Team — FY 2026–27 v4.0</i>, <i>Variable &amp; Incentive Pay Policy — FY 2026–27 v4.0</i> (India
 * Sales) and <i>Variable &amp; Incentive Pay Policy (PS &amp; CSM) — FY 2026–27 v4.0</i>. Each
 * policy prints its own worked examples with the arithmetic shown; those are reproduced as tests,
 * so a wrong figure here fails the build rather than reaching an employee.
 *
 * <p>Seeded once and then owned by whoever maintains it in the console — not re-asserted on boot.
 * A slab is a property of the published policy, but a threshold HR has revised mid-year is a fact
 * somebody entered, and overwriting it every restart would throw away the only copy.
 */
final class PublishedFunctionPlans {

  private PublishedFunctionPlans() {}

  private static BigDecimal bd(String v) {
    return new BigDecimal(v);
  }

  private static Band band(String name, String from, String to, String factor) {
    return new Band(name, from == null ? null : bd(from), to == null ? null : bd(to), bd(factor));
  }

  /**
   * The GRR/NRR ladder, which both geographies share in shape.
   *
   * <p>Read as variance in percentage points from target, and the lower bound of the 50% tier is
   * −1 for ICs and Managers against −0.5 for Function Heads. The tighter Function Head bound is
   * expressed as its own slab rather than as a per-level tweak, because a band table an employee
   * can be shown is worth more than a rule they have to apply themselves.
   */
  private static List<Band> retentionBands(String fiftyTierUpper) {
    return List.of(
        band("Below floor", null, "-2", "0"),
        band("50% tier", "-2", fiftyTierUpper, "50"),
        band("At target", fiftyTierUpper, "1", "100"),
        band("Above target", "1", "3", "120"),
        band("Well above target", "3", "5", "135"),
        band("Exceeds", "5", null, "150"));
  }

  // ---------------------------------------------------------------------------
  // US Team — FY 2026-27 v4.0, denominated in USD
  // ---------------------------------------------------------------------------

  static FunctionPayPlan us() {
    List<Slab> slabs =
        List.of(
            new Slab(
                "mrr-a",
                "MRR slab A — Sales ICs, Sales+Partnership TM, Partnership ICs, Presales",
                SlabKind.ACHIEVEMENT_MULTIPLIES,
                false,
                List.of(
                    band("0–40%", null, "40", "50"),
                    band(">40–80%", "40", "80", "100"),
                    band(">80–120%", "80", "120", "125"),
                    band(">120%", "120", null, "150"))),
            new Slab(
                "mrr-b",
                "MRR slab B — Sales Function Head",
                SlabKind.ACHIEVEMENT_MULTIPLIES,
                false,
                List.of(
                    band("0–40%", null, "40", "50"),
                    band(">40–90%", "40", "90", "100"),
                    band(">90–120%", "90", "120", "120"),
                    band(">120%", "120", null, "150"))),
            new Slab("grr-ic", "GRR — KAM IC & Manager", SlabKind.VARIANCE_POINTS, false, retentionBands("-1")),
            new Slab("grr-fh", "GRR — KAM Function Head", SlabKind.VARIANCE_POINTS, false, retentionBands("-0.5")),
            new Slab("nrr-ic", "NRR — KAM IC & Manager", SlabKind.VARIANCE_POINTS, false, retentionBands("-1")),
            new Slab("nrr-fh", "NRR — KAM FH & Sales FH", SlabKind.VARIANCE_POINTS, false, retentionBands("-0.5")),
            new Slab(
                "conversion",
                "Pipeline to Closure Conversion — Presales / Consulting",
                SlabKind.ACHIEVEMENT_BANDED,
                false,
                List.of(
                    band("Below gate", null, "25", "0"),
                    band("At or above 25%", "25", null, "100"))),
            new Slab(
                "prorated",
                "Prorated on achievement — no slab",
                SlabKind.ACHIEVEMENT_MULTIPLIES,
                false,
                List.of(band("Prorated", null, null, "100"))));

    List<Role> roles =
        List.of(
            new Role(
                "us-sales-ic",
                "Sales (New Business) — US",
                "Executive (IC)",
                List.of(new Component("Net New MRR target", bd("100"), "mrr-a", "Monthly (YTD)", null)),
                List.of(
                    new Kicker("High-Value Deal — Large", bd("15"), "MRR of the qualifying transaction", "Monthly"),
                    new Kicker("Yearly Prepaid Deal", bd("10"), "New MRR", "Monthly"),
                    new Kicker("Multi-year — 2-yr lock-in", bd("7.5"), "initial MRR per year", "Monthly"),
                    new Kicker("Multi-year — 3-yr+ lock-in", bd("10"), "initial MRR per year", "Monthly"),
                    new Kicker("Net Expansion (FY26 accounts)", bd("12.5"), "Net expansion MRR", "Yearly"),
                    new Kicker("Service Billing (OTS / new DRM)", bd("5"), "collection", "Monthly"),
                    new Kicker("H1 Accelerated Growth", bd("10"), "annual VP", "Half-yearly"))),
            new Role(
                "us-sales-fh",
                "Sales (New Business) — US",
                "Function Head",
                List.of(
                    new Component("Net New MRR target", bd("80"), "mrr-b", "Quarterly (YTD)", null),
                    new Component("NRR", bd("20"), "nrr-fh", "Annually", null)),
                List.of(
                    new Kicker("High-Value Deal — Large", bd("5"), "MRR of the qualifying transaction", "Monthly"),
                    new Kicker("Yearly Prepaid Deal", bd("5"), "New MRR", "Monthly"),
                    new Kicker("Multi-year — 2-yr lock-in", bd("5"), "initial MRR per year", "Monthly"),
                    new Kicker("Multi-year — 3-yr+ lock-in", bd("7.5"), "initial MRR per year", "Monthly"),
                    new Kicker("Service Billing (OTS / new DRM)", bd("2"), "collection", "Monthly"),
                    new Kicker("H1 Accelerated Growth", bd("10"), "annual VP", "Half-yearly"))),
            new Role(
                "us-sales-partnership-tm",
                "Sales + Partnership (Team Manager) — US",
                "Team Manager",
                List.of(
                    new Component("MRR through Enterprise deals", bd("45"), "mrr-a", "Quarterly (YTD)", null),
                    new Component("MRR through New industry traction", bd("45"), "mrr-a", "Quarterly (YTD)", null),
                    new Component(
                        "Partner pipeline origination",
                        bd("10"),
                        "mrr-a",
                        "Quarterly (YTD)",
                        "GATE: paid only on pipeline originated solely through the partner channel")),
                List.of(
                    new Kicker("High-Value Deal — Large", bd("7.5"), "MRR of the qualifying transaction", "Monthly"),
                    new Kicker("Yearly Prepaid Deal", bd("5"), "New MRR", "Monthly"),
                    new Kicker("Multi-year — 2-yr lock-in", bd("7.5"), "initial MRR per year", "Monthly"),
                    new Kicker("Multi-year — 3-yr+ lock-in", bd("10"), "initial MRR per year", "Monthly"),
                    new Kicker("Service Billing (OTS / new DRM)", bd("3"), "collection", "Monthly"),
                    new Kicker("H1 Accelerated Growth", bd("10"), "annual VP", "Half-yearly"))),
            new Role(
                "us-kam-ic",
                "Key Account Management (KAM) — US",
                "Executive (IC)",
                List.of(
                    new Component("GRR target", bd("50"), "grr-ic", "Annually", null),
                    new Component("NRR target", bd("50"), "nrr-ic", "Annually", null)),
                List.of(
                    new Kicker("High-Value Deal — Large", bd("7.5"), "MRR (>$5K Edu / >$2K Healthcare)", "Monthly"),
                    new Kicker("Improvement in Yearly Prepaid Billing Net MRR", bd("10"), "MRR", "Yearly"),
                    new Kicker("New Business Health Incentive", bd("7.5"), "MRR", "Yearly"),
                    new Kicker("Multi-year — 2-yr lock-in", bd("5"), "initial MRR per year at renewal", "Monthly"),
                    new Kicker("Multi-year — 3-yr+ lock-in", bd("7.5"), "initial MRR per year at renewal", "Monthly"),
                    new Kicker("Net Expansion (FY26 accounts)", bd("12.5"), "Net expansion MRR", "Yearly"),
                    new Kicker("Service Billing", bd("5"), "collection", "Monthly"),
                    new Kicker("Referral Bonus", bd("10"), "MRR", "Monthly"))),
            new Role(
                "us-kam-manager",
                "Key Account Management (KAM) — US",
                "Team Manager",
                List.of(
                    new Component("GRR target", bd("50"), "grr-ic", "Annually", null),
                    new Component("NRR target", bd("50"), "nrr-ic", "Annually", null)),
                List.of(
                    new Kicker("High-Value Deal — Large", bd("5"), "MRR (>$5K Edu / >$2K Healthcare)", "Monthly"),
                    new Kicker("Improvement in Yearly Prepaid Billing Net MRR", bd("5"), "MRR", "Yearly"),
                    new Kicker("New Business Health Incentive", bd("2.5"), "MRR", "Yearly"),
                    new Kicker("Multi-year — 2-yr lock-in", bd("3"), "initial MRR per year at renewal", "Monthly"),
                    new Kicker("Multi-year — 3-yr+ lock-in", bd("5"), "initial MRR per year at renewal", "Monthly"),
                    new Kicker("Service Billing", bd("3"), "collection", "Monthly"),
                    new Kicker("Referral Bonus", bd("10"), "MRR", "Monthly"))),
            new Role(
                "us-kam-fh",
                "Key Account Management (KAM) — US",
                "Function Head",
                List.of(
                    new Component("GRR target", bd("50"), "grr-fh", "Annually", null),
                    new Component("NRR target", bd("50"), "nrr-fh", "Annually", null)),
                List.of(
                    new Kicker("High-Value Deal — Large", bd("3"), "MRR (>$5K Edu / >$2K Healthcare)", "Monthly"),
                    new Kicker("Improvement in Yearly Prepaid Billing Net MRR", bd("5"), "MRR", "Yearly"),
                    new Kicker("New Business Health Incentive", bd("1.5"), "MRR", "Yearly"),
                    new Kicker("Multi-year — 3-yr+ lock-in", bd("3"), "initial MRR per year at renewal", "Monthly"),
                    new Kicker("Service Billing", bd("2"), "collection", "Monthly"),
                    new Kicker("Referral Bonus", bd("10"), "MRR", "Monthly"))),
            new Role(
                "us-partnership-ic",
                "Partnership — US",
                "Executive (IC)",
                List.of(
                    new Component(
                        "Partner pipeline origination target (MRR)",
                        bd("55"),
                        "mrr-a",
                        "Quarterly (YTD)",
                        "GATE: released only on partner-sourced pipeline"),
                    new Component(
                        "Partner sourced & influenced revenue (MRR)",
                        bd("35"),
                        "mrr-a",
                        "Quarterly (YTD)",
                        "Partner-sourced retires 100% of quota; partner-influenced retires 50%"),
                    new Component(
                        "Partner network addition (quantity)",
                        bd("10"),
                        "prorated",
                        "Yearly",
                        "Prorated on achievement against target — no slab")),
                List.of(new Kicker("H1 Accelerated Growth", bd("15"), "annual VP", "Half-yearly"))),
            new Role(
                "us-presales-ic",
                "Presales / Consulting — US",
                "Executive (IC)",
                List.of(
                    new Component("Net MRR target", bd("80"), "mrr-a", "Monthly (YTD)", null),
                    new Component(
                        "Pipeline to Closure Conversion",
                        bd("20"),
                        "conversion",
                        "Annually",
                        "Below 25% pays 0% unless Net MRR achievement exceeds 80%, which waives the gate and pays 100%")),
                List.of(
                    new Kicker("High-Value Deal — Large", bd("6"), "MRR of the qualifying transaction", "Monthly"),
                    new Kicker("H1 Accelerated Growth", bd("15"), "annual VP", "Half-yearly"))),
            new Role(
                "us-presales-manager",
                "Presales / Consulting — US",
                "Team Manager",
                List.of(
                    new Component("Net MRR target", bd("80"), "mrr-a", "Monthly (YTD)", null),
                    new Component(
                        "Pipeline to Closure Conversion",
                        bd("20"),
                        "conversion",
                        "Annually",
                        "Below 25% pays 0% unless Net MRR achievement exceeds 80%")),
                List.of(
                    new Kicker("High-Value Deal — Large", bd("4"), "MRR of the qualifying transaction", "Monthly"),
                    new Kicker("H1 Accelerated Growth", bd("15"), "annual VP", "Half-yearly"))));

    List<OrgValue> org =
        List.of(
            new OrgValue("hv.edu.threshold", "High-Value Deal threshold — Education", bd("10000"), "$ monthly MRR", "Above this, per transaction"),
            new OrgValue("hv.healthcare.threshold", "High-Value Deal threshold — Healthcare", bd("7500"), "$ monthly MRR", "Above this, per transaction"),
            new OrgValue("hv.kam.edu.threshold", "High-Value Deal threshold — KAM Education", bd("5000"), "$ monthly MRR", null),
            new OrgValue("hv.kam.healthcare.threshold", "High-Value Deal threshold — KAM Healthcare", bd("2000"), "$ monthly MRR", null),
            new OrgValue("floor.ots", "Service Billing floor — OTS", bd("40"), "$ per hour", "Below this, OTS does not qualify"),
            new OrgValue("floor.cr", "Service Billing floor — Change Requests", bd("62.5"), "$ per hour", null),
            new OrgValue("floor.drm", "Service Billing floor — new DRM", bd("62.5"), "$ per hour", "Existing DRM never qualifies"),
            new OrgValue("h1.threshold", "H1 Accelerated Growth threshold", bd("65"), "% of yearly target by end of H1", null),
            new OrgValue("multiyear.escalation.min", "Multi-year minimum YoY escalation", bd("3"), "%", "US deals; 2-year minimum non-cancellable term"),
            new OrgValue("conversion.gate", "Presales conversion gate", bd("25"), "%", "Below this pays 0% on that component"),
            new OrgValue("conversion.waiver", "Conversion gate waiver — MRR achievement", bd("80"), "%", "Above this the conversion gate is waived and the component pays 100%"),
            new OrgValue("invorto.tier1.ceiling", "Invorto tier 1 ceiling", bd("5500"), "$ cumulative annual MRR", "IC 50% / Sales Mgr 30% / Sales FH 10% / KAM Mgr & FH 15%"),
            new OrgValue("invorto.tier2.ceiling", "Invorto tier 2 ceiling", bd("11000"), "$ cumulative annual MRR", "IC 75% / Sales Mgr 40% / Sales FH 20% / KAM Mgr & FH 20%"));

    return new FunctionPayPlan(
        null,
        "us",
        "Variable & Incentive Pay — US Team",
        "FY 2026-27",
        "4.0",
        "USD",
        slabs,
        roles,
        org,
        // No document owned by default. A seeded plan cannot know the id of a file an admin
        // has not uploaded yet, and an empty list is neutral — nothing is demoted until the
        // mapping is set deliberately.
        List.of(),
        Instant.now().toString(),
        "system:published-policy");
  }

  // ---------------------------------------------------------------------------
  // India Sales — FY 2026-27 v4.0, denominated in INR
  // ---------------------------------------------------------------------------

  static FunctionPayPlan indiaSales() {
    List<Slab> slabs =
        List.of(
            new Slab(
                "mrr",
                "Standard MRR slab — Sales, Presales, Sales+CP and Partnership",
                SlabKind.ACHIEVEMENT_MULTIPLIES,
                false,
                List.of(
                    band("0–40%", null, "40", "50"),
                    band(">40–90%", "40", "90", "100"),
                    band(">90–120%", "90", "120", "120"),
                    band(">120%", "120", null, "150"))),
            new Slab("nrr-ic", "NRR — CP and Sales+CP, IC & Manager", SlabKind.VARIANCE_POINTS, true, retentionBands("-1")),
            new Slab("nrr-fh", "NRR — Function Head", SlabKind.VARIANCE_POINTS, true, retentionBands("-0.5")),
            new Slab(
                "service-sales",
                "Service Sales — Blended Hourly Rate",
                SlabKind.ACHIEVEMENT_BANDED,
                false,
                List.of(
                    band("≤80%", null, "80", "0"),
                    band(">80–90%", "80", "90", "75"),
                    band(">90–105%", "90", "105", "100"),
                    band(">105%", "105", null, "120"))),
            new Slab(
                "conversion",
                "Pipeline to Closure Conversion",
                SlabKind.ACHIEVEMENT_BANDED,
                false,
                List.of(
                    band("<25%", null, "25", "0"),
                    band("25–40%", "25", "40", "100"),
                    band(">40%", "40", null, "100"))));

    List<Role> roles =
        List.of(
            new Role(
                "in-sales-ic",
                "Sales (New Business) — India",
                "Executive (IC)",
                List.of(
                    new Component("Net New MRR target", bd("90"), "mrr", "Quarterly (YTD)", null),
                    new Component("Service Sales — Blended Hourly Rate", bd("10"), "service-sales", "Yearly",
                        "Where Service Sales achievement is at least 100%, this component pays the higher of its own factor and the Net New MRR factor")),
                List.of(
                    new Kicker("High-Value Deal — Large (> ₹15L)", bd("35"), "MRR", "Quarterly"),
                    new Kicker("High-Value Deal — Big (> ₹7.5L)", bd("25"), "MRR", "Quarterly"),
                    new Kicker("Yearly Prepaid Deal", bd("15"), "New MRR", "Quarterly"))),
            new Role(
                "in-sales-manager",
                "Sales (New Business) — India",
                "Team Manager",
                List.of(
                    new Component("Net New MRR target", bd("80"), "mrr", "Quarterly (YTD)", null),
                    new Component("Pipeline to Closure Conversion", bd("10"), "conversion", "Yearly",
                        "Below 25% pays 0% unless at least 80% of the Net New MRR target is met"),
                    new Component("Service Sales — Blended Hourly Rate", bd("10"), "service-sales", "Yearly", null)),
                List.of(
                    new Kicker("High-Value Deal — Large (> ₹15L)", bd("10"), "MRR", "Quarterly"),
                    new Kicker("High-Value Deal — Big (> ₹7.5L)", bd("10"), "MRR", "Quarterly"),
                    new Kicker("Yearly Prepaid Deal", bd("10"), "New MRR", "Quarterly"))),
            new Role(
                "in-sales-fh",
                "Sales (New Business) — India",
                "Function Head",
                List.of(
                    new Component("Net New MRR target", bd("80"), "mrr", "Quarterly (YTD)", null),
                    new Component("Pipeline to Closure Conversion", bd("10"), "conversion", "Yearly", null),
                    new Component("Service Sales — Blended Hourly Rate", bd("10"), "service-sales", "Yearly", null)),
                List.of(
                    new Kicker("High-Value Deal — Large (> ₹15L)", bd("10"), "MRR", "Quarterly"),
                    new Kicker("Yearly Prepaid Deal", bd("5"), "New MRR", "Quarterly"))),
            new Role(
                "in-sales-cp-ic",
                "Sales + Client Partner (CP) — India",
                "Executive (IC)",
                List.of(
                    new Component("Net New MRR target", bd("70"), "mrr", "Quarterly (YTD)", null),
                    new Component("NRR target (existing book)", bd("20"), "nrr-ic", "Yearly",
                        "Clubbed with Net New MRR by default; year-end Net New MRR below 75% reverses the clubbing and both are recomputed separately"),
                    new Component("Service Sales — Blended Hourly Rate", bd("10"), "service-sales", "Yearly", null)),
                List.of(
                    new Kicker("High-Value Deal — Large (> ₹15L)", bd("35"), "MRR", "Quarterly"),
                    new Kicker("High-Value Deal — Big (> ₹7.5L)", bd("25"), "MRR", "Quarterly"))),
            new Role(
                "in-cp-ic",
                "Client Partner (CP) — India",
                "Executive (IC)",
                List.of(new Component("NRR target", bd("100"), "nrr-ic", "Yearly",
                    "NRR targets are dynamic and change with the account set; the KAM tenant dashboard is authoritative")),
                List.of(
                    new Kicker("High-Value Deal — Large (> ₹15L)", bd("35"), "MRR", "Quarterly"),
                    new Kicker("High-Value Deal — Big (> ₹7.5L)", bd("25"), "MRR", "Quarterly"),
                    new Kicker("Improvement in Yearly Prepaid Billing Net MRR", bd("10"), "MRR", "Quarterly"),
                    new Kicker("Multi-year — 2-yr lock-in", bd("10"), "initial MRR of each year", "Quarterly"))),
            new Role(
                "in-presales-ic",
                "Presales / Solutions Consulting — India",
                "All levels",
                List.of(
                    new Component("Net MRR target", bd("80"), "mrr", "Quarterly (YTD)", null),
                    new Component("Pipeline to Closure Conversion", bd("20"), "conversion", "Yearly",
                        "Below 25% pays 0% unless at least 80% of the MRR target is met")),
                List.of()),
            new Role(
                "in-sdr-ic",
                "SDR (Sales Development) — India",
                "All levels",
                List.of(
                    new Component("Pipeline Origination target", bd("80"), "mrr", "Quarterly (YTD)",
                        "Organisation-level pipeline is attributed 90% to SDR and 10% to Partnership (India only)"),
                    new Component("Prepipe-to-Pipe Conversion", bd("20"), "conversion", "Yearly", null)),
                List.of()),
            new Role(
                "in-partnership-ic",
                "Partnership — India",
                "ICs & Managers",
                List.of(
                    new Component("Pipeline Origination target", bd("25"), "mrr", "Quarterly (YTD)",
                        "Organisation-level pipeline is attributed 10% to Partnership and 90% to SDR (India only)"),
                    new Component("MRR Origination target (partner-sourced only)", bd("75"), "mrr", "Quarterly (YTD)",
                        "Partner-sourced only: entered by the partner, or partner named in the Business Opportunity at closure")),
                List.of()));

    List<OrgValue> org =
        List.of(
            new OrgValue("hv.large.threshold", "High-Value Deal — Large threshold", bd("1500000"), "₹ MRR", "Above ₹15L"),
            new OrgValue("hv.big.threshold", "High-Value Deal — Big threshold", bd("750000"), "₹ MRR", "Above ₹7.5L"),
            new OrgValue("service.base.bfsi", "Service Sales base rate — BFSI", bd("13500"), "₹ per day", null),
            new OrgValue("service.base.ev.enterprise", "Service Sales base rate — EV Enterprise", bd("12500"), "₹ per day", null),
            new OrgValue("service.base.ev.midmarket", "Service Sales base rate — EV Mid-Market", bd("12500"), "₹ per day", null),
            new OrgValue("service.base.ev.smb", "Service Sales base rate — EV SMB", bd("10000"), "₹ per day", null),
            new OrgValue("service.base.me", "Service Sales base rate — ME", bd("12500"), "₹ per day", null),
            new OrgValue("conversion.gate", "Pipeline to Closure gate", bd("25"), "%", "Below this pays 0%"),
            new OrgValue("conversion.waiver", "Conversion gate waiver — MRR achievement", bd("80"), "%", "At or above this the gate is waived and the component pays 100%"),
            new OrgValue("clubbing.gate", "Sales + CP clubbing gate — year-end Net New MRR", bd("75"), "%", "Below this the clubbing reverses and components are recomputed separately"),
            new OrgValue("invorto.quota.retirement", "Invorto add-on quota retirement", bd("40"), "% of MRR quota", null),
            new OrgValue("bu.nrr.target.met", "BU NRR target met this year", null, "1 = yes, 0 = no", "The NRR 50% tier pays only if the BU target is also met, else 0%"));

    return new FunctionPayPlan(
        null,
        "india-sales",
        "Variable & Incentive Pay — Sales, CP, Presales, SDR & Partnership (India)",
        "FY 2026-27",
        "4.0",
        "INR",
        slabs,
        roles,
        org,
        // No document owned by default. A seeded plan cannot know the id of a file an admin
        // has not uploaded yet, and an empty list is neutral — nothing is demoted until the
        // mapping is set deliberately.
        List.of(),
        Instant.now().toString(),
        "system:published-policy");
  }

  // ---------------------------------------------------------------------------
  // PS & CSM — FY 2026-27 v4.0, denominated in INR
  // ---------------------------------------------------------------------------

  static FunctionPayPlan psCsm() {
    List<Slab> slabs =
        List.of(
            new Slab(
                "utilisation",
                "Billable Utilisation — PS all levels",
                SlabKind.ACHIEVEMENT_BANDED,
                false,
                List.of(
                    band("<60%", null, "60", "0"),
                    band("60–<65%", "60", "65", "50"),
                    band("65–<75%", "65", "75", "100"),
                    band("75–80%", "75", "80", "110"),
                    band(">80%", "80", null, "120"))),
            new Slab(
                "on-time",
                "On-time Onboarding Completion — PS Team Manager",
                SlabKind.ACHIEVEMENT_BANDED,
                false,
                List.of(
                    band("<80%", null, "80", "0"),
                    band("80–90%", "80", "90", "75"),
                    band(">90–95%", "90", "95", "100"),
                    band(">95%", "95", null, "120"))),
            new Slab(
                "budgeted-hours",
                "Budgeted-Hours Adherence — inverse: less effort pays more",
                SlabKind.ACHIEVEMENT_BANDED,
                false,
                List.of(
                    band("<90% of budget", null, "90", "125"),
                    band("90–<100%", "90", "100", "115"),
                    band("100–<110%", "100", "110", "100"),
                    band("110–120%", "110", "120", "80"),
                    band(">120–125%", "120", "125", "50"),
                    band(">125%", "125", null, "0"))),
            new Slab(
                "impl-churn",
                "Implementation Churn — PS Manager & Function Head",
                SlabKind.ACHIEVEMENT_BANDED,
                false,
                List.of(
                    band("0–2.5%", null, "2.5", "100"),
                    band(">2.5–5%", "2.5", "5", "50"),
                    band(">5%", "5", null, "0"))),
            new Slab(
                "ps-margin",
                "PS Margin variance vs plan — PS Function Head",
                SlabKind.VARIANCE_POINTS,
                false,
                List.of(
                    band("<−25%", null, "-25", "0"),
                    band("−25 to −15%", "-25", "-15", "50"),
                    band(">−15 to −5%", "-15", "-5", "75"),
                    band(">−5 to 5%", "-5", "5", "100"),
                    band(">5%", "5", null, "120"))),
            new Slab("grr-ic", "GRR — CSM IC & Manager", SlabKind.VARIANCE_POINTS, true, retentionBands("-1")),
            new Slab("grr-fh", "GRR — CSM Function Head", SlabKind.VARIANCE_POINTS, true, retentionBands("-0.5")));

    List<Role> roles =
        List.of(
            new Role(
                "ps-ic",
                "Professional Services (PS)",
                "Executive (IC)",
                List.of(new Component("Billable Utilisation of available time", bd("100"), "utilisation", "Quarterly (YTD)",
                    "Below 60% utilisation pays 0%")),
                List.of(new Kicker("Implementation Cycle Reduction", bd("25"), "Early MRR realised", "Quarterly"))),
            new Role(
                "ps-manager",
                "Professional Services (PS)",
                "Team Manager",
                List.of(
                    new Component("Billable Utilisation (team-aggregate)", bd("25"), "utilisation", "Quarterly (YTD)", null),
                    new Component("On-time Completion of new onboarding", bd("25"), "on-time", "Quarterly (YTD)", null),
                    new Component("All work delivered within budgeted hours", bd("25"), "budgeted-hours", "Quarterly (YTD)", null),
                    new Component("Churn during implementation (team-aggregate)", bd("25"), "impl-churn", "Yearly", null)),
                List.of(new Kicker("Implementation Cycle Reduction", bd("25"), "Early MRR realised", "Quarterly"))),
            new Role(
                "ps-fh",
                "Professional Services (PS)",
                "Function Head",
                List.of(
                    new Component("Billable Utilisation (BU-level)", bd("25"), "utilisation", "Yearly", null),
                    new Component("All work delivered within budgeted hours", bd("25"), "budgeted-hours", "Yearly", null),
                    new Component("Churn during implementation", bd("25"), "impl-churn", "Yearly", null),
                    new Component("PS Margin", bd("25"), "ps-margin", "Yearly", null)),
                List.of(new Kicker("Implementation Cycle Reduction", bd("25"), "Early MRR realised", "Quarterly"))),
            new Role(
                "csm-ic",
                "Customer Success Management (CSM) — India",
                "Executive (IC)",
                List.of(new Component("GRR target", bd("100"), "grr-ic", "Yearly",
                    "Settled only at year-end on the full-year result — the whole VP is at stake")),
                List.of(
                    new Kicker("New Business Health Incentive", bd("15"), "MRR", "Year-end"),
                    new Kicker("Referral Bonus", bd("10"), "MRR", "Quarterly"))),
            new Role(
                "csm-manager",
                "Customer Success Management (CSM) — India",
                "Team Manager",
                List.of(new Component("GRR target (team)", bd("100"), "grr-ic", "Yearly", null)),
                List.of(new Kicker("Referral Bonus", bd("10"), "MRR", "Quarterly"))),
            new Role(
                "csm-fh",
                "Customer Success Management (CSM) — India",
                "Function Head",
                List.of(new Component("GRR target (team)", bd("100"), "grr-fh", "Yearly", null)),
                List.of(new Kicker("Referral Bonus", bd("10"), "MRR", "Quarterly"))));

    List<OrgValue> org =
        List.of(
            new OrgValue("nps.gate", "Implementation NPS gate", bd("80"), "%", "Cycle Reduction incentive is forfeited below this"),
            new OrgValue("usage.threshold", "New Business Health usage threshold", bd("80"), "%", "Must be sustained all year; a drop at any quarterly review forfeits that account"),
            new OrgValue("referral.churn.window", "Referral Bonus recovery window", bd("6"), "months", "Recovered if the referred deal churns within this window"),
            new OrgValue("utilisation.floor", "Billable Utilisation floor", bd("60"), "%", "Below this the component pays 0%"),
            new OrgValue("bu.grr.target.met", "BU GRR target met this year", null, "1 = yes, 0 = no", "The GRR 50% tier pays only if the BU target is also met, else 0%"));

    return new FunctionPayPlan(
        null,
        "ps-csm",
        "Variable & Incentive Pay — Professional Services & Customer Success",
        "FY 2026-27",
        "4.0",
        "INR",
        slabs,
        roles,
        org,
        // No document owned by default. A seeded plan cannot know the id of a file an admin
        // has not uploaded yet, and an empty list is neutral — nothing is demoted until the
        // mapping is set deliberately.
        List.of(),
        Instant.now().toString(),
        "system:published-policy");
  }

  static List<FunctionPayPlan> all() {
    return List.of(us(), indiaSales(), psCsm());
  }
}
