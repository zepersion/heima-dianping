package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    private static final long BEGIN_TIMESTEMP=1777507200L;
//序列号位数
private static final long COUNT_BITS=32;
    public final StringRedisTemplate stringRedisTemplate;
    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }


    public long nextId(String preFix){
            //生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long NOW_TIMESTEMP = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = NOW_TIMESTEMP-BEGIN_TIMESTEMP;
        //生成序列号
        //.1获取当前日期到天
            String data= now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        //.2自增
        Long increment = stringRedisTemplate.opsForValue().increment("sci" + preFix + ":" + data);
        return timestamp << COUNT_BITS | increment;
    }




}
