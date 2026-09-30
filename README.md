# prepaid-wallet-practice

"선불 충전 → 결제 → 취소" 미니 API로 결제 동시성 제어를 단계별로 실습한다.

- 멱등성 키 (`Idempotency-Key`)
- 지갑 락 (Redis 분산 락)
- 일일 한도 (Redis Lua 스크립트)
- 동시 요청 100개 테스트 (`ExecutorService` + `CountDownLatch`)

커밋 하나가 한 단계다. `git log --reverse`로 순서대로 따라가면 된다.

## 스택

Java 21 · Spring Boot 3.3.4 · Spring Data JPA · H2 (인메모리, MySQL 모드) · Lombok

```bash
./gradlew test      # 테스트
./gradlew bootRun   # 서버 실행 (http://localhost:8080)
```

## 단계별 진행

### 1. 프로젝트 뼈대 (`ffec8e3`)

- Web, Validation, JPA, H2 의존성으로 시작한다.
- `spring.jpa.open-in-view: false` — 영속성 컨텍스트가 트랜잭션 안에서만 살아 있게 한다.
  뒤에서 "락은 `@Transactional` 바깥에서 잡는다"를 설명할 때 트랜잭션 경계가 흐려지지 않도록 처음부터 꺼 둔다.

### 2. 지갑 생성·조회 + 거래 원장 + 에러 응답 (`fa9237a`)

- `Wallet` — 사용자당 1개 (`userId` unique), 잔액 `balance`.
- `WalletTransaction` — 잔액이 바뀔 때마다 한 줄씩 쌓이는 원장.
  `type`(CHARGE/PAY/CANCEL), `amount`, 거래 직후 잔액 `balanceAfter`, 취소 시 원 거래를 가리키는 `originalTransactionId`.
- 에러는 `ErrorCode` enum + `WalletException` 하나로 모으고, `GlobalExceptionHandler`가
  RFC 9457 `ProblemDetail` 형식에 `code` 필드를 붙여 응답한다.

### 3. 충전 (`229aaff`)

- `Wallet.charge(amount)` — 0원 이하면 `INVALID_AMOUNT`(400), 아니면 잔액 증가.
- 한 트랜잭션 안에서 지갑 조회 → 충전 → CHARGE 거래 저장. 잔액은 JPA 변경 감지로 반영된다.
- 금액 검증은 요청 DTO(`@Positive`)가 아니라 도메인에서 한다.
  DTO에서 막으면 Spring 기본 400 응답이 나가서 에러 형식이 `ProblemDetail + code`로 통일되지 않기 때문.
- ⚠️ 아직 락이 없다. 동시에 충전하면 lost update가 난다 — 6단계에서 재현하고 8단계에서 고친다.

### 4. 결제 (`7159019`)

- `Wallet.pay(amount)` — 금액 검증은 충전과 같고, 잔액이 부족하면 `INSUFFICIENT_BALANCE`(422), 아니면 잔액 차감.
  잔액을 정확히 0원까지 쓰는 건 허용한다.
- 422를 쓴 이유: 요청 형식은 맞지만 현재 상태(잔액) 때문에 처리할 수 없다는 뜻이라, 입력 자체가 잘못된 400과 구분한다.
- 잔액 검사와 차감을 같은 도메인 메서드에 둔다. 8단계에서 락을 걸면 "검사 → 차감"이 한 번에 보호된다.
  (지금은 락이 없어서 동시에 결제하면 둘 다 잔액 검사를 통과할 수 있다.)
- 요청 DTO는 충전과 같은 `AmountRequest`를 쓴다.

### 5. 취소

- `POST /api/transactions/{transactionId}/cancel` — PAY 거래만 취소할 수 있고, 결제 금액만큼 환불한다.
- 원 결제 행은 건드리지 않고 **CANCEL 행을 새로 추가**한다. CANCEL 행의 `originalTransactionId`가 원 결제를 가리킨다.
- 이중 취소는 두 겹으로 막는다.
  1. 서비스에서 `existsByOriginalTransactionId`로 확인 → 평소에는 여기서 409 `ALREADY_CANCELED`.
  2. 동시에 두 요청이 1번을 같이 통과해도, `originalTransactionId`의 **unique 제약**이 두 번째 INSERT를 거절한다.
     `saveAndFlush`로 그 자리에서 제약 위반을 잡아 409로 바꾸고, 예외로 트랜잭션이 롤백되니 환불도 반영되지 않는다.
- 충전·취소 거래를 취소하려 하면 422 `NOT_CANCELABLE` (요청 형식은 맞지만 이 거래에는 할 수 없는 동작이라 422).
- 2단계에서 만든 `status` 칼럼(`COMPLETED`/`CANCELED`)과 `TransactionStatus` enum은 이 단계에서 뺐다. 이유는 아래.

#### 고민: 취소를 어떻게 기록할까 — A vs B

| | A. 원 결제 행의 상태를 바꾼다 | B. 원장은 추가만 한다 (선택) |
|---|---|---|
| 취소 기록 | PAY 행 `status`: `COMPLETED → CANCELED` + CANCEL 행 | CANCEL 행만 추가, PAY 행은 그대로 |
| 이중 취소 방지 | 상태를 읽고 → 확인하고 → 바꾸는 사이에 끼어들 수 있어서 **락이 필요** (8단계 락 안에서 상태 재확인) | `originalTransactionId` unique 제약 — **락 없이도 DB가 막는다** |
| 원장 성격 | 과거 행이 나중에 바뀜 | 한 번 쌓인 행은 안 바뀜 (append-only) |
| "이 결제 취소됐나?" | PAY 행 `status`만 보면 됨 | CANCEL 행이 있는지 봐야 함 (`existsByOriginalTransactionId`) |

