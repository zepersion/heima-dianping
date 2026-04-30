package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
@Slf4j
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    public void set(String key, Object value,Long time , TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }
    public void setWithExpire(String key, Object value,Long time , TimeUnit timeUnit) {
        RedisData redisData = new RedisData();//设置逻辑过期
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        redisData.setData(value);
        //写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));

    }
    public <R,ID> R queryWithPassThough(ID id, String keyPreFix, Class<R> type, Function<ID,R> dbFallback,Long time , TimeUnit timeUnit)  {
        String key =  keyPreFix+ id;

        String Json = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if(StrUtil.isNotBlank(Json)){
            //存在 返回商铺信息

            return  JSONUtil.toBean(Json,type);
        }//判断商铺是否为空值
        if(Json != null){
            return null;
        }
        //不存在,根据id查询数据库
        R r= dbFallback.apply(id);

        if(r == null){
            //不存在 404
            //返回Null到redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.SECONDS);
            return null;
        }

        //存在 写入redis
       this.set(key,r,time,timeUnit);
        //存在 返回商铺信息
        return r;
    }
    private boolean  tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", CACHE_NULL_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public <R,ID> R queryWithLogicalExpire(ID id, String keyPreFix, Class<R> type, Function<ID,R> dbFallback,Long time , TimeUnit timeUnit){
        String key =  keyPreFix+ id;

        String Json = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if(StrUtil.isBlank(Json)) {
            //存在 返回商铺信息
            return null;
        }
        //命中 json反序列化为对象
        RedisData redisData = JSONUtil.toBean(Json, RedisData.class);
       R r =JSONUtil.toBean((JSONObject) redisData.getData(),type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())) {
            //没有过期 返回店铺信息

            return r;
        }

        //过期 需要缓存重建
        //获取互斥锁 判断是否成功
        String lockkey=keyPreFix+id;
        boolean isLock = tryLock(lockkey);
        if(isLock){
            //成功 开启独立线程
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //查询
                    R r1 = dbFallback.apply(id);
                    //写入redis
                    this.setWithExpire(key,r1,time,timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockkey);
                }
            });
        }
        //缓存重建
        //失败
        //不存在,根据id查询数据库
        r = dbFallback.apply(id);

        if(r == null){
            //不存在 404
            //返回Null到redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.SECONDS);
            return null;
        }

        //存在 写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(r),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //存在 返回商铺信息
        return r;
    }

//


}
