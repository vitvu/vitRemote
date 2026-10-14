package com.vvtool.vitremote

import java.io.ByteArrayOutputStream

/**
 * Rewrites the H.264 SPS so it declares "no frame reordering" (VUI bitstream_restriction with
 * max_num_reorder_frames = 0). Without it, browsers' decoders hold back a full reorder buffer
 * (~10 frames ≈ 250 ms) before showing anything. Unsupported layouts are returned unchanged.
 */
object SpsFix {

    /** Fixes every SPS inside an Annex-B buffer (e.g. csd-0 + csd-1). */
    fun annexB(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size + 16)
        for (nal in split(data)) {
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(if (nal.isNotEmpty() && nal[0].toInt() and 0x1f == 7) sps(nal) else nal)
        }
        return out.toByteArray()
    }

    private fun split(d: ByteArray): List<ByteArray> {
        val res = ArrayList<ByteArray>()
        var start = -1
        var i = 0
        while (i + 2 < d.size) {
            if (d[i].toInt() == 0 && d[i + 1].toInt() == 0 && d[i + 2].toInt() == 1) {
                if (start >= 0) {
                    var e = i
                    while (e > start && d[e - 1].toInt() == 0) e--
                    res.add(d.copyOfRange(start, e))
                }
                start = i + 3; i += 3
            } else i++
        }
        if (start >= 0) res.add(d.copyOfRange(start, d.size))
        return res
    }

    fun sps(nal: ByteArray): ByteArray = try { rewrite(nal) } catch (_: Exception) { nal }

    private fun rewrite(nal: ByteArray): ByteArray {
        val rbsp = unescape(nal, 1)
        val r = BitReader(rbsp)
        val w = BitWriter()
        fun u(n: Int) = r.u(n).also { w.u(it, n) }
        fun ue() = r.ue().also { w.ue(it) }
        fun se() = r.se().also { w.se(it) }

        val profile = u(8); u(8); u(8); ue()
        if (profile in intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            if (ue() == 3) u(1)
            ue(); ue(); u(1)
            if (u(1) == 1) return nal // scaling matrices: leave untouched
        }
        ue()
        when (ue()) {
            0 -> ue()
            1 -> { u(1); se(); se(); repeat(ue()) { se() } }
        }
        val maxRefFrames = ue()
        u(1); ue(); ue()
        if (u(1) == 0) u(1)
        u(1)
        if (u(1) == 1) { ue(); ue(); ue(); ue() }

        if (r.u(1) == 0) {
            // No VUI: add a minimal one that only carries bitstream_restriction
            w.u(1, 1)
            w.u(0, 1); w.u(0, 1); w.u(0, 1); w.u(0, 1); w.u(0, 1) // aspect, overscan, signal, chroma loc, timing
            w.u(0, 1); w.u(0, 1); w.u(0, 1)                       // nal hrd, vcl hrd, pic_struct
            restriction(w, maxRefFrames)
        } else {
            w.u(1, 1)
            if (u(1) == 1 && u(8) == 255) { u(16); u(16) }        // aspect_ratio_info
            if (u(1) == 1) u(1)                                    // overscan
            if (u(1) == 1) { u(3); u(1); if (u(1) == 1) { u(8); u(8); u(8) } } // video signal
            if (u(1) == 1) { ue(); ue() }                          // chroma loc
            if (u(1) == 1) { u(32); u(32); u(1) }                  // timing
            val nalHrd = u(1); val vclHrd = u(1)
            if (nalHrd == 1 || vclHrd == 1) return nal             // HRD params: leave untouched
            u(1)                                                   // pic_struct_present
            if (r.u(1) == 1) { r.u(1); r.ue(); r.ue(); r.ue(); r.ue(); r.ue(); r.ue() } // drop old restriction
            restriction(w, maxRefFrames)
        }
        w.trailing()
        return byteArrayOf(nal[0]) + escape(w.bytes())
    }

    private fun restriction(w: BitWriter, maxRefFrames: Int) {
        w.u(1, 1)          // bitstream_restriction_flag
        w.u(1, 1)          // motion_vectors_over_pic_boundaries_flag
        w.ue(0); w.ue(0)   // max_bytes_per_pic_denom, max_bits_per_mb_denom
        w.ue(16); w.ue(16) // log2_max_mv_length_horizontal/vertical
        w.ue(0)            // max_num_reorder_frames  <= the important one
        w.ue(maxOf(1, maxRefFrames)) // max_dec_frame_buffering
    }

    private fun unescape(d: ByteArray, from: Int): ByteArray {
        val o = ByteArrayOutputStream(d.size)
        var zeros = 0
        for (i in from until d.size) {
            val b = d[i].toInt() and 0xff
            if (zeros >= 2 && b == 3) { zeros = 0; continue }
            o.write(b)
            zeros = if (b == 0) zeros + 1 else 0
        }
        return o.toByteArray()
    }

    private fun escape(d: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(d.size + 8)
        var zeros = 0
        for (x in d) {
            val b = x.toInt() and 0xff
            if (zeros >= 2 && b <= 3) { o.write(3); zeros = 0 }
            o.write(b)
            zeros = if (b == 0) zeros + 1 else 0
        }
        return o.toByteArray()
    }

    private class BitReader(private val d: ByteArray) {
        private var pos = 0
        fun bit(): Int {
            val v = (d[pos shr 3].toInt() shr (7 - (pos and 7))) and 1
            pos++; return v
        }
        fun u(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or bit() }; return v }
        fun ue(): Int { var z = 0; while (bit() == 0) z++; return (1 shl z) - 1 + u(z) }
        fun se(): Int { val k = ue(); return if (k and 1 == 1) (k + 1) / 2 else -(k / 2) }
    }

    private class BitWriter {
        private val o = ByteArrayOutputStream()
        private var cur = 0
        private var n = 0
        fun bit(b: Int) {
            cur = (cur shl 1) or (b and 1); n++
            if (n == 8) { o.write(cur); cur = 0; n = 0 }
        }
        fun u(v: Int, bits: Int) { for (i in bits - 1 downTo 0) bit(v ushr i) }
        fun ue(v: Int) {
            val x = v + 1
            val len = 32 - Integer.numberOfLeadingZeros(x)
            repeat(len - 1) { bit(0) }
            u(x, len)
        }
        fun se(v: Int) = ue(if (v > 0) 2 * v - 1 else -2 * v)
        fun trailing() { bit(1); while (n != 0) bit(0) }
        fun bytes(): ByteArray = o.toByteArray()
    }
}
