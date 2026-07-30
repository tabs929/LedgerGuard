package com.tarun.ledgerguard.common;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * A minimal, explicit pagination envelope — not Spring Data's default
 * {@code Page} JSON shape. Used wherever an endpoint's approved API
 * contract documents this exact {content, page, size, totalElements,
 * totalPages} shape (see docs/API_SPEC.md's transaction-history contract,
 * Task 6). Generic so it isn't tied to any one item type; still a plain
 * DTO, not a generic reporting/CQRS framework.
 */
@Schema(description = "Custom pagination envelope. Not Spring Data's default Page shape.")
public record PagedResponse<T>(
		List<T> content,
		@Schema(example = "0", description = "Zero-based page number requested") int page,
		@Schema(example = "20", description = "Items per page requested") int size,
		@Schema(example = "1", description = "Total matching items across all pages") long totalElements,
		@Schema(example = "1", description = "Total number of pages") int totalPages
) {
}
