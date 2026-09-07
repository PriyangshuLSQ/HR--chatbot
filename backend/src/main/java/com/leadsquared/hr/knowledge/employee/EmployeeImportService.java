package com.leadsquared.hr.knowledge.employee;

import com.leadsquared.hr.knowledge.model.Employee;
import com.leadsquared.hr.knowledge.model.EnpsResponse;
import com.leadsquared.hr.knowledge.store.EmployeeRepository;
import com.leadsquared.hr.knowledge.store.EnpsResponseRepository;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/**
 * Loads the HR extract — a directory of .xlsx workbooks — into MongoDB.
 *
 * <p>Joined on <b>Employee ID</b>, the one key present in every file. Everything about one person
 * lands in a single {@code employees} document: employment, compensation, leave, attendance and
 * appraisals as sub-documents rather than separate collections. That shape is the privacy design,
 * not a convenience — {@code EmployeeRepository} exposes a lookup by identity and nothing else, so
 * there is no query that can return a second person's data, and no join for a prompt to get wrong.
 *
 * <p><b>Identity comes from the email map when one is supplied.</b> {@code
 * Employee_ID_Email_Map.xlsx} states the employee-ID-to-address mapping outright. Failing that,
 * the master's own addresses are rewritten from the extract domain to the sign-in domain — the
 * extract carries {@code @lsq.com} while Entra identities are {@code @leadsquared.com}, and
 * without one of these two mechanisms every lookup misses and every employee is told no record
 * exists, a total failure that looks exactly like an empty database.
 *
 * <p>The engagement survey goes to its own collection — see {@link EnpsResponse} for why it is
 * kept out of the employee record. {@code Exit Data}, when present, is still not imported: it
 * holds exit-interview verbatims for people who have left, appears in no stated outcome, and is
 * more sensitive than anything the assistant needs.
 *
 * <p>Every workbook's second sheet is a summary or a legend derived from the first — grade-wise
 * averages, an attendance code key. Those are read by people, not by this importer: the figures
 * are recomputed from the rows when needed, and an average CTC by grade is exactly the aggregate
 * the assistant must never serve.
 */
@Service
public class EmployeeImportService {

  private static final Logger log = LoggerFactory.getLogger(EmployeeImportService.class);

  /**
   * Each workbook's accepted names, most preferred first.
   *
   * <p>Two exports of the same data have now arrived with different conventions — {@code
   * Compensation Records.xlsx} and {@code Compensation_Records.xlsx} — and a hardcoded filename
   * turned the second one into "no record exists" for all 5,000 employees. Matching a small list
   * costs nothing and means HR Ops re-exporting with spaces or underscores is not a code change.
   */
  private static final List<String> MASTER =
      List.of("Dummy_Employee_Master_Data.xlsx", "Dummy Employee Master Data.xlsx");

  private static final List<String> COMPENSATION =
      List.of("Compensation_Records.xlsx", "Compensation Records.xlsx");

  private static final List<String> LEAVE =
      List.of("Employee_Leave_Records.xlsx", "Employee Leave Records.xlsx");

  private static final List<String> ATTENDANCE =
      List.of("Attendance_Records.xlsx", "Attendance Records.xlsx");

  /**
   * Employee ID to sign-in address, and the authoritative one when present.
   *
   * <p>Preferred over rewriting the master's domain, because a rewrite is a guess that every
   * address follows one pattern. This file is HR Ops stating the mapping outright, which is the
   * difference between "we think this is their address" and "this is their address".
   */
  private static final List<String> EMAIL_MAP =
      List.of("Employee_ID_Email_Map.xlsx", "Employee ID Email Map.xlsx");

  private static final List<String> ENPS = List.of("NPS_Data.xlsx", "NPS Data.xlsx");

