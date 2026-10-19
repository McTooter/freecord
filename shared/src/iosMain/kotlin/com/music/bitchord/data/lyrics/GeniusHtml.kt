package com.music.bitchord.data.lyrics

/**
 * The markup walk behind [extractGeniusContainers], for where Jsoup does not
 * exist.
 *
 * Not a general HTML parser — the subset Genius's own pages are made of:
 * elements nested inside elements, quoted or bare attribute values, `<br>`
 * and void tags with no close, comments, and `script`/`style` whose bodies
 * are raw text rather than markup. The tree it builds keeps exactly what the
 * Jsoup pass asked of the document: element boundaries, attributes, and text
 * nodes in order. What it does not do is *repair* bad markup the way Jsoup's
 * tree builder would; a container closed early ends there, and the lyrics
 * after it are lost rather than re-homed — acceptable because the failure
 * mode is "no lyrics found", the same answer the rest of the app gives for a
 * page it cannot read.
 */
internal actual fun extractGeniusContainers(html: String): String? {
    val document = ElementScanner(html).parse()
    val containers = document.descendants().filter { it.isLyricsContainer() }
        .ifEmpty { document.descendants().filter { it.name == "div" && it.hasClass("lyrics") } }
        .toList()
    if (containers.isEmpty()) return null

    val builder = StringBuilder()
    for (container in containers) {
        val into = StringBuilder()
        container.appendText(html, into)
        val text = into.toString()
        if (text.isNotBlank()) builder.append(text).append("\n")
    }
    return builder.toString()
}

/** Every element under this one, at any depth — the extent Jsoup's select worked over. */
private fun Element.descendants(): List<Element> {
    val found = mutableListOf<Element>()
    val queue = ArrayDeque(children)
    while (queue.isNotEmpty()) {
        val element = queue.removeFirst()
        found += element
        queue.addAll(element.children)
    }
    return found
}

/** One element, or one text node (name `#text`), with its extent in the source. */
private class Element(
    val name: String,
    val attributes: String,
    val innerStart: Int,
    val innerEnd: Int,
) {
    val children = mutableListOf<Element>()

    fun isLyricsContainer(): Boolean = name == "div" && attribute("data-lyrics-container") == "true"

    fun hasClass(cls: String): Boolean =
        attribute("class")?.split(WHITESPACE)?.any { it == cls } == true

    /** The Jsoup exclusions, by the selector this scraping has always used. */
    private fun isExcluded(): Boolean =
        name == "button" || name == "script" || name == "style" ||
            attribute("data-exclude-from-selection") == "true" ||
            EXCLUDED_CLASSES.any { hasClass(it) }

    fun appendText(html: String, into: StringBuilder) {
        for (child in children) {
            when {
                child.name == TEXT -> appendDecoded(html, child.innerStart, child.innerEnd, into)
                child.isExcluded() -> continue
                child.name == "br" -> into.append('\n')
                // Jsoup prepended a newline to every <p> before the text was read.
                child.name == "p" -> {
                    into.append('\n')
                    child.appendText(html, into)
                }
                else -> child.appendText(html, into)
            }
        }
    }

    private fun attribute(name: String): String? {
        val matcher = Regex(
            "(?:^|\\s)${Regex.escape(name)}\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))",
            RegexOption.IGNORE_CASE,
        ).find(attributes) ?: return null
        val groups = matcher.groupValues
        return groups[1].ifEmpty { groups[2].ifEmpty { groups[3] } }
    }

    companion object {
        const val TEXT = "#text"
        val WHITESPACE = Regex("""\s+""")
        val EXCLUDED_CLASSES = listOf("LyricsHeader__Container", "SongBioPreview__Container", "InreadAd__Container")
    }
}

/** Elements that never carry a close tag, plus the raw-text pair. */
private val VOID_TAGS = setOf(
    "area", "base", "br", "col", "embed", "hr", "img", "input",
    "link", "meta", "param", "source", "track", "wbr",
)
private val RAW_TEXT_TAGS = setOf("script", "style")

private class ElementScanner(private val html: String) {
    fun parse(): Element {
        val root = Element("#root", "", 0, html.length)
        parseChildren(root, 0, html.length)
        return root
    }

