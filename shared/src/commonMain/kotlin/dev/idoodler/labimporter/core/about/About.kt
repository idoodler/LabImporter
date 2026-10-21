package dev.idoodler.labimporter.core.about

/**
 * The funding platforms GitHub's `FUNDING.yml` supports, in display order.
 * [key] is the YAML key; [template] builds the public URL from an entry
 * (`%s`), mirroring GitHub's Sponsor button.
 */
enum class FundingPlatform(val key: String, val brandName: String, private val template: String) {
    GITHUB("github", "GitHub Sponsors", "https://github.com/sponsors/%s"),
    KO_FI("ko_fi", "Ko-fi", "https://ko-fi.com/%s"),
    BUY_ME_A_COFFEE("buy_me_a_coffee", "Buy Me a Coffee", "https://www.buymeacoffee.com/%s"),
    PATREON("patreon", "Patreon", "https://www.patreon.com/%s"),
    OPEN_COLLECTIVE("open_collective", "Open Collective", "https://opencollective.com/%s"),
    LIBERAPAY("liberapay", "Liberapay", "https://liberapay.com/%s/donate"),
    POLAR("polar", "Polar", "https://polar.sh/%s"),
    ISSUEHUNT("issuehunt", "IssueHunt", "https://issuehunt.io/r/%s"),
    TIDELIFT("tidelift", "Tidelift", "https://tidelift.com/funding/github/%s"),
    COMMUNITY_BRIDGE("community_bridge", "LFX Mentorship", "https://mentorship.lfx.linuxfoundation.org/project/%s"),
    LFX_CROWDFUNDING("lfx_crowdfunding", "LFX Crowdfunding", "https://crowdfunding.lfx.linuxfoundation.org/projects/%s"),
    THANKS_DEV("thanks_dev", "thanks.dev", "https://thanks.dev/%s"),
    CUSTOM("custom", "Custom", "%s"),
    ;

    /** The public URL for an entry: a username for most platforms, a full (maybe schemeless) URL for [CUSTOM]. */
    fun url(value: String): String =
        if (this == CUSTOM && "://" !in value) "https://$value" else template.replace("%s", value)
}

/** One funding destination from `FUNDING.yml`. */
data class FundingLink(val platform: FundingPlatform, val url: String) {
    /** Brand name, or for a custom link its host (`paypal.me`) — custom links have no brand. */
    val title: String
        get() = if (platform != FundingPlatform.CUSTOM) platform.brandName
        else url.substringAfter("://").substringBefore('/').removePrefix("www.").ifEmpty { url }
}

/** Where the app came from and how to reach the project — Settings' About and Support rows. */
object About {
    /** Funding links from a parsed `FUNDING.yml` ([parseFundingYaml]), in platform order. */
    fun fundingLinks(config: Map<String, List<String>>): List<FundingLink> =
        FundingPlatform.entries.flatMap { platform ->
            config[platform.key].orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.map { FundingLink(platform, platform.url(it)) }
        }

    /**
     * Reads GitHub's `FUNDING.yml`: `key: value`, `key: [a, b]` or a key
     * followed by `- item` lines. Comments, quotes and empty values are dropped.
     */
    fun parseFundingYaml(text: String): Map<String, List<String>> {
        val result = linkedMapOf<String, MutableList<String>>()
        var listKey: String? = null
        for (raw in text.lines()) {
            val line = raw.substringBefore(" #").let { if (it.trimStart().startsWith("#")) "" else it }.trimEnd()
            if (line.isBlank()) continue
            val trimmed = line.trim()
            if (trimmed.startsWith("- ") && listKey != null) {
                result.getValue(listKey) += unquote(trimmed.removePrefix("- "))
                continue
            }
            val key = trimmed.substringBefore(':', "").trim()
            if (key.isEmpty()) continue
            val value = trimmed.substringAfter(':').trim()
            val values = result.getOrPut(key) { mutableListOf() }
            listKey = null
            when {
                value.isEmpty() -> listKey = key
                value.startsWith("[") -> values += value.removePrefix("[").removeSuffix("]").split(',').map(::unquote)
                else -> values += unquote(value)
            }
        }
        return result.mapValues { (_, values) -> values.filter { it.isNotEmpty() && it != "~" && it != "null" } }
            .filterValues { it.isNotEmpty() }
    }

    private fun unquote(value: String): String = value.trim().removeSurrounding("\"").removeSurrounding("'").trim()

    /**
     * A git remote (`https://…`, with or without `.git`, or `git@host:owner/repo`)
     * as a browsable https URL; null when there is none.
     */
    fun repositoryWebUrl(remote: String?): String? {
        var value = remote?.trim().orEmpty()
        if (value.isEmpty()) return null
        if ("git@" in value) value = "https://" + value.substringAfter("git@").replaceFirst(':', '/')
        return value.removeSuffix(".git").removeSuffix("/")
    }

    /** The repository's "new issue" page, pre-filled with the build to help triage. */
    fun issueReportUrl(repositoryUrl: String, version: String, build: String, branch: String, commit: String): String {
        val body = "\n\n---\nVersion: $version ($build)\nBranch: $branch\nCommit: $commit"
        return "$repositoryUrl/issues/new?body=${percentEncode(body)}"
    }

    /** RFC 3986 percent-encoding of everything but unreserved characters. */
    private fun percentEncode(text: String): String = buildString {
        for (byte in text.encodeToByteArray()) {
            val c = byte.toInt() and 0xFF
            if (c.toChar().isLetterOrDigit() && c < 0x80 || c.toChar() in "-._~") append(c.toChar())
            else append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
        }
    }

    private const val HEX = "0123456789ABCDEF"
}
