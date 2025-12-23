package com.example.lanchat.service;

public class LamportClock {

    private long counter = 0;

    public synchronized long tick() {
        counter += 1;
        return counter;
    }

    public synchronized long observe(long remoteClock) {
        counter = Math.max(counter, remoteClock) + 1;
        return counter;
    }
}

