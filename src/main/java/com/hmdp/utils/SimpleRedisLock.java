package com.hmdp.utils;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private StringRedisTemplate stringRedisTemplate;
    private String name;
    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    private static  final String KEY_PREFIX = "lock:";
private  static  final String ID_PREFIX = UUID.randomUUID().toString()+"-";
private  static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
static {
    //new一个对象 让我们的锁分配对象
    UNLOCK_SCRIPT=new DefaultRedisScript<>();
    UNLOCK_SCRIPT.setLocation(new ClassPathResource("unLock.Lua"));//存入并加载我们的lua的地址classPathResource是在resource地址
    UNLOCK_SCRIPT.setResultType(Long.class);//类型转换
}
    @Override
    public boolean tryLock(long timeoutsec) {
        //获取当前线程id
        String threadId = ID_PREFIX+Thread.currentThread().getId();
        //获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId
                , timeoutsec, TimeUnit.SECONDS);

        return Boolean.TRUE.equals(success);


    }

    @Override
   public void unLock() {
    stringRedisTemplate.execute(
            UNLOCK_SCRIPT,
            Collections.singletonList(KEY_PREFIX+name),//单一类型的集合
            ID_PREFIX+Thread.currentThread().getId()//当前的线程id
    );

    }//    @Override
//    public void unLock() {
//        String ThreadId =ID_PREFIX+Thread.currentThread().getId();
//        String id=stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        if(ThreadId.equals(id)){
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//
//    }
}
