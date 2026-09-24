package com.nyw.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nyw.seckill.domain.po.SeckillVoucher;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface SeckillVoucherMapper extends BaseMapper<SeckillVoucher> {

    /**
     * 扣减秒杀券库存（原子自增，交给数据库保证并发安全）
     * <p>stock &gt; 0 是最后一道防超卖兜底；返回 0 说明库存已耗尽或秒杀券不存在</p>
     *
     * @param voucherId 秒杀券 ID
     * @return 影响行数
     */
    @Update("UPDATE seckill_voucher SET stock = stock - 1, update_time = NOW() " +
            "WHERE voucher_id = #{voucherId} AND stock > 0")
    int deductStock(@Param("voucherId") Long voucherId);

    /**
     * 回补秒杀券库存（原子自增，关闭超时订单时调用）
     * <p>必须是 stock = stock + 1 而不是先查后改，否则并发回补会互相覆盖导致少补</p>
     *
     * @param voucherId 秒杀券 ID
     * @return 影响行数
     */
    @Update("UPDATE seckill_voucher SET stock = stock + 1, update_time = NOW() " +
            "WHERE voucher_id = #{voucherId}")
    int restoreStock(@Param("voucherId") Long voucherId);

    /**
     * 查询需要预热的秒杀券：正在进行中 + 未来一段时间内即将开始的场次
     *
     * @param now      当前时间
     * @param deadline 预热截止时间（now + 提前量）
     * @return 待预热的秒杀券列表
     */
    @Select("SELECT voucher_id, item_id, stock, begin_time, end_time, create_time, update_time " +
            "FROM seckill_voucher WHERE begin_time <= #{deadline} AND end_time >= #{now}")
    List<SeckillVoucher> selectPreheatCandidates(@Param("now") LocalDateTime now,
                                                 @Param("deadline") LocalDateTime deadline);
}
