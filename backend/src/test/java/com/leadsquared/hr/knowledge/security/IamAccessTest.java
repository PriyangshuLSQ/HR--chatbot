package com.leadsquared.hr.knowledge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

import com.leadsquared.hr.knowledge.iam.IamService;
import com.leadsquared.hr.knowledge.model.IamRole;
import com.leadsquared.hr.knowledge.model.IamUser;
import com.leadsquared.hr.knowledge.model.Permissions;
import com.leadsquared.hr.knowledge.store.IamRoleRepository;
import com.leadsquared.hr.knowledge.store.IamUserRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * Access granted by a role assignment, and — more importantly — access taken away.
 *
 * <p>The revocation cases are the point of this file. Roles used to be decided once
 * at sign-in and stamped onto the session, so withdrawing someone's access left them
 * holding it until they next signed out. These pin the behaviour that replaced that.
 */
class IamAccessTest {

  private static final String ADMIN_EMAIL = "granted@leadsquared.com";

  private IamRoleRepository roles;
  private IamUserRepository users;
  private IamService iam;

  private static final IamRole ADMIN_ROLE =
      new IamRole(
          "1",
          "hr-admin",
          "HR admin",
          "",
          Set.of(Permissions.ADMIN_CONSOLE),
          true,
          "2026-01-01T00:00:00Z",
          "system");

  private static final IamRole PLAIN_ROLE =
      new IamRole("2", "employee", "Employee", "", Set.of(), true, "2026-01-01T00:00:00Z", "system");

  @BeforeEach
  void setUp() {
    roles = Mockito.mock(IamRoleRepository.class);
    users = Mockito.mock(IamUserRepository.class);
    iam = new IamService(roles, users);
  }

  private void assign(String email, String... roleNames) {
    when(users.findByEmailIn(anyCollection()))
        .thenReturn(
            List.of(
                new IamUser(
                    "u1", email, "Someone", List.of(roleNames), "2026-01-01T00:00:00Z", "admin")));
    when(roles.findAll()).thenReturn(List.of(ADMIN_ROLE, PLAIN_ROLE));
  }

  private void assignNobody() {
    when(users.findByEmailIn(anyCollection())).thenReturn(List.of());
    when(roles.findAll()).thenReturn(List.of(ADMIN_ROLE, PLAIN_ROLE));
  }

  // -------------------------------------------------------------------------
  // Granting
  // -------------------------------------------------------------------------

  @Test
  void anAssignedAdminRoleGrantsTheConsoleWithoutBeingOnTheConfiguredList() {
    assign(ADMIN_EMAIL, "hr-admin");

    SignedInUser user = resolver(noAdmins()).from(oidc(ADMIN_EMAIL, Map.of()));

    assertThat(user.role()).isEqualTo(SignedInUser.HR_ADMIN);
  }

  @Test
  void aRoleWithoutThePermissionGrantsNothing() {
    assign(ADMIN_EMAIL, "employee");

    assertThat(resolver(noAdmins()).from(oidc(ADMIN_EMAIL, Map.of())).role())
        .isEqualTo(SignedInUser.EMPLOYEE);
  }

  @Test
  void someoneWithNoRecordAtAllIsAnEmployee() {
    assignNobody();

    // Absence has to deny rather than block: requiring a record per person would
    // mean the assistant stopped working for anyone HR had not enrolled by hand.
    assertThat(resolver(noAdmins()).from(oidc("nobody@leadsquared.com", Map.of())).role())
        .isEqualTo(SignedInUser.EMPLOYEE);
  }

  /** An assignment naming a role that has since been deleted contributes nothing. */
  @Test
  void aStaleAssignmentToADeletedRoleIsIgnored() {
    when(users.findByEmailIn(anyCollection()))
        .thenReturn(
            List.of(
                new IamUser(
                    "u1",
                    ADMIN_EMAIL,
                    "Someone",
                    List.of("payroll-viewer"),
                    "2026-01-01T00:00:00Z",
                    "admin")));
    when(roles.findAll()).thenReturn(List.of(ADMIN_ROLE, PLAIN_ROLE));

    assertThat(resolver(noAdmins()).from(oidc(ADMIN_EMAIL, Map.of())).role())
        .isEqualTo(SignedInUser.EMPLOYEE);
  }

  // -------------------------------------------------------------------------
  // Revoking — the cases that used to be wrong
  // -------------------------------------------------------------------------

  /**
   * The behaviour {@code hasRole("HR_ADMIN")} could not deliver: the session still
   * carries the authority stamped on at sign-in, and the request is refused anyway.
   */
  @Test
  void revokingTheRoleRefusesTheNextRequestEvenWithAnAdminSessionAuthority() {
    assignNobody();

    OidcUser principal = oidc(ADMIN_EMAIL, Map.of());
    TestingAuthenticationToken stillStampedAsAdmin =
        new TestingAuthenticationToken(
            principal, "credentials", List.of(new SimpleGrantedAuthority("ROLE_HR_ADMIN")));

    AdminConsoleAccess gate = new AdminConsoleAccess(resolver(noAdmins()));

    assertThat(gate.check(() -> stillStampedAsAdmin, null).isGranted()).isFalse();
  }

