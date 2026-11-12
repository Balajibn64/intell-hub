package com.intellhub.personalization.internal;

import com.intellhub.articles.ArticleCatalog;
import com.intellhub.identity.CurrentUser;
import com.intellhub.personalization.ReadingState;
import com.intellhub.shared.ApiException;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Save, unsave and click. They only ever change the caller's own state: the user id comes from the verified
 * token and there is no way to name another user.
 */
@RestController
@RequestMapping("/api/v1/articles/{id}")
class ReadingStateController {

	private final ReadingState state;

	private final ArticleCatalog articles;

	ReadingStateController(ReadingState state, ArticleCatalog articles) {
		this.state = state;
		this.articles = articles;
	}

	@PutMapping("/save")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void save(CurrentUser user, @PathVariable UUID id) {
		write(id, () -> state.save(user.id(), id));
	}

	@DeleteMapping("/save")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void unsave(CurrentUser user, @PathVariable UUID id) {
		write(id, () -> state.unsave(user.id(), id));
	}

	@PostMapping("/click")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void click(CurrentUser user, @PathVariable UUID id) {
		write(id, () -> {
			state.recordClick(user.id(), id);
			return true;
		});
	}

	/** Checks the article exists, then writes. A row that vanishes in between is reported the same way. */
	private void write(UUID articleId, Supplier<Boolean> action) {
		if (!articles.existsById(articleId)) {
			throw notFound();
		}
		try {
			action.get();
		}
		catch (DataIntegrityViolationException e) {
			String message = e.getMostSpecificCause().getMessage();
			if (message != null && message.contains("user_id")) {
				// The account was deleted while its token was still valid.
				throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required");
			}
			throw notFound();
		}
	}

	private static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "ARTICLE_NOT_FOUND", "Article not found");
	}

}
