object BytecodeFile {
    private const val MAGIC = 0x4D4C4243 // MLBC
    private const val BASE_VERSION = 1
    private const val NATIVE_CALL_VERSION = 2
    private const val FUNCTION_VERSION = 3

    fun encode(chunk: BytecodeChunk): ByteArray {
        val version = validate(chunk, FUNCTION_VERSION)
        val writer = Writer()
        writer.writeInt(MAGIC)
        writer.writeInt(version)
        writer.writeInt(chunk.constants.size())
        for (value in chunk.constants.getAll()) {
            when (value) {
                is MaidValue.IntVal -> {
                    writer.writeByte(1)
                    writer.writeInt(value.value)
                }
                is MaidValue.FloatVal -> {
                    writer.writeByte(2)
                    writer.writeInt(value.value.toRawBits())
                }
                is MaidValue.StringVal -> {
                    val bytes = value.value.encodeToByteArray(throwOnInvalidSequence = true)
                    writer.writeByte(3)
                    writer.writeInt(bytes.size)
                    writer.writeBytes(bytes)
                }
                is MaidValue.CharVal -> {
                    writer.writeByte(4)
                    writer.writeInt(value.value.code)
                }
                else -> error("Unsupported constant type in MLBC")
            }
        }
        writer.writeInt(chunk.code.size)
        chunk.code.forEach(writer::writeInt)
        if (version >= FUNCTION_VERSION) {
            writer.writeInt(chunk.functions.size)
            for (function in chunk.functions) {
                writer.writeInt(function.entry)
                writer.writeInt(function.arity)
                writer.writeInt(function.localCount)
            }
        }
        return writer.toByteArray()
    }

    fun decode(bytes: ByteArray): BytecodeChunk {
        val reader = Reader(bytes)
        require(reader.readInt() == MAGIC) { "Invalid MLBC file signature" }
        val version = reader.readInt()
        require(version in BASE_VERSION..FUNCTION_VERSION) { "Unsupported MLBC version: $version" }

        val chunk = BytecodeChunk()
        val constantCount = reader.readInt()
        require(constantCount >= 0 && reader.remaining >= 4 &&
            constantCount <= (reader.remaining - 4) / 5) { "Invalid MLBC constant count: $constantCount" }
        repeat(constantCount) {
            val value = when (val tag = reader.readByte()) {
                1 -> MaidValue.IntVal(reader.readInt())
                2 -> MaidValue.FloatVal(Float.fromBits(reader.readInt()))
                3 -> MaidValue.StringVal(reader.readString())
                4 -> {
                    val code = reader.readInt()
                    require(code in 0..Char.MAX_VALUE.code) { "Invalid MLBC character: $code" }
                    MaidValue.CharVal(code.toChar())
                }
                else -> error("Unknown MLBC constant tag: $tag")
            }
            chunk.constants.append(value)
        }

        val wordCount = reader.readInt()
        val metadataSize = if (version >= FUNCTION_VERSION) 4 else 0
        require(wordCount >= 0 && reader.remaining >= metadataSize && wordCount <= (reader.remaining - metadataSize) / 4) {
            "Invalid MLBC instruction length: $wordCount"
        }
        repeat(wordCount) { chunk.code.add(reader.readInt()) }
        if (version >= FUNCTION_VERSION) {
            val functionCount = reader.readInt()
            require(functionCount >= 0 && reader.remaining % 12 == 0 && functionCount == reader.remaining / 12) {
                "Invalid MLBC function count: $functionCount"
            }
            repeat(functionCount) {
                chunk.functions.add(CompiledFunction(reader.readInt(), reader.readInt(), reader.readInt()))
            }
        }
        require(reader.remaining == 0) { "Unexpected trailing data in MLBC file" }
        validate(chunk, version)
        return chunk
    }

