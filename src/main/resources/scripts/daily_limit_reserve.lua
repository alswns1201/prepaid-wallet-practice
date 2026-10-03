-- 일일 결제 한도 차지: GET → 비교 → INCRBY를 Redis 안에서 한 번에 실행한다.
-- Redis는 스크립트 하나가 끝날 때까지 다른 명령을 처리하지 않으므로, 중간에 다른 요청이 끼어들 수 없다.
--
-- KEYS[1] : wallet:daily:{walletId}:{yyyyMMdd}
-- ARGV[1] : 이번 결제 금액
-- ARGV[2] : 일일 한도
-- ARGV[3] : 키 TTL(초)
-- return  : 더한 뒤 누적 금액, 한도를 넘으면 -1 (이때는 아무것도 쓰지 않는다)
local used   = tonumber(redis.call('GET', KEYS[1]) or '0')
local amount = tonumber(ARGV[1])
local limit  = tonumber(ARGV[2])

if used + amount > limit then
	return -1
end

-- Lua는 중간에 에러가 나도 앞의 명령을 롤백하지 않는다. 그래서 검사를 다 끝낸 뒤 마지막에 쓴다.
local total = redis.call('INCRBY', KEYS[1], amount)
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
return total
