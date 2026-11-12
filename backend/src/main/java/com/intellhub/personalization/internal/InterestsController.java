package com.intellhub.personalization.internal;

import com.intellhub.identity.CurrentUser;
import com.intellhub.personalization.Interest;
import com.intellhub.personalization.InterestView;
import com.intellhub.personalization.UnknownTagException;
import com.intellhub.personalization.UserInterests;
import com.intellhub.shared.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own interests. The user id comes from the verified token; there is no way to name another user.
 */
@RestController
@RequestMapping("/api/v1/me/interests")
class InterestsController {

	record InterestInput(
			@NotNull @Pattern(regexp = "^[a-z0-9][a-z0-9+#.-]{0,29}$", message = "must be a valid tag name") String tag,
			@DecimalMin("0.1") @DecimalMax("1.0") Double weight) {
	}

	record ReplaceInterests(@NotNull @Size(max = UserInterests.MAX_INTERESTS) List<@NotNull @Valid InterestInput> interests) {
	}

	record InterestItem(String tag, double explicit, double learned) {

		static InterestItem of(InterestView view) {
			return new InterestItem(view.tag(), view.explicit(), view.learned());
		}
	}

	private final UserInterests interests;

	InterestsController(UserInterests interests) {
		this.interests = interests;
	}

	@GetMapping
	List<InterestItem> list(CurrentUser user) {
		return interests.interests(user.id()).stream().map(InterestItem::of).toList();
	}

	@PutMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void replace(CurrentUser user, @Valid @RequestBody ReplaceInterests body) {
		List<Interest> chosen = body.interests().stream()
				.map(i -> new Interest(i.tag(), i.weight() == null ? UserInterests.MAX_WEIGHT : i.weight())).toList();
		try {
			interests.replaceInterests(user.id(), chosen);
		}
		catch (UnknownTagException e) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_TAG", "Unknown tags: " + String.join(", ", e.tags()));
		}
		catch (DataIntegrityViolationException e) {
			if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("user_tag_weights_user_id_fkey")) {
				// The account was deleted while its token was still valid.
				throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required");
			}
			throw e;
		}
	}

}
