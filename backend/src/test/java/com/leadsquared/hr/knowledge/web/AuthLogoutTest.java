package com.leadsquared.hr.knowledge.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leadsquared.hr.knowledge.audit.LoginTracker;
import com.leadsquared.hr.knowledge.iam.IamService;
import com.leadsquared.hr.knowledge.security.AuthProperties;
import com.leadsquared.hr.knowledge.security.CurrentUser;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Signing out has to end the session in every auth posture.
 *
 * <p>This test exists because it did not. {@code POST /api/auth/logout} was registered only
 * inside Spring Security's filter chain, and {@code SecurityConfig} returns early — before the
 * {@code .logout(...)} call — whenever sign-in is unusable. On the deployment that had a dev
 * stand-in and no Entra, the route was therefore a 404, and the browser's bare {@code catch}
 * reported nothing: the button cleared local state, navigated to /login, {@code /api/auth/me}
 * was asked again, the untouched cookie still named a live session, and the employee was
 * redirected back into the app they had just left.
 *
 * <p>Standalone MockMvc, so no filter chain is present — which is precisely the posture in
 * question. If this passes, the endpoint works without Spring Security's help.
 */
class AuthLogoutTest {

  private MockMvc mvc;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    ObjectProvider<org.springframework.security.oauth2.client.registration.ClientRegistrationRepository>
        clients = Mockito.mock(ObjectProvider.class);
    Mockito.when(clients.getIfAvailable()).thenReturn(null); // no Entra: the broken posture

    mvc =
        MockMvcBuilders.standaloneSetup(
                new AuthController(
                    Mockito.mock(CurrentUser.class),
                    new AuthProperties(true, false, "", "/chat"),
                    Mockito.mock(IamService.class),
                    Mockito.mock(LoginTracker.class),
                    clients))
            .build();
  }

  @Test
  @DisplayName("Logout is routed at all when Spring Security's chain is not built")
  void theRouteExists() throws Exception {
    mvc.perform(post("/api/auth/logout")).andExpect(status().isOk());
  }

  @Test
  @DisplayName("Logout invalidates the session that carried the identity")
  void theSessionIsInvalidated() throws Exception {
    MockHttpSession session = new MockHttpSession();
    assertThat(session.isInvalid()).isFalse();

    mvc.perform(post("/api/auth/logout").session(session)).andExpect(status().isOk());

    assertThat(session.isInvalid())
        .as("the session must be dead server-side, not merely forgotten by the browser")
        .isTrue();
  }

  @Test
  @DisplayName("Logout expires the session cookie")
  void theCookieIsCleared() throws Exception {
    MvcResult result =
        mvc.perform(post("/api/auth/logout").session(new MockHttpSession()))
            .andExpect(status().isOk())
            .andReturn();

    Cookie cookie = result.getResponse().getCookie("JSESSIONID");
    assertThat(cookie).as("a Set-Cookie clearing JSESSIONID").isNotNull();
    assertThat(cookie.getMaxAge()).as("max-age=0 expires it immediately").isZero();
    assertThat(cookie.getValue()).isEmpty();
  }

  @Test
  @DisplayName("Logging out without a session is not an error")
  void noSessionIsFine() throws Exception {
    // The sign-out button is reachable from a stale tab whose session already expired.
    mvc.perform(post("/api/auth/logout")).andExpect(status().isOk());
  }
}
