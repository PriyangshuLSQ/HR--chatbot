package com.leadsquared.hr.knowledge.security;

import com.leadsquared.hr.knowledge.iam.IamService;
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
    "/oauth2/**", "/api/auth/callback/**", "/login/**", "/api/auth/me", "/api/auth/local", "/error"
  };

  @Bean
  SecurityFilterChain filterChain(
      HttpSecurity http,
      AuthProperties props,
      IamService iam,
      AdminConsoleAccess adminAccess,
      ObjectProvider<ClientRegistrationRepository> clients)
      throws Exception {

    // Spring Boot only registers this when a client registration exists, so its
    // presence is the honest test of "is sign-in configured".
    boolean configured = clients.getIfAvailable() != null;

    if (!props.shouldSecure(configured)) {
      log.warn(
          "Entra sign-in is OFF ({}). Every endpoint is open and the frontend's demo login is in"
              + " use. Do not run this way anywhere real.",
          configured ? "hr.auth.enabled=false" : "no client-id configured");

      http.authorizeHttpRequests(registry -> registry.anyRequest().permitAll())
          .csrf(csrf -> csrf.disable());
      return http.build();
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
                    .requestMatchers("/api/feedback/**").access(adminAccess)
                    .requestMatchers("/api/knowledge/**").access(adminAccess)
                    // Access management is itself admin-only, or the first employee
                    // to find the endpoint grants themselves the console.
                    .requestMatchers("/api/iam/**").access(adminAccess)
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
                        userInfo -> userInfo.oidcUserService(new EntraOidcUserService(props, iam)))
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
