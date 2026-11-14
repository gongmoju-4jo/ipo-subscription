-- 정합성 검증 SQL 22종 (11~15는 배정, 정산 뒤 검사, 16~19는 재전송, 이중청약, 잔액 부족이 섞인 S3, S4용, 20~22는 마감된 공모주 검사). 슈퍼유저로 접속해서 실행한다.
-- 사용: psql -v ipo="'<이번 회차 공모주 ID>'" -f verify.sql    (verify.sh가 대신 실행한다)
-- skip_global 변수를 주면 전체 계좌 기준인 2, 3번을 건너뛴다 (verify.sh가 공모주 여러 개를 한 번에 검사할 때 두 번째 공모주부터)
\pset pager off
\echo '== 1. 상태별 건수 (기대: 시나리오별로 미리 계산한 건수)'
SELECT status, reject_reason, count(*) FROM subscription.subscription WHERE ipo_id = :ipo GROUP BY 1, 2 ORDER BY 1, 2;

\echo '== 2. 청약증거금 = PENDING 증거금 합계 (기대: 0행. 이번 종목이 아니라 전체 계좌 기준)'
\if :{?skip_global}
\echo '(건너뜀: 전체 계좌 기준이라 같은 실행의 첫 공모주 결과(verify-1)와 같다)'
\else
SELECT a.account_id FROM account.account a LEFT JOIN account.margin m ON m.account_id = a.account_id AND m.status = 'PENDING' GROUP BY a.account_id, a.margin_balance HAVING a.margin_balance <> COALESCE(SUM(m.amount), 0);
\endif

-- 원장 대사 배치(#132)는 이 검사를 마지막 원장 대신 원장 금액 합계로 비교한다 (created_at이 같은 원장 2줄의 오탐 방지)
\echo '== 3. 잔고 = 마지막 원장의 거래 후 잔고 (기대: 0행. 위반이 나오면 created_at 동률인지 먼저 확인)'
\if :{?skip_global}
\echo '(건너뜀: 전체 계좌 기준이라 같은 실행의 첫 공모주 결과(verify-1)와 같다)'
\else
WITH last AS (SELECT DISTINCT ON (account_id) account_id, cash_balance_after, margin_balance_after FROM account.ledger_entry ORDER BY account_id, created_at DESC, ledger_entry_id DESC) SELECT a.account_id FROM account.account a JOIN last l USING (account_id) WHERE a.cash_balance <> l.cash_balance_after OR a.margin_balance <> l.margin_balance_after;
\endif

\echo '== 4. 확정 청약 수 = 인출된 증거금 수 (기대: 두 값이 같음)'
SELECT (SELECT count(*) FROM subscription.subscription WHERE ipo_id = :ipo AND status = 'CONFIRMED') AS confirmed, (SELECT count(*) FROM account.margin WHERE ipo_id = :ipo AND status = 'PENDING') AS pending_margin;

\echo '== 5. 청약 금액 = 인출 금액, margin_id 일치 (기대: 0행. TIMEOUT 취소 후 증거금이 반환된 청약은 margin_id가 비어 있어 margin_id 비교에서만 제외)'
SELECT s.subscription_id FROM subscription.subscription s JOIN account.margin m ON m.subscription_id = s.subscription_id WHERE s.ipo_id = :ipo AND (s.margin_amount <> m.amount OR (NOT (s.status = 'CANCELLED' AND m.status = 'VOIDED') AND s.margin_id IS DISTINCT FROM m.margin_id));

\echo '== 6. 거절된 청약에 증거금 없음 (기대: 0행)'
SELECT s.subscription_id FROM subscription.subscription s JOIN account.margin m ON m.subscription_id = s.subscription_id WHERE s.ipo_id = :ipo AND s.status = 'REJECTED';

\echo '== 7. 원장 건수 = 인출 건수 (기대: 4번의 pending_margin과 같음)'
SELECT count(*) FROM account.ledger_entry l JOIN account.margin m ON m.margin_id = l.margin_id WHERE m.ipo_id = :ipo AND l.type = 'MARGIN_PENDING';

\echo '== 8. Outbox 미발행 없음 (기대: 모두 0)'
SELECT 'acc', count(*) FROM account.outbox WHERE published_at IS NULL UNION ALL SELECT 'sub', count(*) FROM subscription.outbox WHERE published_at IS NULL;

\echo '== 9. 접수 → 확정 시간 (초. 기대: 기록)'
SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM confirmed_at - received_at)) AS p50, percentile_cont(0.95) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM confirmed_at - received_at)) AS p95, MAX(EXTRACT(EPOCH FROM confirmed_at - received_at)) AS max_s FROM subscription.subscription WHERE ipo_id = :ipo AND status = 'CONFIRMED';

