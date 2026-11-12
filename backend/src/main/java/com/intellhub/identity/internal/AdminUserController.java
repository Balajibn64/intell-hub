package com.intellhub.identity.internal;

import com.intellhub.identity.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Accounts are created by an administrator; there is no public registration. */
@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
class AdminUserController {

	private final UserService users;

	AdminUserController(UserService users) {
		this.users = users;
	}

	@GetMapping
	List<UserResponse> list() {
		return users.listAll().stream().map(UserResponse::from).toList();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	UserResponse create(@Valid @RequestBody CreateUserRequest body) {
		return UserResponse.from(users.create(body.email(), body.password(), body.role()));
	}

	record CreateUserRequest(
			@NotBlank @Email @Size(max = 254) String email,
			@NotBlank @Size(min = UserService.MIN_PASSWORD_LENGTH, max = 72) String password,
			@NotNull Role role) {
	}

	record UserResponse(UUID id, String email, Role role, boolean enabled, Instant createdAt) {

		static UserResponse from(User user) {
			return new UserResponse(user.getId(), user.getEmail(), user.getRole(), user.isEnabled(),
					user.getCreatedAt());
		}

	}

}
