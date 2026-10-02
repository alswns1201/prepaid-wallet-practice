# prepaid-wallet-practice

"선불 충전 → 결제 → 취소" 미니 API로 결제 동시성 제어를 단계별로 실습한다.

- 멱등성 키 (`Idempotency-Key`)
- 지갑 락 (Redis 분산 락)
- 일일 한도 (Redis Lua 스크립트)
- 동시 요청 100개 테스트 (`ExecutorService` + `CountDownLatch`)

커밋 하나가 한 단계다. `git log --reverse`로 순서대로 따라가면 된다.

## 스택

Java 21 · Spring Boot 3.3.4 · Spring Data JPA · H2 (인메모리, MySQL 모드) · Lombok · Redis (Spring Data Redis, Redisson) · Testcontainers

```bash
./gradlew test            # 테스트 — Docker가 켜져 있어야 한다 (Testcontainers가 Redis를 띄움)
docker compose up -d      # 로컬 Redis (bootRun 할 때만 필요)
./gradlew bootRun         # 서버 실행 (http://localhost:8080)
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
- 테스트는 "깨진다"를 검증한다: `잔액 < 성공 건수 × 1,000원`. 8단계에서 락을 거치는 버전을 옆에 추가해 나란히 비교한다 (이 테스트는 대조군으로 남긴다).
- 참고: 처음 실행했을 때 한 번 `DataIntegrityViolationException` 2건이 섞여 나왔고, 이후 30번 넘게 다시 돌려도 재현되지 않았다.
  H2가 동시 갱신 충돌을 이렇게 보고한 것으로 추정한다. 검증은 성공 건수를 기준으로 하므로 결과에는 영향이 없다.

### 7. Redis 설정

6단계의 lost update를 풀 도구(지갑 락, 일일 한도, 멱등성 키)가 전부 Redis 위에서 돌아가서, 먼저 Redis를 붙인다. 이 단계에서는 연결만 한다.

- 의존성
  - `spring-boot-starter-data-redis` — `StringRedisTemplate`(Lettuce). 9~11단계의 일일 한도·Lua·멱등성 키에서 쓴다.
  - `redisson-spring-boot-starter` — 8단계 지갑 락(`RLock`)에서 쓴다. 락 대기, 자동 연장(watchdog)을 직접 구현하지 않으려고.
- `docker-compose.yml` — 로컬 `bootRun`용 Redis (`redis:7-alpine`, 6379).
- 테스트는 **Testcontainers**로 진짜 Redis를 띄운다 (`IntegrationTestSupport`).
  - 모든 테스트 클래스가 컨테이너 하나를 공유한다 (싱글턴 컨테이너 패턴: `static` 블록에서 한 번 `start()`).
  - 컨테이너가 빈 포트에 뜨므로 `@DynamicPropertySource`로 실제 host/port를 넣어 준다.
  - Redisson은 시작할 때 바로 Redis에 접속한다. 그래서 이제 `@SpringBootTest` 테스트는 전부 이 부모를 상속한다.
    `@DataJpaTest`(제약 테스트)는 JPA 빈만 띄워서 상관없다.
- `RedisConnectionTest` — 두 클라이언트(`StringRedisTemplate`, `RedissonClient`)가 모두 붙는지 쓰기/읽기로 확인.
  기존 `contextLoads` 테스트는 이 테스트가 역할을 대신해서 지웠다.

**삽질: Docker Desktop 29 + Testcontainers**

Boot 3.3.4가 관리하는 Testcontainers(1.19.x)는 Docker Desktop 29에서 `Status 400`으로 Docker를 못 찾는다.
Docker Desktop 29는 Docker API 1.44 이상만 받는데, 예전 docker-java는 더 낮은 버전으로 요청하기 때문이다. `build.gradle`에서 두 가지로 해결했다.

```groovy
ext['testcontainers.version'] = '1.21.3'                 // Testcontainers 올리기
tasks.named('test') { systemProperty 'api.version', '1.44' }  // docker-java API 버전 고정
```

### 8. 지갑 락 — lost update 해결

6단계에서 본 문제는 **같은 지갑의 잔액을 여러 요청이 동시에 읽고 고치는 것**이었다.
해결은 간단히 말하면 "같은 지갑에 대한 요청은 한 줄로 세워 하나씩 처리한다"이고, 그 줄을 세우는 도구가 락이다.

#### 락이란 — Redis 키 하나로 "지금 누가 쓰는 중" 표시

```
요청 A: Redis에 "wallet:lock:1" 키 만들기 시도 → 성공 (A가 락 주인)
요청 B: "wallet:lock:1" 만들기 시도 → 이미 있음 → 기다림
요청 A: 충전 처리 → 커밋 → 키 삭제 (락 해제)
요청 B: 이제 성공 → A가 커밋한 잔액을 읽고 처리
```

지갑이 다르면 키도 다르다(`wallet:lock:1`, `wallet:lock:2`). 다른 지갑끼리는 서로 기다리지 않는다.

#### 왜 Redis인가

| 방법 | 문제 |
|---|---|
| 자바 `synchronized` / `ReentrantLock` | 서버(JVM) 한 대 안에서만 통한다. 서버가 2대면 각자 따로 잠가서 소용없다. |
| DB 비관적 락 (`SELECT ... FOR UPDATE`) | 실무에서도 많이 쓰는 방법. 다만 기다리는 동안 DB 커넥션을 붙잡고 있어서, 요청이 몰리면 커넥션 풀과 DB가 먼저 버거워진다. |
| **Redis 분산 락** | 서버가 몇 대든 같은 Redis를 보므로 한 곳에서 줄을 세운다. 기다리는 동안 DB 커넥션을 쓰지 않는다. |

서버 여러 대에 걸쳐 통하는 락이라 **분산 락**이라고 부른다.

#### 왜 Redisson인가

`SET key value NX PX 3000`으로 직접 만들 수도 있지만, 직접 챙겨야 할 게 많다. Redisson(`RLock`)이 대신 해 준다.

- **남의 락을 풀면 안 된다** → 락 값에 주인(스레드) ID를 넣고 주인만 풀 수 있다 (`isHeldByCurrentThread`).
- **서버가 죽으면 락이 영원히 남는다** → 만료 시간이 있다. 대신 처리가 길어지면 **watchdog**이 살아 있는 동안 자동 연장한다
  (`leaseTime`을 안 주면 watchdog 사용, 기본 30초 단위).
- **기다리는 방식** → "풀렸나?"를 계속 묻지 않고, 락이 풀리면 Redis pub/sub 알림을 받아 깨어난다.

`WalletLockManager`는 이걸 감싼 얇은 클래스다.

```java
lockManager.executeWithLock(walletId, () -> walletService.charge(walletId, amount));
// tryLock(최대 5초 대기) → 못 잡으면 503 LOCK_TIMEOUT → 잡으면 실행 → finally에서 내가 잡은 락만 unlock
```

#### 핵심: 락은 트랜잭션 **바깥**에서 잡는다

락을 넣는 것보다 **어디서 잡느냐**가 더 중요하다.

```
❌ 트랜잭션 안에서 락:   트랜잭션 시작 → 락 획득 → 충전 → 락 해제 → 커밋
                                                          ↑ 이 틈에 B가 락을 잡고
                                                            아직 커밋 안 된 = 옛 잔액을 읽는다
