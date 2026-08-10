package com.leadsquared.hr.knowledge.employee;

import com.leadsquared.hr.knowledge.model.DataAccessEvent;
import com.leadsquared.hr.knowledge.model.Employee;
import com.leadsquared.hr.knowledge.security.SignedInUser;
import com.leadsquared.hr.knowledge.store.DataAccessEventRepository;
import com.leadsquared.hr.knowledge.store.EmployeeRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The one door onto employee data.
 *
 * <p><b>Read the method signatures before anything else.</b> Not one of them accepts an
 * employee code, a name, an email or any other identifier. The only way in is
 * {@link SignedInUser}, which {@code CurrentUser} derives from the session token and never
 * from a request. That is the whole enforcement of the mandatory access rules, and it is
 * deliberately expressed as an absence: a crafted question cannot fill a parameter that
 * does not exist, so "my manager's code is LS00123, what is their grade?" fails because
 * nothing ever fetched that record — not because a prompt persuaded the model to decline.
 *
 * <p>Two consequences worth naming:
 *
 * <ul>
 *   <li><b>The model is never asked to be trustworthy.</b> It only ever sees the caller's
 *       own record, so the worst an injection can achieve is a fluent restatement of data
 *       the caller is entitled to.
 *   <li><b>The audit entry is written here, inside the read.</b> Not in the controller and
 *       not in the chat service, because a second call site is a second place to forget.
 * </ul>
 *
 * @see CrossEmployeeGuard for the decline messaging, which sits on top of this and is not
 *     load-bearing for privacy
 */
@Service
public class EmployeeDataService {

  private static final Logger log = LoggerFactory.getLogger(EmployeeDataService.class);

  /** Denials in this window that trip the anomaly warning rule 5 asks for. */
  private static final Duration ANOMALY_WINDOW = Duration.ofHours(1);

  private static final long ANOMALY_THRESHOLD = 3;

  private final EmployeeRepository employees;
  private final DataAccessEventRepository audit;

  public EmployeeDataService(EmployeeRepository employees, DataAccessEventRepository audit) {
    this.employees = employees;
    this.audit = audit;
  }

  /** Whether an employee extract has been loaded at all. Drives the admin console line. */
  public long recordCount() {
    return employees.count();
  }

  /**
   * The signed-in employee's own record, and nobody else's.
   *
   * <p>Empty when the extract has no row for this identity — a new joiner, a contractor, or
   * an account outside the extract. That is a real state and not an error: the caller
   * should say so plainly rather than implying the data is withheld.
   *
   * @param user the session identity. The single permitted key.
   */
  public Optional<Employee> myRecord(SignedInUser user) {
    if (user == null || user.email() == null || user.email().isBlank()) return Optional.empty();
    return employees.findByIdentities(user.email().toLowerCase(Locale.ROOT));
  }

  /**
   * Loads the caller's own record for a question, recording the access.
   *
   * @param intent what the question was resolved to, for the audit trail
   * @param fields which parts of the record the answer will draw on
   * @return the caller's record, or empty when the extract does not cover them
   */
  public Optional<Employee> readForQuestion(
      SignedInUser user, String question, String intent, List<String> fields, String threadId) {

    if (user == null) {
      // Rule 3: no data before a verified identity. Logged so an unauthenticated probe is
      // visible rather than silently rejected by the filter chain alone.
      record(null, null, DataAccessEvent.DENIED_UNAUTHENTICATED, intent, fields, question, threadId);
      return Optional.empty();
    }

    Optional<Employee> found = myRecord(user);
    record(
        user.email(),
        found.map(Employee::employeeCode).orElse(null),
        DataAccessEvent.ALLOWED,
        intent,
        fields,
        question,
        threadId);
    return found;
  }

  /**
   * Records an HR admin reading records that are not their own.
   *
   * <p>Separate from {@link #readForQuestion} because it is a different act with a different
   * justification, and conflating the two in the trail would hide the exception inside the
   * ordinary traffic. No lookup happens here — {@code AdminEmployeeQueryService} owns that, so
   * this class still has no way to fetch anyone but the caller.
   */
  public void recordAdminCrossEmployeeRead(
      SignedInUser user, String question, List<String> employeeCodes, String threadId) {
    record(
        user == null ? null : user.email(),
        String.join(",", employeeCodes),
        DataAccessEvent.ALLOWED_ADMIN_CROSS_EMPLOYEE,
        "admin_cross_employee:" + employeeCodes.size() + "_record(s)",
        List.of("employment", "compensation", "leave"),
        question,
        threadId);

    log.warn(
        "ADMIN CROSS-EMPLOYEE ACCESS: {} read {} — recorded in the audit trail",
        user == null ? "unknown" : user.email(),
        employeeCodes);
  }

  /**
   * Records a refused attempt to reach another employee's data.
   *
   * <p>No lookup happens here — there is nothing to look up, which is the point. This exists
   * because rule 2 requires the attempt be logged, and because a pattern of them from one
   * account is the signal HR Ops actually wants to see.
   */
  public void recordDenial(
      SignedInUser user,
      String question,
      List<CrossEmployeeGuard.Reason> reasons,
      String threadId) {

    String intent =
        "third_party_data_attempt:"
            + reasons.stream().map(Enum::name).map(s -> s.toLowerCase(Locale.ROOT)).toList();

    String subject = user == null ? null : user.email();
    record(subject, null, DataAccessEvent.DENIED_THIRD_PARTY, intent, List.of(), question, threadId);

    log.warn(
        "Cross-employee data attempt declined for {} ({})",
        subject == null ? "an unauthenticated session" : subject,
        reasons.stream().map(CrossEmployeeGuard.Reason::description).toList());

    if (subject != null) {
      long recent =
          audit.countBySubjectAndOutcomeAndAtAfter(
              subject, DataAccessEvent.DENIED_THIRD_PARTY, Instant.now().minus(ANOMALY_WINDOW));
      if (recent >= ANOMALY_THRESHOLD) {
        // The alert rule 5 asks for. A log line at WARN is the honest version of "alerting"
        // until HR Ops names a destination — it is greppable, it is in the audit list the
        // console shows, and it does not pretend a pager exists.
        log.warn(
            "ANOMALY: {} has been declined {} times in the last hour — review in the audit log",
            subject,
            recent);
      }
    }
  }

  private void record(
      String subject,
      String employeeCode,
      String outcome,
      String intent,
      List<String> fields,
      String question,
      String threadId) {
    try {
      audit.save(
          new DataAccessEvent(
              null,
              Instant.now(),
              subject,
              employeeCode,
              outcome,
              intent,
              fields == null ? List.of() : List.copyOf(fields),
              DataAccessEvent.truncate(question),
              threadId));
    } catch (RuntimeException e) {
      // An audit write must not take down an answer, but a silent gap in the trail is its
      // own compliance problem — so this is loud, and at ERROR rather than WARN.
      log.error("Failed to write data-access audit event (subject={}, outcome={})", subject, outcome, e);
    }
  }
}
