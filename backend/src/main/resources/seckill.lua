-- Atomic admission is distinct from the eventual MySQL outcome.
local stockKey,buyersKey,stream,resultKey,pauseKey=KEYS[1],KEYS[2],KEYS[3],KEYS[4],KEYS[5]
local function kind(k) return redis.call('TYPE',k).ok end
if redis.call('EXISTS',pauseKey)==1 then return 6 end
if kind(stockKey)~='string' or (kind(buyersKey)~='none' and kind(buyersKey)~='set')
 or (kind(stream)~='none' and kind(stream)~='stream') or kind(resultKey)~='none' then return 3 end
local stock=tonumber(redis.call('GET',stockKey))
if not stock or stock%1~=0 then return 3 end
local beginTime,endTime=tonumber(ARGV[4]),tonumber(ARGV[5])
if not beginTime or not endTime or beginTime>=endTime then return 3 end
if redis.call('SISMEMBER',buyersKey,ARGV[1])==1 then return 2 end
if stock<=0 then return 1 end
local clock=redis.call('TIME'); local now=tonumber(clock[1])*1000+math.floor(tonumber(clock[2])/1000)
if now<beginTime then return 4 end
if now>=endTime then return 5 end
local queued=redis.pcall('XADD',stream,'*','userId',ARGV[1],'voucherId',ARGV[2],'orderId',ARGV[3])
if type(queued)=='table' and queued.err then return 3 end
redis.call('DECR',stockKey); redis.call('SADD',buyersKey,ARGV[1])
redis.call('HSET',resultKey,'userId',ARGV[1],'voucherId',ARGV[2],'orderId',ARGV[3],'status','PENDING')
redis.call('EXPIRE',resultKey,604800)
return 0
