package com.tanjian.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tanjian.dto.LoginFormDTO;
import com.tanjian.dto.Result;
import com.tanjian.entity.User;
import jakarta.servlet.http.HttpSession;


public interface IUserService extends IService<User> {

    Result login(LoginFormDTO loginForm, HttpSession session);

    Result sendCode(String phone, HttpSession session);

    Result logout();

    Result sign();

    Result signCount();
}
