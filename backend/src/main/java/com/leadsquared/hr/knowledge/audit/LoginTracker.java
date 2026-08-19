package com.leadsquared.hr.knowledge.audit;

import com.leadsquared.hr.knowledge.model.Employee;
import com.leadsquared.hr.knowledge.model.LoginRecord;
import com.leadsquared.hr.knowledge.store.EmployeeRepository;
import com.leadsquared.hr.knowledge.store.LoginRecordRepository;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Notes that an account signed in, and reads the list back for the console.
 *
 * <p><b>Never fails a sign-in.</b> Every write is wrapped, because the alternative is that an
 * unreachable database stops people logging in to an assistant that would otherwise work. A
 * missing row means the console under-reports; a thrown exception here would mean nobody gets in
 * at all, and those are not comparable costs.
 */
@Service
public class LoginTracker {

  private static final Logger log = LoggerFactory.getLogger(LoginTracker.class);

  private static final int DEFAULT_LIMIT = 500;

  private final LoginRecordRepository logins;
  private final EmployeeRepository employees;

  public LoginTracker(LoginRecordRepository logins, EmployeeRepository employees) {
    this.logins = logins;
    this.employees = employees;
  }

  /**
   * Records a sign-in, creating the record or bumping the existing one.
   *
   * @param method {@link LoginRecord#ENTRA} or {@link LoginRecord#LOCAL}
   */
  public void recordLogin(String rawEmail, String name, String method) {
    if (rawEmail == null || rawEmail.isBlank()) return;
    String email = rawEmail.trim().toLowerCase(Locale.ROOT);

    try {
      // Resolved on every sign-in rather than only on the first: a new joiner signs in before
      // appearing in the HR extract, and the code should appear once an import catches up.
      String employeeCode = employeeCodeFor(email);

      Optional<LoginRecord> existing = logins.findByEmail(email);
      LoginRecord saved =
          existing.isPresent()
              ? logins.save(existing.get().seenAgain(name, employeeCode, method))
              : logins.save(LoginRecord.first(email, displayName(name, email), employeeCode, method));

      log.debug("Sign-in recorded for {} (count={})", email, saved.loginCount());

    } catch (RuntimeException e) {
      log.warn("Could not record the sign-in for {} — {}", email, e.toString());
    }
  }

  /** Everyone who has signed in, most recent first. */
  public List<LoginRecord> list(Integer limit) {
    int size = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, DEFAULT_LIMIT);
    try {
      return logins.findAllByOrderByLastLoginAtDesc(PageRequest.of(0, size));
    } catch (RuntimeException e) {
      log.warn("Could not read the sign-in list: {}", e.toString());
      return List.of();
    }
  }

  /**
   * The employee code behind this address, if the extract knows it.
   *
   * <p>A read of the employee collection by identity — the same lookup the assistant makes for the
   * caller's own record, and it stays inside this class rather than being handed to the console:
   * what the console gets is a code, never a record.
   */
  private String employeeCodeFor(String email) {
    try {
      return employees.findByIdentities(email).map(Employee::employeeCode).orElse(null);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String displayName(String name, String email) {
    return name == null || name.isBlank() ? email.split("@")[0] : name.trim();
  }
}
