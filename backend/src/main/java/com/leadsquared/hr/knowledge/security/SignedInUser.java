package com.leadsquared.hr.knowledge.security;

/**
 * The signed-in employee, reduced to what this application actually uses.
 *
 * <p>Field names are the wire contract with {@code lib/chatbot-auth.tsx}.
 *
 * @param email the account handle, lower-cased. Everything user-scoped keys on
 *     this — chat threads, who raised a ticket — so it is taken from the token
 *     and never from a request parameter.
 * @param role {@code employee} or {@code hr_admin}
 */
public record SignedInUser(String id, String email, String name, String role) {

  public static final String EMPLOYEE = "employee";
  public static final String HR_ADMIN = "hr_admin";

  public boolean isAdmin() {
    return HR_ADMIN.equals(role);
  }
}
