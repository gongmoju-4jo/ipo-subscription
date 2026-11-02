# 해피 시나리오 연동 확인 (#18)

로그인 → 입금 → 청약 202 → 증거금 인출 → CONFIRMED, 잔액 부족 → REJECTED를 Docker로 전부 띄워서 확인했다.

## 한눈에 보기

| 확인 항목                             | 결과                                                                  |
|-----------------------------------|---------------------------------------------------------------------|
| compose 전체 기동                     | 서비스 5개 healthy                                                      |
| 토픽                                | 10개 + DLT 10개 = 20개, 모두 파티션 3                                       |
| 가입 → 로그인 → 계좌 개설 → 입금 200,000     | 성공                                                                  |
| 청약 10주 (공모주 A, 150,000원)          | 202 → 2초 뒤 `CONFIRMED`                                              |
| 잔고                                | 예수금 50,000 / 청약증거금 150,000                                          |
| 원장                                | 2행 (`DEPOSIT`, `MARGIN_PENDING`)                                    |
| 잔액 부족 청약 (공모주 B 100주, 500,000원)   | 202 → 2초 뒤 `REJECTED`, `rejectReason=INSUFFICIENT_BALANCE`          |
| 없는 계좌로 청약 (Swagger로 직접)           | 202 → 0.6초 뒤 `REJECTED`, `rejectReason=ACCOUNT_NOT_FOUND`, 잔고 변화 없음 |
| 이중청약 (공모주 A에 requestId만 바꿔 다시 청약) | 202 → 1초 뒤 `REJECTED`, `rejectReason=ALREADY_SUBSCRIBED`, 잔고 변화 없음  |

## 측정 환경

