package dev.pocketide.app.core

/** Build a model from simple shapes: box, cylinder, cone, sphere, plane. Units are free; y is up. */
object Primitives {
    private fun cyl(m: Mesh, cx: Float, cy: Float, cz: Float, r0: Float, r1: Float, h: Float, seg: Int, col: String) {
        // r0 = radius at the bottom, r1 = radius at the top (0 makes a cone)
        val bot = ArrayList<Int>(); val top = ArrayList<Int>()
        for (i in 0 until seg) {
            val a = (2 * Math.PI * i / seg).toFloat()
            bot.add(m.addVertex(cx + r0 * Math.cos(a.toDouble()).toFloat(), cy, cz + r0 * Math.sin(a.toDouble()).toFloat()))
            top.add(m.addVertex(cx + r1 * Math.cos(a.toDouble()).toFloat(), cy + h, cz + r1 * Math.sin(a.toDouble()).toFloat()))
        }
        for (i in 0 until seg) {
            val j = (i + 1) % seg
            m.addFace(bot[i], bot[j], top[j], col); m.addFace(bot[i], top[j], top[i], col)
        }
        val cb = m.addVertex(cx, cy, cz); val ct = m.addVertex(cx, cy + h, cz)
        for (i in 0 until seg) { val j = (i + 1) % seg; m.addFace(cb, bot[j], bot[i], col); if (r1 > 0f) m.addFace(ct, top[i], top[j], col) }
    }

    private fun box(m: Mesh, cx: Float, cy: Float, cz: Float, w: Float, h: Float, d: Float, col: String) {
        val x0 = cx - w / 2; val x1 = cx + w / 2; val y0 = cy; val y1 = cy + h; val z0 = cz - d / 2; val z1 = cz + d / 2
        val p = intArrayOf(m.addVertex(x0, y0, z0), m.addVertex(x1, y0, z0), m.addVertex(x1, y1, z0), m.addVertex(x0, y1, z0),
            m.addVertex(x0, y0, z1), m.addVertex(x1, y0, z1), m.addVertex(x1, y1, z1), m.addVertex(x0, y1, z1))
        val quads = arrayOf(intArrayOf(0, 3, 2, 1), intArrayOf(4, 5, 6, 7), intArrayOf(0, 4, 7, 3), intArrayOf(1, 2, 6, 5), intArrayOf(3, 7, 6, 2), intArrayOf(0, 1, 5, 4))
        for (q in quads) { m.addFace(p[q[0]], p[q[1]], p[q[2]], col); m.addFace(p[q[0]], p[q[2]], p[q[3]], col) }
    }

    private fun sphere(m: Mesh, cx: Float, cy: Float, cz: Float, r: Float, seg: Int, col: String) {
        val rings = maxOf(3, seg / 2)
        val idx = Array(rings + 1) { IntArray(seg) }
        for (i in 0..rings) for (j in 0 until seg) {
            val th = Math.PI * i / rings; val ph = 2 * Math.PI * j / seg
            idx[i][j] = m.addVertex(cx + (r * Math.sin(th) * Math.cos(ph)).toFloat(), cy + (r * Math.cos(th)).toFloat(), cz + (r * Math.sin(th) * Math.sin(ph)).toFloat())
        }
        for (i in 0 until rings) for (j in 0 until seg) {
            val k = (j + 1) % seg
            if (i > 0) m.addFace(idx[i][j], idx[i + 1][j], idx[i][k], col)
            if (i < rings - 1) m.addFace(idx[i][k], idx[i + 1][j], idx[i + 1][k], col)
        }
    }

    private fun f(m: Map<*, *>, k: String, d: Float) = (m[k] as? Number)?.toFloat() ?: d
    private fun vec(m: Map<*, *>, k: String, d: List<Float>): List<Float> = (m[k] as? List<*>)?.mapNotNull { (it as? Number)?.toFloat() }?.takeIf { it.size == 3 } ?: d

    /** [shapes] is a list of objects: {type, size|radius|height|segments, pos:[x,y,z] (bottom centre), color}. */
    private fun size(o: Map<*, *>): List<Float>? = (o["size"] as? List<*>)?.mapNotNull { (it as? Number)?.toFloat() }?.takeIf { it.isNotEmpty() }
    private fun radius(o: Map<*, *>): Float = (o["radius"] as? Number)?.toFloat() ?: size(o)?.let { it[0] / 2f } ?: 0.5f
    private fun height(o: Map<*, *>): Float = (o["height"] as? Number)?.toFloat() ?: size(o)?.let { it.getOrNull(1) ?: it[0] } ?: 1f

    fun build(shapes: List<*>): Mesh {
        if (shapes.isEmpty()) throw IllegalArgumentException("shapes is empty")
        if (shapes.size > 500) throw IllegalArgumentException("at most 500 shapes")
        val m = Mesh()
        for (s in shapes) {
            val o = s as? Map<*, *> ?: throw IllegalArgumentException("each shape must be an object")
            val p = vec(o, "pos", listOf(0f, 0f, 0f))
            val col = (o["color"] as? String)?.takeIf { Regex("#[0-9a-fA-F]{6}").matches(it) }?.lowercase() ?: "#b0b0b0"
            val seg = ((o["segments"] as? Number)?.toInt() ?: 12).coerceIn(3, 48)
            when ((o["type"] as? String)?.lowercase()) {
                "box", "cube" -> { val sz = vec(o, "size", listOf(1f, 1f, 1f)); box(m, p[0], p[1], p[2], sz[0], sz[1], sz[2], col) }
                // radius and height, or size [width, height, depth] like a box (radius = half the width)
                "cylinder" -> { val r = radius(o); cyl(m, p[0], p[1], p[2], r, r, height(o), seg, col) }
                "cone" -> cyl(m, p[0], p[1], p[2], radius(o), 0f, height(o), seg, col)
                "sphere" -> { val r = radius(o); sphere(m, p[0], p[1] + r, p[2], r, maxOf(seg, 6), col) }
                "plane" -> { val sz = vec(o, "size", listOf(1f, 0f, 1f)); box(m, p[0], p[1], p[2], sz[0], 0.01f, sz[2], col) }
                else -> throw IllegalArgumentException("unknown shape type '${o["type"]}' (box, cylinder, cone, sphere, plane)")
            }
        }
        return m
    }
}