**B를 고른 이유**

- **정합성을 락에만 기대지 않는다.** A는 락이 빠지거나 락 밖의 경로(배치, 관리자 도구 등)에서 취소하면 이중 환불이 난다.
  B는 DB 제약이 최후 방어선이라, 락은 성능·순서 문제만 맡고 "두 번 환불"은 구조적으로 불가능하다.
- **돈의 기록은 고치지 않고 쌓는다.** 회계 원장처럼 과거 행을 수정하지 않으면, 내역만으로 잔액이 어떻게 변해 왔는지 그대로 재현된다.
  (`balanceAfter`도 그 시점의 값으로 남는다.)
- **단점은 조회가 한 단계 늘어나는 것.** "취소됐나?"를 알려면 CANCEL 행을 찾아야 한다.
  지금 규모에서는 unique 제약이 곧 인덱스라 비용이 거의 없다.
- 2단계에서 미리 만든 `status` 칼럼은 B에서는 쓸 곳이 없어서 뺐다. 이미 push한 2단계 커밋은 고치지 않고, 이 단계의 변경으로 남긴다.

### 6. 동시성 문제 재현 — 락 없이 동시 충전 100건

API는 그대로 동기다. 바뀌는 건 테스트가 **같은 지갑에 요청 100개를 같은 순간에** 보낸다는 것.
(비동기 처리가 아니라, 동기 요청이 동시에 몰리는 상황 — 연타, 여러 서버가 같은 지갑을 처리하는 경우)

- `ConcurrentRunner` (테스트 도구) — `ExecutorService`로 스레드 100개를 띄우고 `CountDownLatch` 3개로 출발을 맞춘다.
  - `ready`: 100개가 모두 출발선에 설 때까지 기다림 / `start`: 한 번에 출발 신호 / `done`: 전부 끝날 때까지 기다림
  - 래치 없이 스레드가 만들어지는 대로 출발하면 사실상 순서대로 실행돼서 경쟁이 잘 안 생긴다.
  - 성공 건수, 실패 사유별 건수(`ErrorCode` 이름), 걸린 시간을 돌려준다.
- `WalletConcurrencyTest` — 테스트에 `@Transactional`을 붙이지 않는다. 요청마다 자기 트랜잭션으로 커밋돼야 실제 상황과 같다.

**결과 (로컬 실행 예)**

```
>>> 성공 100건 → 기대 잔액 100,000원 / 실제 잔액 7,000원
>>> CHARGE 행 100줄, balanceAfter 서로 다른 값 11개
```

- 요청 100건 모두 "성공"했고 원장에도 CHARGE가 100줄 쌓였는데, 잔액은 실행할 때마다 달라지고 수천~수만 원만 남는다 (여러 번 돌려 본 결과 7,000~39,000원).
- **lost update**: 두 트랜잭션이 같은 잔액(예: 0원)을 읽고 각자 +1,000 해서 저장하면, 뒤에 저장한 쪽이 앞의 충전을 덮어쓴다.

  ```
  A: 잔액 읽음 (0)      B: 잔액 읽음 (0)
  A: 1,000 저장         B: 1,000 저장   ← A의 충전이 사라짐
  ```
- 원장의 `balanceAfter`가 100개 중 10~40개 값으로만 찍힌다 → 여러 요청이 같은 잔액을 동시에 읽었다는 흔적.
- 테스트는 "깨진다"를 검증한다: `잔액 < 성공 건수 × 1,000원`. 8단계에서 락을 넣고 정확히 100,000원을 검증하도록 바꾼다.
- 참고: 처음 실행했을 때 한 번 `DataIntegrityViolationException` 2건이 섞여 나왔고, 이후 30번 넘게 다시 돌려도 재현되지 않았다.
  H2가 동시 갱신 충돌을 이렇게 보고한 것으로 추정한다. 검증은 성공 건수를 기준으로 하므로 결과에는 영향이 없다.

## API

| 메서드 | 경로 | 설명 | 주요 에러 |
|---|---|---|---|
| POST | `/api/wallets` | 지갑 생성 `{"userId": 1}` → 201 | 409 `DUPLICATE_WALLET` |
| GET | `/api/wallets/{walletId}` | 지갑 조회 (잔액) | 404 `WALLET_NOT_FOUND` |
| GET | `/api/wallets/{walletId}/transactions` | 거래 내역 (최신순) — 충전·결제·취소마다 한 줄씩 쌓인 원장 | 404 `WALLET_NOT_FOUND` |
| POST | `/api/wallets/{walletId}/charge` | 충전 `{"amount": 10000}` → 생성된 거래 | 400 `INVALID_AMOUNT`, 404 |
| POST | `/api/wallets/{walletId}/pay` | 결제 `{"amount": 3000}` → 생성된 거래 | 400 `INVALID_AMOUNT`, 422 `INSUFFICIENT_BALANCE`, 404 |
| POST | `/api/transactions/{transactionId}/cancel` | 결제 취소 → 생성된 CANCEL 거래 | 404 `TRANSACTION_NOT_FOUND`, 409 `ALREADY_CANCELED`, 422 `NOT_CANCELABLE` |

에러 응답 예시:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "금액은 0보다 커야 합니다.",
  "instance": "/api/wallets/1/charge",
  "code": "INVALID_AMOUNT"
}
```
