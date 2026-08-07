package com.tarun.ledgerguard.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plain unit tests for the Task 17 startup validation -- no Spring context,
 * no database. {@link SecurityConfigurationValidator#validate()} must fail
 * fast for every kind of misconfiguration the design requires it to catch.
 */
class SecurityConfigurationValidatorTest {

	private static final String VALID_BCRYPT_HASH = "$2b$10$KxMCQkZFWnvzSR8LtDbmQuKBL6bm0.AcvICXc6v4hbAiKAItdLPDS";
	private static final String VALID_SIGNING_KEY = Base64.getEncoder().encodeToString(new byte[32]);

	private LedgerGuardSecurityProperties validProperties() {
		LedgerGuardSecurityProperties properties = new LedgerGuardSecurityProperties();
		LedgerGuardSecurityProperties.UserConfig customer = new LedgerGuardSecurityProperties.UserConfig();
		customer.setUsername("test-user");
		customer.setPasswordHash(VALID_BCRYPT_HASH);
		customer.setRole(Role.CUSTOMER);
		properties.setUsers(List.of(customer));
		properties.getJwt().setSigningKey(VALID_SIGNING_KEY);
		return properties;
	}

	@Test
	void validConfigurationPassesWithoutThrowing() {
		new SecurityConfigurationValidator(validProperties()).validate();
	}

	@Test
	void emptyUsersListIsRejected() {
		LedgerGuardSecurityProperties properties = validProperties();
		properties.setUsers(List.of());
		assertThatThrownBy(() -> new SecurityConfigurationValidator(properties).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("users must not be empty");
	}

	@Test
	void duplicateUsernameIsRejected() {
		LedgerGuardSecurityProperties properties = validProperties();
		LedgerGuardSecurityProperties.UserConfig duplicate = new LedgerGuardSecurityProperties.UserConfig();
		duplicate.setUsername("test-user");
		duplicate.setPasswordHash(VALID_BCRYPT_HASH);
		duplicate.setRole(Role.OPERATIONS);
		properties.setUsers(List.of(properties.getUsers().get(0), duplicate));
		assertThatThrownBy(() -> new SecurityConfigurationValidator(properties).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("duplicate username");
	}

	@Test
	void blankUsernameIsRejected() {
		LedgerGuardSecurityProperties properties = validProperties();
		properties.getUsers().get(0).setUsername("  ");
		assertThatThrownBy(() -> new SecurityConfigurationValidator(properties).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("must not be blank");
	}

	@Test
	void missingRoleIsRejected() {
		LedgerGuardSecurityProperties properties = validProperties();
		properties.getUsers().get(0).setRole(null);
		assertThatThrownBy(() -> new SecurityConfigurationValidator(properties).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("role must be CUSTOMER or OPERATIONS");
	}

	@Test
	void malformedBcryptHashIsRejected() {
		LedgerGuardSecurityProperties properties = validProperties();
		properties.getUsers().get(0).setPasswordHash("not-a-bcrypt-hash");
		assertThatThrownBy(() -> new SecurityConfigurationValidator(properties).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("valid BCrypt hash");
	}

	@Test
	void plaintextPasswordIsRejectedAsAnInvalidHash() {
		LedgerGuardSecurityProperties properties = validProperties();
		properties.getUsers().get(0).setPasswordHash("hunter2");
		assertThatThrownBy(() -> new SecurityConfigurationValidator(properties).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("valid BCrypt hash");
	}

	@Test
	void zeroExpirationIsRejected() {
		LedgerGuardSecurityProperties properties = validProperties();
		properties.getJwt().setExpirationSeconds(0);
		assertThatThrownBy(() -> new SecurityConfigurationValidator(properties).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("expiration-seconds must be positive");
	}

	@Test
	void excessiveExpirationIsRejected() {
		LedgerGuardSecurityProperties properties = validProperties();
		properties.getJwt().setExpirationSeconds(999_999);
		assertThatThrownBy(() -> new SecurityConfigurationValidator(properties).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("expiration-seconds");
	}

	@Test
	void negativeClockSkewIsRejected() {
		LedgerGuardSecurityProperties properties = validProperties();
		properties.getJwt().setClockSkewSeconds(-1);
		assertThatThrownBy(() -> new SecurityConfigurationValidator(properties).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("clock-skew-seconds");
	}

	@Test
	void excessiveClockSkewIsRejected() {
		LedgerGuardSecurityProperties properties = validProperties();
		properties.getJwt().setClockSkewSeconds(9999);
		assertThatThrownBy(() -> new SecurityConfigurationValidator(properties).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("clock-skew-seconds");
	}

	@Test
	void blankSigningKeyIsRejected() {
		LedgerGuardSecurityProperties properties = validProperties();
		properties.getJwt().setSigningKey("");
		assertThatThrownBy(() -> new SecurityConfigurationValidator(properties).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("signing-key must be configured");
	}

	@Test
	void nonBase64SigningKeyIsRejected() {
		LedgerGuardSecurityProperties properties = validProperties();
		properties.getJwt().setSigningKey("not valid base64!!!");
		assertThatThrownBy(() -> new SecurityConfigurationValidator(properties).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("valid Base64");
	}

	@Test
	void signingKeyShorterThan32BytesIsRejected() {
		LedgerGuardSecurityProperties properties = validProperties();
		properties.getJwt().setSigningKey(Base64.getEncoder().encodeToString(new byte[16]));
		assertThatThrownBy(() -> new SecurityConfigurationValidator(properties).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("at least 32 bytes");
	}

	@Test
	void exactly32ByteSigningKeyIsAccepted() {
		LedgerGuardSecurityProperties properties = validProperties();
		properties.getJwt().setSigningKey(Base64.getEncoder().encodeToString(new byte[32]));
		new SecurityConfigurationValidator(properties).validate();
	}

	@Test
	void multipleProblemsAreAllReportedTogether() {
		LedgerGuardSecurityProperties properties = validProperties();
		properties.setUsers(List.of());
		properties.getJwt().setSigningKey("");
		assertThatThrownBy(() -> new SecurityConfigurationValidator(properties).validate())
				.hasMessageContaining("users must not be empty")
				.hasMessageContaining("signing-key must be configured");
	}

}
