package com.leadsquared.hr.knowledge.security;

import com.leadsquared.hr.knowledge.iam.IamService;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * Turns an Entra sign-in into an authenticated user with this app's role on it.
 *
 * <p>The role has to be decided here rather than at each call site: Spring's URL
 * rules (`hasRole("HR_ADMIN")`) are evaluated from the authorities on the
 * principal, so if the mapping were done later the admin-only endpoints would be
 * open to every signed-in employee.
 */
public class EntraOidcUserService extends OidcUserService {

  private final AuthProperties props;
  private final IamService iam;

  public EntraOidcUserService(AuthProperties props, IamService iam) {
    this.props = props;
    this.iam = iam;
  }

  @Override
  public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
    OidcUser user = super.loadUser(request);

    // Reuse the one definition of "who is an admin", rather than a second copy of
    // that rule living in this class.
    //
    // The authority stamped on below is now a convenience rather than the gate:
    // AdminConsoleAccess re-decides on every request, because a role revoked in the
    // console has to take effect before the next sign-in.
    SignedInUser resolved = new CurrentUser(props, iam).from(user);

    Set<GrantedAuthority> authorities = new LinkedHashSet<>(user.getAuthorities());
    authorities.add(new SimpleGrantedAuthority("ROLE_" + resolved.role().toUpperCase()));

    // preferred_username, matching `user-name-attribute` in application-local.yml:
    // for a work account it is the UPN, which is what the rest of the app keys on.
    String nameAttribute =
        user.getClaims().containsKey("preferred_username") ? "preferred_username" : "sub";

    return new DefaultOidcUser(authorities, user.getIdToken(), user.getUserInfo(), nameAttribute);
  }
}
