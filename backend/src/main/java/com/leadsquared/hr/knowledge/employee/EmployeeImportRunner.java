package com.leadsquared.hr.knowledge.employee;

import com.leadsquared.hr.knowledge.config.EmployeeImportProperties;
import com.leadsquared.hr.knowledge.security.SignedInUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Runs the extract import once at boot, when explicitly asked to.
 *
 * <p>Exists because the import endpoint is admin-gated — correctly so — which makes a first load
 * awkward on a machine where nobody is signed in yet. This is the same import, triggered by an
 * argument instead of a session:
 *
 * <pre>
 * mvn spring-boot:run -Dspring-boot.run.arguments=--knowledge.employee-import.on-startup=true
 * </pre>
 *
 * <p>Off by default and deliberately not a convenience. An import replaces the whole collection,
 * so leaving it on means every restart silently reloads — and quietly discards any correction
 * made since the last one.
 *
 * <p>It also reads back each identity override through the ordinary lookup path, because the
 * useful question is not "did the import claim success" but "does the account actually resolve".
 * Those two came apart once already on this feature: the extract's {@code @lsq.com} addresses
 * would have imported perfectly and matched no signed-in user at all.
 */
@Component
public class EmployeeImportRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(EmployeeImportRunner.class);

  private final EmployeeImportProperties config;
  private final EmployeeImportService importer;
  private final EmployeeDataService employees;

  public EmployeeImportRunner(
      EmployeeImportProperties config,
      EmployeeImportService importer,
      EmployeeDataService employees) {
    this.config = config;
    this.importer = importer;
    this.employees = employees;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!config.shouldImportOnStartup()) return;

    log.warn(
        "Startup employee import is ON — the employees collection will be REPLACED from {}",
        config.directory());

    try {
      EmployeeImportService.ImportReport report =
          importer.importFrom(
              config.directory(),
              config.fromDomain(),
              config.toDomain(),
              config.overridesOrEmpty());

      log.info(
          "Startup import: {} records; matched {} compensation, {} leave, {} attendance; emails {}",
          report.imported(),
          report.compensationMatched(),
          report.leaveMatched(),
          report.attendanceMatched(),
          report.emailDomainRewrite());
      report.warnings().forEach(w -> log.warn("Import warning: {}", w));

      verifyOverrides();

    } catch (RuntimeException e) {
      // Never fatal. The policy desk works without employee data, and taking the whole service
      // down over a missing spreadsheet would turn a data problem into an outage.
      log.error("Startup employee import failed — the service will run without employee data", e);
    }
  }

  /** Resolves each override through the real lookup, so a claimed success is a proven one. */
  private void verifyOverrides() {
    config
        .overridesOrEmpty()
        .keySet()
        .forEach(
            email -> {
              // A SignedInUser assembled from configuration, not from a request. The point is to
              // exercise exactly the path a real session takes rather than a shortcut past it.
              SignedInUser as = new SignedInUser(null, email, null, SignedInUser.EMPLOYEE);
              employees
                  .myRecord(as)
                  .ifPresentOrElse(
                      found ->
                          log.info(
                              "Override verified: {} resolves to {} — {}, {} {} in {}",
                              email,
                              found.employeeCode(),
                              found.name(),
                              found.employment() == null ? "?" : found.employment().grade(),
                              found.employment() == null ? "?" : found.employment().designation(),
                              found.employment() == null ? "?" : found.employment().businessUnit()),
                      () ->
                          log.error(
                              "Override FAILED: {} resolves to no record. The import reported"
                                  + " success, so this is a lookup-key mismatch, not a load"
                                  + " failure — check the mail-domain rewrite.",
                              email));
            });
  }
}
