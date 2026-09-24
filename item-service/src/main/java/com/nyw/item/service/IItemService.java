package com.nyw.item.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.nyw.item.domain.dto.ItemDTO;
import com.nyw.item.domain.dto.OrderDetailDTO;
import com.nyw.item.domain.po.Item;
import com.nyw.item.domain.query.ItemPageQuery;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 商品表 服务类
 * </p>
 *
 * @author 牛压文
 * @since 2023-05-05
 */
public interface IItemService extends IService<Item> {

    void deductStock(List<OrderDetailDTO> items);

    /**
     * 根据 id 查询商品详情（走多级缓存，防穿透）
     *
     * @param id 商品 id
     * @return 商品详情，不存在返回 null
     */
    ItemDTO queryItemById(Long id);

    List<ItemDTO> queryItemByIds(Collection<Long> ids);

    Map<String, Object> searchItems(ItemPageQuery query);
}
