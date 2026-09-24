package com.nyw.order.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.nyw.order.domain.dto.PayApplyDTO;
import com.nyw.order.domain.dto.PayOrderFormDTO;
import com.nyw.order.domain.po.PayOrder;

public interface IPayOrderService extends IService<PayOrder> {

    String applyPayOrder(PayApplyDTO applyDTO);

    void tryPayOrderByBalance(PayOrderFormDTO payOrderFormDTO);
}