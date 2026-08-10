package com.leadsquared.hr.knowledge.employee;

import com.leadsquared.hr.knowledge.model.Employee;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

/**
 * Renders the caller's own record into facts for the prompt.
 *
 * <p>Only the fields the question needs are included. Not for prompt economy — for blast
 * radius: whatever is put here is what an injected instruction could persuade the model to
 * repeat, so a leave question does not carry the caller's CTC into the context alongside it.
 *
 * <p>Every block carries the extract date. A static extract diverges from payroll the day
 * after it is taken, and an employee told "12 days" when payroll says 9 will discount
 * everything else the assistant says — so the answer says how old the figure is rather than
 * implying it is live.
 */
public final class EmployeeFacts {

  private EmployeeFacts() {}

  private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");

  /**
   * @param fields the subset from {@link PersonalDataIntent#fieldsFor(String)}
   * @return a fact block addressed to the model, never shown to the employee verbatim
   */
  public static String render(Employee employee, Set<String> fields) {
    StringBuilder out = new StringBuilder();
    out.append("EMPLOYEE RECORD — the signed-in employee's own data.\n");
    out.append(
        "This record is an authoritative source, exactly like the policy extracts. A question"
            + " answered by it is answered — do NOT reply NOT_IN_DOCUMENTS because the figure is"
            + " absent from the policy extracts. Quote these values exactly; never adjust or"
            + " recompute them.\n");
    out.append(
        "This is the only employee record available to you. You have no access to any other"
            + " employee's data, and must not estimate, infer or compare against anyone else's.\n");
    out.append("Name: ").append(employee.name()).append("\n");
    out.append("Employee code: ").append(employee.employeeCode()).append("\n");

    Employee.Employment job = employee.employment();
    if (fields.contains(PersonalDataIntent.EMPLOYMENT) && job != null) {
      out.append("\nEmployment:\n");
      line(out, "Grade", job.grade());
      line(out, "Designation", job.designation());
      line(out, "Business unit", job.businessUnit());
      line(out, "Department", job.department());
      line(out, "Function", job.function());
      line(out, "Location", job.location());
      line(out, "Employment status", job.employmentStatus());
      line(out, "Reporting manager", job.reportingManagerName());
      line(out, "L2 manager", job.l2ManagerName());
      line(out, "Date of joining", job.dateOfJoining());
      line(out, "Probation end date", job.probationEndDate());
      line(out, "Confirmation date", job.confirmationDate());
      if (job.dateOfJoining() != null) {
        line(out, "Tenure as of today", tenure(job.dateOfJoining()));
      }
    }

    Employee.Compensation pay = employee.compensation();
    if (pay != null) {
      String unit = pay.currency() == null ? "INR" : pay.currency();
      if (fields.contains(PersonalDataIntent.COMPENSATION)) {
        out.append("\nCompensation:\n");
        money(out, "Fixed CTC (annual)", pay.fixedCtc(), unit);
        money(out, "Monthly fixed gross", pay.monthlyFixedGross(), unit);
      }
      if (fields.contains(PersonalDataIntent.VARIABLE_PAY)) {
        out.append("\nVariable pay:\n");
        money(out, "Annual variable target", pay.variableTargetAmount(), unit);
        if (pay.variablePercentOfCtc() != null) {
          line(out, "Variable as % of CTC", trim(pay.variablePercentOfCtc()) + "%");
        }
        money(out, "Last cycle payout", pay.lastPayoutAmount(), unit);
        line(out, "Last payout cycle", pay.lastPayoutCycle());
      }
    }

    Employee.LeaveBalances leave = employee.leave();
    if (fields.contains(PersonalDataIntent.LEAVE) && leave != null) {
      out.append("\nLeave balances");
      if (leave.asOf() != null) out.append(" (as of ").append(leave.asOf()).append(")");
      out.append(":\n");
      days(out, "Earned leave", leave.earned());
      days(out, "Sick leave", leave.sick());
      days(out, "Casual leave", leave.casual());
      days(out, "Maternity leave", leave.maternity());
      days(out, "Paternity leave", leave.paternity());
    }

    List<Employee.AttendanceMonth> attendance = employee.attendance();
    if (fields.contains(PersonalDataIntent.ATTENDANCE) && attendance != null && !attendance.isEmpty()) {
      out.append("\nAttendance, most recent months last:\n");
      // Last three, because "the last three months" is the question actually asked. ISO
      // YYYY-MM sorts chronologically as text, so no date parsing is needed to order these.
      attendance.stream()
          .sorted(java.util.Comparator.comparing(Employee.AttendanceMonth::month))
          .skip(Math.max(0, attendance.size() - 3))
          .forEach(
              m ->
                  out.append("- ")
                      .append(m.month())
                      .append(": present ")
                      .append(m.presentDays())
                      .append("/")
                      .append(m.workingDays())
                      .append(" working days, ")
                      .append(m.leaveDays())
                      .append(" on leave, ")
                      .append(m.wfhDays())
                      .append(" from home\n"));
    }

    List<Employee.PmsRecord> pms = employee.pms();
    if (fields.contains(PersonalDataIntent.PMS) && pms != null && !pms.isEmpty()) {
      out.append("\nPerformance history:\n");
      pms.forEach(
          p -> {
            out.append("- ").append(p.cycle()).append(": rating ").append(p.rating());
            if (p.achievementPercent() != null) {
              out.append(", achievement ").append(trim(p.achievementPercent())).append("%");
            }
            if (p.remarks() != null && !p.remarks().isBlank()) {
              out.append(" — ").append(p.remarks());
            }
            out.append("\n");
          });
    }

    if (employee.extractedAt() != null) {
      LocalDate extracted = employee.extractedAt().atZone(ZoneId.systemDefault()).toLocalDate();
      out.append("\nData extract date: ").append(extracted).append(".\n");
      out.append(
          "State this date when giving leave balances, attendance or any figure that changes"
              + " over time, so the employee knows how current it is.\n");
    }

    return out.toString();
  }

  private static void line(StringBuilder out, String label, Object value) {
    if (value == null || String.valueOf(value).isBlank()) return;
    out.append("- ").append(label).append(": ").append(value).append("\n");
  }

  private static void money(StringBuilder out, String label, BigDecimal value, String unit) {
    if (value == null) return;
    out.append("- ").append(label).append(": ").append(unit).append(" ")
        .append(MONEY.format(value)).append("\n");
  }

  private static void days(StringBuilder out, String label, BigDecimal value) {
    if (value == null) return;
    out.append("- ").append(label).append(": ").append(trim(value)).append(" days\n");
  }

  private static String tenure(LocalDate joined) {
    Period period = Period.between(joined, LocalDate.now());
    int years = period.getYears();
    int months = period.getMonths();
    if (years == 0) return months + (months == 1 ? " month" : " months");
    return years
        + (years == 1 ? " year " : " years ")
        + months
        + (months == 1 ? " month" : " months");
  }

  private static String trim(BigDecimal value) {
    return value.stripTrailingZeros().toPlainString();
  }
}
