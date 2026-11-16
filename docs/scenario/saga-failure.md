# 청약 SAGA 실패 시나리오

> 청약 접수부터 증거금 인출, 확정까지는 청약 서비스와 계좌 서비스에 걸친 분산 트랜잭션이다
> - 실패하면 어떤 처리를 하고, 최종 상태와 증거금이 어떻게 되는지 정의한다
> - 시나리오마다 재현 방법과 결과(테스트, 측정)를 붙인다
> - 알림을 받은 후 운영자가 할 일은 [장애 대응 절차](./incident-response.md)

- **기준**: develop `dc2ac92` (11/16) 코드 + 반환 재요청 백오프 PR #148 (#144, 머지 전)
- **용어, 상태값**: [UBIQUITOUS_LANGUAGE.md](../UBIQUITOUS_LANGUAGE.md) 4장 청약 상태값

## 1. 대상 트랜잭션

| 서비스 | 데이터 변경 | 실패 시 되돌릴 것 |
|-----|---------|------------|
| 청약 | 청약 저장(`PENDING`) → `CONFIRMED` | 청약 `CANCELLED` |
| 계좌 | 예수금 차감, 청약증거금 증가, 증거금 `PENDING`, 원장 `MARGIN_PENDING` | 증거금 반환(`VOIDED`): 청약증거금 차감, 예수금 증가, 반환 원장 추가 |

- **한 트랜잭션 불가**: 청약, 계좌가 DB 스키마와 계정이 분리되어 있어 두 서비스의 변경을 하나의 DB 트랜잭션으로 처리 불가
- **보상 방식**: DB 롤백이 아니라 반대 거래(증거금 반환)를 새로 실행. 원장은 추가만 함

## 2. 방식: Orchestration

> 청약 서비스가 SAGA 상태(청약 상태값)를 보유하고 다음 단계와 보상을 결정한다

| 비교 | Orchestration (선택) | Choreography |
|----|-------------------|--------------|
| 보상 시작점 | 청약 서비스 한 곳 | 각 서비스가 실패 이벤트를 구독해 각자 판단 |
| 진행 상태 조회 | 청약 상태값 1개로 확인 | 여러 서비스 기록을 조합해야 확인 |
| 시간 초과 감지 | 청약 서비스 스케줄러가 `PENDING` 청약 조회 | 상태를 보유한 곳이 없어 별도 감지 필요 |
| 한계 | 청약 서비스가 모든 단계를 알아야 함 (단계 추가 시 청약 서비스 수정) | 서비스 간 결합은 낮음 |

- **이유**: 참여 서비스가 2개(청약, 계좌)라 중앙 조정의 부담이 작고, 청약 상태가 청약 서비스에 있어 보상 시작점이 한 곳이면 추적이 쉬움
- **출처**: SA 서비스 분리 표 ("청약 상태가 청약 서비스에 있어 보상 시작점이 한 곳 → 추적이 쉬움")

## 3. 이벤트 흐름

```
[청약 API] 청약 저장(PENDING) + Outbox(margin.reserve-requested)  ← 같은 트랜잭션, 202 응답
    │
    ▼ margin.reserve-requested (키 accountId)
[계좌] 예수금 → 청약증거금 인출 (증거금 PENDING, 원장 MARGIN_PENDING)
    ├─ 성공 ─ margin.reserved ──────▶ [청약] CONFIRMED                       (정상 플로우)
    └─ 거절 ─ margin.reserve-failed ─▶ [청약] REJECTED                        (인출 전 실패, 보상 없음)

보상 (인출 후 실패, 또는 인출 여부를 모르는 시간 초과)
[청약] COMPENSATING + Outbox(margin.void-requested)  ← 같은 트랜잭션
    │
    ▼ margin.void-requested
[계좌] 증거금 반환 (증거금 VOIDED, 청약증거금 → 예수금, 반환 원장)
    │
    ▼ margin.voided
[청약] CANCELLED (재청약 가능)
```

- **발신 유실 방지**: 상태 변경과 발행할 이벤트를 같은 트랜잭션에서 Outbox에 저장하고, 발행기가 Kafka로 전송 (D-25, D-29)
- **수신 중복 방지**: 모든 Consumer가 이벤트 처리 기록(`processed_event`, `eventId`)으로 같은 이벤트를 1번만 반영
- **수신 실패**: 1초 → 2초 → 4초 재시도 3회 후 `{토픽}.DLT`로 전송
  - 운영자 처리: [장애 대응 절차](./incident-response.md) 3절

