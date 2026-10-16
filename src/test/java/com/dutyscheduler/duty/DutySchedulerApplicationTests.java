package com.dutyscheduler.duty;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Its own container rather than application.yml's localhost:5432. Without one
 * the test silently depends on whatever happens to be listening on that port —
 * a stopped compose db fails it, and a different local Postgres fails it with
 * a misleading "role does not exist".
 */
@SpringBootTest
@Testcontainers
class DutySchedulerApplicationTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@Test
	void contextLoads() {
	}

}
