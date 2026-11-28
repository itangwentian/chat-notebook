package com.chatnotebook.app.update

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppUpdateTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun compareVersionsHandlesPrefixAndMissingSegments() {
        assertTrue(compareVersions("1.1.6", "1.1.5") > 0)
        assertTrue(compareVersions("v1.2.0", "1.1.9") > 0)
        assertTrue(compareVersions("1.10.0", "1.9.9") > 0)
        assertEquals(0, compareVersions("1.1.5", "v1.1.5"))
        assertEquals(0, compareVersions("1.1", "1.1.0"))
    }

    @Test
    fun latestReturnsNewerReleaseWithApkAsset() = runTest {
        val apkUrl = server.url("/apk")
        server.enqueue(
            MockResponse().setBody(
                """
                {"tag_name":"v1.1.6","assets":[
                  {"name":"notes.txt","browser_download_url":"https://example.com/notes.txt","size":1},
                  {"name":"chat_notebook-1.1.6.apk","browser_download_url":"$apkUrl","size":123}
                ]}
                """.trimIndent(),
            ),
        )

        val update = updater().latest("1.1.5")

        assertNotNull(update)
        assertEquals("1.1.6", update?.version)
        assertEquals(apkUrl.toString(), update?.apkUrl)
        assertEquals(123L, update?.sizeBytes)
    }

    @Test
    fun latestReturnsNullWhenReleaseIsNotNewer() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"tag_name":"v1.1.5","assets":[{"name":"app.apk","browser_download_url":"${server.url("/apk")}","size":1}]}""",
            ),
        )

        assertNull(updater().latest("1.1.5"))
    }

    @Test
    fun latestReturnsNullWhenEndpointIsUnavailable() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        assertNull(updater().latest("1.1.5"))
    }

    @Test
    fun latestReturnsNullWithoutApkAsset() = runTest {
        server.enqueue(MockResponse().setBody("""{"tag_name":"v1.1.6","assets":[]}"""))

        assertNull(updater().latest("1.1.5"))
    }

    @Test
    fun downloadWritesApkAndReportsProgress() = runTest {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val dir = Files.createTempDirectory("update").toFile()
        val progress = mutableListOf<Int>()
        val update = AppUpdate("1.1.6", server.url("/apk").toString(), bytes.size.toLong())

        val file = updater().download(update, dir) { progress += it }

        assertTrue(file.isFile)
        assertTrue(file.readBytes().contentEquals(bytes))
        assertEquals(100, progress.last())
        dir.deleteRecursively()
    }

    private fun updater() = AppUpdater(OkHttpClient(), server.url("/latest").toString())
}