package com.leadsquared.hr.knowledge.web;

import com.leadsquared.hr.knowledge.audit.AdminAuditService;
import com.leadsquared.hr.knowledge.model.FunctionPayPlan;
import com.leadsquared.hr.knowledge.payroll.FunctionPayPlanService;
import com.leadsquared.hr.knowledge.security.CurrentUser;
import com.leadsquared.hr.knowledge.security.SignedInUser;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The three revenue-function pay policies, as maintained in the admin console.
 *
 * <p>Sits under {@code /api/payroll}, so it is gated by {@code admin.payroll} alongside the
 * Non-Sales plan. Everything reachable here decides what employees in those functions are told
 * they would earn.
 */
@RestController
@RequestMapping("/api/payroll/function-pay")
public class FunctionPayController {

  private final FunctionPayPlanService plans;
  private final CurrentUser currentUser;
  private final AdminAuditService audit;

  public FunctionPayController(
      FunctionPayPlanService plans, CurrentUser currentUser, AdminAuditService audit) {
    this.plans = plans;
    this.currentUser = currentUser;
    this.audit = audit;
  }

  public record PlansResponse(List<FunctionPayPlan> plans) {}

  @GetMapping("/plans")
  public PlansResponse plans() {
    return new PlansResponse(plans.all());
  }

  @PutMapping("/plans/{planKey}")
  public ResponseEntity<?> update(
      @PathVariable String planKey, @RequestBody(required = false) FunctionPayPlan body) {

    if (body == null) return ApiErrors.badRequest("Expected a plan.");
    try {
      FunctionPayPlan saved = plans.save(planKey, body, actor());
      audit.record(
          "function_pay_plan_updated",
          planKey,
          "Variable & incentive pay plan '" + saved.label() + "' (" + saved.fyLabel()
              + ") updated — slabs, role weightings, kicker rates or org values changed.");
      return ResponseEntity.ok(saved);
    } catch (IllegalArgumentException e) {
      return ApiErrors.badRequest(e.getMessage());
    }
  }

  private String actor() {
    return currentUser.get().map(SignedInUser::email).orElse("unknown");
  }
}
