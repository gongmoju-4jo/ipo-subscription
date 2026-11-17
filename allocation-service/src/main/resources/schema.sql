-- 엔티티로 표현할 수 없는 제약, 인덱스 (ERD v1.1). 테이블은 Hibernate(ddl-auto: update)가 먼저 만든다
-- 기동할 때마다 실행되므로 여러 번 실행해도 결과가 같아야 한다
-- 인덱스는 IF NOT EXISTS라 정의(WHERE 등)를 바꿔도 기존 DB에는 반영되지 않는다. 바꾸면 이름을 바꾸거나 PostgreSQL 볼륨을 초기화한다

-- 아웃박스 실패 횟수는 음수가 될 수 없다
-- ADD CONSTRAINT에는 IF NOT EXISTS가 없어서 한 문장으로 지우고 다시 추가한다 (DO $$ 블록은 Spring 스크립트 분리기가 인식하지 못함)
ALTER TABLE outbox
    DROP CONSTRAINT IF EXISTS ck_outbox_retry_count,
    ADD CONSTRAINT ck_outbox_retry_count CHECK (retry_count >= 0);

-- 아웃박스 미발행 조회
CREATE INDEX IF NOT EXISTS ix_outbox_unpublished
    ON outbox (created_at)
    WHERE published_at IS NULL;

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
