package com.gongmoju.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Eureka 서버 없이 실행
@SpringBootTest(properties = "eureka.client.enabled=false")
class ApiGatewayApplicationTests {

	@Test
	void contextLoads() {
	}

}
