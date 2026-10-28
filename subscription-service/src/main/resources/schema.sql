-- 엔티티로 표현할 수 없는 제약, 인덱스 (ERD v1.1). 테이블은 Hibernate(ddl-auto: update)가 먼저 만든다
-- 기동할 때마다 실행되므로 여러 번 실행해도 결과가 같아야 한다
-- 인덱스는 IF NOT EXISTS라 정의(WHERE 등)를 바꿔도 기존 DB에는 반영되지 않는다. 바꾸면 이름을 바꾸거나 PostgreSQL 볼륨을 초기화한다

-- 공모주 CHECK
-- ADD CONSTRAINT에는 IF NOT EXISTS가 없어서 지우고 다시 추가한다 (DO $$ 블록은 Spring 스크립트 분리기가 인식하지 못함)
ALTER TABLE ipo DROP CONSTRAINT IF EXISTS ck_ipo_offer_price;
ALTER TABLE ipo ADD CONSTRAINT ck_ipo_offer_price CHECK (offer_price >= 1);

ALTER TABLE ipo DROP CONSTRAINT IF EXISTS ck_ipo_retail_quantity;
ALTER TABLE ipo ADD CONSTRAINT ck_ipo_retail_quantity CHECK (retail_quantity >= 1);

-- 균등 물량은 일반청약 물량의 절반 이상, 전체 이하
ALTER TABLE ipo DROP CONSTRAINT IF EXISTS ck_ipo_equal_quantity;
ALTER TABLE ipo ADD CONSTRAINT ck_ipo_equal_quantity
    CHECK (equal_quantity * 2 >= retail_quantity AND equal_quantity <= retail_quantity);

ALTER TABLE ipo DROP CONSTRAINT IF EXISTS ck_ipo_min_quantity;
ALTER TABLE ipo ADD CONSTRAINT ck_ipo_min_quantity CHECK (min_quantity >= 1);

ALTER TABLE ipo DROP CONSTRAINT IF EXISTS ck_ipo_max_quantity;
ALTER TABLE ipo ADD CONSTRAINT ck_ipo_max_quantity CHECK (max_quantity >= min_quantity);

ALTER TABLE ipo DROP CONSTRAINT IF EXISTS ck_ipo_margin_rate;
ALTER TABLE ipo ADD CONSTRAINT ck_ipo_margin_rate CHECK (margin_rate > 0 AND margin_rate <= 1);

ALTER TABLE ipo DROP CONSTRAINT IF EXISTS ck_ipo_subscription_period;
ALTER TABLE ipo ADD CONSTRAINT ck_ipo_subscription_period CHECK (subscription_end_at > subscription_start_at);

-- 환불일은 마감일(KST 날짜)보다 뒤
ALTER TABLE ipo DROP CONSTRAINT IF EXISTS ck_ipo_refund_date;
ALTER TABLE ipo ADD CONSTRAINT ck_ipo_refund_date
    CHECK (refund_date > (subscription_end_at AT TIME ZONE 'Asia/Seoul')::date);

-- 이중청약 방지: 사람 기준, 거절, 취소 뒤에는 재청약 허용 (D-14)
CREATE UNIQUE INDEX IF NOT EXISTS uk_subscription_ipo_user
    ON subscription (ipo_id, user_id)
    WHERE status NOT IN ('REJECTED', 'CANCELLED');

-- 아웃박스 미발행 조회
CREATE INDEX IF NOT EXISTS ix_outbox_unpublished
    ON outbox (created_at)
    WHERE published_at IS NULL;
