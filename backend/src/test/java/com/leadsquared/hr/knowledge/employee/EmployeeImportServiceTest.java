package com.leadsquared.hr.knowledge.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.leadsquared.hr.knowledge.model.Employee;
import com.leadsquared.hr.knowledge.store.EmployeeRepository;
import com.leadsquared.hr.knowledge.store.EnpsResponseRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import java.io.File;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Reconciliation of the import against the HR extract.
 *
 * <p>Runs against the real workbooks and asserts spot values, because the Phase 1 bar is that
 * employee data matches the extract <i>exactly</i> — and a parse that silently reads the wrong
 * column produces a plausible number, not an error. The values below were read out of the
 * spreadsheets by hand.
 *
 * <p>The repository is stubbed, so nothing is written to the database: this checks the parse,
 * not the persistence. Skipped rather than failed when the extract is not present, since the
 * files are not in the repository and CI has no copy of them.
 */
class EmployeeImportServiceTest {

  /** Where the extract landed. Not in version control — HR-supplied data stays out of git. */
  private static final String EXTRACT_DIR =
      System.getProperty(
          "hr.extract.dir", System.getProperty("user.home") + "/Downloads/OneDrive_1_8-10-2026");

  private Map<String, Employee> runImport() {
    return runImport(Map.of());
  }

  private Map<String, Employee> runImport(Map<String, String> overrides) {
    EmployeeRepository repo = mock(EmployeeRepository.class);
    when(repo.count()).thenReturn(0L);

    EmployeeImportService service = new EmployeeImportService(repo, mock(EnpsResponseRepository.class), mock(MongoTemplate.class));
    var report = service.importFrom(EXTRACT_DIR, "lsq.com", "leadsquared.com", overrides);

    assertThat(report.imported()).isEqualTo(5000);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Iterable<Employee>> saved = ArgumentCaptor.forClass(Iterable.class);
    verify(repo).saveAll(saved.capture());

    List<Employee> records = new java.util.ArrayList<>();
    saved.getValue().forEach(records::add);
    return records.stream().collect(Collectors.toMap(Employee::employeeCode, Function.identity()));
  }

  @Test
  @DisplayName("The extract imports and reconciles for a known employee")
  void reconcilesAgainstTheExtract() {
    assumeThat(new File(EXTRACT_DIR).isDirectory())
        .as("HR extract not present at %s — skipping reconciliation", EXTRACT_DIR)
        .isTrue();

    Map<String, Employee> byCode = runImport();

    // LS00001, read out of the four workbooks by hand.
    Employee aarav = byCode.get("LS00001");
    assertThat(aarav).isNotNull();
    assertThat(aarav.name()).isEqualTo("Aarav Shah");

    // The domain rewrite: the extract says @lsq.com, the session will say @leadsquared.com.
    // Without this the lookup matches nothing and every employee is told they have no record.
    assertThat(aarav.identities()).containsExactly("aarav.shah@leadsquared.com");
    assertThat(aarav.isResolvable()).isTrue();

    assertThat(aarav.employment().grade()).isEqualTo("L3");
    assertThat(aarav.employment().designation()).isEqualTo("Software Engineer");
    assertThat(aarav.employment().businessUnit()).isEqualTo("Operations");
    // Taken from the leaf of "Operations > Legal & Compliance", not the whole path.
    assertThat(aarav.employment().department()).isEqualTo("Legal & Compliance");
    assertThat(aarav.employment().reportingManagerName()).isEqualTo("Manish Yadav");
    assertThat(aarav.employment().l2ManagerName()).isEqualTo("Tushar Agarwal");
    assertThat(aarav.employment().dateOfJoining()).isEqualTo(java.time.LocalDate.of(2023, 3, 31));
    assertThat(aarav.employment().employmentStatus()).isEqualTo("Active");

    assertThat(aarav.compensation().fixedCtc()).isEqualByComparingTo(new BigDecimal("806000"));
    assertThat(aarav.compensation().variableTargetAmount())
        .isEqualByComparingTo(new BigDecimal("76500"));
    assertThat(aarav.compensation().variablePercentOfCtc())
        .isEqualByComparingTo(new BigDecimal("9.5"));

    assertThat(aarav.leave().earned()).isEqualByComparingTo(new BigDecimal("6"));
    assertThat(aarav.leave().sick()).isEqualByComparingTo(new BigDecimal("9"));

    // Six months of attendance, Feb to Jul 2026, aggregated from the daily register and keyed
    // so that string ordering is chronological ordering.
    assertThat(aarav.attendance()).hasSize(6);
    assertThat(aarav.attendance().stream().map(Employee.AttendanceMonth::month))
        .containsExactly("2026-02", "2026-03", "2026-04", "2026-05", "2026-06", "2026-07");
    Employee.AttendanceMonth feb = aarav.attendance().get(0);
    assertThat(feb.workingDays()).isBetween(18, 24); // 28 days less weekends
    assertThat(feb.presentDays()).isLessThanOrEqualTo(feb.workingDays());

    // Ratings are present; achievement percentages are not anywhere in the extract, which is
    // why a payout cannot be computed unless the employee states their own figure.
    assertThat(aarav.pms()).isNotEmpty();
    assertThat(aarav.pms()).allSatisfy(p -> assertThat(p.achievementPercent()).isNull());
  }