    private fun validate(chunk: BytecodeChunk, version: Int): Int {
        val starts = BooleanArray(chunk.code.size)
        val jumps = mutableListOf<Triple<Int, Int, Int>>()
        var minimumVersion = BASE_VERSION
        var previousEntry = -1
        for (function in chunk.functions) {
            require(function.entry in chunk.code.indices && function.entry > previousEntry) { "Invalid MLBC function entry: ${function.entry}" }
            require(function.arity >= 0 && function.localCount in function.arity..MAX_FRAME_LOCALS) { "Invalid MLBC function frame size" }
            previousEntry = function.entry
            minimumVersion = FUNCTION_VERSION
        }
        var functionIndex = -1
        var frameStart = 0
        var frameEnd = chunk.functions.firstOrNull()?.entry ?: chunk.code.size
        var index = 0
        while (index < chunk.code.size) {
            if (index == frameEnd) {
                functionIndex++
                frameStart = index
                frameEnd = chunk.functions.getOrNull(functionIndex + 1)?.entry ?: chunk.code.size
            }
            starts[index] = true
            val opcode = Opcode.fromValue(chunk.code[index++])
            when (opcode) {
                Opcode.LOAD_INT, Opcode.LOAD_FLOAT, Opcode.LOAD_STRING, Opcode.LOAD_CHAR -> {
                    val operand = operand(chunk, index++)
                    require(operand in 0 until chunk.constants.size()) { "Invalid MLBC constant index: $operand" }
                    val constant = chunk.constants.getConstant(operand)
                    val matches = when (opcode) {
                        Opcode.LOAD_INT -> constant is MaidValue.IntVal
                        Opcode.LOAD_FLOAT -> constant is MaidValue.FloatVal
                        Opcode.LOAD_STRING -> constant is MaidValue.StringVal
                        Opcode.LOAD_CHAR -> constant is MaidValue.CharVal
                    }
                    require(matches) { "MLBC constant type does not match $opcode at index $operand" }
                }
                Opcode.LOAD_VAR, Opcode.STORE_VAR -> {
                    require(operand(chunk, index++) >= 0) { "Invalid MLBC variable slot" }
                }
                Opcode.JUMP, Opcode.JUMP_IF_TRUE, Opcode.JUMP_IF_FALSE ->
                    jumps.add(Triple(operand(chunk, index++), frameStart, frameEnd))
                Opcode.LOAD_LOCAL, Opcode.STORE_LOCAL -> {
                    require(version >= FUNCTION_VERSION) { "$opcode requires MLBC version $FUNCTION_VERSION" }
                    val slot = operand(chunk, index++)
                    val function = chunk.functions.getOrNull(functionIndex)
                    require(function != null && slot in 0 until function.localCount) { "Invalid MLBC local slot: $slot" }
                    minimumVersion = FUNCTION_VERSION
                }
                Opcode.CALL -> {
                    require(version >= FUNCTION_VERSION) { "CALL requires MLBC version $FUNCTION_VERSION" }
                    val target = operand(chunk, index++)
                    require(target in chunk.functions.indices) { "Invalid MLBC function index: $target" }
                    minimumVersion = FUNCTION_VERSION
                }
                Opcode.POP -> {
                    require(version >= FUNCTION_VERSION) { "POP requires MLBC version $FUNCTION_VERSION" }
                    minimumVersion = FUNCTION_VERSION
                }
                Opcode.CALL_NATIVE -> {
                    require(version >= NATIVE_CALL_VERSION) { "CALL_NATIVE requires MLBC version $NATIVE_CALL_VERSION" }
                    chunk.nativeCall(operand(chunk, index++), operand(chunk, index++))
                    minimumVersion = maxOf(minimumVersion, NATIVE_CALL_VERSION)
                }
                Opcode.ENTER_SCOPE, Opcode.EXIT_SCOPE, Opcode.LOAD_INDEX, Opcode.STORE_INDEX ->
                    error("Unsupported MLBC instruction: $opcode")
                else -> Unit
            }
            require(index <= frameEnd) { "MLBC instruction crosses a function boundary" }
        }
        for ((target, start, end) in jumps) {
            require(target == end || (target in start until end && starts[target])) {
                "Invalid MLBC jump target: $target"
            }
        }
        return minimumVersion
    }

    private fun operand(chunk: BytecodeChunk, index: Int): Int {
        require(index < chunk.code.size) { "Truncated MLBC instruction at index ${index - 1}" }
        return chunk.code[index]
    }

    private class Reader(private val bytes: ByteArray) {
        private var offset = 0
        val remaining get() = bytes.size - offset

        fun readByte(): Int {
            require(remaining >= 1) { "Truncated MLBC file" }
            return bytes[offset++].toInt() and 0xff
        }

        fun readInt(): Int =
            (readByte() shl 24) or (readByte() shl 16) or (readByte() shl 8) or readByte()

        fun readString(): String {
            val length = readInt()
            require(length in 0..remaining) { "Invalid MLBC string length: $length" }
            val end = offset + length
            return bytes.decodeToString(offset, end, throwOnInvalidSequence = true).also { offset = end }
        }
    }

    private class Writer {
        private var buffer = ByteArray(128)
        private var size = 0

        fun writeByte(value: Int) {
            reserve(1)
            buffer[size++] = value.toByte()
        }

        fun writeInt(value: Int) {
            writeByte(value ushr 24)
            writeByte(value ushr 16)
            writeByte(value ushr 8)
            writeByte(value)
        }

        fun writeBytes(bytes: ByteArray) {
            reserve(bytes.size)
            bytes.copyInto(buffer, size)
            size += bytes.size
        }

        fun toByteArray(): ByteArray = buffer.copyOf(size)

        private fun reserve(extra: Int) {
            require(extra <= Int.MAX_VALUE - size) { "MLBC file is too large" }
            val needed = size + extra
            if (needed > buffer.size) {
                val doubled = buffer.size.coerceAtMost(Int.MAX_VALUE / 2) * 2
                buffer = buffer.copyOf(maxOf(needed, doubled))
            }
        }
    }
}
