-- ============================================
-- 秒杀压测 - 数据库初始化脚本
-- 执行方式：mysql -h ${MW_HOST} -u root -p${MW_PASSWORD} nyw < init-seckill-test.sql
-- ============================================

-- 1. 创建秒杀券表（如不存在）
CREATE TABLE IF NOT EXISTS `seckill_voucher` (
    `voucher_id` BIGINT NOT NULL COMMENT '秒杀券ID',
    `item_id` BIGINT NOT NULL COMMENT '关联商品ID',
    `stock` INT NOT NULL DEFAULT 0 COMMENT '秒杀库存',
    `begin_time` DATETIME NOT NULL COMMENT '秒杀开始时间',
    `end_time` DATETIME NOT NULL COMMENT '秒杀结束时间',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`voucher_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀券表';

-- 2. 创建秒杀订单表（如不存在）
CREATE TABLE IF NOT EXISTS `seckill_order` (
    `id` BIGINT NOT NULL COMMENT '订单ID',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `voucher_id` BIGINT NOT NULL COMMENT '秒杀券ID',
    `item_id` BIGINT NOT NULL COMMENT '商品ID',
    `status` INT NOT NULL DEFAULT 1 COMMENT '1=未支付, 2=已支付, 3=已关闭',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `pay_time` DATETIME DEFAULT NULL COMMENT '支付时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_voucher` (`user_id`, `voucher_id`),
    KEY `idx_status_create_time` (`status`, `create_time`)
]) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀订单表';

-- 3. 插入测试秒杀券（库存 3000，用于 3000 并发测试）
-- 先清理旧数据
DELETE FROM seckill_order WHERE voucher_id = 1;
DELETE FROM seckill_voucher WHERE voucher_id = 1;

-- 插入秒杀券（假设商品ID=1，库存3000，秒杀时间覆盖当前）
INSERT INTO seckill_voucher (voucher_id, item_id, stock, begin_time, end_time, create_time, update_time)
VALUES (1, 1, 3000, '2025-01-01 00:00:00', '2030-12-31 23:59:59', NOW(), NOW());

-- 4. 超时订单扫表兜底所需联合索引：(status 等值过滤, create_time 范围过滤)
--    MySQL 8 不支持 ADD INDEX IF NOT EXISTS，先查 information_schema 再动态执行
SET @idx_cnt := (SELECT COUNT(*) FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = 'seckill_order'
                   AND index_name = 'idx_status_create_time');
SET @ddl := IF(@idx_cnt = 0,
               'ALTER TABLE seckill_order ADD INDEX idx_status_create_time (status, create_time)',
               'SELECT 1');
PREPARE s FROM @ddl;
EXECUTE s;
DEALLOCATE PREPARE s;

-- 5. 验证数据
SELECT voucher_id, item_id, stock, begin_time, end_time FROM seckill_voucher WHERE voucher_id = 1;