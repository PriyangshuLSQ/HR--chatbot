package com.leadsquared.hr.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Whether HR admins may read other employees' records through the assistant.
 *
 * <p><b>Off by default, and it contradicts the Phase 1 privacy architecture when on.</b> Rule 2
 * of the mandatory rules says every data query resolves exclusively against the session's own
 * record; this permits a narrow exception for accounts holding the {@code hr_admin} role. It
 * exists so the imported extract can be verified and the data layer exercised during
 * development, and it must be signed off by HR Ops before it is enabled anywhere real —
 * "temporarily on for testing" is how a control like this reaches production.
 *
 * <p>Two independent conditions gate it, so neither alone is enough: this flag, and the role on
 * the session. An ordinary employee account cannot reach the path however the flag is set, and a
 * misplaced flag grants nothing to non-admins.
 *
 * @param enabled master switch. Startup logs a warning while it is on.
 * @param maxRecordsPerAnswer how many other people's records may enter one prompt. A small
 *     number on purpose: the point is looking a record up, not exporting the collection, and
 *     whatever lands in the context is what an injected instruction could persuade the model to
 *     repeat back.
 */
@ConfigurationProperties(prefix = "knowledge.admin-data-access")
public record AdminDataAccessProperties(
    @DefaultValue("false") boolean enabled, @DefaultValue("3") int maxRecordsPerAnswer) {}
