package com.tarun.ledgerguard.security;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Fails application startup, rather than failing quietly or on first
 * request, when {@code ledgerguard.security} is misconfigured (Task 17's
 * explicit requirement). Checks the things plain {@code @ConfigurationProperties}
 * binding cannot express by itself: cross-entry uniqueness, BCrypt hash
 * shape, and signing-key strength.
 */
@Component
public class SecurityConfigurationValidator {

	// Matches a well-formed BCrypt hash, e.g. $2a$10$... (60 chars total).
	private static final Pattern BCRYPT_PATTERN = Pattern.compile("^\\$2[aby]?\\$\\d{2}\\$[./A-Za-z0-9]{53}$");
	private static final int MIN_SIGNING_KEY_BYTES = 32;
	private static final long MAX_EXPIRATION_SECONDS = 86_400;
	private static final long MAX_CLOCK_SKEW_SECONDS = 300;

	private final LedgerGuardSecurityProperties properties;

	public SecurityConfigurationValidator(LedgerGuardSecurityProperties properties) {
		this.properties = properties;
	}

	@PostConstruct
	public void validate() {
		List<String> problems = new ArrayList<>();

		List<LedgerGuardSecurityProperties.UserConfig> users = properties.getUsers();
		if (users.isEmpty()) {
			problems.add("ledgerguard.security.users must not be empty");
		}
		Set<String> seenUsernames = new HashSet<>();
		for (LedgerGuardSecurityProperties.UserConfig user : users) {
			String username = user.getUsername();
			if (username == null || username.isBlank()) {
				problems.add("username must not be blank");
			} else if (!seenUsernames.add(username)) {
				problems.add("duplicate username: " + username);
			}
			if (user.getRole() == null) {
				problems.add("role must be CUSTOMER or OPERATIONS for user '" + username + "'");
			}
			String hash = user.getPasswordHash();
			if (hash == null || !BCRYPT_PATTERN.matcher(hash).matches()) {
				problems.add("passwordHash must be a valid BCrypt hash for user '" + username + "'");
			}
		}

		LedgerGuardSecurityProperties.Jwt jwt = properties.getJwt();
		if (jwt.getExpirationSeconds() <= 0 || jwt.getExpirationSeconds() > MAX_EXPIRATION_SECONDS) {
			problems.add("jwt.expiration-seconds must be positive and no greater than " + MAX_EXPIRATION_SECONDS);
		}
		if (jwt.getClockSkewSeconds() < 0 || jwt.getClockSkewSeconds() > MAX_CLOCK_SKEW_SECONDS) {
			problems.add("jwt.clock-skew-seconds must be non-negative and no greater than " + MAX_CLOCK_SKEW_SECONDS);
		}
		String signingKey = jwt.getSigningKey();
		if (signingKey == null || signingKey.isBlank()) {
			problems.add("jwt.signing-key must be configured");
		} else {
			try {
				byte[] decoded = Base64.getDecoder().decode(signingKey);
				if (decoded.length < MIN_SIGNING_KEY_BYTES) {
					problems.add("jwt.signing-key must Base64-decode to at least " + MIN_SIGNING_KEY_BYTES + " bytes");
				}
			} catch (IllegalArgumentException e) {
				problems.add("jwt.signing-key must be valid Base64");
			}
		}

		if (!problems.isEmpty()) {
			throw new IllegalStateException("Invalid ledgerguard.security configuration: " + String.join("; ", problems));
		}
	}

}
