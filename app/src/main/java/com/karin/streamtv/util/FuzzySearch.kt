package com.karin.streamtv.util

object FuzzySearch {

    fun levenshteinDistance(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }

        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j

        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[len1][len2]
    }

    fun similarity(s1: String, s2: String): Double {
        val maxLen = maxOf(s1.length, s2.length)
        if (maxLen == 0) return 1.0
        val distance = levenshteinDistance(s1, s2)
        return 1.0 - (distance.toDouble() / maxLen)
    }

    fun findBestMatch(query: String, candidates: List<String>, threshold: Double = 0.4): Pair<String, Double>? {
        val normalizedQuery = query.lowercase().trim()
        var bestMatch: String? = null
        var bestScore = 0.0

        for (candidate in candidates) {
            val normalizedCandidate = candidate.lowercase().trim()

            if (normalizedCandidate == normalizedQuery) {
                return Pair(candidate, 1.0)
            }

            if (normalizedCandidate.contains(normalizedQuery) || normalizedQuery.contains(normalizedCandidate)) {
                val score = similarity(normalizedQuery, normalizedCandidate)
                if (score > bestScore) {
                    bestScore = score
                    bestMatch = candidate
                }
                continue
            }

            val words1 = normalizedQuery.split("\\s+".toRegex())
            val words2 = normalizedCandidate.split("\\s+".toRegex())
            var wordScore = 0.0
            var matchedWords = 0

            for (w1 in words1) {
                for (w2 in words2) {
                    val s = similarity(w1, w2)
                    if (s > 0.6) {
                        wordScore += s
                        matchedWords++
                        break
                    }
                }
            }

            if (matchedWords > 0) {
                val avgWordScore = wordScore / words1.size
                if (avgWordScore > bestScore) {
                    bestScore = avgWordScore
                    bestMatch = candidate
                }
            }

            val overallScore = similarity(normalizedQuery, normalizedCandidate)
            if (overallScore > bestScore) {
                bestScore = overallScore
                bestMatch = candidate
            }
        }

        return if (bestMatch != null && bestScore >= threshold) Pair(bestMatch, bestScore) else null
    }
}
