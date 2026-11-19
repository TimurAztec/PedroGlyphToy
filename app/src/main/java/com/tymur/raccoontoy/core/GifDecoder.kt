package com.tymur.raccoontoy.core

/**
 * Minimal streaming GIF87a/89a decoder (pure Kotlin, no Android deps).
 *
 * Emits fully composited ARGB frames (disposal methods 0–3, transparency,
 * local colour tables and interlacing are handled). The canvas array passed to
 * [FrameSink.onFrame] is reused between frames — copy it if you need to keep it.
 */
class GifFormatException(message: String) : Exception(message)

object GifDecoder {

    fun interface FrameSink {
        fun onFrame(argb: IntArray, width: Int, height: Int, delayMs: Int)
    }

    /** @return number of frames emitted */
    fun decode(data: ByteArray, maxFrames: Int = Int.MAX_VALUE, sink: FrameSink): Int {
        if (data.size < 13 || data[0] != 'G'.code.toByte() || data[1] != 'I'.code.toByte() ||
            data[2] != 'F'.code.toByte()
        ) throw GifFormatException("Not a GIF file")

        val r = Reader(data, 6)
        val width = r.u16()
        val height = r.u16()
        if (width <= 0 || height <= 0 || width.toLong() * height > 4096L * 4096L) {
            throw GifFormatException("Bad canvas size ${width}x$height")
        }
        val packed = r.u8()
        r.u8() // background colour index (browsers ignore it; we clear to transparent)
        r.u8() // pixel aspect ratio
        val globalTable = if (packed and 0x80 != 0) r.colorTable(2 shl (packed and 7)) else null

        val canvas = IntArray(width * height)
        var saved: IntArray? = null
        var delayMs = 0
        var transparent = -1
        var disposal = 0
        var count = 0

        loop@ while (r.hasMore() && count < maxFrames) {
            when (r.u8()) {
                0x21 -> { // extension
                    val label = r.u8()
                    if (label == 0xF9) { // graphic control extension
                        val size = r.u8()
                        val p = r.u8()
                        delayMs = r.u16() * 10
                        val ti = r.u8()
                        disposal = (p shr 2) and 7
                        transparent = if (p and 1 != 0) ti else -1
                        r.skip(size - 4)
                    }
                    r.skipSubBlocks()
                }

                0x2C -> { // image descriptor
                    val ix = r.u16()
                    val iy = r.u16()
                    val iw = r.u16()
                    val ih = r.u16()
                    val ip = r.u8()
                    val table = if (ip and 0x80 != 0) r.colorTable(2 shl (ip and 7)) else globalTable
                        ?: throw GifFormatException("No colour table")
                    val interlaced = ip and 0x40 != 0
                    val minCodeSize = r.u8()
                    val pixels = lzwDecode(minCodeSize, r.readSubBlocks(), iw * ih)

                    if (disposal == 3) saved = canvas.copyOf()

                    val rows = if (interlaced) interlaceOrder(ih) else null
                    for (row in 0 until ih) {
                        val y = iy + (rows?.get(row) ?: row)
                        if (y !in 0 until height) continue
                        val src = row * iw
                        val dst = y * width
                        for (col in 0 until iw) {
                            val x = ix + col
                            if (x !in 0 until width) continue
                            val idx = pixels[src + col].toInt() and 0xFF
                            if (idx == transparent) continue
                            canvas[dst + x] = if (idx < table.size) table[idx] else 0xFF000000.toInt()
                        }
                    }

                    sink.onFrame(canvas, width, height, delayMs)
                    count++

                    when (disposal) {
                        2 -> for (y in maxOf(iy, 0) until minOf(iy + ih, height)) {
                            canvas.fill(0, y * width + maxOf(ix, 0), y * width + minOf(ix + iw, width))
                        }
                        3 -> saved?.copyInto(canvas)
                    }
                    delayMs = 0; transparent = -1; disposal = 0
                }

                0x3B -> break@loop // trailer
                else -> break@loop  // tolerate trailing garbage
            }
        }
        if (count == 0) throw GifFormatException("GIF has no frames")
        return count
    }

