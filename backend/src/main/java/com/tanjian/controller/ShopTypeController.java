package com.tanjian.controller;


import com.tanjian.dto.Result;
import com.tanjian.entity.ShopType;
import com.tanjian.service.IShopTypeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 */
@RestController
@RequestMapping("/shop-type")
public class ShopTypeController {

    private final IShopTypeService typeService;

    public ShopTypeController(IShopTypeService typeService) {
        this.typeService = typeService;
    }

    @GetMapping("list")
    public Result queryTypeList() {
        return typeService.queryShopType();
    }
}
