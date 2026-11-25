package app.nestro.data

import java.math.BigInteger

/** SemVer 2.0 precedence and a bounded range matcher for community skill dependencies. */
object SkillSemVer {
    private data class Version(val major: BigInteger, val minor: BigInteger, val patch: BigInteger, val pre: List<String>)

    private fun parse(raw: String): Version {
        require(raw.length <= 128) { "技能版本过长" }
        val match = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$").matchEntire(raw)
            ?: error("技能版本格式无效：$raw")
        val pre = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
        require(pre.none { it.isBlank() }) { "预发布版本标识无效" }
        require(pre.none { it.length > 1 && it.all(Char::isDigit) && it.startsWith('0') }) { "预发布数字标识不能包含前导零" }
        val build = raw.substringAfter('+', "").takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
        require(build.none { it.isBlank() }) { "构建元数据标识无效" }
        return Version(BigInteger(match.groupValues[1]), BigInteger(match.groupValues[2]), BigInteger(match.groupValues[3]), pre)
    }

    fun compare(left: String, right: String): Int {
        val a = parse(left); val b = parse(right)
        if (a.major != b.major) return a.major.compareTo(b.major)
        if (a.minor != b.minor) return a.minor.compareTo(b.minor)
        if (a.patch != b.patch) return a.patch.compareTo(b.patch)
        if (a.pre.isEmpty() || b.pre.isEmpty()) return when {
            a.pre.isEmpty() && b.pre.isEmpty() -> 0
            a.pre.isEmpty() -> 1
            else -> -1
        }
        for (i in 0 until minOf(a.pre.size, b.pre.size)) {
            val x=a.pre[i]; val y=b.pre[i]
            val xn=x.takeIf { it.all(Char::isDigit) }?.let(::BigInteger)
            val yn=y.takeIf { it.all(Char::isDigit) }?.let(::BigInteger)
            val c=when {
                xn!=null && yn!=null -> xn.compareTo(yn)
                xn!=null -> -1
                yn!=null -> 1
                else -> x.compareTo(y)
            }
            if(c!=0) return c
        }
        return a.pre.size.compareTo(b.pre.size)
    }

    /**
     * 把范围边界里的省略写法补齐为完整三段式："1" -> "1.0.0"，"1.0" -> "1.0.0"。
     * 仅用于依赖范围的边界；完整版本号与预发布/构建元数据原样走严格 parse。
     * 含前导零（如 "01.0"）或四段以上的不补齐，仍由 parse 抛错。
     */
    private fun normalizePartialVersion(raw: String): String {
        val core = raw.substringBefore('-').substringBefore('+')
        val pre = raw.substring(core.length)
        val parts = core.split('.')
        if (parts.size > 2 || parts.any { it.isEmpty() || !it.matches(Regex("0|[1-9][0-9]*")) }) return raw
        return when (parts.size) {
            1 -> "${parts[0]}.0.0$pre"
            2 -> "${parts[0]}.${parts[1]}.0$pre"
            else -> raw
        }
    }

    fun matches(version: String, range: String): Boolean {
        val candidate=parse(version)
        if(range.isBlank()) return false
        if(range=="*") return candidate.pre.isEmpty()
        return range.split("||").any { alternative ->
            val terms=alternative.trim().split(Regex("[ ,]+")).filter { it.isNotBlank() }
            val allowsPrerelease = terms.any { term ->
                val expectedText = term.removePrefix(">=").removePrefix("<=").removePrefix(">").removePrefix("<").removePrefix("^").removePrefix("~")
                runCatching { parse(normalizePartialVersion(expectedText)).let { it.pre.isNotEmpty() && it.major==candidate.major && it.minor==candidate.minor && it.patch==candidate.patch } }.getOrDefault(false)
            }
            terms.isNotEmpty() && (candidate.pre.isEmpty() || allowsPrerelease) && terms.all { term ->
                if (term == "*") candidate.pre.isEmpty() else {
                    val operator=listOf(">=","<=",">","<","^","~").firstOrNull(term::startsWith)
                    val expectedText=if(operator==null) term else term.removePrefix(operator)
                    // 范围边界允许省略次要/补丁位（如 ">=1.0" 即 ">=1.0.0"，与 npm 风格一致）；
                    // 技能自身的 version 字段仍走严格 parse，不受此影响。
                    val normalizedExpected=normalizePartialVersion(expectedText)
                    val expected=parse(normalizedExpected)
                    val comparison=compare(version,normalizedExpected)
                    when(operator) {
                        null -> comparison==0
                        ">=" -> comparison>=0
                        "<=" -> comparison<=0
                        ">" -> comparison>0
                        "<" -> comparison<0
                        "^" -> comparison>=0 && when {
                            expected.major>BigInteger.ZERO -> candidate.major==expected.major
                            expected.minor>BigInteger.ZERO -> candidate.major==BigInteger.ZERO && candidate.minor==expected.minor
                            else -> candidate.major==BigInteger.ZERO && candidate.minor==BigInteger.ZERO && candidate.patch==expected.patch
                        }
                        "~" -> comparison>=0 && candidate.major==expected.major && candidate.minor==expected.minor
                        else -> false
                    }
                }
            }
        }
    }
}
