-- ============================================
-- seckill-trade-system · 秒杀业务建表脚本
-- 库名：nyw    字符集：utf8mb4
-- 执行：mysql -h <host> -u root -p nyw < sql/seckill-tables.sql
--
-- 说明：item / cart / user / order 等基础表沿用项目原有库结构，
--       本脚本只包含秒杀模块新增的两张表及其索引。
-- ============================================

-- 1. 秒杀券表
CREATE TABLE IF NOT EXISTS `seckill_voucher` (
    `voucher_id`  BIGINT   NOT NULL COMMENT '秒杀券ID',
    `item_id`     BIGINT   NOT NULL COMMENT '关联商品ID',
    `stock`       INT      NOT NULL DEFAULT 0 COMMENT '秒杀库存',
    `begin_time`  DATETIME NOT NULL COMMENT '秒杀开始时间',
    `end_time`    DATETIME NOT NULL COMMENT '秒杀结束时间',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`voucher_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '秒杀券表';

-- 2. 秒杀订单表
CREATE TABLE IF NOT EXISTS `seckill_order` (
    `id`          BIGINT   NOT NULL COMMENT '订单ID（分布式ID，19位，BIGINT 安全）',
    `user_id`     BIGINT   NOT NULL COMMENT '用户ID',
    `voucher_id`  BIGINT   NOT NULL COMMENT '秒杀券ID',
    `item_id`     BIGINT   NOT NULL COMMENT '商品ID',
    `status`      INT      NOT NULL DEFAULT 1 COMMENT '1=未支付, 2=已支付, 3=已关闭',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `pay_time`    DATETIME DEFAULT NULL COMMENT '支付时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_voucher` (`user_id`, `voucher_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '秒杀订单表';

-- 3. 扫表兜底任务所需联合索引
--    SeckillTimeoutScanTask 的查询条件是 status = 1 AND create_time < ?
--    等值列在前、范围列在后，可以同时吃到过滤与排序
--    MySQL 8 的 ADD INDEX 不支持 IF NOT EXISTS，先查 information_schema 再动态执行，保证可重复执行
SET @idx_cnt := (SELECT COUNT(*) FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = 'seckill_order'
                   AND index_name = 'idx_status_create_time');
SET @ddl := IF(@idx_cnt = 0,
               'ALTER TABLE seckill_order ADD INDEX idx_status_create_time (status, create_time)',
               'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 4. 演示数据：库存 3000 的秒杀券，可直接用于压测复现（重复执行不会插重）
INSERT IGNORE INTO `seckill_voucher` (`voucher_id`, `item_id`, `stock`, `begin_time`, `end_time`)
VALUES (1, 1, 3000, '2025-01-01 00:00:00', '2030-12-31 23:59:59');
