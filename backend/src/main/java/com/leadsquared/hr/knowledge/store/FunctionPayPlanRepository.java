package com.leadsquared.hr.knowledge.store;

import com.leadsquared.hr.knowledge.model.FunctionPayPlan;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** The revenue-function pay policies, keyed by which policy they represent. */
public interface FunctionPayPlanRepository extends MongoRepository<FunctionPayPlan, String> {

  Optional<FunctionPayPlan> findByPlanKey(String planKey);
}
