package com.leadsquared.hr.knowledge.security;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Sign-in configuration.
 *
 * @param enabled whether to require a signed-in user. Only meaningful together
 *     with a configured client id — see {@link #shouldSecure(boolean)}, which is
 *     what decides.
 * @param require fail startup rather than run open when sign-in is unusable. Off by
 *     default for local development; on in every deployed profile.
 * @param adminEmails comma-separated accounts that get the HR admin dashboard
 * @param successPath where the browser lands after a successful sign-in
 */
@ConfigurationProperties(prefix = "hr.auth")
public record AuthProperties(
    boolean enabled, boolean require, String adminEmails, String successPath) {

  /**
   * Whether an unauthenticated deployment is a startup failure rather than a warning.
   *
   * <p>Default false, so a checkout with no credentials still runs. Set true in every
   * deployed profile: it converts "every endpoint is open" from a log line into a refusal
   * to boot, which is the only form of that check that survives contact with a hurried
   * deploy. Required by the Phase 1 rule that no employee data is served before identity is
   * verified — a rule a warning cannot enforce.
   */
  public boolean requireAuthentication() {
    return require;
  }

  /**
   * Whether the email/password path may create a session — only ever true when
   * there is no Entra client configured.
   *
   * <p>Deliberately not configurable. Microsoft sign-in is the only way in wherever
   * it is available, and a flag that could grant an exception is a flag someone
   * eventually sets: no password is verified on this path, so anyone able to reach
   * the API could name an address and become that employee.
   *
   * <p>It survives at all for the checkout with no credentials, where the service
   * runs open anyway and the alternative is no login screen at all. Even there it
   * creates a real session, which the browser-side demo identity it replaced did
   * not — that fiction is how the app once showed someone signed in as
   * {@code employee@company.com} while the backend knew no such session.
   */
  public boolean localLoginEnabled(boolean ssoConfigured) {
    return !ssoConfigured;
  }

  /**
   * Whether to stand up the authenticated filter chain.
   *
   * <p>Both conditions have to hold, and the asymmetry is deliberate. With no
   * client id there is nothing to sign in against, so requiring a login would
   * lock everyone out of a service that has no way to let them back in — the app
   * runs open, as it did before Entra, and says so. But `enabled: false` with
   * credentials present is treated as what it is: someone turning authentication
   * off on a deployment that has it available.
   */
  public boolean shouldSecure(boolean clientConfigured) {
    return enabled && clientConfigured;
  }

  /** Normalised, lower-cased admin list. */
  public Set<String> admins() {
    if (adminEmails == null || adminEmails.isBlank()) return Set.of();
    return Arrays.stream(adminEmails.split(","))
        .map(s -> s.trim().toLowerCase())
        .filter(s -> !s.isEmpty())
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
  }

  public String resolvedSuccessPath() {
    return successPath == null || successPath.isBlank() ? "/chat" : successPath;
  }
}
