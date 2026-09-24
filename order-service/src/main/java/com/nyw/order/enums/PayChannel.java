package com.nyw.order.enums;

import lombok.Getter;

@Getter
public enum PayChannel {
    BALANCE("balance", "余额支付"),
    ALIPAY("alipay", "支付宝"),
    WECHAT("wechat", "微信支付"),
    ;
    private final String value;
    private final String desc;

    PayChannel(String value, String desc) {
        this.value = value;
        this.desc = desc;
    }
}