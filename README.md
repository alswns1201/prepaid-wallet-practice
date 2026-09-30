# prepaid-wallet-practice

"선불 충전 → 결제 → 취소" 미니 API로 결제 동시성 제어를 단계별로 실습한다.

- 멱등성 키 (`Idempotency-Key`)
- 지갑 락 (Redis 분산 락)
- 일일 한도 (Redis Lua 스크립트)
- 동시 요청 100개 테스트 (`ExecutorService` + `CountDownLatch`)

커밋 하나가 한 단계다. `git log --reverse`로 순서대로 따라가면 된다.
