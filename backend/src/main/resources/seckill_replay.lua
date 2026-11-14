-- A replay reuses the reservation and order ID; it cannot reopen a compensated failure.
if redis.call('HGET',KEYS[2],'status')~='REVIEW_REQUIRED' then return 0 end
if redis.call('HGET',KEYS[2],'userId')~=ARGV[1] or redis.call('HGET',KEYS[2],'voucherId')~=ARGV[2] then return -1 end
if redis.call('SISMEMBER',KEYS[3],ARGV[1])~=1 then return -2 end
local queued=redis.pcall('XADD',KEYS[1],'*','userId',ARGV[1],'voucherId',ARGV[2],'orderId',ARGV[3])
if type(queued)=='table' and queued.err then return -3 end
redis.call('HSET',KEYS[2],'status','PENDING','message','订单正在恢复处理中')
redis.call('EXPIRE',KEYS[2],604800); redis.call('SREM',KEYS[4],ARGV[3]); return 1
