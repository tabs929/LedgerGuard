package com.tarun.ledgerguard.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration-backed demo/dev identities and JWT settings for Task 17.
 * No user registration, no database-backed identity store — exactly the
 * small, fixed set of CUSTOMER/OPERATIONS identities an operator lists
 * here, with only a BCrypt hash ever configured, never a plaintext
 * password. See {@link SecurityConfigurationValidator} for the startup
 * validation this binding alone cannot express (username uniqueness,
 * BCrypt hash shape, signing-key strength).
 */
@ConfigurationProperties(prefix = "ledgerguard.security")
public class LedgerGuardSecurityProperties {

	private List<UserConfig> users = new ArrayList<>();

	private Jwt jwt = new Jwt();

	public List<UserConfig> getUsers() {
		return users;
	}

	public void setUsers(List<UserConfig> users) {
		this.users = users;
	}

	public Jwt getJwt() {
		return jwt;
	}

	public void setJwt(Jwt jwt) {
		this.jwt = jwt;
	}

	public static class UserConfig {

		private String username;
		private String passwordHash;
		private Role role;

		public String getUsername() {
			return username;
		}

		public void setUsername(String username) {
			this.username = username;
		}

		public String getPasswordHash() {
			return passwordHash;
		}

		public void setPasswordHash(String passwordHash) {
			this.passwordHash = passwordHash;
		}

		public Role getRole() {
			return role;
		}

		public void setRole(Role role) {
			this.role = role;
		}

	}

	public static class Jwt {

		private String signingKey;
		private String issuer = "ledgerguard";
		private String audience = "ledgerguard-api";
		private long expirationSeconds = 900;
		private long clockSkewSeconds = 30;

		public String getSigningKey() {
			return signingKey;
		}

		public void setSigningKey(String signingKey) {
			this.signingKey = signingKey;
		}

		public String getIssuer() {
			return issuer;
		}

		public void setIssuer(String issuer) {
			this.issuer = issuer;
		}

		public String getAudience() {
			return audience;
		}

		public void setAudience(String audience) {
			this.audience = audience;
		}

		public long getExpirationSeconds() {
			return expirationSeconds;
		}

		public void setExpirationSeconds(long expirationSeconds) {
			this.expirationSeconds = expirationSeconds;
		}

		public long getClockSkewSeconds() {
			return clockSkewSeconds;
		}

		public void setClockSkewSeconds(long clockSkewSeconds) {
			this.clockSkewSeconds = clockSkewSeconds;
		}

	}

}
