package com.leadsquared.hr.knowledge.model;

import java.time.LocalDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One employee's answer to the engagement survey.
 *
 * <p><b>Its own collection, and deliberately not part of {@link Employee}.</b> The assistant loads
 * an employee's record to answer questions about their grade, leave and pay; if a survey score and
 * a verbatim comment travelled inside that record they would be one prompt-construction mistake
 * away from being quoted back. Keeping them apart means the assistant cannot surface them by
 * accident, because it never reads this collection at all.
 *
 * <p>Stored because the data was supplied and HR analytics will want it. Not wired into any answer
 * path: none of the stated outcomes involve eNPS, and a comment given to a survey is not something
 * to make queryable on the strength of it being available.
 *
 * @param employeeCode whose response this is. Present in the source, so the survey is not
 *     anonymous — which is exactly why the collection is handled at arm's length.
 * @param score 0–10, the standard eNPS scale
 * @param category Promoter / Passive / Detractor, as the source classifies it
 * @param comment the verbatim answer. The most sensitive field here.
 */
@Document("enps_responses")
public record EnpsResponse(
    @Id String id,
    @Indexed String employeeCode,
    String name,
    String vertical,
    String grade,
    Integer score,
    String category,
    LocalDate surveyDate,
    String comment) {}
