package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
        @Resource
        private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //互斥锁解决缓存击穿
        Shop shop = queryWithMutex(id);
        //返回
        if(shop == null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }
//    public Shop queryWithPassThough(Long id) throws InterruptedException {
//        String key =  CACHE_SHOP_KEY + id;
//
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        //判断是否存在
//        if(StrUtil.isNotBlank(shopJson)){
//            //存在 返回商铺信息
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//            return shop;
//        }//判断商铺是否为空值
//        if(shopJson != null){
//            return null;
//        }
//        //不存在,根据id查询数据库
//        Shop shop = getById(id);
//        Thread.sleep(200);
//        if(shop == null){
//            //不存在 404
//            //返回Null到redis
//            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.SECONDS);
//            return null;
//        }
//
//        //存在 写入redis
//        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        //存在 返回商铺信息
//        return shop;
//    }
    public Shop queryWithMutex(Long id) {
        String key =  CACHE_SHOP_KEY + id;

        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //存在 返回商铺信息
            return JSONUtil.toBean(shopJson, Shop.class);
        }//判断商铺是否为空值
        if(shopJson != null){
            return null;
        }
        //未命中获取互斥锁
        String lockkey=LOCK_SHOP_KEY+id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockkey);
            //判断是否获取锁
            if(!isLock){
                Thread.sleep(5);
                return queryWithMutex(id);
            }
            //成功,根据id查询数据库
            shop = getById(id);
                Thread.sleep(200);
            if(shop == null){
                //不存在 404
                //返回Null到redis
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.SECONDS);
                return null;
            }
            //存在 写入redis
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {  unLock(lockkey);
        }

        //存在 返回商铺信息
        return shop;
    }
//private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
//    public Shop queryWithLogicalExpire(Long id){
//        String key =  CACHE_SHOP_KEY + id;
//
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        //判断是否存在
//        if(StrUtil.isBlank(shopJson)) {
//            //存在 返回商铺信息
//            return null;
//        }
//        //命中 json反序列化为对象
//        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
//        Shop shop =JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
//        LocalDateTime expireTime = redisData.getExpireTime();
//        //判断是否过期
//        if(expireTime.isAfter(LocalDateTime.now())) {
//            //没有过期 返回店铺信息
//
//            return shop;
//        }
//
//        //过期 需要缓存重建
//        //获取互斥锁 判断是否成功
//        String lockkey=LOCK_SHOP_KEY+id;
//        boolean isLock = tryLock(lockkey);
//        if(isLock){
//            //成功 开启独立线程
//            CACHE_REBUILD_EXECUTOR.submit(()->{
//                try {
//                    this.saveShop2Redis(id,30L);
//                } catch (Exception e) {
//                    throw new RuntimeException(e);
//                } finally {
//                    unLock(lockkey);
//                }
//            });
//        }
//        //缓存重建
//                //失败
//        //不存在,根据id查询数据库
//         shop = getById(id);
//
//        if(shop == null){
//            //不存在 404
//            //返回Null到redis
//            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.SECONDS);
//            return null;
//        }
//
//        //存在 写入redis
//        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        //存在 返回商铺信息
//        return shop;
//    }
    public void saveShop2Redis(Long id,Long expireSeconds){
        //查数据
        Shop shop = getById(id);
        //封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //写入redis
 stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData),expireSeconds,TimeUnit.SECONDS);


    }
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null){

            return Result.fail("商铺名不能为空");
        }
        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }



   private boolean  tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", CACHE_NULL_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    @Override
    public List<Shop> searchShops(String keyword, Object o) {
        return lambdaQuery()
                .like(StrUtil.isNotBlank(keyword), Shop::getName, keyword)
                .list();
    }
}
