package com.tanjian.service;

import com.tanjian.dto.Result;
import com.tanjian.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;


public interface IShopTypeService extends IService<ShopType> {

    Result queryShopType();
}