## 4. 실패 시나리오

> 8개 시나리오의 최종 상태
> - `CONFIRMED`, `REJECTED`, `CANCELLED` 중 하나, 또는 운영자 확인 대상(ERROR 로그, DLT)으로 기록
> - 증거금이 인출된 채 청약만 종료되는 경우 없음

| # | 실패 상황 | 시스템 처리 | 최종 청약 상태 | 증거금 |
|---|-------|--------|----------|-----|
| F1 | 예수금 부족 | 계좌가 `margin.reserve-failed(INSUFFICIENT_BALANCE)` 발행 | `REJECTED` | 인출 없음 |
| F2 | 계좌 없음, 소유자 다름 | 계좌가 `margin.reserve-failed(ACCOUNT_NOT_FOUND)` 발행 (두 경우 같은 사유로 응답, 로그로 구분) | `REJECTED` | 인출 없음 |
| F3 | 이중청약 (같은 회원, 같은 공모주) | 접수 시 청약 서비스가 바로 거절, 계좌 요청 없음 | `REJECTED(ALREADY_SUBSCRIBED)` | 인출 없음 |
| F4 | 계좌 응답 없음 (계좌 서비스 중단) | 30초마다 인출 재요청, 3회 후 시간 초과 보상 | `COMPENSATING(TIMEOUT)` → `CANCELLED` | 인출됐으면 반환, 아니면 반환 기록만 |
| F5 | 인출 후 확정 실패 (확정 저장 예외 반복) | 재시도 3회 소진 후 보상 시작 | `COMPENSATING(CONFIRM_FAILED)` → `CANCELLED` | 반환 |
| F6 | 반환 응답 없음 | 응답이 수신될 때까지 반환 재요청 (간격 30초부터 2배, 5분 상한). 5번째 요청부터 ERROR 로그 | 응답 후 `CANCELLED` | 응답 후 반환 |
| F7 | 메시지 형식 오류, 데이터 불일치 | 재시도 없이 DLT | 변경 없음 | 변경 없음, 운영자 확인 |
| F8 | 같은 메시지 중복 수신 | 이벤트 처리 기록으로 2번째부터 건너뜀 | 변경 없음 | 1번만 반영 |

- **F4 상세**: 응답이 없으면 계좌가 인출했는지 알 수 없어 `margin_id` 없이 반환 요청
  - 계좌는 증거금이 있으면 반환, 없으면 반환 기록(`margin_void_intent`)을 저장해 이후 도착한 인출 요청을 거절
  - 시간 초과 기준: 마지막 요청 후 30초, 최대 3회 → 접수 약 90초 후 보상 (`saga.timeout.*`)
  - 마지막 인출 요청이 늦게 발행됐으면 보상을 다음 주기로 미룸 (계좌가 응답할 시간 보장)
