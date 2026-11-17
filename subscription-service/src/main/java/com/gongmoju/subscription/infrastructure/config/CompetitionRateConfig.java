package com.gongmoju.subscription.infrastructure.config;

import com.gongmoju.subscription.domain.ipo.CompetitionRateCounter;
import com.gongmoju.subscription.infrastructure.redis.CompetitionRateProperties;
import com.gongmoju.subscription.infrastructure.redis.NoOpCompetitionRateCounter;
import com.gongmoju.subscription.infrastructure.redis.RedisCompetitionRateCounter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;

// 경쟁률 사본(F-15). ipo.competition-rate.redis-enabled=false면 Redis 없이 조회마다 DB로 합산한다 (Redis 없이 띄우는 테스트)
@Configuration
@EnableConfigurationProperties(CompetitionRateProperties.class)
class CompetitionRateConfig {

    @Bean
    CompetitionRateCounter competitionRateCounter(CompetitionRateProperties properties, ObjectProvider<StringRedisTemplate> redis) {
        return properties.redisEnabled()
                ? new RedisCompetitionRateCounter(redis.getObject(), properties, Clock.systemUTC())
                : new NoOpCompetitionRateCounter();
    }
}
