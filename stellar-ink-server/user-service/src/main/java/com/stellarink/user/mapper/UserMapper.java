package com.stellarink.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stellarink.user.pojo.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserMapper extends BaseMapper<User> {
    @Update("UPDATE `user` SET role=#{role}, auth_version=auth_version+1, "
            + "role_applied_at=NULL, role_apply_note=NULL WHERE id=#{id}")
    int changeRoleAndInvalidate(@Param("id") Long id, @Param("role") String role);

    @Update("UPDATE `user` SET password=#{newHash}, auth_version=auth_version+1 "
            + "WHERE id=#{id} AND password=#{oldHash}")
    int changePasswordAndInvalidate(@Param("id") Long id, @Param("oldHash") String oldHash,
                                   @Param("newHash") String newHash);
}
