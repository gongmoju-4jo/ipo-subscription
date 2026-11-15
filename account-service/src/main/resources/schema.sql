-- account 스키마: 엔티티로 만들 수 없는 제약과 부분 인덱스 (테이블은 ddl-auto: update가 만든다)
-- 서비스가 뜰 때마다 실행된다 (spring.sql.init.mode: always, Hibernate가 테이블을 만든 뒤 실행)
-- PostgreSQL은 ADD CONSTRAINT IF NOT EXISTS가 없어서 DROP IF EXISTS 후 다시 추가한다
-- 기준: ERD v1.1 DDL

-- users
ALTER TABLE users DROP CONSTRAINT IF EXISTS ck_users_role;
ALTER TABLE users ADD CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN'));

-- account: 잔고는 음수가 될 수 없다 (코드 검증이 빠져도 DB가 막는다)
ALTER TABLE account DROP CONSTRAINT IF EXISTS ck_account_cash_balance;
ALTER TABLE account ADD CONSTRAINT ck_account_cash_balance CHECK (cash_balance >= 0);
ALTER TABLE account DROP CONSTRAINT IF EXISTS ck_account_margin_balance;
ALTER TABLE account ADD CONSTRAINT ck_account_margin_balance CHECK (margin_balance >= 0);

-- margin
ALTER TABLE margin DROP CONSTRAINT IF EXISTS ck_margin_amount;
ALTER TABLE margin ADD CONSTRAINT ck_margin_amount CHECK (amount > 0);
ALTER TABLE margin DROP CONSTRAINT IF EXISTS ck_margin_posted_amount;
ALTER TABLE margin ADD CONSTRAINT ck_margin_posted_amount CHECK (posted_amount IS NULL OR (posted_amount >= 0 AND posted_amount <= amount));
ALTER TABLE margin DROP CONSTRAINT IF EXISTS ck_margin_status;
ALTER TABLE margin ADD CONSTRAINT ck_margin_status CHECK (status IN ('PENDING', 'POSTED', 'VOIDED'));
-- POSTED면 정산 금액이 있어야 한다
ALTER TABLE margin DROP CONSTRAINT IF EXISTS ck_margin_posted_required;
ALTER TABLE margin ADD CONSTRAINT ck_margin_posted_required CHECK (status <> 'POSTED' OR posted_amount IS NOT NULL);

-- ledger_entry: 금액은 항상 양수 (방향은 type), 거래 후 잔고는 0 이상, 입금은 요청 ID 필수 (멱등키)
ALTER TABLE ledger_entry DROP CONSTRAINT IF EXISTS ck_ledger_entry_type;
ALTER TABLE ledger_entry ADD CONSTRAINT ck_ledger_entry_type CHECK (type IN ('DEPOSIT', 'MARGIN_PENDING', 'MARGIN_POST', 'MARGIN_VOID', 'REFUND'));
ALTER TABLE ledger_entry DROP CONSTRAINT IF EXISTS ck_ledger_entry_amount;
ALTER TABLE ledger_entry ADD CONSTRAINT ck_ledger_entry_amount CHECK (amount > 0);
ALTER TABLE ledger_entry DROP CONSTRAINT IF EXISTS ck_ledger_entry_balance;
ALTER TABLE ledger_entry ADD CONSTRAINT ck_ledger_entry_balance CHECK (cash_balance_after >= 0 AND margin_balance_after >= 0);
ALTER TABLE ledger_entry DROP CONSTRAINT IF EXISTS ck_ledger_entry_deposit_request;
ALTER TABLE ledger_entry ADD CONSTRAINT ck_ledger_entry_deposit_request CHECK (type <> 'DEPOSIT' OR request_id IS NOT NULL);

-- outbox
ALTER TABLE outbox DROP CONSTRAINT IF EXISTS ck_outbox_retry_count;
ALTER TABLE outbox ADD CONSTRAINT ck_outbox_retry_count CHECK (retry_count >= 0);
-- 미발행 조회 (발행기가 published_at IS NULL인 행만 읽는다)
CREATE INDEX IF NOT EXISTS ix_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;

-- margin_void_intent: 처음 받은 반환 사유 (margin.void-requested의 reason과 같은 값)
ALTER TABLE margin_void_intent DROP CONSTRAINT IF EXISTS ck_margin_void_intent_reason;
ALTER TABLE margin_void_intent ADD CONSTRAINT ck_margin_void_intent_reason CHECK (reason IN ('CONFIRM_FAILED', 'TIMEOUT', 'USER_CANCEL'));

-- shedlock: 스케줄러 중복 실행 방지 락 (common CommonSchedulerLockAutoConfiguration, ShedLock 라이브러리 소유라 엔티티가 없어 여기서 만든다)
-- 작업 이름(name)마다 한 줄. 락을 잡은 인스턴스가 lock_until까지 실행하고, 나머지 인스턴스는 건너뛴다
-- 시각 컬럼만 팀 규칙(TIMESTAMPTZ)과 달리 TIMESTAMP다. ShedLock이 DB 시각을 timezone('utc', CURRENT_TIMESTAMP)(시간대 없는 UTC)로 넣고 비교해서,
-- TIMESTAMPTZ면 접속 시간대(예: IntelliJ로 띄운 KST)에 따라 값이 바뀌어 락 시각이 9시간 어긋날 수 있다
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
