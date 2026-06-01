package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Resource
    private IUserService userService;


    @Override
    public Result follow(Long followId, boolean isfollow) {
        //导入当前用户信息
        Long userId = UserHolder.getUser().getId();
        //判断是否关注
        if(isfollow){
            Follow follow = new Follow();
            follow.setId(followId);
            follow.setUserId(userId);
            boolean isSuccess = save(follow);
            if(isSuccess){
                String key ="follow_"+userId;
                //把关注用户的id 放入redis的set集合 sadd userId followerUserId
                redisTemplate.opsForSet().add(key, String.valueOf(followId));
            }
        }else {
            boolean isSuccess = remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", followId));
            if(isSuccess){
                String key ="follow_"+userId;
                redisTemplate.opsForSet().remove(key,followId);
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long userId) {
        //select * from tb_follow where user_id = ? and follow_user_id=?
        Integer count = query().eq("user_id", userId).eq("follow_user_id", userId).count();
        return Result.ok(count>0);
    }

    @Override
    public Result followCommons(Long id) {
        //获取userid
        Long userId = UserHolder.getUser().getId();
        //设置user的key
        String key1="User"+userId;
        //被关注的人的id
        String key2="Follow"+id;
    //获取交集
        Set<String> intersect = redisTemplate.opsForSet().intersect(key1, key2);
        if(intersect==null||intersect.isEmpty()){
            //如果不存在返回一个空集合 其实就空页面
            return Result.ok(Collections.emptyList());
        }
        //解析id集合
        List<Long> ids = intersect.stream()
                .map(Long::valueOf)
                .collect(Collectors.toList());
        //查询用户
        List<UserDTO> users = userService.listByIds(ids).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(users);
    }
}
