package com.leadsquared.hr.knowledge.security;

import com.leadsquared.hr.knowledge.iam.IamService;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/**
 * Resolves the signed-in employee from the security context.
 *
 * <p>This is the only place identity is derived. Controllers ask here rather than
 * reading an email out of a request body, because a caller who can name their own
 * identity has no identity at all — that was the flaw in the first cut of
 * {@code /api/threads}.
 */
@Component
public class CurrentUser {

  private static final List<String> ADMIN_CLAIM_VALUES =
      List.of("hr_admin", "hr.admin", "hradmin", "hr-admin");

  private final AuthProperties props;

  /**
   * Nullable, and only because {@code EntraOidcUserService} is constructed by hand
   * inside the security configuration. With no service the two configuration-based
   * rules still apply, so identity resolution degrades rather than failing.
   */
  private final IamService iam;

  public CurrentUser(AuthProperties props, IamService iam) {
    this.props = props;
    this.iam = iam;
  }

  /** Empty when nobody is signed in, or when sign-in is not configured at all. */
  public Optional<SignedInUser> get() {
    return resolve(SecurityContextHolder.getContext().getAuthentication());
  }

  /**
   * The signed-in user behind a given authentication.
   *
   * <p>Separate from {@link #get()} so the filter chain can authorize from the
   * authentication it already holds. That matters more than it looks: for an Entra
   * session the role is recomputed here on <em>every</em> call, which is what makes
   * a revoked role take effect immediately instead of at next sign-in.
   */
  public Optional<SignedInUser> resolve(Authentication auth) {
    if (auth == null || !auth.isAuthenticated()) return Optional.empty();

    Object principal = auth.getPrincipal();
    // Entra sign-in.
    if (principal instanceof OidcUser user) return Optional.of(from(user));
    // Email/password sign-in, which stores the resolved identity directly. Both
    // paths converge here so nothing downstream has to know which was used.
    //
    // Note this path's role is whatever it was at sign-in, because there are no
    // claims to re-read. It only exists where Entra is not configured at all (see
    // AuthProperties.localLoginEnabled), so no Entra deployment depends on it.
    if (principal instanceof SignedInUser local) return Optional.of(refreshed(local));
    return Optional.empty();
  }

  /** Re-applies the access rules to a stored identity, so a revocation still lands. */
  private SignedInUser refreshed(SignedInUser local) {
    if (local.email() == null) return local;

    String role =
        props.admins().contains(local.email()) || grantsAdminConsole(Set.of(local.email()))
            ? SignedInUser.HR_ADMIN
            : SignedInUser.EMPLOYEE;

    return role.equals(local.role())
        ? local
        : new SignedInUser(local.id(), local.email(), local.name(), role);
  }

  /**
   * @throws NotSignedInException when there is no session. Controllers that are
   *     meaningless without an identity call this, so a misconfigured filter
   *     chain fails closed with a 401 instead of quietly serving someone else's
   *     data under a null owner.
   */
  public SignedInUser require() {
    return get().orElseThrow(NotSignedInException::new);
  }

  /**
   * Resolves an email/password sign-in. Role comes from the same rules as Entra — an
   * IAM assignment or the configured allowlist — so the two paths cannot disagree
   * about who is an admin.
   */
  public SignedInUser fromEmail(String rawEmail, String displayName) {
    String email = rawEmail.trim().toLowerCase();
    String role =
        props.admins().contains(email) || grantsAdminConsole(Set.of(email))
            ? SignedInUser.HR_ADMIN
            : SignedInUser.EMPLOYEE;
    String name =
        displayName == null || displayName.isBlank() ? email.split("@")[0] : displayName.trim();
    return new SignedInUser(email, email, name, role);
  }

