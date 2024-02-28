package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.RedisIdWorker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;



@SpringBootTest
class HmDianPingApplicationTests {


    @Resource
    private RedisIdWorker redisIdWorker;




    @Test
    void testIdWorker() throws InterruptedException {
        ExecutorService es = Executors.newFixedThreadPool(500);
        CountDownLatch latch = new CountDownLatch(300);
        // 定义一个可运行的对象task，包含一个Lambda表达式
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id=" + id);
            }
            // 调用latch对象的countDown方法，减少计数器1
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("Time : " + (end - begin));

    }


    @Resource
    private ShopServiceImpl shopService;

    @Test
    public void testSaveShop2Redis() throws InterruptedException {


        // Setting the expire time
        Long expireTime = 3600L;


        // Calling the method under test
        shopService.saveShop2Redis(1L, expireTime);


    }


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void loadShopDate(){
        // 1、查询商铺信息
        List<Shop> list = shopService.list();

        // 2、把店铺分组，按照typeid分组，typeid一致的放到一个集合
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));

        // 3、分批完成写入redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            Long typeId = entry.getKey();
            String key = "shop:geo:" + typeId;

            List<Shop> value = entry.getValue();
            ArrayList<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());

            for (Shop shop : value) {
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(),shop.getY())
                ));
            }

            stringRedisTemplate.opsForGeo().add(key,locations);
        }
    }

}
