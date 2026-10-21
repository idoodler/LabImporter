package dev.idoodler.labimporter.core

import dev.idoodler.labimporter.core.about.About
import dev.idoodler.labimporter.core.about.FundingPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AboutTest {
    @Test
    fun fundingYamlInEveryShapeGitHubAccepts() {
        val yaml = """
            # These are supported funding model platforms
            github: [octocat, "surftocat"]
            patreon: # Replace with a single Patreon username
            liberapay: idoodler
            ko_fi: ~
            custom:
              - paypal.me/octocat
              - 'https://www.example.org/donate'
        """.trimIndent()
        val config = About.parseFundingYaml(yaml)
        assertEquals(mapOf("github" to listOf("octocat", "surftocat"), "liberapay" to listOf("idoodler"),
            "custom" to listOf("paypal.me/octocat", "https://www.example.org/donate")), config)
        val links = About.fundingLinks(config)
        assertEquals(listOf(FundingPlatform.GITHUB, FundingPlatform.GITHUB, FundingPlatform.LIBERAPAY, FundingPlatform.CUSTOM, FundingPlatform.CUSTOM),
            links.map { it.platform })
        assertEquals("https://liberapay.com/idoodler/donate", links[2].url)
        assertEquals("https://paypal.me/octocat", links[3].url)
        assertEquals(listOf("GitHub Sponsors", "GitHub Sponsors", "Liberapay", "paypal.me", "example.org"), links.map { it.title })
    }

    @Test
    fun remotesBecomeWebUrls() {
        assertEquals("https://github.com/idoodler/LabImporter", About.repositoryWebUrl("git@github.com:idoodler/LabImporter.git"))
        assertEquals("https://github.com/idoodler/LabImporter", About.repositoryWebUrl("https://github.com/idoodler/LabImporter.git\n"))
        assertNull(About.repositoryWebUrl(""))
        assertNull(About.repositoryWebUrl(null))
    }

    @Test
    fun issueLinksCarryTheBuild() {
        val url = About.issueReportUrl("https://github.com/o/r", "1.0", "7", "main", "abc123")
        assertTrue(url.startsWith("https://github.com/o/r/issues/new?body="))
        assertTrue("Version%3A%201.0%20%287%29" in url)
        assertTrue("%0A---%0A" in url)
    }
}