| 항목     | 값                                                                                                 |
|--------|---------------------------------------------------------------------------------------------------|
| 일시, 코드 | 2026-11-02, develop `f330fd0` (#16, #59 머지 후)                                                     |
| 기기     | MacBook Air (`Mac15,13`), Apple M3 8코어, 메모리 16GB, macOS 14.7.5                                    |
| Docker | Engine 29.5.2 (Ubuntu 24.04 VM), VM 할당 CPU 4, 메모리 약 5.8GiB, Compose 5.6.0                         |
| 구성     | `./gradlew build -x test && docker-compose up -d --build`, 서비스 인스턴스 각 1개, PostgreSQL, Kafka 같은 머신 |

> 기능 연동 확인용이라 응답 시간, 처리량은 측정하지 않았다.

확인 명령: `sysctl -n hw.model machdep.cpu.brand_string hw.ncpu hw.memsize`, `sw_vers -productVersion`,
`docker info --format '{{.NCPU}} {{.MemTotal}} {{.ServerVersion}} {{.OperatingSystem}}'`,
`docker-compose version --short`

## 실행 방법

```bash
./gradlew build -x test && docker-compose up -d --build
docker-compose ps                 # 서비스 healthy 확인. Gateway가 서비스를 찾는 데 최대 30초 (처음 요청 503 가능)
Q2=100 ./scripts/happy.sh                 # Gateway(8080)를 거쳐 호출. 토큰은 출력하지 않는다
```

> 처음 실행 때 회원가입이 503이었다 (Gateway가 아직 서비스를 못 찾음). 30초쯤 뒤 다시 실행한 결과가 아래 출력이다.

## 1. 컨테이너 상태

```
ipo-account-service Up 2 minutes (healthy)
ipo-allocation-service Up 2 minutes (healthy)
ipo-api-gateway Up 10 minutes (healthy)
ipo-eureka-server Up 10 minutes (healthy)
ipo-grafana Up 3 minutes (healthy)
ipo-kafka Up 10 minutes (healthy)
ipo-kafka-init Exited (0) 2 minutes ago
ipo-kafka-ui Up 10 minutes
ipo-postgres Up 3 minutes (healthy)
ipo-prometheus Up 10 minutes (healthy)
ipo-subscription-service Up 2 minutes (healthy)
```

## 2. 토픽 (`kafka-topics.sh --describe`)

```
__consumer_offsets PartitionCount: 50
allocation.finished PartitionCount: 3
allocation.finished.DLT PartitionCount: 3
allocation.result PartitionCount: 3
allocation.result.DLT PartitionCount: 3
ipo.closed PartitionCount: 3
ipo.closed.DLT PartitionCount: 3
margin.reserve-failed PartitionCount: 3
margin.reserve-failed.DLT PartitionCount: 3
margin.reserve-requested PartitionCount: 3
margin.reserve-requested.DLT PartitionCount: 3
margin.reserved PartitionCount: 3
margin.reserved.DLT PartitionCount: 3
margin.settled PartitionCount: 3
margin.settled.DLT PartitionCount: 3
margin.void-requested PartitionCount: 3
margin.void-requested.DLT PartitionCount: 3
margin.voided PartitionCount: 3
margin.voided.DLT PartitionCount: 3
subscription.requested PartitionCount: 3
subscription.requested.DLT PartitionCount: 3
```

## 3. 시나리오 실행 출력 (`Q2=100 ./scripts/happy.sh`)

> 8번 제목은 "10주"로 잘못 찍혔다. 실제로 보낸 건 100주(500,000원)다. 스크립트 제목은 고쳤다.

```

### 1. 회원가입
{"success":true,"data":{"userId":"940e2b74-7883-4148-97a6-5b42a802334d","loginId":"happy625600","name":"해피","createdAt":"2026-11-02T22:20:00+09:00"},"message":"회원가입이 완료되었습니다."}

### 2. 로그인 (토큰은 출력하지 않음)
accessToken 길이=223

### 3. 계좌 개설
{"success":true,"data":{"accountId":"20d804e5-bd75-4cb5-a2bd-c67bbf303dc5","accountNumber":"691447329543","userId":"940e2b74-7883-4148-97a6-5b42a802334d","cashBalance":0,"marginBalance":0,"createdAt":"2026-11-02T22:20:00+09:00"},"message":"계좌가 개설되었습니다."}

### 4. 입금 200,000원
{"success":true,"data":{"depositId":"9a7d19cc-77ed-4e75-b06a-6f827a5cd1da","accountId":"20d804e5-bd75-4cb5-a2bd-c67bbf303dc5","amount":200000,"cashBalance":200000,"marginBalance":0,"createdAt":"2026-11-02T22:20:00+09:00"},"message":"입금이 완료되었습니다."}

### 5. 청약 신청 10주 (150,000원) → 202
{"success":true,"data":{"subscriptionId":"c56f1326-818b-32e7-bab6-62b5e76df4fe","requestId":"b055e61b-6a5b-4d52-b006-f63ed732fb3f","status":"ACCEPTED","statusUrl":"/api/v1/subscriptions/c56f1326-818b-32e7-bab6-62b5e76df4fe"},"message":"청약 신청을 접수했습니다."}
HTTP 202

### 6. 청약 상태 (CONFIRMED가 될 때까지 최대 30초)
{"success":true,"data":{"subscriptionId":"c56f1326-818b-32e7-bab6-62b5e76df4fe","requestId":"b055e61b-6a5b-4d52-b006-f63ed732fb3f","ipoId":"8b2d0000-0000-4000-8000-000000000001","ipoName":"테스트 공모주 A","accountId":"20d804e5-bd75-4cb5-a2bd-c67bbf303dc5","requestedQuantity":10,"marginAmount":150000,"status":"CONFIRMED","rejectReason":null,"cancelReason":null,"allocatedQuantity":null,"allocatedAmount":null,"refundAmount":null,"receivedAt":"2026-11-02T22:20:00+09:00","confirmedAt":"2026-11-02T22:20:02+09:00"},"message":"청약 상태를 조회했습니다."}
→ 2초 후 status=CONFIRMED

### 7. 계좌 잔고 (예수금 50,000 / 청약증거금 150,000 예상)
{"success":true,"data":{"accountId":"20d804e5-bd75-4cb5-a2bd-c67bbf303dc5","accountNumber":"691447329543","userId":"940e2b74-7883-4148-97a6-5b42a802334d","cashBalance":50000,"marginBalance":150000,"createdAt":"2026-11-02T22:20:00+09:00"},"message":"계좌를 조회했습니다."}

### 8. 잔액 부족 청약 10주 (예수금 50,000 < 150,000) → REJECTED 예상
{"success":true,"data":{"subscriptionId":"ff6efb7a-8cb3-384e-a047-fe9545b0871e","requestId":"d4031374-8456-464b-ad12-a2e852f5a1fe","status":"ACCEPTED","statusUrl":"/api/v1/subscriptions/ff6efb7a-8cb3-384e-a047-fe9545b0871e"},"message":"청약 신청을 접수했습니다."}
HTTP 202
{"success":true,"data":{"subscriptionId":"ff6efb7a-8cb3-384e-a047-fe9545b0871e","requestId":"d4031374-8456-464b-ad12-a2e852f5a1fe","ipoId":"8b2d0000-0000-4000-8000-000000000002","ipoName":"테스트 공모주 B","accountId":"20d804e5-bd75-4cb5-a2bd-c67bbf303dc5","requestedQuantity":100,"marginAmount":500000,"status":"REJECTED","rejectReason":"INSUFFICIENT_BALANCE","cancelReason":null,"allocatedQuantity":null,"allocatedAmount":null,"refundAmount":null,"receivedAt":"2026-11-02T22:20:02+09:00","confirmedAt":null},"message":"청약 상태를 조회했습니다."}
→ 2초 후 status=REJECTED

ACC=20d804e5-bd75-4cb5-a2bd-c67bbf303dc5 SUB=c56f1326-818b-32e7-bab6-62b5e76df4fe SUB2=ff6efb7a-8cb3-384e-a047-fe9545b0871e
```

## 4. DB 확인 (원장, 계좌 Outbox)

```
      type      | amount | cash_balance_after | margin_balance_after 
----------------+--------+--------------------+----------------------
 DEPOSIT        | 200000 |             200000 |                    0
 MARGIN_PENDING | 150000 |              50000 |               150000
(2 rows)

         topic         | count | published 
-----------------------+-------+-----------
 margin.reserved       |     1 |         1
 margin.reserve-failed |     1 |         1
(2 rows)
```

## 5. account-service 로그

```
[nio-8081-exec-6] c.g.a.a.account.DepositService           : [입금] 원장 저장 (커밋 전). accountId=20d804e5-bd75-4cb5-a2bd-c67bbf303dc5, amount=200000, cashBalanceAfter=200000, requestId=b680d59f-62cf-4c50-90c7-835abeea2d97
[ntainer#0-2-C-1] c.g.a.a.margin.MarginReserveService      : [증거금 인출] 인출 저장 (커밋 전). eventId=fea34003-3135-4196-8bda-46a331c6375f, subscriptionId=c56f1326-818b-32e7-bab6-62b5e76df4fe, accountId=20d804e5-bd75-4cb5-a2bd-c67bbf303dc5, marginId=95d568ce-9aac-4480-a01f-ec81c7da0ab4, amount=150000
[ntainer#0-2-C-1] c.g.a.a.margin.MarginReserveService      : [증거금 인출] 거절 INSUFFICIENT_BALANCE(예수금 부족). eventId=9d2de802-03fc-465d-9c5a-62371be72f88, subscriptionId=ff6efb7a-8cb3-384e-a047-fe9545b0871e, accountId=20d804e5-bd75-4cb5-a2bd-c67bbf303dc5, amount=500000, cashBalance=50000
```

## 6. 실패 경로 2: 없는 계좌로 청약 (Swagger로 직접 확인)

통합 Swagger에서 `investor01`로 로그인한 뒤, 본인 계좌가 아닌 accountId(Swagger 예시값)로 청약했다.

- 청약 서비스는 계좌 DB를 볼 수 없어서 접수만 하고 202를 준다 (Kafka에 넣고 바로 응답)
- 계좌 확인은 계좌 서비스의 인출 Consumer가 한다. 계좌가 없거나 본인 계좌가 아니면 같은 사유 `ACCOUNT_NOT_FOUND`로 거절한다 (다른 사람 계좌의 존재 여부를 숨김, 로그에는 "계좌
  없음"/"소유자 다름"으로 구분)
- 잔고는 그대로다 (원장, 증거금 행 없음)

요청 (`POST /api/v1/subscriptions`)

```json
{
  "requestId": "3b8f0000-0000-4000-8000-000000000031",
  "ipoId": "8b2d0000-0000-4000-8000-000000000001",
  "accountId": "9d4e0000-0000-4000-8000-000000000002",
  "requestedQuantity": 100
}
```

응답 (202)

```json
{
  "success": true,
  "data": {
    "subscriptionId": "2c8a7e42-080f-3701-914a-caa89133fbdb",
    "requestId": "3b8f0000-0000-4000-8000-000000000031",
    "status": "ACCEPTED",
    "statusUrl": "/api/v1/subscriptions/2c8a7e42-080f-3701-914a-caa89133fbdb"
  },
  "message": "청약 신청을 접수했습니다."
}
```

청약 상태 (DB)

```
           subscription_id            |              account_id              |  status  |   reject_reason   
--------------------------------------+--------------------------------------+----------+-------------------
 2c8a7e42-080f-3701-914a-caa89133fbdb | 9d4e0000-0000-4000-8000-000000000002 | REJECTED | ACCOUNT_NOT_FOUND
(1 row)
```

로그 (account-service, subscription-service)

```
2026-11-02T22:39:41.586+09:00  INFO 1 --- [account-service] [ntainer#0-1-C-1] c.g.a.a.margin.MarginReserveService      : [증거금 인출] 거절 ACCOUNT_NOT_FOUND(계좌 없음). eventId=a45f4ba9-edb0-4dc1-a34d-efef2864ad19, subscriptionId=2c8a7e42-080f-3701-914a-caa89133fbdb, accountId=9d4e0000-0000-4000-8000-000000000002
2026-11-02T22:39:41.036+09:00  INFO 1 --- [subscription-service] [ntainer#2-1-C-1] c.g.s.i.m.SubscriptionRequestedConsumer  : 접수 처리 완료. result=ACCEPTED, requestId=3b8f0000-0000-4000-8000-000000000031, subscriptionId=2c8a7e42-080f-3701-914a-caa89133fbdb
2026-11-02T22:39:41.663+09:00  INFO 1 --- [subscription-service] [ntainer#0-1-C-1] c.g.s.a.s.SubscriptionRejectService      : 청약을 거절함. subscriptionId=2c8a7e42-080f-3701-914a-caa89133fbdb, reason=ACCOUNT_NOT_FOUND
2026-11-02T22:39:41.665+09:00  INFO 1 --- [subscription-service] [ntainer#0-1-C-1] c.g.s.i.m.MarginReserveFailedConsumer    : 증거금 확보 실패 응답 처리 완료. result=REJECTED, eventId=225734ae-e4f1-4fd7-8fe4-927f0a4d283f, subscriptionId=2c8a7e42-080f-3701-914a-caa89133fbdb
```

## 7. 실패 경로 3: 이중청약

같은 사람이 같은 종목(공모주 A)에 requestId만 바꿔서 다시 청약했다. 스크립트 9, 10번 단계다 (2026-11-02 23:14 실행).

- 이중청약은 청약 서비스가 접수할 때 막는다. 계좌 서비스까지 가지 않아서 계좌 로그에는 아무것도 안 남는다
- 거절, 취소된 청약은 다시 청약할 수 있어서 이 검사에서 빠진다

스크립트 출력 (9, 10번)

```
### 9. 이중청약: 공모주 A에 requestId만 바꿔 다시 10주 → REJECTED(ALREADY_SUBSCRIBED) 예상
{"success":true,"data":{"subscriptionId":"7dbe2671-6eca-3690-bef2-69e3f9b0e934","requestId":"d14025f5-f054-41e3-8b9e-4466f50d8240","status":"ACCEPTED","statusUrl":"/api/v1/subscriptions/7dbe2671-6eca-3690-bef2-69e3f9b0e934"},"message":"청약 신청을 접수했습니다."}
HTTP 202
{"success":true,"data":{"subscriptionId":"7dbe2671-6eca-3690-bef2-69e3f9b0e934","requestId":"d14025f5-f054-41e3-8b9e-4466f50d8240","ipoId":"8b2d0000-0000-4000-8000-000000000001","ipoName":"테스트 공모주 A","accountId":"99921083-0388-4203-9990-2d3f3ffd727b","requestedQuantity":10,"marginAmount":150000,"status":"REJECTED","rejectReason":"ALREADY_SUBSCRIBED","cancelReason":null,"allocatedQuantity":null,"allocatedAmount":null,"refundAmount":null,"receivedAt":"2026-11-02T23:14:45+09:00","confirmedAt":null},"message":"청약 상태를 조회했습니다."}
→ 1초 후 status=REJECTED

### 10. 계좌 잔고 (이중청약 거절 뒤에도 예수금 50,000 / 청약증거금 150,000 그대로 예상)
{"success":true,"data":{"accountId":"99921083-0388-4203-9990-2d3f3ffd727b","accountNumber":"883000337307","userId":"d83d07b6-670a-425b-a9c2-c39dc276e9d9","cashBalance":50000,"marginBalance":150000,"createdAt":"2026-11-02T23:14:41+09:00"},"message":"계좌를 조회했습니다."}

ACC=99921083-0388-4203-9990-2d3f3ffd727b SUB=d7728a75-61f8-31e4-9531-50c8a55fd6b6 SUB2=6d5820ef-c5a7-3404-a536-2d36e35ccaea SUB3=7dbe2671-6eca-3690-bef2-69e3f9b0e934
```

subscription-service 로그

```
2026-11-02T23:14:45.121+09:00  INFO 1 --- [subscription-service] [ntainer#2-0-C-1] c.g.s.a.s.SubscriptionAcceptService      : 이중청약으로 거절함. subscriptionId=7dbe2671-6eca-3690-bef2-69e3f9b0e934, userId=d83d07b6-670a-425b-a9c2-c39dc276e9d9, ipoId=8b2d0000-0000-4000-8000-000000000001
2026-11-02T23:14:45.122+09:00  INFO 1 --- [subscription-service] [ntainer#2-0-C-1] c.g.s.i.m.SubscriptionRequestedConsumer  : 접수 처리 완료. result=REJECTED_ALREADY_SUBSCRIBED, requestId=d14025f5-f054-41e3-8b9e-4466f50d8240, subscriptionId=7dbe2671-6eca-3690-bef2-69e3f9b0e934
```

DB (이 회원의 청약, 계좌의 증거금 행 수)

```
 ipo |  status   |    reject_reason     
-----+-----------+----------------------
 01  | CONFIRMED | 
 02  | REJECTED  | INSUFFICIENT_BALANCE
 01  | REJECTED  | ALREADY_SUBSCRIBED
(3 rows)

 margin_rows 
-------------
           1
(1 row)
```

### requestId가 같을 때와 다를 때

이중청약을 확인하려면 requestId를 새로 만들어야 한다. 같은 requestId로 보내면 이중청약 검사 전에 멱등 처리에서 먼저 걸린다.

| requestId  | 걸리는 곳          | 결과                                             |
|------------|----------------|------------------------------------------------|
| 같음         | 멱등 (같은 요청 재전송) | `DUPLICATE_IGNORED`. 처음 결과 그대로이고 새 청약이 생기지 않는다 |
| 다름 + 같은 종목 | 이중청약           | 새 청약이 `REJECTED`, `ALREADY_SUBSCRIBED`         |

- requestId는 "버튼 한 번 누른 것"이다. 네트워크 문제로 다시 보내도 한 번만 처리하려고 사용한다
- 이중청약 기준은 사람(userId) + 종목(ipoId)이다. 계좌를 바꿔도 막힌다. 이전 청약이 `REJECTED`나 `CANCELLED`면 막지 않는다 (`schema.sql`의
  `WHERE status NOT IN ('REJECTED', 'CANCELLED')`)

Swagger로 직접 확인 (investor01, 공모주 A는 22:53에 이미 `CONFIRMED`)

- requestId `...021`: 새 요청 → 이중청약 거절. 같은 값으로 한 번 더 보내니 `DUPLICATE_IGNORED`
- requestId `...099`: 새 요청 → 이중청약 거절

```
2026-11-02T23:19:54.842+09:00  INFO 1 --- [subscription-service] [ntainer#2-1-C-1] c.g.s.a.s.SubscriptionAcceptService      : 이중청약으로 거절함. subscriptionId=1459f600-d6cd-342c-bd75-25118158cd80, userId=6706d866-e2bf-4af7-b852-3ad2f1b06ac6, ipoId=8b2d0000-0000-4000-8000-000000000001
2026-11-02T23:19:54.849+09:00  INFO 1 --- [subscription-service] [ntainer#2-1-C-1] c.g.s.i.m.SubscriptionRequestedConsumer  : 접수 처리 완료. result=REJECTED_ALREADY_SUBSCRIBED, requestId=3b8f0000-0000-4000-8000-000000000021, subscriptionId=1459f600-d6cd-342c-bd75-25118158cd80
2026-11-02T23:19:58.579+09:00  INFO 1 --- [subscription-service] [ntainer#2-1-C-1] c.g.s.a.s.SubscriptionAcceptService      : 같은 내용의 재전송이라 무시함. requestId=3b8f0000-0000-4000-8000-000000000021, subscriptionId=1459f600-d6cd-342c-bd75-25118158cd80
2026-11-02T23:19:58.580+09:00  INFO 1 --- [subscription-service] [ntainer#2-1-C-1] c.g.s.i.m.SubscriptionRequestedConsumer  : 접수 처리 완료. result=DUPLICATE_IGNORED, requestId=3b8f0000-0000-4000-8000-000000000021, subscriptionId=1459f600-d6cd-342c-bd75-25118158cd80
2026-11-02T23:27:31.560+09:00  INFO 1 --- [subscription-service] [ntainer#2-1-C-1] c.g.s.a.s.SubscriptionAcceptService      : 이중청약으로 거절함. subscriptionId=e9e7c609-5f77-3197-a4d1-bba259c52492, userId=6706d866-e2bf-4af7-b852-3ad2f1b06ac6, ipoId=8b2d0000-0000-4000-8000-000000000001
2026-11-02T23:27:31.563+09:00  INFO 1 --- [subscription-service] [ntainer#2-1-C-1] c.g.s.i.m.SubscriptionRequestedConsumer  : 접수 처리 완료. result=REJECTED_ALREADY_SUBSCRIBED, requestId=3b8f0000-0000-4000-8000-000000000099, subscriptionId=e9e7c609-5f77-3197-a4d1-bba259c52492
```