✅ 락이 트랜잭션을 감싼다: 락 획득 → 트랜잭션 시작 → 충전 → 커밋 → 락 해제
```

JPA는 변경을 **커밋 직전에** flush 하기 때문에, ❌에서는 잔액 UPDATE 자체가 락을 푼 뒤에 DB로 나간다.

그런데 락과 트랜잭션을 `WalletService` 한 클래스 안에서 처리하려고 하면 두 방법 모두 안 된다.

1. **`@Transactional` 메서드 안에서 락을 잡는다** → 위의 ❌ 순서가 된다. 커밋은 메서드가 끝난 뒤 프록시가 하기 때문.
2. **락 메서드가 같은 클래스의 `@Transactional` 메서드를 `this.charge()`로 부른다** → 순서는 맞아 보이지만
   **트랜잭션이 아예 안 열린다.** `@Transactional`은 프록시가 바깥에서 들어오는 호출을 가로채서 동작하는데,
   자기 자신 호출은 프록시를 거치지 않는다. (private 메서드에 `@Transactional`이 안 먹는 것과 같은 원리)

그래서 **락을 잡는 빈과 트랜잭션을 여는 빈을 나눈다.**

```
Controller ─ 충전·결제·취소 ─▶ WalletFacade (락)
                                  락 획득
                                    └─▶ WalletService (@Transactional, 다른 빈이라 프록시 경유)
                                          트랜잭션 시작 → 처리 → 커밋
                                  락 해제
