local pending=redis.call('XPENDING',KEYS[1],ARGV[1])
if pending[1]~=0 then return 0 end
local groups=redis.call('XINFO','GROUPS',KEYS[1]); local last=nil
for _,g in ipairs(groups) do
 local name,id=nil,nil
 for i=1,#g,2 do if g[i]=='name' then name=g[i+1] elseif g[i]=='last-delivered-id' then id=g[i+1] end end
 if name==ARGV[1] then last=id end
end
if not last then return 0 end
local unread=redis.call('XREAD','COUNT',1,'STREAMS',KEYS[1],last)
if unread then return 0 end

-- Caller has paused this voucher before reading MySQL, and holds the init lock.
if redis.call('GET',KEYS[5])~='1' or redis.call('SCARD',KEYS[4])~=0 then return 0 end
local stock=tonumber(ARGV[2]); if not stock or stock<0 or stock%1~=0 then return -1 end
redis.call('DEL',KEYS[3]); for i=3,#ARGV do redis.call('SADD',KEYS[3],ARGV[i]) end
redis.call('SET',KEYS[2],ARGV[2]); redis.call('DEL',KEYS[5]); return 1
