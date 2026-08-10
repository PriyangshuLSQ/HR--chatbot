package com.leadsquared.hr.knowledge.store;

import com.leadsquared.hr.knowledge.model.Employee;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Employee records, reachable one way only.
 *
 * <p><b>There is exactly one read method, and it takes the session's own address.</b> That
 * is the whole design. No find-by-code, no find-by-name, no find-by-manager, no
 * findAll for serving — because a query method that exists is a query method that can be
 * called, and the mandatory privacy rules turn on there being no way to name another
 * employee. Adding a finder here would move the security boundary out of this file and
 * into the discipline of whoever calls it next.
 *
 * <p>{@code count} and the inherited {@code saveAll} exist for the import path and the
 * admin console's "N records loaded" line. Neither can return one employee's data to
 * another employee.
 *
 * @see com.leadsquared.hr.knowledge.employee.EmployeeDataService the only permitted caller
 */
public interface EmployeeRepository extends MongoRepository<Employee, String> {

  /**
   * The one way in: match any of the record's identities.
   *
   * <p>Mongo matches a scalar against an array field element-wise, so this is an exact match on
   * one entry rather than a scan — the multikey index on {@code identities} serves it directly.
   *
   * @param identity always {@code SignedInUser.email()}, never a request parameter
   */
  Optional<Employee> findByIdentities(String identity);
}
