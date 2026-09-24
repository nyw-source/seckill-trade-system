package com.nyw.order.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.nyw.order.domain.po.OrderDetail;
import com.nyw.order.mapper.OrderDetailMapper;
import com.nyw.order.service.IOrderDetailService;
import org.springframework.stereotype.Service;

@Service
public class OrderDetailServiceImpl extends ServiceImpl<OrderDetailMapper, OrderDetail> implements IOrderDetailService {
}