package com.stellarink.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stellarink.user.pojo.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
