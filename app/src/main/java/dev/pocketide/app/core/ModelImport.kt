package dev.pocketide.app.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** Import OBJ and glTF/GLB files into a [Mesh] (colour per face), and write a Mesh back out as OBJ + MTL. */
object ModelImport {
    const val MAX_VERTICES = 2_000_000

    // ---------- OBJ ----------
    fun obj(text: String, mtl: String? = null): Mesh {
        val colours = HashMap<String, String>()
        if (mtl != null) {
            var cur = ""
            for (raw in mtl.lines()) {
                val t = raw.trim().split(Regex("\\s+"))
                if (t[0] == "newmtl" && t.size > 1) cur = t[1]
                else if (t[0] == "Kd" && t.size >= 4 && cur.isNotEmpty()) colours[cur] = Mesh.hex((t[1].toFloat() * 255).toInt(), (t[2].toFloat() * 255).toInt(), (t[3].toFloat() * 255).toInt())
            }
        }
        val pos = ArrayList<FloatArray>(); val m = Mesh(); val map = HashMap<Int, Int>()
        var cur = "#b8b8b8"
        for (raw in text.lines()) {
            val line = raw.trim(); if (line.isEmpty() || line[0] == '#') continue
            val t = line.split(Regex("\\s+"))
            when (t[0]) {
                "v" -> { if (t.size < 4) continue; pos.add(floatArrayOf(t[1].toFloat(), t[2].toFloat(), t[3].toFloat())); if (pos.size > MAX_VERTICES) throw IllegalArgumentException("model is too big (over $MAX_VERTICES vertices)") }
                "usemtl" -> cur = colours[t.getOrNull(1) ?: ""] ?: "#b8b8b8"
                "f" -> {
                    val ids = t.drop(1).map { s -> val i = s.substringBefore('/').toInt(); if (i < 0) pos.size + i else i - 1 }
                    if (ids.size < 3) continue
                    if (ids.any { it < 0 || it >= pos.size }) throw IllegalArgumentException("face uses a vertex that does not exist")
                    fun vx(i: Int) = map.getOrPut(i) { m.addVertex(pos[i][0], pos[i][1], pos[i][2]) }
                    for (k in 1 until ids.size - 1) m.addFace(vx(ids[0]), vx(ids[k]), vx(ids[k + 1]), cur)
                }
            }
        }
        if (m.faceCount() == 0) throw IllegalArgumentException("no faces found in the OBJ file")
        return m
    }

    /** OBJ and MTL text for a mesh: one material per distinct colour. */
    fun toObj(m: Mesh, name: String): Pair<String, String> {
        val mtl = StringBuilder(); val obj = StringBuilder("mtllib $name.mtl\n")
        val mats = LinkedHashMap<String, String>()
        for (c in m.colors) if (c !in mats) mats[c] = "c" + mats.size
        for ((c, n) in mats) { val r = Integer.parseInt(c.substring(1, 3), 16) / 255.0; val g = Integer.parseInt(c.substring(3, 5), 16) / 255.0; val b = Integer.parseInt(c.substring(5, 7), 16) / 255.0
            mtl.append("newmtl $n\nKd ${"%.4f".format(r)} ${"%.4f".format(g)} ${"%.4f".format(b)}\n\n") }
        for (i in 0 until m.vertexCount()) obj.append("v ${m.v[i * 3]} ${m.v[i * 3 + 1]} ${m.v[i * 3 + 2]}\n")
        var last = ""
        for (i in 0 until m.faceCount()) {
            val c = m.colors[i]; if (c != last) { obj.append("usemtl ${mats[c]}\n"); last = c }
            obj.append("f ${m.f[i * 3] + 1} ${m.f[i * 3 + 1] + 1} ${m.f[i * 3 + 2] + 1}\n")
        }
        return obj.toString() to mtl.toString()
    }

