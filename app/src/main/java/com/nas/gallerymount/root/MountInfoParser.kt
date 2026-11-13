package com.nas.gallerymount.root

/**
 * 解析 `/proc/.../mountinfo` 單行，解碼八進位跳脫路徑。
 */
data class MountInfoEntry(
    val mountId: Int,
    val parentId: Int,
    val majorMinor: String,
    val root: String,
    val mountPoint: String,
    val mountOptions: String,
    val optionalFields: String,
    val fsType: String,
    val source: String,
    val superOptions: String,
) {
    val isReadOnly: Boolean
        get() = mountOptions.split(',').contains("ro") ||
            superOptions.split(',').contains("ro")
}

object MountInfoParser {
    fun parse(content: String): List<MountInfoEntry> =
        content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { parseLine(it) }
            .toList()

    fun findExactMountPoint(content: String, mountPoint: String): MountInfoEntry? {
        val normalized = mountPoint.trimEnd('/').ifEmpty { "/" }
        return parse(content).lastOrNull { entry ->
            entry.mountPoint.trimEnd('/').ifEmpty { "/" } == normalized
        }
    }

    fun parseLine(line: String): MountInfoEntry? {
        // 格式：id parent major:minor root mountPoint options [optional...] - fstype source super
        val sep = line.indexOf(" - ")
        if (sep < 0) return null
        val left = line.substring(0, sep).trim()
        val right = line.substring(sep + 3).trim()
        val leftParts = left.split(' ')
        if (leftParts.size < 6) return null
        val rightParts = right.split(' ')
        if (rightParts.size < 3) return null
        val optional = if (leftParts.size > 6) {
            leftParts.subList(6, leftParts.size).joinToString(" ")
        } else {
            ""
        }
        return MountInfoEntry(
            mountId = leftParts[0].toIntOrNull() ?: return null,
            parentId = leftParts[1].toIntOrNull() ?: return null,
            majorMinor = leftParts[2],
            root = unescape(leftParts[3]),
            mountPoint = unescape(leftParts[4]),
            mountOptions = leftParts[5],
            optionalFields = optional,
            fsType = rightParts[0],
            source = unescape(rightParts[1]),
            superOptions = rightParts.drop(2).joinToString(" "),
        )
    }

    /** 解碼 mountinfo 的 \xxx 八進位跳脫。 */
    fun unescape(raw: String): String {
        if (!raw.contains('\\')) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 3 < raw.length) {
                val oct = raw.substring(i + 1, i + 4)
                val code = oct.toIntOrNull(8)
                if (code != null) {
                    out.append(code.toChar())
                    i += 4
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }
}
