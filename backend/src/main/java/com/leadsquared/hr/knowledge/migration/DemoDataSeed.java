package com.leadsquared.hr.knowledge.migration;

import com.leadsquared.hr.knowledge.model.Feedback;
import com.leadsquared.hr.knowledge.model.Ticket;
import com.leadsquared.hr.knowledge.model.TicketComment;
import com.leadsquared.hr.knowledge.store.FeedbackRepository;
import com.leadsquared.hr.knowledge.store.TicketRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Seeds the tickets and feedback that used to be hardcoded as {@code SEED_TICKETS}
 * and {@code SEED_FEEDBACK} in {@code lib/hr-store.ts}.
 *
 * <p>They existed so a fresh install had a dashboard worth looking at rather than
 * three empty panels. That is still worth having, but it belongs on the server now
 * — while the seeds lived in the frontend they were a *fallback for an empty
 * store*, which meant a real store that happened to be empty showed fake
 * escalations. Here they are written once and then are ordinary rows.
 *
 * <p>Runs only when both collections are empty, so a second boot is a no-op and
 * real HR data is never overwritten. A Mongo outage at boot logs and moves on: the
 * knowledge base answers questions without this, and failing startup over demo
 * content would be the wrong trade.
 */
@Component
@Order(20)
public class DemoDataSeed implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(DemoDataSeed.class);

  private final TicketRepository tickets;
  private final FeedbackRepository feedback;

  public DemoDataSeed(TicketRepository tickets, FeedbackRepository feedback) {
    this.tickets = tickets;
    this.feedback = feedback;
  }

  @Override
  public void run(ApplicationArguments args) {
    try {
      if (tickets.count() == 0) {
        tickets.saveAll(seedTickets());
        log.info("Seeded {} demo tickets", 3);
      }
      if (feedback.count() == 0) {
        feedback.saveAll(seedFeedback());
        log.info("Seeded {} demo feedback entries", 8);
      }
    } catch (DataAccessException e) {
      log.warn(
          "Could not seed demo data — MongoDB is unreachable. Tickets and feedback will be"
              + " unavailable until it is: {}",
          e.getMessage());
    }
  }

  private static String daysAgo(int n) {
    return Instant.now().minus(n, ChronoUnit.DAYS).toString();
  }

  private static List<Ticket> seedTickets() {
    return List.of(
        new Ticket(
            "SEN-4C2A1",
            "I want to report inappropriate behaviour by a senior colleague",
            "Sensitive matter — routed immediately, bypassing the HR Ops queue",
            "anonymous@company.com",
            "Withheld (confidential)",
            daysAgo(1),
            "in-progress",
            "critical",
            "hr_head",
            "HR Ops Team",
            List.of("sensitive", "posh", "urgent", "confidential"),
            1.0,
            List.of(),
            true,
            List.of()),
        new Ticket(
            "HR-9B71D",
            "My relocation allowance was not included in the last payroll",
            "Out of scope — no matching policy in the knowledge base",
            "employee@company.com",
            "Ananya Sharma",
            daysAgo(2),
            "open",
            "normal",
            "hr_ops",
            "HR Ops Queue",
            List.of("payroll", "out-of-scope"),
            0.18,
            List.of(),
            false,
            List.of()),
        new Ticket(
            "HR-7A33C",
            "Can I convert my notice period buyout into leave encashment?",
            "Low confidence after clarification — needs a human decision",
            "employee@company.com",
            "Ananya Sharma",
            daysAgo(4),
            "resolved",
            "normal",
            "hr_ops",
            "HR Ops Queue",
            List.of("exit", "low-confidence"),
            0.34,
            List.of(),
            false,
            List.of()));
  }

  private static List<Feedback> seedFeedback() {
    return List.of(
        new Feedback(
            "FB-SEED1", "seed-1", "down",
            "This gave me the policy but not my actual balance.",
            "leave_policy", "Leave policy & entitlement",
            "how many leaves do i have", 0.61, daysAgo(1)),
        new Feedback(
            "FB-SEED2", "seed-2", "down",
            "Did not answer about arrears at all.",
            "salary_date", "When salary is credited",
            "when will my arrears be paid", 0.44, daysAgo(2)),
        new Feedback(
            "FB-SEED3", "seed-3", "down", null,
            "salary_date", "When salary is credited",
            "salary credited less this month", 0.52, daysAgo(3)),
        new Feedback(
            "FB-SEED4", "seed-4", "up", null,
            "insurance_benefits", "Health insurance & benefits",
            "is my father covered", 0.83, daysAgo(1)),
        new Feedback(
            "FB-SEED5", "seed-5", "up", null,
            "leave_apply", "How to apply for leave",
            "how to apply leave", 0.95, daysAgo(2)),
        new Feedback(
            "FB-SEED6", "seed-6", "up", null,
            "wfh_policy", "Work-from-home policy",
            "wfh rules", 0.88, daysAgo(3)),
        new Feedback(
            "FB-SEED7", "seed-7", "up", null,
            "tax_form16", "Tax / Form 16",
            "form 16 download", 0.91, daysAgo(4)),
        new Feedback(
            "FB-SEED8", "seed-8", "up", null,
            "leave_balance", "My leave balance",
            "leave balance", 0.97, daysAgo(5)));
  }
}
