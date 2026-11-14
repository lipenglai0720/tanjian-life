-- Validate key types before changing inventory. Completion and ACK are one Redis operation.
local function kind(k) return redis.call('TYPE',k).ok end
for i,t in ipairs({'string','set','hash','stream','set','string','string'}) do
 local k=kind(KEYS[i]); if k~='none' and k~=t then return -1 end
end
local previous=redis.call('HGET',KEYS[3],'status')
if previous~='CREATED' and previous~='FAILED' then
 local outcome=ARGV[4]
 if outcome~='CREATED' and outcome~='DUPLICATE' and outcome~='SOLD_OUT' then return -2 end
 if outcome~='CREATED' then
  local stock=tonumber(redis.call('GET',KEYS[1]))
  if stock and stock%1==0 then redis.call('INCR',KEYS[1]) else redis.call('SET',KEYS[6],'1') end
  if outcome=='SOLD_OUT' then
   redis.call('SREM',KEYS[2],ARGV[1]); redis.call('SET',KEYS[6],'1')
  end
 end
 local status='FAILED'; if outcome=='CREATED' then status='CREATED' end
 redis.call('HSET',KEYS[3],'userId',ARGV[1],'voucherId',ARGV[2],'orderId',ARGV[3],
  'status',status,'message',ARGV[5],'existingOrderId',ARGV[6])
 redis.call('EXPIRE',KEYS[3],604800)
 redis.call('SREM',KEYS[5],ARGV[3]); redis.call('DEL',KEYS[7])
end
if ARGV[8]~='' then redis.call('XACK',KEYS[4],ARGV[7],ARGV[8]) end
return 1
