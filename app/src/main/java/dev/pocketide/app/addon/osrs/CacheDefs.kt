package dev.pocketide.app.addon.osrs

import dev.pocketide.app.core.*

import dev.pocketide.app.addon.osrs.Js5.Reader

/**
 * Item, NPC and object definitions from a game cache (config index 2, groups 10, 9 and 6). Only the fields that matter for
 * building a game from the data are kept: names, options, sizes, stackability, whether an object blocks walking. Parsing stops
 * at an opcode it does not know (newer cache revisions add some), keeps what it has read and marks the entry partial.
 */
object CacheDefs {
    const val CONFIG_INDEX = 2
    const val ITEMS = 10
    const val NPCS = 9
    const val OBJECTS = 6

    class Def(val id: Int, val fields: LinkedHashMap<String, Any?>, val partial: Boolean) {
        val name: String get() = (fields["name"] as? String).orEmpty()
    }

    /**
     * Every definition file of a kind, by id. Old School caches keep them in config index 2 (one group per kind); the 2009
     * caches (build 530, the one 2009scape uses) keep each kind in its own index, 256 ids per group: objects 16, npcs 18, items 19.
     */
    fun files(js: Js5, kind: String): Map<Int, ByteArray>? {
        val g = kindGroup(kind) ?: return null
        js.files(CONFIG_INDEX, g)?.let { if (it.isNotEmpty()) return it }
        val idx = OLD_INDEX[g] ?: return null
        val t = js.table(idx) ?: return null
        val out = HashMap<Int, ByteArray>()
        for (grp in t.groups) { val fs = try { js.files(idx, grp) } catch (e: Exception) { null } ?: continue; for ((f, b) in fs) out[(grp shl 8) + f] = b }
        return out.ifEmpty { null }
    }
    private val OLD_INDEX = mapOf(OBJECTS to 16, NPCS to 18, ITEMS to 19)

    /** True for a 2009-era cache (definitions in their own indexes rather than config index 2). */
    fun oldLayout(js: Js5): Boolean = (js.files(CONFIG_INDEX, ITEMS)?.isEmpty() ?: true) && (js.table(19)?.groups?.size ?: 0) > 20

    fun kindGroup(kind: String): Int? = when (kind) { "items", "item" -> ITEMS; "npcs", "npc" -> NPCS; "objects", "object", "locs" -> OBJECTS; else -> null }

    /** [old]: the definition comes from a 2009-era cache, whose opcodes differ in places (see [item530], [npc530], [obj530]). */
    fun parse(kind: String, id: Int, data: ByteArray, old: Boolean = false): Def = when (kind) {
        "items", "item" -> if (old) item530(id, data) else item(id, data)
        "npcs", "npc" -> if (old) npc530(id, data) else npc(id, data)
        else -> if (old) obj530(id, data) else obj(id, data)
    }

    private fun colourBytes(r: Reader) { repeat(r.u8()) { r.i8() } }
    private fun params530(r: Reader, f: LinkedHashMap<String, Any?>) = params(r, f)

