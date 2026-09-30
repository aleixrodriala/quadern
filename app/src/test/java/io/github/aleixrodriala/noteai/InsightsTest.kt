package io.github.aleixrodriala.noteai

import io.github.aleixrodriala.noteai.auth.ChatGptAuth
import io.github.aleixrodriala.noteai.insights.ChatCompletionsSummarizer
import io.github.aleixrodriala.noteai.insights.ChatGptSummarizer
import io.github.aleixrodriala.noteai.insights.InsightsPrompt
import io.github.aleixrodriala.noteai.insights.ResponsesStream
import io.github.aleixrodriala.noteai.transcription.ProviderId
import io.github.aleixrodriala.noteai.transcription.SttException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class InsightsPromptTest {
    @Test fun parsesPlainJson() {
        val r = InsightsPrompt.parse("""{"title":"Plan the week","summary":"Plan to run twice a week.","tags":["Health","#running"]}""")
        assertEquals("Plan the week", r.title)
        assertEquals("Plan to run twice a week.", r.summary)
        assertEquals(listOf("health", "running"), r.tags)
    }

    @Test fun toleratesFencesChatterAndTagString() {
        val r = InsightsPrompt.parse("Sure!\n```json\n{\"title\": \"\\\"Trip ideas.\\\"\", \"summary\": \"\", \"tags\": \"travel, family\"}\n```")
        assertEquals("Trip ideas", r.title)
        assertEquals("", r.summary)
        assertEquals(listOf("travel", "family"), r.tags)
    }

    @Test fun unreadableAnswerIsTransient() {
        try {
            InsightsPrompt.parse("I can't do that")
            fail()
        } catch (e: SttException.Transient) {
            // retried later
        }
    }

    @Test fun bulletsBecomeProse() {
        val s = InsightsPrompt.cleanSummary("- Call the bank\n- **Book** flights\n- Pay rent")
        assertEquals("Call the bank Book flights Pay rent", s)
    }

    @Test fun keepsParagraphs() {
        assertEquals("One.\n\nTwo.", InsightsPrompt.cleanSummary("One.\n\n\n\nTwo.  "))
    }

    @Test fun cleansTags() {
        val t = InsightsPrompt.cleanTags(listOf(" #Work ", "work", "kids, school", "", "a".repeat(40), "extra", "more"))
        assertEquals(listOf("work", "kids school", "extra"), t)
    }

    @Test fun cutsLongTitlesAtAWord() {
        val t = InsightsPrompt.cleanTitle("word ".repeat(30))
        assertTrue(t.length <= 81)
        assertTrue(t.endsWith("word…"))
    }

    @Test fun topTagsByUse() {
        assertEquals(listOf("work", "family", "health"), InsightsPrompt.topTags(listOf("work,health", "family,work", "family", "work")))
    }

    @Test fun inputKeepsStartAndEndOfHugeTranscripts() {
        val text = "a".repeat(100_000) + "b".repeat(100_000)
        val input = InsightsPrompt.input(text, listOf("x", "y"))
        assertTrue(input.startsWith("Existing tags: x, y\n\nTranscript:\naaa"))
        assertTrue(input.endsWith("bbb"))
        assertTrue("[…]" in input)
        assertTrue(input.length < InsightsPrompt.MAX_TRANSCRIPT_CHARS + 200)
    }
}

class ResponsesStreamTest {
    private fun events(vararg json: String) = json.flatMap { listOf("event: x", "data: $it", "") }.iterator()

    @Test fun collectsFinalText() {
        val text = ResponsesStream.collect(
            events(
                """{"type":"response.created","response":{"status":"in_progress"}}""",
                """{"type":"response.output_text.delta","delta":"{\"ti"}""",
                """{"type":"response.output_text.delta","delta":"tle\"}"}""",
                """{"type":"response.output_text.done","text":"{\"title\"}"}""",
                """{"type":"response.completed","response":{"status":"completed","output":[]}}""",
            )
        )
        assertEquals("{\"title\"}", text)
    }

    @Test fun fallsBackToDeltas() {
        val text = ResponsesStream.collect(
            events(
                """{"type":"response.output_text.delta","delta":"ab"}""",
                """{"type":"response.output_text.delta","delta":"c"}""",
                """{"type":"response.completed","response":{}}""",
            )
        )
        assertEquals("abc", text)
    }

    @Test fun truncatedStreamIsTransient() {
        try {
            ResponsesStream.collect(events("""{"type":"response.output_text.delta","delta":"ab"}"""))
            fail()
        } catch (e: SttException.Transient) {
            // retried later
        }
    }

    @Test fun contentFilterIsPermanent() {
        try {
            ResponsesStream.collect(events("""{"type":"response.incomplete","response":{"incomplete_details":{"reason":"content_filter"}}}"""))
            fail()
        } catch (e: SttException.Permanent) {
            // not retried
        }
    }

    @Test fun failedResponseIsTransient() {
        try {
            ResponsesStream.collect(events("""{"type":"response.failed","response":{"error":{"code":"server_error","message":"boom"}}}"""))
            fail()
        } catch (e: SttException.Transient) {
            assertTrue(e.message!!.contains("boom"))
        }
    }
}

class SummarizerHttpTest {
    private lateinit var server: MockWebServer
    private val answer = """{"title":"Bike repair","summary":"Plan to fix the bike on Sunday.","tags":["errands"]}"""

    private fun sse(text: String): MockResponse {
        val done = kotlinx.serialization.json.JsonPrimitive(text).toString()
        val body = "event: response.output_text.done\ndata: {\"type\":\"response.output_text.done\",\"text\":$done}\n\n" +
            "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{}}\n\n"
        return MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream").body(body).build()
    }

