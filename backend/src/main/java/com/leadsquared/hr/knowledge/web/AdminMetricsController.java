package com.leadsquared.hr.knowledge.web;

import com.leadsquared.hr.knowledge.metrics.ConversationMetricsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Aggregate usage numbers for the admin overview.
 *
 * <p>Under {@code /api/admin} so it inherits the console's existing gate — SecurityConfig routes
 * {@code /api/admin/**} through the admin check, re-decided per request. That placement is not
 * incidental: conversation volume is the kind of aggregate that is fine for an HR lead and is
 * nobody else's business, and putting it beside the audit and login endpoints means it cannot be
 * reached without the same role they need.
 *
 * <p>Counts only. See {@link ConversationMetricsService} for why nothing here can return a
 * transcript.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminMetricsController {

  private final ConversationMetricsService metrics;

  public AdminMetricsController(ConversationMetricsService metrics) {
    this.metrics = metrics;
  }

  /**
   * @param days trailing whole days to measure, default 7. Clamped to 1..90 by the service rather
   *     than rejected, so a hand-edited URL degrades to a sane chart instead of a 400.
   */
  @GetMapping("/metrics")
  public ConversationMetricsService.Metrics metrics(
      @RequestParam(name = "days", required = false) Integer days) {
    return metrics.compute(days == null ? 7 : days);
  }
}
