package com.leadsquared.hr.knowledge.web;

import com.leadsquared.hr.knowledge.feedback.FeedbackService;
import com.leadsquared.hr.knowledge.model.Feedback;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Answer ratings.
 *
 * <p>The whole set is returned rather than a pre-aggregated digest: the digest
 * grouping already exists in {@code buildWeeklyDigest} in {@code lib/hr-store.ts}
 * and is covered by the frontend's own tests, so duplicating that arithmetic here
 * would create a second version of it to keep honest. The volume is one row per
 * rated answer — small enough that shipping it whole is not the bottleneck.
 */
@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

  private final FeedbackService feedback;

  public FeedbackController(FeedbackService feedback) {
    this.feedback = feedback;
  }

  public record FeedbackList(List<Feedback> feedback, long count) {}

  @GetMapping
  public FeedbackList list() {
    List<Feedback> all = feedback.list();
    return new FeedbackList(all, all.size());
  }

  @PostMapping
  public ResponseEntity<Feedback> record(@RequestBody FeedbackService.RecordFeedback body) {
    return ResponseEntity.status(HttpStatus.CREATED).body(feedback.record(body));
  }
}
