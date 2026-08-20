package com.leadsquared.hr.knowledge.security;

import com.leadsquared.hr.knowledge.iam.IamService;
import com.leadsquared.hr.knowledge.model.Permissions;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/**
 * Admits a request to the audit trail and the sign-in list — HR Ops Admin only.
 *
 * <p>A second, narrower gate beside {@link AdminConsoleAccess}, and decided per request for the
 * same reason: demoting someone from HR Ops Admin to HR Ops has to take effect on their next
 * request, not at their next sign-in.
 *
 * <p>The break-glass list in {@code hr.auth.admin-emails} passes, matching {@code AuthController}.
 * An emergency administrator who can reach the console but not the record of what has been done to
 * it would be unable to investigate the situation the list exists for.
 */
@Component
public class AuditAccess implements AuthorizationManager<RequestAuthorizationContext> {

  private final CurrentUser currentUser;
  private final AuthProperties props;
  private final IamService iam;

  public AuditAccess(CurrentUser currentUser, AuthProperties props, IamService iam) {
    this.currentUser = currentUser;
    this.props = props;
    this.iam = iam;
  }

  @Override
  public AuthorizationDecision check(
      Supplier<Authentication> authentication, RequestAuthorizationContext context) {

    boolean allowed =
        currentUser
            .resolve(authentication.get())
            .map(
                user ->
                    props.admins().contains(user.email())
                        || iam.permissionsFor(Set.of(user.email()))
                            .contains(Permissions.ADMIN_AUDIT))
            .orElse(false);

    return new AuthorizationDecision(allowed);
  }
}
