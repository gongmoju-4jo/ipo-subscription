package com.gongmoju.allocation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Eureka, PostgreSQL 없이 실행 (H2 메모리 DB)
// schema.sql은 PostgreSQL 문법(부분 인덱스)이라 H2에서는 실행하지 않는다
@SpringBootTest(properties = {
		"eureka.client.enabled=false",
		"spring.datasource.url=jdbc:h2:mem:test;MODE=PostgreSQL",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.sql.init.mode=never"
})
class AllocationServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