- **F5 상세**: `MarginReservedCompensationRecoverer`가 재시도 소진 후 DLT 전에 보상 시작. 보상 시작도 실패하면 `margin.reserved.DLT`
- **F6 상세**: 재요청 대기 시간 = min(30초 x 2^(요청 횟수 - 1), 5분) → 30, 60, 120, 240, 300초 (`saga.void-retry.*`)
  - 최대 횟수 없음. 청약 상태를 강제로 종료하지 않음 (결정 14)
  - 알림: 요청 5회 이상(보상 시작 약 7분 30초 후) 응답이 없으면 "반환 응답이 N번째 요청까지 오지 않음. ... 운영자 확인 필요" ERROR 로그
  - 이전 동작 (PR #148 이전): 3회 후 재요청을 멈추고 `COMPENSATING`에 머무름, 증거금 `PENDING` 유지
- **F7 상세**: 반환 금액이 청약 증거금과 불일치하면 `CANCELLED`로 바꾸지 않고 DLT (계좌, 청약 기록 불일치 방지)
- **경쟁 조건**: 응답과 시간 초과 보상이 동시에 발생하면 낙관적 락(`@Version`)으로 1개만 저장, 나머지는 다음 주기에 재판단

## 5. 재현과 결과

> 재현 방법
> - F4: 부하 중 계좌 서비스 강제 종료로 7회 재현, 정합성 위반 0건
> - F5: Kafka, PostgreSQL 테스트로 재현
> - F1-F3: 흐름 스크립트로 재현

| # | 재현 방법 | 결과 | 출처 |
|---|-------|----|----|
| F1, F3 | `Q2=100 ./scripts/subscription-flow.sh` (11/2 확인) | `REJECTED(INSUFFICIENT_BALANCE)`, `REJECTED(ALREADY_SUBSCRIBED)`, 잔고 변화 없음 | [subscription-flow.md](./subscription-flow.md) |
| F2 | Swagger로 없는 계좌 ID로 청약 | `REJECTED(ACCOUNT_NOT_FOUND)`, 잔고 변화 없음 | [subscription-flow.md](./subscription-flow.md) 6절 |
| F4 | S6: 부하(100건/초 x 10초) 중 계좌 서비스 `docker kill`, 80초 후 재시작 | 4회 중앙값: `CONFIRMED` 850, `CANCELLED(TIMEOUT)` 150건, 정합성 위반 0 | `loadtest/s6.sh`, PR #143 |
| F5 | 확정 처리가 항상 예외를 발생시키도록 대체 | 4회 시도 후 `COMPENSATING(CONFIRM_FAILED)`, `margin.void-requested` 발행, DLT 0건 | `MarginReservedCompensationKafkaTest` |
| F6 | S6-d: 반환 처리만 약 8분 실패 | 장애 해제 후 64-66초 안에 전부 `CANCELLED`, `PENDING` 증거금 0원 (3회). PR #148 이전은 전부 `COMPENSATING`에 머무름 | PR #148 |
| F7, F8 | 형식 오류 메시지 발행, DLT 재발행 2번 | 형식 오류는 바로 DLT, 2번째 재발행은 "중복 수신, 건너뜀" | [장애 대응 절차](./incident-response.md) 3-3, 11/16 실측 |

<details>
<summary>S6 상세 (변형별 조건, 지표, 실행 명령)</summary>

| 지표 | S6-a (10초 장애, 3회) | S6-b (80초 장애, 4회) |
|----|------------------|------------------|
| 종료 → 서비스 UP | 33.3초 | 103.3초 |
| `CONFIRMED` | 1,000 | 850 (743-1,000) |
| `CANCELLED(TIMEOUT)` | 0 | 150 (0-257) |
| 반환 기록 (`margin_void_intent`) | 0 | 150 (0-257) |
| 접수 API p95 | 9.4ms | 9.0ms |
| DLT 증가, 정합성 위반 | 0, 0 | 0, 0 |

- 환경: 로컬 Docker, 인스턴스 1개, DB, Kafka, k6 같은 머신, 코드 `f322561`
- 정합성 검사: `loadtest/sql/verify.sql`(22종) + `verify-s6.sql`(유실, 중복 인출, 취소 후 `PENDING` 증거금, 멈춘 SAGA)
- 실행

```bash
WARM=1 OUTAGE=10 bash loadtest/s6.sh --run-id S6-a-r1   # 짧은 장애
WARM=1 OUTAGE=80 bash loadtest/s6.sh --run-id S6-b-r1   # 긴 장애 (시간 초과 보상 발생)
./gradlew :subscription-service:test --tests '*MarginReservedCompensationKafkaTest'   # F5 (Docker 필요)
```

</details>

- **장애 중 접수**: 계좌 서비스가 중단돼도 접수 API는 계속 202 응답 (접수는 계좌 서비스를 호출하지 않음, S6 p95 9.0-9.4ms)

## 6. 한계

- **PR #148 머지 전 기준**: F6는 PR #148 동작으로 작성함. 머지 전 develop은 반환 재요청 3회 후 멈춤
- **F6 무기한 대기**: 계좌가 계속 응답하지 않으면 5분마다 재요청하고 `COMPENSATING`에 머무름 (운영자 확인 필요)
- **청약 취소 API 없음**: `CANCEL_REQUESTED` 상태값과 반환 처리는 있으나 투자자 취소 API는 구현하지 않음
- **배정 후 정산은 범위 밖**: 배정 → 정산(`POSTED`), 환불 흐름의 실패 처리는 이 문서에 포함하지 않음 (DLT, 원장 대사로 확인)
- **인스턴스 2개 이상**: 시간 초과, 반환 재요청은 낙관적 락으로 1개만 저장되지만, 반환 지연 알림은 인스턴스마다 기록 가능
