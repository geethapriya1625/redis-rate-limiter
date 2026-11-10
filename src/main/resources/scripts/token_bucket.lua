-- Token bucket, evaluated atomically inside Redis.
--
-- KEYS[1]  bucket key
-- ARGV[1]  capacity          (max tokens, also the burst size)
-- ARGV[2]  refill rate       (tokens per second, may be fractional)
-- ARGV[3]  requested tokens  (usually 1)
--
-- Returns { allowed (1|0), remaining tokens (floored), retry_after_ms, reset_ms }
--   retry_after_ms: wait before `requested` tokens will be available (0 if allowed)
--   reset_ms:       wait until the bucket is completely full again
--
-- The clock is Redis's own TIME, not the caller's, so app servers with skewed clocks
-- still share one consistent view of every bucket.

local key       = KEYS[1]
local capacity  = tonumber(ARGV[1])
local rate      = tonumber(ARGV[2])
local requested = tonumber(ARGV[3])

local t   = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

local state  = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts     = tonumber(state[2])
if tokens == nil or ts == nil then
  tokens = capacity
  ts = now
end

-- refill for the time elapsed since the last call (never above capacity)
local elapsed = math.max(0, now - ts)
tokens = math.min(capacity, tokens + elapsed * rate / 1000)

local allowed = 0
local retry_after = 0
if tokens >= requested then
  tokens = tokens - requested
  allowed = 1
else
  retry_after = math.ceil((requested - tokens) * 1000 / rate)
end

local reset = math.ceil((capacity - tokens) * 1000 / rate)

redis.call('HSET', key, 'tokens', tokens, 'ts', now)
-- an idle bucket refills completely after `reset` ms; keep it a little longer, then let it go
redis.call('PEXPIRE', key, reset + 1000)

return { allowed, math.floor(tokens), retry_after, reset }
