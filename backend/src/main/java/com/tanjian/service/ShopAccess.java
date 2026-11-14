package com.tanjian.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tanjian.dto.UserDTO;
import com.tanjian.entity.ShopManager;
import com.tanjian.mapper.ShopManagerMapper;
import com.tanjian.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ShopAccess {
    private final ShopManagerMapper shopManagerMapper;

    public boolean canManage(Long shopId) {
        UserDTO user = UserHolder.getUser();
        return shopId != null && user != null && user.getId() != null
                && shopManagerMapper.selectCount(new LambdaQueryWrapper<ShopManager>()
                .eq(ShopManager::getUserId, user.getId()).eq(ShopManager::getShopId, shopId)) > 0;
    }

    public List<Long> managedShopIds() {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) return List.of();
        return shopManagerMapper.selectList(new LambdaQueryWrapper<ShopManager>()
                        .eq(ShopManager::getUserId, user.getId()))
                .stream().map(ShopManager::getShopId).distinct().toList();
    }
}
