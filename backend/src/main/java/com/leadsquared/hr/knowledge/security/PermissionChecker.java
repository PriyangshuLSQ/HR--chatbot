package com.leadsquared.hr.knowledge.security;

import com.leadsquared.hr.knowledge.iam.IamService;
import com.leadsquared.hr.knowledge.model.Permissions;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The one answer to "does this person hold that permission".
 *
 * <p>Two callers need it and they must not drift: {@link PermissionAccess} gates whole URLs, and
 * {@code TicketController} gates rows within one URL — employees read their own escalations from
 * the same endpoint HR reads the queue from, so that one cannot be decided by path.
 *
 * <p>Every grant is read from the store per request rather than from the authorities frozen at
 * sign-in, so a revocation takes effect on the next request instead of the next sign-out.
 */
@Component
public class PermissionChecker {

  private final AuthProperties props;
  private final IamService iam;

  public PermissionChecker(AuthProperties props, IamService iam) {
    this.props = props;
    this.iam = iam;
  }

  /**
   * Whether {@code email} holds {@code permission}, and the console permission behind it.
   *
   * <p>Both, because {@link Permissions#ADMIN_CONSOLE} is the front door: a role carrying only
   * area permissions reaches nothing, and removing the console permission removes the console
   * wholesale rather than leaving the areas individually reachable.
   *
   * <p>The break-glass list in {@code hr.auth.admin-emails} short-circuits it. That list exists
   * for a store that cannot be read or an organisation with nobody holding {@link
   * Permissions#ADMIN_ACCESS} any more, and an emergency administrator admitted to half the
   * console could resolve neither.
   */
  public boolean has(String email, String permission) {
    if (email == null) return false;
    if (props.admins().contains(email)) return true;

    Set<String> held = iam.permissionsFor(Set.of(email));
    return held.contains(Permissions.ADMIN_CONSOLE) && held.contains(permission);
  }

  /** As {@link #has(String, String)}, for a resolved user. Null — no sign-in configured — is not
   * decided here: callers that still run on the open filter chain have their own allowance. */
  public boolean has(SignedInUser user, String permission) {
    return user != null && has(user.email(), permission);
  }
}
