package com.leadsquared.hr.knowledge.security;

import java.util.function.Supplier;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/**
 * Admits a request to the admin console's APIs, deciding it fresh each time.
 *
 * <p>This replaces {@code hasRole("HR_ADMIN")} on those routes, and the difference
 * is the whole point. Spring evaluates {@code hasRole} against the authorities
 * stamped onto the principal at sign-in, so with roles now managed in the console,
 * revoking someone's access would have left them holding it until they happened to
 * sign out — potentially for days. Withdrawing access has to mean the next request
 * is refused.
 *
 * <p>Delegating to {@link CurrentUser} rather than re-reading claims keeps one
 * definition of who is an admin: Entra app role, then IAM assignment, then the
 * configured break-glass list.
 */
@Component
public class AdminConsoleAccess implements AuthorizationManager<RequestAuthorizationContext> {

  private final CurrentUser currentUser;

  public AdminConsoleAccess(CurrentUser currentUser) {
    this.currentUser = currentUser;
  }

  @Override
  public AuthorizationDecision check(
      Supplier<Authentication> authentication, RequestAuthorizationContext context) {

    return new AuthorizationDecision(
        currentUser.resolve(authentication.get()).map(SignedInUser::isAdmin).orElse(false));
  }
}
