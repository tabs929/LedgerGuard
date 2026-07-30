package com.tarun.ledgerguard.common;

import java.util.List;

/**
 * A minimal, explicit pagination envelope — not Spring Data's default
 * {@code Page} JSON shape. Used wherever an endpoint's approved API
 * contract documents this exact {content, page, size, totalElements,
 * totalPages} shape (see docs/API_SPEC.md's transaction-history contract,
 * Task 6). Generic so it isn't tied to any one item type; still a plain
 * DTO, not a generic reporting/CQRS framework.
 */
public record PagedResponse<T>(
		List<T> content,
		int page,
		int size,
		long totalElements,
		int totalPages
) {
}
