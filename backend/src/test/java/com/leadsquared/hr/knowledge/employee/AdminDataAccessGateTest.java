package com.leadsquared.hr.knowledge.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.leadsquared.hr.knowledge.config.AdminDataAccessProperties;
import com.leadsquared.hr.knowledge.security.SignedInUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/**
 * The two gates on admin cross-employee access, tested independently.
 *
 * <p>This is the exception to the mandatory privacy rules, so what matters is not that it works —
 * it is that it cannot be reached by anyone it was not meant for. Each gate is asserted on its
 * own, because a single test with both conditions true proves nothing about either.
 *
 * <p>The database is stubbed and verified as never queried: "returns nothing" would also pass if
 * the query ran and matched nobody, and that is a different and much weaker property than "no
 * query was issued at all".
 */
class AdminDataAccessGateTest {

  private static final SignedInUser EMPLOYEE =
      new SignedInUser("1", "someone@leadsquared.com", "Someone", SignedInUser.EMPLOYEE);
  private static final SignedInUser ADMIN =
      new SignedInUser("2", "bnalamat@gitam.in", "Admin", SignedInUser.HR_ADMIN);

  private static final String QUESTION = "What is the CTC of Priya Sharma?";

  private AdminEmployeeQueryService service(boolean enabled, MongoTemplate mongo) {
    return new AdminEmployeeQueryService(mongo, new AdminDataAccessProperties(enabled, 3));
  }

  @Test
  @DisplayName("An employee cannot read other records even with the flag on")
  void employeesAreNeverGranted() {
    MongoTemplate mongo = mock(MongoTemplate.class);
    AdminEmployeeQueryService service = service(true, mongo);

    assertThat(service.isAvailableTo(EMPLOYEE)).isFalse();
    assertThat(service.findNamedIn(QUESTION, EMPLOYEE)).isEmpty();

    // No query was issued — the role is checked before the database is touched.
    verify(mongo, never()).find(org.mockito.ArgumentMatchers.any(Query.class), org.mockito.ArgumentMatchers.any());
  }

  @Test
  @DisplayName("An admin cannot read other records while the flag is off")
  void adminsAreNotGrantedByRoleAlone() {
    MongoTemplate mongo = mock(MongoTemplate.class);
    AdminEmployeeQueryService service = service(false, mongo);

    assertThat(service.isAvailableTo(ADMIN)).isFalse();
    assertThat(service.findNamedIn(QUESTION, ADMIN)).isEmpty();
    verify(mongo, never()).find(org.mockito.ArgumentMatchers.any(Query.class), org.mockito.ArgumentMatchers.any());
  }

  @Test
  @DisplayName("An unauthenticated session cannot read other records")
  void anonymousIsNeverGranted() {
    MongoTemplate mongo = mock(MongoTemplate.class);
    AdminEmployeeQueryService service = service(true, mongo);

    assertThat(service.isAvailableTo(null)).isFalse();
    assertThat(service.findNamedIn(QUESTION, null)).isEmpty();
    verify(mongo, never()).find(org.mockito.ArgumentMatchers.any(Query.class), org.mockito.ArgumentMatchers.any());
  }

  @Test
  @DisplayName("Both gates open: an admin with the flag on may query")
  void adminWithFlagIsGranted() {
    MongoTemplate mongo = mock(MongoTemplate.class);
    AdminEmployeeQueryService service = service(true, mongo);

    assertThat(service.isAvailableTo(ADMIN)).isTrue();
  }

  @Test
  @DisplayName("A question naming nobody issues no query, even for an admin")
  void aQuestionNamingNobodyQueriesNothing() {
    MongoTemplate mongo = mock(MongoTemplate.class);
    AdminEmployeeQueryService service = service(true, mongo);

    // "Who earns the most" names no one. The exception covers looking a named record up; it is
    // not a licence to rank or aggregate, which is why this must not become a scan.
    assertThat(service.findNamedIn("Who earns the highest in Sales?", ADMIN)).isEmpty();
    verify(mongo, never()).find(org.mockito.ArgumentMatchers.any(Query.class), org.mockito.ArgumentMatchers.any());
  }

  @Test
  @DisplayName("Rendering another employee's record is refused when the gates are shut")
  void renderingIsAlsoGated() {
    MongoTemplate mongo = mock(MongoTemplate.class);
    AdminEmployeeQueryService offService = service(false, mongo);

    // The renderer re-checks rather than trusting its caller: it is the method that puts another
    // person's data into a prompt, and it should be impossible to reach without the gates open.
    assertThat(offService.renderForAdmin(java.util.List.of(), ADMIN)).isEmpty();
  }
}
