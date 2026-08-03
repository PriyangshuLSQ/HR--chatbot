package com.leadsquared.hr.knowledge.store;

import com.leadsquared.hr.knowledge.model.Ticket;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Escalation tickets, newest first — the order the dashboard displays them in. */
public interface TicketRepository extends MongoRepository<Ticket, String> {

  List<Ticket> findAllByOrderByCreatedAtDesc();
}