    /** An item of a 2009-era cache (build 530): worn models have no offset byte, 42 is a colour palette, 121-130 exist. */
    fun item530(id: Int, data: ByteArray): Def {
        val f = LinkedHashMap<String, Any?>(); val r = Reader(data); var partial = false
        val worn = ArrayList<Int>(); val wornF = ArrayList<Int>()
        try {
            loop@ while (r.left() > 0) {
                when (val op = r.u8()) {
                    0 -> break@loop
                    1 -> f["model"] = r.u16(); 2 -> f["name"] = r.string()
                    4 -> f["zoom2d"] = r.u16(); 5 -> f["xan2d"] = r.u16(); 6 -> f["yan2d"] = r.u16()
                    7 -> f["xoff2d"] = r.u16().let { if (it > 32767) it - 65536 else it }; 8 -> f["yoff2d"] = r.u16().let { if (it > 32767) it - 65536 else it }
                    11 -> f["stackable"] = true
                    12 -> f["cost"] = r.i32()
                    16 -> f["members"] = true
                    23, 24, 78 -> worn.add(r.u16()); 25, 26, 79 -> wornF.add(r.u16())
                    in 30..34 -> opt(r, f, "options", op - 30, r.string())
                    in 35..39 -> opt(r, f, "inventoryOptions", op - 35, r.string())
                    40 -> swaps(r, f, "recolor"); 41 -> swaps(r, f, "retexture"); 42 -> colourBytes(r)
                    65 -> f["tradeable"] = true
                    90, 91, 92, 93, 95 -> r.u16()
                    96 -> r.u8()
                    97 -> f["noteOf"] = r.u16(); 98 -> f["noteTemplate"] = r.u16()
                    in 100..109 -> { r.u16(); r.u16() }
                    110, 111, 112 -> r.u16()
                    113, 114 -> r.i8()
                    115 -> r.u8()
                    121 -> f["lentOf"] = r.u16(); 122 -> f["lentTemplate"] = r.u16()
                    125, 126 -> r.skip(3)
                    127, 128, 129, 130 -> { r.u8(); r.u16() }
                    249 -> params530(r, f)
                    else -> { partial = true; f["unknownOpcode"] = op; break@loop }
                }
            }
        } catch (e: Exception) { partial = true }
        if (worn.isNotEmpty()) f["wornModels"] = worn
        if (wornF.isNotEmpty()) f["wornModelsFemale"] = wornF
        return Def(id, f, partial)
    }

    /** An NPC of a 2009-era cache: animations come from a shared set (127), many extra visual opcodes. */
    fun npc530(id: Int, data: ByteArray): Def {
        val f = LinkedHashMap<String, Any?>(); val r = Reader(data); var partial = false
        try {
            loop@ while (r.left() > 0) {
                when (val op = r.u8()) {
                    0 -> break@loop
                    1 -> modelList(r, f, "models")
                    2 -> f["name"] = r.string()
                    12 -> f["size"] = r.u8()
                    in 30..34 -> opt(r, f, "actions", op - 30, r.string())
                    40 -> swaps(r, f, "recolor"); 41 -> swaps(r, f, "retexture"); 42 -> colourBytes(r)
                    60 -> shorts(r, r.u8())
                    93 -> f["minimap"] = false
                    95 -> f["combatLevel"] = r.u16()
                    97 -> f["widthScale"] = r.u16(); 98 -> f["heightScale"] = r.u16()
                    99, 109, 111 -> {}
                    100, 101 -> r.i8()
                    102, 103 -> r.u16()
                    106 -> transforms(r, f, false)
                    118 -> transforms(r, f, true)
                    107 -> f["interactable"] = false
                    113 -> { r.u16(); r.u16() }
                    114 -> { r.i8(); r.i8() }
                    115 -> { r.u8(); r.u8() }
                    119 -> r.i8()
                    121 -> repeat(r.u8()) { r.u8(); r.skip(3) }
                    122, 123, 126 -> r.u16()
                    125 -> f["spawnDirection"] = r.i8()
                    127 -> f["animationSet"] = r.u16()
                    128 -> r.u8()
                    134 -> { r.u16(); r.u16(); r.u16(); r.u16(); r.u8() }
                    135, 136 -> { r.u8(); r.u16() }
                    137 -> r.u16()
                    249 -> params530(r, f)
                    else -> { partial = true; f["unknownOpcode"] = op; break@loop }
                }
            }
        } catch (e: Exception) { partial = true }
        return Def(id, f, partial)
    }

