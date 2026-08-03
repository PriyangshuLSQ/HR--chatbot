package com.leadsquared.hr.knowledge.web;

import java.util.Map;
import org.springframework.http.ResponseEntity;

/**
 * Error bodies.
 *
 * <p>The frontend client reads {@code body.error} for the message it shows the
 * user, so every failure response carries that one key and nothing else.
 */
final class ApiErrors {

  private ApiErrors() {}

  static ResponseEntity<Map<String, String>> badRequest(String message) {
    return ResponseEntity.badRequest().body(Map.of("error", message));
  }
}
