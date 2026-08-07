package com.tarun.ledgerguard.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata for the generated document (Task 8; bearer scheme added
 * in Task 17). Only the fields the approved documentation actually
 * specifies content for — no license, contact, or server URL, since none
 * of those are documented anywhere and inventing placeholder values for
 * them is explicitly out of scope. Omitting {@code servers} lets springdoc
 * default to the current request's own host, which is accurate rather
 * than a hardcoded, possibly-wrong URL.
 *
 * <p>Declaring the {@code bearerAuth} scheme here only documents it in the
 * generated spec/Swagger UI "Authorize" button — it has no effect on
 * actual enforcement, which is entirely {@code security.SecurityConfig}'s
 * responsibility.
 */
@Configuration
public class OpenApiConfig {

	private static final String BEARER_SECURITY_SCHEME = "bearerAuth";

	@Bean
	public OpenAPI ledgerGuardOpenApi() {
		return new OpenAPI()
				.info(new Info()
						.title("LedgerGuard API")
						.description("LedgerGuard is an atomic double-entry ledger API supporting customer-wallet "
								+ "creation, USD deposits, USD customer-to-customer transfers, materialized balance "
								+ "reads, and immutable transaction-history reads. Every endpoint under /api/v1 "
								+ "except POST /api/v1/auth/token requires a bearer JWT obtained from that "
								+ "endpoint.")
						.version("0.0.1-SNAPSHOT"))
				.components(new Components()
						.addSecuritySchemes(BEARER_SECURITY_SCHEME, new SecurityScheme()
								.type(SecurityScheme.Type.HTTP)
								.scheme("bearer")
								.bearerFormat("JWT")))
				.addSecurityItem(new SecurityRequirement().addList(BEARER_SECURITY_SCHEME));
	}

}
