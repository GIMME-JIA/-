package com.hmdp.utils;


import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    private StringRedisTemplate redisTemplate;

    public RedisIdWorker(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }


    // 从格林威治时间
    private static final long  BEGIN_TIMESTAMP = 1640995200L;   // LocalDateTime.of(2022, 1, 1, 0, 0, 0).toEpochSecond(ZoneOffset.UTC)

    // 全局唯一id时间戳的偏移量
    private static final int COUNT_BITS = 32;


    /**
     *  生成全局唯一id
     * @param keyPrefix 业务对应的key前缀
     * @return
     */
    public long nextId(String keyPrefix){
        // 1、生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowEpochSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowEpochSecond - BEGIN_TIMESTAMP;

        // 2、生成序列号
        // 2.1 获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2 自增长
        Long count = redisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        // 3、拼接返回
        return timeStamp << COUNT_BITS | count;
    }



}
