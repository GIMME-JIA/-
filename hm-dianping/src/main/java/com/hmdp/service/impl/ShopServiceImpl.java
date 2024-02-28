package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.TypeReference;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import io.netty.util.internal.StringUtil;
import org.springframework.context.annotation.Bean;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.ErrorMessageConstants.SHOP_INEXITENCE_ERROR;
import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    /**
     * 根据id查询店铺：redis缓存实现
     *
     * @param id
     * @return
     */
    public Result queryByid(Long id) {

//        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // TODO: 2023/11/24 bug: java.lang.ClassCastException: class cn.hutool.json.JSONObject cannot be cast to class com.hmdp.entity.Shop
        // Shop shop = cacheClient.queryWithLogicExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.SECONDS);
        Shop shop = queryWithLogicExpire(id);

        if (shop == null) {
            return Result.fail("店铺不能为空");
        }
        return Result.ok(shop);
    }

    /**
     * 互斥锁解决缓存击穿和缓存穿透问题
     *
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        Shop shop;
        String keyLock;

        boolean getLock;        // 判断锁是否获取成功
        do {
            // 1、先从redis看有没有该商铺信息
            String jsonShop = stringRedisTemplate.opsForValue().get(key);

            // 2、判断缓存是否命中
            if (StrUtil.isNotBlank(jsonShop)) {
                // 不为空，直接封装返回
                shop = JSONUtil.toBean(jsonShop, Shop.class);
                return shop;
            }

            // 3、这里判断命中的是否为空，防止缓存穿透
            if (jsonShop != null) {     // 进数据库查找之前，因为isBlank只有判断是字符串才会返回true，也有可能命中为null
                return null;
            }

            // 4、实现缓存重建
            // 4.1 尝试获取互斥锁
            try {
                keyLock = LOCK_SHOP_KEY + id;
                getLock = tryLock(keyLock);
                // 4.2 判断是否获取成功
                if (!getLock) {
                    // 4.3 失败，休眠，再重新获取（ 不用递归实现，改为循环
                    Thread.sleep(5000);
                    //                return queryWithMutex(id);
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        } while (!getLock);     // 获取锁失败，就等待重建缓存完成，然后重新获取数据
        // 4.4 成功，进数据库做缓存数据重建

        // 5、缓存未命中，进数据库查找
        shop = getById(id);
        // 模拟重建数据的延迟
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unLock(keyLock);
        }

        // 6、判断数据库是否存在信息
        if (shop == null) {
            // 6.1 数据库中没有，说明店铺不存在
            // 返回错误
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 6.2数据库中存在店铺信息，将其添加至redis缓存中，以便下次直接取
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonPrettyStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);    // 设置过期时间作超时剔除

        // 7、释放锁
        unLock(keyLock);
        // 8、返回
        return shop;
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


    /**
     * 将热key店铺信息写入redis缓存
     *
     * @param id
     * @param expireTime
     */
    public void saveShop2Redis(Long id, Long expireTime) throws InterruptedException {
        // 1、数据库查询数据
        Shop shop = getById(id);
        Thread.sleep(200);
        // 2、设置逻辑过期时间
        RedisData<Shop> redisData = new RedisData<>();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTime));   // 设置过期时间:当前时间 + 一定时间（单位：秒）
        // 3、 写入redis缓存
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }


    // 自定义线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 逻辑过期实现缓存击穿
     *
     * @param id
     * @return
     */
    public Shop queryWithLogicExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;
        Shop shop = null;
        // 1、先从redis看有没有该商铺信息
        String jsonShop = stringRedisTemplate.opsForValue().get(key);

        // 2、判断缓存是否命中
        if (StrUtil.isBlank(jsonShop)) {
            // 2.1 为空，直接返回null
            return shop;
        }

        // 3、缓存命中，判断是否过期
//        RedisData<Shop> redisData = JSONUtil.toBean(jsonShop, RedisData.class);
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
        RedisData<Shop> redisData = JSONUtil.toBean(jsonShop, new TypeReference<RedisData<Shop>>() {
        }, false);
        shop = redisData.getData();
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {  // 判断过期时间是否在当前时间之后
            // 没过期
            return shop;     // 直接返回
        }
        // 4、过期了
        // 4.1 尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);

        // 4.1.1 获取成功，再检测redis缓存是否过期，做一个doubleCheck
        if (isLock) {
            try {
                // 再检测redis缓存是否过期
                jsonShop = stringRedisTemplate.opsForValue().get(key);
                redisData = JSONUtil.toBean(jsonShop, new TypeReference<RedisData<Shop>>() {
                }, false);
                shop = redisData.getData();
                expireTime = redisData.getExpireTime();
                if (expireTime.isAfter(LocalDateTime.now())) {  // 判断过期时间是否在当前时间之后
                    // 没过期
                    //                unLock(lockKey);    // 释放锁，然后返回
                    return shop;
                }
                // 还是过期的，此时开启一个新线程完成数据重建，原线程返回旧数据
                CACHE_REBUILD_EXECUTOR.submit(() -> {
                            // 重建缓存
                            try {
                                this.saveShop2Redis(id, 20L);
                            } catch (InterruptedException e) {
                                throw new RuntimeException(e);
                            }
                        }
                );
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                unLock(lockKey);        // 最后释放锁
            }
        }
        // 4.1.2 获取失败，返回之前的商铺信息
        return shop;
    }

    /**
     * 根据商铺id更新商铺信息
     *
     * @param shop
     * @return
     */
    @Transactional      // 开启事务
    public Result update(Shop shop) {
        Long shopId = shop.getId();
        if (shopId == null) {
            return Result.fail("商铺id不能为空");
        }

        // 更新数据库信息
        updateById(shop);

        // 删除redis缓存相关信息
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shopId);

        return Result.ok();

    }

    /**
     * 根据店铺类型查询商铺
     *
     * @param typeId  店铺类型
     * @param current 分页参数，当前条目
     * @param x       经度
     * @param y       纬度
     * @return
     */
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if (x == null || y == null) {
            // 不需要坐标查询
            Page<Shop> page = query().eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 查询redis，按照距离排序，分页，结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;

        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs().includeDistance().limit(end)
                );

        if (results == null) {
            return Result.ok(Collections.emptyList());
        }

        // 解析出id
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();

        if (list.size() < from) {
            return Result.ok(Collections.emptyList());
        }
        // 1、截取from-end部分
        ArrayList<Long> ids = new ArrayList<>(list.size());
        HashMap<String, Distance> distanceMap = new HashMap<>(list.size());

        list.stream().skip(from).forEach(result -> {
            // 获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));

            // 获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);

        });

        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("order by field(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }

        return Result.ok(shops);
    }
}