    /** An object (loc) of a 2009-era cache. */
    fun obj530(id: Int, data: ByteArray): Def {
        val f = LinkedHashMap<String, Any?>(); val r = Reader(data); var partial = false
        try {
            loop@ while (r.left() > 0) {
                when (val op = r.u8()) {
                    0 -> break@loop
                    1 -> { val ms = ArrayList<Int>(); val ts = ArrayList<Int>(); repeat(r.u8()) { ms.add(r.u16()); ts.add(r.u8()) }; if (ms.isNotEmpty()) { f["models"] = ms; f["modelTypes"] = ts } }
                    2 -> f["name"] = r.string()
                    5 -> modelList(r, f, "models")
                    14 -> f["width"] = r.u8(); 15 -> f["length"] = r.u8()
                    17 -> { f["solid"] = false; f["blocksProjectile"] = false }
                    18 -> f["blocksProjectile"] = false
                    19 -> r.u8()
                    62 -> f["mirror"] = true
                    21, 22, 23, 27, 64, 73, 74, 82, 88, 89, 90, 91, 94, 95, 96, 97, 98 -> {}
                    24 -> r.u16()
                    28, 29, 39 -> r.u8()
                    in 30..34 -> opt(r, f, "actions", op - 30, r.string())
                    40 -> swaps(r, f, "recolor"); 41 -> swaps(r, f, "retexture"); 42 -> colourBytes(r)
                    60 -> f["mapFunction"] = r.u16()
                    65 -> f["scaleX"] = r.u16(); 66 -> f["scaleY"] = r.u16(); 67 -> f["scaleZ"] = r.u16()
                    69 -> r.u8()
                    70 -> f["offsetX"] = r.u16().toShort().toInt(); 71 -> f["offsetY"] = r.u16().toShort().toInt(); 72 -> f["offsetZ"] = r.u16().toShort().toInt()
                    75 -> r.u8()
                    77 -> transforms(r, f, false)
                    92 -> transforms(r, f, true)
                    78 -> { r.u16(); r.u8() }
                    79 -> { r.u16(); r.u16(); r.u8(); shorts(r, r.u8()) }
                    81 -> f["contour"] = r.u8()
                    93 -> r.u16()
                    99, 100 -> { r.u8(); r.u16() }
                    101 -> r.u8()
                    102 -> r.u16()
                    249 -> params530(r, f)
                    else -> { partial = true; f["unknownOpcode"] = op; break@loop }
                }
            }
        } catch (e: Exception) { partial = true }
        return Def(id, f, partial)
    }

    private fun pairs(r: Reader) { repeat(r.u8()) { r.u16(); r.u16() } }
    /** Colour or texture swaps: [[from, to], ...] (face colours are 16-bit HSL values). */
    private fun swaps(r: Reader, f: LinkedHashMap<String, Any?>, key: String) {
        val l = ArrayList<List<Int>>(); repeat(r.u8()) { l.add(listOf(r.u16(), r.u16())) }
        if (l.isNotEmpty()) f[key] = l
    }
    private fun modelList(r: Reader, f: LinkedHashMap<String, Any?>, key: String) { val l = ArrayList<Int>(); repeat(r.u8()) { l.add(r.u16()) }; if (l.isNotEmpty()) f[key] = l }
    private fun shorts(r: Reader, n: Int) { repeat(n) { r.u16() } }
    private fun params(r: Reader, f: LinkedHashMap<String, Any?>) {
        val m = LinkedHashMap<String, Any?>()
        repeat(r.u8()) { val str = r.u8() == 1; val key = r.u24(); m[key.toString()] = if (str) r.string() else r.i32() }
        if (m.isNotEmpty()) f["params"] = m
    }
    private fun opt(r: Reader, f: LinkedHashMap<String, Any?>, key: String, i: Int, s: String) {
        @Suppress("UNCHECKED_CAST") val l = (f.getOrPut(key) { ArrayList<String?>(arrayOfNulls<String>(5).toList()) } as MutableList<String?>)
        l[i] = if (s.equals("hidden", true)) null else s
    }
    private fun smart(r: Reader): Int = if ((r.b[r.p].toInt() and 0xff) < 128) r.u8() else r.u16() - 32768
    private fun largeSmart(r: Reader): Int = if (r.b[r.p].toInt() < 0) r.i32() and 0x7fffffff else r.u16()
    private fun transforms(r: Reader, f: LinkedHashMap<String, Any?>, withDefault: Boolean) {
        val varbit = r.u16().let { if (it == 65535) -1 else it }
        val varp = r.u16().let { if (it == 65535) -1 else it }
        val def = if (withDefault) r.u16().let { if (it == 65535) -1 else it } else -1
        val n = r.u8()
        val kids = ArrayList<Int>(); repeat(n + 1) { kids.add(r.u16().let { if (it == 65535) -1 else it }) }
        f["varbit"] = varbit; f["varp"] = varp; if (withDefault) f["transformDefault"] = def; f["transforms"] = kids
    }

