local k=redis.call('TYPE',KEYS[1]).ok
if k~='none' and k~='string' then return -1 end
local count=redis.call('INCR',KEYS[1]); redis.call('EXPIRE',KEYS[1],604800); return count
