package com.leadsquared.hr.knowledge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * Identity mapping for an Entra sign-in.
 *
 * <p>These exist because the alternative way to find a mistake here is a full
 * browser round trip through Microsoft, and the first attempt failed on exactly
 * that: {@code preferred_username} is in the ID token but NOT in Entra's userinfo
 * response, so requiring it as the user-name attribute blew up after a perfectly
 * good code exchange. {@link #entraUserInfoHasNoPreferredUsername()} pins that.
 */
class CurrentUserTest {

  private static final AuthProperties NO_ADMINS = new AuthProperties(true, false, "", "/chat");
  private static final AuthProperties WITH_ADMIN =
      new AuthProperties(true, false, "boss@leadsquared.com , OTHER@leadsquared.com", "/chat");

  /**
   * Built with no IAM service, because these cases pin the two rules that live
   * outside it: the Entra app-role claim and the configured allowlist. Grants that
   * come from a role assignment are covered in {@link IamAccessTest}, and keeping
   * them apart is what makes a failure here point at one rule rather than three.
   */
  private static CurrentUser resolverFor(AuthProperties props) {
    return new CurrentUser(props, null);
  }

  /** An Entra v2 ID token for a work account. */
  private static OidcIdToken idToken(Map<String, Object> extra) {
    Map<String, Object> claims = new java.util.LinkedHashMap<>();
    claims.put("sub", "sub-abc-123");
    claims.put("oid", "oid-def-456");
    claims.put("preferred_username", "Someone@LeadSquared.com");
    claims.put("name", "Someone Surname");
    claims.put("tid", "e63c0f42-d155-462b-98b5-0f10171605aa");
    claims.putAll(extra);
    return new OidcIdToken("token", Instant.now(), Instant.now().plusSeconds(3600), claims);
  }

  /**
   * What Entra's userinfo endpoint actually returns: no preferred_username, no
   * email. This is the shape that broke sign-in.
   */
  private static OidcUserInfo entraUserInfo() {
    return new OidcUserInfo(
        Map.of("sub", "sub-abc-123", "name", "Someone Surname", "family_name", "Surname"));
  }

  private static OidcUser principal(OidcIdToken token) {
    // Mirrors what EntraOidcUserService hands on: the name attribute is read from
    // the merged ID-token + userinfo claims, which is why preferred_username works
    // there but not against userinfo alone.
    return new DefaultOidcUser(
        List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")),
        token,
        entraUserInfo(),
        "preferred_username");
  }

  @Test
  void entraUserInfoHasNoPreferredUsername() {
    // The regression guard. If this ever passes, Entra changed its userinfo and
    // the `user-name-attribute: sub` comment in application.yml is stale.
    assertThat(entraUserInfo().getClaims()).doesNotContainKey("preferred_username");

    // And the merged view — what DefaultOidcUser validates against — does have it,
    // which is why the principal below can be built at all.
    assertThatCode(() -> principal(idToken(Map.of()))).doesNotThrowAnyException();
  }

  @Test
  void emailComesFromPreferredUsernameLowercased() {
    SignedInUser user = resolverFor(NO_ADMINS).from(principal(idToken(Map.of())));

    assertThat(user.email()).isEqualTo("someone@leadsquared.com");
    assertThat(user.name()).isEqualTo("Someone Surname");
    assertThat(user.id()).isEqualTo("sub-abc-123");
    assertThat(user.role()).isEqualTo(SignedInUser.EMPLOYEE);
  }

  @Test
  void emailClaimWinsWhenEntraSendsOne() {
    SignedInUser user =
        resolverFor(NO_ADMINS).from(principal(idToken(Map.of("email", "Mail@LeadSquared.com"))));

    assertThat(user.email()).isEqualTo("mail@leadsquared.com");
  }

  @Test
  void adminListIsCaseAndWhitespaceInsensitive() {
    CurrentUser resolver = resolverFor(WITH_ADMIN);

    assertThat(resolver.from(principal(idToken(Map.of("preferred_username", "BOSS@leadsquared.com")))).role())
        .isEqualTo(SignedInUser.HR_ADMIN);
    // Second entry had a leading space and upper case in the configured list.
    assertThat(resolver.from(principal(idToken(Map.of("preferred_username", "other@leadsquared.com")))).role())
        .isEqualTo(SignedInUser.HR_ADMIN);
    assertThat(resolver.from(principal(idToken(Map.of("preferred_username", "temp@leadsquared.com")))).role())
        .isEqualTo(SignedInUser.EMPLOYEE);
  }

  @Test
  void appRoleClaimGrantsAdminWithoutBeingOnTheList() {
    SignedInUser user =
        resolverFor(NO_ADMINS)
            .from(principal(idToken(Map.of("roles", List.of("HR.Admin")))));

    assertThat(user.role()).isEqualTo(SignedInUser.HR_ADMIN);
  }

  /**
   * A guest invited from another tenant. The allowlist is written with the address
   * a person actually knows, not Entra's mangled external UPN.
   */
  @Test
  void guestFromAnotherTenantMatchesTheAddressAnAdminWouldWrite() {
    AuthProperties props = new AuthProperties(true, false, "bnalamat@gitam.in", "/chat");

    // Case 1: preferred_username is the #EXT# UPN.
    OidcUser asExtUpn =
        principal(
            idToken(
                Map.of(
                    "preferred_username",
                    "bnalamat_gitam.in#EXT#@leadsquared.onmicrosoft.com")));
    assertThat(resolverFor(props).from(asExtUpn).role()).isEqualTo(SignedInUser.HR_ADMIN);

    // Case 2: preferred_username is the real external address. Both happen.
    OidcUser asRealAddress = principal(idToken(Map.of("preferred_username", "bnalamat@gitam.in")));
    assertThat(resolverFor(props).from(asRealAddress).role()).isEqualTo(SignedInUser.HR_ADMIN);
  }

  @Test
  void extUpnTranslationDoesNotOverreach() {
    // A member whose local part merely contains an underscore must not be rewritten
    // into some other address and accidentally match.
    AuthProperties props = new AuthProperties(true, false, "first@last.com", "/chat");
    OidcUser user = principal(idToken(Map.of("preferred_username", "first_last@leadsquared.com")));

    assertThat(resolverFor(props).from(user).role()).isEqualTo(SignedInUser.EMPLOYEE);
  }

  @Test
  void unrelatedAppRoleDoesNotGrantAdmin() {
    SignedInUser user =
        resolverFor(NO_ADMINS)
            .from(principal(idToken(Map.of("roles", List.of("ReplicationStudio.Reader")))));

    assertThat(user.role()).isEqualTo(SignedInUser.EMPLOYEE);
  }

  @Test
  void signInIsOnlyEnforcedWhenAClientIsConfigured() {
    // No client id: run open rather than lock everyone out of a service that has
    // no way to let them back in.
    assertThat(NO_ADMINS.shouldSecure(false)).isFalse();
    assertThat(NO_ADMINS.shouldSecure(true)).isTrue();
    assertThat(new AuthProperties(false, false, "", "/chat").shouldSecure(true)).isFalse();
  }

  /**
   * The password path and the dev stand-in must not both be on offer.
   *
   * <p>{@code localLoginEnabled} verifies no password — it issues a session for whatever address
   * is asked for, HR admin included. That was tolerable as the only way into a credential-less
   * checkout. It stopped being tolerable once the dev stand-in existed for the same situation and
   * signs in as exactly one configured address, because the two were then offered side by side on
   * a deployment reachable over the network.
   *
   * <p>The user-visible symptom was the other end of the same thing: a login page offering "use
   * the demo employee account", which created a real session for {@code employee@company.com} and
   * a chat window that greeted them by an invented name.
   */
  @Test
  void theDevStandInSupersedesThePasswordPath() {
    AuthProperties open = new AuthProperties(true, false, "", "/chat");
    // Nothing configured: the password path is the only way in, so it stays.
    assertThat(open.localLoginEnabled(false)).isTrue();
    // Entra configured: off, as it always was.
    assertThat(open.localLoginEnabled(true)).isFalse();

    AuthProperties standIn =
        new AuthProperties(
            true, false, "", "/chat", "nalamati.shirin@leadsquared.com", "Nalamati Bhargav Shirin");
    // A stand-in is configured, so there is a way in that does not mint arbitrary identities.
    assertThat(standIn.localLoginEnabled(false)).isFalse();
    assertThat(standIn.localLoginEnabled(true)).isFalse();
    // And the stand-in itself is unaffected.
    assertThat(standIn.devSignInEmail()).isEqualTo("nalamati.shirin@leadsquared.com");

    // Blank is not configured — a set-but-empty env var must not disable the only login path.
    AuthProperties blank = new AuthProperties(true, false, "", "/chat", "  ", "");
    assertThat(blank.localLoginEnabled(false)).isTrue();
  }
}