    private fun interlaceOrder(h: Int): IntArray {
        val order = IntArray(h)
        var i = 0
        for ((start, step) in arrayOf(0 to 8, 4 to 8, 2 to 4, 1 to 2)) {
            var y = start
            while (y < h) { order[i++] = y; y += step }
        }
        return order
    }

    private fun lzwDecode(minCodeSize: Int, data: ByteArray, pixelCount: Int): ByteArray {
        val out = ByteArray(pixelCount)
        if (minCodeSize !in 1..11) throw GifFormatException("Bad LZW code size $minCodeSize")
        val clear = 1 shl minCodeSize
        val eoi = clear + 1
        val prefix = IntArray(4096)
        val suffix = ByteArray(4096)
        val stack = ByteArray(4097)
        for (i in 0 until clear) suffix[i] = i.toByte()

        var codeSize = minCodeSize + 1
        var codeMask = (1 shl codeSize) - 1
        var avail = clear + 2
        var old = -1
        var first = 0
        var datum = 0
        var bits = 0
        var di = 0
        var op = 0

        while (op < pixelCount) {
            while (bits < codeSize) {
                if (di >= data.size) return out // truncated: remaining pixels stay 0
                datum = datum or ((data[di++].toInt() and 0xFF) shl bits)
                bits += 8
            }
            val code = datum and codeMask
            datum = datum ushr codeSize
            bits -= codeSize

            if (code == clear) {
                codeSize = minCodeSize + 1
                codeMask = (1 shl codeSize) - 1
                avail = clear + 2
                old = -1
                continue
            }
            if (code == eoi) break
            if (old == -1) {
                if (code >= clear) break // corrupt stream
                out[op++] = suffix[code]
                old = code
                first = code
                continue
            }
            if (code > avail) break // corrupt stream

            var sp = 0
            var c = code
            if (code == avail) { // KwKwK case
                stack[sp++] = first.toByte()
                c = old
            }
            while (c >= clear) {
                stack[sp++] = suffix[c]
                c = prefix[c]
            }
            first = suffix[c].toInt() and 0xFF
            stack[sp++] = first.toByte()

            if (avail < 4096) {
                prefix[avail] = old
                suffix[avail] = first.toByte()
                avail++
                if (avail and codeMask == 0 && avail < 4096) {
                    codeSize++
                    codeMask = (1 shl codeSize) - 1
                }
            }
            old = code
            while (sp > 0 && op < pixelCount) out[op++] = stack[--sp]
        }
        return out
    }

    private class Reader(private val d: ByteArray, var pos: Int) {
        fun hasMore() = pos < d.size
        fun u8(): Int {
            if (pos >= d.size) throw GifFormatException("Unexpected end of file")
            return d[pos++].toInt() and 0xFF
        }
        fun u16(): Int = u8() or (u8() shl 8)
        fun skip(n: Int) { if (n > 0) pos = minOf(pos + n, d.size) }

        fun colorTable(entries: Int): IntArray = IntArray(entries) {
            (0xFF shl 24) or (u8() shl 16) or (u8() shl 8) or u8()
        }

        fun skipSubBlocks() {
            while (pos < d.size) {
                val n = u8()
                if (n == 0) return
                skip(n)
            }
        }

        fun readSubBlocks(): ByteArray {
            var total = 0
            var p = pos
            while (p < d.size) {
                val n = d[p].toInt() and 0xFF
                p++
                if (n == 0) break
                total += n
                p += n
            }
            val out = ByteArray(total)
            var o = 0
            while (pos < d.size) {
                val n = u8()
                if (n == 0) break
                val len = minOf(n, d.size - pos)
                System.arraycopy(d, pos, out, o, len)
                o += len
                pos += len
            }
            return if (o == total) out else out.copyOf(o)
        }
    }
}
