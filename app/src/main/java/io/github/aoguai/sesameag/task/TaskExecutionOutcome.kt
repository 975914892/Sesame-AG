package io.github.aoguai.sesameag.task

import io.github.aoguai.sesameag.task.common.TaskFlowExecutionState
import io.github.aoguai.sesameag.task.common.TaskFlowRunResult
import kotlinx.coroutines.asContextElement
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

enum class TaskExecutionOutcome {
    SUCCESS,
    FAILED,
    SKIPPED,
    CANCELLED,
    DEFERRED,
}

/** 每次模块执行独立收集结果；协程切换线程时保留归属，不传给独立调度的子任务。 */
internal class TaskExecutionReport {
    companion object {
        private val activeReport = ThreadLocal<TaskExecutionReport?>()

        fun current(): TaskExecutionReport? = activeReport.get()
    }

    val context = activeReport.asContextElement(this)
    private val flows = ConcurrentHashMap<Triple<TaskFlowExecutionState, String, String>, TaskFlowRunResult>()
    private val failed = AtomicBoolean(false)
    private val exception = AtomicBoolean(false)

    val hasException: Boolean
        get() = exception.get()

    fun recordException() {
        exception.set(true)
    }

    fun recordFlow(state: TaskFlowExecutionState, module: String, flow: String, result: TaskFlowRunResult) {
        flows[Triple(state, module, flow)] = result
        if (result.failureCount > 0) failed.set(true)
    }

    fun outcome(): TaskExecutionOutcome = when {
        hasException || failed.get() -> TaskExecutionOutcome.FAILED
        flows.values.any { it.interrupted } -> TaskExecutionOutcome.CANCELLED
        flows.values.any { !it.completed } -> TaskExecutionOutcome.DEFERRED
        else -> TaskExecutionOutcome.SUCCESS
    }
}
