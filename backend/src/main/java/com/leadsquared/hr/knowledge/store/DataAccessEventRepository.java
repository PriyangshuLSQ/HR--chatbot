package com.leadsquared.hr.knowledge.store;

import com.leadsquared.hr.knowledge.model.DataAccessEvent;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * The audit trail. Append and read; nothing here amends or removes.
 *
 * <p>Deliberately no {@code deleteBy*}: a retention policy that HR Ops has not yet set
 * should be applied by an explicit, reviewed job, not be one accidental call away.
 */
public interface DataAccessEventRepository extends MongoRepository<DataAccessEvent, String> {

  List<DataAccessEvent> findAllByOrderByAtDesc(Pageable pageable);

  List<DataAccessEvent> findByOutcomeOrderByAtDesc(String outcome, Pageable pageable);

  List<DataAccessEvent> findBySubjectOrderByAtDesc(String subject, Pageable pageable);

  /** Drives the anomaly check: how often this subject was denied in a recent window. */
  long countBySubjectAndOutcomeAndAtAfter(String subject, String outcome, Instant since);
}
