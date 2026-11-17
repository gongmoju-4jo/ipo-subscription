package com.gongmoju.subscription.application.ipo;

import com.gongmoju.subscription.domain.ipo.CompetitionRateCounter;
import com.gongmoju.subscription.domain.ipo.Ipo;
import com.gongmoju.subscription.domain.subscription.SubscriptionRepository;
import com.gongmoju.subscription.domain.subscription.SubscriptionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("경쟁률 계산 (사본과 DB)")
class CompetitionRateReaderTest {

    private final CompetitionRateCounter counter = mock(CompetitionRateCounter.class);
    private final SubscriptionRepository repository = mock(SubscriptionRepository.class);
    private final CompetitionRateReader reader = new CompetitionRateReader(counter, repository);

    @Test
    @DisplayName("사본에 있는 공모주는 DB를 합산하지 않고, 없는 공모주만 채우는 중 표시 → DB 합산 → 채우기 순서로 넣는다")
    void rates_usesCounter_andLoadsOnlyMissingFromDatabase() {
        // given
        Ipo cached = ipo(100);
        Ipo missing = ipo(100);
        when(counter.find(anyCollection())).thenReturn(Map.of(cached.getId(), 250L));
        when(counter.startLoading(anyCollection())).thenReturn(Set.of(missing.getId()));
        when(repository.sumRequestedQuantityByIpoIds(anyCollection(), eq(SubscriptionStatus.ALLOCATION_TARGETS)))
                .thenReturn(Map.of(missing.getId(), 50L));

        // when
        Map<UUID, BigDecimal> rates = reader.rates(List.of(cached, missing));

        // then
        assertThat(rates.get(cached.getId())).isEqualByComparingTo("2.50");
        assertThat(rates.get(missing.getId())).isEqualByComparingTo("0.50");
        InOrder order = inOrder(counter, repository);
        order.verify(counter).startLoading(argThat((Collection<UUID> ids) -> ids.size() == 1 && ids.contains(missing.getId())));
        order.verify(repository).sumRequestedQuantityByIpoIds(argThat((Collection<UUID> ids) -> ids.size() == 1 && ids.contains(missing.getId())),
                eq(SubscriptionStatus.ALLOCATION_TARGETS));
        order.verify(counter).finishLoading(Map.of(missing.getId(), 50L));
    }

    @Test
    @DisplayName("DB에 배정 대상 청약이 없는 공모주는 0으로 사본에 넣고 경쟁률은 0.00이다")
    void rates_storesZero_whenNoTargetSubscriptions() {
        Ipo ipo = ipo(100);
        when(counter.find(anyCollection())).thenReturn(Map.of());
        when(counter.startLoading(anyCollection())).thenReturn(Set.of(ipo.getId()));
        when(repository.sumRequestedQuantityByIpoIds(anyCollection(), any())).thenReturn(Map.of());

        assertThat(reader.rates(List.of(ipo)).get(ipo.getId())).isEqualByComparingTo("0.00");
        verify(counter).finishLoading(Map.of(ipo.getId(), 0L));
    }

    @Test
    @DisplayName("다른 인스턴스가 채우는 중인 공모주는 DB로 계산해 응답하되 사본에 넣지 않는다")
    void rates_doesNotStore_whenAnotherInstanceIsLoading() {
        Ipo ipo = ipo(100);
        when(counter.find(anyCollection())).thenReturn(Map.of());
        when(counter.startLoading(anyCollection())).thenReturn(Set.of());
        when(repository.sumRequestedQuantityByIpoIds(anyCollection(), any())).thenReturn(Map.of(ipo.getId(), 70L));

        assertThat(reader.rates(List.of(ipo)).get(ipo.getId())).isEqualByComparingTo("0.70");
        verify(counter).finishLoading(Map.of());
    }

    @Test
    @DisplayName("사본이 모두 있으면 DB를 부르지 않는다")
    void rates_doesNotQueryDatabase_whenAllCached() {
        Ipo ipo = ipo(100);
        when(counter.find(anyCollection())).thenReturn(Map.of(ipo.getId(), 100L));

        assertThat(reader.rates(List.of(ipo)).get(ipo.getId())).isEqualByComparingTo("1.00");
        verify(repository, never()).sumRequestedQuantityByIpoIds(anyCollection(), any());
    }

