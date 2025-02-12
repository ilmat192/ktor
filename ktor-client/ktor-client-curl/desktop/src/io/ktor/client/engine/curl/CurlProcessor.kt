/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.curl

import io.ktor.client.engine.curl.internal.*
import io.ktor.util.collections.*
import io.ktor.utils.io.*
import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import kotlin.coroutines.*
import kotlin.native.concurrent.*

/**
 * Only set in curl worker thread
 */
@ThreadLocal
private lateinit var curlApi: CurlMultiApiHandler

internal class CurlProcessor(
    override val coroutineContext: CoroutineContext
) : CoroutineScope {
    private val worker: Worker = Worker.start()
    private val responseConsumers: ConcurrentMap<CurlRequestData, CompletableDeferred<CurlSuccess>> = ConcurrentMap()
    private val activeRequests = atomic(0)

    init {
        worker.execute(TransferMode.SAFE, { }) {
            curlApi = CurlMultiApiHandler()
        }.consume { }
    }

    suspend fun executeRequest(request: CurlRequestData, callContext: CoroutineContext): CurlSuccess {
        val deferred = CompletableDeferred<CurlSuccess>()
        responseConsumers[request] = deferred

        val easyHandle = worker.execute(TransferMode.SAFE, { request.apply { makeShared() } }, ::curlSchedule).result

        val requestCleaner = callContext[Job]!!.invokeOnCompletion { cause ->
            if (cause == null) return@invokeOnCompletion
            cancelRequest(easyHandle, cause)
        }

        try {
            activeRequests.incrementAndGet()

            while (deferred.isActive) {
                val completedResponses = poll()
                while (completedResponses.state == FutureState.SCHEDULED) delay(100)
                processPoll(completedResponses.result)
            }

            return deferred.await()
        } finally {
            requestCleaner.dispose()
        }
    }

    fun close() {
        worker.execute(TransferMode.SAFE, { }) { curlApi.close() }.consume { }
        worker.requestTermination()
    }

    private fun poll(): Future<List<CurlResponseData>> =
        worker.execute(TransferMode.SAFE, { }, { pollCompleted() })

    private fun processPoll(result: List<CurlResponseData>) {
        result.forEach { response ->
            val task = responseConsumers[response.request]!!
            when (response) {
                is CurlSuccess -> task.complete(response)
                is CurlFail -> task.completeExceptionally(response.cause)
            }
            responseConsumers.remove(response.request)
        }

        activeRequests.update { it - result.size }
    }

    private fun cancelRequest(easyHandle: EasyHandle, cause: Throwable) {
        worker.execute(TransferMode.SAFE, { (easyHandle to cause).apply { makeShared() } }) {
            curlApi.cancelRequest(it.first, it.second)
        }.consume { }
    }
}

internal fun curlSchedule(request: CurlRequestData): EasyHandle {
    return curlApi.scheduleRequest(request)
}

internal fun pollCompleted(): List<CurlResponseData> = curlApi.pollCompleted(100).apply { makeShared() }
