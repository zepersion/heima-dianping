package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;

import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
public Result sendCode(String phone, HttpSession session) {
    //校验手机号
    if(RegexUtils.isPhoneInvalid(phone)){
        //不符合 返回信息
        return Result.fail("手机号格式错误");
    }
    //符合 生成验证码
    String code = RandomUtil.randomNumbers(6);
    //保存验证码到redis
        stringRedisTemplate
                .opsForValue()
                .set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
    //发送验证码
    session.setAttribute("code",code);
    //验证:因为需要第三方库所以暂时用该信息代替
    log.debug("发送验证码成功,验证码: {}");
    return Result.ok();
}

    @Override
    public Result sendLoginCode(LoginFormDTO loginForm, HttpSession session) {
        //获取手机号
        String phone=loginForm.getPhone();
        //校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            //不符合 返回信息
            return Result.fail("手机号格式错误");
        }
        //校验验证码传到Redis

        String cacheCode=stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();//前端传回来的值
        if(cacheCode==null || !cacheCode.equals(code)){
            //不一致 返回信息
            return Result.fail("验证码不正确!");
        }
        //如果一致,根据手机查询该用户是否存在 select * from usr where phone = #{phone} 但是plus版本为下面
        User user = query().eq("phone", phone).one();
        //
        if(user==null){

            user= createUserWithPhone(phone);
        }
        //将用户保存到redis中
        //session.setAttribute("user",user);//将user拷贝到userdto
        //TODO 随机生成token 作为登录令牌
        String token = UUID.randomUUID().toString();
        //TODO 随将user对象转为Hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);//
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), CopyOptions.create()
                .setIgnoreNullValue(true).setFieldValueEditor((fieldName,fieldValue)->fieldValue.toString()));
        //TODO 存储
        String tokenKey=LOGIN_CODE_KEY + phone;
        stringRedisTemplate
                .opsForHash()
                .putAll(tokenKey,userMap);
        //TODO 设置token有效期
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL, TimeUnit.MINUTES);
        //返回token值
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
    User user = new User();
    user.setPhone(phone);
    user.setPassword(RandomUtil.randomNumbers(6));
    return user;

    }

}
