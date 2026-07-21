/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.torrent.client

import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.minus
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.plus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

interface ConnectivityAware {
    val isConnected: Boolean

    /**
     * Register a transform callback for connectivity state.
     *
     * When [isConnected] changed from [prev][StateTransform.prev] to [next][StateTransform.next], [block] is called.
     *
     * If [prev][StateTransform.prev] == [next][StateTransform.next], [block] will call when [isConnected] transform to it.
     */
    fun registerStateTransform(prev: Boolean, next: Boolean, block: () -> Unit): StateTransform

    fun unregister(key: StateTransform)

    class StateTransform(val prev: Boolean, val next: Boolean)
}

class DefaultConnectivityAware(
    scope: CoroutineScope,
    private val isConnectedFlow: StateFlow<Boolean>
) : ConnectivityAware {
    private val registry: MutableStateFlow<PersistentMap<ConnectivityAware.StateTransform, () -> Unit>> =
        MutableStateFlow(persistentHashMapOf())

    override val isConnected: Boolean
        get() = isConnectedFlow.value

    init {
        scope.launch {
            isConnectedFlow.runningFold(Array(2) { isConnected }) { acc, value ->
                acc[0] = acc[1]
                acc[1] = value
                acc
            }.collect { (prev, next) ->
                registry.value.forEach { (transform, block) ->
                    if ((transform.prev == transform.next && next == transform.next) ||
                        transform.prev == prev && transform.next == next
                    ) {
                        block()
                    }
                }
            }
        }
    }

    override fun registerStateTransform(
        prev: Boolean, next: Boolean, block: () -> Unit
    ): ConnectivityAware.StateTransform {
        val registry = registry
        val key = ConnectivityAware.StateTransform(prev, next)

        while (true) {
            val prevValue = registry.value
            val nextValue = prevValue.plus(key to block)
            if (registry.compareAndSet(prevValue, nextValue)) {
                return key
            }
        }
    }

    override fun unregister(key: ConnectivityAware.StateTransform) {
        registry.update { map -> map.minus(key) }
    }
}

/**
 * Register a transform callback with `prev == next`.
 *
 * @see ConnectivityAware.registerStateTransform
 */
fun ConnectivityAware.registerState(state: Boolean, block: () -> Unit): ConnectivityAware.StateTransform {
    return registerStateTransform(state, state, block)
}