package com.leadsquared.hr.knowledge.store;

import com.leadsquared.hr.knowledge.model.EnpsResponse;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Engagement survey responses.
 *
 * <p>Write-and-count only, matching {@link EmployeeRepository}'s reasoning: no finder by name, by
 * vertical, by grade or by score. The import saves, the console counts, and nothing reads a
 * response back — so no answer path can reach a colleague's survey comment even by mistake.
 *
 * <p>When HR Ops does want reporting on this, it should arrive as an explicit aggregate endpoint
 * with its own access rule, not as a finder added here.
 */
public interface EnpsResponseRepository extends MongoRepository<EnpsResponse, String> {}
