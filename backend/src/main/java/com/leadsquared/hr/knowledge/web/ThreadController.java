package com.leadsquared.hr.knowledge.web;

import com.leadsquared.hr.knowledge.model.ChatThread;
import com.leadsquared.hr.knowledge.security.CurrentUser;
import com.leadsquared.hr.knowledge.store.ThreadRepository;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
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
    return new ThreadList(threads.findByOwnerOrderByUpdatedAtDesc(owner));
  }

  /** The client sends only its threads; who they belong to is not its call. */
  public record ThreadSync(List<ChatThread> threads) {}

  /**
   * Replaces the signed-in employee's threads with what the client holds.
   *
   * <p>Whole-set replacement rather than per-thread patching, because the chat
   * window's state <em>is</em> the thread list — it edits titles, prepends new
   * conversations and drops the oldest past the cap. Reconciling that as a diff
   * would be more code and more ways to leave the two out of step.
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

    threads.deleteByOwner(owner);
    if (!owned.isEmpty()) threads.saveAll(owned);

    return ResponseEntity.ok(Map.of("ok", true, "count", owned.size()));
  }
}
