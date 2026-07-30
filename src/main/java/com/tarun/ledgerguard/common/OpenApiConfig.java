package com.tarun.ledgerguard.common;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata for the generated document (Task 8). Only the fields
 * the approved documentation actually specifies content for — no license,
 * contact, or server URL, since none of those are documented anywhere and
 * inventing placeholder values for them is explicitly out of scope.
 * Omitting {@code servers} lets springdoc default to the current request's
 * own host, which is accurate rather than a hardcoded, possibly-wrong URL.
 * No security scheme is declared here — Phase 1 has no authentication.
 */
@Configuration
public class OpenApiConfig {

	@Bean
	public OpenAPI ledgerGuardOpenApi() {
		return new OpenAPI().info(new Info()
				.title("LedgerGuard API")
				.description("LedgerGuard is an atomic double-entry ledger API supporting customer-wallet "
						+ "creation, USD deposits, USD customer-to-customer transfers, materialized balance "
						+ "reads, and immutable transaction-history reads.")
				.version("0.0.1-SNAPSHOT"));
	}

}