    @Test
    @DisplayName("사본이 없는 같은 공모주를 여러 요청이 동시에 읽으면 DB 합산은 한 번만 하고 모두 같은 값을 받는다")
    void rates_loadsOnce_whenConcurrentRequestsMissSameIpo() throws Exception {
        // given: DB 합산이 끝나지 않은 동안 다른 요청들이 들어옴
        Ipo ipo = ipo(100);
        when(counter.find(anyCollection())).thenReturn(Map.of());
        when(counter.startLoading(anyCollection())).thenReturn(Set.of(ipo.getId()));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(repository.sumRequestedQuantityByIpoIds(anyCollection(), any())).thenAnswer(inv -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Map.of(ipo.getId(), 300L);
        });
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            Future<Map<UUID, BigDecimal>> first = pool.submit(() -> reader.rates(List.of(ipo)));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            List<Future<Map<UUID, BigDecimal>>> others = List.of(
                    pool.submit(() -> reader.rates(List.of(ipo))),
                    pool.submit(() -> reader.rates(List.of(ipo))),
                    pool.submit(() -> reader.rates(List.of(ipo))));
            awaitWaiters(ipo.getId(), 3);  // 나머지 요청이 계산 중인 결과를 기다리는 상태가 된 뒤에 풀어 줌

            // when
            release.countDown();