  @Test
  void aCurrentAssignmentIsAdmittedByTheGate() {
    assign(ADMIN_EMAIL, "hr-admin");

    OidcUser principal = oidc(ADMIN_EMAIL, Map.of());
    // No admin authority on the session at all — the grant is decided live.
    TestingAuthenticationToken plainSession =
        new TestingAuthenticationToken(
            principal, "credentials", List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")));

    AdminConsoleAccess gate = new AdminConsoleAccess(resolver(noAdmins()));

    assertThat(gate.check(() -> plainSession, null).isGranted()).isTrue();
  }

  @Test
  void theGateRefusesWhenNobodyIsSignedIn() {
    assignNobody();
    AdminConsoleAccess gate = new AdminConsoleAccess(resolver(noAdmins()));

    assertThat(gate.check(() -> null, null).isGranted()).isFalse();
  }

  // -------------------------------------------------------------------------
  // The rules that outrank IAM
  // -------------------------------------------------------------------------

  /**
   * The break-glass. On a fresh deployment the assignment collection is empty, so if
   * IAM were the only authority nobody could open the console to make the first
   * grant.
   */
  @Test
  void theConfiguredListStillGrantsTheConsoleWithNoAssignment() {
    assignNobody();

    AuthProperties withAdmin = new AuthProperties(true, false, ADMIN_EMAIL, "/chat");

    assertThat(resolver(withAdmin).from(oidc(ADMIN_EMAIL, Map.of())).role())
        .isEqualTo(SignedInUser.HR_ADMIN);
  }

  @Test
  void anEntraAppRoleStillGrantsTheConsoleWithNoAssignment() {
    assignNobody();

    assertThat(
            resolver(noAdmins())
                .from(oidc(ADMIN_EMAIL, Map.of("roles", List.of("HR.Admin"))))
                .role())
        .isEqualTo(SignedInUser.HR_ADMIN);
  }

  /**
   * Mongo being unreachable already degrades this application rather than breaking
   * it. An outage must not become a lockout, so a failed lookup falls through to the
   * configured list instead of denying.
   */
  @Test
  void anIamOutageFallsBackToConfigurationRatherThanLockingEveryoneOut() {
    when(users.findByEmailIn(anyCollection()))
        .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("mongo down"));

    AuthProperties withAdmin = new AuthProperties(true, false, ADMIN_EMAIL, "/chat");

    assertThat(resolver(withAdmin).from(oidc(ADMIN_EMAIL, Map.of())).role())
        .isEqualTo(SignedInUser.HR_ADMIN);
  }

  @Test
  void anIamOutageDoesNotInventAdminsEither() {
    when(users.findByEmailIn(anyCollection()))
        .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("mongo down"));

    assertThat(resolver(noAdmins()).from(oidc(ADMIN_EMAIL, Map.of())).role())
        .isEqualTo(SignedInUser.EMPLOYEE);
  }

  // -------------------------------------------------------------------------
  // Role bookkeeping
  // -------------------------------------------------------------------------

  @Test
  void aBuiltInRoleCannotBeDeleted() {
    when(roles.findByName("hr-admin")).thenReturn(Optional.of(ADMIN_ROLE));

    assertThat(catchThrowable(() -> iam.deleteRole("hr-admin")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot be deleted");
  }

  @Test
  void aBuiltInRolesPermissionsCannotBeEdited() {
    when(roles.findByName("hr-admin")).thenReturn(Optional.of(ADMIN_ROLE));

    assertThat(catchThrowable(() -> iam.updateRole("hr-admin", "HR admin", "", Set.of(), "me")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("built-in");
  }

  /** An unknown permission would save happily and grant nothing. */
  @Test
  void anUnknownPermissionIsRejectedRatherThanStoredInert() {
    when(roles.existsByName("payroll-viewer")).thenReturn(false);

    assertThat(
            catchThrowable(
                () ->
                    iam.createRole(
                        "payroll-viewer", "Payroll viewer", "", Set.of("payroll.read"), "me")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown permission");
  }

  @Test
  void aRoleNameHasToBeASlug() {
    assertThat(
            catchThrowable(
                () -> iam.createRole("Payroll Viewer!", "Payroll viewer", "", Set.of(), "me")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("lower-case letters");
  }

  @Test
  void assigningARoleThatDoesNotExistIsRejected() {
    when(users.findByEmail(any())).thenReturn(Optional.empty());
    when(roles.existsByName("ghost")).thenReturn(false);

    assertThat(
            catchThrowable(
                () -> iam.upsertUser("someone@leadsquared.com", "Someone", List.of("ghost"), "me")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("No role named");
  }

  // -------------------------------------------------------------------------
  // Fixtures
  // -------------------------------------------------------------------------

  private CurrentUser resolver(AuthProperties props) {
    return new CurrentUser(props, iam);
  }

  private static AuthProperties noAdmins() {
    return new AuthProperties(true, false, "", "/chat");
  }

  private static OidcUser oidc(String email, Map<String, Object> extraClaims) {
    Map<String, Object> claims = new java.util.LinkedHashMap<>();
    claims.put("sub", "subject-" + email);
    claims.put("preferred_username", email);
    claims.putAll(extraClaims);

    OidcIdToken token =
        new OidcIdToken("token", java.time.Instant.now(), java.time.Instant.now().plusSeconds(300), claims);
    return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")), token, "preferred_username");
  }

  private static Throwable catchThrowable(ThrowingCallable callable) {
    return org.assertj.core.api.Assertions.catchThrowable(callable::call);
  }

  private interface ThrowingCallable {
    void call() throws Exception;
  }
}
