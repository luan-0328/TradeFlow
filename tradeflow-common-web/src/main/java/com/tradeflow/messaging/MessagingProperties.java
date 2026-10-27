package com.tradeflow.messaging;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 网络默认关闭；固定租约和有界重试属于协议，避免服务配置产生不一致语义。 */
@ConfigurationProperties("tradeflow.messaging")
public class MessagingProperties {
    private boolean enabled;
    private List<String> consumers = new ArrayList<>();
    private long pollIntervalMs = 1000;
    private int batchSize = 20;
    private long confirmTimeoutMs = 5000;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public List<String> getConsumers() { return consumers; }
    public void setConsumers(List<String> consumers) { this.consumers = consumers; }
    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long value) { this.pollIntervalMs = value; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int value) { this.batchSize = value; }
    public long getConfirmTimeoutMs() { return confirmTimeoutMs; }
    public void setConfirmTimeoutMs(long value) { this.confirmTimeoutMs = value; }
    void validate() {
        if (pollIntervalMs < 100 || batchSize < 1 || batchSize > 100 || confirmTimeoutMs < 100 || confirmTimeoutMs > 10000)
            throw new IllegalArgumentException("消息轮询/批量/确认等待配置超限");
        if (consumers == null || consumers.stream().anyMatch(c -> !EventCodec.CONSUMERS.contains(c)))
            throw new IllegalArgumentException("未知消息消费者");
    }
}
