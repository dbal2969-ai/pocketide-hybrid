package dev.pocketide.app.core

/** Triangle mesh with a colour per face, the model format the game kit's model.js draws (same as game_cache models). */
class Mesh {
    val v = ArrayList<Float>()        // x,y,z flat, y up
    val f = ArrayList<Int>()          // vertex indices, 3 per face
    val colors = ArrayList<String>()  // "#rrggbb" per face

    fun vertexCount() = v.size / 3
    fun faceCount() = f.size / 3
    fun addVertex(x: Float, y: Float, z: Float): Int { v.add(x); v.add(y); v.add(z); return v.size / 3 - 1 }
    fun addFace(a: Int, b: Int, c: Int, color: String) { f.add(a); f.add(b); f.add(c); colors.add(color) }

    fun toJson(id: Int = 0): String {
        val sb = StringBuilder("{\"id\":$id,\"vertices\":[")
        for (i in v.indices) { if (i > 0) sb.append(','); sb.append(num(v[i])) }
        sb.append("],\"faces\":[")
        for (i in f.indices) { if (i > 0) sb.append(','); sb.append(f[i]) }
        sb.append("],\"colors\":[")
        for (i in colors.indices) { if (i > 0) sb.append(','); sb.append('"').append(colors[i]).append('"') }
        sb.append("]}")
        return sb.toString()
    }

    private fun num(x: Float): String { val r = Math.round(x * 1000f) / 1000f; return if (r == Math.rint(r.toDouble()).toFloat() && Math.abs(r) < 1e9) r.toLong().toString() else r.toString() }

    companion object {
        fun hex(r: Int, g: Int, b: Int) = String.format("#%02x%02x%02x", r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))

        fun fromJson(text: String): Mesh {
            val m = JsonText.parse(text) as? Map<*, *> ?: throw IllegalArgumentException("a model file must be a JSON object")
            val vs = (m["vertices"] as? List<*>)?.map { (it as Number).toFloat() } ?: throw IllegalArgumentException("model has no vertices")
            val fs = (m["faces"] as? List<*>)?.map { (it as Number).toInt() } ?: throw IllegalArgumentException("model has no faces")
            val cs = (m["colors"] as? List<*>)?.map { it.toString() } ?: emptyList()
            if (vs.size % 3 != 0 || fs.size % 3 != 0) throw IllegalArgumentException("vertices and faces must come in threes")
            if (fs.any { it < 0 || it >= vs.size / 3 }) throw IllegalArgumentException("a face points outside the vertices")
            val mesh = Mesh(); mesh.v.addAll(vs); mesh.f.addAll(fs)
            for (i in 0 until fs.size / 3) mesh.colors.add(cs.getOrNull(i) ?: "#cccccc")
            return mesh
        }
    }
}
