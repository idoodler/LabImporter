package dev.idoodler.labimporter.ai

/**
 * Wording for the development stand-in. It exists in debug builds only and
 * is developer-facing, so it's plain English rather than a translated resource.
 */
data class StandInTexts(
    /** Unsupported-device screen: the opt-in button. */
    val use: String,
    /** Unsupported-device screen: what opting in means. */
    val explanation: String,
    /** Review screen: on every result the stand-in produced. */
    val reviewBanner: String,
    /** Settings: the AI row while the stand-in is active. */
    val active: String,
    /** Settings: switches back to the real model. */
    val useGemini: String,
    /** Paste screen: fills in a sample report. */
    val insertSample: String,
)
