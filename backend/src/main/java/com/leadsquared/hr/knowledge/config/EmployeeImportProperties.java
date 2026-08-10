package com.leadsquared.hr.knowledge.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the HR extract lives and how its identities map onto sign-in identities.
 *
 * <p>Its own properties record rather than another member of {@link KnowledgeProperties}
 * because it is operational configuration for a once-per-extract job, not runtime tuning for
 * the request path — and because a separate record is picked up by the application's
 * {@code @ConfigurationPropertiesScan} without every existing caller of the other one changing
 * shape.
 *
 * @param directory folder holding the workbooks. Left blank by default: the extract contains
 *     5,000 people's compensation and is not something to have a default path for.
 * @param fromDomain the mail domain the extract uses, e.g. {@code lsq.com}
 * @param toDomain the domain identities actually sign in with, e.g. {@code leadsquared.com}.
 *     Without this rewrite a lookup keyed on the session email matches nothing and every
 *     employee is told they have no record.
 * @param identityOverrides signed-in email to employee code, applied after the import.
 *     <p>This exists for testing, and it is worth being blunt about what it does: it makes one
 *     account resolve to somebody else's record. That is exactly the kind of mapping the
 *     privacy rules are built to prevent, so it is legitimate only because the extract is dummy
 *     data and the mapping is explicit, reviewable configuration rather than something a
 *     question can influence. It has no place in a deployment holding real employee data —
 *     the import logs every override at WARN so its presence cannot be quiet.
 *     <p>YAML keys containing {@code @} or {@code .} need bracket quoting:
 *     <pre>
 *     identity-overrides:
 *       "[someone@example.com]": LS00008
 *     </pre>
 */
@ConfigurationProperties(prefix = "knowledge.employee-import")
public record EmployeeImportProperties(
    @DefaultValue("") String directory,
    @DefaultValue("") String fromDomain,
    @DefaultValue("") String toDomain,
    @DefaultValue("false") boolean onStartup,
    Map<String, String> identityOverrides) {

  /**
   * Whether to run the import once at boot.
   *
   * <p>Default false, and it should stay false in anything long-lived: an import replaces the
   * whole employee collection, so a restart loop would repeatedly wipe and reload 5,000 records
   * — and a deploy that reloads data as a side effect of restarting is a deploy that can undo a
   * correction someone made by hand. Pass it as an argument for a one-off load:
   *
   * <pre>
   * mvn spring-boot:run -Dspring-boot.run.arguments=--knowledge.employee-import.on-startup=true
   * </pre>
   */
  public boolean shouldImportOnStartup() {
    return onStartup && directory != null && !directory.isBlank();
  }

  public Map<String, String> overridesOrEmpty() {
    return identityOverrides == null ? Map.of() : identityOverrides;
  }
}
