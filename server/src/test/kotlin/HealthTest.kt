package familytracker

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthTest {
    private val dbFile = Files.createTempFile("family-tracker-test", ".db").toFile()

    @AfterTest
    fun cleanUp() {
        dbFile.delete()
    }

    @Test
    fun `health returns ok as text plain`() = testApplication {
        application { module(Config(dbFile.absolutePath, "http://localhost", "test")) }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Text.Plain, response.contentType()?.withoutParameters())
        assertEquals("ok", response.bodyAsText())
    }
}
