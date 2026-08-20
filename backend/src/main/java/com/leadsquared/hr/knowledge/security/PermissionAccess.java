package com.leadsquared.hr.knowledge.security;

import java.util.function.Supplier;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * Admits a request whose caller holds one named permission.
 *
 * <p>One class rather than one per permission — {@link AdminConsoleAccess} and {@link AuditAccess}
 * were already the same handful of lines with a different constant, and the console now has six
 * areas to gate. A factory keeps each rule in {@code SecurityConfig} on the line beside the URL it
 * guards, instead of spread across six near-identical beans.
 *
 * <p>The decision itself lives in {@link PermissionChecker}, because the escalation queue needs
 * the same answer and cannot ask for it by URL.
 */
public final class PermissionAccess implements AuthorizationManager<RequestAuthorizationContext> {

  private final CurrentUser currentUser;
  private final PermissionChecker permissions;
  private final String required;

  private PermissionAccess(
      CurrentUser currentUser, PermissionChecker permissions, String required) {
    this.currentUser = currentUser;
    this.permissions = permissions;
    this.required = required;
  }

  /** An authorization manager admitting holders of {@code permission}. */
  public static PermissionAccess requiring(
      CurrentUser currentUser, PermissionChecker permissions, String permission) {
    return new PermissionAccess(currentUser, permissions, permission);
  }

  @Override
  public AuthorizationDecision check(
      Supplier<Authentication> authentication, RequestAuthorizationContext context) {

    boolean allowed =
        currentUser
            .resolve(authentication.get())
            .map(user -> permissions.has(user.email(), required))
            .orElse(false);

    return new AuthorizationDecision(allowed);
  }
}
