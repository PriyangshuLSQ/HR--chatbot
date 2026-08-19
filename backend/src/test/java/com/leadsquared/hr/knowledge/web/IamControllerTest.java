package com.leadsquared.hr.knowledge.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leadsquared.hr.knowledge.audit.AdminAuditService;
import com.leadsquared.hr.knowledge.iam.IamService;
import com.leadsquared.hr.knowledge.security.CurrentUser;
import com.leadsquared.hr.knowledge.security.SignedInUser;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Request binding and the one guard that lives in the controller.
 *
 * <p>Standalone rather than a sliced Spring context: what is under test here is how
 * a URL binds to a method, not the filter chain. Authorization is asserted where it
 * is implemented — see {@code IamAccessTest}.
 */
class IamControllerTest {

  private IamService iam;
  private CurrentUser currentUser;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    iam = Mockito.mock(IamService.class);
    currentUser = Mockito.mock(CurrentUser.class);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new IamController(iam, currentUser, Mockito.mock(AdminAuditService.class)))
            .build();
  }

  private void signedInAs(String email) {
    when(currentUser.get())
        .thenReturn(Optional.of(new SignedInUser(email, email, "Someone", SignedInUser.HR_ADMIN)));
  }

  /**
   * An email is a path variable ending in {@code .com}, which is exactly the shape
   * older Spring would have truncated as a file extension — deleting
   * {@code someone@corp.com} would have called the service with
   * {@code someone@corp}, found no record, and reported "no access record" for
   * someone plainly on the list.
   */
  @Test
  void theWholeEmailReachesTheServiceIncludingTheTld() throws Exception {
    signedInAs("admin@leadsquared.com");

    mvc.perform(delete("/api/iam/users/someone@corp.com")).andExpect(status().isOk());

    verify(iam).deleteUser("someone@corp.com");
  }

  @Test
  void aDottedSubdomainSurvivesToo() throws Exception {
    signedInAs("admin@leadsquared.com");

    mvc.perform(delete("/api/iam/users/first.last@mail.corp.co.in")).andExpect(status().isOk());

    verify(iam).deleteUser("first.last@mail.corp.co.in");
  }

  /**
   * The one mistake in this console that cannot be undone from the console: an admin
   * who revokes their own access loses the page they would need to put it back.
   */
  @Test
  void revokingYourOwnAccessIsRefused() throws Exception {
    signedInAs("admin@leadsquared.com");

    mvc.perform(delete("/api/iam/users/admin@leadsquared.com"))
        .andExpect(status().isBadRequest());

    verify(iam, never()).deleteUser(any());
  }

  @Test
  void theSelfCheckIgnoresCase() throws Exception {
    signedInAs("admin@leadsquared.com");

    mvc.perform(delete("/api/iam/users/Admin@LeadSquared.com"))
        .andExpect(status().isBadRequest());

    verify(iam, never()).deleteUser(any());
  }

  @Test
  void aRoleNameBindsFromThePathToo() throws Exception {
    signedInAs("admin@leadsquared.com");

    mvc.perform(delete("/api/iam/roles/payroll-viewer")).andExpect(status().isOk());

    verify(iam).deleteRole("payroll-viewer");
  }
}
