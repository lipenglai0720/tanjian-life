package com.tanjian.mapper;

import com.tanjian.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {


    User selectByPhone(String phone);

    void save(User user);
}
