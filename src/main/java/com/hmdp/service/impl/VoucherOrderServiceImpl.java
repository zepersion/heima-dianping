package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import net.sf.jsqlparser.expression.StringValue;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;


/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private IVoucherOrderService voucherOrderService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        //new一个对象 让我们的锁分配对象
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation((new ClassPathResource("seckill.lua")));/* 存入并加载我们的lua的地址classPathResource是在resource地址 */
        SECKILL_SCRIPT.setResultType(Long.class);//类型转换
    }

    //创建阻塞队列
    private BlockingQueue<VoucherOrder> OrdersTask = new ArrayBlockingQueue<>(1024 * 1024);
    //添加线程
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();//线程池

    //当前类初始化完成之后启动
    @PostConstruct
    public void init() {
        SECKILL_ORDER_EXECUTOR.submit(new vocherOrederHandle());
    }

    //创建代理对象的内部成员对象IVocherService
    private IVoucherOrderService proxy;
    //创建一个线程任务线程任务

    public class vocherOrederHandle implements Runnable {
        String queueName = "stream.orders";

        //在用户抢购之前使用,在类初始化之后执行
        @Override
        public void run() {
            while (true) {
                try {
                    //1.获取 xreadgroup group g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        continue;
                        //如果获取失败,说明没有消息,继续下一次循环
                    }
                    //解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    //如果获取成功 可以下单
                    handleVocherOrder(voucherOrder);
                    //ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("订单处理异常", e);
                    handlePendingList();
                }
            }

        }

        private void handlePendingList() {
            while (true) {
                try {
                    //1.获取 xreadgroup group g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        break;
                        //如果获取失败,说明没有消息,继续下一次循环
                    }
                    //解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    //如果获取成功 可以下单
                    handleVocherOrder(voucherOrder);
                    //ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    try {
                        log.error("处理pending--list异常", e);
                        Thread.sleep(1000);
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }

                }
            }


        }
    }


        private void handleVocherOrder(VoucherOrder voucherOrder) {
            //创建一个锁对象
            //simpleRedisLock lock = new simpleRedisLock(stringRedisTemplate, "Order"+userId);
            // SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate,"Order:"+userId);
            Long userId = voucherOrder.getUserId();

            RLock lock = redissonClient.getLock("Lock:Order:" + userId);
            //尝试获取锁
            boolean success = lock.tryLock();
            if (!success) {

                log.error("不允许重复下单");
                return;
            }

            try {
                proxy.createVoucherOrder(voucherOrder);//对当前1业务改造
            } catch (IllegalStateException e) {
                throw new RuntimeException(e);
            } finally {
                //释放锁
                lock.unlock();
            }
        }


        @Override
        @Transactional
        public Result seckillVoucher(Long voucherId) {
            //获取用户
            Long userId = UserHolder.getUser().getId();
            //包装信息
            long order = redisIdWorker.nextId("order");
            //执行lua脚本
            Long result = stringRedisTemplate.execute(
                    SECKILL_SCRIPT,
                    Collections.emptyList(),
                    voucherId.toString(), userId.toString(), String.valueOf(order)
            );
            int r = result.intValue();
            if (r != 0) {
                //判断是否为零
                return Result.fail(r == 1 ? "库存不充足" : "不可以重复下单");
            }
            VoucherOrder voucherOrder = new VoucherOrder();
            voucherOrder.setVoucherId(voucherId);
            voucherOrder.setUserId(userId);

            //TODO 异步下单 创建阻塞队列
            OrdersTask.add(voucherOrder);

            //创建代理对象
            proxy = (IVoucherOrderService) AopContext.currentProxy();//currentProxy()该方法是拿到当前的代理对象


            return Result.ok(order);






        /*
        //1.查询优惠卷信息
        SeckillVoucher secKillVoucher = seckillVoucherService.getById(voucherId);
        //2.判断秒杀是否开始
        if(secKillVoucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀未开始!");
        }
        if(secKillVoucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已结束!");
        }
        //2.1否
        //3.判断库存是否充足
        if(secKillVoucher.getStock() <1){
            return Result.fail("库存不充足!");
        }
        Long userId = UserHolder.getUser().getId();
       // synchronized (userId.toString().intern()) {//intern()方法比较String池里面有没有相同地址的方法
        //创建一个锁对象
        //simpleRedisLock lock = new simpleRedisLock(stringRedisTemplate, "Order"+userId);
       // SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate,"Order:"+userId);

        RLock lock =redissonClient.getLock("Lock:Order:"+userId);
        //尝试获取锁
        boolean success = lock.tryLock();
        if(!success){
            return Result.fail("不允许重复下单");
        }

        try {
            IVoucherOrderService proxy =( IVoucherOrderService) AopContext.currentProxy();//currentProxy()该方法是拿到当前的代理对象
            return proxy. createVoucherOrder(voucherId);//当前存储存储的对象
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        }finally {
            //释放锁
            lock.unlock();
        }*/


        }

        @Transactional
        public void createVoucherOrder(VoucherOrder voucherOrder) {
            //一人只能一个单
            Long userId = voucherOrder.getUserId();
            int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
            if (count > 0) {
                log.error("用户已经下过一次单了");

                return;
            }
            //扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock=stock-1")
                    .eq("voucher_id", voucherOrder.getVoucherId())
                    .eq("stock", 0)//where id+? and stock>0
                    .update();
            if (!success) {
                log.error("库存不足!");
            }
            save(voucherOrder);
            return;
        }
    }
