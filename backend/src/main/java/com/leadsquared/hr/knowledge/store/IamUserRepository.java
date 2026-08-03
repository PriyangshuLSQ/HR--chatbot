package com.leadsquared.hr.knowledge.store;

import com.leadsquared.hr.knowledge.model.IamUser;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Role assignments, keyed on the signed-in address. */
public interface IamUserRepository extends MongoRepository<IamUser, String> {

  Optional<IamUser> findByEmail(String email);

  /**
   * One query for every address a signed-in account might present.
   *
   * <p>A guest invited from another tenant can arrive as either their real address
   * or Entra's mangled {@code #EXT#} form, and which one lands in the token varies —
   * so the lookup takes the whole candidate set rather than guessing.
   */
  List<IamUser> findByEmailIn(Collection<String> emails);

  List<IamUser> findAllByOrderByCreatedAtDesc();
}
