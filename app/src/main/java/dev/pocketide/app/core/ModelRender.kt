package dev.pocketide.app.core

/** Draws a 3D model as a flat picture (a sprite), seen from a chosen angle. Software rendering, no GPU needed. */
object ModelRender {
    /** Several models can be drawn together (a character made of body parts). yaw turns it around, pitch tilts the camera down. */
    /**
     * [unit] > 0 draws at a fixed scale of that many pixels per model unit (so a giant is bigger than a chicken) with the feet at
     * the bottom of the picture and the figure centred sideways; a figure under 40% of the picture's height is enlarged to that
     * so it stays visible, and one wider than the picture is shrunk to fit. With unit 0 the model is fitted to the picture.
     */
    fun render(meshes: List<Mesh>, size: Int, yaw: Double, pitch: Double, roll: Double = 0.0, unit: Double = 0.0): Png.Image {
        val sz = size.coerceIn(8, 512)
        val v = ArrayList<DoubleArray>(); val tri = ArrayList<IntArray>(); val cols = ArrayList<Int>()
        for (m in meshes) {
            val o = v.size
            for (i in 0 until m.vertexCount()) v.add(doubleArrayOf(m.v[i * 3].toDouble(), m.v[i * 3 + 1].toDouble(), m.v[i * 3 + 2].toDouble()))
            for (i in 0 until m.faceCount()) { tri.add(intArrayOf(m.f[i * 3] + o, m.f[i * 3 + 1] + o, m.f[i * 3 + 2] + o)); cols.add(parse(m.colors.getOrNull(i))) }
        }
        if (v.isEmpty() || tri.isEmpty()) throw IllegalArgumentException("the model has no faces to draw")
        val cx = DoubleArray(3) { k -> (v.minOf { it[k] } + v.maxOf { it[k] }) / 2 }
        val a = Math.toRadians(yaw); val b = Math.toRadians(pitch)
        val n = v.size; val x = DoubleArray(n); val y = DoubleArray(n); val d = DoubleArray(n)
        for (i in 0 until n) {
            val px = v[i][0] - cx[0]; val py = v[i][1] - cx[1]; val pz = v[i][2] - cx[2]
            val rx = px * Math.cos(a) + pz * Math.sin(a); val rz = -px * Math.sin(a) + pz * Math.cos(a)
            x[i] = rx; y[i] = py * Math.cos(b) - rz * Math.sin(b); d[i] = py * Math.sin(b) + rz * Math.cos(b)
            if (roll != 0.0) { val c = Math.cos(Math.toRadians(roll)); val sn = Math.sin(Math.toRadians(roll)); val tx = x[i] * c - y[i] * sn; y[i] = x[i] * sn + y[i] * c; x[i] = tx }
        }
        val ext = maxOf(x.max() - x.min(), y.max() - y.min()).takeIf { it > 1e-9 } ?: 1.0
        val h = y.max() - y.min()
        val s = if (unit > 0) { var u = unit; if (h > 1e-9 && h * u < 0.4 * sz) u = 0.4 * sz / h; if (ext * u > sz - 2) u = (sz - 2) / ext; u } else (sz - 2) / ext
        val mx = (x.max() + x.min()) / 2; val my = (y.max() + y.min()) / 2; val ymin = y.min()
        val sx = DoubleArray(n) { (x[it] - mx) * s + sz / 2.0 }
        val sy = if (unit > 0) DoubleArray(n) { sz * 0.9 - (y[it] - ymin) * s } else DoubleArray(n) { sz / 2.0 - (y[it] - my) * s }
        val px = IntArray(sz * sz); val zb = DoubleArray(sz * sz) { Double.MAX_VALUE }
        val lx = 0.3; val ly = 0.8; val lz = 0.5; val ll = Math.sqrt(lx * lx + ly * ly + lz * lz)
        for ((fi, t) in tri.withIndex()) {
            val p0 = v[t[0]]; val p1 = v[t[1]]; val p2 = v[t[2]]
            val ux = p1[0] - p0[0]; val uy = p1[1] - p0[1]; val uz = p1[2] - p0[2]; val wx = p2[0] - p0[0]; val wy = p2[1] - p0[1]; val wz = p2[2] - p0[2]
            var nx = uy * wz - uz * wy; var ny = uz * wx - ux * wz; var nz = ux * wy - uy * wx
            val nl = Math.sqrt(nx * nx + ny * ny + nz * nz); if (nl == 0.0) continue
            nx /= nl; ny /= nl; nz /= nl
            val shade = 0.55 + 0.45 * Math.abs((nx * lx + ny * ly + nz * lz) / ll)
            val c = cols[fi]; val r = ((c shr 16 and 255) * shade).toInt(); val g = ((c shr 8 and 255) * shade).toInt(); val bl = ((c and 255) * shade).toInt()
            val argb = (255 shl 24) or (r shl 16) or (g shl 8) or bl
            val xs = doubleArrayOf(sx[t[0]], sx[t[1]], sx[t[2]]); val ys = doubleArrayOf(sy[t[0]], sy[t[1]], sy[t[2]]); val ds = doubleArrayOf(d[t[0]], d[t[1]], d[t[2]])
            val den = (ys[1] - ys[2]) * (xs[0] - xs[2]) + (xs[2] - xs[1]) * (ys[0] - ys[2]); if (Math.abs(den) < 1e-9) continue
            val x0 = Math.floor(xs.min()).toInt().coerceAtLeast(0); val x1 = Math.ceil(xs.max()).toInt().coerceAtMost(sz - 1)
            val y0 = Math.floor(ys.min()).toInt().coerceAtLeast(0); val y1 = Math.ceil(ys.max()).toInt().coerceAtMost(sz - 1)
            for (py in y0..y1) for (qx in x0..x1) {
                val fx = qx + 0.5; val fy = py + 0.5
                val w0 = ((ys[1] - ys[2]) * (fx - xs[2]) + (xs[2] - xs[1]) * (fy - ys[2])) / den
                val w1 = ((ys[2] - ys[0]) * (fx - xs[2]) + (xs[0] - xs[2]) * (fy - ys[2])) / den
                val w2 = 1 - w0 - w1
                if (w0 < -1e-6 || w1 < -1e-6 || w2 < -1e-6) continue
                val dd = w0 * ds[0] + w1 * ds[1] + w2 * ds[2]
                if (dd < zb[py * sz + qx]) { zb[py * sz + qx] = dd; px[py * sz + qx] = argb }
            }
        }
        return Png.Image(sz, sz, px)
    }

    /** Cuts away the empty border, so a thin or wide model (a goblin holding a weapon) fills its picture. */
    fun trim(img: Png.Image): Png.Image {
        var x0 = img.width; var x1 = -1; var y0 = img.height; var y1 = -1
        for (y in 0 until img.height) for (x in 0 until img.width) if ((img.argb[y * img.width + x] ushr 24) != 0) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }
        if (x1 < 0) return img
        val w = x1 - x0 + 1; val h = y1 - y0 + 1; val out = IntArray(w * h)
        for (y in 0 until h) System.arraycopy(img.argb, (y0 + y) * img.width + x0, out, y * w, w)
        return Png.Image(w, h, out)
    }

    private fun parse(c: String?): Int = try { 0xFF000000.toInt() or (c ?: "#cccccc").removePrefix("#").toInt(16) } catch (e: NumberFormatException) { 0xFFCCCCCC.toInt() }
}