Controller ─ 생성·조회 ──────▶ WalletService (락 불필요)
```

| 클래스 | 역할 |
|---|---|
| `WalletLockManager` (새로 만듦) | Redisson으로 지갑별 락 잡기/풀기 |
| `WalletFacade` (새로 만듦) | 락 → `WalletService` 호출 → 락 해제. 충전·결제·취소의 입구 |
| `WalletService` (그대로) | 실제 처리. `@Transactional`. 코드는 그대로이고 "변경은 Facade를 거쳐 부를 것" 주석만 추가 |
| `WalletController` | 충전·결제·취소는 Facade로, 생성·조회는 `WalletService`로 |

- 취소는 거래 ID로 들어오므로, Facade가 먼저 `findWalletIdOf(transactionId)`로 어느 지갑을 잠글지 찾는다.
  거래의 지갑은 바뀌지 않으니 락 밖에서 읽어도 된다.
- 고민: `WalletService`를 "락이 필요한 것(충전·결제·취소)"과 "필요 없는 것(생성·조회)" 두 클래스로 나누면
  규칙이 클래스 이름으로 드러나서 더 깔끔하다. 다만 이름 변경이 diff를 덮어 핵심이 묻혀서, 이 실습은 **클래스 하나를 유지**했다.
  대신 `WalletService.charge()`는 public이라 락 없이 직접 부를 수 있다 — 테스트는 이걸 일부러 대조군으로 쓴다.
  실무에서는 팀 규칙이나 패키지 구조로 "변경은 Facade로만" 들어오게 막는다.

#### 테스트 — 네 가지를 나란히

`WalletConcurrencyTest` (요청 100건 동시, 로컬 실행 예)

| 테스트 | 호출 경로 | 결과 |
|---|---|---|
| 락 없음 (6단계 대조군) | `WalletService` 직접 | 100건 성공, 잔액 **13,000원**, `balanceAfter` 서로 다른 값 13개 |
| **지갑 락** | `WalletFacade` | 100건 성공, 잔액 **정확히 100,000원**, `balanceAfter` **100개 전부 다름** |
| **지갑 락 + 결제** | `WalletFacade` | 잔액 20,000원에 1,000원 결제 100건 → **정확히 20건 성공**, 80건 `INSUFFICIENT_BALANCE`, 잔액 0원 |
| 잘못된 락 위치 | `@Transactional` 안에서 락 | 100건 성공, 잔액 **14,000원** — 락을 넣었는데도 깨진다 |

- 결제 테스트: 잔액 검사와 차감이 락 안에서 한 번에 일어나므로 20건을 넘겨 결제되지(초과 결제) 않는다.
- 락을 거치면 100건이 한 줄로 처리돼서 조금 느려진다 (100건에 300~500ms). 정확성과 맞바꾸는 비용이다.
- **잘못된 락 위치 테스트에 대해 솔직하게:** 락 해제와 커밋 사이의 틈은 아주 짧다. 처음엔 그대로 돌렸더니 5번 중 2번은 멀쩡했고,
  깨져도 1,000~2,000원 차이였다. 운영에서는 GC 멈춤, 느린 커밋, 락 뒤의 후처리 코드 때문에 이 틈이 벌어진다.
  테스트에서는 **락 해제 후 커밋 전에 후처리 10ms**(`Thread.sleep`)를 넣어 그 상황을 만들었고, 그러면 매번 크게 깨진다.
  즉 "대부분 괜찮아 보이지만 가끔 돈이 사라지는" 버그라서 더 위험하다.

### 9. 일일 결제 한도 — 락 안에서 GET → 비교 → INCRBY

지갑마다 **하루 결제 합계가 1,000,000원**(`wallet.daily-pay-limit`)을 넘지 못하게 한다.
이 단계는 일부러 **원자적이지 않은 방식**(GET → 비교 → INCRBY 세 번의 명령)으로 만든다.
8단계의 지갑 락 안에서만 부르니 지금은 안전하다. 10단계에서 이걸 락 밖에서 부르면 뚫리는 것을 보이고 Lua로 바꾼다.

#### 한도를 Redis 키 하나에 누적한다

```
wallet:daily:{walletId}:{yyyyMMdd}   예) wallet:daily:1:20261002 = "600000"
```

- 날짜가 키에 들어 있어서 **자정이 지나면 새 키**를 쓴다. "한도 초기화" 배치가 필요 없다.
- 지난 키는 TTL(2일)로 알아서 사라진다. 하루가 아니라 2일인 이유는 자정 직전 결제를 다음 날 취소할 때 키가 남아 있어야 해서.
- 하루를 자르는 기준은 **KST**. 서버 타임존에 따라 날짜가 달라지지 않도록 `Clock` 빈(`ClockConfig`, `Asia/Seoul`)에서 "오늘"을 받는다.
  `LocalDate.now()`를 직접 부르지 않고 빈으로 둔 건 테스트에서 날짜를 바꿔 끼우기 위해서다.

`DailyLimitManager.reserve()`

```java
long used = used(walletId, date);                     // 1. GET
if (used + amount > dailyPayLimit) {                  // 2. 비교 (자바에서)
    throw new WalletException(DAILY_LIMIT_EXCEEDED);  //    → 422
}
redisTemplate.opsForValue().increment(key, amount);   // 3. INCRBY
```

1~3 사이에 다른 요청이 끼어들 수 있다. 두 요청이 동시에 GET 하면 둘 다 "아직 여유 있음"을 보고 둘 다 INCRBY 한다 —
6단계 lost update와 같은 모양의 문제다. 지금은 **지갑 락 안에서만** 부르니 같은 지갑 요청은 한 번에 하나씩이라 끼어들 틈이 없다.

#### 결제 흐름 — 한도는 트랜잭션 밖에서, 보상은 직접

```
WalletFacade.pay
 └ 락 획득
    ├ DailyLimitManager.reserve    한도 차지 (Redis INCRBY)
    ├ WalletService.pay            결제 트랜잭션 (DB)
    │   └ 실패(잔액 부족 등) → DailyLimitManager.release (DECRBY)로 되돌림 → 예외 그대로 던짐
 └ 락 해제
