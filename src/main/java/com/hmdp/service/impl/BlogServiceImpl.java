package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

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
    @Resource
    private IFollowService followService;
    @Autowired
    private RedisTemplate<Object, Object> redisTemplate;

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

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccees = save(blog);
        // 返回id
        if(!isSuccees){
            return Result.fail("新建笔记失败");
        }
        //查询用户的收藏列表
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        for(Follow follow:follows){
            //获取粉丝id
            Long userId = follow.getUserId();
            //获取他的key
            String key=FEED_KEY+userId;
            //推送消息
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        //查询收件箱
        String key=FEED_KEY+userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 3);
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        //解析数据blogId minTime offset
        List<Long> ids=new ArrayList<>(typedTuples.size());
       long minTime=0;
        int os=1;
        for(ZSetOperations.TypedTuple<String> typedTuple:typedTuples){
            //获取id
           ids.add(Long.valueOf(typedTuple.getValue()));
           //
            long time=typedTuple.getScore().longValue();
           if(time==minTime){
               os++;
           }else
           {
               minTime=time;
               os=1;
           }
//minTime负责定位到分数段，os负责在这个分数段内定位到具体的位置。两者结合，才能在不支持双游标的旧版 Redis 中，完美解决同分数据丢失的问题。
        }
        //根据id查询blog
        String idstr = StrUtil.join(",", ids);
        List<Blog> blogs=query().in("id",ids).last("ORDER BY FIELD(id"+idstr+")").list();
        for(Blog blog:blogs){
            //查询和blog有关的用户
            queryBlogUser(blog);
            //查询是否被点赞
        }
        //封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);
        return Result.ok(r);
    }
}
