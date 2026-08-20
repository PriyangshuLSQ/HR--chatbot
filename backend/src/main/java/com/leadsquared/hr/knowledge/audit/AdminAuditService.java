package com.leadsquared.hr.knowledge.audit;

import com.leadsquared.hr.knowledge.model.AdminAuditEvent;
import com.leadsquared.hr.knowledge.security.CurrentUser;
import com.leadsquared.hr.knowledge.store.AdminAuditEventRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Writes the administrative audit trail, and reads it back for the console.
 *
 * <p>The actor is taken from the session here rather than passed in by callers. A caller that
 * supplies its own idea of who is acting is a caller that can be wrong about it, and an audit
 * trail is worth precisely as much as the accuracy of that one field.
 *
 * <p><b>Recording never fails a request.</b> Every write is wrapped: if Mongo is unreachable, the
 * policy still uploads and the role is still assigned, with a warning in the log. The alternative
 * — refusing the action because it could not be written down — would make the audit trail an
 * availability risk for the console it is auditing. That is a deliberate trade, and it is why the
 * warning is logged at WARN with the action in it: a gap in the trail is recoverable from the
 * application log.
 */
@Service
public class AdminAuditService {

  private static final Logger log = LoggerFactory.getLogger(AdminAuditService.class);

  /** Enough to review a week of console activity without paging. */
  private static final int DEFAULT_LIMIT = 200;

  private static final int MAX_LIMIT = 1000;

  private final AdminAuditEventRepository events;
  private final CurrentUser currentUser;

  public AdminAuditService(AdminAuditEventRepository events, CurrentUser currentUser) {
    this.events = events;
    this.currentUser = currentUser;
  }

  /** Records an action attributed to whoever is signed in. */
  public void record(String action, String target, String detail) {
    String actor = currentUser.get().map(u -> u.email()).orElse("unknown");
    recordAs(actor, action, target, detail);
  }

  /**
   * Records an action on behalf of a named actor.
   *
   * <p>For the paths where there is no session to read — startup seeding, for one, which grants a
   * role before anybody has signed in.
   */
  public void recordAs(String actor, String action, String target, String detail) {
    try {
      events.save(AdminAuditEvent.of(actor, action, target, detail));
    } catch (RuntimeException e) {
      log.warn(
          "Could not write audit event action={} target={} actor={} — {}",
          action,
          target,
          actor,
          e.toString());
    }
  }

  /**
   * The trail, newest first.
   *
   * @param action optional filter on one of {@link AdminAuditEvent}'s action constants
   */
  public List<AdminAuditEvent> list(String action, Integer limit) {
    int size = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    PageRequest page = PageRequest.of(0, size);
    try {
      return action == null || action.isBlank()
          ? events.findAllByOrderByAtDesc(page)
          : events.findByActionOrderByAtDesc(action.trim(), page);
    } catch (RuntimeException e) {
      log.warn("Could not read the audit trail: {}", e.toString());
      return List.of();
    }
  }
}
