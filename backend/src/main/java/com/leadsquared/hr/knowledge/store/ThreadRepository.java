package com.leadsquared.hr.knowledge.store;

import com.leadsquared.hr.knowledge.model.ChatThread;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Chat threads, always queried by owner.
 *
 * <p>There is deliberately no {@code findAll} in use anywhere: reading threads
 * without an owner filter would hand one employee another's conversation.
 */
public interface ThreadRepository extends MongoRepository<ChatThread, String> {

  List<ChatThread> findByOwnerOrderByUpdatedAtDesc(String owner);

  void deleteByOwner(String owner);
}
