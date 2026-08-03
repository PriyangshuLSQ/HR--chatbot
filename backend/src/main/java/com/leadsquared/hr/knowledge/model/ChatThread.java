package com.leadsquared.hr.knowledge.model;

import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One employee's conversation, so context survives a reload — and a device
 * change, which localStorage never did.
 *
 * <p>Threads are scoped to {@code owner} and never returned across owners: an
 * employee asking about a garnished salary or a grievance has a reasonable
 * expectation that only they and HR see it.
 *
 * @param owner the employee's email, from the session
 * @param updatedAt epoch millis — the chat sidebar sorts newest-first on this,
 *     and it is a number rather than an ISO string because that is what the
 *     existing UI compares
 */
@Document(collection = "threads")
public record ChatThread(
    @Id String id,
    @Indexed String owner,
    String title,
    long updatedAt,
    List<ThreadMessage> messages) {

  /**
   * A single chat bubble.
   *
   * <p>{@code turn} is the bot's full structured answer — citations, data cards,
   * suggested follow-ups. It is held as an opaque object rather than a mirrored
   * Java type: the frontend is the only thing that interprets it, and porting
   * that whole tree here would create two definitions to keep in step for no
   * gain. Mongo stores it as a nested document and hands it back unchanged.
   */
  public record ThreadMessage(
      String id, String role, String text, long at, Object turn, String sourceQuery) {}
}
