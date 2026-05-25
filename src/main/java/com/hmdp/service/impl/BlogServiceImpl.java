package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog != null) {
            return Result.fail("笔记不存在");
        }
        //查询blog有关的用户
        queryBlogUser(blog);
        //查询blog是否点赞
        isBlogliked(blog);
        return Result.ok(blog);
    }



    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }


    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
             this.queryBlogUser(blog);
            this.isBlogliked(blog);}
            )
        ;
        return Result.ok(records);
    }

    @Override
    public Result LikeBlog(Long id) {
     //获取登陆用户
        Long userId=UserHolder.getUser().getId();
     //判断当前登陆用户是否已经点赞
        String key="blog:liked"+id;
        Double score = stringRedisTemplate.opsForZSet().score(key,userId.toString());
        if(score==null){
            //如果未点赞 可以点赞
             //数据库修改
            boolean isSuccess=update().setSql("liked = liked + 1").eq("id",id).update();
            if(isSuccess){
                stringRedisTemplate.opsForZSet().add(key, userId.toString(),System.currentTimeMillis());
            }else {
                boolean isFalse=update().setSql("liked = liked - 1").eq("id",id).update();
                if(isFalse){
                    stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
    }
        return Result.ok();
}

    private  void isBlogliked(Blog blog) {
        //获取登陆用户
        UserDTO user = UserHolder.getUser();
        if(user == null){
            return ;
        }
        Long userId = user.getId();
        //判断当前登陆用户是否已经点赞
        String key="blog:liked"+blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score!=null);//true代表你已经点过赞了
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key=BLOG_LIKED_KEY+id;
        //top用户id的集合
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key , 0, 4);
        if(top5 ==null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //map映射成Long类型 collect收集这些信息整合成为一个集合
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idstr = StrUtil.join(",", ids);
        //拿到这些集合中的用户信息转化为Dto
        List<UserDTO> userDTOS = userService.query()
                .in("id",ids).
                last("ORDER BY FIELD(id"+idstr+")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user,UserDTO.class))
                .collect(Collectors.toList());


        return Result.ok(userDTOS);
    }
}
