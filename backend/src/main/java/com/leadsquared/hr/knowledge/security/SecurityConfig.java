package com.leadsquared.hr.knowledge.security;

import com.leadsquared.hr.knowledge.audit.LoginTracker;
import com.leadsquared.hr.knowledge.iam.IamService;
import com.leadsquared.hr.knowledge.model.Permissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * Who may call what.
 *
 * <p>One chain, built one of two ways depending on whether an Entra client is
 * actually configured. A deployment should either require sign-in or visibly not
 * have it — never appear to authenticate while letting everything through — so the
 * decision is made once, here, and logged loudly either way.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

  /**
   * Where Microsoft sends the browser back with the authorization code.
   *
   * <p>Not Spring's default {@code /login/oauth2/code/*}: the Entra app
   * registration lists the NextAuth-style path, and the registered URI is the one
   * that has to win. The trailing wildcard is the registration id — {@code
   * azure-ad} — which is how the callback is matched back to its client config.
   */
  static final String CALLBACK_BASE_URI = "/api/auth/callback/*";

  /** Started by a full-page navigation from the login page. */
  public static final String AUTHORIZATION_BASE_URI = "/oauth2/authorization";

  /**
   * Reachable before there is a session.
   *
   * <p>The callback has to be public for the obvious reason: it is the request
   * that establishes the session. Requiring authentication for it would make
   * signing in depend on already being signed in.
   */
  private static final String[] PUBLIC = {
    "/oauth2/**",
    "/api/auth/callback/**",
    "/login/**",
    "/api/auth/me",
    "/api/auth/local",
    // TEMPORARY, and public for the same reason the callback is: it is the request that
    // establishes the session. It signs in as one configured address and 404s when that
    // property is unset, which is its state everywhere but one laptop. See AuthProperties.
    "/api/auth/dev",
    "/error"
  };

  /** Shorthand for the area gates below, so each rule reads as one line beside its URL. */
  private static PermissionAccess area(
      CurrentUser currentUser, PermissionChecker permissions, String permission) {
    return PermissionAccess.requiring(currentUser, permissions, permission);
  }

  @Bean
  SecurityFilterChain filterChain(
      HttpSecurity http,
      AuthProperties props,
      IamService iam,
      AdminConsoleAccess adminAccess,
      CurrentUser currentUser,
      PermissionChecker permissions,
      LoginTracker logins,
      ObjectProvider<ClientRegistrationRepository> clients)
      throws Exception {

    // Spring Boot only registers this when a client registration exists, so its
    // presence is the honest test of "is sign-in configured".
    boolean configured = clients.getIfAvailable() != null;

    if (!props.shouldSecure(configured)) {
      // Rule 3 of the Phase 1 privacy requirements: no employee data before a verified
      // identity. This branch serves every endpoint open, so on a deployment holding the
      // employee extract it is not a warning-worthy compromise — it is the breach. Refusing
      // to start is the only version of this check that cannot be ignored, and a warning in
      // a log nobody reads is exactly how an open deployment reaches production.
      //
      // Local development still works: leave `hr.auth.require` at its default of false and
      // the service runs open, as it did before Entra existed.
      if (props.requireAuthentication()) {
        throw new IllegalStateException(
            "hr.auth.require=true but sign-in is not usable ("
                + (configured ? "hr.auth.enabled=false" : "no client-id configured")
                + "). Refusing to start: this profile would serve every endpoint, including "
                + "employee data, without authentication. Configure Entra, or unset "
                + "hr.auth.require for local development.");
      }

      log.warn(
          "Entra sign-in is OFF ({}). Every endpoint is open and the frontend's demo login is in"
              + " use. Do not run this way anywhere real, and never with a loaded employee"
              + " extract — set hr.auth.require=true in any deployed profile to make this state"
              + " a startup failure.",
          configured ? "hr.auth.enabled=false" : "no client-id configured");

      http.authorizeHttpRequests(registry -> registry.anyRequest().permitAll())
          .csrf(csrf -> csrf.disable());
      return http.build();
    }

    // TEMPORARY, removed when the Entra registration is approved. The bypass leaves this chain
    // fully in force — it substitutes only who asserts the identity — but a deployed profile must
    // still never carry it, so the same switch that forbids running open forbids this too.
    String devSignIn = props.devSignInEmail();
    if (devSignIn != null) {
      if (props.requireAuthentication()) {
        throw new IllegalStateException(
            "hr.auth.require=true with hr.auth.dev-sign-in-as="
                + devSignIn
                + ". Refusing to start: that property signs anyone who can reach /api/auth/dev in"
                + " as that account without Microsoft. It is a local development stand-in for a"
                + " pending Entra registration — unset it in any deployed profile.");
      }
      log.warn(
          "TEMPORARY Entra bypass is ON: POST /api/auth/dev signs in as {} with no Microsoft"
              + " sign-in. Authorization is otherwise unchanged — the session, the role rules and"
              + " every endpoint's checks all still apply. Remove hr.auth.dev-sign-in-as once the"
              + " app registration is approved.",
          devSignIn);
    }

    log.info(
        "Entra sign-in is ON. Admin accounts: {}",
        props.admins().isEmpty() ? "none configured (everyone is an employee)" : props.admins());

    http.authorizeHttpRequests(
            registry ->
                registry
                    .requestMatchers(PUBLIC).permitAll()
                    // The admin console's own data. Employees are signed in, but
                    // the escalation queue and the ratings behind it are not
                    // theirs to read — a colleague's harassment report is in there.
                    //
                    // AdminConsoleAccess rather than hasRole("HR_ADMIN"): roles are
                    // managed in the console now, and hasRole reads authorities
                    // frozen at sign-in — so a revoked admin would keep the console
                    // until they next signed out. See that class.
                    // One area, one permission, each also requiring ADMIN_CONSOLE — see
                    // PermissionAccess. The console used to be a single grant, so maintaining the
                    // policy library and reading every harassment report were the same access.
                    //
                    // Path-level, which is what decides the granularity available here: these
                    // are whole-endpoint grants, not read-versus-write. A finer split would have
                    // to move to method-level checks on each controller.
                    .requestMatchers("/api/feedback/**")
                    .access(area(currentUser, permissions, Permissions.ADMIN_DIGEST))
                    .requestMatchers("/api/knowledge/**")
                    .access(area(currentUser, permissions, Permissions.ADMIN_KNOWLEDGE))
                    // Access management gates itself, or the first employee to find the endpoint
                    // grants themselves the console.
                    .requestMatchers("/api/iam/**")
                    .access(area(currentUser, permissions, Permissions.ADMIN_ACCESS))
                    // The variable pay plan. Its own gate because entering a revenue figure moves
                    // the computed payout for every non-sales employee at once.
                    .requestMatchers("/api/payroll/**")
                    .access(area(currentUser, permissions, Permissions.ADMIN_PAYROLL))
                    // The audit trail and the sign-in list. Listed before the /api/** rule below,
                    // because the first matching rule wins and a later, broader one would never
                    // be consulted.
                    .requestMatchers("/api/admin/**")
                    .access(area(currentUser, permissions, Permissions.ADMIN_AUDIT))
                    // Not listed: /api/tickets. Employees raise and read their own escalations
                    // there, so it cannot be gated by path — TicketController decides per request
                    // whether the caller sees the queue or only what they filed, and whether the
                    // sensitive ones are among them.
                    .requestMatchers("/api/**").authenticated()
                    // Anything else belongs to the Next.js app, not this service.
                    .anyRequest().permitAll())
        .oauth2Login(
            login ->
                login
                    // Must match the redirect-uri in application.yml, or Spring
                    // never sees the callback and Microsoft's code goes to a 404.
                    .redirectionEndpoint(redirection -> redirection.baseUri(CALLBACK_BASE_URI))
                    .userInfoEndpoint(
                        userInfo ->
                            userInfo.oidcUserService(new EntraOidcUserService(props, iam, logins)))
                    .defaultSuccessUrl(props.resolvedSuccessPath(), true))
        .logout(
            logout ->
                logout
                    .logoutRequestMatcher(new AntPathRequestMatcher("/api/auth/logout", "POST"))
                    .logoutSuccessHandler(
                        (request, response, authentication) -> {
                          response.setStatus(HttpStatus.OK.value());
                          response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                          response.getWriter().write("{\"ok\":true}");
                        })
                    .invalidateHttpSession(true)
                    .deleteCookies("JSESSIONID"))
        .exceptionHandling(
            handling ->
                handling
                    // An expired session on a background fetch has to come back as
                    // 401 JSON. The default is a 302 to Microsoft, which fetch()
                    // follows and then fails to parse as JSON — the employee would
                    // see a broken chat rather than a request to sign in again.
                    .defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        new AntPathRequestMatcher("/api/**"))
                    .accessDeniedHandler(
                        (request, response, denied) -> {
                          response.setStatus(HttpStatus.FORBIDDEN.value());
                          response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                          response
                              .getWriter()
                              .write("{\"error\":\"Your account does not have HR admin access.\"}");
                        }))
        // CSRF tokens are not threaded through the frontend's fetches yet. The
        // session cookie is SameSite=Lax (see application.yml), so another origin
        // cannot make the browser attach it to a POST — the attack these tokens
        // defend against. Worth doing properly before this leaves the office
        // network: a token endpoint, plus a header on every mutating call in
        // lib/hr-api.ts and lib/knowledge/api.ts.
        .csrf(csrf -> csrf.disable());

    return http.build();
  }
}
