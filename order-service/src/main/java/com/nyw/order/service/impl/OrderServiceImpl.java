package com.nyw.order.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.nyw.api.client.CartClient;
import com.nyw.api.client.ItemClient;
import com.nyw.api.dto.ItemDTO;
import com.nyw.api.dto.OrderDetailDTO;
import com.nyw.common.exception.BadRequestException;
import com.nyw.common.utils.DistributedIdGenerator;
import com.nyw.order.domain.dto.OrderFormDTO;
import com.nyw.order.domain.po.Order;
import com.nyw.order.domain.po.OrderDetail;
import com.nyw.order.mapper.OrderMapper;
import com.nyw.order.service.IOrderDetailService;
import com.nyw.order.service.IOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements IOrderService {

    private final ItemClient itemClient;
    private final CartClient cartClient;
    private final IOrderDetailService detailService;
    private final DistributedIdGenerator distributedIdGenerator;

    @Override
    @Transactional
    public Long createOrder(OrderFormDTO orderFormDTO) {
        Order order = new Order();
        List<OrderDetailDTO> detailDTOS = orderFormDTO.getDetails();
        Map<Long, Integer> itemNumMap = detailDTOS.stream()
                .collect(Collectors.toMap(OrderDetailDTO::getItemId, OrderDetailDTO::getNum));
        Set<Long> itemIds = itemNumMap.keySet();

        // 通过 Feign 查询商品
        List<ItemDTO> items = itemClient.queryItemByIds(itemIds);
        if (items == null || items.size() < itemIds.size()) {
            throw new BadRequestException("商品不存在");
        }

        // 计算总价
        int total = 0;
        for (ItemDTO item : items) {
            total += item.getPrice() * itemNumMap.get(item.getId());
        }
        order.setTotalFee(total);
        order.setPaymentType(orderFormDTO.getPaymentType());
        order.setUserId(com.nyw.common.utils.UserContext.getUser());
        order.setStatus(1);

        // 使用 Redis 分布式 ID
        order.setId(distributedIdGenerator.nextOrderId());
        order.setCreateTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());
        save(order);

        // 保存订单详情
        List<OrderDetail> details = buildDetails(order.getId(), items, itemNumMap);
        detailService.saveBatch(details);

        // 清理购物车
        cartClient.removeByItemIds(itemIds);

        // 扣减库存
        try {
            itemClient.deductStock(detailDTOS);
        } catch (Exception e) {
            throw new RuntimeException("库存不足！");
        }
        return order.getId();
    }

    @Override
    public void markOrderPaySuccess(Long orderId) {
        Order order = new Order();
        order.setId(orderId);
        order.setStatus(2);
        order.setPayTime(LocalDateTime.now());
        updateById(order);
    }

    private List<OrderDetail> buildDetails(Long orderId, List<ItemDTO> items, Map<Long, Integer> numMap) {
        List<OrderDetail> details = new ArrayList<>(items.size());
        for (ItemDTO item : items) {
            OrderDetail detail = new OrderDetail();
            detail.setName(item.getName());
            detail.setSpec(item.getSpec());
            detail.setPrice(item.getPrice());
            detail.setNum(numMap.get(item.getId()));
            detail.setItemId(item.getId());
            detail.setImage(item.getImage());
            detail.setOrderId(orderId);
            details.add(detail);
        }
        return details;
    }
}