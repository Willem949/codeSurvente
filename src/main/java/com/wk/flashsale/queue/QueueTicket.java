package com.wk.flashsale.queue;

/**
 * @param position nombre de personnes devant soi (0 une fois admis)
 */
public record QueueTicket(String token, Status status, long position) {

    public enum Status {
        WAITING,
        ADMITTED
    }
}
