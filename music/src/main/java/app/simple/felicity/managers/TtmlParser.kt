package app.simple.felicity.managers

import android.util.Xml
import org.xmlpull.v1.XmlPullParser

/**
 * A small TTML reader for word-timed lyrics: every `<p>` is a line, every `<span>` inside it with
 * a begin/end is a word. Background-vocal spans (`ttm:role="x-bg"`) are skipped, as the lyrics
 * view has no second voice. Clock values accepted: `1:23.456`, `00:01:23.456`, `83.456s`, `83456ms`.
 */
internal object TtmlParser {

    data class Line(val startMs: Long, val endMs: Long, val text: String, val words: List<Triple<Long, Long, String>>)

    fun parse(ttml: String): List<Line> = runCatching { read(ttml) }.getOrDefault(emptyList())

    private fun read(ttml: String): List<Line> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ttml.reader())
        val lines = ArrayList<Line>()
        var lineStart = 0L
        var lineEnd = 0L
        var words = ArrayList<Triple<Long, Long, String>>()
        var plain = StringBuilder()
        var inLine = false
        var spanDepth = 0
        var skipDepth = 0
        var spanStart = -1L
        var spanEnd = -1L
        var spanText = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.substringAfter(':')) {
                    "p" -> {
                        inLine = true
                        lineStart = clock(parser.getAttributeValue(null, "begin")) ?: 0L
                        lineEnd = clock(parser.getAttributeValue(null, "end")) ?: lineStart
                        words = ArrayList()
                        plain = StringBuilder()
                        spanDepth = 0
                        skipDepth = 0
                    }
                    "span" -> if (inLine) {
                        if (skipDepth > 0 || parser.getAttributeValue(null, "ttm:role") == "x-bg") {
                            skipDepth++
                        } else {
                            spanDepth++
                            if (spanDepth == 1) {
                                spanStart = clock(parser.getAttributeValue(null, "begin")) ?: -1L
                                spanEnd = clock(parser.getAttributeValue(null, "end")) ?: -1L
                                spanText = StringBuilder()
                            }
                        }
                    }
                    "br" -> if (inLine && skipDepth == 0) plain.append(' ')
                }
                XmlPullParser.TEXT -> if (inLine && skipDepth == 0) {
                    plain.append(parser.text)
                    if (spanDepth > 0) {
                        spanText.append(parser.text)
                    } else if (parser.text.isNotEmpty() && parser.text.isBlank() && words.isNotEmpty()) {
                        // A space between two words belongs to the word before it, as in enhanced LRC.
                        val last = words.removeAt(words.lastIndex)
                        words += Triple(last.first, last.second, last.third + " ")
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name.substringAfter(':')) {
                    "span" -> if (inLine) {
                        if (skipDepth > 0) {
                            skipDepth--
                        } else if (spanDepth > 0) {
                            spanDepth--
                            if (spanDepth == 0 && spanStart >= 0 && spanEnd > spanStart && spanText.isNotBlank()) {
                                words += Triple(spanStart, spanEnd, spanText.toString())
                            }
                        }
                    }
                    "p" -> if (inLine) {
                        inLine = false
                        val text = plain.toString().replace(Regex("\\s+"), " ").trim()
                        if (text.isNotEmpty()) lines += Line(lineStart, lineEnd, text, words)
                    }
                }
            }
            event = parser.next()
        }
        return lines
    }

    private fun clock(value: String?): Long? {
        val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        v.removeSuffix("ms").takeIf { v.endsWith("ms") }?.toDoubleOrNull()?.let { return it.toLong() }
        v.removeSuffix("s").takeIf { v.endsWith("s") }?.toDoubleOrNull()?.let { return (it * 1000).toLong() }
        val parts = v.split(':')
        var seconds = 0.0
        for (part in parts) seconds = seconds * 60 + (part.toDoubleOrNull() ?: return null)
        return (seconds * 1000).toLong()
    }
}
