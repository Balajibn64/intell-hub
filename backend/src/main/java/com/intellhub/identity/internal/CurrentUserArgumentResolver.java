package com.intellhub.identity.internal;

import com.intellhub.identity.CurrentUser;
import com.intellhub.identity.Role;
import com.intellhub.shared.ApiException;
import java.util.UUID;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Builds {@link CurrentUser} from the verified JWT. Anything unexpected about the token is a 401, not a
 * server error, so a malformed or odd token can never be mistaken for a valid user.
 */
class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return CurrentUser.class.equals(parameter.getParameterType());
	}

	@Override
	public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (!(authentication instanceof JwtAuthenticationToken token) || !token.isAuthenticated()) {
			throw unauthenticated();
		}
		try {
			UUID id = UUID.fromString(token.getToken().getSubject());
			Role role = Role.valueOf(token.getToken().getClaimAsString(JwtService.ROLE_CLAIM));
			return new CurrentUser(id, role);
		}
		catch (IllegalArgumentException | NullPointerException e) {
			throw unauthenticated();
		}
	}

	private static ApiException unauthenticated() {
		return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required");
	}

}
