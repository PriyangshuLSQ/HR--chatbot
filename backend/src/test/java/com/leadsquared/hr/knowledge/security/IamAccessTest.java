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

  /**
   * Shaped like the {@code dev} role somebody actually built: the console plus every area
   * <em>except</em> the audit trail. It exists to prove an omission is honoured.
   */
  private static final IamRole ALL_BUT_AUDIT_ROLE =
      new IamRole(
          "3",
          "dev",
          "Dev access",
          "",
          Set.of(
              Permissions.ADMIN_CONSOLE,
              Permissions.ADMIN_ACCESS,
              Permissions.ADMIN_TICKETS,
              Permissions.ADMIN_TICKETS_SENSITIVE,
              Permissions.ADMIN_DIGEST,
              Permissions.ADMIN_KNOWLEDGE,
              Permissions.ADMIN_PAYROLL),
          true,
          "2026-01-01T00:00:00Z",
          "admin");

  @BeforeEach
  void setUp() {
    roles = Mockito.mock(IamRoleRepository.class);
    users = Mockito.mock(IamUserRepository.class);
    // No seeded admin addresses: these tests are about the permission lookup, and a seeder
    // writing to a mocked repository would only add noise to the verifications below.
    iam = new IamService(roles, users, "");
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

  /**
   * Creating a role again, and the validation that has to come back with it.
   *
   * <p>These were removed when the role set was fixed at two — with {@code admin.console} as the
   * only real grant, a third role could differ from the built-ins in name alone, so there was
   * nothing worth minting. Now that each area of the console is its own permission there is, and
   * the checks matter again.
   */
  @Test
  void aRoleCanBeCreatedWithAChosenSetOfPermissions() {
    when(roles.findByName("policy-librarian")).thenReturn(Optional.empty());
    when(roles.save(any(IamRole.class))).thenAnswer(call -> call.getArgument(0));

    IamRole created =
        iam.createRole(
            "policy-librarian",
            "Policy Librarian",
            "Maintains the policy library.",
            Set.of(Permissions.ADMIN_CONSOLE, Permissions.ADMIN_KNOWLEDGE),
            "me");

    assertThat(created.name()).isEqualTo("policy-librarian");
    assertThat(created.permissions())
        .containsExactlyInAnyOrder(Permissions.ADMIN_CONSOLE, Permissions.ADMIN_KNOWLEDGE);
    // Never built-in: those are re-asserted from constants on every boot, which would overwrite
    // whatever was chosen here at the next restart.
    assertThat(created.builtIn()).isFalse();
  }

  @Test
  void aRoleNameIsDerivedFromTheLabelAndSlugged() {
    when(roles.findByName(any())).thenReturn(Optional.empty());
    when(roles.save(any(IamRole.class))).thenAnswer(call -> call.getArgument(0));

    IamRole created =
        iam.createRole(null, "Payroll  Viewer!", "", Set.of(Permissions.ADMIN_CONSOLE), "me");

    assertThat(created.name()).isEqualTo("payroll-viewer");
  }

  @Test
  void anUnknownPermissionIsRefusedRatherThanStoredInert() {
    // The reason this check exists: an unrecognised string saves happily and grants nothing, so
    // the console would show access that every endpoint goes on refusing.
    when(roles.findByName(any())).thenReturn(Optional.empty());

    assertThat(
            catchThrowable(
                () ->
                    iam.createRole(
                        "payroll-viewer", "Payroll viewer", "", Set.of("payroll.read"), "me")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown permission");
  }

  @Test
  void aDuplicateNameIsRefused() {
    when(roles.findByName("hr-ops")).thenReturn(Optional.of(ADMIN_ROLE));

    assertThat(
            catchThrowable(
                () -> iam.createRole("hr-ops", "HR Ops", "", Set.of(Permissions.ADMIN_CONSOLE), "me")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("already exists");
  }

  @Test
  void aRoleNeedsAName() {
    assertThat(catchThrowable(() -> iam.createRole(null, "  ", "", Set.of(), "me")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("needs a name");
  }

  @Test
  void theTwoFixedRolesCannotBeDeleted() {
    IamRole hrOps =
        new IamRole(
            "9",
            IamService.HR_OPS_ROLE,
            "HR Ops",
            "",
            Set.of(Permissions.ADMIN_CONSOLE),
            true,
            "2026-01-01T00:00:00Z",
            "system");
    when(roles.findByName(IamService.HR_OPS_ROLE)).thenReturn(Optional.of(hrOps));

    assertThat(catchThrowable(() -> iam.deleteRole(IamService.HR_OPS_ROLE)))
        .isInstanceOf(IllegalArgumentException.class);
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

  // -------------------------------------------------------------------------
  // A role's omissions have to bite
  // -------------------------------------------------------------------------

  /**
   * The bug these two exist for, because it happened.
   *
   * <p>A {@code dev} role was created deliberately without {@link Permissions#ADMIN_AUDIT},
   * assigned to one account, and the audit trail stayed visible. Nothing was wrong with the role
   * or the assignment — the same address was also in {@code hr.auth.admin-emails}, and
   * {@link PermissionChecker#has} answers from that list before it ever reads IAM. The list does
   * not add to a role; it replaces the question.
   *
   * <p>So one test per side: the omission is honoured when the list is empty, and the list
   * overrides the omission when it is not. The second is not a bug, it is the break-glass
   * working — but it is the behaviour that made a correct role look broken, so it is pinned here
   * where the next person will find it.
   */
  @Test
  void aRoleWithoutTheAuditPermissionCannotReachTheAuditTrail() {
    when(users.findByEmailIn(anyCollection()))
        .thenReturn(
            List.of(
                new IamUser(
                    "u1",
                    ADMIN_EMAIL,
                    "Someone",
                    List.of("dev"),
                    "2026-01-01T00:00:00Z",
                    "admin")));
    when(roles.findAll()).thenReturn(List.of(ADMIN_ROLE, PLAIN_ROLE, ALL_BUT_AUDIT_ROLE));

    PermissionChecker checker = new PermissionChecker(noAdmins(), iam);

    assertThat(checker.has(ADMIN_EMAIL, Permissions.ADMIN_AUDIT)).isFalse();
    // The rest of the role still works — this is a withheld permission, not a broken account.
    assertThat(checker.has(ADMIN_EMAIL, Permissions.ADMIN_KNOWLEDGE)).isTrue();
    assertThat(checker.has(ADMIN_EMAIL, Permissions.ADMIN_TICKETS)).isTrue();
  }

  @Test
  void theBreakGlassListOverridesAnOmissionInTheRole() {
    when(users.findByEmailIn(anyCollection()))
        .thenReturn(
            List.of(
                new IamUser(
                    "u1",
                    ADMIN_EMAIL,
                    "Someone",
                    List.of("dev"),
                    "2026-01-01T00:00:00Z",
                    "admin")));
    when(roles.findAll()).thenReturn(List.of(ADMIN_ROLE, PLAIN_ROLE, ALL_BUT_AUDIT_ROLE));

    PermissionChecker checker =
        new PermissionChecker(new AuthProperties(true, false, ADMIN_EMAIL, "/chat"), iam);

    assertThat(checker.has(ADMIN_EMAIL, Permissions.ADMIN_AUDIT)).isTrue();
  }

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
