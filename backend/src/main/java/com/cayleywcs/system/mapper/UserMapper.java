package com.cayleywcs.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cayleywcs.auth.LoginUserRow;
import com.cayleywcs.system.entity.UserEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

public interface UserMapper extends BaseMapper<UserEntity> {

    @Select("""
            select
              u."id" as user_id,
              u."user_num" as user_num,
              u."user_name" as user_name,
              u."user_role" as user_role,
              ur."id" as userrole_id,
              u."tenant_id" as tenant_id,
              u."auth_string" as auth_string
            from "wcs_user" u
            join "wcs_userrole" ur
              on u."user_role" = ur."role_name"
             and u."tenant_id" = ur."tenant_id"
            where (u."user_name" = #{loginName} or u."user_num" = #{loginName})
              and u."is_valid" = true
              and ur."is_valid" = true
            limit 1
            """)
    @Results({
            @Result(column = "user_id", property = "userId"),
            @Result(column = "user_num", property = "userNum"),
            @Result(column = "user_name", property = "userName"),
            @Result(column = "user_role", property = "userRole"),
            @Result(column = "userrole_id", property = "userroleId"),
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "auth_string", property = "authString")
    })
    LoginUserRow findLoginUser(@Param("loginName") String loginName);
}
