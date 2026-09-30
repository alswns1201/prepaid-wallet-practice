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

## API

| 메서드 | 경로 | 설명 | 주요 에러 |
|---|---|---|---|
| POST | `/api/wallets` | 지갑 생성 `{"userId": 1}` → 201 | 409 `DUPLICATE_WALLET` |
| GET | `/api/wallets/{walletId}` | 지갑 조회 (잔액) | 404 `WALLET_NOT_FOUND` |
| GET | `/api/wallets/{walletId}/transactions` | 거래 내역 (최신순) — 충전·결제·취소마다 한 줄씩 쌓인 원장 | 404 `WALLET_NOT_FOUND` |
| POST | `/api/wallets/{walletId}/charge` | 충전 `{"amount": 10000}` → 생성된 거래 | 400 `INVALID_AMOUNT`, 404 |
| POST | `/api/wallets/{walletId}/pay` | 결제 `{"amount": 3000}` → 생성된 거래 | 400 `INVALID_AMOUNT`, 422 `INSUFFICIENT_BALANCE`, 404 |

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
