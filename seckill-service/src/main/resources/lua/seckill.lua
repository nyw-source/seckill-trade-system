-- 秒杀 Lua 脚本：原子校验库存 + 一人一单 + 扣减库存
-- KEYS[1]: 秒杀库存 key (hmall:seckill:stock:{voucherId})
-- KEYS[2]: 秒杀已下单用户集合 key (hmall:seckill:order:set:{voucherId})
-- ARGV[1]: 用户 ID
-- ARGV[2]: 订单 ID
-- 返回值: 0=成功, 1=库存不足, 2=用户已下单

-- 1. 检查用户是否已下单
local isMember = redis.call('sismember', KEYS[2], ARGV[1])
if isMember == 1 then
    return 2
end

-- 2. 检查库存是否充足
local stock = redis.call('get', KEYS[1])
if not stock or tonumber(stock) <= 0 then
    return 1
end

-- 3. 扣减库存
redis.call('decr', KEYS[1])

-- 4. 记录用户已下单
redis.call('sadd', KEYS[2], ARGV[1])

-- 5. 返回成功
return 0