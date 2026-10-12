package dev.r1ptt.workspace

/** A reply from before sleep/reconnect cannot reopen the next recovery window. */
internal class RecoveryFence {
    private var generation = 0L
    private var pending = false
    private var verified = false
    @Synchronized fun invalidate(): Long { pending = true; verified = false; return ++generation }
    /** A new check window that keeps the current access: replies from before it cannot confirm it. */
    @Synchronized fun renew(): Long = ++generation
    @Synchronized fun ticket(): Long = generation
    @Synchronized fun pending(): Boolean = pending
    @Synchronized fun verified(): Boolean = verified
    @Synchronized fun confirm(ticket: Long): Boolean {
        if (ticket != generation) return false
        pending = false; verified = true; return true
    }
    /** A failed check never reopens content or actions; only an authenticated current reply can. */
    @Synchronized fun offline(ticket: Long) {
        if (ticket == generation && !verified) pending = true
    }
}
