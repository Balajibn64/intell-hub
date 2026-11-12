package com.intellhub.identity;

import java.util.UUID;

/**
 * The authenticated caller, as taken from the verified access token. Declare it as a controller method
 * parameter and it is filled in for you; it can never come from the request itself, so a client cannot
 * claim to be someone else by sending an id.
 *
 * <p>The account is not re-checked on every request, so a user who has just been disabled can still call
 * the API until their access token expires (at most 15 minutes).
 *
 * @param id the user id
 * @param role the user's role
 */
public record CurrentUser(UUID id, Role role) {
}
