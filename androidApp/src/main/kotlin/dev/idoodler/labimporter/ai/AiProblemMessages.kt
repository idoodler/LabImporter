package dev.idoodler.labimporter.ai

import androidx.annotation.StringRes
import dev.idoodler.labimporter.R

/** What the user is told for each problem — iOS's wording wherever iOS has the same case. */
@get:StringRes
val AiProblem.messageRes: Int
    get() = when (this) {
        AiProblem.DOCUMENT_TOO_LONG -> R.string.ai_problem_too_long
        AiProblem.BUSY -> R.string.ai_problem_busy
        AiProblem.QUOTA_EXCEEDED -> R.string.ai_problem_quota
        AiProblem.BACKGROUND -> R.string.ai_problem_background
        AiProblem.NOT_ENOUGH_STORAGE -> R.string.ai_problem_storage
        AiProblem.DOWNLOAD_FAILED -> R.string.ai_problem_download
        AiProblem.UNAVAILABLE -> R.string.ai_problem_unavailable
        AiProblem.LOAD_FAILED -> R.string.ai_problem_load_failed
        AiProblem.FAILED -> R.string.error_parse
    }
