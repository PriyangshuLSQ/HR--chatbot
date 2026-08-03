package com.leadsquared.hr.knowledge.store;

import com.leadsquared.hr.knowledge.model.Feedback;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Answer ratings. The digest reads the trailing week; the console shows a count. */
public interface FeedbackRepository extends MongoRepository<Feedback, String> {

  List<Feedback> findAllByOrderByCreatedAtDesc();

  /** Re-rating replaces the earlier vote, so the digest counts employees not clicks. */
  void deleteByMessageId(String messageId);
}
