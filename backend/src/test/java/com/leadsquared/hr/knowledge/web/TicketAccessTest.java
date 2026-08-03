package com.leadsquared.hr.knowledge.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leadsquared.hr.knowledge.model.Ticket;
import com.leadsquared.hr.knowledge.model.TicketComment;
import com.leadsquared.hr.knowledge.security.CurrentUser;
import com.leadsquared.hr.knowledge.security.SignedInUser;
import com.leadsquared.hr.knowledge.tickets.TicketService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Who may read and write a ticket.
 *
 * <p>The reason this file is thorough: a ticket holds an employee's pay dispute or
 * their harassment report, the ids are short, and before this change the status
 * endpoint applied any id from any signed-in caller. Every rule below is one that
 * was either absent or is newly load-bearing now that threads exist.
 */
class TicketAccessTest {

  private static final String OWNER = "ananya@leadsquared.com";
  private static final String OTHER = "someone.else@leadsquared.com";
  private static final String ADMIN = "hr.admin@leadsquared.com";

  private TicketService tickets;
  private CurrentUser currentUser;
  private MockMvc mvc;

  private static Ticket ticket(boolean confidential) {
    return new Ticket(
        "HR-9B71D",
        "My relocation allowance was missing from payroll",
        "Out of scope",
        OWNER,
        confidential ? "Withheld (confidential)" : "Ananya Sharma",
        "2026-08-01T00:00:00Z",
        "open",
        "normal",
        "hr_ops",
        "HR Ops Queue",
        List.of(),
        0.18,
        List.of(),
        confidential,
        "HR Portal",
        List.of());
  }

  @BeforeEach
  void setUp() {
    tickets = Mockito.mock(TicketService.class);
    currentUser = Mockito.mock(CurrentUser.class);
    mvc = MockMvcBuilders.standaloneSetup(new TicketController(tickets, currentUser)).build();
  }

  private void signedInAs(String email, boolean admin) {
    when(currentUser.get())
        .thenReturn(
            Optional.of(
                new SignedInUser(
                    email,
                    email,
                    "Someone",
                    admin ? SignedInUser.HR_ADMIN : SignedInUser.EMPLOYEE)));
  }

  // -------------------------------------------------------------------------
  // Reading one ticket
  // -------------------------------------------------------------------------

