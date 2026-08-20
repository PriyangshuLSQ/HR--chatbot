package com.leadsquared.hr.knowledge.web;

import com.leadsquared.hr.knowledge.audit.AdminAuditService;
import com.leadsquared.hr.knowledge.model.VariablePayPlan;
import com.leadsquared.hr.knowledge.payroll.VariablePayPlanService;
import com.leadsquared.hr.knowledge.security.CurrentUser;
import com.leadsquared.hr.knowledge.security.SignedInUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The variable pay plan, as maintained in the admin console.
 *
 * <p>Gated by {@code admin.payroll} in {@code SecurityConfig}. Everything reachable here decides
 * what every non-sales employee is told they will be paid, which is why it is a permission of its
 * own rather than part of the knowledge base.
 */
@RestController
@RequestMapping("/api/payroll/variable-pay")
public class VariablePayController {

  private final VariablePayPlanService plans;
  private final CurrentUser currentUser;
  private final AdminAuditService audit;

  public VariablePayController(
      VariablePayPlanService plans, CurrentUser currentUser, AdminAuditService audit) {
    this.plans = plans;
    this.currentUser = currentUser;
    this.audit = audit;
  }

  @GetMapping("/plan")
  public VariablePayPlan plan() {
    return plans.plan();
  }

  /**
   * Replaces the plan.
   *
   * <p>Audited, and deliberately verbose about it: a changed revenue figure moves the computed
   * payout for thousands of people at once, and "who set this, and when" is the first question
   * anyone will ask when a figure is disputed.
   */
  @PutMapping("/plan")
  public ResponseEntity<?> update(@RequestBody(required = false) VariablePayPlan body) {
    if (body == null) return ApiErrors.badRequest("Expected a plan.");

    VariablePayPlan before = plans.plan();
    VariablePayPlan saved = plans.save(body, actor());

    audit.record(
        "variable_pay_plan_updated",
        saved.fyLabel(),
        "Variable pay plan for "
            + saved.fyLabel()
            + " updated — revenue "
            + describe(before.revenueActualCr())
            + " → "
            + describe(saved.revenueActualCr())
            + " Cr, GRR "
            + describe(before.grrActualPercent())
            + " → "
            + describe(saved.grrActualPercent())
            + "%.");

    return ResponseEntity.ok(saved);
  }

  private static String describe(Object value) {
    return value == null ? "not declared" : value.toString();
  }

  private String actor() {
    return currentUser.get().map(SignedInUser::email).orElse("unknown");
  }

}
