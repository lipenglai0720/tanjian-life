package com.tanjian.service;

import com.tanjian.dto.Result;
import com.tanjian.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;


public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    Result queryMyOrders(Integer current);
    Result claimVoucher(Long voucherId);
    Result queryOrderResult(Long id);

}
