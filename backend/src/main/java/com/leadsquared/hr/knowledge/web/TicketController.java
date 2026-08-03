package com.leadsquared.hr.knowledge.web;

import com.leadsquared.hr.knowledge.model.Ticket;
import com.leadsquared.hr.knowledge.security.CurrentUser;
import com.leadsquared.hr.knowledge.security.SignedInUser;
import com.leadsquared.hr.knowledge.tickets.TicketService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Escalation tickets.
 *
 * <p>Response shapes are a contract with {@code lib/hr-store.ts}: the chat window
 * creates tickets here and the admin dashboard lists and re-statuses them.
 */
@RestController
@RequestMapping("/api/tickets")
public class TicketController {

  private final TicketService tickets;
  private final CurrentUser currentUser;

  public TicketController(TicketService tickets, CurrentUser currentUser) {
    this.tickets = tickets;
    this.currentUser = currentUser;
  }

  public record TicketList(List<Ticket> tickets) {}

  /**
   * The whole queue for an HR admin; only your own escalations otherwise.
   *
   * <p>An employee reading the full list would be reading their colleagues'
   * escalations — including the confidential ones, whose entire purpose is that
   * they are not visible to people who are not handling them.
   */
  @GetMapping
  public TicketList list() {
    SignedInUser user = currentUser.get().orElse(null);

    // No user at all means sign-in is not configured (the open chain); behave as
    // this endpoint did before, rather than returning an empty queue that would
    // look like "no escalations" on the dashboard.
    if (user == null || user.isAdmin()) {
      return new TicketList(tickets.list());
    }

    String email = user.email();
    return new TicketList(
        tickets.list().stream().filter(t -> email != null && email.equalsIgnoreCase(t.raisedBy())).toList());
  }

  /**
   * The client supplies the id it already showed the employee. A collision means
   * two tickets would share a number, so it is refused rather than overwritten —
   * losing someone's escalation to a silent overwrite is the worst outcome here.
   */
  @PostMapping
  public ResponseEntity<?> create(@RequestBody TicketService.CreateTicket body) {
    if (body.id() != null && !body.id().isBlank() && tickets.exists(body.id())) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(Map.of("error", "A ticket with that id already exists."));
    }

    // Attribution comes from the session, not the body. HR acts on these — a
    // ticket that names the wrong employee sends someone to talk to the wrong
    // person about their pay. The client's own values are ignored when signed in.
    TicketService.CreateTicket attributed =
        currentUser
            .get()
            .map(
                user ->
                    new TicketService.CreateTicket(
                        body.id(),
                        body.query(),
                        body.reason(),
                        user.email(),
                        user.name(),
                        body.route(),
                        body.sensitive(),
                        body.tags(),
                        body.confidence(),
                        body.transcript(),
                        body.channel()))
            .orElse(body);

    return ResponseEntity.status(HttpStatus.CREATED).body(tickets.create(attributed));
  }

  public record StatusChange(String status) {}

  @PatchMapping("/{id}/status")
  public ResponseEntity<?> updateStatus(
      @PathVariable String id, @RequestBody StatusChange body) {

    return tickets
        .updateStatus(id, body.status())
        .<ResponseEntity<?>>map(ResponseEntity::ok)
        .orElseGet(
            () ->
                ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "No such ticket.")));
  }
}
