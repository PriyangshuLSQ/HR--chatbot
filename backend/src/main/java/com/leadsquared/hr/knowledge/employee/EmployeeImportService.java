package com.leadsquared.hr.knowledge.employee;

import com.leadsquared.hr.knowledge.model.Employee;
import com.leadsquared.hr.knowledge.store.EmployeeRepository;
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
 * Loads the HR extract — four .xlsx workbooks — into the employee collection.
 *
 * <p>Joined on <b>Employee ID</b>, not email: only the master workbook carries an email at
 * all, so the ID is the one key present in every file.
 *
 * <p><b>The email domain is rewritten, and without that nothing works.</b> The extract
 * carries {@code @lsq.com} addresses while Entra identities are {@code @leadsquared.com}, so
 * a lookup keyed on the session email would match zero rows and every employee would be told
 * no record exists — a total failure that looks exactly like an empty database. The mapping is
 * configuration rather than a constant because the next extract may well use a third domain.
 *
 * <p>Two workbooks are deliberately <b>not</b> imported. {@code NPS Data} holds per-employee
 * eNPS scores and verbatim survey comments, and {@code Exit Data} holds exit-interview
 * verbatims for 1,476 leavers. Neither appears in the Phase 1 outcomes, both are far more
 * sensitive than anything the assistant needs, and survey feedback given in confidence is not
 * something to make queryable by accident. They stay out until HR Ops asks for them.
 */
@Service
public class EmployeeImportService {

  private static final Logger log = LoggerFactory.getLogger(EmployeeImportService.class);

  private static final String MASTER = "Dummy_Employee_Master_Data.xlsx";
  private static final String COMPENSATION = "Compensation Records.xlsx";
  private static final String LEAVE = "Employee_Leave_Records.xlsx";
  private static final String ATTENDANCE = "Attendance Records.xlsx";

  /** Extract dates are written like {@code 31-Mar-2023}. */
  private static final DateTimeFormatter EXTRACT_DATE =
      DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH);

  private final EmployeeRepository employees;

  /**
   * Only for dropping the collection — see {@link #importFrom}. Not a second read path onto
   * employee data: nothing here queries it.
   */
  private final MongoTemplate mongo;

  public EmployeeImportService(EmployeeRepository employees, MongoTemplate mongo) {
    this.employees = employees;
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
                str(row, col, "L2 Manager"));

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
                num(row, col, "Est. Monthly In-Hand (₹)"),
                null, // no prior-cycle payout in this extract
                "FY 2026-27",
                "INR");
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

  private static Workbook open(File dir, String name) throws IOException {
    File file = new File(dir, name);
    if (!file.isFile()) throw new IOException("Missing workbook: " + file.getAbsolutePath());
    return new XSSFWorkbook(new FileInputStream(file));
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
