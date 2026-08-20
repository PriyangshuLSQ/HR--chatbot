package com.leadsquared.hr.knowledge.security;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

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
 * @param devSignInAs TEMPORARY — see {@link #devSignInEmail()}
 * @param devSignInName display name for that account, since there are no claims to read it from
 */
@ConfigurationProperties(prefix = "hr.auth")
public record AuthProperties(
    boolean enabled,
    boolean require,
    String adminEmails,
    String successPath,
    String devSignInAs,
    String devSignInName) {

  /**
   * Which constructor binds the configuration.
   *
   * <p>Required from the moment a second constructor exists: value-object binding only happens
   * when there is exactly one candidate, and with two Boot falls back to JavaBean binding and
   * fails on a record for having no default constructor. The failure names the missing no-arg
   * constructor rather than the ambiguity, which sends you the wrong way.
   */
  @ConstructorBinding
  public AuthProperties {}

  /**
   * The four-argument shape the tests construct. Kept so adding the dev sign-in fields did not
   * touch seven unrelated test cases; those cases are about admin resolution and say nothing
   * about this bypass, which is off in every one of them.
   */
  public AuthProperties(boolean enabled, boolean require, String adminEmails, String successPath) {
    this(enabled, require, adminEmails, successPath, null, null);
  }

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
   * TEMPORARY: the one account {@code POST /api/auth/dev} may sign in as, or null when that
   * endpoint is off — which is its state unless this is set, and it is set nowhere in committed
   * configuration.
   *
   * <p>Why this exists: the Entra app registration for this tenant is still awaiting IT approval,
   * so nobody can complete a Microsoft sign-in yet. The obvious workaround — turn authentication
   * off, or comment out the filter chain — is the one thing that must not happen here, because
   * {@code shouldSecure(false)} serves every endpoint open, employee compensation included. This
   * keeps the whole chain standing and substitutes only the *first* step: instead of Microsoft
   * asserting who you are, one address in a gitignored local file does. Everything after that —
   * the session, the role lookup, the per-request authorization, the privacy rule that resolves
   * employee data against the session's own record — runs exactly as it will in production.
   *
   * <p>Two properties of the design matter, and both are deliberate:
   *
   * <ul>
   *   <li><b>The caller cannot choose the address.</b> The endpoint takes no email; it reads this
   *       one. A bypass that accepted an address from the request body would let anyone who can
   *       reach the API become any employee, which is worse than having no login.
   *   <li><b>It cannot ship.</b> {@code hr.auth.require=true} — mandatory in every deployed
   *       profile — refuses to start while this is set. See SecurityConfig.
   * </ul>
   *
   * <p>Removing it is deleting the two lines from {@code application-local.yml}. No code changes.
   */
  public String devSignInEmail() {
    if (devSignInAs == null || devSignInAs.isBlank()) return null;
    return devSignInAs.trim().toLowerCase();
  }

  /** Display name for the dev sign-in account; the local part of the address if unset. */
  public String devSignInDisplayName() {
    if (devSignInName != null && !devSignInName.isBlank()) return devSignInName.trim();
    String email = devSignInEmail();
    return email == null ? null : email.split("@")[0];
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
