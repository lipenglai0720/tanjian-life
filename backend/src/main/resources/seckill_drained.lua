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

return 1
