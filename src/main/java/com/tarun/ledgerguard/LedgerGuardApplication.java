package com.tarun.ledgerguard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling only activates the scheduling infrastructure itself;
// the one @Scheduled method (outbox.OutboxPublisherScheduler, Task 12)
// still only registers when ledgerguard.outbox.publisher.enabled=true, so
// enabling this unconditionally is harmless when it's off.
@EnableScheduling
@SpringBootApplication
public class LedgerGuardApplication {

	public static void main(String[] args) {
		SpringApplication.run(LedgerGuardApplication.class, args);
	}

}
