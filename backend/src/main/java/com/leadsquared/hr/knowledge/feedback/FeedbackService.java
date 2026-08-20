package com.leadsquared.hr.knowledge.feedback;

import com.leadsquared.hr.knowledge.model.Feedback;
import com.leadsquared.hr.knowledge.store.FeedbackRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/** Records answer ratings and hands the raw set to the console for the digest. */
@Service
public class FeedbackService {

  private final FeedbackRepository feedback;

  public FeedbackService(FeedbackRepository feedback) {
    this.feedback = feedback;
  }

  public record RecordFeedback(
      String id,
      String messageId,
      String rating,
      String comment,
      String intentId,
      String intentLabel,
      String query,
      double confidence) {}

  public List<Feedback> list() {
    return feedback.findAllByOrderByCreatedAtDesc();
  }

  public long count() {
    return feedback.count();
  }

  /**
   * One rating per message: an employee changing their mind replaces the earlier
   * vote instead of stacking a second one.
   *
   * @throws IllegalArgumentException on a missing message id or a rating that is
   *     neither up nor down
   */
  public Feedback record(RecordFeedback input) {
    if (input.messageId() == null || input.messageId().isBlank()) {
      throw new IllegalArgumentException("Feedback needs the id of the message being rated.");
    }
    if (!"up".equals(input.rating()) && !"down".equals(input.rating())) {
      throw new IllegalArgumentException("Rating must be up or down.");
    }

    // Delete-then-insert rather than an upsert on messageId: the id is part of
    // the record the client already holds, and replacing the document wholesale
    // means a re-rating cannot leave a stale comment from the previous vote.
    feedback.deleteByMessageId(input.messageId());

    return feedback.save(
        new Feedback(
            input.id() == null || input.id().isBlank()
                ? "FB-" + Long.toString(System.currentTimeMillis(), 36).toUpperCase()
                : input.id(),
            input.messageId(),
            input.rating(),
            // Only a thumbs-down carries a comment; drop anything sent with an up
            // so the digest's comment list is exactly the complaints.
            "down".equals(input.rating()) ? blankToNull(input.comment()) : null,
            blankToNull(input.intentId()),
            blankToNull(input.intentLabel()),
            input.query() == null ? "" : input.query(),
            input.confidence(),
            Instant.now().toString()));
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
