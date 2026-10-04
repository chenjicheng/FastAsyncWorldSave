package com.fastasyncworldsave.test;

import java.util.concurrent.atomic.AtomicInteger;

/** Models third-party persistence callbacks, including Essential Commands. */
public final class SaveCallbacks {
    public static final AtomicInteger PLAYERS = new AtomicInteger();
    public static final AtomicInteger LEVELS = new AtomicInteger();
    public static volatile Thread playerThread;
    public static volatile Thread levelThread;

    private SaveCallbacks() {}
}
