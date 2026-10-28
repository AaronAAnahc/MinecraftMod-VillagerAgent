package com.github.AaronAA0721.villageragent.ai;

import java.util.UUID;

/** A task's alternative implementation. Estimates must not start work or spend resources. */
public interface TaskMethod<C> {
    String id();
    /** Remaining cost in score units; positive infinity means currently unavailable. */
    double estimate(C context);
    void start(C context, UUID attemptId);
    Update tick(C context);
    /** Idempotent: release only this attempt's resources. Never undo completed world changes. */
    void close(C context);

    enum Status { RUNNING, WAITING, DONE, FAILED }
    final class Update {
        public final Status status;
        public final boolean progressed, safeToSwitch;
        public Update(Status status, boolean progressed, boolean safeToSwitch) {
            this.status = status; this.progressed = progressed; this.safeToSwitch = safeToSwitch;
        }
        public static Update waiting(boolean progressed) { return new Update(Status.WAITING, progressed, true); }
    }
}
