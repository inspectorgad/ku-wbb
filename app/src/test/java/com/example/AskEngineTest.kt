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
import com.example.platform.newHttp
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import com.example.json.JSONArray
import com.example.json.JSONObject
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
        http = newHttp(connectSeconds = 10, readSeconds = 30),
        baseUrl = server.url("/").toString().trimEnd('/'),
        loadPack = { pack }
    )

    private fun reply(stop: String, vararg blocks: JSONObject, container: String? = "cont_1") =
        MockResponse().setBody(
            JSONObject()
                .put("type", "message")
                .put("role", "assistant")
                .put("model", "claude-opus-5-5")
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
        // Two: the sandbox and get_table. Web search is declared only when the
        // reader turns it on, and this question did not.
        assertEquals(2, tools.length())
        assertEquals("code_execution_20260120", tools.getJSONObject(0).getString("type"))
        assertEquals("code_execution_20260120",
            tools.getJSONObject(1).getJSONArray("allowed_callers").getString(0))
        assertEquals(
            "Today is 2026-09-26. Web search is off for this question. How did we do?",
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
        //
        // The list is written in three places — ask_pack.py, docs/ask.js and
        // AskEngine.kt — and pinned in two: here, and in
        // scripts/test_ask_pack.py, which is the one that actually checks the
        // three files against each other. Adding a table means touching all
        // five; this assertion is the backstop that catches forgetting.
        assertEquals(
            listOf("games", "periods", "ku_lines", "team_totals", "opponent_lines",
                "opponent_roster", "upcoming", "standings", "poll", "roster", "definitions",
                "team_records", "big12_results", "d1_results"),
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

    // --- Web search ---------------------------------------------------------
    //
    // The same cases the volleyball app's tests cover, in basketball's names.
    // Each one stands for something that went wrong against the real API.

    private val thinking = JSONObject().put("type", "thinking").put("thinking", "").put("signature", "sig")
    private fun search(id: String, q: String) =
        JSONObject().put("type", "server_tool_use").put("id", id).put("name", "web_search")
            .put("input", JSONObject().put("query", q))
    private fun results(id: String) =
        JSONObject().put("type", "web_search_tool_result").put("tool_use_id", id)
            .put("content", JSONArray().put(JSONObject().put("type", "web_search_result")
                .put("url", "https://huskers.com/r").put("title", "Roster").put("encrypted_content", "enc")))
    private fun code(id: String) =
        JSONObject().put("type", "server_tool_use").put("id", id).put("name", "code_execution")
            .put("input", JSONObject().put("code", "await get_table({'table': 'opponent_roster'})"))
    private fun tableCall(id: String, codeId: String) =
        JSONObject().put("type", "tool_use").put("id", id).put("name", "get_table")
            .put("input", JSONObject().put("table", "games"))
            .put("caller", JSONObject().put("type", "code_execution_20260120").put("tool_id", codeId))
    private fun cited(t: String, vararg urls: String) = text(t).put(
        "citations",
        JSONArray(urls.map {
            JSONObject().put("type", "web_search_result_location").put("url", it)
                .put("title", "Page").put("encrypted_index", "i").put("cited_text", "...")
        })
    )
    private fun roles(body: JSONObject): List<String> {
        val m = body.getJSONArray("messages")
        return (0 until m.length()).map { m.getJSONObject(it).getString("role") }
    }

    @Test
    fun `with web search on, the tool is offered and cited pages are numbered and listed`() = runBlocking {
        val engine = engine()
        server.enqueue(
            MockResponse().setBody(
                JSONObject()
                    .put("type", "message").put("role", "assistant").put("model", "claude-opus-5-5")
                    .put("stop_reason", "end_turn")
                    .put("content", JSONArray(listOf(
                        text("Here is what I found."),
                        search("srv_1", "Nebraska women's basketball injuries"),
                        results("srv_1"),
                        text("Nebraska "),
                        cited("lost their point guard to injury", "https://huskers.com/a"),
                        text(" and "),
                        cited("start a freshman.", "https://huskers.com/b", "https://huskers.com/a")
                    )))
                    .put("usage", JSONObject().put("input_tokens", 10_000).put("output_tokens", 1_000)
                        .put("server_tool_use", JSONObject().put("web_search_requests", 2)))
                    .toString()
            )
        )
        val answer = engine.ask("Any news on Nebraska?", "sk-ant-test", opus, "2026-11-12", webSearch = true)
        assertEquals(
            "Here is what I found.\n\nNebraska lost their point guard to injury [1] and start a freshman. [2] [1]",
            answer.text
        )
        assertEquals(listOf("https://huskers.com/a", "https://huskers.com/b"), answer.sources.map { it.url })
        assertEquals(listOf(1, 2), answer.sources.map { it.number })
        assertEquals(listOf("Nebraska women's basketball injuries"), answer.searches)
        // 10k at $4 + 1k at $20 = 6 cents, plus 2 searches at 1 cent.
        assertTrue(answer.notes.last(), answer.notes.last().startsWith("Claude Opus 5.5 · about 8.0¢ · 2 web searches included"))

        val body = nextBody()
        val web = body.getJSONArray("tools").getJSONObject(2)
        assertEquals("web_search_20250305", web.getString("type"))
        assertEquals(AskEngine.MAX_SEARCHES, web.getInt("max_uses"))
        assertTrue(body.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0)
            .getString("text").contains("Web search is on for this question."))

        // Turned off for a follow-up: the tool stays declared, since the tool
        // list can't change partway through, and the question says search is off.
        server.enqueue(reply("end_turn", text("From the tables only.")))
        engine.ask("And their rebounding?", "sk-ant-test", opus, "2026-11-12", webSearch = false)
        val body2 = nextBody()
        assertEquals(3, body2.getJSONArray("tools").length())
        val messages = body2.getJSONArray("messages")
        // The search results go back exactly as received, encrypted content and all.
        val result = messages.getJSONObject(1).getJSONArray("content").getJSONObject(2)
        assertEquals("enc", result.getJSONArray("content").getJSONObject(0).getString("encrypted_content"))
        assertTrue(messages.getJSONObject(2).getJSONArray("content").getJSONObject(0)
            .getString("text").contains("Web search is off for this question."))
    }

    @Test
    fun `a page without a title is listed by its address, and partial answers carry their sources`() = runBlocking {
        val noTitle = text("Reported out.").put(
            "citations",
            JSONArray().put(
                JSONObject().put("type", "web_search_result_location").put("url", "https://x.example/a")
                    .put("title", JSONObject.NULL).put("encrypted_index", "i").put("cited_text", "...")
            )
        )
        // A cited paragraph, then Claude's code asks for a table before finishing.
        server.enqueue(
            reply(
                "tool_use",
                noTitle,
                JSONObject().put("type", "tool_use").put("id", "tu_2").put("name", "get_table")
                    .put("input", JSONObject().put("table", "games"))
            )
        )
        server.enqueue(reply("end_turn", text("Done.")))
        val partials = mutableListOf<Pair<String, List<String>>>()
        val answer = engine().ask("News?", "sk-ant-test", opus, "2026-11-12", webSearch = true) { _, text, sources ->
            partials += text to sources.map { it.title }
        }
        assertEquals(listOf("https://x.example/a"), answer.sources.map { it.title })
        assertTrue(partials.any { (text, titles) -> text.contains("[1]") && titles == listOf("https://x.example/a") })
    }

    @Test
    fun `the tool list never changes partway through a conversation`() = runBlocking {
        val engine = engine()
        // On from the first question, off later, with no search ever made: still declared.
        server.enqueue(reply("end_turn", text("One.")))
        server.enqueue(reply("end_turn", text("Two.")))
        engine.ask("One?", "sk-ant-test", opus, "2026-11-12", webSearch = true)
        engine.ask("Two?", "sk-ant-test", opus, "2026-11-12", webSearch = false)
        assertEquals(3, nextBody().getJSONArray("tools").length())
        assertEquals(3, nextBody().getJSONArray("tools").length())

        // Off from the first question, then turned on: a new conversation, with a note.
        engine.reset()
        server.enqueue(reply("end_turn", text("Three.")))
        engine.ask("Three?", "sk-ant-test", opus, "2026-11-12", webSearch = false)
        assertEquals(2, nextBody().getJSONArray("tools").length())
        assertFalse(engine.startsNewConversation(false))
        assertTrue(engine.startsNewConversation(true))
        server.enqueue(reply("end_turn", text("Four.")))
        val four = engine.ask("Four?", "sk-ant-test", opus, "2026-11-12", webSearch = true)
        assertEquals(AskEngine.NEW_CONVERSATION_NOTE, four.notes.first())
        val body = nextBody()
        assertEquals(3, body.getJSONArray("tools").length())
        assertEquals(1, body.getJSONArray("messages").length())
        assertFalse(body.has("container"))
        assertFalse(engine.startsNewConversation(true))
    }

    @Test
    fun `a paused answer goes back as it is when it ends on a search not yet run, and is nudged otherwise`() = runBlocking {
        val engine = engine()
        // 1. Paused on a search not yet run: Anthropic's documented continuation.
        server.enqueue(reply("pause_turn", text("Checking the news."), thinking, search("srv_1", "Nebraska injuries")))
        // 2. Paused after a finished search, with thinking at the end: cut back and nudged.
        server.enqueue(reply("pause_turn", results("srv_1"), search("srv_2", "Nebraska basketball injuries"),
            results("srv_2"), text("Nebraska's line-up"), thinking))
        server.enqueue(reply("end_turn", text("has no reported injuries.")))

        val answer = engine.ask("Any injuries for Nebraska?", "sk-ant-test", opus, "2026-11-12", webSearch = true)
        assertNull(answer.error)
        assertTrue(answer.text, answer.text.startsWith("Checking the news."))
        assertTrue(answer.text, answer.text.endsWith("has no reported injuries."))
        assertEquals(listOf("Nebraska injuries", "Nebraska basketball injuries"), answer.searches)

        nextBody()
        val second = nextBody()
        assertEquals(listOf("user", "assistant"), roles(second))
        assertEquals(3, second.getJSONArray("messages").getJSONObject(1).getJSONArray("content").length())

        val third = nextBody()
        assertEquals(listOf("user", "assistant", "assistant", "user"), roles(third))
        val kept = third.getJSONArray("messages").getJSONObject(2).getJSONArray("content")
        assertEquals(4, kept.length()) // the trailing thinking is gone
        assertEquals("text", kept.getJSONObject(3).getString("type"))
        assertEquals(AskEngine.CONTINUE_NOTE, third.getJSONArray("messages").getJSONObject(3)
            .getJSONArray("content").getJSONObject(0).getString("text"))
        // The nudge isn't one of the reader's questions.
        assertEquals(1, engine.turns)

        assertEquals(0, AskEngine.finishedPart(JSONArray().put(search("a", "q"))).length())
        assertEquals(2, AskEngine.finishedPart(JSONArray().put(search("a", "q")).put(results("a"))).length())
        assertTrue(AskEngine.endsOnWaitingServerTool(JSONArray().put(text("x")).put(search("a", "q"))))
        assertFalse(AskEngine.endsOnWaitingServerTool(JSONArray().put(search("a", "q")).put(thinking)))
    }

    @Test
    fun `an empty or cut-short reply never leaves the thread unable to take a follow-up`() = runBlocking {
        val engine = engine()
        // A nudged pause, then an empty end_turn: nothing empty is kept.
        server.enqueue(reply("pause_turn", text("Nebraska lists no injuries.")))
        server.enqueue(reply("end_turn"))
        engine.ask("Injuries?", "sk-ant-test", opus, "2026-11-12", webSearch = true)
        // Cut short with code half made: only the finished part is kept.
        server.enqueue(reply("max_tokens", text("Working it out."), code("srv_c")))
        engine.ask("Their rebounding?", "sk-ant-test", opus, "2026-11-12", webSearch = true)
        server.enqueue(reply("end_turn", text("Done.")))
        engine.ask("And ours?", "sk-ant-test", opus, "2026-11-12", webSearch = true)

        repeat(3) { nextBody() }
        val last = nextBody().getJSONArray("messages")
        for (i in 0 until last.length()) {
            assertTrue("message $i is empty", last.getJSONObject(i).getJSONArray("content").length() > 0)
        }
        // The cut-short reply kept its text and lost the half-made code run.
        val cut = last.getJSONObject(last.length() - 2).getJSONArray("content")
        assertEquals(listOf("text"), (0 until cut.length()).map { cut.getJSONObject(it).getString("type") })
        assertEquals(1, AskEngine.settledPart(JSONArray().put(text("x")).put(tableCall("tu", "srv_c"))).length())
    }

    @Test
    fun `a web search asked for while Claude's code waits on a table sets the answer back once`() = runBlocking {
        val engine = engine()
        server.enqueue(reply("tool_use", thinking, search("srv_w", "Nebraska basketball injuries"),
            code("srv_c"), tableCall("tu_9", "srv_c"), container = "cont_a"))
        server.enqueue(reply("tool_use", thinking, code("srv_d"), tableCall("tu_10", "srv_d"), container = "cont_b"))
        server.enqueue(reply("end_turn", text("No injuries reported."), container = "cont_b"))

        val answer = engine.ask("Injuries?", "sk-ant-test", opus, "2026-11-12", webSearch = true)
        assertNull(answer.error)
        assertEquals("No injuries reported.", answer.text)
        val first = nextBody().getJSONArray("messages").getJSONObject(0).getJSONArray("content")
            .getJSONObject(0).getString("text")
        assertTrue(first, first.contains(AskEngine.SEARCH_APART))

        // Asked again: the question and the app's note, in a fresh sandbox.
        val second = nextBody()
        assertEquals(listOf("user", "user"), roles(second))
        assertEquals(AskEngine.SEPARATE_NOTE, second.getJSONArray("messages").getJSONObject(1)
            .getJSONArray("content").getJSONObject(0).getString("text"))
        assertFalse(second.has("container"))

        val third = nextBody()
        assertEquals(listOf("user", "user", "assistant", "user"), roles(third))
        assertEquals("cont_b", third.getString("container"))
        assertEquals(1, engine.turns)

        // Only once: a second such reply goes back as Anthropic's guide says.
        val again = engine()
        server.enqueue(reply("tool_use", search("w1", "q"), code("c1"), tableCall("t1", "c1")))
        server.enqueue(reply("tool_use", search("w2", "q"), code("c2"), tableCall("t2", "c2")))
        server.enqueue(reply("end_turn", text("Done.")))
        again.ask("Injuries?", "sk-ant-test", opus, "2026-11-12", webSearch = true)
        repeat(2) { nextBody() }
        assertEquals(listOf("user", "user", "assistant", "user"), roles(nextBody()))
    }

    @Test
    fun `a rejected request says which step failed, how it ended and what the reply before it held`() = runBlocking {
        server.enqueue(reply("pause_turn", text("Looking.")))
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                JSONObject().put("type", "error").put("error", JSONObject().put("type", "invalid_request_error")
                    .put("message", "Something about the request.")).toString()
            )
        )
        val answer = engine().ask("News?", "sk-ant-test", opus, "2026-11-12", webSearch = true)
        val error = answer.error!!
        assertTrue(error, error.startsWith("Anthropic couldn't accept the request: Something about the request."))
        assertTrue(error, error.endsWith(
            "(For the developer: request 2 of this answer; it ended with a user message: text. " +
                "Reply before it (claude-opus-5-5, pause_turn, 1 block): text.)"))
        assertEquals(
            "thinking, server_tool_use web_search (waiting), server_tool_use code_execution (waiting), " +
                "tool_use get_table via code_execution_20260120",
            AskEngine.describe(JSONArray(listOf(thinking, search("w", "q"), code("c"), tableCall("t", "c"))))
        )
    }
}
