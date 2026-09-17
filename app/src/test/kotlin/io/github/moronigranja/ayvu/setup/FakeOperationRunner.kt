package io.github.moronigranja.ayvu.setup

import io.github.moronigranja.ayvu.ops.OperationReporter
import io.github.moronigranja.ayvu.ops.OperationRunner
import io.github.moronigranja.ayvu.ops.OperationSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Host-side [OperationRunner] for the setup tests: records the spec (the id IS
 * the cancel address), runs the body on [scope], and captures body failures the
 * way the real runner posts a terminal notification instead of throwing.
 */
class FakeOperationRunner(
    private val scope: CoroutineScope,
    private val reporter: OperationReporter = OperationReporter { _, _ -> },
) : OperationRunner {
    val specs = CopyOnWriteArrayList<OperationSpec>()
    val cancelled = CopyOnWriteArrayList<String>()
    val failures = CopyOnWriteArrayList<Throwable>()

    override fun run(
        spec: OperationSpec,
        block: suspend (OperationReporter) -> Unit,
    ) {
        specs += spec
        scope.launch {
            try {
                block(reporter)
            } catch (t: Throwable) {
                failures += t
            }
        }
    }

    override fun cancel(id: String): Boolean {
        cancelled += id
        return specs.any { it.id == id }
    }
}