    private fun parseChildren(parent: Element, from: Int, until: Int) {
        var i = from
        while (i < until) {
            val lt = html.indexOf('<', i)
            if (lt < 0 || lt >= until) {
                if (i < until) parent.children += text(i, until)
                return
            }
            if (lt > i) parent.children += text(i, lt)
            if (html.startsWith("<!--", lt)) {
                val end = html.indexOf("-->", lt + 4)
                if (end < 0 || end + 3 > until) return
                i = end + 3
                continue
            }
            if (html.startsWith("<!", lt) || html.startsWith("<?", lt)) {
                val end = html.indexOf('>', lt)
                if (end < 0 || end + 1 > until) return
                i = end + 1
                continue
            }
            if (lt + 1 >= until || !html[lt + 1].isLetter()) {
                // A stray '<' is text on a real page, not a tag.
                parent.children += text(lt, (lt + 1).coerceAtMost(until))
                i = lt + 1
                continue
            }
            val past = tagEnd(lt, until) ?: run {
                parent.children += text(lt, until)
                return
            }
            if (html[lt + 1] == '/') {
                // A close tag without an open one here: the markup's problem,
                // not this walk's; skipped like any other tag.
                i = past
                continue
            }
            val nameStart = lt + 1
            var nameEnd = nameStart
            while (nameEnd < past - 1 && (html[nameEnd].isLetterOrDigit() || html[nameEnd] == '-')) nameEnd++
            val name = html.substring(nameStart, nameEnd).lowercase()
            val attributes = html.substring(nameEnd, past - 1).trim()
            val selfClosing = html[past - 2] == '/'
            if (name in VOID_TAGS || selfClosing) {
                parent.children += Element(name, attributes, past, past)
                i = past
                continue
            }
            if (name in RAW_TEXT_TAGS) {
                // The body is raw text: its '<' and even a literal close tag in
                // a string say nothing about this document's structure.
                val rawClose = findClose(name, past, until)
                if (rawClose == null) {
                    parent.children += Element(name, attributes, past, until)
                    return
                }
                parent.children += Element(name, attributes, past, rawClose)
                i = tagEnd(rawClose, until) ?: return
                continue
            }
            val closeStart = findMatching(name, past, until)
            if (closeStart == null) {
                parent.children += Element(name, attributes, past, until)
                return
            }
            val element = Element(name, attributes, past, closeStart)
            parseChildren(element, past, closeStart)
            parent.children += element
            i = tagEnd(closeStart, until) ?: return
        }
    }

    /**
     * The index of the close tag matching the open tag whose body starts at
     * [from] — counting same-name nesting, and skipping script/style bodies
     * wholesale so their contents cannot close anything.
     */
    private fun findMatching(name: String, from: Int, until: Int): Int? {
        var depth = 1
        var scan = from
        while (scan < until) {
            val lt = html.indexOf('<', scan)
            if (lt < 0 || lt >= until) return null
            if (html.startsWith("<!--", lt)) {
                val end = html.indexOf("-->", lt + 4)
                if (end < 0 || end + 3 > until) return null
                scan = end + 3
                continue
            }
            val past = tagEnd(lt, until) ?: return null
            if (html[lt + 1] == '/') {
                if (nameAt(lt + 2, past) == name) {
                    depth--
                    if (depth == 0) return lt
                }
            } else {
                val inner = nameAt(lt + 1, past)
                if (inner in RAW_TEXT_TAGS) {
                    val rawClose = findClose(inner, past, until) ?: return null
                    scan = tagEnd(rawClose, until) ?: return null
                    continue
                }
                if (inner == name && html[past - 2] != '/' && inner !in VOID_TAGS) depth++
            }
            scan = past
        }
        return null
    }

    /** Where a raw-text element's close tag opens, or null when the page ends first. */
    private fun findClose(name: String, from: Int, until: Int): Int? {
        val needle = "</$name"
        var i = from
        while (i < until) {
            val at = html.indexOf('<', i)
            if (at < 0 || at >= until) return null
            if (html.regionMatches(at, needle, 0, needle.length, ignoreCase = true)) return at
            i = at + 1
        }
        return null
    }

    /** The tag name at [from], when there is a whole one before [until]. */
    private fun nameAt(from: Int, until: Int): String {
        if (from >= until || !html[from].isLetter()) return ""
        var end = from
        while (end < until && (html[end].isLetterOrDigit() || html[end] == '-')) end++
        return html.substring(from, end).lowercase()
    }

    /** Just past a tag's '>', quote-aware so a '>' inside an attribute is not the end. */
    private fun tagEnd(start: Int, until: Int): Int? {
        var i = start + 1
        var quote: Char? = null
        while (i < until) {
            val c = html[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '>' -> return i + 1
            }
            i++
        }
        return null
    }

    private fun text(start: Int, end: Int) = Element(Element.TEXT, "", start, end)
}

/** Entities decoded the way `wholeText` did, named and numeric. */
private fun appendDecoded(html: String, start: Int, end: Int, into: StringBuilder) {
    var i = start
    while (i < end) {
        val c = html[i]
        if (c != '&') {
            into.append(c)
            i++
            continue
        }
        val semi = html.indexOf(';', i + 1)
        if (semi in (i + 2)..(i + 9)) {
            val entity = html.substring(i + 1, semi)
            val decoded: String? = when {
                entity == "amp" -> "&"
                entity == "lt" -> "<"
                entity == "gt" -> ">"
                entity == "quot" -> "\""
                entity == "apos" || entity == "#39" || entity == "#x27" || entity == "#X27" -> "'"
                entity == "nbsp" -> "\u00A0"
                entity.startsWith("#x") || entity.startsWith("#X") ->
                    entity.substring(2).toIntOrNull(16)?.let { codePoint(it) }
                entity.startsWith("#") -> entity.substring(1).toIntOrNull()?.let { codePoint(it) }
                else -> null
            }
            if (decoded != null) {
                into.append(decoded)
                i = semi + 1
                continue
            }
        }
        into.append('&')
        i++
    }
}

/** One code point as text, surrogate pair included, as the JVM's toChar wanted. */
private fun codePoint(value: Int): String? {
    if (value <= 0 || value > 0x10FFFF) return null
    if (value <= 0xFFFF) return value.toChar().toString()
    val offset = value - 0x10000
    return ((offset shr 10) + 0xD800).toChar().toString() + ((offset and 0x3FF) + 0xDC00).toChar().toString()
}
