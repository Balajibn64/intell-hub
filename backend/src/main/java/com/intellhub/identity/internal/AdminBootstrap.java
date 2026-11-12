package com.intellhub.identity.internal;

import com.intellhub.identity.Role;
import com.intellhub.shared.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Creates the first administrator from ADMIN_EMAIL and ADMIN_PASSWORD, but only while there are
 * no users at all. After that the variables are ignored, so changing them never resets or adds an
 * account. The password is never logged.
 */
@Component
class AdminBootstrap implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

	private final UserRepository users;

	private final UserService userService;

	private final IdentityProperties properties;

	AdminBootstrap(UserRepository users, UserService userService, IdentityProperties properties) {
		this.users = users;
		this.userService = userService;
		this.properties = properties;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (users.count() > 0) {
			return;
		}
		IdentityProperties.Admin admin = properties.admin();
		if (!admin.configured()) {
			log.warn("No users exist and ADMIN_EMAIL / ADMIN_PASSWORD are not set; nobody can log in yet");
			return;
		}
		try {
			User created = userService.create(admin.email(), admin.password(), Role.ADMIN);
			log.info("Created initial administrator {}", created.getEmail());
		}
		catch (ApiException e) {
			throw new IllegalStateException("ADMIN_EMAIL / ADMIN_PASSWORD are not usable: " + e.getMessage(), e);
		}
	}

}
