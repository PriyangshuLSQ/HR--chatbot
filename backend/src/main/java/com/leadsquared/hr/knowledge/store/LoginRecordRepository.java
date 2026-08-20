package com.leadsquared.hr.knowledge.store;

import com.leadsquared.hr.knowledge.model.LoginRecord;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Sign-in records, one per account, newest activity first. */
public interface LoginRecordRepository extends MongoRepository<LoginRecord, String> {

  Optional<LoginRecord> findByEmail(String email);

  List<LoginRecord> findAllByOrderByLastLoginAtDesc(Pageable pageable);
}
