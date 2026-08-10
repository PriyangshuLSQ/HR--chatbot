package com.leadsquared.hr.knowledge.employee;

import com.leadsquared.hr.knowledge.config.AdminDataAccessProperties;
import com.leadsquared.hr.knowledge.model.Employee;
import com.leadsquared.hr.knowledge.security.SignedInUser;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Cross-employee lookup for HR admins. The deliberate exception to the access rules.
 *
 * <p><b>Why this is a separate class, and why it uses {@link MongoTemplate} rather than the
 * repository.</b> {@code EmployeeDataService} and {@code EmployeeRepository} carry the guarantee
 * that nothing can name another employee — enforced by reflection tests that assert no method
 * takes an identifier and that the repository declares exactly one finder. Those tests are the
 * evidence for the HR Ops sign-off gate. Adding admin finders to either would have broken them,
 * and a weakened test is worse than no test: it looks like assurance and is not. So the
 * exception lives here, named for what it is, and the employee-facing boundary is untouched.
 *
 * <p>Every method demands the {@link SignedInUser} and re-checks the role itself. It does not
 * trust having been called from somewhere that already checked — the whole failure mode being
 * guarded against is a future caller reaching a permissive method by a path nobody re-examined.
 */
@Service
public class AdminEmployeeQueryService {

  private static final Logger log = LoggerFactory.getLogger(AdminEmployeeQueryService.class);

  /** {@code LS00123}, {@code LSQ4471} — how a record gets named directly. */
  private static final Pattern EMPLOYEE_CODE =
      Pattern.compile("\\b((?:ls|lsq|emp)[-_ ]?\\d{3,})\\b", Pattern.CASE_INSENSITIVE);

  /**
   * A person's name, as capitalised words.
   *
   * <p>Two words minimum. A single capitalised word is far more often a department, a location or
   * the first word of a sentence than a person, and searching on it returns arbitrary people.
   */
  private static final Pattern FULL_NAME =
      Pattern.compile("\\b([A-Z][a-z]{2,}\\s+[A-Z][a-z]{2,})\\b");

  private final MongoTemplate mongo;
  private final AdminDataAccessProperties config;

  public AdminEmployeeQueryService(MongoTemplate mongo, AdminDataAccessProperties config) {
    this.mongo = mongo;
    this.config = config;
    if (config.enabled()) {
      log.warn(
          "ADMIN CROSS-EMPLOYEE DATA ACCESS IS ENABLED. hr_admin accounts can read any"
              + " employee's record through the assistant. This contradicts Phase 1 privacy"
              + " rule 2 and must be turned off (knowledge.admin-data-access.enabled=false)"
              + " before go-live. Every such read is written to the audit trail as"
              + " allowed_admin_cross_employee.");
    }
  }

  /** Both gates: the flag, and the role on this session. Neither alone is enough. */
  public boolean isAvailableTo(SignedInUser user) {
    return config.enabled() && user != null && user.isAdmin();
  }

  /**
   * Records named in the question — by employee code, or by full name.
   *
   * <p>Returns empty when the caller is not an admin, whatever the question says. That check is
   * here rather than only at the call site because this method is the one that returns other
   * people's data, and it should be impossible to reach it without passing the role.
   *
   * @return at most {@link AdminDataAccessProperties#maxRecordsPerAnswer()} records
   */
  public List<Employee> findNamedIn(String question, SignedInUser user) {
    if (!isAvailableTo(user) || question == null || question.isBlank()) return List.of();

    List<Criteria> alternatives = new java.util.ArrayList<>();

    Matcher codes = EMPLOYEE_CODE.matcher(question);
    while (codes.find()) {
      alternatives.add(Criteria.where("_id").regex("^" + Pattern.quote(codes.group(1)) + "$", "i"));
    }

    Matcher names = FULL_NAME.matcher(question);
    while (names.find()) {
      // Anchored and quoted: the name comes from a user-supplied question, and an unquoted
      // regex there is a denial-of-service waiting to happen as much as a correctness problem.
      alternatives.add(Criteria.where("name").regex("^" + Pattern.quote(names.group(1)) + "$", "i"));
    }

    if (alternatives.isEmpty()) return List.of();

    Query query = new Query(new Criteria().orOperator(alternatives.toArray(Criteria[]::new)));
    query.limit(Math.max(1, config.maxRecordsPerAnswer()));

    List<Employee> found = mongo.find(query, Employee.class);
    log.warn(
        "ADMIN CROSS-EMPLOYEE READ by {}: {} record(s) — {}",
        user.email(),
        found.size(),
        found.stream().map(Employee::employeeCode).toList());
    return found;
  }

  /**
   * The facts block for records that are not the caller's own.
   *
   * <p>Labelled as somebody else's data, in the prompt, because the model otherwise has no way to
   * tell these apart from the caller's own record and will happily answer "your CTC is…" about a
   * colleague. Restricted to the fields needed to verify an import — enough to check the data
   * loaded correctly, not a full dossier.
   */
  public String renderForAdmin(List<Employee> others, SignedInUser user) {
    if (others.isEmpty() || !isAvailableTo(user)) return "";

    StringBuilder out = new StringBuilder();
    out.append("OTHER EMPLOYEES' RECORDS — administrative access.\n");
    out.append(
        "The signed-in user is an HR administrator and asked about the people below. These are"
            + " NOT the signed-in user's own data: never say \"your\" about any of it, and name"
            + " whose record each figure belongs to.\n");
    out.append(
        "Answer only about the people named here. If the question also asks about anyone else,"
            + " say that you can only look up people named directly.\n\n");

    for (Employee e : others) {
      out.append("Employee ").append(e.employeeCode()).append(" — ").append(e.name()).append("\n");
      if (e.employment() != null) {
        Employee.Employment j = e.employment();
        out.append("- Grade: ").append(j.grade()).append(", designation: ").append(j.designation()).append("\n");
        out.append("- Business unit: ").append(j.businessUnit())
            .append(", department: ").append(j.department()).append("\n");
        out.append("- Status: ").append(j.employmentStatus())
            .append(", joined: ").append(j.dateOfJoining()).append("\n");
        out.append("- Reporting manager: ").append(j.reportingManagerName()).append("\n");
      }
      if (e.compensation() != null && e.compensation().fixedCtc() != null) {
        out.append("- Annual fixed CTC: ")
            .append(e.compensation().currency() == null ? "INR" : e.compensation().currency())
            .append(" ")
            .append(e.compensation().fixedCtc().toPlainString())
            .append("\n");
        if (e.compensation().variableTargetAmount() != null) {
          out.append("- Annual variable target: ")
              .append(e.compensation().variableTargetAmount().toPlainString())
              .append("\n");
        }
      }
      if (e.leave() != null) {
        out.append("- Leave balances — earned: ").append(text(e.leave().earned()))
            .append(", sick: ").append(text(e.leave().sick())).append("\n");
      }
      out.append("\n");
    }
    return out.toString();
  }

  private static String text(java.math.BigDecimal value) {
    return value == null ? "not in extract" : value.stripTrailingZeros().toPlainString();
  }

  /** For the log line and the audit intent. */
  public static String describe(List<Employee> found) {
    return found.stream().map(Employee::employeeCode).collect(java.util.stream.Collectors.joining(","));
  }

  static String normalise(String s) {
    return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
  }
}
