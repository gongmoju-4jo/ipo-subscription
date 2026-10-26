-- 서비스별 DB 분리 (발제 필수). 컨테이너를 처음 만들 때(볼륨이 비어 있을 때) 한 번만 실행된다
-- DB를 추가하거나 바꾸면 docker-compose down -v 로 볼륨을 지운 뒤 다시 올린다
CREATE DATABASE account_db;
CREATE DATABASE subscription_db;
CREATE DATABASE allocation_db;