\echo '== 10. 전체 소진 시간 (기대: 기록)'
SELECT MIN(received_at), MAX(confirmed_at), MAX(confirmed_at) - MIN(received_at) FROM subscription.subscription WHERE ipo_id = :ipo AND status = 'CONFIRMED';

-- 11~15: 배정, 정산 뒤 정합성 (G4). 모두 위반만 행으로 나온다 (기대 0행). 배정 전이면 대상이 없어 0행
--   [계좌 단독] account 스키마만 읽는다 → 계좌 서비스 원장 대사 배치(#132)가 2, 3, 11, 12번을 기반으로 구현됨
--   [서비스 간] subscription, allocation 스키마도 읽는다 → 배치에서는 서비스 계정 권한이 없어 이벤트나 API로 받은 값과 비교해야 한다
--   배정 라운드가 DONE이고 공모주가 ALLOCATED가 아니면 대상이 없어 0행이 나와 통과로 오해할 수 있으므로 건너뛴다고 출력한다
SELECT EXISTS (SELECT 1 FROM allocation.allocation_round WHERE ipo_id = :ipo AND status = 'DONE') AND EXISTS (SELECT 1 FROM subscription.ipo WHERE ipo_id = :ipo AND status = 'ALLOCATED') AS alloc_done \gset
\if :alloc_done
\echo '== 11. [계좌 단독] 모든 증거금에 인출 원장이 정확히 1줄, 금액 = 증거금 (기대: 0행. 중복 인출, 원장 누락)'
SELECT m.subscription_id FROM account.margin m LEFT JOIN account.ledger_entry l ON l.margin_id = m.margin_id AND l.type = 'MARGIN_PENDING' WHERE m.ipo_id = :ipo GROUP BY m.subscription_id, m.amount HAVING count(l.ledger_entry_id) <> 1 OR COALESCE(SUM(l.amount), 0) <> m.amount;

\echo '== 12. [계좌 단독] 상태별 원장: PENDING은 정산, 반환 원장 없음 / POSTED는 차감 = posted_amount, 환불 = 증거금 - posted_amount / VOIDED는 반환 = 증거금, 정산 원장 없음 (기대: 0행)'
WITH l AS (SELECT margin_id, COALESCE(SUM(amount) FILTER (WHERE type = 'MARGIN_POST'), 0) AS post, COALESCE(SUM(amount) FILTER (WHERE type = 'REFUND'), 0) AS refund, COALESCE(SUM(amount) FILTER (WHERE type = 'MARGIN_VOID'), 0) AS void FROM account.ledger_entry WHERE margin_id IS NOT NULL GROUP BY margin_id) SELECT m.subscription_id, m.status, m.amount, m.posted_amount, COALESCE(l.post, 0) AS post, COALESCE(l.refund, 0) AS refund, COALESCE(l.void, 0) AS void FROM account.margin m LEFT JOIN l USING (margin_id) WHERE m.ipo_id = :ipo AND NOT CASE m.status WHEN 'PENDING' THEN COALESCE(l.post, 0) + COALESCE(l.refund, 0) + COALESCE(l.void, 0) = 0 WHEN 'POSTED' THEN COALESCE(l.post, 0) = m.posted_amount AND COALESCE(l.refund, 0) = m.amount - m.posted_amount AND COALESCE(l.void, 0) = 0 WHEN 'VOIDED' THEN COALESCE(l.void, 0) = m.amount AND COALESCE(l.post, 0) + COALESCE(l.refund, 0) = 0 ELSE false END;

\echo '== 13. [서비스 간] 배정 결과와 정산 일치: 0주는 VOIDED, 배정이 있으면 POSTED이고 posted_amount = 배정 대금 (기대: 0행. 정산 대기 중이면 PENDING으로 나온다)'
SELECT a.subscription_id, a.allocated_amount, m.status, m.posted_amount FROM allocation.allocation a JOIN allocation.allocation_round r ON r.allocation_round_id = a.allocation_round_id AND r.status = 'DONE' LEFT JOIN account.margin m ON m.subscription_id = a.subscription_id WHERE a.ipo_id = :ipo AND (m.margin_id IS NULL OR CASE WHEN a.allocated_amount = 0 THEN m.status <> 'VOIDED' ELSE m.status <> 'POSTED' OR m.posted_amount <> a.allocated_amount END);

\echo '== 14. [서비스 간] 배정 수량 합 = 일반청약 물량과 신청 수량 합 중 작은 값 (기대: 0행. 신청 수량은 배정 대상 청약 전체로 따로 합산해 배정 행이 빠진 청약도 잡는다)'
WITH req AS (SELECT COALESCE(SUM(requested_quantity), 0) AS requested FROM subscription.subscription WHERE ipo_id = :ipo AND status IN ('CONFIRMED', 'ALLOCATED', 'REFUNDED')), alloc AS (SELECT allocation_round_id, SUM(allocated_quantity) AS allocated FROM allocation.allocation WHERE ipo_id = :ipo GROUP BY allocation_round_id) SELECT r.allocation_round_id, r.retail_quantity, req.requested, COALESCE(a.allocated, 0) AS allocated FROM allocation.allocation_round r CROSS JOIN req LEFT JOIN alloc a ON a.allocation_round_id = r.allocation_round_id WHERE r.ipo_id = :ipo AND r.status = 'DONE' AND COALESCE(a.allocated, 0) <> LEAST(r.retail_quantity, req.requested);

\echo '== 15. [서비스 간] 배정이 끝난 공모주에 남은 PENDING 증거금 (기대: 0행. 정산 메시지 처리 중이면 잠깐 나올 수 있다)'
SELECT m.subscription_id FROM account.margin m JOIN subscription.ipo i ON i.ipo_id = m.ipo_id WHERE m.ipo_id = :ipo AND i.status = 'ALLOCATED' AND m.status = 'PENDING';
\else
\echo '== 11~15. (건너뜀: 이 공모주의 배정 라운드가 DONE이 아니거나 공모주가 ALLOCATED가 아니다. 배정, 정산이 끝난 뒤 다시 실행한다)'
\endif

-- 16~19: 재전송, 이중청약, 잔액 부족 (S3, S4). 모두 위반만 행으로 나온다 (기대 0행). 어느 시나리오에서 돌려도 맞아야 하는 조건이다
\echo '== 16. 같은 requestId의 청약이 2행 이상 (기대: 0행. 재전송이 새 청약으로 저장됨)'
SELECT request_id, count(*) FROM subscription.subscription WHERE ipo_id = :ipo GROUP BY request_id HAVING count(*) > 1;

\echo '== 17. 같은 청약의 증거금이 2개 이상 (기대: 0행. 재전송이나 메시지 중복으로 두 번 인출됨)'
SELECT subscription_id, count(*) FROM account.margin WHERE ipo_id = :ipo GROUP BY subscription_id HAVING count(*) > 1;

\echo '== 18. 회원당 유효 청약(REJECTED, CANCELLED 제외)이 2건 이상 (기대: 0행. 다른 계좌로 한 이중청약이 둘 다 유효함)'
SELECT user_id, count(*) FROM subscription.subscription WHERE ipo_id = :ipo AND status NOT IN ('REJECTED', 'CANCELLED') GROUP BY user_id HAVING count(*) > 1;

-- 19: 잔액 부족 거절이 없는 공모주에서는 대상이 없어 0행이 통과로 오해될 수 있으므로 건너뛴다고 출력한다.
--     입금액 그대로인지(돈이 전혀 안 움직였는지)는 행동 CSV를 아는 judge.py가 확인한다
SELECT EXISTS (SELECT 1 FROM subscription.subscription WHERE ipo_id = :ipo AND status = 'REJECTED' AND reject_reason = 'INSUFFICIENT_BALANCE') AS has_short \gset
\if :has_short
\echo '== 19. 잔액 부족으로 거절됐는데 계좌 예수금이 청약 금액 이상 (기대: 0행. 돈이 있는데 거절됐거나, 거절 뒤 돈이 들어옴)'
SELECT s.subscription_id, s.account_id, a.cash_balance, s.margin_amount FROM subscription.subscription s JOIN account.account a ON a.account_id = s.account_id WHERE s.ipo_id = :ipo AND s.status = 'REJECTED' AND s.reject_reason = 'INSUFFICIENT_BALANCE' AND a.cash_balance >= s.margin_amount;
\else
\echo '== 19. (건너뜀: 이 공모주에 잔액 부족 거절이 없다. S4가 아니면 정상)'
\endif

-- 20~22: 마감된 공모주 검사 (S2 마감 경계). 모두 위반만 행으로 나온다 (기대 0행). 어느 시나리오에서 돌려도 맞아야 하는 조건이다
--   마감 처리 시각은 ipo.closed Outbox를 기록한 시각이다 (공모주 CLOSED 전환과 같은 트랜잭션)
--   아직 마감되지 않은 공모주는 대상이 없어 0행이 통과로 오해될 수 있으므로 20, 22번은 건너뛴다고 출력한다
SELECT EXISTS (SELECT 1 FROM subscription.outbox WHERE topic = 'ipo.closed' AND aggregate_id = :ipo) AS is_closed \gset
\if :is_closed
\echo '== 20. 마감 처리 뒤에 생긴 청약 (기대: 0행. 202를 받고도 마감 때 집계에서 빠진 청약)'
SELECT s.subscription_id, s.status, s.received_at, s.created_at, c.closed_at FROM subscription.subscription s CROSS JOIN (SELECT min(created_at) AS closed_at FROM subscription.outbox WHERE topic = 'ipo.closed' AND aggregate_id = :ipo) c WHERE s.ipo_id = :ipo AND s.created_at > c.closed_at;
\else
\echo '== 20. (건너뜀: 아직 마감되지 않은 공모주)'
\endif

\echo '== 21. 마감 시각 이후 도착(received_at >= 마감 시각)인데 접수된 청약 (기대: 0행)'
SELECT s.subscription_id, s.status, s.received_at, i.subscription_end_at FROM subscription.subscription s JOIN subscription.ipo i ON i.ipo_id = s.ipo_id WHERE s.ipo_id = :ipo AND s.received_at >= i.subscription_end_at;

\if :is_closed
\echo '== 22. 마감 때 확정 수(ipo.closed confirmedCount)와 지금 유효 청약 수(REJECTED, CANCELLED 제외)가 다름 (기대: 0행. 마감 뒤에 청약이 늘거나 줄었음)'
SELECT c.confirmed_at_close, v.valid_now FROM (SELECT (payload::json->>'confirmedCount')::bigint AS confirmed_at_close FROM subscription.outbox WHERE topic = 'ipo.closed' AND aggregate_id = :ipo ORDER BY created_at LIMIT 1) c CROSS JOIN (SELECT count(*) AS valid_now FROM subscription.subscription WHERE ipo_id = :ipo AND status NOT IN ('REJECTED', 'CANCELLED')) v WHERE c.confirmed_at_close <> v.valid_now;
\else
\echo '== 22. (건너뜀: 아직 마감되지 않은 공모주)'
\endif
