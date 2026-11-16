package com.gongmoju.common.tracing;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Kafka 발행, 수신 관측이 쓰는 clusterId를 고정값으로 넣어, Kafka에 clusterId를 조회하지 않게 한다.
 * <p>
 * 관측을 켜면 KafkaTemplate과 리스너가 span의 상대 서비스 이름({@code "Apache Kafka: {clusterId}"})을 만들려고
 * {@link KafkaAdmin#clusterId()}로 clusterId를 묻는다. 서비스가 뜬 뒤 아직 한 번도 조회하지 못한 상태에서 Kafka가 응답하지 않으면
 * 이 조회가 Admin 클라이언트 대기 시간만큼 발행 스레드를 붙잡고(재현 시 첫 시도 60초, 이후 Kafka가 돌아올 때까지),
 * 실패하면 캐시하지 않아 보낼 때마다 반복되며 ERROR 로그를 남긴다.
 * clusterId는 span 이름에만 쓰이고 Kafka 클러스터가 하나라서 실제 값 대신 고정값을 넣는다.
 * <p>
 * 서비스가 clusterId를 직접 넣었으면 바꾸지 않는다. spring-kafka가 없는 앱(게이트웨이)에서는 등록하지 않는다
 */
@AutoConfiguration
@ConditionalOnClass(KafkaAdmin.class)
public class CommonKafkaClusterIdAutoConfiguration {

    static final String CLUSTER_ID = "ipo";

    // BeanPostProcessor는 다른 빈보다 먼저 만들어지므로 static으로 둔다 (이 설정 클래스를 일찍 만들지 않게)
    @Bean
    static BeanPostProcessor kafkaAdminClusterIdPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof KafkaAdmin admin && admin.getClusterId() == null) {
                    admin.setClusterId(CLUSTER_ID);
                }
                return bean;
            }
        };
    }
}
