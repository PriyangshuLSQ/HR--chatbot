package com.leadsquared.hr.knowledge.store;

import com.leadsquared.hr.knowledge.model.IamRole;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Role definitions. Read on every admin-gated request, so keep the queries indexed. */
public interface IamRoleRepository extends MongoRepository<IamRole, String> {

  Optional<IamRole> findByName(String name);

  boolean existsByName(String name);

  void deleteByName(String name);
}
