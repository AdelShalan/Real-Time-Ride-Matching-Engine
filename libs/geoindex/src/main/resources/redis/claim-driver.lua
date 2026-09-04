-- Atomically claim a driver for a ride offer (ADR-0004, layer 1).
--
-- Redis executes a script on its single command thread with nothing interleaved, so the
-- check and the set below cannot be split by another client. That is the entire reason this
-- is a script rather than three round trips: between a GET and a SET, a competing worker
-- would fit comfortably.
--
-- This is a CONTENTION OPTIMISER, not the correctness boundary. The guarantee that a driver
-- holds at most one active trip is the uniq_driver_active_trip index in PostgreSQL. If this
-- script is bypassed, lost, or its key space flushed, correctness still holds — only
-- throughput suffers, because losers would discover the conflict at the database instead of
-- one Redis round trip earlier.
--
-- KEYS[1] driver state hash        KEYS[2] claim marker        KEYS[3] fence counter
-- ARGV[1] offer token (opaque)     ARGV[2] claim TTL in millis
--
-- Returns { 1, fence }        on success
--         { 0, reason }       on refusal

local status = redis.call('HGET', KEYS[1], 'status')

-- An unknown driver is refused rather than claimed. A driver absent from the index has
-- either gone offline or was evicted as stale; claiming them would dispatch an offer nobody
-- receives and burn the whole offer TTL before the rider gets another candidate.
if not status then
  return { 0, 'UNKNOWN_DRIVER' }
end

if status ~= 'AVAILABLE' then
  return { 0, 'NOT_AVAILABLE' }
end

-- A live claim marker means another worker won a moment ago and its offer is outstanding.
if redis.call('EXISTS', KEYS[2]) == 1 then
  return { 0, 'ALREADY_CLAIMED' }
end

local fence = redis.call('INCR', KEYS[3])

-- The TTL is the safety valve: a worker that dies between claiming and dispatching must not
-- strand the driver forever. The claim simply expires and the driver becomes available again.
redis.call('SET', KEYS[2], ARGV[1], 'PX', tonumber(ARGV[2]))
redis.call('HSET', KEYS[1], 'status', 'RESERVED', 'fence', fence)

return { 1, fence }
