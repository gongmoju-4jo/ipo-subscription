-- S6(장애 주입) 정합성 검사 4종 + 요약 (verify.sql 1~22번과 번호가 겹치지 않게 23~26번). 슈퍼유저로 접속해서 실행한다.
-- 23~26번은 부하 테스트 상세 문서 7-11~7-14와 같은 검사이고, 모두 위반만 행으로 나온다 (기대 0행).
-- 사용: psql -v ipo="'<이번 회차 공모주 ID>'" -f verify-s6.sql    (s6.sh가 verify.sql 다음에 실행한다)
\pset pager off
\echo '== S6 요약-1. 상태별 건수 (CANCELLED는 취소 사유별)'
SELECT status, cancel_reason, reject_reason, count(*) FROM subscription.subscription WHERE ipo_id = :ipo GROUP BY 1, 2, 3 ORDER BY 1, 2, 3;

\echo '== S6 요약-2. 요청 수 = CONFIRMED + CANCELLED(TIMEOUT) (기대: other = 0. TIMEOUT 취소 건수는 따로 표시)'
SELECT count(*) AS total,
       count(*) FILTER (WHERE status = 'CONFIRMED') AS confirmed,
       count(*) FILTER (WHERE status = 'CANCELLED' AND cancel_reason = 'TIMEOUT') AS cancelled_timeout,
       count(*) FILTER (WHERE NOT (status = 'CONFIRMED' OR (status = 'CANCELLED' AND cancel_reason = 'TIMEOUT'))) AS other
FROM subscription.subscription WHERE ipo_id = :ipo;

\echo '== S6 요약-3. 반환 기록(margin_void_intent) 수 (기록용. without_margin은 증거금 행 없이 반환 기록만 있는 청약 = 반환이 인출보다 먼저 처리돼 늦은 인출이 막힌 경로)'
SELECT count(*) AS void_intent,
       count(*) FILTER (WHERE m.subscription_id IS NULL) AS without_margin
FROM account.margin_void_intent v
JOIN subscription.subscription s ON s.subscription_id = v.subscription_id
LEFT JOIN account.margin m ON m.subscription_id = v.subscription_id
WHERE s.ipo_id = :ipo;

\echo '== 23. (S6) 취소된 청약에 묶인 돈 없음 (기대: 0행. CANCELLED인데 증거금이 VOIDED가 아님)'
SELECT s.subscription_id, s.cancel_reason, m.status AS margin_status FROM subscription.subscription s JOIN account.margin m ON m.subscription_id = s.subscription_id WHERE s.ipo_id = :ipo AND s.status = 'CANCELLED' AND m.status <> 'VOIDED';

\echo '== 24. (S6) 증거금 없이 반환된 청약은 선행 반환 기록이 있음 (기대: 0행. 증거금도 없고 선행 반환 기록도 없는데 CANCELLED)'
SELECT s.subscription_id, s.cancel_reason FROM subscription.subscription s LEFT JOIN account.margin m ON m.subscription_id = s.subscription_id LEFT JOIN account.margin_void_intent v ON v.subscription_id = s.subscription_id WHERE s.ipo_id = :ipo AND s.status = 'CANCELLED' AND m.subscription_id IS NULL AND v.subscription_id IS NULL;

\echo '== 25. (S6) 선행 반환 기록이 있는 청약에 인출된 증거금 없음 (기대: 0행. 반환이 먼저 처리된 뒤 늦게 온 인출이 돈을 옮기지 않았는지)'
SELECT v.subscription_id, v.reason, m.status AS margin_status FROM account.margin_void_intent v JOIN account.margin m ON m.subscription_id = v.subscription_id WHERE m.ipo_id = :ipo AND m.status = 'PENDING';

\echo '== 26. (S6) 멈춘 SAGA 없음 (기대: 0행. 소진 대기가 끝난 뒤에도 PENDING, COMPENSATING, CANCEL_REQUESTED가 남음)'
SELECT status, count(*) FROM subscription.subscription WHERE ipo_id = :ipo AND status IN ('PENDING', 'COMPENSATING', 'CANCEL_REQUESTED') GROUP BY status;