  @Test
  void theOwnerCanReadTheirOwnTicket() throws Exception {
    signedInAs(OWNER, false);
    when(tickets.find("HR-9B71D")).thenReturn(Optional.of(ticket(false)));

    mvc.perform(get("/api/tickets/HR-9B71D"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("HR-9B71D"));
  }

  @Test
  void anAdminCanReadAnybodysTicket() throws Exception {
    signedInAs(ADMIN, true);
    when(tickets.find("HR-9B71D")).thenReturn(Optional.of(ticket(false)));

    mvc.perform(get("/api/tickets/HR-9B71D")).andExpect(status().isOk());
  }

  /**
   * 404 and not 403, deliberately. A 403 confirms the id exists, and these ids are
   * short — enough to enumerate which colleagues have raised confidential matters,
   * which is the one thing confidentiality here exists to prevent.
   */
  @Test
  void someoneElsesTicketIsReportedAsAbsentRatherThanForbidden() throws Exception {
    signedInAs(OTHER, false);
    when(tickets.find("HR-9B71D")).thenReturn(Optional.of(ticket(false)));

    mvc.perform(get("/api/tickets/HR-9B71D")).andExpect(status().isNotFound());
  }

  @Test
  void ownershipIsCaseInsensitive() throws Exception {
    // Entra can present the same account with different capitalisation than the one
    // stored on the ticket; a case-sensitive compare would hide someone's own ticket.
    signedInAs("Ananya@LeadSquared.com", false);
    when(tickets.find("HR-9B71D")).thenReturn(Optional.of(ticket(false)));

    mvc.perform(get("/api/tickets/HR-9B71D")).andExpect(status().isOk());
  }

  // -------------------------------------------------------------------------
  // Commenting
  // -------------------------------------------------------------------------

  @Test
  void hrCommentsAreStampedAsHr() throws Exception {
    signedInAs(ADMIN, true);
    when(tickets.find("HR-9B71D")).thenReturn(Optional.of(ticket(false)));
    when(tickets.addComment(any(), any(), any(), any(), any()))
        .thenReturn(Optional.of(ticket(false)));

    mvc.perform(
            post("/api/tickets/HR-9B71D/comments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"Which month was this?\"}"))
        .andExpect(status().isOk());

    verify(tickets)
        .addComment(eq("HR-9B71D"), eq("Which month was this?"), eq(ADMIN), any(), eq(TicketComment.HR));
  }

  @Test
  void theOwnerCanReplyAndIsStampedAsTheEmployee() throws Exception {
    signedInAs(OWNER, false);
    when(tickets.find("HR-9B71D")).thenReturn(Optional.of(ticket(false)));
    when(tickets.addComment(any(), any(), any(), any(), any()))
        .thenReturn(Optional.of(ticket(false)));

    mvc.perform(
            post("/api/tickets/HR-9B71D/comments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"June payroll.\"}"))
        .andExpect(status().isOk());

    verify(tickets)
        .addComment(eq("HR-9B71D"), eq("June payroll."), eq(OWNER), any(), eq(TicketComment.EMPLOYEE));
  }

  @Test
  void anEmployeeCannotCommentOnSomeoneElsesTicket() throws Exception {
    signedInAs(OTHER, false);
    when(tickets.find("HR-9B71D")).thenReturn(Optional.of(ticket(false)));

    mvc.perform(
            post("/api/tickets/HR-9B71D/comments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"let me see this\"}"))
        .andExpect(status().isNotFound());

    verify(tickets, never()).addComment(any(), any(), any(), any(), any());
  }

  /** The role is decided from the session, never from anything the caller sends. */
  @Test
  void anEmployeeCannotPassThemselvesOffAsHr() throws Exception {
    signedInAs(OWNER, false);
    when(tickets.find("HR-9B71D")).thenReturn(Optional.of(ticket(false)));
    when(tickets.addComment(any(), any(), any(), any(), any()))
        .thenReturn(Optional.of(ticket(false)));

    mvc.perform(
            post("/api/tickets/HR-9B71D/comments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"hi\",\"authorRole\":\"hr\",\"author\":\"hr@leadsquared.com\"}"))
        .andExpect(status().isOk());

    verify(tickets)
        .addComment(any(), eq("hi"), eq(OWNER), any(), eq(TicketComment.EMPLOYEE));
  }

  // -------------------------------------------------------------------------
  // Status — the hole this change closed
  // -------------------------------------------------------------------------

  /**
   * Previously this endpoint took any id from any signed-in caller and applied it, so
   * an employee could resolve a colleague's escalation, or reopen their own after HR
   * had closed it, by guessing a ticket number.
   */
  @Test
  void anEmployeeCannotRestatusSomeoneElsesTicket() throws Exception {
    signedInAs(OTHER, false);

    mvc.perform(
            patch("/api/tickets/HR-9B71D/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"resolved\"}"))
        .andExpect(status().isForbidden());

    verify(tickets, never()).updateStatus(any(), any());
  }

  @Test
  void anEmployeeCannotEvenRestatusTheirOwnTicket() throws Exception {
    // Whether the matter is handled is HR's judgement, not the raiser's.
    signedInAs(OWNER, false);

    mvc.perform(
            patch("/api/tickets/HR-9B71D/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"resolved\"}"))
        .andExpect(status().isForbidden());

    verify(tickets, never()).updateStatus(any(), any());
  }

  @Test
  void hrCanRestatus() throws Exception {
    signedInAs(ADMIN, true);
    when(tickets.updateStatus("HR-9B71D", "resolved")).thenReturn(Optional.of(ticket(false)));

    mvc.perform(
            patch("/api/tickets/HR-9B71D/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"resolved\"}"))
        .andExpect(status().isOk());
  }

  /** With no sign-in configured at all the service runs open, as it did before Entra. */
  @Test
  void withNoSessionAtAllTheServiceStillWorks() throws Exception {
    when(currentUser.get()).thenReturn(Optional.empty());
    when(tickets.updateStatus("HR-9B71D", "resolved")).thenReturn(Optional.of(ticket(false)));

    mvc.perform(
            patch("/api/tickets/HR-9B71D/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"resolved\"}"))
        .andExpect(status().isOk());
  }

  // -------------------------------------------------------------------------
  // Listing
  // -------------------------------------------------------------------------

  @Test
  void anEmployeeSeesOnlyTheirOwnTicketsInTheList() throws Exception {
    signedInAs(OWNER, false);

    Ticket mine = ticket(false);
    Ticket theirs =
        new Ticket(
            "SEN-4C2A1", "something else", "", OTHER, "Withheld (confidential)",
            "2026-08-02T00:00:00Z", "open", "critical", "hr_head", "Priya Nair",
            List.of(), 1.0, List.of(), true, "HR Portal", List.of());

    when(tickets.list()).thenReturn(List.of(mine, theirs));

    mvc.perform(get("/api/tickets"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tickets.length()").value(1))
        .andExpect(jsonPath("$.tickets[0].id").value("HR-9B71D"));
  }

  @Test
  void confidentialityDoesNotHideATicketFromItsOwnRaiser() throws Exception {
    // The name is withheld from HR's view; the person who raised it still needs the
    // thread, or they can never read the reply.
    signedInAs(OWNER, false);
    when(tickets.find("HR-9B71D")).thenReturn(Optional.of(ticket(true)));

    mvc.perform(get("/api/tickets/HR-9B71D")).andExpect(status().isOk());
  }

  @Test
  void aMissingTicketIsANotFound() throws Exception {
    signedInAs(OWNER, false);
    when(tickets.find("NOPE")).thenReturn(Optional.empty());

    mvc.perform(get("/api/tickets/NOPE")).andExpect(status().isNotFound());
  }

  @Test
  void theTicketIdSurvivesPathBindingIntact() throws Exception {
    signedInAs(ADMIN, true);
    when(tickets.find(any())).thenReturn(Optional.of(ticket(false)));

    mvc.perform(get("/api/tickets/SEN-4C2A1")).andExpect(status().isOk());
    verify(tickets).find("SEN-4C2A1");
  }
}
