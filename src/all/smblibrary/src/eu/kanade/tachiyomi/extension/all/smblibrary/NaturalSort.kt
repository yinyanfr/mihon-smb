package eu.kanade.tachiyomi.extension.all.smblibrary

import java.math.BigInteger
import java.text.Normalizer
import java.util.Locale

object NaturalSort {
    private val tokenRegex = Regex("\\d+|\\D+")

    val comparator: Comparator<String> = Comparator { left, right ->
        compare(left, right)
    }

    fun sorted(values: Iterable<String>): List<String> = values.sortedWith(comparator.thenBy { it })

    fun <T> sortedBy(values: Iterable<T>, selector: (T) -> String): List<T> = values.sortedWith(compareBy<T, String>(comparator, selector).thenBy(selector))

    fun compare(left: String, right: String): Int {
        val leftTokens = tokenize(left)
        val rightTokens = tokenize(right)
        val max = minOf(leftTokens.size, rightTokens.size)
        for (index in 0 until max) {
            val result = compareToken(leftTokens[index], rightTokens[index])
            if (result != 0) return result
        }
        return leftTokens.size.compareTo(rightTokens.size).takeIf { it != 0 }
            ?: normalize(left).compareTo(normalize(right))
    }

    private fun compareToken(left: String, right: String): Int {
        val leftNumber = left.all(Char::isDigit)
        val rightNumber = right.all(Char::isDigit)
        return when {
            leftNumber && rightNumber -> {
                val numeric = BigInteger(left).compareTo(BigInteger(right))
                if (numeric != 0) numeric else left.length.compareTo(right.length)
            }
            else -> left.compareTo(right)
        }
    }

    private fun tokenize(value: String): List<String> = tokenRegex.findAll(normalize(value)).map { it.value }.toList()

    private fun normalize(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
        return buildString(normalized.length) {
            normalized.forEach { char ->
                append(
                    when (char) {
                        in '\uFF10'..'\uFF19' -> '0' + (char.code - '\uFF10'.code)
                        else -> char.lowercaseChar()
                    },
                )
            }
        }.lowercase(Locale.ROOT)
    }
}