    // ---------- glTF / GLB ----------
    /** [read] fetches a file next to the glTF (for external .bin buffers). */
    fun gltf(data: ByteArray, read: (String) -> ByteArray?): Mesh {
        var json: String; var glbBin: ByteArray? = null
        if (data.size > 12 && data[0] == 'g'.code.toByte() && data[1] == 'l'.code.toByte() && data[2] == 'T'.code.toByte() && data[3] == 'F'.code.toByte()) {
            val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            var p = 12; var j: String? = null
            while (p + 8 <= data.size) {
                val len = b.getInt(p); val type = b.getInt(p + 4); p += 8
                if (len < 0 || p + len > data.size) throw IllegalArgumentException("GLB is damaged")
                if (type == 0x4E4F534A) j = String(data, p, len, Charsets.UTF_8) else if (type == 0x004E4942 && glbBin == null) glbBin = data.copyOfRange(p, p + len)
                p += (len + 3) and 3.inv()
            }
            json = j ?: throw IllegalArgumentException("GLB has no JSON part")
        } else json = String(data, Charsets.UTF_8)
        val root = JsonText.parse(json) as? Map<*, *> ?: throw IllegalArgumentException("not a glTF file")
        val buffers = (root["buffers"] as? List<*>)?.mapIndexed { i, bf ->
            val uri = (bf as? Map<*, *>)?.get("uri") as? String
            when {
                uri == null -> glbBin ?: throw IllegalArgumentException("buffer $i has no data")
                uri.startsWith("data:") -> Base64.getDecoder().decode(uri.substringAfter("base64,"))
                else -> read(java.net.URLDecoder.decode(uri, "UTF-8")) ?: throw IllegalArgumentException("buffer file '$uri' is missing: put it next to the model")
            }
        } ?: throw IllegalArgumentException("glTF has no buffers")
        val views = root["bufferViews"] as? List<*> ?: emptyList<Any>()
        val accessors = root["accessors"] as? List<*> ?: emptyList<Any>()
        val materials = root["materials"] as? List<*> ?: emptyList<Any>()
        val meshes = root["meshes"] as? List<*> ?: throw IllegalArgumentException("glTF has no meshes")
        val nodes = root["nodes"] as? List<*> ?: emptyList<Any>()
        val out = Mesh()

        fun num(x: Any?) = (x as? Number)?.toDouble() ?: 0.0
        fun read(accIdx: Int): Triple<FloatArray?, IntArray?, Int> {
            val a = accessors.getOrNull(accIdx) as? Map<*, *> ?: throw IllegalArgumentException("accessor $accIdx is missing")
            val count = num(a["count"]).toInt(); val comp = num(a["componentType"]).toInt()
            val type = a["type"] as? String ?: "SCALAR"
            val n = when (type) { "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; else -> throw IllegalArgumentException("unsupported accessor type $type") }
            val bvI = (a["bufferView"] as? Number)?.toInt() ?: throw IllegalArgumentException("sparse or empty accessors are not supported")
            val bv = views.getOrNull(bvI) as? Map<*, *> ?: throw IllegalArgumentException("bufferView $bvI is missing")
            val buf = buffers.getOrNull(num(bv["buffer"]).toInt()) ?: throw IllegalArgumentException("buffer is missing")
            val size = when (comp) { 5120, 5121 -> 1; 5122, 5123 -> 2; 5125, 5126 -> 4; else -> throw IllegalArgumentException("unsupported component type $comp") }
            val stride = num(bv["byteStride"]).toInt().takeIf { it > 0 } ?: (size * n)
            val start = num(bv["byteOffset"]).toInt() + num(a["byteOffset"]).toInt()
            if (count < 0 || count > MAX_VERTICES * 3 || start + (count - 1).coerceAtLeast(0) * stride + size * n > buf.size) throw IllegalArgumentException("accessor $accIdx runs past its buffer")
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            return if (comp == 5126) {
                val f = FloatArray(count * n); for (i in 0 until count) for (k in 0 until n) f[i * n + k] = bb.getFloat(start + i * stride + k * 4)
                Triple(f, null, n)
            } else {
                val iArr = IntArray(count * n)
                for (i in 0 until count) for (k in 0 until n) { val o = start + i * stride + k * size
                    iArr[i * n + k] = when (comp) { 5120 -> bb.get(o).toInt(); 5121 -> bb.get(o).toInt() and 0xff; 5122 -> bb.getShort(o).toInt(); 5123 -> bb.getShort(o).toInt() and 0xffff; else -> bb.getInt(o) } }
                Triple(null, iArr, n)
            }
        }

        fun srgb(c: Double): Int { val v = if (c <= 0.0031308) 12.92 * c else 1.055 * Math.pow(c, 1 / 2.4) - 0.055; return (v * 255 + 0.5).toInt() }
        fun colourOf(prim: Map<*, *>): String {
            val mat = materials.getOrNull((prim["material"] as? Number)?.toInt() ?: -1) as? Map<*, *> ?: return "#b8b8b8"
            val f = ((mat["pbrMetallicRoughness"] as? Map<*, *>)?.get("baseColorFactor") as? List<*>)?.map { num(it) } ?: return "#b8b8b8"
            return Mesh.hex(srgb(f[0]), srgb(f[1]), srgb(f[2]))
        }

        fun mul(a: DoubleArray, b: DoubleArray): DoubleArray { val r = DoubleArray(16); for (c in 0 until 4) for (rr in 0 until 4) { var s = 0.0; for (k in 0 until 4) s += a[k * 4 + rr] * b[c * 4 + k]; r[c * 4 + rr] = s }; return r }
        fun local(node: Map<*, *>): DoubleArray {
            (node["matrix"] as? List<*>)?.let { l -> if (l.size == 16) return DoubleArray(16) { num(l[it]) } }
            val t = (node["translation"] as? List<*>)?.map { num(it) } ?: listOf(0.0, 0.0, 0.0)
            val q = (node["rotation"] as? List<*>)?.map { num(it) } ?: listOf(0.0, 0.0, 0.0, 1.0)
            val s = (node["scale"] as? List<*>)?.map { num(it) } ?: listOf(1.0, 1.0, 1.0)
            val (x, y, z, w) = q
            val m = doubleArrayOf(
                (1 - 2 * (y * y + z * z)) * s[0], (2 * (x * y + z * w)) * s[0], (2 * (x * z - y * w)) * s[0], 0.0,
                (2 * (x * y - z * w)) * s[1], (1 - 2 * (x * x + z * z)) * s[1], (2 * (y * z + x * w)) * s[1], 0.0,
                (2 * (x * z + y * w)) * s[2], (2 * (y * z - x * w)) * s[2], (1 - 2 * (x * x + y * y)) * s[2], 0.0,
                t[0], t[1], t[2], 1.0)
            return m
        }

        fun emit(meshIdx: Int, m: DoubleArray) {
            val mesh = meshes.getOrNull(meshIdx) as? Map<*, *> ?: return
            for (p in (mesh["primitives"] as? List<*>) ?: emptyList<Any>()) {
                val prim = p as? Map<*, *> ?: continue
                val mode = (prim["mode"] as? Number)?.toInt() ?: 4
                if (mode != 4) throw IllegalArgumentException("only triangle meshes are supported (mesh $meshIdx uses mode $mode)")
                val posAcc = ((prim["attributes"] as? Map<*, *>)?.get("POSITION") as? Number)?.toInt() ?: continue
                val pos = read(posAcc).first ?: continue
                val idx = (prim["indices"] as? Number)?.toInt()?.let { read(it).second } ?: IntArray(pos.size / 3) { it }
                val base = out.vertexCount()
                for (i in 0 until pos.size / 3) {
                    val x = pos[i * 3].toDouble(); val y = pos[i * 3 + 1].toDouble(); val z = pos[i * 3 + 2].toDouble()
                    out.addVertex((m[0] * x + m[4] * y + m[8] * z + m[12]).toFloat(), (m[1] * x + m[5] * y + m[9] * z + m[13]).toFloat(), (m[2] * x + m[6] * y + m[10] * z + m[14]).toFloat())
                }
                val col = colourOf(prim)
                var i = 0
                while (i + 2 < idx.size) { out.addFace(base + idx[i], base + idx[i + 1], base + idx[i + 2], col); i += 3 }
                if (out.vertexCount() > MAX_VERTICES) throw IllegalArgumentException("model is too big (over $MAX_VERTICES vertices)")
            }
        }

        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)
        fun walk(i: Int, parent: DoubleArray, depth: Int) {
            if (depth > 64) throw IllegalArgumentException("glTF nodes are nested too deeply")
            val node = nodes.getOrNull(i) as? Map<*, *> ?: return
            val m = mul(parent, local(node))
            (node["mesh"] as? Number)?.let { emit(it.toInt(), m) }
            (node["children"] as? List<*>)?.forEach { walk(num(it).toInt(), m, depth + 1) }
        }
        val scene = (root["scenes"] as? List<*>)?.getOrNull((root["scene"] as? Number)?.toInt() ?: 0) as? Map<*, *>
        val roots = (scene?.get("nodes") as? List<*>)?.map { num(it).toInt() }
        if (roots != null) roots.forEach { walk(it, identity, 0) } else if (nodes.isNotEmpty()) nodes.indices.forEach { walk(it, identity, 0) } else meshes.indices.forEach { emit(it, identity) }
        if (out.faceCount() == 0) throw IllegalArgumentException("no triangles found in the glTF file")
        return out
    }
}
