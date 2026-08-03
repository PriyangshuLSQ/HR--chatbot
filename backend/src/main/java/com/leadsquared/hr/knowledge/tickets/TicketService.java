package com.leadsquared.hr.knowledge.tickets;

import com.leadsquared.hr.knowledge.model.Ticket;
import com.leadsquared.hr.knowledge.model.TranscriptTurn;
import com.leadsquared.hr.knowledge.store.TicketRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Creates, lists and re-statuses escalations. */
@Service
public class TicketService {

  /** Enough context for HR to understand the ask; not the employee's whole day. */
  private static final int TRANSCRIPT_TURNS = 6;

  private final TicketRepository tickets;

  public TicketService(TicketRepository tickets) {
    this.tickets = tickets;
  }

  /**
   * What the chat window sends. Routing fields are absent by design — the client
   * does not get to choose its own priority or assignee.
   */
  public record CreateTicket(
      String id,
      String query,
      String reason,
      String raisedBy,
      String raisedByName,
      String route,
      boolean sensitive,
      List<String> tags,
      double confidence,
      List<TranscriptTurn> transcript,
      String channel) {}

  public List<Ticket> list() {
    return tickets.findAllByOrderByCreatedAtDesc();
  }

  public Optional<Ticket> find(String id) {
    return tickets.findById(id);
  }

  public boolean exists(String id) {
    return tickets.existsById(id);
  }

  /**
   * @throws IllegalArgumentException if the query is blank — a ticket with no
   *     question in it is not actionable, and silently storing one would put an
   *     empty row in front of HR
   */
  public Ticket create(CreateTicket input) {
    String query = input.query() == null ? "" : input.query().trim();
    if (query.isBlank()) {
      throw new IllegalArgumentException("A ticket needs the employee's question.");
    }

    Routing.Decision routing = Routing.decide(input.route(), input.sensitive());

    List<TranscriptTurn> transcript =
        input.transcript() == null ? List.of() : trailing(input.transcript(), TRANSCRIPT_TURNS);

    List<String> tags =
        input.tags() == null
            ? List.of()
            : List.copyOf(new LinkedHashSet<>(input.tags()));

    Ticket ticket =
        new Ticket(
            input.id() == null || input.id().isBlank() ? generateId(input.sensitive()) : input.id(),
            query,
            input.reason() == null ? "" : input.reason(),
            input.raisedBy(),
            // A confidential ticket must not carry a name even if the client sent
            // one — the routing decision and the stored record agree on this.
            routing.confidential() ? "Withheld (confidential)" : input.raisedByName(),
            Instant.now().toString(),
            "open",
            routing.priority(),
            routing.route(),
            routing.assignee(),
            tags,
            input.confidence(),
            transcript,
            routing.confidential(),
            input.channel() == null ? "HR Portal" : input.channel());

    return tickets.save(ticket);
  }

  /**
   * @return the updated ticket, or empty if there is no such id
   * @throws IllegalArgumentException on a status the dashboard cannot render
   */
  public Optional<Ticket> updateStatus(String id, String status) {
    if (!Routing.STATUSES.contains(status)) {
      throw new IllegalArgumentException(
          "Status must be one of open, in-progress or resolved.");
    }
    return tickets.findById(id).map(t -> tickets.save(t.withStatus(status)));
  }

  private static <T> List<T> trailing(List<T> all, int n) {
    return all.size() <= n ? List.copyOf(all) : new ArrayList<>(all.subList(all.size() - n, all.size()));
  }

  /**
   * Fallback id for a caller that did not bring one. The chat window generates
   * its own so the employee sees a number without waiting for the round trip;
   * this keeps the same shape for anything posting directly to the API.
   */
  private static String generateId(boolean sensitive) {
    String suffix = Long.toString(System.currentTimeMillis(), 36).toUpperCase();
    return (sensitive ? "SEN-" : "HR-") + suffix.substring(Math.max(0, suffix.length() - 5));
  }
}
