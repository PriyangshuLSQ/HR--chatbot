package com.leadsquared.hr.knowledge.web;

import com.leadsquared.hr.knowledge.audit.LoginTracker;
import com.leadsquared.hr.knowledge.iam.IamService;
import com.leadsquared.hr.knowledge.model.LoginRecord;
import com.leadsquared.hr.knowledge.model.Permissions;
import com.leadsquared.hr.knowledge.security.AuthProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import com.leadsquared.hr.knowledge.security.CurrentUser;
import com.leadsquared.hr.knowledge.security.SecurityConfig;
import com.leadsquared.hr.knowledge.security.SignedInUser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who am I.
 *
 * <p>Deliberately public: the sign-in page has to be able to ask before it has a
 * session, both to know whether it is already signed in and to know whether
 * offering a Microsoft button would work at all.
 *
 * <p>Sign-out is handled by Spring's logout filter at {@code POST
 * /api/auth/logout} (see SecurityConfig), not by a method here — it has to clear
 * the session and the cookie, which the filter does properly.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(AuthController.class);

  /**
   * Matches the registration key in application.yml and the last segment of the
   * registered callback URI. Those three have to agree.
   */
  private static final String REGISTRATION_ID = "azure-ad";

  /** Deliberately permissive — this is a shape check, not identity verification. */
  private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s.]+\\.[^@\\s]+$");

  private final CurrentUser currentUser;
  private final AuthProperties props;
  private final IamService iam;
  private final LoginTracker logins;
  private final boolean ssoConfigured;

  public AuthController(
      CurrentUser currentUser,
      AuthProperties props,
      IamService iam,
      LoginTracker logins,
      ObjectProvider<ClientRegistrationRepository> clients) {
    this.currentUser = currentUser;
    this.props = props;
    this.iam = iam;
    this.logins = logins;
    this.ssoConfigured = clients.getIfAvailable() != null;
  }

  /**
   * @param ssoEnabled whether Microsoft sign-in is available and required. When
   *     false the frontend falls back to its demo login, and says so.
   * @param signInUrl where to send the browser to start the flow. A full-page
   *     navigation, never a fetch — the OAuth redirect chain cannot be followed by
   *     XHR.
   */
  public record Me(
      boolean authenticated,
      boolean ssoEnabled,
      String signInUrl,
      /**
       * Whether the email/password form may be shown. Reported by the server so the
       * page cannot offer a path the backend will refuse.
       */
      boolean localLoginEnabled,
      /**
       * TEMPORARY — whether {@code POST /api/auth/dev} will sign this browser in while the Entra
       * registration is pending. The login page points its Microsoft button at that endpoint when
       * this is true, so there is one button to click either way and nothing to change back in the
       * UI when the real flow arrives.
       */
      boolean devSignIn,
      /** The address that bypass would sign in as, so the page can name it rather than surprise. */
      String devSignInEmail,
      SignedInUser user,
      /**
       * What this session may do, as {@code Permissions} keys.
       *
       * <p>Reported so the console can hide a section it has no access to rather than render it
       * and collect a 403. It is not the control — every endpoint behind those sections is gated
       * server-side and re-decided per request — so a client that ignores this list learns
       * nothing it could not have guessed.
       */
      List<String> permissions) {}

  public record LocalLogin(String email, String password, String name) {}

  /**
   * Email/password sign-in, establishing a real server session.
   *
   * <p>The password is not checked against anything — there is no user store here.
   * This exists so an account outside the Entra tenant, or a laptop with no
   * network, can still use the app; it is refused outright unless
   * {@code hr.auth.local-login} allows it, precisely so it cannot become an
   * accidental way in.
   *
   * <p>Unlike the previous client-side demo login, this produces a session the API
   * accepts. A signed-in-looking UI over a backend that 401s everything is worse
   * than no login at all.
   */
  @PostMapping("/local")
  public ResponseEntity<?> localLogin(
      @RequestBody(required = false) LocalLogin body,
      HttpServletRequest request,
      HttpServletResponse response) {

    if (!props.localLoginEnabled(ssoConfigured)) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN)
          .body(Map.of("error", "This workspace uses Microsoft sign-in."));
    }

    String email = body == null || body.email() == null ? "" : body.email().trim();
    if (!EMAIL.matcher(email).matches()) {
      return ResponseEntity.badRequest().body(Map.of("error", "Enter a valid work email."));
    }

    SignedInUser user = currentUser.fromEmail(email, body.name());
    establishSession(user, request, response);
    logins.recordLogin(user.email(), user.name(), LoginRecord.LOCAL);

    return ResponseEntity.ok(user);
  }

  /**
   * TEMPORARY: sign in as the single account named by {@code hr.auth.dev-sign-in-as}, while the
   * Entra app registration for this tenant is awaiting IT approval.
   *
   * <p>Note what this endpoint does <em>not</em> take: an email. The identity comes from
   * configuration — a gitignored local file — never from the caller. That is the difference
   * between standing in for Microsoft's assertion of who you are and letting anyone who can reach
   * the API assert it themselves, which is the flaw {@code CurrentUser} exists to prevent.
   *
   * <p>Everything downstream is untouched: this produces an ordinary session, the role is resolved
   * by the same rules Entra sign-in uses (IAM assignment, then {@code hr.auth.admin-emails}), and
   * every endpoint re-decides authorization per request. The employee-data privacy rule is
   * likewise unaffected — answers resolve against whatever record this address matches in the
   * extract, exactly as they will for a real sign-in.
   *
   * <p>404 rather than 403 when the property is unset, so a deployment that never enabled this
   * does not advertise that the route exists.
   */
  @PostMapping("/dev")
  public ResponseEntity<?> devSignIn(HttpServletRequest request, HttpServletResponse response) {
    String email = props.devSignInEmail();
    if (email == null) {
      return ResponseEntity.notFound().build();
    }

    SignedInUser user = currentUser.fromEmail(email, props.devSignInDisplayName());
    establishSession(user, request, response);
    // Recorded as its own method, not as `local`: the audit trail should show which of these
    // sessions were asserted by Microsoft and which by a config line, and "the bypass was on
    // between these dates" is the question someone will actually ask later.
    logins.recordLogin(user.email(), user.name(), LoginRecord.DEV);

    log.warn("Dev sign-in used for {} — Entra bypass is active on this instance.", user.email());
    return ResponseEntity.ok(user);
  }

  /**
   * Ends the session.
   *
   * <p>This exists because {@code POST /api/auth/logout} was a 404 on any deployment not running
   * Entra. The route was configured only inside Spring Security's filter chain, and {@link
   * SecurityConfig} returns early — before {@code .logout(...)} is reached — whenever sign-in is
   * unusable. So in exactly the posture that has a dev stand-in and no Entra, signing out did
   * this: the browser POSTed, got a 404, {@code chatbot-auth.tsx} swallowed it in a bare
   * {@code catch}, cleared its own state and navigated to /login — where {@code /api/auth/me} was
   * asked again, the untouched cookie still named a valid session, and the redirect effect sent
   * the employee straight back into the app. The button looked like it worked and the session
   * outlived it.
   *
   * <p>A controller method rather than another security-chain entry, because it has to work in
   * both postures. Where the chain IS built its {@code LogoutFilter} matches this path first and
   * handles the request without reaching the dispatcher, so this is the open-mode path only —
   * the two do the same three things and cannot disagree about the outcome.
   */
  @PostMapping("/logout")
  public ResponseEntity<?> logout(HttpServletRequest request, HttpServletResponse response) {
    HttpSession session = request.getSession(false);
    if (session != null) session.invalidate();
    SecurityContextHolder.clearContext();

    // The session is already dead server-side, so this is only tidiness in the browser — but
    // without it the dead id is presented on every subsequent request and shows up in logs as
    // an authentication attempt.
    Cookie cleared = new Cookie("JSESSIONID", "");
    cleared.setPath("/");
    cleared.setMaxAge(0);
    cleared.setHttpOnly(true);
    response.addCookie(cleared);

    return ResponseEntity.ok(Map.of("ok", true));
  }

  /** Puts a resolved identity into a fresh session, for both non-Entra sign-in paths. */
  private void establishSession(
      SignedInUser user, HttpServletRequest request, HttpServletResponse response) {

    Authentication auth =
        new UsernamePasswordAuthenticationToken(
            user,
            null,
            List.of(new SimpleGrantedAuthority("ROLE_" + user.role().toUpperCase())));

    // A fresh session id: reusing the anonymous one across a privilege change is
    // textbook session fixation.
    request.getSession().invalidate();
    request.getSession(true);

    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(auth);
    SecurityContextHolder.setContext(context);
    new HttpSessionSecurityContextRepository()
        .saveContext(context, request, response);
  }

  @GetMapping("/me")
  public Me me() {
    boolean enforced = props.shouldSecure(ssoConfigured);
    SignedInUser user = currentUser.get().orElse(null);
    String devEmail = props.devSignInEmail();
    return new Me(
        user != null,
        enforced,
        enforced ? SecurityConfig.AUTHORIZATION_BASE_URI + "/" + REGISTRATION_ID : null,
        props.localLoginEnabled(ssoConfigured),
        devEmail != null,
        devEmail,
        user,
        permissionsFor(user));
  }

  /**
   * The signed-in account's permissions, break-glass included.
   *
   * <p>An address in {@code hr.auth.admin-emails} holds the console without an IAM assignment, so
   * reading roles alone would tell that account it has no access to a page it is about to open.
   * The audit permission is granted the same way: an emergency admin who cannot see the audit
   * trail cannot investigate the emergency.
   */
  private List<String> permissionsFor(SignedInUser user) {
    if (user == null) return List.of();
    if (props.admins().contains(user.email())) {
      // Everything, and derived from the catalogue rather than listed here — this used to name
      // its two permissions inline, which meant a new one would be enforced by PermissionChecker
      // for these accounts while this method told the console they did not have it, hiding a tab
      // they could in fact open. The break-glass list is all-or-nothing by design.
      List<String> all = new ArrayList<>();
      all.add(Permissions.ADMIN_CONSOLE);
      all.addAll(Permissions.AREAS);
      return List.copyOf(all);
    }
    return List.copyOf(iam.permissionsFor(Set.of(user.email())));
  }
}
