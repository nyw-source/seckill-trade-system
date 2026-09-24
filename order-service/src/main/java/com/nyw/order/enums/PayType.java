package com.nyw.order.enums;

import lombok.Getter;

@Getter
public enum PayType {
    H5(1, "H5支付"),
    MINI_APP(2, "小程序支付"),
    MP(3, "公众号支付"),
    SCAN(4, "扫码支付"),
    BALANCE(5, "余额支付"),
    ;
    private final int value;
    private final String desc;

    PayType(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    public boolean equalsValue(Integer value) {
        if (value == null) return false;
        return getValue() == value;
    }
}