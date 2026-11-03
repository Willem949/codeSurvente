package com.wk.flashsale.queue;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = {"flashsale.redis.enabled", "flashsale.queue.enabled"}, havingValue = "true")
class QueueAdmissionJob {

    private final WaitingRoom waitingRoom;

    QueueAdmissionJob(WaitingRoom waitingRoom) {
        this.waitingRoom = waitingRoom;
    }

    @Scheduled(fixedRateString = "${flashsale.queue.tick-interval:PT1S}")
    void tick() {
        waitingRoom.admitNextBatch();
    }
}
