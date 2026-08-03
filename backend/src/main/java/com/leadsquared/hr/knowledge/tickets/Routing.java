package com.leadsquared.hr.knowledge.tickets;

import java.util.Set;

/**
 * Who an escalation goes to.
 *
 * <p>The only definition of this rule. The frontend used to carry a copy in
 * {@code routeFor}; it was removed when tickets moved here, because two versions
 * of "does this bypass the shared queue" is exactly the kind of duplication whose
 * failure mode is a harassment report sitting in HR Ops' inbox.
 *
 * <p>Callers send {@code route} and {@code sensitive}. They do not get to choose
 * their own priority or assignee.
 */
public final class Routing {

  private Routing() {}

  public static final Set<String> STATUSES = Set.of("open", "in-progress", "resolved");
  public static final Set<String> PRIORITIES = Set.of("critical", "high", "normal");
  public static final Set<String> ROUTES = Set.of("hr_ops", "hrbp", "hr_head", "none");

  /**
   * @param route where the NLU suggested this should go
   * @param sensitive harassment, grievances, anything a shared queue must not see
   */
  public record Decision(String route, String assignee, String priority, boolean confidential) {}

  static String assigneeFor(String route) {
    return switch (route) {
      case "hr_ops" -> "HR Ops Queue";
      case "hrbp" -> "Meera Iyer (HRBP)";
      case "hr_head" -> "Priya Nair (HR Head)";
      default -> "Unassigned";
    };
  }

  /**
   * Sensitive matters are always critical and always bypass the shared HR Ops
   * queue. That decision is made here rather than at the call site so it cannot
   * be forgotten by a future caller — including a caller that posts straight to
   * the API without going through the chat window.
   */
  public static Decision decide(String route, boolean sensitive) {
    String requested = route == null || !ROUTES.contains(route) ? "none" : route;

    if (sensitive) {
      String target = "hrbp".equals(requested) ? "hrbp" : "hr_head";
      return new Decision(target, assigneeFor(target), "critical", true);
    }

    String target = "none".equals(requested) ? "hr_ops" : requested;
    return new Decision(target, assigneeFor(target), "normal", false);
  }
}