    fun item(id: Int, data: ByteArray): Def {
        val f = LinkedHashMap<String, Any?>(); val r = Reader(data); var partial = false
        val worn = ArrayList<Int>(); val wornF = ArrayList<Int>()
        try {
            loop@ while (r.left() > 0) {
                when (val op = r.u8()) {
                    0 -> break@loop
                    1 -> f["model"] = r.u16(); 2 -> f["name"] = r.string(); 3 -> r.string()
                    4 -> f["zoom2d"] = r.u16(); 5 -> f["xan2d"] = r.u16(); 6 -> f["yan2d"] = r.u16()
                    7 -> f["xoff2d"] = r.u16().let { if (it > 32767) it - 65536 else it }; 8 -> f["yoff2d"] = r.u16().let { if (it > 32767) it - 65536 else it }
                    9 -> r.string()
                    11 -> f["stackable"] = true
                    12 -> f["cost"] = r.i32()
                    13 -> f["wearSlot"] = r.u8(); 14 -> f["hidesSlot"] = r.u8()
                    16 -> f["members"] = true
                    23 -> { worn.add(r.u16()); r.u8() }; 24 -> worn.add(r.u16()); 25 -> { wornF.add(r.u16()); r.u8() }; 26 -> wornF.add(r.u16()); 27 -> f["hidesSlot2"] = r.u8()
                    in 30..34 -> opt(r, f, "options", op - 30, r.string())
                    in 35..39 -> opt(r, f, "inventoryOptions", op - 35, r.string())
                    40 -> swaps(r, f, "recolor")
                    41 -> swaps(r, f, "retexture")
                    42 -> r.u8()
                    43 -> { r.u8(); while (true) { if (r.u8() == 0) break; r.string() } }
                    65 -> f["tradeable"] = true
                    75 -> f["weight"] = r.i16()
                    78 -> worn.add(r.u16()); 79 -> wornF.add(r.u16()); 90, 91, 92, 93 -> r.u16()
                    94 -> f["category"] = r.u16()
                    95 -> f["zan2d"] = r.u16()
                    97 -> f["noteOf"] = r.u16()
                    98 -> f["noteTemplate"] = r.u16()
                    in 100..109 -> { r.u16(); r.u16() }
                    110, 111, 112 -> r.u16()
                    113, 114 -> r.i8()
                    115 -> r.u8()
                    139 -> r.u16(); 140 -> r.u16()
                    148 -> f["placeholderOf"] = r.u16()
                    149 -> f["placeholderTemplate"] = r.u16()
                    249 -> params(r, f)
                    else -> { partial = true; f["unknownOpcode"] = op; break@loop }
                }
            }
        } catch (e: Exception) { partial = true }
        if (worn.isNotEmpty()) f["wornModels"] = worn
        if (wornF.isNotEmpty()) f["wornModelsFemale"] = wornF
        return Def(id, f, partial)
    }

