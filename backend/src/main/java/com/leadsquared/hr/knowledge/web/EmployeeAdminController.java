package com.leadsquared.hr.knowledge.web;

import com.leadsquared.hr.knowledge.employee.EmployeeDataService;
import com.leadsquared.hr.knowledge.employee.EmployeeImportService;
import com.leadsquared.hr.knowledge.config.EmployeeImportProperties;
import com.leadsquared.hr.knowledge.model.DataAccessEvent;
import com.leadsquared.hr.knowledge.store.DataAccessEventRepository;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only: loading the HR extract, and reading the audit trail.
 *
 * <p>Mapped under {@code /api/knowledge/**}, which the filter chain already gates behind the
 * admin check — so this inherits that rather than restating it, and cannot be left open by
 * someone adding a path here later.
 *
 * <p>Note what is <b>not</b> here: any endpoint that returns an employee record. The audit
 * trail says which employee codes were read, never what the records contained, and there is no
 * "look up employee X" route for support purposes — that would be exactly the parameterised
 * lookup the privacy rules forbid, and an admin console is not an exemption from them.
 */
@RestController
@RequestMapping("/api/knowledge/employees")
public class EmployeeAdminController {

  /** Audit pages. Large enough to spot a pattern, small enough to render. */
  private static final int AUDIT_PAGE = 200;

  private final EmployeeImportService importer;
  private final EmployeeDataService employees;
  private final DataAccessEventRepository audit;
  private final EmployeeImportProperties config;

  public EmployeeAdminController(
      EmployeeImportService importer,
      EmployeeDataService employees,
      DataAccessEventRepository audit,
      EmployeeImportProperties config) {
    this.importer = importer;
    this.employees = employees;
    this.audit = audit;
    this.config = config;
  }

  public record ImportRequest(String directory, String fromDomain, String toDomain) {}

  /** How many records are loaded, for the console's status line. */
  @GetMapping("/status")
  public ResponseEntity<?> status() {
    return ResponseEntity.ok(java.util.Map.of("recordCount", employees.recordCount()));
  }

  /**
   * Replaces the employee collection from a directory of extract workbooks.
   *
   * <p>Returns the reconciliation report rather than a bare OK: the Phase 1 bar is that
   * employee data matches the extract exactly, and "5,000 imported, 5,000 matched on
   * compensation" is the evidence for that. Warnings are part of the response for the same
   * reason — a silent import that dropped 40 rows would satisfy a 200 and fail the bar.
   */
  @PostMapping("/import")
  public ResponseEntity<?> importExtract(
      @RequestBody(required = false) ImportRequest body) {

    // The request may override any of these; unset fields fall back to configuration, so a
    // bare POST with no body does the configured import. That keeps the directory holding
    // 5,000 people's compensation out of a URL and out of anyone's shell history.
    String directory = firstSet(body == null ? null : body.directory(), config.directory());
    String fromDomain = firstSet(body == null ? null : body.fromDomain(), config.fromDomain());
    String toDomain = firstSet(body == null ? null : body.toDomain(), config.toDomain());

    if (directory == null) {
      return ResponseEntity.badRequest()
          .body(
              java.util.Map.of(
                  "error",
                  "No extract directory. Pass one in the request body, or set"
                      + " knowledge.employee-import.directory / HR_EXTRACT_DIR."));
    }
    try {
      EmployeeImportService.ImportReport report =
          importer.importFrom(directory, fromDomain, toDomain, config.overridesOrEmpty());
      return ResponseEntity.ok(report);
    } catch (IllegalArgumentException | IllegalStateException e) {
      return ResponseEntity.badRequest().body(java.util.Map.of("error", e.getMessage()));
    }
  }

  private static String firstSet(String preferred, String fallback) {
    if (preferred != null && !preferred.isBlank()) return preferred;
    return fallback == null || fallback.isBlank() ? null : fallback;
  }

  /**
   * The audit trail (rule 5).
   *
   * @param outcome optional filter — {@code denied_third_party} is the one HR Ops will want,
   *     since a run of those from one account is the anomaly worth investigating
   */
  @GetMapping("/audit")
  public ResponseEntity<List<DataAccessEvent>> auditTrail(
      @RequestParam(required = false) String outcome) {
    PageRequest page = PageRequest.of(0, AUDIT_PAGE);
    return ResponseEntity.ok(
        outcome == null || outcome.isBlank()
            ? audit.findAllByOrderByAtDesc(page)
            : audit.findByOutcomeOrderByAtDesc(outcome, page));
  }
}
