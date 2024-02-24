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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 优惠券订单服务实现类
 * </p>
 *
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService iSeckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private RedisIdWorker redisIdWorker;

    // 设置lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     * 创建阻塞队列并指定大小
     */
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    /**
     * 创建线程池
     */
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct  // 在该类初始化的时候执行
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /**
     * 子线程：执行创建订单任务
     */
    private class VoucherOrderHandler implements Runnable{
        String queueName = "stream.orders";
        @Override
        public void run() {
            while(true){
                try {
                    // 1、获取队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );

                    // 2、判断消息是否获得成功
                    if(list == null || list.isEmpty()){
                        // 如果获取失败，说明没有消息，进入下一轮循环
                        continue;
                    }

                    // 3、解析消息中的订单信息
                    MapRecord<String,Object,Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 4、如果获得成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    // 5、ack确认 sack stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());

                } catch (Exception e) {
                    log.error("处理pending-list异常",e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }

        /**
         * 处理在ack确认前发生异常进入pendingList的消息
         */
        private void handlePendingList() {
            while(true){
                try {
                    // 1、获取队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );

                    // 2、判断消息是否获得成功
                    if(list == null || list.isEmpty()){
                        // 如果获取失败，说明pendinglist没有异常消息，结束循环
                        break;
                    }

                    // 3、解析消息中的订单信息
                    MapRecord<String,Object,Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 4、如果获得成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    // 5、ack确认 sack stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());

                } catch (Exception e) {
                    log.error("订单处理异常",e);
                    handlePendingList();
                }
            }
        }
    }

    /**
     * 由子线程来调用创建订单的方法
     * @param voucherOrder
     */
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        // 1、创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 2、获取锁
        boolean isLock = lock.tryLock();        // 默认等待时长是30s

        // 3、判断获取状态
        if (!isLock){
            log.error("不允许重复下单！");
            return;
        }

        // 4、处理业务
        // 获取事务代理对象
        try {
             proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 代理对象
     */
    private IVoucherOrderService proxy;


    /**
     * 秒杀优惠券抢购
     *
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 版本二：redis异步秒杀优化

        // 获取用户
        Long userId = UserHolder.getUser().getId();
        // 获取订单id
        long seckillOrderId = redisIdWorker.nextId("order");
        // 1、执行lua
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),        // 空集合
                voucherId.toString(),
                userId.toString(),
                String.valueOf(seckillOrderId)
        );

        // 判断是否具备购买资格
        int r = result.intValue();  // long转成int
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        /*
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(seckillOrderId);     // 订单id
        voucherOrder.setVoucherId(voucherId);   // 券id
        voucherOrder.setUserId(userId);   // 用户id

        // 加入阻塞队列
        orderTasks.add(voucherOrder);
        */

        // 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        // 返回订单id
        return Result.ok(seckillOrderId);
    }

    /**
     * 生成订单，保存至数据库
     *
     * @param voucherOrder
     */
    @Override
    @Transactional      // 多表操作添加事务
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 不在方法上加锁，这样锁的范围太大
        // 4、实现一人一单，如果用户已经买了，就不在保存该订单
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();

        Integer count = query().eq("voucher_id", voucherId).eq("user_id", userId).count();
        if (count > 0) {
            log.error("不可重复下单");
            return;
        }


        // 5、以上条件满足，扣减库存，创建秒杀订单
        boolean success = iSeckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)      // 使用乐观锁的cas方法（compare and swap)实现超卖，只有数据库中的库存大于0才能创建成功
                .update();// 扣减库存，更新数据库

        if (!success) {
            log.error("该券已售罄");
            return ;
        }

        long seckillOrderId = redisIdWorker.nextId("order");        // 给秒杀券生成全局唯一id
        voucherOrder.setId(seckillOrderId);     // 订单id
        voucherOrder.setVoucherId(voucherId);   // 券id
        voucherOrder.setUserId(userId);   // 用户id

        save(voucherOrder);     // 秒杀券订单保存至数据库
    }
}
