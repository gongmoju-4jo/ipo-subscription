package com.gongmoju.account.application.margin;

import com.gongmoju.account.domain.account.AccountRepository;
import com.gongmoju.account.domain.ledger.LedgerEntryRepository;
import com.gongmoju.account.domain.margin.MarginEventPublisher;
import com.gongmoju.account.domain.margin.MarginRepository;
import com.gongmoju.account.domain.margin.MarginVoidIntentRepository;
import com.gongmoju.common.event.ProcessedEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// MarginReserveServiceTest의 모든 경우를 배치 경로(reserveAll)로 다시 확인한다 (#162 2단계). 테스트 본문은 그대로 상속하고,
// 서비스의 reserve(command)가 reserveAll(List.of(command))를 부르도록 바꾼 빈을 대신 쓴다.
// 정상, 거절, 선행 반환 기록, 중복 수신, 재요청, 불일치 롤백, 동시 요청이 건별 경로와 같은 결과여야 한다. Docker가 필요하다
@DisplayName("증거금 인출 (reserveAll로 한 건씩 호출, MarginReserveServiceTest와 같은 경우)")
@Import(MarginReserveServiceBatchPathTest.BatchPathConfig.class)
class MarginReserveServiceBatchPathTest extends MarginReserveServiceTest {

    @TestConfiguration
    static class BatchPathConfig {

        @Bean
        @Primary
        MarginReserveService batchPathMarginReserveService(AccountRepository accountRepository,
                                                           MarginRepository marginRepository,
                                                           MarginVoidIntentRepository marginVoidIntentRepository,
                                                           LedgerEntryRepository ledgerEntryRepository,
                                                           MarginEventPublisher marginEventPublisher,
                                                           ProcessedEventRepository processedEventRepository) {
            return new BatchPathMarginReserveService(accountRepository, marginRepository, marginVoidIntentRepository,
                    ledgerEntryRepository, marginEventPublisher, processedEventRepository);
        }
    }

    static class BatchPathMarginReserveService extends MarginReserveService {

        BatchPathMarginReserveService(AccountRepository accountRepository, MarginRepository marginRepository,
                                      MarginVoidIntentRepository marginVoidIntentRepository,
                                      LedgerEntryRepository ledgerEntryRepository,
                                      MarginEventPublisher marginEventPublisher,
                                      ProcessedEventRepository processedEventRepository) {
            super(accountRepository, marginRepository, marginVoidIntentRepository, ledgerEntryRepository,
                    marginEventPublisher, processedEventRepository);
        }

        @Override
        @Transactional
        public void reserve(ReserveMarginCommand command) {
            reserveAll(List.of(command));
        }
    }
}
