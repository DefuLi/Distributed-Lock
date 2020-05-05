package com.redisson.distributedlock;

import org.redisson.Redisson;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.concurrent.TimeUnit;

@Controller
public class ShopCartController {
//    @Autowired
//    private Redisson redisson;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedisLockImp redisLock;

    private String flag = "locks";

    @RequestMapping("/submitorder")
    @ResponseBody
    public String submitOrder() {
        // 使用redisson第三方库可以更稳定的实现分布式锁功能
        // redisson.getLock(flag);

        // 该分布式锁可以保证互斥性 锁超时 线程阻塞
        if(redisLock.tryLock(flag, 30, TimeUnit.SECONDS)) {
            int stock = Integer.valueOf(stringRedisTemplate.opsForValue().get("stock"));
            if (stock > 0) {
                // todo 下单
                stock--;
                stringRedisTemplate.opsForValue().set("stock", String.valueOf(stock));
                System.out.println("扣减成功，库存stock:" + stock);
            } else {
                System.out.println("扣减失败，库存不足");
            }
        }
        redisLock.releaseLock(flag);
        return "end";
    }

/*    @Bean
    public Redisson configfun() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:6379").setDatabase(0);
        return (Redisson)Redisson.create(config);
    }*/
}
