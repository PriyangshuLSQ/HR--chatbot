package com.leadsquared.hr.knowledge.store;

import com.leadsquared.hr.knowledge.model.AdminAuditEvent;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * The administrative audit trail. Append and read only.
 *
 * <p>No {@code deleteBy*} and no update path, for the same reason as
 * {@link DataAccessEventRepository}: the people who can reach this trail are exactly the people
 * whose actions it records, so the ability to prune it would defeat it.
 */
public interface AdminAuditEventRepository extends MongoRepository<AdminAuditEvent, String> {

  List<AdminAuditEvent> findAllByOrderByAtDesc(Pageable pageable);

  List<AdminAuditEvent> findByActionOrderByAtDesc(String action, Pageable pageable);

  List<AdminAuditEvent> findByActorOrderByAtDesc(String actor, Pageable pageable);
}
