package com.intellhub.retention.internal;

import com.intellhub.retention.RetentionResult;
import com.intellhub.retention.RetentionRunningException;
import com.intellhub.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runs the purge by hand. It is a dry run unless the caller says otherwise, so a mistaken call deletes nothing.
 */
@RestController
@RequestMapping("/api/v1/admin/retention")
@PreAuthorize("hasRole('ADMIN')")
class RetentionAdminController {

	private final RetentionService retention;

	RetentionAdminController(RetentionService retention) {
		this.retention = retention;
	}

	/**
	 * Only the exact words "true" and "false" are accepted. Spring would also read "0", "no" and "off" as false, which
	 * would turn a half-remembered flag into a real deletion; here anything else is a 400 and nothing runs.
	 */
	@PostMapping("/purge")
	RetentionResult purge(@RequestParam(required = false) String dryRun) {
		boolean dry;
		if (dryRun == null || "true".equals(dryRun)) {
			dry = true;
		}
		else if ("false".equals(dryRun)) {
			dry = false;
		}
		else {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "dryRun must be true or false");
		}
		try {
			return retention.run(dry);
		}
		catch (RetentionRunningException e) {
			throw new ApiException(HttpStatus.CONFLICT, "RETENTION_RUNNING", "A purge is already running");
		}
	}

}
