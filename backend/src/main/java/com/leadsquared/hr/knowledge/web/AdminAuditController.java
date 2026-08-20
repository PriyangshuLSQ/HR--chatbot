package com.leadsquared.hr.knowledge.web;

import com.leadsquared.hr.knowledge.audit.AdminAuditService;
import com.leadsquared.hr.knowledge.audit.LoginTracker;
import com.leadsquared.hr.knowledge.model.AdminAuditEvent;
import com.leadsquared.hr.knowledge.model.LoginRecord;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What HR Ops Admin can see and HR Ops cannot: the audit trail and the sign-in list.
 *
 * <p>Read-only by design. There is no endpoint here that writes or clears either collection —
 * every row arrives as a side effect of the action it describes, which is the only way a trail
 * stays trustworthy when its readers are also its subjects.
 *
 * <p>Gated on {@code admin.audit} in {@code SecurityConfig}, checked per request. The console
 * hides these tabs without the permission, but that is presentation; the refusal is here.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminAuditController {

  private final AdminAuditService audit;
  private final LoginTracker logins;

  public AdminAuditController(AdminAuditService audit, LoginTracker logins) {
    this.audit = audit;
    this.logins = logins;
  }

  /**
   * @param actions the action keys the console offers as filters, so the two cannot drift
   */
  public record AuditResponse(List<AdminAuditEvent> events, List<String> actions) {}

  @GetMapping("/audit")
  public AuditResponse audit(
      @RequestParam(name = "action", required = false) String action,
      @RequestParam(name = "limit", required = false) Integer limit) {

    return new AuditResponse(
        audit.list(action, limit),
        List.of(
            AdminAuditEvent.POLICY_UPLOADED,
            AdminAuditEvent.POLICY_DELETED,
            AdminAuditEvent.KNOWLEDGE_REINDEXED,
            AdminAuditEvent.ACCESS_GRANTED,
            AdminAuditEvent.ACCESS_CHANGED,
            AdminAuditEvent.ACCESS_REVOKED,
            AdminAuditEvent.ROLE_UPDATED));
  }

  public record LoginsResponse(List<LoginRecord> logins) {}

  @GetMapping("/logins")
  public LoginsResponse logins(@RequestParam(name = "limit", required = false) Integer limit) {
    return new LoginsResponse(logins.list(limit));
  }
}
