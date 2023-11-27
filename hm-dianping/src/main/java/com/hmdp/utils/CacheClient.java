package com.hmdp.utils;

import cn.hutool.core.lang.TypeReference;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * 封装用于操作redis缓存的工具类
 */
@Component      // 交给ioc管理
@Slf4j
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    // 自定义线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 需要定义构造方法来注入StringRedisTemplate
     *
     * @param stringRedisTemplate
     */
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 在redis中存入key，并设置过期时间
     *
     * @param key
     * @param value
     * @param time
     * @param timeUnit
     */
    public void set(String key, Object value, Long time, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    public <T> void setWithLogicExpire(String key, T value, Long time, TimeUnit timeUnit) {
        RedisData<T> redisData = new RedisData<>();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 在redis中存入key，逻辑过期
     *
     */
    public <R, ID> R queryWithLogicExpire(
            String profixKey, ID id, Class<R> type, Function<ID, R> dbFallBack, Long time, TimeUnit timeUnit) {

        String key = profixKey + id;

        // 1、先从redis看有没有信息
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2、判断缓存是否命中
        if (StrUtil.isBlank(json)) {        // 未命中，
            return null;
        }


        // 3、缓存命中，判断是否过期

        /*
        TypeReference 的作用：
        TypeReference 是一个抽象类，它允许在运行时保留泛型信息。通过创建一个匿名的子类实例，可以绕过泛型擦除，使得在运行时能够正确地反映出实际的泛型类型。
        匿名子类的实现：
        通过使用 new TypeReference<RedisData<Shop>>() {} 创建了一个匿名子类的实例。由于 Java 的匿名子类会保留超类的泛型信息，这里就达到了在运行时保留 RedisData<Shop> 泛型信息的目的。
        JSON 转换：
        JSONUtil.toBean(jsonShop, new TypeReference<RedisData<Shop>>() {}, false) 方法接受一个 JSON 字符串 jsonShop，以及一个 TypeReference 对象。
        通过传递具体的 TypeReference 对象，JSON 转换工具能够利用其中的泛型信息来正确地还原对象的类型。
        第三个参数 false：
        有些 JSON 转换库允许用户选择是否缓存泛型信息。在这里，第三个参数 false 表示不缓存泛型信息，这可能有助于避免一些潜在的性能问题。
         */
        RedisData<R> redisData = JSONUtil.toBean(json, new TypeReference<RedisData<R>>() {
        }, false);

        R data = redisData.getData();
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {  // 判断过期时间是否在当前时间之后
            // 没过期
            return data;     // 直接返回
        }
        // 4、过期了
        // 4.1 尝试获取互斥锁
        String lockKey = LOCK_KEY + id;
        boolean isLock = tryLock(lockKey);

        // 4.1.1 获取成功，再检测redis缓存是否过期，做一个doubleCheck
        if (isLock) {

            // 再检测redis缓存是否过期
            json = stringRedisTemplate.opsForValue().get(key);
            redisData = JSONUtil.toBean(json, new TypeReference<RedisData<R>>() {
            }, false);
            data = redisData.getData();
            expireTime = redisData.getExpireTime();
            if (expireTime.isAfter(LocalDateTime.now())) {  // 判断过期时间是否在当前时间之后
                // 没过期
                unLock(lockKey);    // 释放锁，然后返回
                return data;
            }
            try {
                // 还是过期的，此时开启一个新线程完成数据重建，原线程返回旧数据
                CACHE_REBUILD_EXECUTOR.submit(() -> {
                    // 重建缓存
                    R sqlData = dbFallBack.apply(id);
                    this.setWithLogicExpire(key, sqlData, time, timeUnit);
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                unLock(lockKey);        // 最后释放锁
            }
        }
        // 4.1.2 获取失败，返回之前的商铺信息

        return data;
    }

    public <R, ID> R queryWithPassThrough(
            String keyProfix, ID id, Class<R> type, Function<ID, R> dbFallBack, Long time, TimeUnit timeUnit) {
        String key = keyProfix + id;

        // 1、先从redis看有没有该商铺信息
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2、判断缓存是否命中
        if (StrUtil.isNotBlank(json)) {        // 命中，且对象不为空
            return JSONUtil.toBean(json, type);
        }
        // 3、判断命中的是不是空值
        if (json != null) {
            return null;
        }


        // 4、查询数据库
        R r = dbFallBack.apply(id);     // 这里封装成函数式接口，由调用者处理这里的方法执行

        // 5、判断数据库是否存在该数据
        if (r == null) {
            // 将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        } else {     // 数据库存在该数据
            // 将存在的数据写入redis

            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(r), time, timeUnit);
            return r;
        }
    }


    public boolean tryLock(String key) {
        // setIfAbsent就是setnx（不存在才设置），相当于只能设置一次，以此来实现锁的功能
        //
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        // 因为Boolean是包装类，其可以为null，但是基本数据类型不能为null，防止空指针异常，这里需要手动拆箱返回
        return flag != null && flag;
    }

    public void unLock(String key) {
        stringRedisTemplate.delete(key);
    }


}
