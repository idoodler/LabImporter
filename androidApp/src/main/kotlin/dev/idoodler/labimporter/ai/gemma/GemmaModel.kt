package dev.idoodler.labimporter.ai.gemma

/**
 * The one model LabImporter downloads, for phones that don't offer Gemini
 * Nano to apps: Google's open Gemma 4 E2B in the LiteRT-LM format. Pinned to
 * one revision of one file, so every download is the same bytes — checked
 * against [SHA256] before the app uses it. This URL is the only address the
 * app ever contacts (see [SystemModelDownloads]); quality checked with
 * `evaluation/` (see its README) before any change here.
 */
object GemmaModel {
    const val NAME = "Gemma 4 E2B"
    const val FILE_NAME = "gemma-4-E2B-it.litertlm"
    const val REPOSITORY = "litert-community/gemma-4-E2B-it-litert-lm"
    const val REVISION = "a790a852d433991c0c0a41c1154cf9b3850300fa"
    const val HOST = "huggingface.co"
    const val URL = "https://$HOST/$REPOSITORY/resolve/$REVISION/$FILE_NAME"
    const val SIZE_BYTES = 2_588_147_712L
    const val SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"

    /** The model page (card, license, benchmarks) for "Learn more". */
    const val PAGE_URL = "https://$HOST/$REPOSITORY"
    const val LICENSE = "Apache 2.0"
    const val LICENSE_URL = "https://www.apache.org/licenses/LICENSE-2.0"

    /** Room to keep free beyond the model itself, so the download never fills the phone. */
    const val SPACE_MARGIN_BYTES = 500L * 1024 * 1024
}
