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
