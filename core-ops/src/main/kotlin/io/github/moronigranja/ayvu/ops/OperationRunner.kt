package io.github.moronigranja.ayvu.ops

/** The user-facing surface a long operation appears on (the implementation maps
 *  each member to its Android notification channel). */
enum class OperationChannel { DOWNLOADS, IMPORT }

/** One long operation. [id] is the stable key: starting an id that is already
 *  running cancels the running one first (supersede), and [cancel] addresses it. */
data class OperationSpec(
    val id: String,
    val channel: OperationChannel,
    val title: String,
    val cancelLabel: String = "Stop",
)

/** Progress sink handed to the operation body. [text] is the notification's
 *  content text; a null [percent] renders an indeterminate bar. */
fun interface OperationReporter {
    fun report(
        text: String,
        percent: Int?,
    )
}

/** Indeterminate convenience: [report] with a null percent. */
fun OperationReporter.report(text: String) = report(text, null)

interface OperationRunner {
    /** Starts [block] as a long operation; supersedes a running operation with
     *  the same [spec] id. Returns immediately. */
    fun run(
        spec: OperationSpec,
        block: suspend (OperationReporter) -> Unit,
    )

    /** Cancels the running operation [id]; false when none is running. Cancelling
     *  never posts a failure notification. */
    fun cancel(id: String): Boolean
}
