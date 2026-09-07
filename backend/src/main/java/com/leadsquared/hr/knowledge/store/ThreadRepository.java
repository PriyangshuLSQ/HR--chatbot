package com.leadsquared.hr.knowledge.store;

import com.leadsquared.hr.knowledge.model.ChatThread;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Chat threads, always queried by owner.
 *
 * <p>There is deliberately no {@code findAll} in use anywhere: reading threads
 * without an owner filter would hand one employee another's conversation.
 */
public interface ThreadRepository extends MongoRepository<ChatThread, String> {

  List<ChatThread> findByOwnerOrderByUpdatedAtDesc(String owner);

  /**
   * The newest page of one owner's threads, for the chat sidebar.
   *
   * <p>Exists because the server now retains history the client has aged out — see
   * {@code ThreadController.sync}, which no longer deletes. Without a limit here the sidebar would
   * grow without bound and every chat load would ship an employee's entire history to render a
   * list they only ever scroll the top of.
   */
  List<ChatThread> findByOwnerOrderByUpdatedAtDesc(String owner, Pageable pageable);

  /**
   * One thread, and only if this owner has it.
   *
   * <p>Owner in the query rather than checked after loading: thread ids are guessable, and a
   * finder that returns the document first leaves the check to every call site. Used to read the
   * transcript back for conversation context — see {@code AiController.historyFor}.
   */
  Optional<ChatThread> findByIdAndOwner(String id, String owner);

  void deleteByOwner(String owner);
}