    fun npc(id: Int, data: ByteArray): Def {
        val f = LinkedHashMap<String, Any?>(); val r = Reader(data); var partial = false
        try {
            loop@ while (r.left() > 0) {
                when (val op = r.u8()) {
                    0 -> break@loop
                    1 -> modelList(r, f, "models")
                    2 -> f["name"] = r.string()
                    12 -> f["size"] = r.u8()
                    13 -> f["standAnim"] = r.u16(); 14 -> f["walkAnim"] = r.u16(); 15, 16 -> r.u16()
                    17 -> { f["walkAnim"] = r.u16(); shorts(r, 3) }
                    18 -> f["category"] = r.u16()
                    in 30..34 -> opt(r, f, "actions", op - 30, r.string())
                    40 -> swaps(r, f, "recolor")
                    41 -> swaps(r, f, "retexture")
                    60 -> shorts(r, r.u8())
                    74, 75, 76, 77, 78, 79 -> r.u16()
                    93 -> f["minimap"] = false
                    95 -> f["combatLevel"] = r.u16()
                    97 -> f["widthScale"] = r.u16()
                    98 -> f["heightScale"] = r.u16()
                    99 -> {}
                    100, 101 -> r.i8()
                    102 -> { val bits = r.u8(); var len = 0; var b = bits; while (b != 0) { len++; b = b shr 1 }
                        for (i in 0 until len) if (bits and (1 shl i) != 0) { largeSmart(r); smart(r) } }
                    103 -> r.u16()
                    106 -> transforms(r, f, false)
                    107 -> f["interactable"] = false
                    109 -> {}
                    111 -> f["follower"] = true
                    114, 115 -> shorts(r, if (op == 114) 1 else 4)
                    116, 117 -> shorts(r, 4)
                    118 -> transforms(r, f, true)
                    122, 123 -> {}
                    124 -> r.u16()
                    249 -> params(r, f)
                    else -> { partial = true; f["unknownOpcode"] = op; break@loop }
                }
            }
        } catch (e: Exception) { partial = true }
        return Def(id, f, partial)
    }

    fun obj(id: Int, data: ByteArray): Def {
        val f = LinkedHashMap<String, Any?>(); val r = Reader(data); var partial = false
        try {
            loop@ while (r.left() > 0) {
                when (val op = r.u8()) {
                    0 -> break@loop
                    1 -> { val ms = ArrayList<Int>(); val ts = ArrayList<Int>(); repeat(r.u8()) { ms.add(r.u16()); ts.add(r.u8()) }; if (ms.isNotEmpty()) { f["models"] = ms; f["modelTypes"] = ts } }
                    2 -> f["name"] = r.string()
                    5 -> modelList(r, f, "models")
                    14 -> f["width"] = r.u8()
                    15 -> f["length"] = r.u8()
                    17 -> { f["solid"] = false; f["blocksProjectile"] = false }
                    18 -> f["blocksProjectile"] = false
                    19 -> r.u8()
                    21, 22, 23 -> {}
                    24 -> r.u16()
                    27 -> f["solid"] = true
                    28, 29 -> r.u8()
                    in 30..34 -> opt(r, f, "actions", op - 30, r.string())
                    39 -> r.i8()
                    40 -> swaps(r, f, "recolor")
                    41 -> swaps(r, f, "retexture")
                    61 -> f["category"] = r.u16()
                    62 -> f["mirror"] = true
                    64 -> {}
                    65 -> f["scaleX"] = r.u16()
                    66 -> f["scaleY"] = r.u16()
                    67 -> f["scaleZ"] = r.u16()
                    68 -> r.u16()
                    69 -> r.u8()
                    70 -> f["offsetX"] = r.u16().toShort().toInt(); 71 -> f["offsetY"] = r.u16().toShort().toInt(); 72 -> f["offsetZ"] = r.u16().toShort().toInt()
                    73, 74 -> {}
                    75 -> r.u8()
                    77 -> transforms(r, f, false)
                    78 -> { r.u16(); r.u8(); r.u8() }
                    79 -> { r.u16(); r.u16(); r.u8(); r.u8(); shorts(r, r.u8()) }
                    81 -> f["contour"] = r.u8()
                    82 -> r.u16()
                    89 -> {}
                    90 -> r.skip(3)
                    92 -> transforms(r, f, true)
                    249 -> params(r, f)
                    else -> { partial = true; f["unknownOpcode"] = op; break@loop }
                }
            }
        } catch (e: Exception) { partial = true }
        return Def(id, f, partial)
    }


    const val UNDERLAYS = 1
    const val OVERLAYS = 4

