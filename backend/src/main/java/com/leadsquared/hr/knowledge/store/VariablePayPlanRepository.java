package com.leadsquared.hr.knowledge.store;

import com.leadsquared.hr.knowledge.model.VariablePayPlan;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * The variable pay plans, keyed by which policy they represent.
 *
 * <p>Read whenever an employee asks what their payout would be, so it is cached in {@code
 * VariablePayPlanService} rather than fetched per question.
 */
public interface VariablePayPlanRepository extends MongoRepository<VariablePayPlan, String> {

  Optional<VariablePayPlan> findByPlanKey(String planKey);
}
