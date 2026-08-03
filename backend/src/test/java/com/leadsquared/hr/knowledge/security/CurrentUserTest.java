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

  private static final AuthProperties NO_ADMINS = new AuthProperties(true, "", "/chat");
  private static final AuthProperties WITH_ADMIN =
      new AuthProperties(true, "boss@leadsquared.com , OTHER@leadsquared.com", "/chat");

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
    AuthProperties props = new AuthProperties(true, "bnalamat@gitam.in", "/chat");

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
    AuthProperties props = new AuthProperties(true, "first@last.com", "/chat");
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
    assertThat(new AuthProperties(false, "", "/chat").shouldSecure(true)).isFalse();
  }
}