    /** Floor colour (0xRRGGBB, or -1 when it uses a texture only) of an underlay (kind=1) or overlay (kind=4) definition. */
    fun floorColour(group: Int, data: ByteArray): Int {
        val r = Reader(data); var colour = -1
        try {
            while (r.left() > 0) when (r.u8()) {
                0 -> break
                1 -> colour = r.u24()
                2 -> r.u8()
                5, 8 -> {}
                7 -> r.u24()
                else -> break
            }
        } catch (e: Exception) { }
        return colour
    }

    /** Colour (0xRRGGBB or -1) and texture (-1 for none) of a floor definition from a 2009-era cache. */
    fun floor530(data: ByteArray): Pair<Int, Int> {
        val r = Reader(data); var colour = -1; var tex = -1
        try {
            loop@ while (r.left() > 0) when (r.u8()) {
                0 -> break@loop
                1 -> colour = r.u24()
                2 -> tex = r.u8()
                3 -> tex = r.u16().let { if (it == 65535) -1 else it }
                5, 8, 10, 12 -> {}
                7, 13 -> r.u24()
                9 -> r.u16()
                11, 14 -> r.u8()
                else -> break@loop
            }
        } catch (e: Exception) { }
        return colour to tex
    }

    /** The texture an overlay uses (water, for one), or -1 when it is a plain colour. */
    fun floorTexture(data: ByteArray): Int {
        val r = Reader(data); var tex = -1
        try {
            while (r.left() > 0) when (r.u8()) {
                0 -> break
                1 -> r.u24()
                2 -> { val t = r.u8(); if (t != 255) tex = t }
                5, 8 -> {}
                7 -> r.u24()
                else -> break
            }
        } catch (e: Exception) { }
        return tex
    }

    // ---- writing: build a definition from the same fields json/parse produce ----
    private class W { val o = java.io.ByteArrayOutputStream()
        fun u8(v: Int) = o.write(v); fun u16(v: Int) { o.write(v shr 8); o.write(v) }; fun i32(v: Int) { o.write(v ushr 24); o.write(v ushr 16); o.write(v ushr 8); o.write(v) }
        fun u24(v: Int) { o.write(v shr 16); o.write(v shr 8); o.write(v) }
        fun str(s: String) { o.write(s.toByteArray(Charsets.ISO_8859_1)); o.write(0) } }
    private fun int(v: Any?): Int? = (v as? Number)?.toInt() ?: (v as? String)?.toIntOrNull()
    private fun writeOpts(w: W, f: Map<String, Any?>, key: String, first: Int) {
        val l = f[key] as? List<*> ?: return
        for ((i, s) in l.withIndex().take(5)) if (s != null && s.toString().isNotEmpty()) { w.u8(first + i); w.str(s.toString()) }
    }
    private fun writeSwaps(w: W, f: Map<String, Any?>, key: String, op: Int) {
        val l = f[key] as? List<*> ?: return
        if (l.isEmpty()) return
        w.u8(op); w.u8(l.size)
        for (e in l) { val p = e as? List<*> ?: continue; w.u16(int(p.getOrNull(0)) ?: 0); w.u16(int(p.getOrNull(1)) ?: 0) }
    }
    private fun writeParams(w: W, f: Map<String, Any?>) {
        val m = f["params"] as? Map<*, *> ?: return
        if (m.isEmpty()) return
        w.u8(249); w.u8(m.size)
        for ((k, v) in m) { val isStr = v is String; w.u8(if (isStr) 1 else 0); w.u24(k.toString().toInt()); if (v is String) w.str(v) else w.i32(int(v) ?: 0) }
    }