            // then
            assertThat(first.get(5, TimeUnit.SECONDS).get(ipo.getId())).isEqualByComparingTo("3.00");
            for (Future<Map<UUID, BigDecimal>> f : others) {
                assertThat(f.get(5, TimeUnit.SECONDS).get(ipo.getId())).isEqualByComparingTo("3.00");
            }
            verify(repository, times(1)).sumRequestedQuantityByIpoIds(anyCollection(), any());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("DB 합산이 Error로 끝나도 기다리던 요청은 멈추지 않고, 한 번 다시 계산해 값을 받는다")
    void rates_followersDoNotHang_whenLoaderFailsWithError() throws Exception {
        // given: 첫 합산은 Error, 다시 계산하면 성공
        Ipo ipo = ipo(100);
        when(counter.find(anyCollection())).thenReturn(Map.of());
        when(counter.startLoading(anyCollection())).thenReturn(Set.of(ipo.getId()));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(repository.sumRequestedQuantityByIpoIds(anyCollection(), any()))
                .thenAnswer(inv -> {
                    started.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    throw new StackOverflowError("loader failed");
                })
                .thenReturn(Map.of(ipo.getId(), 400L));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Map<UUID, BigDecimal>> leader = pool.submit(() -> reader.rates(List.of(ipo)));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Map<UUID, BigDecimal>> follower = pool.submit(() -> reader.rates(List.of(ipo)));
            awaitWaiters(ipo.getId(), 1);  // 기다리는 상태가 된 뒤에 실패시킴

            // when
            release.countDown();

            // then
            assertThat(follower.get(5, TimeUnit.SECONDS).get(ipo.getId())).isEqualByComparingTo("4.00");
            assertThat(catchThrowable(() -> leader.get(5, TimeUnit.SECONDS))).hasCauseInstanceOf(StackOverflowError.class);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("다른 요청의 합산을 기다리다 시간이 넘으면 같은 계산에 다시 합류하지 않고 직접 합산한다 (사본은 넣지 않음)")
    void rates_sumsDirectly_whenWaitTimesOut() throws Exception {
        // given: 앞선 합산이 오래 걸림, 기다리는 시간은 짧게
        reader.setWaitMillisForTest(300);
        Ipo ipo = ipo(100);
        when(counter.find(anyCollection())).thenReturn(Map.of());
        when(counter.startLoading(anyCollection())).thenReturn(Set.of(ipo.getId()));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(repository.sumRequestedQuantityByIpoIds(anyCollection(), any()))
                .thenAnswer(inv -> {
                    started.countDown();
                    release.await(10, TimeUnit.SECONDS);
                    return Map.of(ipo.getId(), 300L);
                })
                .thenReturn(Map.of(ipo.getId(), 500L));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Map<UUID, BigDecimal>> leader = pool.submit(() -> reader.rates(List.of(ipo)));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            // when: 뒤 요청은 기다리다 시간을 넘김
            Map<UUID, BigDecimal> follower = pool.submit(() -> reader.rates(List.of(ipo))).get(5, TimeUnit.SECONDS);

            // then: 직접 합산한 값을 받고, 앞선 합산은 그대로 끝남. 사본은 앞선 합산만 넣음
            assertThat(follower.get(ipo.getId())).isEqualByComparingTo("5.00");
            release.countDown();
            assertThat(leader.get(5, TimeUnit.SECONDS).get(ipo.getId())).isEqualByComparingTo("3.00");
            verify(repository, times(2)).sumRequestedQuantityByIpoIds(anyCollection(), any());
            verify(counter, times(1)).startLoading(anyCollection());
            verify(counter, times(1)).finishLoading(Map.of(ipo.getId(), 300L));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("사본이 없는 공모주 여러 개를 기다릴 때 대기 시간은 공모주마다가 아니라 요청 전체에 한 번이고, 남은 공모주를 한 번에 직접 합산한다")
    void rates_waitsOnceForAllIpos_thenSumsRemainingTogether() throws Exception {
        // given: 앞선 요청이 공모주 3개를 오래 합산함, 기다리는 시간 400ms
        reader.setWaitMillisForTest(400);
        List<Ipo> ipos = List.of(ipo(100), ipo(100), ipo(100));
        when(counter.find(anyCollection())).thenReturn(Map.of());
        when(counter.startLoading(anyCollection())).thenAnswer(inv -> Set.copyOf(inv.<Collection<UUID>>getArgument(0)));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(repository.sumRequestedQuantityByIpoIds(anyCollection(), any()))
                .thenAnswer(inv -> {
                    started.countDown();
                    release.await(10, TimeUnit.SECONDS);
                    return Map.of();
                })
                .thenReturn(Map.of(ipos.get(0).getId(), 100L, ipos.get(1).getId(), 200L, ipos.get(2).getId(), 300L));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> reader.rates(ipos));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            // when
            long t0 = System.nanoTime();
            Map<UUID, BigDecimal> follower = pool.submit(() -> reader.rates(ipos)).get(5, TimeUnit.SECONDS);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

            // then: 공모주마다 400ms씩(1,200ms)이 아니라 한 번(400ms 남짓)만 기다리고, 남은 3개를 한 번에 직접 합산
            assertThat(elapsedMillis).isLessThan(1_000);
            assertThat(follower.get(ipos.get(2).getId())).isEqualByComparingTo("3.00");
            // 앞선 요청의 합산 1번 + 이 요청의 직접 합산 1번, 둘 다 공모주 3개를 한 번에
            verify(repository, times(2)).sumRequestedQuantityByIpoIds(argThat((Collection<UUID> ids) -> ids.size() == 3), any());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    // 다른 요청들이 공모주의 계산 중 결과를 기다리는 상태가 될 때까지 기다린다 (고정 sleep 대신)
    @SuppressWarnings("unchecked")
    private void awaitWaiters(UUID ipoId, int waiters) throws InterruptedException {
        Map<UUID, java.util.concurrent.CompletableFuture<Long>> loading =
                (Map<UUID, java.util.concurrent.CompletableFuture<Long>>) ReflectionTestUtils.getField(reader, "loading");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            java.util.concurrent.CompletableFuture<Long> future = loading.get(ipoId);
            if (future != null && future.getNumberOfDependents() >= waiters) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("기다리는 요청이 " + waiters + "개가 되지 않음");
    }

    private static Ipo ipo(long retailQuantity) {
        try {
            Constructor<Ipo> constructor = Ipo.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            Ipo ipo = constructor.newInstance();
            ReflectionTestUtils.setField(ipo, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(ipo, "retailQuantity", retailQuantity);
            return ipo;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
