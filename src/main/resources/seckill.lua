local vocherId=ARGV[1]
local userId=ARGV[2]
local orderId=ARGV[3]
--定义
local stockKey="seckill:stock:"..vocherId
local orderKey="seckill:orrder:"..vocherId
if(tonumber(redis.call("get",stockKey)) <=0)then
    return 1
end
--判断用户是否下单 isimember
if (redis.call("sismember",orderKey,userId)==1) then
    return 2
end
--扣库存
redis.call("incrby",stockKey,-1)
--下单(保存用户)
redis.call("sadd",orderKey,userId)
--发送消息到队列中 xadd
redis.call('xadd',"stream.orders",'*',userId,'voucherId',vocherId,'id')
return 0