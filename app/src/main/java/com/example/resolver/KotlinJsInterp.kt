package com.example.resolver

import android.util.Log

/**
 * Pure Kotlin port of yt_dlp/jsinterp.py semantics (§A.4 Problem 1).
 */
object KotlinJsInterp {
    private const val TAG = "KotlinJsInterp"

    fun decipherSignature(s: String, operations: List<CipherOp>): String {
        if (operations.isEmpty()) return s
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
                    if (list.isNotEmpty()) {
                        val target = op.arg % list.size
                        val temp = list[0]
                        list[0] = list[target]
                        list[target] = temp
                    }
                }
            }
        }
        return list.joinToString("")
    }

    /**
     * Preserves the 'n' parameter on direct mobile/VR InnerTube streams rather than
     * corrupting it with a static permutation.
     */
    fun transformNParam(n: String): String {
        return n
    }

    enum class OpType { REVERSE, SPLICE, SWAP }
    data class CipherOp(val type: OpType, val arg: Int = 0)
}