    private fun json(code: Int, body: String) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        ChatGptSummarizer.workingModel = null
    }

    @After fun tearDown() = server.close()

    @Test fun chatGptStreamsAndSendsTheRightRequest() = runBlocking {
        server.enqueue(sse(answer))
        val s = ChatGptSummarizer({ ChatGptAuth.Credentials("tok", "acct") }, "gpt-6-luna", false, server.url("/r").toString())
        val r = s.summarize("I need to fix the bike on Sunday.", listOf("errands"))
        assertEquals("Bike repair", r.title)
        assertEquals(listOf("errands"), r.tags)
        val req = server.takeRequest()
        assertEquals("Bearer tok", req.headers["Authorization"])
        assertEquals("acct", req.headers["ChatGPT-Account-Id"])
        val body = req.body!!.utf8()
        assertTrue("\"stream\":true" in body && "\"store\":false" in body && "\"model\":\"gpt-6-luna\"" in body)
        assertTrue("json_schema" in body && "Existing tags: errands" in body)
    }

    @Test fun chatGptRefreshesOnceOn401() = runBlocking {
        server.enqueue(json(401, """{"detail":"expired"}"""))
        server.enqueue(sse(answer))
        val forced = mutableListOf<Boolean>()
        val s = ChatGptSummarizer({ forced += it; ChatGptAuth.Credentials(if (it) "new" else "old", "acct") }, "gpt-6-luna", false, server.url("/r").toString())
        assertEquals("Bike repair", s.summarize("text", emptyList()).title)
        assertEquals(listOf(false, true), forced)
        assertEquals("Bearer old", server.takeRequest().headers["Authorization"])
        assertEquals("Bearer new", server.takeRequest().headers["Authorization"])
    }

    @Test fun chatGptWalksPastARetiredModel() = runBlocking {
        server.enqueue(json(400, """{"detail":"The 'gpt-old' model is not supported when using Codex with a ChatGPT account."}"""))
        server.enqueue(sse(answer))
        val s = ChatGptSummarizer({ ChatGptAuth.Credentials("tok", "acct") }, "gpt-old", false, server.url("/r").toString())
        assertEquals("Bike repair", s.summarize("text", emptyList()).title)
        assertTrue("\"model\":\"gpt-old\"" in server.takeRequest().body!!.utf8())
        assertTrue("\"model\":\"gpt-6-luna\"" in server.takeRequest().body!!.utf8())
    }

    @Test fun chatGptExplicitModelHasNoSubstitutes() = runBlocking {
        server.enqueue(json(400, """{"detail":"The 'mine' model is not supported when using Codex with a ChatGPT account."}"""))
        val s = ChatGptSummarizer({ ChatGptAuth.Credentials("tok", "acct") }, "mine", true, server.url("/r").toString())
        try {
            s.summarize("text", emptyList())
            fail()
        } catch (e: SttException.Permanent) {
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun chatCompletionsFallsBackToJsonMode() = runBlocking {
        server.enqueue(json(400, """{"error":{"message":"response_format json_schema is not supported by this model"}}"""))
        server.enqueue(json(200, """{"choices":[{"message":{"role":"assistant","content":${kotlinx.serialization.json.JsonPrimitive(answer)}}}]}"""))
        val s = ChatCompletionsSummarizer(ProviderId.CUSTOM, server.url("/v1").toString(), null, "local", null)
        assertEquals("Bike repair", s.summarize("text", emptyList()).title)
        assertTrue("json_schema" in server.takeRequest().body!!.utf8())
        val second = server.takeRequest()
        assertEquals("/v1/chat/completions", second.url.encodedPath)
        assertTrue("\"json_object\"" in second.body!!.utf8())
    }

    @Test fun chatCompletionsReadsChunkedContent() = runBlocking {
        val chunks = """[{"type":"thinking","thinking":[]},{"type":"text","text":${kotlinx.serialization.json.JsonPrimitive(answer)}}]"""
        server.enqueue(json(200, """{"choices":[{"message":{"role":"assistant","content":$chunks}}]}"""))
        val s = ChatCompletionsSummarizer(ProviderId.MISTRAL, server.url("/v1").toString(), "k", "mistral-small-latest", "none")
        assertEquals("Bike repair", s.summarize("text", emptyList()).title)
        val body = server.takeRequest().body!!.utf8()
        assertTrue("\"reasoning_effort\":\"none\"" in body)
    }
}

/**
 * Talks to the real ChatGPT endpoint. Runs only with NOTEAI_LIVE_TOKEN and NOTEAI_LIVE_ACCOUNT set
 * (an access token and account id from a ChatGPT sign-in).
 */
class ChatGptLiveTest {
    @Test fun summarizesARealNote() = runBlocking {
        val token = System.getenv("NOTEAI_LIVE_TOKEN")
        val account = System.getenv("NOTEAI_LIVE_ACCOUNT")
        assumeTrue(!token.isNullOrBlank() && !account.isNullOrBlank())
        val s = ChatGptSummarizer({ ChatGptAuth.Credentials(token!!, account!!) }, ProviderId.CHATGPT.chatModel!!, false)
        val r = s.summarize(
            "Vale, idea para la app de notas: que cuando termine de grabar haga un resumen corto y ponga un título " +
                "automático. Y etiquetas para buscar fácil. Lo tengo que hablar con Marc el lunes.",
            listOf("app ideas", "work"),
        )
        println("LIVE: $r")
        assertTrue(r.title.isNotBlank())
        assertTrue(r.tags.isNotEmpty())
    }
}
