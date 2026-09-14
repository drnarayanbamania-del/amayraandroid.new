package com.amayra.maya.events

/** Event types observed by the cognitive core (mirrors reference EventBus). */
enum class EventType { MESSAGE, NOTIFICATION, TOOL_RESULT, SYSTEM, SOS, MACRO, STANDBY }
enum class EventSource { USER, UI, NOTIFICATION_LISTENER, TOOL, SYSTEM, AUTOMATION }
enum class EventPriority { LOW, NORMAL, HIGH, CRITICAL }

data class MayaaEvent(
    val id: Long = System.nanoTime(),
    val type: EventType,
    val source: EventSource,
    val priority: EventPriority = EventPriority.NORMAL,
    val packageName: String? = null,
    val title: String? = null,
    val text: String? = null,
    val payload: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/** Lightweight in-process pub/sub for subsystem events. */
class EventBus {
    private val _events = kotlinx.coroutines.flow.MutableSharedFlow<MayaaEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val events: kotlinx.coroutines.flow.SharedFlow<MayaaEvent> = _events

    fun publish(e: MayaaEvent) { _events.tryEmit(e) }
}
