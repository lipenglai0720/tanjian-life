package com.tanjian.service.impl;

import com.tanjian.entity.UserInfo;
import com.tanjian.mapper.UserInfoMapper;
import com.tanjian.service.IUserInfoService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;


@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

}
