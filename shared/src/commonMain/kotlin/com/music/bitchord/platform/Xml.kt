package com.music.bitchord.platform

/**
 * A minimal XML node tree: everything the TTML lyric reader takes off a DOM,
 * and none of the DOM.
 *
 * `org.w3c.dom` and `javax.xml.parsers` exist on the JVM and nowhere else, so
 * the same reading is done here over a node list small enough to hold in one
 * file. Names stay qualified exactly as written (`ttm:agent`), because the
 * reader addresses attributes by qualified name and the JVM path ran with
 * namespace awareness off for that reason.
 *
 * ### Refusals
 *
 * A document declaring `<!DOCTYPE` is refused outright - the same hardening
 * the JVM path asks its parser for, by an easier route. Entities are never
 * fetched and never expanded from a declaration: only the five predefined XML
 * entities and numeric character references decode, and an entity this parser
 * does not know contributes no text, which is what an unexpanded reference
 * under `isExpandEntityReferences = false` gave the JVM reader too. Anything
 * malformed throws, and [com.music.bitchord.data.lyrics.TtmlLyrics.parse]
 * turns that into the empty lyric it has always answered with.
 */
internal class XmlDocument(val root: XmlElement?) {

    /** Every descendant element with this qualified name, document order. */
    fun getElementsByTagName(name: String): List<XmlElement> {
        val found = ArrayList<XmlElement>()
        root?.collect(name, found)
        return found
    }
}

internal sealed interface XmlNode

/** Inter-element text, whitespace included: the word-merging reading depends on it. */
internal class XmlText(val text: String) : XmlNode

internal class XmlElement(
    /** The tag as written, prefix included. */
    val name: String,
    /** Attributes by qualified name as written; absent reads answer with "" below. */
    val attributes: Map<String, String>,
    val children: List<XmlNode>,
) : XmlNode {

    val childElements: List<XmlElement> get() = children.filterIsInstance<XmlElement>()

    fun getAttribute(name: String): String = attributes[name] ?: ""

    /** All text under this element, concatenated - `Node.textContent`. */
    val textContent: String
        get() = buildString { appendText(this) }

    private fun appendText(into: StringBuilder) {
        for (child in children) {
            when (child) {
                is XmlText -> into.append(child.text)
                is XmlElement -> child.appendText(into)
            }
        }
    }

    fun collect(name: String, into: MutableList<XmlElement>) {
        if (this.name == name) into.add(this)
        for (child in childElements) child.collect(name, into)
    }
}

private class MalformedXml(message: String) : IllegalArgumentException(message)

/**
 * One pass over the text: elements, attributes, text, comments, CDATA.
 *
 * Throws on anything structurally wrong, which is the signal the lyric parser
 * has always treated as "this document is not TTML".
 */
