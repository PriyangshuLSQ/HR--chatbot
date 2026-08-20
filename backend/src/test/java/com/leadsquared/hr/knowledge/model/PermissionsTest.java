package com.leadsquared.hr.knowledge.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The permission catalogue is a contract in three directions at once.
 *
 * <p>It drives the checkboxes in the console, it is validated on write so an unrecognised string
 * cannot be saved against a role, and {@code SecurityConfig} gates a URL with each key. A key that
 * exists in one place and not another fails quietly in the worst available way: access that looks
 * granted in the console while the endpoint keeps refusing, or the reverse.
 */
class PermissionsTest {

  @Test
  @DisplayName("every catalogue entry is a known permission, and every known one is listed")
  void catalogueAndKeysAgree() {
    assertEquals(
        Permissions.CATALOGUE.size(),
        Permissions.keys().size(),
        "CATALOGUE and keys() must describe the same set");

    for (Permissions.Definition definition : Permissions.CATALOGUE) {
      assertTrue(
          Permissions.isKnown(definition.key()),
          "catalogued but not known: " + definition.key());
    }
  }

  @Test
  @DisplayName("AREAS lists every permission except the console itself")
  void areasCoverEverythingBehindTheFrontDoor() {
    // AREAS is what the break-glass list and the pre-split migration expand to. If a permission
    // were added to the catalogue and not here, an emergency administrator would be told they
    // lack a tab the server would in fact let them open.
    var expected = new HashSet<>(Permissions.keys());
    expected.remove(Permissions.ADMIN_CONSOLE);

    assertEquals(expected, new HashSet<>(Permissions.AREAS));
    assertFalse(
        Permissions.AREAS.contains(Permissions.ADMIN_CONSOLE),
        "the console permission is the door, not a room behind it");
  }

  @Test
  @DisplayName("an unrecognised permission is refused")
  void unknownIsRejected() {
    assertFalse(Permissions.isKnown("admin.everything"));
    assertFalse(Permissions.isKnown(""));
  }

  @Test
  @DisplayName("the permissions the split was made for are distinct")
  void sensitiveIsSeparateFromTheQueue() {
    // The whole point of the exercise: working the escalation queue must not imply reading
    // harassment reports. If these two ever collapse into one key, that is silently untrue again.
    assertTrue(Permissions.isKnown(Permissions.ADMIN_TICKETS));
    assertTrue(Permissions.isKnown(Permissions.ADMIN_TICKETS_SENSITIVE));
    assertFalse(Permissions.ADMIN_TICKETS.equals(Permissions.ADMIN_TICKETS_SENSITIVE));

    List<String> keys = Permissions.keys();
    assertEquals(keys.size(), new HashSet<>(keys).size(), "duplicate permission key");
  }
}
