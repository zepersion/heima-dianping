package com.hmdp.utils;

public interface ILock {

    public boolean tryLock(long timeoutsec);
    public void unLock();
}