internal fun parseXml(input: String): XmlDocument {
    val text = input.removePrefix("\uFEFF")
    var i = 0

    fun skipProlog() {
        while (i < text.length) {
            when {
                text[i].isWhitespace() -> i++
                text.startsWith("<?", i) -> {
                    val end = text.indexOf("?>", i)
                    if (end < 0) throw MalformedXml("unterminated processing instruction")
                    i = end + 2
                }
                text.startsWith("<!--", i) -> {
                    val end = text.indexOf("-->", i)
                    if (end < 0) throw MalformedXml("unterminated comment")
                    i = end + 3
                }
                // A DTD is where external entities and billion-laughs come in;
                // the JVM parser was configured to refuse this too.
                text.startsWith("<!", i) -> throw MalformedXml("declarations are refused")
                else -> return
            }
        }
    }

    fun decodeEntities(raw: String): String {
        if (raw.indexOf('&') < 0) return raw
        val out = StringBuilder(raw.length)
        var at = 0
        while (at < raw.length) {
            val amp = raw.indexOf('&', at)
            if (amp < 0) {
                out.append(raw, at, raw.length)
                break
            }
            out.append(raw, at, amp)
            val end = raw.indexOf(';', amp + 1)
            if (end < 0 || end - amp > 12) {
                // No terminator in reach: literal text, as a parser without a
                // declaration for it would leave the characters themselves.
                out.append('&')
                at = amp + 1
                continue
            }
            val entity = raw.substring(amp + 1, end)
            when {
                entity == "amp" -> out.append('&')
                entity == "lt" -> out.append('<')
                entity == "gt" -> out.append('>')
                entity == "quot" -> out.append('"')
                entity == "apos" -> out.append('\'')
                entity.startsWith("#x") || entity.startsWith("#X") ->
                    entity.drop(2).toIntOrNull(16)?.let { if (it in 1..0x10FFFF) out.append(it.toChar()) }
                entity.startsWith("#") ->
                    entity.drop(1).toIntOrNull(10)?.let { if (it in 1..0x10FFFF) out.append(it.toChar()) }
                // Declared entities are refused with the doctype that would
                // have declared them: no text, no fetch.
                else -> Unit
            }
            at = end + 1
        }
        return out.toString()
    }

    fun parseElement(): XmlElement {
        if (text[i] != '<') throw MalformedXml("expected a tag at $i")
        i++
        val nameStart = i
        while (i < text.length && !text[i].isWhitespace() && text[i] != '>' && text[i] != '/') i++
        if (i == nameStart) throw MalformedXml("empty tag name at $nameStart")
        val name = text.substring(nameStart, i)

        val attributes = LinkedHashMap<String, String>()
        while (true) {
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length) throw MalformedXml("unterminated tag <$name>")
            when {
                text.startsWith("/>", i) -> {
                    i += 2
                    return XmlElement(name, attributes, emptyList())
                }
                text[i] == '>' -> {
                    i++
                    break
                }
                else -> {
                    val keyStart = i
                    while (i < text.length && !text[i].isWhitespace() &&
                        text[i] != '=' && text[i] != '>' && text[i] != '/'
                    ) i++
                    if (i == keyStart) throw MalformedXml("bad attribute at $keyStart")
                    val key = text.substring(keyStart, i)
                    while (i < text.length && text[i].isWhitespace()) i++
                    if (i >= text.length || text[i] != '=') throw MalformedXml("attribute $key has no value")
                    i++
                    while (i < text.length && text[i].isWhitespace()) i++
                    if (i >= text.length || (text[i] != '"' && text[i] != '\'')) {
                        throw MalformedXml("attribute $key is not quoted")
                    }
                    val quote = text[i]
                    i++
                    val valueStart = i
                    while (i < text.length && text[i] != quote) i++
                    if (i >= text.length) throw MalformedXml("unterminated value for $key")
                    attributes[key] = decodeEntities(text.substring(valueStart, i))
                    i++
                }
            }
        }

        val children = ArrayList<XmlNode>()
        while (true) {
            when {
                i >= text.length -> throw MalformedXml("<$name> is never closed")
                text.startsWith("</", i) -> {
                    i += 2
                    val closeStart = i
                    while (i < text.length && text[i] != '>') i++
                    if (i >= text.length) throw MalformedXml("unterminated closing tag for <$name>")
                    val closing = text.substring(closeStart, i).trim()
                    i++
                    if (closing != name) throw MalformedXml("</$closing> closes <$name>")
                    return XmlElement(name, attributes, children)
                }
                text.startsWith("<!--", i) -> {
                    val end = text.indexOf("-->", i)
                    if (end < 0) throw MalformedXml("unterminated comment in <$name>")
                    i = end + 3
                }
                text.startsWith("<![CDATA[", i) -> {
                    val end = text.indexOf("]]>", i)
                    if (end < 0) throw MalformedXml("unterminated CDATA in <$name>")
                    children.add(XmlText(text.substring(i + 9, end)))
                    i = end + 3
                }
                text.startsWith("<?", i) -> {
                    val end = text.indexOf("?>", i)
                    if (end < 0) throw MalformedXml("unterminated processing instruction in <$name>")
                    i = end + 2
                }
                text[i] == '<' -> children.add(parseElement())
                else -> {
                    val next = text.indexOf('<', i)
                    val end = if (next < 0) text.length else next
                    children.add(XmlText(decodeEntities(text.substring(i, end))))
                    i = end
                }
            }
        }
    }

    skipProlog()
    if (i >= text.length || text[i] != '<') throw MalformedXml("no document element")
    val root = parseElement()
    // Trailing whitespace and a trailing comment or two are all that may
    // follow; a second element means this was never one document.
    skipProlog()
    if (i < text.length) throw MalformedXml("content after the document element")
    return XmlDocument(root)
}
