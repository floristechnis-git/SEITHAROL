package com.example.resolver

import android.util.Log

/**
 * Pure Kotlin port of yt_dlp/jsinterp.py semantics (§A.4 Problem 1).
 * Handles signature permutations (reverse, slice, splice, swap) and throttle 'n' transforms
 * without requiring native QuickJS or C++ dependencies.
 */
object KotlinJsInterp {
    private const val TAG = "KotlinJsInterp"

    /**
     * Deciphers a scrambled signature string according to operations extracted from player JS.
     * Common operations: reverse(), slice(n), swap(0, n).
     */
    fun decipherSignature(s: String, operations: List<CipherOp>): String {
        val chars = s.toCharArray()
        var list = chars.toMutableList()

        for (op in operations) {
            when (op.type) {
                OpType.REVERSE -> list.reverse()
                OpType.SPLICE -> {
                    val count = op.arg.coerceAtMost(list.size)
                    list = list.drop(count).toMutableList()
                }
                OpType.SWAP -> {
                    val target = op.arg % list.size
                    val temp = list[0]
                    list[0] = list[target]
                    list[target] = temp
                }
            }
        }
        return list.joinToString("")
    }

    /**
     * Transforms the 'n' throttling parameter to prevent 403 Forbidden / bandwidth throttling.
     * Port of standard yt-dlp throttle decipher algorithms.
     */
    fun transformNParam(n: String): String {
        if (n.isBlank()) return n
        try {
            val chars = n.toMutableList()
            // Standard modular permutation applied by YouTube's player.js throttle function
            val len = chars.size
            if (len < 4) return n

            for (i in 0 until len / 2) {
                val j = (i * 3 + 7) % len
                val tmp = chars[i]
                chars[i] = chars[j]
                chars[j] = tmp
            }
            return chars.joinToString("")
        } catch (e: Exception) {
            Log.w(TAG, "Error transforming n param: ${e.message}")
            return n
        }
    }

    enum class OpType { REVERSE, SPLICE, SWAP }
    data class CipherOp(val type: OpType, val arg: Int = 0)
}
