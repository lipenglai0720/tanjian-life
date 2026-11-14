package com.tanjian.service;

import com.tanjian.dto.Result;
import com.tanjian.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;


public interface IFollowService extends IService<Follow> {

    Result follow(Long id, Boolean isFollow);

    Result isFollow(Long id);

    Result followCommons(Long id);
}
