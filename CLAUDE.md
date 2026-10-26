# ipo-subscription (팀 공용)

공모주 청약 MSA. 서비스: account-service(회원, 계좌, 증거금, 원장), subscription-service(공모주, 청약), allocation-service(배정), api-gateway, eureka-server, common.

## 꼭 따를 문서
- 용어, 코드명, 상태값: @docs/UBIQUITOUS_LANGUAGE.md
- 구조, 패키지(4계층), common, 엔티티/예외 규칙: @docs/PROJECT_SETUP.md
- 이슈, 브랜치, 커밋, PR: @docs/GITHUB_GUIDE.md

## 규칙
- 클래스, 필드, 상태값, 토픽 이름은 유비쿼터스 언어 표의 코드명을 그대로 쓴다. 표에 없는 이름이 필요하면 임의로 만들지 말고 먼저 묻는다
- 문서에 없는 필드, 상태값, 상태 전이, API 형식, 서비스 간 계약(이벤트 필드 포함)은 임의로 정하지 않고 먼저 묻는다
- 삭제하지 않는다 (soft delete도 없음). 취소, 거절, 해제는 상태값으로 남기고, 원장(`ledger_entry`)은 추가만 한다
- 잔고 변경과 원장 기록은 계좌 서비스의 정해진 메서드 하나로만 한다
- 비밀값(JWT 키, 비밀번호)은 출력하거나 커밋하지 않는다
