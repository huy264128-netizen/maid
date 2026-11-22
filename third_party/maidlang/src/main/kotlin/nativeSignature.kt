/** The on-disk signature contains parameter type codes followed by the return type code. */
internal enum class NativeType(val code: Char, val typeName: String) {
    INT('i', "int"),
    FLOAT('f', "float"),
    CHAR('c', "char"),
    STRING('s', "string"),
    BOOL('b', "bool"),
    VOID('v', "void");

    fun accepts(value: MaidValue): Boolean = when (this) {
        INT -> value is MaidValue.IntVal
        FLOAT -> value is MaidValue.FloatVal
        CHAR -> value is MaidValue.CharVal
        STRING -> value is MaidValue.StringVal
        BOOL -> value is MaidValue.IntVal && value.value in 0..1
        VOID -> value == MaidValue.NullVal
    }

    companion object {
        fun fromType(type: Type): NativeType = when (type) {
            Type.IntType -> INT
            Type.FloatType -> FLOAT
            Type.CharType -> CHAR
            Type.StringType -> STRING
            Type.BoolType -> BOOL
            Type.VoidType -> VOID
            else -> error("Unsupported native function type")
        }
    }
}

internal data class NativeSignature(val parameters: List<NativeType>, val result: NativeType) {
    init {
        require(NativeType.VOID !in parameters) { "Native function parameters cannot be void" }
    }

    fun encode(): String = buildString {
        for (parameter in parameters) append(parameter.code)
        append(result.code)
    }

    companion object {
        fun decode(descriptor: String): NativeSignature {
            require(descriptor.isNotEmpty()) { "Missing native function signature" }
            val types = descriptor.map { code ->
                NativeType.entries.firstOrNull { it.code == code }
                    ?: error("Invalid native function type code: $code")
            }
            return NativeSignature(types.dropLast(1), types.last())
        }
    }
}

internal fun BytecodeChunk.nativeCall(nameIndex: Int, signatureIndex: Int): Pair<String, NativeSignature> {
    fun stringConstant(index: Int): String {
        require(index in 0 until constants.size()) { "Invalid native call constant index: $index" }
        val constant = constants.getConstant(index)
        require(constant is MaidValue.StringVal) { "Native call constant must be a string at index $index" }
        return constant.value
    }

    val name = stringConstant(nameIndex)
    require(name.isNotEmpty()) { "Missing native function name" }
    return name to NativeSignature.decode(stringConstant(signatureIndex))
}
