package com.hmdp.utils;

public interface ILock {

    /**
     * 尝试获取锁，非阻塞式实现，只获取一次，获取失败不会等待，直接返回
     * @param timeoutSec 过期时间
     * @return true代表成功，false代表失败
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     *
     */
    void unlock();

}
