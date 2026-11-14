package app.headsup.core.planner

/** Checks a regular expression for what Android's engine (ICU) rejects and the JVM accepts. */
object PatternCheck {
    /** Places of braces and closing brackets that are not escaped (other than a quantifier like {2,3}). */
    fun loneBraces(pattern: String): List<Int> {
        val bad = ArrayList<Int>()
        var inClass = false
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> { i += 2; continue }
                inClass && c == ']' -> inClass = false
                inClass && (c == '{' || c == '}') -> bad += i
                !inClass && c == '[' -> inClass = true
                !inClass && c == '{' && Regex("^\\{\\d+(,\\d*)?\\}").containsMatchIn(pattern.substring(i)) -> i = pattern.indexOf('}', i)
                !inClass && (c == '{' || c == '}' || c == ']') -> bad += i
            }
            i++
        }
        return bad
    }

}
