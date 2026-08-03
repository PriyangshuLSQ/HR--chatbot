package com.leadsquared.hr.knowledge.web;

import com.leadsquared.hr.knowledge.model.Ticket;
import com.leadsquared.hr.knowledge.model.TicketComment;
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

  /**
   * One ticket, for the employee's own thread view or the admin console.
   *
   * <p>404 rather than 403 when someone asks for a ticket that is not theirs. A 403
   * would confirm the id exists, and ticket ids are short and sequential-ish — that
   * is enough to enumerate which colleagues have raised confidential matters, which
   * is exactly what confidentiality here is protecting.
   */
  @GetMapping("/{id}")
  public ResponseEntity<?> one(@PathVariable String id) {
    return tickets
        .find(id)
        .filter(this::mayRead)
        .<ResponseEntity<?>>map(ResponseEntity::ok)
        .orElseGet(() -> notFound());
  }

  public record NewComment(String body) {}

  /**
   * Adds a message to the thread.
   *
   * <p>HR may comment on anything; an employee only on their own ticket, and their
   * message is stamped as theirs regardless of what the request says. Both
   * directions are open on purpose — a thread where only one side can write is not a
   * conversation, and HR asking "which month was this?" needs somewhere for the
   * answer to go.
   */
  @PostMapping("/{id}/comments")
  public ResponseEntity<?> comment(
      @PathVariable String id, @RequestBody(required = false) NewComment body) {

    if (body == null) return ApiErrors.badRequest("Expected a JSON body.");

    SignedInUser user = currentUser.get().orElse(null);
    Ticket ticket = tickets.find(id).orElse(null);

    // Same reasoning as above: an unreadable ticket is reported as absent.
    if (ticket == null || !mayRead(ticket)) return notFound();

    boolean asHr = user == null || user.isAdmin();
    String role = asHr ? TicketComment.HR : TicketComment.EMPLOYEE;

    return tickets
        .addComment(
            id,
            body.body(),
            user == null ? "hr@leadsquared.com" : user.email(),
            user == null ? "HR" : user.name(),
            role)
        .<ResponseEntity<?>>map(ResponseEntity::ok)
        .orElseGet(() -> notFound());
  }

  public record StatusChange(String status) {}

  /**
   * Re-statuses a ticket. HR only.
   *
   * <p>This used to take any id from any signed-in caller and apply it, so an
   * employee could have resolved a colleague's escalation — or reopened their own
   * after HR closed it — by guessing a ticket number. Closing an escalation is HR's
   * judgement about whether the matter is handled, so it stays with HR.
   */
  @PatchMapping("/{id}/status")
  public ResponseEntity<?> updateStatus(
      @PathVariable String id, @RequestBody StatusChange body) {

    if (!isHr()) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN)
          .body(Map.of("error", "Only HR can change a ticket's status."));
    }

    return tickets
        .updateStatus(id, body.status())
        .<ResponseEntity<?>>map(ResponseEntity::ok)
        .orElseGet(() -> notFound());
  }

  // -------------------------------------------------------------------------
  // Access
  // -------------------------------------------------------------------------

  /**
   * A null user means sign-in is not configured at all (the open filter chain), and
   * this service then behaves as it did before Entra: everything is readable. That is
   * the same allowance {@link #list()} makes, kept in one place.
   */
  private boolean isHr() {
    SignedInUser user = currentUser.get().orElse(null);
    return user == null || user.isAdmin();
  }

  private boolean mayRead(Ticket ticket) {
    SignedInUser user = currentUser.get().orElse(null);
    if (user == null || user.isAdmin()) return true;
    return ticket.isOwnedBy(user.email());
  }

  private static ResponseEntity<?> notFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "No such ticket."));
  }
}
