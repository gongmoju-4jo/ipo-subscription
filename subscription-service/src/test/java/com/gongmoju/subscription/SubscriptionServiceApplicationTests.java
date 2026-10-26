package com.gongmoju.subscription;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Eureka, PostgreSQL 없이 실행 (H2 메모리 DB)
@SpringBootTest(properties = {
		"eureka.client.enabled=false",
		"spring.datasource.url=jdbc:h2:mem:test;MODE=PostgreSQL",
		"spring.datasource.username=sa",
		"spring.datasource.password="
})
class SubscriptionServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