  SignedInUser from(OidcUser user) {
    // Entra puts the work account's UPN in preferred_username. `email` is only
    // present when the account has a mail attribute or the optional claim was
    // added, so it cannot be the primary source.
    String email = firstNonBlank(user.getEmail(), claim(user, "preferred_username"), claim(user, "upn"));
    String name = firstNonBlank(user.getFullName(), claim(user, "name"), email);
    String id = firstNonBlank(user.getSubject(), claim(user, "oid"), email);

    return new SignedInUser(
        id,
        email == null ? null : email.toLowerCase(),
        name,
        roleFor(email, user));
  }

  /**
   * Entra app role, then an IAM assignment, then the configured list.
   *
   * <p>Three sources, in descending order of how much authority they carry outside
   * this application. An Entra app role is the answer HR's IT would give and it
   * survives both this database and this deployment. An IAM assignment is what the
   * console manages day to day. The email list is the break-glass — it is what makes
   * the console reachable on a fresh install, before any assignment exists to grant
   * it. None grants anything by default: an account in none of them is an employee.
   */
  private String roleFor(String email, OidcUser user) {
    if (hasAdminClaim(user.getClaimAsStringList("roles"))
        || hasAdminClaim(user.getClaimAsStringList("groups"))) {
      return SignedInUser.HR_ADMIN;
    }

    Set<String> identities = identitiesOf(email, user);

    if (grantsAdminConsole(identities)) return SignedInUser.HR_ADMIN;

    Set<String> admins = props.admins();
    for (String candidate : identities) {
      if (admins.contains(candidate)) return SignedInUser.HR_ADMIN;
    }
    return SignedInUser.EMPLOYEE;
  }

  /** Never throws: an IAM outage falls through to the configuration-based rules. */
  private boolean grantsAdminConsole(Set<String> identities) {
    return iam != null && iam.grantsAdminConsole(identities);
  }

  /**
   * The break-glass admin list, for the console to display.
   *
   * <p>Surfaced because these accounts hold the console without appearing in any
   * assignment, and an access page that omitted them would be lying about who can
   * get in.
   */
  public Set<String> configuredAdmins() {
    return props.admins();
  }

  /**
   * Every form of this account's address worth matching an allowlist against.
   *
   * <p>Guests are why this is a set rather than one string. An account invited
   * into the directory from another tenant gets a UPN like
   * {@code someone_theirdomain.com#EXT#@ourtenant.onmicrosoft.com}, and which of
   * that or their real address lands in {@code preferred_username} varies. Someone
   * writing an allowlist will write the address they know —
   * {@code someone@theirdomain.com} — so the mangled form is translated back
   * rather than expecting them to guess at Entra's encoding.
   */
  static Set<String> identitiesOf(String email, OidcUser user) {
    Set<String> out = new LinkedHashSet<>();
    for (String raw :
        new String[] {
          email, claim(user, "preferred_username"), claim(user, "upn"), claim(user, "email")
        }) {
      if (raw == null || raw.isBlank()) continue;
      String value = raw.trim().toLowerCase();
      out.add(value);

      int ext = value.indexOf("#ext#@");
      if (ext > 0) {
        // someone_theirdomain.com#EXT#@ourtenant.onmicrosoft.com -> someone@theirdomain.com
        String local = value.substring(0, ext);
        int split = local.lastIndexOf('_');
        if (split > 0) {
          out.add(local.substring(0, split) + "@" + local.substring(split + 1));
        }
      }
    }
    return out;
  }

  private static boolean hasAdminClaim(Collection<String> values) {
    if (values == null) return false;
    return values.stream()
        .filter(v -> v != null)
        .map(v -> v.trim().toLowerCase())
        .anyMatch(ADMIN_CLAIM_VALUES::contains);
  }

  private static String claim(OidcUser user, String name) {
    Object value = user.getClaims().get(name);
    return value == null ? null : String.valueOf(value);
  }

  private static String firstNonBlank(String... values) {
    for (String v : values) {
      if (v != null && !v.isBlank()) return v;
    }
    return null;
  }

  /** No session where one is required. Mapped to 401 by the exception handler. */
  public static class NotSignedInException extends RuntimeException {
    public NotSignedInException() {
      super("Sign in to continue.");
    }
  }
}
