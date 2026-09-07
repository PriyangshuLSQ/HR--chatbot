package com.leadsquared.hr.knowledge.payroll;

import com.leadsquared.hr.knowledge.model.FunctionPayPlan;
import com.leadsquared.hr.knowledge.store.FunctionPayPlanRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Loads and edits the three revenue-function pay policies.
 *
 * <p>Seeded once from the published documents, then owned by whoever maintains them in the console.
 * Seeded rather than re-asserted every boot, for the same reason the Non-Sales plan is: a slab is a
 * property of the published policy, but a threshold HR revised mid-year is a fact somebody entered,
 * and overwriting it on restart would discard the only copy.
 */
@Service
public class FunctionPayPlanService {

  private static final Logger log = LoggerFactory.getLogger(FunctionPayPlanService.class);

  public static final String US = "us";
  public static final String INDIA_SALES = "india-sales";
  public static final String PS_CSM = "ps-csm";

  private final FunctionPayPlanRepository plans;

  public FunctionPayPlanService(FunctionPayPlanRepository plans) {
    this.plans = plans;
  }

  /**
   * One plan, falling back to the published defaults when the store has nothing or is unreachable.
   *
   * <p>Degrading to the published document beats refusing every question about it: the seeded
   * values are the policy. What it cannot invent is anything HR has since revised.
   */
  public FunctionPayPlan plan(String planKey) {
    try {
      Optional<FunctionPayPlan> stored = plans.findByPlanKey(planKey);
      if (stored.isPresent()) return stored.get();
    } catch (RuntimeException e) {
      log.warn("Could not read the {} pay plan, using published defaults: {}", planKey, e.toString());
    }
    return PublishedFunctionPlans.all().stream()
        .filter(p -> p.planKey().equals(planKey))
        .findFirst()
        .orElse(null);
  }

  /** Every plan, for the console and for putting the policy set in the model's context. */
  public List<FunctionPayPlan> all() {
    List<FunctionPayPlan> out = new ArrayList<>();
    for (String key : List.of(US, INDIA_SALES, PS_CSM)) {
      FunctionPayPlan p = plan(key);
      if (p != null) out.add(p);
    }
    return out;
  }

  /** Stores an edited plan, stamped with who changed it. */
  /**
   * Which plan owns each knowledge-base document, for retrieval scoping.
   *
   * <p>Derived from the plans rather than configured separately, so there is one place a document
   * is claimed and it is the same place its slabs live. A document claimed by no plan is absent
   * from this map, which retrieval treats as neutral — most of the corpus is leave, insurance and
   * travel policy that belongs to no pay plan and must never be demoted.
   */
  public Map<String, String> documentOwners() {
    Map<String, String> owners = new HashMap<>();
    for (FunctionPayPlan plan : all()) {
      if (plan == null || plan.sourceDocumentIds() == null) continue;
      for (String docId : plan.sourceDocumentIds()) {
        if (docId != null && !docId.isBlank()) owners.put(docId.trim(), plan.planKey());
      }
    }
    return owners;
  }

  public FunctionPayPlan save(String planKey, FunctionPayPlan edited, String actor) {
    FunctionPayPlan current = plan(planKey);
    if (current == null) throw new IllegalArgumentException("No such plan: " + planKey);

    FunctionPayPlan toSave =
        new FunctionPayPlan(
            current.id(),
            planKey,
            blankTo(edited.label(), current.label()),
            blankTo(edited.fyLabel(), current.fyLabel()),
            blankTo(edited.policyVersion(), current.policyVersion()),
            blankTo(edited.currency(), current.currency()),
            emptyTo(edited.slabs(), current.slabs()),
            emptyTo(edited.roles(), current.roles()),
            // Org values pass through as given: clearing one is a legitimate edit meaning "not
            // declared", which is not the same as unchanged.
            edited.orgValues() == null ? current.orgValues() : edited.orgValues(),
            // Null means "not supplied by this edit" and keeps what is stored. Without this a
            // save from the console would clear the document mapping every time somebody
            // adjusted a slab, and the wrong-policy citations would come back silently.
            edited.sourceDocumentIds() == null
                ? current.sourceDocumentIds()
                : edited.sourceDocumentIds(),
            Instant.now().toString(),
            actor);

    FunctionPayPlan saved = plans.save(toSave);
    log.info("{} pay plan updated by {}", planKey, actor);
    return saved;
  }

  @EventListener(ApplicationReadyEvent.class)
  void seed() {
    try {
      for (FunctionPayPlan published : PublishedFunctionPlans.all()) {
        if (plans.findByPlanKey(published.planKey()).isPresent()) continue;
        plans.save(published);
        log.info(
            "Seeded the {} pay plan from the published policy ({}). Thresholds and base rates are"
                + " example values from the document — review them in the admin console.",
            published.planKey(),
            published.policyVersion());
      }
    } catch (RuntimeException e) {
      log.warn("Could not seed the function pay plans — is MongoDB reachable? {}", e.toString());
    }
  }

  private static String blankTo(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private static <T> List<T> emptyTo(List<T> value, List<T> fallback) {
    return value == null || value.isEmpty() ? fallback : value;
  }
}
