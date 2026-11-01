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
