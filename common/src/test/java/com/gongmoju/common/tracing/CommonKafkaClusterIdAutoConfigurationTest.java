package com.gongmoju.common.tracing;

import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaAdmin;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class CommonKafkaClusterIdAutoConfigurationTest {

    // 닿을 수 없는 주소. clusterId를 조회하면 시간 초과까지 기다리게 된다
    private static final Map<String, Object> UNREACHABLE = Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:1");

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CommonKafkaClusterIdAutoConfiguration.class));

    @Test
    void KafkaAdmin에_고정_clusterId를_넣어_Kafka에_묻지_않는다() {
        contextRunner
                .withBean(KafkaAdmin.class, () -> new KafkaAdmin(UNREACHABLE))
                .run(context -> {
                    KafkaAdmin admin = context.getBean(KafkaAdmin.class);

                    String clusterId = assertTimeoutPreemptively(Duration.ofSeconds(1), admin::clusterId);

                    assertThat(clusterId).isEqualTo(CommonKafkaClusterIdAutoConfiguration.CLUSTER_ID);
                });
    }

    @Test
    void 서비스가_clusterId를_직접_넣었으면_바꾸지_않는다() {
        contextRunner
                .withBean(KafkaAdmin.class, () -> {
                    KafkaAdmin admin = new KafkaAdmin(UNREACHABLE);
                    admin.setClusterId("custom");
                    return admin;
                })
                .run(context -> assertThat(context.getBean(KafkaAdmin.class).getClusterId()).isEqualTo("custom"));
    }

    @Test
    void spring_kafka가_없으면_등록하지_않는다() {
        contextRunner.withClassLoader(new FilteredClassLoader(KafkaAdmin.class))
                .run(context -> assertThat(context).doesNotHaveBean("kafkaAdminClusterIdPostProcessor"));
    }
}
