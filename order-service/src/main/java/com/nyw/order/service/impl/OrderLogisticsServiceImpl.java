package com.nyw.order.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.nyw.order.domain.po.OrderLogistics;
import com.nyw.order.mapper.OrderLogisticsMapper;
import com.nyw.order.service.IOrderLogisticsService;
import org.springframework.stereotype.Service;

@Service
public class OrderLogisticsServiceImpl extends ServiceImpl<OrderLogisticsMapper, OrderLogistics> implements IOrderLogisticsService {
}