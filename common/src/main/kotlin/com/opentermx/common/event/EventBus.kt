package com.opentermx.common.event

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

object EventBus {
    private val log = LoggerFactory.getLogger(EventBus::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _events = MutableSharedFlow<AppEvent>(
        replay = 0,
        extraBufferCapacity = 256,
    )

    val events: Flow<AppEvent> = _events.asSharedFlow()

    inline fun <reified T : AppEvent> eventsOf(): Flow<T> = events.filterIsInstance<T>()

    @JvmStatic
    fun publish(event: AppEvent) {
        if (!_events.tryEmit(event)) {
            log.warn("EventBus buffer full; falling back to suspending emit for {}", event::class.simpleName)
            scope.launch { _events.emit(event) }
        }
    }

    @JvmStatic
    fun subscribe(listener: EventListener): Subscription {
        val job = scope.launch {
            _events.collect { event -> listener.onEvent(event) }
        }
        return Subscription { job.cancel() }
    }
}

fun interface EventListener {
    fun onEvent(event: AppEvent)
}

fun interface Subscription : AutoCloseable {
    override fun close()
}