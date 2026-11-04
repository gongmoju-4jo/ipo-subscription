-- 정합성 검증 SQL 15종 (11~15는 배정, 정산 뒤 검사). 슈퍼유저로 접속해서 실행한다.
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

\echo '== 3. 잔고 = 마지막 원장의 거래 후 잔고 (기대: 0행. 위반이 나오면 created_at 동률인지 먼저 확인)'
\if :{?skip_global}
\echo '(건너뜀: 전체 계좌 기준이라 같은 실행의 첫 공모주 결과(verify-1)와 같다)'
\else
WITH last AS (SELECT DISTINCT ON (account_id) account_id, cash_balance_after, margin_balance_after FROM account.ledger_entry ORDER BY account_id, created_at DESC, ledger_entry_id DESC) SELECT a.account_id FROM account.account a JOIN last l USING (account_id) WHERE a.cash_balance <> l.cash_balance_after OR a.margin_balance <> l.margin_balance_after;
\endif

\echo '== 4. 확정 청약 수 = 인출된 증거금 수 (기대: 두 값이 같음)'
SELECT (SELECT count(*) FROM subscription.subscription WHERE ipo_id = :ipo AND status = 'CONFIRMED') AS confirmed, (SELECT count(*) FROM account.margin WHERE ipo_id = :ipo AND status = 'PENDING') AS pending_margin;

\echo '== 5. 청약 금액 = 인출 금액, margin_id 일치 (기대: 0행)'
SELECT s.subscription_id FROM subscription.subscription s JOIN account.margin m ON m.subscription_id = s.subscription_id WHERE s.ipo_id = :ipo AND (s.margin_amount <> m.amount OR s.margin_id IS DISTINCT FROM m.margin_id);

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
--   [계좌 단독] account 스키마만 읽는다 → 계좌 서비스 대사 배치로 그대로 옮길 수 있다
--   [서비스 간] subscription, allocation 스키마도 읽는다 → 배치에서는 서비스 계정 권한이 없어 이벤트나 API로 받은 값과 비교해야 한다
\echo '== 11. [계좌 단독] 모든 증거금에 인출 원장이 정확히 1줄, 금액 = 증거금 (기대: 0행. 중복 인출, 원장 누락)'
SELECT m.subscription_id FROM account.margin m LEFT JOIN account.ledger_entry l ON l.margin_id = m.margin_id AND l.type = 'MARGIN_PENDING' WHERE m.ipo_id = :ipo GROUP BY m.subscription_id, m.amount HAVING count(l.ledger_entry_id) <> 1 OR COALESCE(SUM(l.amount), 0) <> m.amount;

\echo '== 12. [계좌 단독] 상태별 원장: PENDING은 정산, 반환 원장 없음 / POSTED는 차감 = posted_amount, 환불 = 증거금 - posted_amount / VOIDED는 반환 = 증거금, 정산 원장 없음 (기대: 0행)'
WITH l AS (SELECT margin_id, COALESCE(SUM(amount) FILTER (WHERE type = 'MARGIN_POST'), 0) AS post, COALESCE(SUM(amount) FILTER (WHERE type = 'REFUND'), 0) AS refund, COALESCE(SUM(amount) FILTER (WHERE type = 'MARGIN_VOID'), 0) AS void FROM account.ledger_entry WHERE margin_id IS NOT NULL GROUP BY margin_id) SELECT m.subscription_id, m.status, m.amount, m.posted_amount, COALESCE(l.post, 0) AS post, COALESCE(l.refund, 0) AS refund, COALESCE(l.void, 0) AS void FROM account.margin m LEFT JOIN l USING (margin_id) WHERE m.ipo_id = :ipo AND NOT CASE m.status WHEN 'PENDING' THEN COALESCE(l.post, 0) + COALESCE(l.refund, 0) + COALESCE(l.void, 0) = 0 WHEN 'POSTED' THEN COALESCE(l.post, 0) = m.posted_amount AND COALESCE(l.refund, 0) = m.amount - m.posted_amount AND COALESCE(l.void, 0) = 0 WHEN 'VOIDED' THEN COALESCE(l.void, 0) = m.amount AND COALESCE(l.post, 0) + COALESCE(l.refund, 0) = 0 ELSE false END;

\echo '== 13. [서비스 간] 배정 결과와 정산 일치: 0주는 VOIDED, 배정이 있으면 POSTED이고 posted_amount = 배정 대금 (기대: 0행. 정산 대기 중이면 PENDING으로 나온다)'
SELECT a.subscription_id, a.allocated_amount, m.status, m.posted_amount FROM allocation.allocation a JOIN allocation.allocation_round r ON r.allocation_round_id = a.allocation_round_id AND r.status = 'DONE' LEFT JOIN account.margin m ON m.subscription_id = a.subscription_id WHERE a.ipo_id = :ipo AND (m.margin_id IS NULL OR CASE WHEN a.allocated_amount = 0 THEN m.status <> 'VOIDED' ELSE m.status <> 'POSTED' OR m.posted_amount <> a.allocated_amount END);

\echo '== 14. [서비스 간] 배정 수량 합 = 일반청약 물량과 신청 수량 합 중 작은 값 (기대: 0행)'
SELECT r.allocation_round_id, r.retail_quantity, SUM(s.requested_quantity) AS requested, SUM(a.allocated_quantity) AS allocated FROM allocation.allocation_round r JOIN allocation.allocation a ON a.allocation_round_id = r.allocation_round_id JOIN subscription.subscription s ON s.subscription_id = a.subscription_id WHERE r.ipo_id = :ipo AND r.status = 'DONE' GROUP BY r.allocation_round_id, r.retail_quantity HAVING SUM(a.allocated_quantity) <> LEAST(r.retail_quantity, SUM(s.requested_quantity));

\echo '== 15. [서비스 간] 배정이 끝난 공모주에 남은 PENDING 증거금 (기대: 0행. 정산 메시지 처리 중이면 잠깐 나올 수 있다)'
SELECT m.subscription_id FROM account.margin m JOIN subscription.ipo i ON i.ipo_id = m.ipo_id WHERE m.ipo_id = :ipo AND i.status = 'ALLOCATED' AND m.status = 'PENDING';
