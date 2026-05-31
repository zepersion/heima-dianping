package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.beans.BeanUtils;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.*;
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

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
      //1.判斷是否要根據坐標查詢
        if(x==null || y==null){

            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

       //2.計算分頁參數
        int from=(current-1)*SystemConstants.DEFAULT_PAGE_SIZE;
        int end=current * SystemConstants.DEFAULT_PAGE_SIZE;

        //3。查询redis 按照距离排序 分页 结果
        String key=SHOP_GEO_KEY+typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()//GEOSEARCH key BYLONLAT X Y BYRADIUS 10 WITHDSTANCE
                .search(key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeCoordinates().limit(end)
                );
        //解析出redis
        if (results ==null){
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <=from ){
            return Result.ok(Collections.emptyList());
        }
        //截取from到end的部分
        Map<String,Distance> distanceMap = new HashMap<>(list.size());
        List<Long> ids = new ArrayList<>(list.size());

        list.stream().skip(from).forEach(result -> {
            //获取商铺id
            String shopIdStr= result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            //获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr,distance);
        });
        //根据id查询shop
        String idsStr = JSONUtil.toJsonStr(ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idsStr + ")").list();
            for(Shop shop:shops){
                shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
            }
        //返回
        return Result.ok(shops);
    }
}