  /** Extract dates are written like {@code 31-Mar-2023}. */
  private static final DateTimeFormatter EXTRACT_DATE =
      DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH);

  private final EmployeeRepository employees;
  private final EnpsResponseRepository enps;

  /**
   * Only for dropping the collection — see {@link #importFrom}. Not a second read path onto
   * employee data: nothing here queries it.
   */
  private final MongoTemplate mongo;

  public EmployeeImportService(
      EmployeeRepository employees, EnpsResponseRepository enps, MongoTemplate mongo) {
    this.employees = employees;
    this.enps = enps;
    this.mongo = mongo;
  }

  /**
   * What the import did, for the admin console and for reconciliation.
   *
   * @param warnings things HR Ops should know — a workbook missing, IDs present in one file
   *     and absent from another. Never thrown, because a partial import that says what it
   *     missed is more useful than an exception.
   */
  public record ImportReport(
      int imported,
      int masterRows,
      int compensationMatched,
      int leaveMatched,
      int attendanceMatched,
      int unresolvable,
      String emailDomainRewrite,
      List<String> warnings) {}

  /**
   * @param directory where the workbooks live
   * @param fromDomain the domain in the extract, e.g. {@code lsq.com}
   * @param toDomain the domain identities sign in with, e.g. {@code leadsquared.com}
   */
  public ImportReport importFrom(String directory, String fromDomain, String toDomain) {
    return importFrom(directory, fromDomain, toDomain, Map.of());
  }

  /**
   * @param identityOverrides signed-in email to employee code, applied last. Logged at WARN for
   *     each one: a mapping that makes one account resolve to another person's record is a
   *     testing affordance, and it must never be able to sit in a deployment unnoticed.
   */
  public ImportReport importFrom(
      String directory,
      String fromDomain,
      String toDomain,
      Map<String, String> identityOverrides) {
    List<String> warnings = new ArrayList<>();
    File dir = new File(directory);
    if (!dir.isDirectory()) {
      throw new IllegalArgumentException("Not a directory: " + directory);
    }

    Map<String, Builder> byId = readMaster(dir, warnings, fromDomain, toDomain);
    int mapped = applyEmailMap(dir, byId, warnings);
    int compensationMatched = readCompensation(dir, byId, warnings);
    int leaveMatched = readLeave(dir, byId, warnings);
    int attendanceMatched = readAttendance(dir, byId, warnings);

    applyIdentityOverrides(byId, identityOverrides, warnings);
    int unresolvable = validateIdentities(byId, warnings);

    Instant extractedAt = Instant.now();
    List<Employee> records = byId.values().stream().map(b -> b.build(extractedAt)).toList();

    // Dropped, not emptied. Two reasons, and the second one cost a startup failure to learn:
    //
    // 1. An extract is a snapshot. Merging would leave people who have left the company in the
    //    collection indefinitely, still answerable.
    // 2. deleteAll() removes documents but keeps indexes. When the identity field changed shape,
    //    the stale unique index on the old field remained, and 5,000 new documents that lacked
    //    it all collided on null — the service would not start. Dropping takes the indexes with
    //    it, and Spring rebuilds them from the current mapping.
    mongo.dropCollection(Employee.class);
    employees.saveAll(records);

    int enps = readEnps(dir, byId.keySet(), warnings);

    log.info(
        "Employee import complete: {} records ({} comp, {} leave, {} attendance matched;"
            + " {} cannot self-serve){}",
        records.size(),
        compensationMatched,
        leaveMatched,
        attendanceMatched,
        unresolvable,
        warnings.isEmpty() ? "" : " with " + warnings.size() + " warning(s)");

    return new ImportReport(
        records.size(),
        byId.size(),
        compensationMatched,
        leaveMatched,
        attendanceMatched,
        unresolvable,
        fromDomain + " -> " + toDomain,
        warnings);
  }

  /**
   * Loads the engagement survey into its own collection.
   *
   * <p>Separate from the employee record on purpose — see {@link EnpsResponse}. Runs after the
   * employees are written so a survey row naming an unknown ID can be reported rather than
   * silently kept.
   *
   * @param knownIds employee IDs the master workbook carried, for reconciliation only
   * @return how many responses were stored
   */
  private int readEnps(File dir, Set<String> knownIds, List<String> warnings) {
    if (!present(dir, ENPS)) return 0;

    List<EnpsResponse> rows = new ArrayList<>();
    int unknownIds = 0;

    try (Workbook wb = open(dir, ENPS)) {
      Sheet sheet = wb.getSheetAt(0);

      // The header is not on row 0: the sheet opens with a title and a scale description. Found
      // rather than hardcoded, because the two exports so far have differed by a row.
      int headerRow = -1;
      for (int r = 0; r <= Math.min(10, sheet.getLastRowNum()); r++) {
        Map<String, Integer> candidate = headerIndex(sheet.getRow(r));
        if (candidate.containsKey("employee id")) {
          headerRow = r;
          break;
        }
      }
      if (headerRow < 0) {
        warnings.add("eNPS workbook has no \"Employee ID\" header — survey data not imported.");
        return 0;
      }

      Map<String, Integer> col = headerIndex(sheet.getRow(headerRow));
      for (int r = headerRow + 1; r <= sheet.getLastRowNum(); r++) {
        Row row = sheet.getRow(r);
        if (row == null) continue;

        String id = str(row, col, "Employee ID");
        if (id == null || id.isBlank()) continue;
        id = id.trim();
        if (!knownIds.contains(id)) unknownIds++;

        BigDecimal score = num(row, col, "NPS Score");
        rows.add(
            new EnpsResponse(
                null,
                id,
                str(row, col, "Full Name"),
                str(row, col, "Vertical"),
                str(row, col, "Grade"),
                score == null ? null : score.intValue(),
                str(row, col, "NPS Category"),
                date(row, col, "Survey Date"),
                str(row, col, "Comments")));
      }
    } catch (IOException e) {
      warnings.add("Could not read " + ENPS + " — survey data not imported.");
      log.warn("eNPS workbook unreadable", e);
      return 0;
    }

    // Replaced wholesale, like the employee collection: a survey export is a snapshot, and
    // merging would leave last cycle's responses beside this one with no way to tell them apart.
    mongo.dropCollection(EnpsResponse.class);
    enps.saveAll(rows);

    if (unknownIds > 0) {
      warnings.add(unknownIds + " eNPS response(s) name an employee ID absent from the master.");
    }
    log.info("eNPS import complete: {} response(s) stored", rows.size());
    return rows.size();
  }

  /**
   * Adds the sign-in address HR Ops mapped to each employee ID.
   *
   * <p>Added rather than replacing, for the same reason as the identity overrides: an address
   * already carried by the master keeps working, so a mapping file covering only some of the
   * organisation cannot silently cut the rest off. Whichever address a session presents, one of
   * them matches.
   *
   * <p>Optional. Without the file the domain rewrite is the only mapping, which is how the first
   * export worked — a missing map is a warning, not a failure.
   *
   * @return how many employees gained an address from the map
   */
  private int applyEmailMap(File dir, Map<String, Builder> byId, List<String> warnings) {
    if (!present(dir, EMAIL_MAP)) {
      warnings.add(
          "No employee ID to email map ("
              + EMAIL_MAP.get(0)
              + ") — identities come from the master workbook's own addresses, rewritten from "
              + "the extract domain. Supply the map to state them explicitly.");
      return 0;
    }

    int mapped = 0;
    int unknownIds = 0;
    try (Workbook wb = open(dir, EMAIL_MAP)) {
      Sheet sheet = wb.getSheetAt(0);
      Map<String, Integer> head = headerIndex(sheet.getRow(0));
      Integer idCol = column(head, "employee id", "emp id");
      Integer mailCol = column(head, "company email id", "email", "email id");

      if (idCol == null || mailCol == null) {
        warnings.add(
            "Email map is missing an \"Employee Id\" or \"Company Email ID\" column — ignored.");
        return 0;
      }

      for (int r = 1; r <= sheet.getLastRowNum(); r++) {
        Row row = sheet.getRow(r);
        if (row == null) continue;

        String id = cellString(row.getCell(idCol));
        String mail = cellString(row.getCell(mailCol));
        if (id == null || mail == null || id.isBlank() || !mail.contains("@")) continue;

        Builder b = byId.get(id.trim());
        if (b == null) {
          unknownIds++;
          continue;
        }
        String address = mail.trim().toLowerCase(Locale.ROOT);
        if (!b.identities.contains(address)) b.identities.add(address);
        mapped++;
      }
    } catch (IOException e) {
      warnings.add("Could not read the email map: " + e.getMessage());
      return 0;
    }

    if (unknownIds > 0) {
      warnings.add(
          unknownIds
              + " row(s) in the email map name an employee ID absent from the master workbook.");
    }
    log.info("Email map applied: {} employee(s) given an explicit sign-in address", mapped);
    return mapped;
  }

  /** First matching header, so a re-export that renames a column slightly still binds. */
  private static Integer column(Map<String, Integer> head, String... candidates) {
    for (String c : candidates) {
      Integer i = head.get(c);
      if (i != null) return i;
    }
    return null;
  }

  /**
   * Checks every identity is unique, and counts the employees no session can reach.
   *
   * <p>A duplicate is <b>fatal</b>, not a warning. The unique index rejects the second write, so
   * one of the two employees silently ends up with no record and no way to find out why — the
   * kind of failure that surfaces weeks later as "the bot says I don't work here". Better to
   * refuse the extract and have HR Ops fix the source.
   *
   * <p>An employee with no identity at all is <b>not</b> fatal. They still need to exist: they
   * appear as somebody's manager, and they count in HR's own reporting. They simply cannot
   * self-serve, so the number is reported rather than each row warned about — 5,000 individual
   * warnings is a way of telling nobody.
   *
   * @return how many employees have no identity
   */
  private int validateIdentities(Map<String, Builder> byId, List<String> warnings) {
    Map<String, String> owner = new HashMap<>();
    int unresolvable = 0;

    for (Builder b : byId.values()) {
      if (b.identities.isEmpty()) {
        unresolvable++;
        continue;
      }
      for (String identity : b.identities) {
        String existing = owner.putIfAbsent(identity, b.code);
        if (existing != null) {
          throw new IllegalStateException(
              "Duplicate identity "
                  + identity
                  + " on employees "
                  + existing
                  + " and "
                  + b.code
                  + ". Two people cannot share a sign-in address — one of them would silently"
                  + " lose their record. Fix the extract and re-import.");
        }
      }
    }

    if (unresolvable > 0) {
      warnings.add(
          unresolvable
              + " employee(s) have no email in the extract and cannot look themselves up."
              + " They are still imported, since they appear as managers and in reporting.");
    }
    return unresolvable;
  }

  /**
   * Points a signed-in email at a chosen employee code.
   *
   * <p>Now an <b>addition</b>, not a replacement: the address joins that record's identities and
   * the extract's own address keeps working. That is the whole reason identities became a list —
   * the previous version overwrote the field, which meant the real employee silently lost the
   * only way to reach their own record. The tester still sees the extract's name on the data,
   * which is honest about whose record it is.
   */
  private void applyIdentityOverrides(
      Map<String, Builder> byId, Map<String, String> overrides, List<String> warnings) {
    if (overrides == null || overrides.isEmpty()) return;

    overrides.forEach(
        (email, code) -> {
          if (email == null || code == null) return;
          Builder target = byId.get(code.trim());
          if (target == null) {
            warnings.add(
                "Identity override " + email + " -> " + code + " skipped: no such employee code.");
            return;
          }
          String added = email.trim().toLowerCase(Locale.ROOT);
          target.identities.add(added);
          log.warn(
              "IDENTITY OVERRIDE: {} now also resolves to {} ({}). Identities for that record:"
                  + " {}. This makes one account read another person's record and must not be"
                  + " configured against real employee data.",
              added,
              target.code,
              target.name,
              target.identities);
          warnings.add(
              "Identity override active: "
                  + added
                  + " resolves to "
                  + target.code
                  + " ("
                  + target.name
                  + "). Remove before using a real extract.");
        });
  }

  // -------------------------------------------------------------------------
  // Master — the spine. Header on row 1.
  // -------------------------------------------------------------------------

  private Map<String, Builder> readMaster(
      File dir, List<String> warnings, String fromDomain, String toDomain) {
    Map<String, Builder> byId = new LinkedHashMap<>();

    try (Workbook wb = open(dir, MASTER)) {
      Sheet sheet = wb.getSheetAt(0);
      Map<String, Integer> col = headerIndex(sheet.getRow(0));

      for (int r = 1; r <= sheet.getLastRowNum(); r++) {
        Row row = sheet.getRow(r);
        if (row == null) continue;
        String id = str(row, col, "Employee Id");
        if (id == null || id.isBlank()) continue;

        Builder b = new Builder();
        b.code = id.trim();
        b.name = str(row, col, "Full Name");
        String companyEmail = rewriteDomain(str(row, col, "Company Email ID"), fromDomain, toDomain);
        if (companyEmail != null && !companyEmail.isBlank()) b.identities.add(companyEmail);

        // Department comes from the hierarchy's leaf: "Operations > Legal & Compliance" is
        // filed under Operations but the department people name is the second part.
        String hierarchy = str(row, col, "Departments Hierarchy");
        String department = hierarchy;
        if (hierarchy != null && hierarchy.contains(">")) {
          department = hierarchy.substring(hierarchy.lastIndexOf('>') + 1).trim();
        }

        b.employment =
            new Employee.Employment(
                str(row, col, "Grade"),
                str(row, col, "Designation Name"),
                str(row, col, "Vertical"),
                department,
                str(row, col, "Sub-Division Name"),
                str(row, col, "Office Location"),
                date(row, col, "Date of Joining"),
                date(row, col, "Probation End Date"),
                date(row, col, "Confirmation Date"),
                str(row, col, "Employee Status"),
                str(row, col, "Direct manager name"),
                str(row, col, "L2 Manager"),
                str(row, col, "HRBP Name"));

        // Ratings across the three cycles the extract carries. achievementPercent stays null
        // throughout: the master has ratings and promotion history but no target-achievement
        // figure anywhere, which is why a payout cannot be computed without the employee
        // stating their own percentage. See the warning below.
        List<Employee.PmsRecord> pms = new ArrayList<>();
        for (String fy : List.of("FY2023-24", "FY2024-25", "FY2025-26")) {
          String rating = str(row, col, "PMS " + fy + " Rating");
          if (rating == null || rating.isBlank()) continue;
          String promoted = str(row, col, "PMS " + fy + " Promoted");
          pms.add(
              new Employee.PmsRecord(
                  fy.replace("FY", "FY "),
                  rating,
                  null,
                  "Yes".equalsIgnoreCase(promoted)
                      ? "Promoted to " + str(row, col, "PMS " + fy + " Post-Promotion Designation")
                      : null));
        }
        b.pms = pms;

        byId.put(b.code, b);
      }
    } catch (IOException e) {
      throw new IllegalStateException("Could not read " + MASTER, e);
    }

    warnings.add(
        "No target-achievement figures in the extract — the master carries PMS ratings only. "
            + "Variable payouts can be computed when an employee states their achievement %, "
            + "but there is nothing to fall back on otherwise. HR Ops needs to supply either "
            + "achievement data or an agreed rating-to-achievement mapping.");
    return byId;
  }

  // -------------------------------------------------------------------------
  // Compensation — header on row 4 (a two-line title banner sits above it).
  // -------------------------------------------------------------------------

  private int readCompensation(File dir, Map<String, Builder> byId, List<String> warnings) {
    int matched = 0;
    try (Workbook wb = open(dir, COMPENSATION)) {
      Sheet sheet = wb.getSheetAt(0);
      Map<String, Integer> col = headerIndex(sheet.getRow(3));

      for (int r = 4; r <= sheet.getLastRowNum(); r++) {
        Row row = sheet.getRow(r);
        if (row == null) continue;
        String id = str(row, col, "Employee ID");
        Builder b = id == null ? null : byId.get(id.trim());
        if (b == null) continue;

        b.compensation =
            new Employee.Compensation(
                num(row, col, "Annual Fixed CTC (₹)"),
                num(row, col, "Annual Variable Target (₹)"),
                num(row, col, "Variable % of CTC"),
                // Both, and each into its own field. These were previously one column feeding a
                // field named for the other, which is how the in-hand figure came to be reported
                // as the employee's gross.
                num(row, col, "Monthly Gross (₹)"),
                num(row, col, "Est. Monthly In-Hand (₹)"),
                null, // no prior-cycle payout in this extract
                "FY 2026-27",
                "INR",
                // The "Monthly Breakdown" block. Header keys are whitespace-collapsed, so the
                // sheet's "Basic\n(Monthly ₹)" is matched as "Basic (Monthly ₹)".
                num(row, col, "Total Target CTC (₹)"),
                num(row, col, "Monthly CTC (₹)"),
                num(row, col, "Basic (Monthly ₹)"),
                num(row, col, "HRA (Monthly ₹)"),
                num(row, col, "Special Allowance (₹)"),
                num(row, col, "Employer PF (Monthly ₹)"),
                num(row, col, "Gratuity Provision (₹)"),
                num(row, col, "Employee PF Deduction (₹)"),
                num(row, col, "Professional Tax (₹)"));
                // Deliberately NOT imported: Grade Band Min/Midpoint/Max, Compa Ratio, Band
                // Position and Last Increment %. Those are compensation-planning figures about
                // where someone sits against their peers, not payslip components — telling an
                // employee their band position is a decision for HR to take on purpose, not a
                // side effect of fixing HRA.
        matched++;
      }
    } catch (IOException e) {
      warnings.add("Could not read " + COMPENSATION + " — no compensation data imported.");
      log.warn("Compensation workbook unreadable", e);
    }
    if (matched < byId.size()) {
      warnings.add((byId.size() - matched) + " employee(s) have no compensation row.");
    }
    return matched;
  }

  // -------------------------------------------------------------------------
  // Leave — sheet 1, header on row 4. Balances only; the transaction sheet is not
  // imported, since "what is my balance" is the Phase 1 outcome and 32,689 rows of
  // history would multiply the record size for a question nobody has asked yet.
  // -------------------------------------------------------------------------

  private int readLeave(File dir, Map<String, Builder> byId, List<String> warnings) {
    int matched = 0;
    try (Workbook wb = open(dir, LEAVE)) {
      Sheet sheet = wb.getSheetAt(0);
      Map<String, Integer> col = headerIndex(sheet.getRow(3));

      for (int r = 4; r <= sheet.getLastRowNum(); r++) {
        Row row = sheet.getRow(r);
        if (row == null) continue;
        String id = str(row, col, "Employee ID");
        Builder b = id == null ? null : byId.get(id.trim());
        if (b == null) continue;

        b.leave =
            new Employee.LeaveBalances(
                num(row, col, "EL Closing Balance"),
                num(row, col, "SL Balance"),
                null, // the extract has no casual leave — EL, SL, paternity and maternity only
                num(row, col, "Maternity Balance"),
                num(row, col, "Paternity Balance"),
                LocalDate.of(2026, 3, 31)); // FY 2025-26 close, per the workbook's own banner
        matched++;
      }
    } catch (IOException e) {
      warnings.add("Could not read " + LEAVE + " — no leave balances imported.");
      log.warn("Leave workbook unreadable", e);
    }
    warnings.add(
        "The extract has no casual-leave figures (EL, SL, maternity and paternity only), so "
            + "\"what is my casual leave balance\" cannot be answered from this data.");
    return matched;
  }

  // -------------------------------------------------------------------------
  // Attendance — a 183-column daily register, Feb to Jul 2026, aggregated to months.
  // -------------------------------------------------------------------------

  /** Codes that mean the day was not a working day at all. */
  private static final List<String> NON_WORKING = List.of("WO", "H", "HO");

  private int readAttendance(File dir, Map<String, Builder> byId, List<String> warnings) {
    int matched = 0;
    try (Workbook wb = open(dir, ATTENDANCE)) {
      Sheet sheet = wb.getSheetAt(0);

      // Row 2 carries a month name above the first day-column of each block; the columns
      // between one banner and the next are that month's days.
      Row banner = sheet.getRow(1);
      Map<Integer, String> monthStarts = new LinkedHashMap<>();
      for (int c = 2; c < banner.getLastCellNum(); c++) {
        String value = cellString(banner.getCell(c));
        if (value != null && !value.isBlank()) monthStarts.put(c, value.trim());
      }
      if (monthStarts.isEmpty()) {
        warnings.add("Attendance workbook has no month banners — attendance not imported.");
        return 0;
      }
      List<Integer> starts = new ArrayList<>(monthStarts.keySet());

      for (int r = 3; r <= sheet.getLastRowNum(); r++) {
        Row row = sheet.getRow(r);
        if (row == null) continue;
        String id = cellString(row.getCell(0));
        Builder b = id == null ? null : byId.get(id.trim());
        if (b == null) continue;

        List<Employee.AttendanceMonth> months = new ArrayList<>();
        for (int m = 0; m < starts.size(); m++) {
          int from = starts.get(m);
          int to = m + 1 < starts.size() ? starts.get(m + 1) - 1 : row.getLastCellNum() - 1;

          int working = 0;
          int present = 0;
          int leave = 0;
          for (int c = from; c <= to; c++) {
            String code = cellString(row.getCell(c));
            if (code == null || code.isBlank()) continue;
            String head = code.trim().toUpperCase(Locale.ROOT);
            if (NON_WORKING.contains(head)) continue;
            working++;
            if (head.equals("P")) present++;
            else if (head.equals("L")) leave++;
            else if (head.startsWith("0.5")) present++; // a half day is still attendance
            // Everything else — A, SA, U and their regularisation variants — is neither.
          }
          if (working == 0) continue;
          months.add(
              new Employee.AttendanceMonth(
                  isoMonth(monthStarts.get(from)), working, present, leave, 0));
        }
        b.attendance = months;
        matched++;
      }
    } catch (IOException e) {
      warnings.add("Could not read " + ATTENDANCE + " — no attendance imported.");
      log.warn("Attendance workbook unreadable", e);
    }
    warnings.add(
        "The attendance legend has no work-from-home code, so WFH days are reported as 0 for "
            + "everyone. Attendance answers cover present, leave and absent days only.");
    return matched;
  }

  /** "February 2026" to "2026-02", so month strings sort chronologically as text. */
  private static String isoMonth(String monthName) {
    try {
      LocalDate parsed =
          LocalDate.parse(
              "01-" + monthName, DateTimeFormatter.ofPattern("dd-MMMM yyyy", Locale.ENGLISH));
      return parsed.toString().substring(0, 7);
    } catch (DateTimeParseException e) {
      return monthName;
    }
  }

  // -------------------------------------------------------------------------
  // Cell helpers
  // -------------------------------------------------------------------------

  /** Opens the first of {@code names} that exists, so either export convention works. */
  private static Workbook open(File dir, List<String> names) throws IOException {
    for (String name : names) {
      File file = new File(dir, name);
      if (file.isFile()) return new XSSFWorkbook(new FileInputStream(file));
    }
    throw new IOException("Missing workbook, tried " + names + " in " + dir.getAbsolutePath());
  }

  private static boolean present(File dir, List<String> names) {
    return names.stream().anyMatch(n -> new File(dir, n).isFile());
  }

  /**
   * Header label to column index.
   *
   * <p>Labels are normalised — the workbooks wrap headers across lines ({@code "Annual
   * Variable\nTarget (₹)"}), so a literal match on the visible text fails.
   */
  private static Map<String, Integer> headerIndex(Row header) {
    Map<String, Integer> index = new HashMap<>();
    if (header == null) return index;
    for (int c = 0; c < header.getLastCellNum(); c++) {
      String label = cellString(header.getCell(c));
      if (label == null || label.isBlank()) continue;
      index.put(normalise(label), c);
    }
    return index;
  }

  private static String normalise(String label) {
    return label.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
  }

  private static String str(Row row, Map<String, Integer> col, String label) {
    Integer c = col.get(normalise(label));
    return c == null ? null : cellString(row.getCell(c));
  }

  private static BigDecimal num(Row row, Map<String, Integer> col, String label) {
    Integer c = col.get(normalise(label));
    if (c == null) return null;
    Cell cell = row.getCell(c);
    if (cell == null) return null;
    try {
      if (cell.getCellType() == CellType.NUMERIC) {
        return BigDecimal.valueOf(cell.getNumericCellValue());
      }
      String text = cellString(cell);
      if (text == null || text.isBlank()) return null;
      // Percentages arrive as "66.7%" in some columns and as numbers in others.
      return new BigDecimal(text.replace("%", "").replace(",", "").replace("₹", "").trim());
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static LocalDate date(Row row, Map<String, Integer> col, String label) {
    Integer c = col.get(normalise(label));
    if (c == null) return null;
    Cell cell = row.getCell(c);
    if (cell == null) return null;
    try {
      if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
        return cell.getLocalDateTimeCellValue().toLocalDate();
      }
      String text = cellString(cell);
      if (text == null || text.isBlank()) return null;
      return LocalDate.parse(text.trim(), EXTRACT_DATE);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String cellString(Cell cell) {
    if (cell == null) return null;
    return switch (cell.getCellType()) {
      case STRING -> cell.getStringCellValue();
      case NUMERIC -> {
        if (DateUtil.isCellDateFormatted(cell)) {
          yield cell.getLocalDateTimeCellValue().toLocalDate().toString();
        }
        double d = cell.getNumericCellValue();
        yield d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
      }
      case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
      case FORMULA -> {
        try {
          yield cell.getStringCellValue();
        } catch (IllegalStateException e) {
          yield String.valueOf(cell.getNumericCellValue());
        }
      }
      default -> null;
    };
  }

  /** Rewrites the extract's mail domain onto the one identities actually sign in with. */
  private static String rewriteDomain(String email, String fromDomain, String toDomain) {
    if (email == null || email.isBlank()) return null;
    String lower = email.trim().toLowerCase(Locale.ROOT);
    if (fromDomain == null || toDomain == null || fromDomain.isBlank() || toDomain.isBlank()) {
      return lower;
    }
    String suffix = "@" + fromDomain.toLowerCase(Locale.ROOT);
    return lower.endsWith(suffix)
        ? lower.substring(0, lower.length() - suffix.length()) + "@" + toDomain.toLowerCase(Locale.ROOT)
        : lower;
  }

  /** Mutable while the four workbooks are stitched together, then frozen into a record. */
  private static final class Builder {
    String code;

    /**
     * Every address that resolves here. A list, so adding a test alias or a second domain is an
     * addition rather than an overwrite that costs somebody else their record.
     */
    final java.util.LinkedHashSet<String> identities = new java.util.LinkedHashSet<>();

    String name;
    Employee.Employment employment;
    Employee.Compensation compensation;
    Employee.LeaveBalances leave;
    List<Employee.AttendanceMonth> attendance = List.of();
    List<Employee.PmsRecord> pms = List.of();

    Employee build(Instant extractedAt) {
      return new Employee(
          code,
          List.copyOf(identities),
          name,
          employment,
          compensation,
          leave,
          attendance,
          pms,
          extractedAt);
    }
  }
}
