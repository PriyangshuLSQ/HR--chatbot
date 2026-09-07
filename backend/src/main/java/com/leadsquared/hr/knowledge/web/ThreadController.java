package com.leadsquared.hr.knowledge.web;

import com.leadsquared.hr.knowledge.model.ChatThread;
import com.leadsquared.hr.knowledge.security.CurrentUser;
import com.leadsquared.hr.knowledge.store.ThreadRepository;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Chat history, so a conversation survives a reload and follows the employee to
 * another device.
 *
 * <p>The owner comes from the Entra session and nowhere else. It used to be a
 * request parameter, which meant anyone who could reach this service could name
 * any owner and read that person's conversations — someone asking about a
 * garnished salary or a grievance has a reasonable expectation that they cannot.
 * There is no way to ask for another employee's threads through this controller,
 * including for an HR admin.
 */
@RestController
@RequestMapping("/api/threads")
public class ThreadController {

  /** Matches the cap the chat sidebar already applied. */
  private static final int MAX_THREADS = 20;

  private final ThreadRepository threads;
  private final CurrentUser currentUser;

  public ThreadController(ThreadRepository threads, CurrentUser currentUser) {
    this.threads = threads;
    this.currentUser = currentUser;
  }

  public record ThreadList(List<ChatThread> threads) {}

  @GetMapping
  public ThreadList list() {
    String owner = currentUser.require().email();
    // Newest MAX_THREADS only. The collection now holds more than this — sync stopped
    // deleting — but the sidebar shows a fixed window and the client would otherwise
    // download an entire history to render it.
    return new ThreadList(
        threads.findByOwnerOrderByUpdatedAtDesc(owner, PageRequest.of(0, MAX_THREADS)));
  }

  /** The client sends only its threads; who they belong to is not its call. */
  public record ThreadSync(List<ChatThread> threads) {}

  /**
   * Upserts the threads the client is holding. Anything it is not holding is left alone.
   *
   * <p><b>This used to delete first, and that was destroying chat history.</b> The old
   * implementation called {@code deleteByOwner} and then saved the incoming list, which the client
   * caps at 20 ({@code threads.slice(0, 20)} in the chat page). So conversation 21 was not aged
   * out of the sidebar — it was erased from the database. Measuring conversation volume is what
   * exposed it: a Tuesday that had already happened kept shrinking, 14 threads to 12 to 10, as
   * new conversations were started on the Friday.
   *
   * <p>Saving without deleting keeps the client's cap as what it reads like — a display window —
   * while the server retains the history behind it. {@code save} is an upsert on {@code @Id}, so
   * an edited title or a new turn still overwrites the stored copy; only the deletion is gone.
   *
   * <p>Nothing calls for a delete: the chat UI has no way to remove a conversation. If one is
   * added, it needs an explicit endpoint that names the thread — not an absence in a sync body,
   * which is indistinguishable from a client that simply has not loaded it.
   */
  @PutMapping
  public ResponseEntity<?> sync(@RequestBody ThreadSync body) {
    String owner = currentUser.require().email();

    List<ChatThread> incoming = body.threads() == null ? List.of() : body.threads();
    List<ChatThread> owned =
        incoming.stream()
            .filter(t -> t != null && t.id() != null && !t.id().isBlank())
            // Stamped server-side from the session: a thread cannot be filed
            // under another account even if the body says otherwise.
            .map(t -> new ChatThread(t.id(), owner, t.title(), t.updatedAt(), t.messages()))
            .limit(MAX_THREADS)
            .toList();

    if (!owned.isEmpty()) threads.saveAll(owned);

    return ResponseEntity.ok(Map.of("ok", true, "count", owned.size()));
  }
}
