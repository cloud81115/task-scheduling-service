package com.example.demo;

import com.example.demo.service.TaskPoller;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * The poller bean is created (so wiring problems, such as a bean name clash,
 * fail here) but its first run is an hour away: Spring caches test contexts,
 * so a poller left running would trigger due tasks created by other tests.
 */
@SpringBootTest(properties = "app.scheduler.initial-delay-ms=3600000")
class DemoApplicationTests {

	@Autowired
	private TaskPoller poller;

	@Test
	void contextLoads() {
		assertThat(poller).isNotNull();
	}

}
