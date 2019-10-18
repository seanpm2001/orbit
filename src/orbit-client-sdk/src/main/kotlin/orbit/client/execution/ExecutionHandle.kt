/*
 Copyright (C) 2015 - 2019 Electronic Arts Inc.  All rights reserved.
 This file is part of the Orbit Project <https://www.orbit.cloud>.
 See license in LICENSE.
 */

package orbit.client.execution

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import mu.KotlinLogging
import orbit.client.addressable.Addressable
import orbit.client.addressable.AddressableImplDefinition
import orbit.client.addressable.AddressableInterfaceDefinition
import orbit.client.addressable.InvocationSystem
import orbit.client.addressable.MethodInvoker
import orbit.client.net.Completion
import orbit.client.util.DeferredWrappers
import orbit.shared.addressable.AddressableInvocation
import orbit.shared.addressable.AddressableReference
import orbit.shared.exception.CapacityExceededException
import orbit.util.concurrent.SupervisorScope
import orbit.util.di.jvm.ComponentContainer
import orbit.util.time.Clock
import orbit.util.time.stopwatch
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicLong

internal class ExecutionHandle(
    val instance: Addressable,
    val reference: AddressableReference,
    val interfaceDefinition: AddressableInterfaceDefinition,
    val implDefinition: AddressableImplDefinition,
    componentContainer: ComponentContainer
) {
    private val clock: Clock by componentContainer.inject()
    private val supervisorScope: SupervisorScope by componentContainer.inject()
    private val invocationSystem: InvocationSystem by componentContainer.inject()
    private val addressableBufferCount = 128

    private val logger = KotlinLogging.logger { }

    val createdTime = clock.currentTime

    @Volatile
    var deactivateNextTick = false

    private val lastActivityAtomic = AtomicLong(createdTime)
    val lastActivity get() = lastActivityAtomic.get()

    private val channel = Channel<EventType>(addressableBufferCount)

    init {
        /* if (instance is AbstractAddressable) {
             instance.context = AddressableContext(
                 reference = reference,
                 runtime = runtimeContext
             )
         }*/
    }

    fun activate(): Completion =
        CompletableDeferred<Any?>().also {
            sendEvent(EventType.ActivateEvent(it))
        }

    fun deactivate(): Completion =
        CompletableDeferred<Any?>().also {
            sendEvent(EventType.DeactivateEvent(it))
        }

    fun invoke(
        invocation: AddressableInvocation
    ): Completion =
        CompletableDeferred<Any?>().also {
            sendEvent(EventType.InvokeEvent(invocation, it))
        }


    private fun sendEvent(eventType: EventType) {
        if (!channel.offer(eventType)) {
            val errMsg = "Buffer capacity exceeded (>${addressableBufferCount}) for $reference"
            logger.error(errMsg)
            throw CapacityExceededException(errMsg)
        }
    }

    private suspend fun onActivate() {
        logger.debug { "Activating $reference..." }
        stopwatch(clock) {
            implDefinition.onActivateMethod?.also {
                DeferredWrappers.wrapCall(it.method.invoke(instance)).await()
            }
        }.also { (elapsed, _) ->
            logger.debug { "Activated $reference in ${elapsed}ms. " }
        }
    }

    private suspend fun onInvoke(invocation: AddressableInvocation): Any? {
        lastActivityAtomic.set(clock.currentTime)
        try {
            return MethodInvoker.invokeDeferred(instance, invocation.method, invocation.args).await()
        } catch (ite: InvocationTargetException) {
            throw ite.targetException
        }
    }

    private suspend fun onDeactivate() {
        logger.debug { "Deactivating $reference..." }
        stopwatch(clock) {
            implDefinition.onDeactivateMethod?.also {
                DeferredWrappers.wrapCall(it.method.invoke(instance)).await()
            }

            worker.cancel()
            channel.close()
            drainChannel()

        }.also { (elapsed, _) ->
            logger.debug { "Deactivated $reference in ${elapsed}ms." }
        }
    }

    private suspend fun drainChannel() {
        for (event in channel) {
            if (event is EventType.InvokeEvent) {
                logger.warn(
                    "Received invocation which can no longer be handled locally. " +
                            "Rerouting... ${event.invocation}"
                )

                invocationSystem.sendInvocation(event.invocation)
            }
        }
    }

    private val worker = supervisorScope.launch {
        for (event in channel) {
            try {
                when (event) {
                    is EventType.ActivateEvent -> onActivate()
                    is EventType.InvokeEvent -> onInvoke(event.invocation)
                    is EventType.DeactivateEvent -> onDeactivate()
                }.also {
                    event.completion.complete(it)
                }
            } catch (t: Throwable) {
                event.completion.completeExceptionally(t)
            }
        }
    }

    private sealed class EventType {
        abstract val completion: Completion

        data class ActivateEvent(override val completion: Completion) : EventType()
        data class InvokeEvent(val invocation: AddressableInvocation, override val completion: Completion) : EventType()
        data class DeactivateEvent(override val completion: Completion) : EventType()
    }
}