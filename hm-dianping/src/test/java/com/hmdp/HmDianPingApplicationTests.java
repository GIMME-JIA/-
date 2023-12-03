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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.mockito.Mockito.*;

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
}
