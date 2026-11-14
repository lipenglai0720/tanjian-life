local function kind(k) return redis.call('TYPE',k).ok end
for i,t in ipairs({'stream','stream','hash','set','string'}) do
 local k=kind(KEYS[i]); if k~='none' and k~=t then return -1 end
end
local previous=redis.call('HGET',KEYS[3],'status')
if previous=='CREATED' or previous=='FAILED' or (previous=='REVIEW_REQUIRED' and redis.call('HGET',KEYS[3],'sourceId')==ARGV[1]) then
 redis.call('XACK',KEYS[1],ARGV[2],ARGV[1]); return 0
end
local added=redis.pcall('XADD',KEYS[2],'*','sourceId',ARGV[1],'orderId',ARGV[5],
 'userId',ARGV[3],'voucherId',ARGV[4],'attempts',ARGV[6],'reason',ARGV[7],'payload',ARGV[8])
if type(added)=='table' and added.err then return -1 end
if ARGV[9]=='1' then
 redis.call('HSET',KEYS[3],'orderId',ARGV[5],'userId',ARGV[3],'voucherId',ARGV[4],
 'status','REVIEW_REQUIRED','message','订单结果正在核对，请稍后查看','sourceId',ARGV[1])
 redis.call('EXPIRE',KEYS[3],604800); redis.call('SADD',KEYS[4],ARGV[5])
end
redis.call('XACK',KEYS[1],ARGV[2],ARGV[1]); return 1
