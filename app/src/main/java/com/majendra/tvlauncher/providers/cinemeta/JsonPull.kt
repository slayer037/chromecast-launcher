package com.majendra.tvlauncher.providers.cinemeta

import java.io.IOException
import java.io.Reader

/**
 * Forward-only JSON reader. Fields the caller does not ask for are skipped character by character, so a
 * catalogue page never becomes a full object tree. Pure Kotlin: android.util.JsonReader is a stub in JVM tests.
 */
internal class JsonPull(private val input: Reader, private val maxChars: Long = 2_000_000, private val maxText: Int = 16_384) {
    private val buffer = CharArray(8192)
    private var position = 0
    private var limit = 0
    private var consumed = 0L
    private val text = StringBuilder()

    /** Reads an object, calling [field] once per name; [field] must consume exactly one value. False (value skipped) when it is not an object. */
    fun objectOrSkip(field: (String) -> Unit): Boolean {
        if (peek() != '{') { skip(); return false }
        take()
        if (peek() == '}') { take(); return true }
        while (true) {
            if (token() != '"') fail()
            val name = string(true)
            if (token() != ':') fail()
            field(name)
            when (token()) { ',' -> continue; '}' -> return true; else -> fail() }
        }
    }

    /** Reads an array, calling [element] once per entry; [element] must consume exactly one value. */
    fun arrayOrSkip(element: () -> Unit): Boolean {
        if (peek() != '[') { skip(); return false }
        take()
        if (peek() == ']') { take(); return true }
        while (true) {
            element()
            when (token()) { ',' -> continue; ']' -> return true; else -> fail() }
        }
    }

    /** A string, or the literal text of a number or boolean. Null, objects and arrays give null. */
    fun scalar(): String? = when (peek()) {
        '"' -> { take(); string(true) }
        '{', '[' -> { skip(); null }
        else -> literal().takeIf { it != "null" }
    }

    fun skip() {
        when (token()) {
            '"' -> string(false)
            '{', '[' -> {
                var open = 1
                while (open > 0) when (take()) {
                    '"' -> string(false)
                    '{', '[' -> if (++open > MAX_DEPTH) fail()
                    '}', ']' -> open--
                    else -> Unit
                }
            }
            else -> { position--; consumed--; literal() }
        }
    }

    private fun literal(): String {
        peek()
        text.setLength(0)
        while (true) {
            val next = read()
            if (next < 0) break
            val char = next.toChar()
            if (char == ',' || char == '}' || char == ']' || char.isWhitespace()) { position--; consumed--; break }
            if (text.length >= 64) fail()
            text.append(char)
        }
        if (text.isEmpty()) fail()
        return text.toString()
    }

    /** The opening quote is already consumed. Text beyond [maxText] is read but not kept. */
    private fun string(keep: Boolean): String {
        text.setLength(0)
        while (true) {
            var char = take()
            if (char == '"') break
            if (char == '\\') char = when (val escaped = take()) {
                'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; 'b' -> '\b'; 'f' -> '\u000C'
                'u' -> {
                    var code = 0
                    repeat(4) { val digit = Character.digit(take(), 16); if (digit < 0) fail(); code = code * 16 + digit }
                    code.toChar()
                }
                '"', '\\', '/' -> escaped
                else -> fail()
            }
            if (keep && text.length < maxText) text.append(char)
        }
        return if (keep) text.toString() else ""
    }

    private fun peek(): Char {
        while (true) {
            val next = read()
            if (next < 0) fail()
            if (!next.toChar().isWhitespace()) { position--; consumed--; return next.toChar() }
        }
    }
    private fun token(): Char { val char = peek(); take(); return char }
    private fun take(): Char { val next = read(); if (next < 0) fail(); return next.toChar() }
    private fun read(): Int {
        if (position == limit) {
            limit = input.read(buffer, 0, buffer.size); position = 0
            if (limit <= 0) { limit = 0; return -1 }
        }
        if (++consumed > maxChars) throw IOException("JSON response too large")
        return buffer[position++].code
    }
    private fun fail(): Nothing = throw IOException("Malformed JSON response")

    private companion object { const val MAX_DEPTH = 64 }
}
