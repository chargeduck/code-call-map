package com.github.chargeduck.codecallmap.agent;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 采样率控制：0-100（百分比），默认 100% 全量采样。
 *
 * <p>线程安全：使用 {@link ThreadLocalRandom} 无共享状态竞争。
 */
public final class Sampler {

    private final int sampleRate;

    /** @param sampleRate 采样率 0-100，越界值会被收敛到合法区间 */
    public Sampler(int sampleRate) {
        this.sampleRate = Math.max(0, Math.min(100, sampleRate));
    }

    /** 当前采样率（0-100）。 */
    public int sampleRate() {
        return sampleRate;
    }

    /** 判断本次调用是否应当采样记录。 */
    public boolean shouldSample() {
        if (sampleRate >= 100) {
            return true;
        }
        if (sampleRate <= 0) {
            return false;
        }
        return ThreadLocalRandom.current().nextInt(100) < sampleRate;
    }
}
