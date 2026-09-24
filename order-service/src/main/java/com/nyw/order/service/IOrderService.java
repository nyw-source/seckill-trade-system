package com.nyw.order.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.nyw.order.domain.dto.OrderFormDTO;
import com.nyw.order.domain.po.Order;

public interface IOrderService extends IService<Order> {

    Long createOrder(OrderFormDTO orderFormDTO);

    void markOrderPaySuccess(Long orderId);
}