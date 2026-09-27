package com.example

import com.example.data.ASK_MODELS
import com.example.data.AskEngine
import com.example.ui.MdBlock
import com.example.ui.inlineMarkdown
import com.example.ui.parseMarkdown
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * The engine spends the reader's money and speaks in the app's voice, so what
 * matters here is the shape of what goes out (model, tools, thread) and that a
 * question that fails leaves nothing behind to corrupt the next one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AskEngineTest {

    private lateinit var server: MockWebServer
    private val opus = ASK_MODELS.first { it.id == "claude-opus-5-5" }
    private val sonnet = ASK_MODELS.first { it.id == "claude-sonnet-5" }
    private val pack = JSONObject()
        .put("system_prompt", "RULES\n\nsummary")
        .put("games", JSONArray().put(JSONObject().put("opponent", "Iowa St.").put("result", "W")))
        .put("definitions", JSONObject().put("reb", "total rebounds"))

    @Before
    fun start() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun stop() {
        // A request cancelled mid-response can still be closing when the test
        // ends, and shutdown then throws; that is cleanup, not a failure.
        try {
            server.shutdown()
        } catch (e: java.io.IOException) {
        }
    }

    private fun engine() = AskEngine(
        client = OkHttpClient(),
        baseUrl = server.url("/").toString().trimEnd('/'),
        loadPack = { pack }
    )

    private fun reply(stop: String, vararg blocks: JSONObject, container: String? = "cont_1") =
        MockResponse().setBody(
            JSONObject()
                .put("type", "message")
                .put("role", "assistant")
                .put("stop_reason", stop)
                .put("content", JSONArray(blocks.toList()))
                .put("usage", JSONObject().put("input_tokens", 1000).put("output_tokens", 200)
                    .put("cache_read_input_tokens", 5000))
                .apply { container?.let { put("container", JSONObject().put("id", it)) } }
                .toString()
        )

    private fun text(t: String) = JSONObject().put("type", "text").put("text", t)

    private fun nextBody(): JSONObject =
        JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())

    @Test
    fun `a get_table call is answered from the season data and the code carries on`() = runBlocking {
        server.enqueue(
            reply(
                "tool_use",
                JSONObject().put("type", "server_tool_use").put("id", "srv_1").put("name", "code_execution")
                    .put("input", JSONObject().put("code", "df = await get_table({'table': 'games'})")),
                JSONObject().put("type", "tool_use").put("id", "tu_1").put("name", "get_table")
                    .put("input", JSONObject().put("table", "games"))
                    .put("caller", JSONObject().put("type", "code_execution_20260120").put("tool_id", "srv_1"))
            )
        )
        server.enqueue(reply("end_turn", text("KU are **1-0** against Iowa State.")))

        val answer = engine().ask("How did we do?", "sk-ant-test", opus, "2026-09-26")

        assertEquals("KU are **1-0** against Iowa State.", answer.text)
        assertEquals(listOf("df = await get_table({'table': 'games'})"), answer.code)
        assertNull(answer.error)
        assertTrue(answer.notes.last().startsWith("Claude Opus 5.5 · "))

        val first = server.takeRequest()
        assertEquals("/v1/messages?beta=true", first.path)
        assertEquals("sk-ant-test", first.getHeader("x-api-key"))
        assertEquals(AskEngine.FALLBACK_BETA, first.getHeader("anthropic-beta"))
        val body1 = JSONObject(first.body.readUtf8())
        assertEquals("claude-opus-5-5", body1.getString("model"))
        assertEquals("medium", body1.getJSONObject("output_config").getString("effort"))
        assertEquals("default", body1.getString("fallbacks"))
        assertEquals("RULES\n\nsummary", body1.getJSONArray("system").getJSONObject(0).getString("text"))
        assertFalse(body1.has("container"))
        val tools = body1.getJSONArray("tools")
        assertEquals("code_execution_20260120", tools.getJSONObject(0).getString("type"))
        assertEquals("code_execution_20260120",
            tools.getJSONObject(1).getJSONArray("allowed_callers").getString(0))
        assertEquals(
            "Today is 2026-09-26. How did we do?",
            body1.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text")
        )

        // The follow-up: same container, the assistant turn sent back as
        // received, and a user turn holding only the tool result.
        val body2 = nextBody()
        assertEquals("cont_1", body2.getString("container"))
        val messages = body2.getJSONArray("messages")
        assertEquals(3, messages.length())
        assertEquals("tu_1", messages.getJSONObject(1).getJSONArray("content").getJSONObject(1).getString("id"))
        val results = messages.getJSONObject(2).getJSONArray("content")
        assertEquals(1, results.length())
        val result = results.getJSONObject(0)
        assertEquals("tool_result", result.getString("type"))
        assertEquals("tu_1", result.getString("tool_use_id"))
        assertEquals("Iowa St.", JSONArray(result.getString("content")).getJSONObject(0).getString("opponent"))
    }

    @Test
    fun `the offered tables are the ones the pipeline writes`() {
        // The enum in the tool schema and the table names in ask_pack.py have
        // to agree, or Claude's code asks for something that does not exist.
        assertEquals(
            listOf("games", "periods", "ku_lines", "team_totals", "opponent_lines", "upcoming",
                "standings", "poll", "roster", "definitions"),
            AskEngine.TABLES
        )
        // Volleyball's tables must not have come along with the port.
        assertFalse(AskEngine.TABLES.contains("goals"))
        assertFalse(AskEngine.TABLES.contains("sets"))
    }

    @Test
    fun `an unknown table comes back as an error the code can read`() = runBlocking {
        server.enqueue(
            reply(
                "tool_use",
                JSONObject().put("type", "tool_use").put("id", "tu_9").put("name", "get_table")
                    .put("input", JSONObject().put("table", "injuries"))
            )
        )
        server.enqueue(reply("end_turn", text("That isn't in the data.")))
        engine().ask("Who is injured?", "sk-ant-test", sonnet, "2026-09-26")
        server.takeRequest()
        val body2 = nextBody()
        val result = body2.getJSONArray("messages").getJSONObject(2).getJSONArray("content").getJSONObject(0)
        assertTrue(result.getBoolean("is_error"))
        assertTrue(result.getString("content").contains("ku_lines"))
    }

    @Test
    fun `follow-ups keep the thread, and Sonnet is sent without fallbacks`() = runBlocking {
        val engine = engine()
        server.enqueue(reply("end_turn", text("First answer.")))
        server.enqueue(reply("end_turn", text("Second answer.")))
        engine.ask("One?", "sk-ant-test", sonnet, "2026-09-26")
        engine.ask("Two?", "sk-ant-test", sonnet, "2026-09-26")
        val first = server.takeRequest()
        assertNull(first.getHeader("anthropic-beta"))
        val body1 = JSONObject(first.body.readUtf8())
        assertFalse(body1.has("fallbacks"))
        assertFalse(body1.has("output_config"))
        val body2 = nextBody()
        assertEquals(3, body2.getJSONArray("messages").length())
        assertEquals("cont_1", body2.getString("container"))

        engine.reset()
        server.enqueue(reply("end_turn", text("Fresh.")))
        engine.ask("Three?", "sk-ant-test", sonnet, "2026-09-26")
        val body3 = nextBody()
        assertEquals(1, body3.getJSONArray("messages").length())
        assertFalse(body3.has("container"))
    }

    @Test
    fun `a rejected key is explained and leaves no trace in the thread`() = runBlocking {
        val engine = engine()
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
        )
        val failed = engine.ask("One?", "sk-ant-bad", opus, "2026-09-26")
        assertTrue(failed.error!!.contains("API key was rejected"))
        server.takeRequest()

        server.enqueue(reply("end_turn", text("Fine now.")))
        engine.ask("Two?", "sk-ant-good", opus, "2026-09-26")
        assertEquals(1, nextBody().getJSONArray("messages").length())
    }

    @Test
    fun `a refusal is said plainly and rolled back`() = runBlocking {
        val engine = engine()
        server.enqueue(reply("refusal"))
        val answer = engine.ask("Something off-topic", "sk-ant-test", opus, "2026-09-26")
        assertTrue(answer.error!!.contains("declined"))
        server.takeRequest()
        server.enqueue(reply("end_turn", text("OK.")))
        engine.ask("Back on topic", "sk-ant-test", opus, "2026-09-26")
        assertEquals(1, nextBody().getJSONArray("messages").length())
    }

    @Test
    fun `stopping cancels the request and rolls the question back`() = runBlocking {
        val engine = engine()
        server.enqueue(reply("end_turn", text("Too late.")).setBodyDelay(3, TimeUnit.SECONDS))
        val job = async { engine.ask("Slow?", "sk-ant-test", opus, "2026-09-26") }
        // This thread must stay free for the request to go out: wait by suspending.
        while (server.requestCount == 0) delay(20)
        job.cancel()
        val stopped = try {
            job.await(); false
        } catch (e: CancellationException) {
            true
        }
        assertTrue(stopped)
        server.takeRequest()
        server.enqueue(reply("end_turn", text("Quick.")))
        engine.ask("Quick?", "sk-ant-test", opus, "2026-09-26")
        assertEquals(1, nextBody().getJSONArray("messages").length())
    }

    @Test
    fun `server errors and cost lines read plainly`() {
        assertTrue(AskEngine.explainError(429, "").contains("rate limit"))
        assertTrue(AskEngine.explainError(529, "").contains("busy"))
        assertTrue(
            AskEngine.explainError(400, """{"error":{"message":"max_tokens too large"}}""")
                .endsWith("max_tokens too large")
        )
        // 10k input at $4, 100k cached at 5% of $4, 2k output at $20 = 4 + 2 + 4 = 10 cents.
        assertEquals(
            "Claude Opus 5.5 · about 10¢ (season data read from cache) · plus a little code-execution time",
            AskEngine.costLine(ASK_MODELS[0], longArrayOf(10_000, 0, 100_000, 2_000))
        )
        assertTrue(AskEngine.costLine(ASK_MODELS[2], longArrayOf(100, 0, 0, 10)).contains("under 1¢"))
    }

    @Test
    fun `answers render headings, lists, tables and code`() {
        val blocks = parseMarkdown(
            """
            ## Rebounding
            KU rebounded **better** at home.

            | Site | REB/G |
            |---|---|
            | Home | 36.4 |
            | Road | 33.1 |

            - one
            - two

            ```
            print(1)
            ```
            """.trimIndent()
        )
        assertEquals(MdBlock.Heading("Rebounding"), blocks[0])
        assertEquals(MdBlock.Paragraph("KU rebounded **better** at home."), blocks[1])
        assertEquals(
            MdBlock.Table(listOf("Site", "REB/G"), listOf(listOf("Home", "36.4"), listOf("Road", "33.1"))),
            blocks[2]
        )
        assertEquals(MdBlock.ListBlock(listOf("one", "two"), ordered = false), blocks[3])
        assertEquals(MdBlock.Code("print(1)"), blocks[4])
        assertEquals("KU shot better, a .447 clip", inlineMarkdown("KU shot **better**, a `.447` *clip*").text)
    }
}
