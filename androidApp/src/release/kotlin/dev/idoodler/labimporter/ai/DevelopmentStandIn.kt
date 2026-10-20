package dev.idoodler.labimporter.ai

/** Release builds have no stand-in: without Gemini Nano the app says the device isn't supported. */
object DevelopmentStandIn {
    val texts: StandInTexts? = null

    val sampleReport: String? = null

    fun create(): OnDeviceAi? = null
}
