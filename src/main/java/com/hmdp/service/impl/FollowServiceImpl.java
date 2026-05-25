package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

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
    @Override
    public Result follow(Long followId, boolean isfollow) {
        //导入当前用户信息
        Long userId = UserHolder.getUser().getId();
        //判断是否关注
        if(isfollow){
            Follow follow = new Follow();
            follow.setId(followId);
            follow.setUserId(userId);
            save(follow);
        }else {
            remove(new QueryWrapper<Follow>().eq("user_id",userId).eq("follow_user_id",followId));
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long userId) {
        //select * from tb_follow where user_id = ? and follow_user_id=?
        Integer count = query().eq("user_id", userId).eq("follow_user_id", userId).count();
        return Result.ok(count>0);
    }
}
