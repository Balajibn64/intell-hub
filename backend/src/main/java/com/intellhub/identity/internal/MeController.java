package com.intellhub.identity.internal;

import com.intellhub.identity.Role;
import com.intellhub.shared.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
class MeController {

	private final UserRepository users;

	MeController(UserRepository users) {
		this.users = users;
	}

	@GetMapping
	MeResponse me(@AuthenticationPrincipal Jwt jwt) {
		// A valid token can outlive its user (up to the access token lifetime), so re-check the account.
		User user = users.findById(UUID.fromString(jwt.getSubject()))
				.filter(User::isEnabled)
				.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
						"Authentication required"));
		return new MeResponse(user.getId(), user.getEmail(), user.getRole());
	}

	record MeResponse(UUID id, String email, Role role) {
	}

}