  @Test
  @DisplayName("An identity override makes a real account resolve to a chosen record")
  void identityOverrideResolves() {
    assumeThat(new File(EXTRACT_DIR).isDirectory()).isTrue();

    Map<String, Employee> byCode = runImport(Map.of("bnalamat@gitam.in", "LS00008"));

    Employee mapped = byCode.get("LS00008");
    // Added, not substituted — this is the whole point of identities being a list. The previous
    // single-field version overwrote the address, so the real employee lost their own record.
    assertThat(mapped.identities())
        .contains("bnalamat@gitam.in", "aishwarya.gandhi@leadsquared.com");
    // Sales, so a payout question exercises the department matrix rather than the default.
    assertThat(mapped.employment().businessUnit()).isEqualTo("Sales");
    assertThat(mapped.compensation().variableTargetAmount()).isNotNull();

    // The extract's own address still resolves, and only to this record.
    assertThat(
            byCode.values().stream()
                .filter(e -> e.identities().contains("aishwarya.gandhi@leadsquared.com"))
                .toList())
        .hasSize(1);
  }

  @Test
  @DisplayName("An override naming a code that does not exist is reported, not silently ignored")
  void unknownOverrideIsReported() {
    assumeThat(new File(EXTRACT_DIR).isDirectory()).isTrue();

    EmployeeRepository repo = mock(EmployeeRepository.class);
    var report =
        new EmployeeImportService(repo, mock(EnpsResponseRepository.class), mock(MongoTemplate.class))
            .importFrom(EXTRACT_DIR, "lsq.com", "leadsquared.com", Map.of("x@y.com", "LS99999"));

    assertThat(report.warnings())
        .anySatisfy(w -> assertThat(w).contains("no such employee code"));
  }

  @Test
  @DisplayName("Every identity is unique, or the lookup breaks for someone")
  void identitiesAreUnique() {
    assumeThat(new File(EXTRACT_DIR).isDirectory()).isTrue();

    Map<String, Employee> byCode = runImport();
    List<String> identities =
        byCode.values().stream().flatMap(e -> e.identities().stream()).toList();

    // Uniqueness is now enforced during the import itself — a duplicate throws rather than
    // reaching the unique index, where it would cost one employee their record. This asserts the
    // extract is clean; the throwing behaviour is covered separately.
    assertThat(identities).doesNotHaveDuplicates();
    assertThat(identities).hasSize(byCode.size());
  }

  @Test
  @DisplayName("An identity claimed by two employees fails the import outright")
  void duplicateIdentityIsFatal() {
    assumeThat(new File(EXTRACT_DIR).isDirectory()).isTrue();

    // Give LS00008 an address that already belongs to LS00001. Warning and carrying on would let
    // the unique index reject one write, leaving one of them silently unreachable — so the import
    // must refuse instead.
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> runImport(Map.of("aarav.shah@leadsquared.com", "LS00008")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Duplicate identity")
        .hasMessageContaining("LS00001");
  }

  @Test
  @DisplayName("Compensation and leave match for all 5,000, not just the sample")
  void allRecordsAreComplete() {
    assumeThat(new File(EXTRACT_DIR).isDirectory()).isTrue();

    Map<String, Employee> byCode = runImport();

    // The 100%-data-accuracy bar is about coverage as much as correctness: a join that
    // matched 4,960 of 5,000 rows would look fine in a spot check.
    assertThat(byCode.values().stream().filter(e -> e.compensation() != null).count())
        .isEqualTo(5000);
    assertThat(byCode.values().stream().filter(e -> e.leave() != null).count()).isEqualTo(5000);
    assertThat(byCode.values().stream().filter(e -> !e.attendance().isEmpty()).count())
        .isEqualTo(5000);
  }
}
