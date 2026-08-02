package com.tarun.ledgerguard.settlement;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Task 14 settlement-import configuration, under
 * {@code ledgerguard.settlement.import}. Validated at startup, the same
 * pattern {@code outbox.OutboxPublisherProperties} (Task 12) and
 * {@code inbox.LedgerConsumerProperties} (Task 13) already established.
 *
 * <p>{@code enabled} defaults to {@code true}. When {@code false},
 * {@code SettlementImportService} rejects every import request with a
 * single explicit, documented response (503) without affecting any other
 * endpoint (see docs/API_SPEC.md).
 *
 * <p>{@code maxFileSizeBytes} is enforced twice: once as an outer
 * boundary via {@code spring.servlet.multipart.max-file-size}
 * (application.yml), and again inside {@code SettlementImportService}
 * itself while reading the upload's bytes -- the Spring-level limit alone
 * would not be reliably testable/adjustable independently of Task 14's own
 * configurable limit.
 */
@Component
@Validated
@ConfigurationProperties(prefix = "ledgerguard.settlement.import")
public class SettlementImportProperties {

	private boolean enabled = true;

	@Positive
	private long maxFileSizeBytes = 5L * 1024 * 1024;

	@Positive
	private int maxRowCount = 10_000;

	@Positive
	private int maxSourceLength = 64;

	@Positive
	private int maxExternalReferenceLength = 128;

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public long getMaxFileSizeBytes() {
		return maxFileSizeBytes;
	}

	public void setMaxFileSizeBytes(long maxFileSizeBytes) {
		this.maxFileSizeBytes = maxFileSizeBytes;
	}

	public int getMaxRowCount() {
		return maxRowCount;
	}

	public void setMaxRowCount(int maxRowCount) {
		this.maxRowCount = maxRowCount;
	}

	public int getMaxSourceLength() {
		return maxSourceLength;
	}

	public void setMaxSourceLength(int maxSourceLength) {
		this.maxSourceLength = maxSourceLength;
	}

	public int getMaxExternalReferenceLength() {
		return maxExternalReferenceLength;
	}

	public void setMaxExternalReferenceLength(int maxExternalReferenceLength) {
		this.maxExternalReferenceLength = maxExternalReferenceLength;
	}

}
