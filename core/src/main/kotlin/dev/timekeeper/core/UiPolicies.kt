package dev.timekeeper.core

/** Circular hours and minutes repeat logical values with unique physical row indices. */
object WheelValues {
    fun valueAt(index: Int, count: Int): Int {
        require(count > 0)
        return Math.floorMod(index, count)
    }
    fun initialIndex(value: Int, count: Int): Int {
        require(value in 0 until count)
        return count * 500 + value
    }
}

/** A system panel pauses tracking without discarding the notification being inspected. */
object NotificationPolicy {
    fun target(current: String?, previous: String?, suspended: Boolean, blockedContext: Boolean = false): String? =
        if (current != null) current else if (suspended || blockedContext) previous else null
}

enum class BlockHandoff { HOLD_CURTAIN, OPEN_EXPLANATION, RELEASE_CURTAIN }

/** Never uncover a blocked package merely because the notification shade takes focus. */
object BlockHandoffPolicy {
    fun decide(target: String, foreground: String?, isHome: Boolean, stillBlocked: Boolean): BlockHandoff = when {
        !stillBlocked -> BlockHandoff.RELEASE_CURTAIN
        foreground == null || foreground == target -> BlockHandoff.HOLD_CURTAIN
        isHome -> BlockHandoff.OPEN_EXPLANATION
        else -> BlockHandoff.RELEASE_CURTAIN
    }
}
