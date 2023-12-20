package com.hmdp.utils;

import cn.hutool.core.lang.UUID;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import javax.annotation.Resource;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {

    private String name;        // 不同的name代表不同业务有不同的锁，有调用者指定


    private StringRedisTemplate stringRedisTemplate;

    private static final String KEY_PREFIX = "lock:";

    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";         // 用uuid生成锁的唯一标识,解决集群环境下锁超时释放引发的线程安全问题


    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    // 再静态代码块中初始化脚本
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));     // 指定脚本位置
        UNLOCK_SCRIPT.setResultType(Long.class);        // 指定返回值类型
    }

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 尝试获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);

        return Boolean.TRUE.equals(success);        // 自动拆箱有风险,因为可能返回空指针,Hutool实现方式也是这样
    }

    /**
     * 释放锁,lua脚本实现
     */
    @Override
    public void unlock() {
        // 调用lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),           // key就是锁的key
                ID_PREFIX + Thread.currentThread().getId()          // 值就是线程的uuid+threadId
        );
    }
   /* @Override
    public void unlock() {
        // 1、获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 1、获取锁标识
        String lockValue = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        if (threadId.equals(lockValue)){
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }*/
}
