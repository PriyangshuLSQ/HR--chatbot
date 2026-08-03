package com.leadsquared.hr.knowledge.web;

import com.leadsquared.hr.knowledge.security.AuthProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
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

  /**
   * Matches the registration key in application.yml and the last segment of the
   * registered callback URI. Those three have to agree.
   */
  private static final String REGISTRATION_ID = "azure-ad";

  /** Deliberately permissive — this is a shape check, not identity verification. */
  private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s.]+\\.[^@\\s]+$");

  private final CurrentUser currentUser;
  private final AuthProperties props;
  private final boolean ssoConfigured;

  public AuthController(
      CurrentUser currentUser,
      AuthProperties props,
      ObjectProvider<ClientRegistrationRepository> clients) {
    this.currentUser = currentUser;
    this.props = props;
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
      SignedInUser user) {}

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

    return ResponseEntity.ok(user);
  }

  @GetMapping("/me")
  public Me me() {
    boolean enforced = props.shouldSecure(ssoConfigured);
    return new Me(
        currentUser.get().isPresent(),
        enforced,
        enforced ? SecurityConfig.AUTHORIZATION_BASE_URI + "/" + REGISTRATION_ID : null,
        props.localLoginEnabled(ssoConfigured),
        currentUser.get().orElse(null));
  }
}
