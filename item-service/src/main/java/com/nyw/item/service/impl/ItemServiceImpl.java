package com.nyw.item.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.nyw.common.cache.CacheService;
import com.nyw.common.cache.RedisKeyConstants;
import com.nyw.common.exception.BizIllegalException;
import com.nyw.common.utils.BeanUtils;
import com.nyw.item.domain.dto.ItemDTO;
import com.nyw.item.domain.dto.OrderDetailDTO;
import com.nyw.item.domain.po.Item;
import com.nyw.item.domain.query.ItemPageQuery;
import com.nyw.item.mapper.ItemMapper;
import com.nyw.item.service.IItemService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 商品表 服务实现类
 * </p>
 *
 * @author 牛压文
 */
@Service
@RequiredArgsConstructor
public class ItemServiceImpl extends ServiceImpl<ItemMapper, Item> implements IItemService {

    private final CacheService cacheService;

    /** 商品详情缓存基础 TTL（分钟），实际写入时会叠加 80%~120% 的随机波动防雪崩 */
    private static final long ITEM_CACHE_TTL_MINUTES = 30L;

    // ==================== 商品详情查询（接入缓存） ====================

    /**
     * 商品详情查询：缓存 → DB 回源 → 回填
     *
     * <p>用 {@code queryWithPassThrough}（防穿透）而不是先查缓存再判断空：
     * 查不到的商品会写入一个 30 秒的空值缓存，恶意用不存在的 id 刷接口时不会每次都打到 DB。
     */
    @Override
    public ItemDTO queryItemById(Long id) {
        if (id == null) {
            return null;
        }
        return cacheService.queryWithPassThrough(
                RedisKeyConstants.CACHE_ITEM,
                id,
                ItemDTO.class,
                this::loadItemFromDb,
                ITEM_CACHE_TTL_MINUTES,
                TimeUnit.MINUTES);
    }

    /**
     * DB 回源（缓存未命中时才会执行，是 SQL 条数的唯一来源）
     */
    private ItemDTO loadItemFromDb(Long id) {
        Item item = getById(id);
        return item == null ? null : BeanUtils.copyBean(item, ItemDTO.class);
    }

    // ==================== 写路径：Cache-Aside 删缓存 ====================

    /**
     * 覆写 MP 的 updateById，统一在写成功后失效缓存
     * <p>放在这里而不是 Controller，是为了不漏掉任何调用方（saveOrUpdate 内部也会走本方法）
     */
    @Override
    public boolean updateById(Item entity) {
        boolean success = super.updateById(entity);
        if (success) {
            cacheService.evict(RedisKeyConstants.CACHE_ITEM, entity.getId());
        }
        return success;
    }

    @Override
    public boolean removeById(Serializable id) {
        boolean success = super.removeById(id);
        if (success) {
            cacheService.evict(RedisKeyConstants.CACHE_ITEM, id);
        }
        return success;
    }

    @Override
    public void deductStock(List<OrderDetailDTO> items) {
        String sqlStatement = "com.nyw.item.mapper.ItemMapper.updateStock";
        boolean r = false;
        try {
            r = executeBatch(items, (sqlSession, entity) -> sqlSession.update(sqlStatement, entity));
        } catch (Exception e) {
            throw new BizIllegalException("更新库存异常，可能是库存不足!", e);
        }
        if (!r) {
            throw new BizIllegalException("库存不足！");
        }
        // 库存属于商品快照的一部分，扣减后同样要失效缓存，否则详情页会读到旧库存
        items.forEach(item -> cacheService.evict(RedisKeyConstants.CACHE_ITEM, item.getItemId()));
    }

    // ==================== 其余查询 ====================

    @Override
    public List<ItemDTO> queryItemByIds(Collection<Long> ids) {
        return BeanUtils.copyList(listByIds(ids), ItemDTO.class);
    }

    @Override
    public Map<String, Object> searchItems(ItemPageQuery query) {
        Page<Item> result = lambdaQuery()
                .like(StrUtil.isNotBlank(query.getKey()), Item::getName, query.getKey())
                .eq(StrUtil.isNotBlank(query.getBrand()), Item::getBrand, query.getBrand())
                .eq(StrUtil.isNotBlank(query.getCategory()), Item::getCategory, query.getCategory())
                .eq(Item::getStatus, 1)
                .between(query.getMaxPrice() != null, Item::getPrice, query.getMinPrice(), query.getMaxPrice())
                .page(query.toMpPage("update_time", false));

        Map<String, Object> map = new HashMap<>();
        map.put("total", result.getTotal());
        map.put("pages", result.getPages());
        map.put("list", BeanUtils.copyList(result.getRecords(), ItemDTO.class));
        return map;
    }
}
