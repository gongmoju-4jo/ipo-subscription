# GitHub 협업 가이드

## 목차

1. [작업 흐름](#1--작업-흐름)
2. [브랜치](#2-브랜치)
3. [이슈](#3--이슈)
4. [라벨](#4-라벨)
5. [커밋](#5--커밋)
6. [PR과 리뷰](#6--pr과-리뷰)
7. [명령어](#7-명령어)
8. [충돌 해결](#8-충돌-해결)
9. [금지](#9-금지)

---

## 1. 📌 작업 흐름

```
① 이슈 생성 (템플릿 + 라벨 + 담당자) -> 생성되면 이슈 제목 오른쪽 또는 하단에 번호가 생깁니다. 
    ex) 청약 신청 구현 #12
② develop에서 브랜치         feat/12-subscription-create
③ 커밋                       [feat] 청약 신청 API 추가
④ develop으로 PR             Closes #12
⑤ 리뷰 Approve
⑥ Merge → 이슈 자동 종료 → 브랜치 삭제(선택)
```

---

## 2. 브랜치

- `main`: 최종본 (발표, 제출), 직접 push ❌
- `develop`: 개발 통합, 모든 PR 대상, 직접 push ❌
- 작업 브랜치: 이슈 1개 = 브랜치 1개, develop에서 생성

### 📌 이름 규칙

`{타입}/{이슈번호}-{설명}` (영어 소문자, 하이픈)

| 타입 | 예시 |
| --- | --- |
| `feat` | `feat/12-subscription-create` |
| `fix` | `fix/20-margin-void-error` |
| `refactor` | `refactor/31-allocation-service` |
| `chore` | `chore/5-gateway-routing` |
| `docs` | `docs/8-api-spec` |
| `test` | `test/40-ledger-test` |

---

## 3. 📌 이슈

> 💡 **작업 전에 이슈부터 만듭니다.** 이슈 번호로 브랜치와 PR이 연결됨

| 템플릿 | 제목 |
| --- | --- |
| Bug report | `[BUG] ...` |
| Feature request | `[FEAT] ...` |
| Refactoring request | `[REFACTOR] ...` |

- Assignees, Labels 지정
- Todo 체크리스트는 PR 전에 전부 체크

### 이슈 단위

**이슈 1개 = PR 1개 = 1~2일 안에 끝나는 작업**

- ✅ 청약 신청 API / 공모주 목록, 상세 조회 API / 회원가입
- ❌ subscription-service 개발 (너무 큼) / 청약 DTO 만들기 (너무 작음)
- 여러 서비스에 걸치면 서비스별로 쪼개기. 예) 증거금 확보 → subscription 확보 요청 발행 / account 증거금 인출 Consumer

---

## 4. 라벨

유형 + 서비스 조합. 예: `✨ feature` `📋 subscription`

| 분류 | 라벨 |
| --- | --- |
| 유형 | `🐛 bug` `✨ feature` `♻️ refactor` `📝 docs` `🔧 chore` `✅ test` `🐳 infra` `🔥 hotfix` |
| 서비스 | `📋 subscription` `💰 account` `🎯 allocation` `🚪 gateway` `🧩 common` |


---

## 5. 📌 커밋

```
[타입] 한글 요약 #이슈번호

- 변경 내용
```

| 타입 | 용도 |
| --- | --- |
| `[feat]` | 기능 |
| `[fix]` | 버그 |
| `[refactor]` | 개선 (동작 동일) |
| `[chore]` | 설정, 빌드, 의존성 |
| `[docs]` | 문서 |
| `[test]` | 테스트 |

- 커밋 1개 = 변경 1개
- 빌드 확인 후 커밋 (`./gradlew build -x test`)

---

## 6. 📌 PR과 리뷰

### PR

1. 브랜치 push → **Compare & pull request**
2. base = **`develop`** (main ❌)
3. 제목: `[feat] 청약 신청 API 추가 (#12)`
4. 본문에 `Closes #12` → 머지 시 이슈 자동 종료
5. Reviewers, Assignees, Labels 지정

### 리뷰 규칙

| 항목 | 규칙 |
| --- | --- |
| 리뷰어 | 연관 기능 담당자 |
| 머지 | 작성자 |
| 방식 | Create a merge commit (기본값) |

### 체크 포인트

- 팀 규칙 ([PROJECT_SETUP.md](./PROJECT_SETUP.md)): 패키지 위치, 테이블 이름, UUID 참조, 엔티티 직접 반환, 삭제 대신 상태값
- 인가: 본인 데이터(본인 계좌, 본인 청약)만 조회, 수정 / ADMIN 전용 경로
- 예외: ErrorCode, 실패 시 롤백, 보상
- 돈: 잔고 변경과 원장 기록이 같은 트랜잭션인지, 같은 이벤트를 두 번 받아도 한 번만 반영되는지(멱등)
- 서비스 간 API 계약 변경 여부

코멘트 머리말: `[필수]` `[제안]` `[질문]`

---

## 7. 명령어

```bash
# 시작
git switch develop && git pull origin develop
git switch -c feat/12-subscription-create

# 커밋, 푸시
git add subscription-service/
git commit -m "[feat] 청약 신청 API 추가 #12"
git push -u origin feat/12-subscription-create

# develop 최신화
git fetch origin && git merge origin/develop

# 머지 후 정리
git switch develop && git pull origin develop
git branch -d feat/12-subscription-create

# 되돌리기
git restore 파일명              # 변경 취소
git restore --staged 파일명     # add 취소
git commit --amend             # 마지막 커밋 수정 (push 전만)
```

---

## 8. 충돌 해결

```bash
git fetch origin && git merge origin/develop
# IntelliJ: Git → Resolve Conflicts…
./gradlew build -x test
git add . && git commit && git push
```

> ⚠️ 충돌 발생 시 담당자에게 확인 필수

>**충돌 잦은 파일 (수정 전 공유)**: `settings.gradle`, 루트 `build.gradle`, `common`, `docker-compose.yml`, 게이트웨이 라우팅

---

## 9. 금지

- `main`, `develop` 직접 push 
- force push
- `build/`, `.idea/` 커밋
- 비밀값 커밋 (`application-secret.yml`, `.env`)

> ⚠️ 비밀값을 올렸다면 즉시 팀에 알리고 키 재발급하기. 커밋을 지워도 히스토리에 남음.

---

문제 해결 시 **상황 → 원인 → 해결 → 효과**를 정리해 공유 (요구사항)
