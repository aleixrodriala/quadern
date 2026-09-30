package io.github.aleixrodriala.quadern.insights

import io.github.aleixrodriala.quadern.transcription.SttException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** What a text model writes about a note: a title, a short prose summary and a few topic tags. */
data class NoteInsights(val title: String, val summary: String, val tags: List<String>)

/** The prompt, the answer format and the cleanup, shared by every service that writes summaries. */
object InsightsPrompt {
    const val MAX_TAGS = 3
    private const val MAX_TAG_LENGTH = 30
    private const val MAX_TITLE_LENGTH = 80
    private const val MAX_SUMMARY_LENGTH = 2_000

    /** About 30k tokens: hours of speech. Longer transcripts keep their start and end. */
    const val MAX_TRANSCRIPT_CHARS = 120_000

    val instructions = """
        You title and summarize personal voice notes. The input is the transcript of one voice note, made by speech recognition, so expect small errors.

        Answer with a JSON object with these keys:
        - title: a short, specific title of 2 to 6 words, the way a person would name the note. No quotes, no final period, no emoji.
        - summary: what the note is about, in plain prose, as short as it can be while still naming every main topic, decision and plan. Usually 2 to 4 sentences, under 60 words. Only a long note with many topics gets a second short paragraph, and never more than 120 words in total. Start directly with the content (for example "Reflections on..." or "Plan to..."), never with "The speaker" or "In this note". No lists, headings or markdown. If the input says the note is short, return an empty summary.
        - tags: 1 to 3 short lowercase topic tags of one or two words, without "#". Reuse an existing tag only when it really fits; otherwise make a new one.

        Write everything in the language of the transcript.
    """.trimIndent()

    /** JSON Schema for the answer; strict mode needs every key required and no extras. */
    val schema: JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonArray("required") { add("title"); add("summary"); add("tags") }
        putJsonObject("properties") {
            putJsonObject("title") { put("type", "string") }
            putJsonObject("summary") { put("type", "string") }
            putJsonObject("tags") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
            }
        }
    }

    /** Notes shorter than this get a title and tags but no summary: it would only repeat them. */
    const val MIN_SUMMARY_WORDS = 40

    /**
     * Decided here rather than by the model, which was inconsistent: it skipped a five-sentence
     * recipe and summarized a one-line errand. Chinese and Japanese don't space words; about 1.5
     * characters make one.
     */
    fun wantsSummary(transcript: String): Boolean {
        val words = transcript.split(Regex("\\s+")).count { it.isNotEmpty() }
        val cjk = transcript.count { Character.UnicodeScript.of(it.code) in CJK_SCRIPTS }
        return words + cjk * 2 / 3 >= MIN_SUMMARY_WORDS
    }

    private val CJK_SCRIPTS = setOf(
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA, Character.UnicodeScript.THAI,
    )

    fun input(transcript: String, existingTags: List<String>): String = buildString {
        if (!wantsSummary(transcript)) append("The note is short: leave the summary empty.\n\n")
        if (existingTags.isNotEmpty()) append("Existing tags: ").append(existingTags.joinToString(", ")).append("\n\n")
        append("Transcript:\n")
        append(clip(transcript.trim()))
    }

    private fun clip(text: String): String {
        if (text.length <= MAX_TRANSCRIPT_CHARS) return text
        val head = MAX_TRANSCRIPT_CHARS * 2 / 3
        val tail = MAX_TRANSCRIPT_CHARS - head
        return text.substring(0, head) + "\n[…]\n" + text.substring(text.length - tail)
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Reads the model's answer. Tolerates code fences and chatter around the JSON, since not every
     * service enforces the schema. An unreadable answer is worth another try, so it's [SttException.Transient].
     */
    fun parse(raw: String): NoteInsights {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) throw SttException.Transient("The summary came back empty")
        val obj = runCatching { json.parseToJsonElement(raw.substring(start, end + 1)) as? JsonObject }.getOrNull()
            ?: throw SttException.Transient("The summary came back unreadable")
        fun str(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val tags = when (val t = obj["tags"]) {
            is JsonArray -> t.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> t.contentOrNull.orEmpty().split(',', ';')
            else -> emptyList()
        }
        return NoteInsights(cleanTitle(str("title")), cleanSummary(str("summary")), cleanTags(tags))
    }

    fun cleanTitle(title: String): String {
        var t = title.replace(Regex("\\s+"), " ").trim()
            .trim('"', '\'', '“', '”', '‘', '’', '«', '»', '*', '#', ' ')
            .removeSuffix(".").trim()
        if (t.length > MAX_TITLE_LENGTH) {
            val cut = t.lastIndexOf(' ', MAX_TITLE_LENGTH).takeIf { it > MAX_TITLE_LENGTH / 2 } ?: MAX_TITLE_LENGTH
            t = t.substring(0, cut).trimEnd(',', ';', ':', ' ', '-') + "…"
        }
        return t
    }

    fun cleanSummary(summary: String): String {
        val lines = summary.replace("\r", "").lines().map { it.trim() }
        // Some models slip into bullets anyway; keep the words, drop the list.
        val bullet = Regex("^([-*•]|\\d+[.)])\\s+")
        val prose = if (lines.count { bullet.containsMatchIn(it) } >= 2) {
            lines.filter { it.isNotEmpty() }.joinToString(" ") { it.replace(bullet, "") }
        } else {
            lines.joinToString("\n")
        }
        return prose.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
            .take(MAX_SUMMARY_LENGTH)
    }

    fun cleanTags(tags: List<String>): List<String> = tags
        .map { tag ->
            tag.lowercase().replace(",", " ").replace(Regex("\\s+"), " ").trim().trimStart('#').trim()
        }
        .filter { it.isNotEmpty() && it.length <= MAX_TAG_LENGTH }
        .distinct()
        .take(MAX_TAGS)

    /** The most used tags first, so the model keeps the vocabulary consistent. */
    fun topTags(tagStrings: List<String>, limit: Int = 40): List<String> = tagStrings
        .flatMap { io.github.aleixrodriala.quadern.data.splitTags(it) }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(limit)
        .map { it.key }
}

/**
 * Collects the answer from an OpenAI Responses API event stream (`text/event-stream`). Returns the
 * final output text, or throws when the stream reports an error or ends before `response.completed`.
 */
object ResponsesStream {
    private val json = Json { ignoreUnknownKeys = true }

    fun collect(lines: Iterator<String>): String {
        val deltas = StringBuilder()
        var finalText: String? = null
        for (line in lines) {
            if (!line.startsWith("data:")) continue
            val data = line.substring(5).trim()
            if (data.isEmpty() || data == "[DONE]") continue
            val event = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: continue
            when (event.str("type")) {
                "response.output_text.delta" -> deltas.append(event.str("delta").orEmpty())
                "response.output_text.done" -> finalText = event.str("text")
                "response.completed" -> return finalText ?: deltas.toString()
                "response.incomplete" -> {
                    val reason = event.obj("response")?.obj("incomplete_details")?.str("reason") ?: "unknown"
                    // A filtered answer won't change on retry; anything else might.
                    if (reason == "content_filter") throw SttException.Permanent("The summary was blocked ($reason)")
                    throw SttException.Transient("The summary was cut short ($reason)")
                }
                "response.failed" -> {
                    val error = event.obj("response")?.obj("error")
                    throw SttException.Transient("Summary failed: ${error?.str("message") ?: error?.str("code") ?: "unknown error"}")
                }
                "error" -> throw SttException.Transient("Summary failed: ${event.str("message") ?: event.str("code") ?: "unknown error"}")
            }
        }
        throw SttException.Transient("The summary stream ended early")
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
}
