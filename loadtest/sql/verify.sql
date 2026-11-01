-- 정합성 검증 SQL (부하테스트-상세 7장). 슈퍼유저로 접속해서 실행한다.
-- 사용: psql -v ipo="'<이번 회차 공모주 ID>'" -f verify.sql    (verify.sh가 대신 실행한다)
\pset pager off
\echo '== 7-1 상태별 건수 (기대: 시나리오별 기대 건수, 부하테스트-상세 8장)'
SELECT status, reject_reason, count(*) FROM subscription.subscription WHERE ipo_id = :ipo GROUP BY 1, 2 ORDER BY 1, 2;

\echo '== 7-2 청약증거금 = PENDING 증거금 합계 (기대: 0행. 이번 종목이 아니라 전체 계좌 기준)'
SELECT a.account_id FROM account.account a LEFT JOIN account.margin m ON m.account_id = a.account_id AND m.status = 'PENDING' GROUP BY a.account_id, a.margin_balance HAVING a.margin_balance <> COALESCE(SUM(m.amount), 0);

\echo '== 7-3 잔고 = 마지막 원장의 거래 후 잔고 (기대: 0행. 위반이 나오면 created_at 동률인지 먼저 확인)'
WITH last AS (SELECT DISTINCT ON (account_id) account_id, cash_balance_after, margin_balance_after FROM account.ledger_entry ORDER BY account_id, created_at DESC, ledger_entry_id DESC) SELECT a.account_id FROM account.account a JOIN last l USING (account_id) WHERE a.cash_balance <> l.cash_balance_after OR a.margin_balance <> l.margin_balance_after;

\echo '== 7-4 확정 청약 수 = 인출된 증거금 수 (기대: 두 값이 같음)'
SELECT (SELECT count(*) FROM subscription.subscription WHERE ipo_id = :ipo AND status = 'CONFIRMED') AS confirmed, (SELECT count(*) FROM account.margin WHERE ipo_id = :ipo AND status = 'PENDING') AS pending_margin;

\echo '== 7-5 청약 금액 = 인출 금액, margin_id 일치 (기대: 0행)'
SELECT s.subscription_id FROM subscription.subscription s JOIN account.margin m ON m.subscription_id = s.subscription_id WHERE s.ipo_id = :ipo AND (s.margin_amount <> m.amount OR s.margin_id IS DISTINCT FROM m.margin_id);

\echo '== 7-6 거절된 청약에 증거금 없음 (기대: 0행)'
SELECT s.subscription_id FROM subscription.subscription s JOIN account.margin m ON m.subscription_id = s.subscription_id WHERE s.ipo_id = :ipo AND s.status = 'REJECTED';

\echo '== 7-7 원장 건수 = 인출 건수 (기대: 7-4의 pending_margin과 같음)'
SELECT count(*) FROM account.ledger_entry l JOIN account.margin m ON m.margin_id = l.margin_id WHERE m.ipo_id = :ipo AND l.type = 'MARGIN_PENDING';

\echo '== 7-8 Outbox 미발행 없음 (기대: 모두 0)'
SELECT 'acc', count(*) FROM account.outbox WHERE published_at IS NULL UNION ALL SELECT 'sub', count(*) FROM subscription.outbox WHERE published_at IS NULL;

\echo '== 7-9 접수 → 확정 시간 (G2, 초. 기대: 기록)'
SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM confirmed_at - received_at)) AS p50, percentile_cont(0.95) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM confirmed_at - received_at)) AS p95, MAX(EXTRACT(EPOCH FROM confirmed_at - received_at)) AS max_s FROM subscription.subscription WHERE ipo_id = :ipo AND status = 'CONFIRMED';

\echo '== 7-10 전체 소진 시간 (기대: 기록)'
SELECT MIN(received_at), MAX(confirmed_at), MAX(confirmed_at) - MIN(received_at) FROM subscription.subscription WHERE ipo_id = :ipo AND status = 'CONFIRMED';
