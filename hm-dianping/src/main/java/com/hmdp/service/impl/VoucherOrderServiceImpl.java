package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService iSeckillVoucherService;

    @Resource
    private IVoucherOrderService iVoucherOrderService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Override

    public Result seckillVoucher(Long voucherId) {

        // 1、查询优惠券信息
        SeckillVoucher seckillVoucher = iSeckillVoucherService.getById(voucherId);
        // 2、判断是否在秒杀时间范围内
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 秒杀之前，不合理
            return Result.fail("秒杀未开始");
        }
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 秒杀之后，不合理
            return Result.fail("秒杀已结束");
        }
        // 3、判断库存是否充足
        if (seckillVoucher.getStock() < 1) {
            return Result.fail("该券已售罄");
        }


        Long userId = UserHolder.getUser().getId();
//        intern() 方法是字符串类 String 的一个方法。intern() 方法返回字符串对象的规范化表示形式，即返回字符串池中的唯一实例。
        synchronized (userId.toString().intern()) {         // 使用每个用户的id作为锁，intern（）表示
            // 获取事务代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
    }

    @Transactional      // 多表操作添加事务
    public Result createVoucherOrder(Long voucherId) {     // 不在方法上加锁，这样锁的范围太大
        // 4、实现一人一单，如果用户已经买了，就不在保存该订单
        Long userId = UserHolder.getUser().getId();


        Integer count = query().eq("voucher_id", voucherId).eq("user_id", userId).count();
        if (count > 0) {
            return Result.fail("不可重复下单");
        }


        // 5、以上条件满足，扣减库存，创建秒杀订单
        boolean success = iSeckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)      // 使用乐观锁的cas方法（compare and swap)实现超卖，只有数据库中的库存大于0才能创建成功
                .update();// 扣减库存，更新数据库

        if (!success) {
            return Result.fail("该券已售罄");
        }


        VoucherOrder voucherOrder = new VoucherOrder();
        long seckillOrderId = redisIdWorker.nextId("order");        // 给秒杀券生成全局唯一id
        voucherOrder.setId(seckillOrderId);     // 订单id
        voucherOrder.setVoucherId(voucherId);   // 券id
        voucherOrder.setUserId(userId);   // 用户id

        save(voucherOrder);     // 秒杀券订单保存至数据库

        // 6、返回成功信息：订单id
        return Result.ok(seckillOrderId);
    }

}
