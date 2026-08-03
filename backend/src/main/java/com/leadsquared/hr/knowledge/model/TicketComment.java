package com.leadsquared.hr.knowledge.model;

/**
 * One message on a ticket thread.
 *
 * <p>Embedded on the {@link Ticket} rather than kept in its own collection. A thread
 * is only ever read as a whole, in order, alongside the ticket it belongs to — so a
 * separate collection would buy a second query and a sort for nothing. It also means
 * a comment cannot outlive the ticket it was written on.
 *
 * <p>Field names are the wire contract with {@code lib/hr-store.ts}.
 *
 * @param authorRole {@code hr} or {@code employee}. Stored rather than derived at
 *     read time: whether the person who wrote this was acting as HR is a fact about
 *     the moment they wrote it, and recomputing it later would silently relabel
 *     history when someone's access changes.
 * @param authorName what the thread shows. Withheld on a confidential ticket for the
 *     employee's own messages, exactly as {@link Ticket#raisedByName()} is.
 */
public record TicketComment(
    String id,
    String body,
    String author,
    String authorName,
    String authorRole,
    String createdAt) {

  public static final String HR = "hr";
  public static final String EMPLOYEE = "employee";

  public boolean isFromHr() {
    return HR.equals(authorRole);
  }
}
