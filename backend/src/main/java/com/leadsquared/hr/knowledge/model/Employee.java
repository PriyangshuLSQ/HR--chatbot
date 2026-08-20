package com.leadsquared.hr.knowledge.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One employee's record from the static HR extract.
 *
 * <p><b>Never embedded, never retrieved by similarity.</b> Policy documents go into
 * Qdrant because they are the same for everyone; these do not, because vector search
 * ranks by resemblance rather than ownership — the record closest to "what is my CTC"
 * is whichever compensation record embeds nearest, which need not be the caller's. That
 * is a cross-employee leak with no filter to forget, so this data is reached only by a
 * keyed lookup on {@link #identities}. See {@code EmployeeDataService}.
 *
 * @param identities every address that resolves to this person, lower-cased — the only field a
 *     query is ever allowed to be keyed on, and the value always comes from the session token.
 *     <p>A list rather than one address because organisations break the one-email assumption
 *     constantly: a legacy domain after an acquisition (this extract already mixes
 *     {@code @lsq.com} and {@code @leadsquared.com}), a name change on marriage, or Entra's
 *     guest mangling of {@code bnalamat@gitam.in} into {@code bnalamat_gitam.in#EXT#@…}. With a
 *     single field each of those is a destructive rewrite that silently costs somebody their
 *     record; as a list, HR admin adds an address and both keep working.
 *     <p>Unique across the whole collection, not just within a record — the index is multikey,
 *     so two employees cannot share an address even by accident.
 * @param extractedAt when HR Ops took the extract. Surfaced in every answer built from
 *     this record: leave balances and attendance drift from payroll the day after the
 *     extract, and an employee told "12 days" when payroll says 9 will reasonably stop
 *     trusting everything else the assistant says.
 */
@Document("employees")
public record Employee(
    @Id String employeeCode,
    @Indexed(unique = true) List<String> identities,
    String name,
    Employment employment,
    Compensation compensation,
    LeaveBalances leave,
    List<AttendanceMonth> attendance,
    List<PmsRecord> pms,
    Instant extractedAt) {

  /** Whether any signed-in identity maps here. False means this person cannot self-serve. */
  public boolean isResolvable() {
    return identities != null && !identities.isEmpty();
  }

  /**
   * @param reportingManagerName the caller's own manager, which is theirs to know — "who
   *     is my reporting manager" is a required outcome. Deliberately stored as a name and
   *     not as a foreign key: a manager's employee code in this record would be an
   *     identifier sitting one dereference away from their compensation, and there is no
   *     dereference to make if it is not here.
   */
  public record Employment(
      String grade,
      String designation,
      String businessUnit,
      String department,
      String function,
      String location,
      LocalDate dateOfJoining,
      LocalDate probationEndDate,
      LocalDate confirmationDate,
      String employmentStatus,
      String reportingManagerName,
      String l2ManagerName,
      /**
       * The employee's HR business partner, from the master's {@code HRBP Name} column.
       *
       * <p>Held here beside the two managers because employees ask for it the same way and for
       * the same reason — "who do I take this to". It was the one name in that set the extract
       * carried and the import dropped, so "who is my HRBP?" was answered with "not in the
       * documents I have access to" while the answer sat in the spreadsheet.
       */
      String hrbpName) {}

  /**
   * @param variableTargetAmount the annual variable pay target in rupees — the figure the
   *     payout matrix multiplies. Not a percentage, so no rounding is inherited from one.
   * @param monthlyGross monthly pay before the employee's own deductions, from the extract's
   *     {@code Monthly Gross (₹)} column.
   * @param monthlyInHand what reaches the bank, after employee PF and professional tax, from
   *     {@code Est. Monthly In-Hand (₹)}. Two fields rather than one because they are two
   *     different numbers and the difference is the employee's deductions: this record used to
   *     carry a single {@code monthlyFixedGross} loaded from the in-hand column, so "what is my
   *     monthly gross salary" was answered with the in-hand figure — 62,100 against a true gross
   *     of 64,100, stated flatly and with no caveat. A wrong number about someone's pay is the
   *     failure every grounding rule here exists to prevent, and it came from the mapping rather
   *     than the model. Keep them distinct, and label them as what they are.
   * @param lastPayoutAmount what was actually paid last cycle, for "what was my variable
   *     payout in the last appraisal cycle"
   */
  public record Compensation(
      BigDecimal fixedCtc,
      BigDecimal variableTargetAmount,
      BigDecimal variablePercentOfCtc,
      BigDecimal monthlyGross,
      BigDecimal monthlyInHand,
      BigDecimal lastPayoutAmount,
      String lastPayoutCycle,
      String currency) {}

  /** @param asOf leave balances are the fastest-moving field in the extract. */
  public record LeaveBalances(
      BigDecimal earned,
      BigDecimal sick,
      BigDecimal casual,
      BigDecimal maternity,
      BigDecimal paternity,
      LocalDate asOf) {}

  /** @param month ISO {@code YYYY-MM}, so string ordering is chronological ordering. */
  public record AttendanceMonth(
      String month, int workingDays, int presentDays, int leaveDays, int wfhDays) {}

  /**
   * @param achievementPercent target achievement for the cycle. The input the payout
   *     matrix is applied to when an employee does not name a figure themselves.
   */
  public record PmsRecord(
      String cycle, String rating, BigDecimal achievementPercent, String remarks) {}
}
