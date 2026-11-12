package com.intellhub.sources.internal;

import com.intellhub.sources.SourceType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Administration of content sources. Disabling is the normal way to stop one; deleting is permanent. */
@RestController
@RequestMapping("/api/v1/admin/sources")
@PreAuthorize("hasRole('ADMIN')")
class AdminSourceController {

	private final SourceService sources;

	AdminSourceController(SourceService sources) {
		this.sources = sources;
	}

	@GetMapping
	List<SourceResponse> list() {
		return sources.listAll().stream().map(SourceResponse::from).toList();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	SourceResponse create(@Valid @RequestBody CreateSourceRequest body) {
		return SourceResponse.from(sources.create(body.type(), body.name(), body.url(), body.config()));
	}

	@PatchMapping("/{id}")
	SourceResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateSourceRequest body) {
		return SourceResponse.from(sources.update(id, body.name(), body.enabled(), body.config()));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@PathVariable UUID id) {
		sources.delete(id);
	}

	record CreateSourceRequest(
			@NotNull SourceType type,
			@NotBlank @Size(max = SourceService.MAX_NAME_LENGTH) String name,
			@NotBlank @Size(max = SourceUrlValidator.MAX_LENGTH) String url,
			Map<String, Object> config) {
	}

	/** Only these fields can change. The type and URL are fixed for the life of a source. */
	record UpdateSourceRequest(
			@Size(min = 1, max = SourceService.MAX_NAME_LENGTH) String name,
			Boolean enabled,
			Map<String, Object> config) {
	}

	record SourceResponse(UUID id, SourceType type, String name, String url, Map<String, Object> config,
			boolean enabled, Instant lastFetchedAt, FetchStatus lastStatus, String lastError,
			int consecutiveFailures, Instant createdAt) {

		static SourceResponse from(Source s) {
			return new SourceResponse(s.getId(), s.getType(), s.getName(), s.getUrl(), s.getConfig(), s.isEnabled(),
					s.getLastFetchedAt(), s.getLastStatus(), s.getLastError(), s.getConsecutiveFailures(),
					s.getCreatedAt());
		}

	}

}
