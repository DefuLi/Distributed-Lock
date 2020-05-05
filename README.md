# 实现基于Redis的分布式锁
实现了基于Redis的分布式锁，具有互斥性、锁超时性、支持阻塞性和非阻塞性、可重入性、高可用性。

## 项目简介
这是一个秒杀的项目，stock变量存储在redis服务器中，如果对主要代码加synchronized锁，在单台web服务器中部署该项目的话，可以保证线程安全。<br>
但是如果采用分布式的部署方式，我采用的是nginx代理服务器，在本地开了两个web服务，区别是端口不一样，这样就可以模拟在本地部署两个独立的web服务器的场景，这个场景下就不是线程安全的，因为synchronized是jvm级别的锁机制，分布式的部署是不同操作系统进程级别的。<br>
nginx在这里的作用是负载均衡，分发请求到不同的web服务器。<br>

## 分布式锁的五大特性
要想实现一个分布式锁，需要具备互斥性、锁超时性、支持阻塞性和非阻塞性、可重入性、高可用性。<br>
互斥性：setnx全称为set if not exist，也就是说如果key不存在，设置val，返回1，如果key存在，不进行任何操作，返回0。
但是这样有个问题，如果在else中发生异常，导致程序中断，那么redis数据库中就会一直有flag的key，从而造成死锁。
解决这个问题，需要加上try，finally语句。但是加上try，finally语句，运行到try语句中如果发生断电等事故，仍然会面临前面出现的问题。<br>
<br>
锁超时性：为了解决上述两种情况下面临的死锁问题，可以对flag的key设置超时时间。也就是说在一段时间内，锁是有效的，过了一段时间锁就无效了。
假设stock=100，同时也有100个请求过来，当某个请求在占用锁进行订单处理时，其它请求过来就只能return了，虽然我们有正好的请求对应正好的stock，但是由于这种机制会导致stock有剩余。一种解决的方案是让没有锁的请求进行阻塞。<br>
<br>
支持阻塞性和非阻塞性：在之前的解决方案中，如果有请求占用了分布式锁，直接返回，这是非阻塞的。如果我们要保证阻塞，可以使用while循环去抢占锁，也就是自旋锁。自旋锁有个问题就是会一直占用cpu的资源，对性能要求较高。<br>
<br>
可重入性：一个线程可以多次获取锁，就是可重入的，为了保证可重入性，在获取锁时，如果redis服务器中还没有key，那么设置并返回true，如果redis中已经有key了，那么直接返回true。<br>
<br>
高可用性：使用异步线程在线程执行逻辑代码期间，该异步线程一直对redis服务器中的key进行时间设置，也就是续命。<br>

## 代码
获取锁和释放锁
```java
package com.redisson.distributedlock;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 阻塞型的分布式锁
 */
@Component
public class RedisLockImp implements RedisLock {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    ThreadLocal<String> threadLocal = new ThreadLocal<>();

    @Override
    public boolean tryLock(String key, long timeout, TimeUnit unit) {
        boolean lock = false;
        if (threadLocal.get() == null) {
            // 异步续命
            Thread thread = new Thread(){
                @Override
                public void run() {
                    while (true) {
                        try {
                            Thread.sleep(10000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        stringRedisTemplate.expire(key, timeout, unit);
                    }
                }
            };

            // lock为true 代表无请求占用锁
            // lock为false 代表锁被占用
            String uuid = UUID.randomUUID().toString();
            threadLocal.set(uuid);
            lock = stringRedisTemplate.opsForValue().setIfAbsent(key, uuid, timeout, unit);
            while (!lock) {
                // 自旋
                lock = stringRedisTemplate.opsForValue().setIfAbsent(key, uuid, timeout, unit);
            }
            thread.start();  // 拿到锁的线程才会启动异步续命
        } else if (stringRedisTemplate.opsForValue().get(key).equals(threadLocal.get())) {
            lock = true;
        }
        return lock;
    }

    @Override
    public void releaseLock(String key) {
        if (stringRedisTemplate.opsForValue().get(key).equals(threadLocal.get())) {
            stringRedisTemplate.delete(key);
        }
    }
}

```

下单逻辑代码
```java
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

```

