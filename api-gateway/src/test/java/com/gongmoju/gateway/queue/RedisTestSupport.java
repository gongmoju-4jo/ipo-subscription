package com.gongmoju.gateway.queue;

import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 대기열 테스트용 Redis 컨테이너 (redis:7, docker-compose와 같은 이미지). 테스트 클래스들이 컨테이너 하나를 같이 쓴다.
 * Spring 컨텍스트 없이 템플릿만 만들어 스크립트를 직접 확인한다.
 */
abstract class RedisTestSupport {

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);

    protected static final ReactiveStringRedisTemplate TEMPLATE;

    static {
        REDIS.start();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        factory.start();
        TEMPLATE = new ReactiveStringRedisTemplate(factory);
    }

    // 게이트웨이 전체를 띄우는 테스트가 이 컨테이너에 붙도록 접속 주소를 넘긴다
    protected static void redisProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    protected static void flushAll() {
        TEMPLATE.execute(connection -> connection.serverCommands().flushAll()).blockLast();
    }
}
