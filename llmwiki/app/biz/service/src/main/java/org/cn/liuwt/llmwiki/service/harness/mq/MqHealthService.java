package org.cn.liuwt.llmwiki.service.harness.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class MqHealthService {

    private static final Logger log = LoggerFactory.getLogger(MqHealthService.class);

    private volatile boolean available = true;
    private volatile long lastFailureAt = 0;

    @Value("${llmwiki.rocketmq.probe-cooldown-ms:60000}")
    private long probeCooldownMs;

    public boolean isAvailable() {
        return available;
    }

    public boolean shouldAttempt() {
        return available || System.currentTimeMillis() - lastFailureAt >= probeCooldownMs;
    }

    public void markSendSuccess() {
        if (!available) {
            available = true;
            log.info("RocketMQ send recovered, resuming MQ dispatch path");
        }
    }

    public void markSendFailed() {
        available = false;
        lastFailureAt = System.currentTimeMillis();
        log.warn("RocketMQ send failed, using local dispatch for the next {}ms", probeCooldownMs);
    }
}