```

- **Redis는 DB 트랜잭션에 묶이지 않는다.** 결제가 잔액 부족으로 실패하면 DB는 롤백되지만 Redis에 더한 금액은 그대로 남는다.
  그대로 두면 "결제는 안 됐는데 한도만 줄어든" 상태가 된다 → `catch`에서 `release`로 되돌린다 (**보상**).
- 고민: 보상을 `WalletService`(트랜잭션 안)에 둘 수도 있었다. 하지만 트랜잭션 안에서는 커밋이 실패하는 경우를 잡을 수 없고,
  "DB 트랜잭션에 안 묶이는 것은 트랜잭션 밖에서 다룬다"가 더 분명해서 **Facade**에 뒀다. `WalletService.pay`는 `businessDate` 인자만 늘었다.
- 한도 확인을 결제보다 **먼저** 하는 이유: 결제를 먼저 하고 한도를 나중에 보면, 초과일 때 이미 커밋된 결제를 되돌려야 한다.
  한도는 Redis 값 하나만 되돌리면 되니 "먼저 차지하고, 실패하면 돌려준다"가 훨씬 단순하다.

#### 취소 — 원래 결제한 날의 한도를 돌려준다 (`businessDate`)

결제를 취소하면 그 금액만큼 한도가 돌아와야 한다. 문제는 **어느 날의 한도**냐다.

```
10/1 23:50  300,000원 결제   → wallet:daily:1:20261001 = 300,000
10/2 09:00  200,000원 결제   → wallet:daily:1:20261002 = 200,000
10/2 09:10  10/1 결제 취소   → 20261001 키에서 빼야 한다. 오늘(20261002) 키에서 빼면 오늘 한도가 공짜로 늘어난다
```

그래서 `WalletTransaction`에 **`businessDate`**(일일 한도를 센 날짜) 칼럼을 추가했다.

| 거래 | `businessDate` |
|---|---|
| PAY | 결제한 날 — 그날의 한도 키에 더해졌다 |
| CANCEL | 원 결제의 날짜를 **그대로 복사** — 그날의 한도를 돌려줬다는 뜻 |
| CHARGE | `null` (한도와 상관없음) |

- `createdAt`에서 날짜를 뽑지 않은 이유: `createdAt`은 서버 시계(`LocalDateTime.now()`)라 테스트에서 날짜를 고정할 수 없고 서버 타임존을 탄다.
  "한도를 어느 날로 셌는가"는 비즈니스 규칙이라 따로 기록하는 게 맞다고 봤다.
- 응답(`TransactionResponse`)에도 `businessDate`가 나간다.
- 취소 흐름: 락 → `WalletService.cancel` (커밋) → `release(walletId, 금액, 취소 거래의 businessDate)`.
- 고민: 취소가 커밋된 **뒤에** 한도 반환이 실패하면? 예외를 던지면 사용자는 "취소 실패"로 보지만 실제로는 환불된 상태가 된다.
  한도 반환이 빠지는 쪽(그날 한도를 조금 덜 쓰게 됨)이 덜 위험하다고 보고, **로그만 남기고 성공으로 응답**한다.

#### 테스트

모든 `@SpringBootTest`가 Redis 컨테이너 하나를 같이 쓰고, 지갑 ID는 컨텍스트마다 1부터 다시 시작한다.
그래서 다른 테스트가 남긴 `wallet:daily:1:...` 키를 이어받지 않도록 `IntegrationTestSupport`에 두 가지를 추가했다.

- `@BeforeEach`에서 Redis `FLUSHALL` + 시계 초기화
- `TestClock` — `setDate(날짜)`로 "오늘"을 바꿀 수 있는 시계. `@Primary` 빈으로 운영 `Clock` 대신 주입된다.
  "어제 결제 → 오늘 취소"를 하루 기다리지 않고 만든다.

`DailyLimitTest` (한도 1,000,000원)

| 테스트 | 확인하는 것 |
|---|---|
| 한도 초과 | 600,000 + 400,000원(정확히 한도)까지 성공, 1원 더 → 422 `DAILY_LIMIT_EXCEEDED`, 잔액 그대로 |
| 결제 실패 보상 | 잔액 1,000원에 5,000원 결제 → `INSUFFICIENT_BALANCE`, 한도 사용액 **0원** (보상이 없으면 5,000원이 남는다) |
| 취소하면 한도 반환 | 한도를 꽉 채운 뒤 취소 → 사용액 0원, 다시 1,000,000원 결제 가능 |
| 어제 결제를 오늘 취소 | 어제 300,000 / 오늘 200,000 결제 후 어제 것 취소 → 어제 사용액 0원, **오늘은 200,000원 그대로** |
| 날짜가 바뀌면 새 한도 | 어제 한도를 다 써도 오늘은 결제 가능 |
| **동시 결제 (락 안)** | 20,000원 결제 100건 동시 → **정확히 50건 성공**, 50건 `DAILY_LIMIT_EXCEEDED`, 사용액 정확히 1,000,000원 |

```
>>> [일일 한도, 락 안] 성공 50건 / 한도 초과 50건 → 사용액 1,000,000원 (한도 1,000,000원)
```

- 동시 결제 테스트는 5번 반복해서 매번 같은 결과였다. 잔액(3,000,000원)은 충분하니 실패는 전부 한도 때문이다.
- `WalletApiTest`에는 API 응답 확인용으로 한 개 추가: 한도 초과 422 응답의 `code`와, 결제 응답의 `businessDate`.
- 이 결과는 **락 덕분**이다. 10단계에서 같은 테스트를 락 없이 `reserve`만 동시에 부르면 50건을 넘겨 통과하는 것을 보인다.

## API

| 메서드 | 경로 | 설명 | 주요 에러 |
|---|---|---|---|
| POST | `/api/wallets` | 지갑 생성 `{"userId": 1}` → 201 | 409 `DUPLICATE_WALLET` |
| GET | `/api/wallets/{walletId}` | 지갑 조회 (잔액) | 404 `WALLET_NOT_FOUND` |
| GET | `/api/wallets/{walletId}/transactions` | 거래 내역 (최신순) — 충전·결제·취소마다 한 줄씩 쌓인 원장 | 404 `WALLET_NOT_FOUND` |
| POST | `/api/wallets/{walletId}/charge` | 충전 `{"amount": 10000}` → 생성된 거래 | 400 `INVALID_AMOUNT`, 404, 503 `LOCK_TIMEOUT` |
| POST | `/api/wallets/{walletId}/pay` | 결제 `{"amount": 3000}` → 생성된 거래 (`businessDate` = 결제한 날). 하루 합계 1,000,000원까지 | 400 `INVALID_AMOUNT`, 422 `INSUFFICIENT_BALANCE`, 422 `DAILY_LIMIT_EXCEEDED`, 404, 503 `LOCK_TIMEOUT` |
| POST | `/api/transactions/{transactionId}/cancel` | 결제 취소 → 생성된 CANCEL 거래. 원 결제한 날의 일일 한도를 돌려준다 | 404 `TRANSACTION_NOT_FOUND`, 409 `ALREADY_CANCELED`, 422 `NOT_CANCELABLE`, 503 `LOCK_TIMEOUT` |

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