    /** Definition bytes for a kind ("items", "npcs", "objects") from its fields (id and partial are ignored). */
    fun encode(kind: String, f: Map<String, Any?>): ByteArray {
        val w = W()
        fun name() { (f["name"] as? String)?.let { w.u8(2); w.str(it) } }
        when (kind) {
            "items", "item" -> {
                int(f["model"])?.let { w.u8(1); w.u16(it) }
                name()
                if (f["stackable"] == true) w.u8(11)
                int(f["cost"])?.let { w.u8(12); w.i32(it) }
                if (f["members"] == true) w.u8(16)
                int(f["zoom2d"])?.let { w.u8(4); w.u16(it) }; int(f["xan2d"])?.let { w.u8(5); w.u16(it) }; int(f["yan2d"])?.let { w.u8(6); w.u16(it) }
                int(f["xoff2d"])?.let { w.u8(7); w.u16(it and 0xffff) }; int(f["yoff2d"])?.let { w.u8(8); w.u16(it and 0xffff) }; int(f["zan2d"])?.let { w.u8(95); w.u16(it) }
                writeOpts(w, f, "options", 30); writeOpts(w, f, "inventoryOptions", 35)
                writeSwaps(w, f, "recolor", 40); writeSwaps(w, f, "retexture", 41)
                if (f["tradeable"] == true) w.u8(65)
                int(f["weight"])?.let { w.u8(75); w.u16(it and 0xffff) }
                int(f["category"])?.let { w.u8(94); w.u16(it) }
                int(f["noteOf"])?.let { w.u8(97); w.u16(it) }; int(f["noteTemplate"])?.let { w.u8(98); w.u16(it) }
                int(f["placeholderOf"])?.let { w.u8(148); w.u16(it) }; int(f["placeholderTemplate"])?.let { w.u8(149); w.u16(it) }
            }
            "npcs", "npc" -> {
                (f["models"] as? List<*>)?.let { l -> w.u8(1); w.u8(l.size); for (m in l) w.u16(int(m) ?: 0) }
                name()
                int(f["size"])?.let { w.u8(12); w.u8(it) }
                int(f["category"])?.let { w.u8(18); w.u16(it) }
                writeOpts(w, f, "actions", 30)
                writeSwaps(w, f, "recolor", 40); writeSwaps(w, f, "retexture", 41)
                if (f["minimap"] == false) w.u8(93)
                int(f["combatLevel"])?.let { w.u8(95); w.u16(it) }
                int(f["widthScale"])?.let { w.u8(97); w.u16(it) }; int(f["heightScale"])?.let { w.u8(98); w.u16(it) }
                if (f["interactable"] == false) w.u8(107)
                if (f["follower"] == true) w.u8(111)
            }
            else -> {
                val ms = f["models"] as? List<*>; val ts = f["modelTypes"] as? List<*>
                if (ms != null && ts != null && ts.size == ms.size) { w.u8(1); w.u8(ms.size); for (i in ms.indices) { w.u16(int(ms[i]) ?: 0); w.u8(int(ts[i]) ?: 10) } }
                else if (ms != null) { w.u8(5); w.u8(ms.size); for (m in ms) w.u16(int(m) ?: 0) }
                name()
                int(f["width"])?.let { w.u8(14); w.u8(it) }; int(f["length"])?.let { w.u8(15); w.u8(it) }
                if (f["solid"] == false) w.u8(17) else { if (f["blocksProjectile"] == false) w.u8(18); if (f["solid"] == true) w.u8(27) }
                writeOpts(w, f, "actions", 30)
                int(f["category"])?.let { w.u8(61); w.u16(it) }
                writeSwaps(w, f, "recolor", 40); writeSwaps(w, f, "retexture", 41)
                int(f["scaleX"])?.let { w.u8(65); w.u16(it) }; int(f["scaleY"])?.let { w.u8(66); w.u16(it) }; int(f["scaleZ"])?.let { w.u8(67); w.u16(it) }
            }
        }
        writeParams(w, f)
        w.u8(0)
        return w.o.toByteArray()
    }

    /** JSON helpers, kept here for the add-on's own code; they live in core/JsonText.kt. */
    fun parseJson(text: String): Any? = JsonText.parse(text)
    fun json(v: Any?): String = JsonText.write(v)
    fun writeJson(sb: StringBuilder, v: Any?) = JsonText.write(sb, v)
    fun toMap(d: Def): Map<String, Any?> = LinkedHashMap<String, Any?>().also { it["id"] = d.id; it.putAll(d.fields); if (d.partial) it["partial"] = true }
}
