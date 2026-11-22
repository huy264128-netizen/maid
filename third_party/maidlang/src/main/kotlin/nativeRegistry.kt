/**
 * MaidLang 原生函数注册表
 *
 * 独立管理宿主注册的外部函数（供 external fun 声明使用），
 * 可在 Interpreter 与 VM 实例之间直接共享或复制复用。
 */

typealias NativeFunction = (List<MaidValue>) -> MaidValue

class NativeRegistry {
    private val functions = mutableMapOf<String, NativeFunction>()

    /** 注册一个原生函数，同名函数会被新实现覆盖。 */
    fun register(name: String, func: NativeFunction) {
        require(name.isNotBlank()) { "Native function name cannot be blank" }
        functions[name] = func
    }

    /** 批量注册另一个注册表中的所有原生函数。 */
    fun registerAll(other: NativeRegistry) {
        functions.putAll(other.functions)
    }

    /** 从映射批量注册原生函数。 */
    fun registerAll(entries: Map<String, NativeFunction>) {
        for ((name, func) in entries) {
            register(name, func)
        }
    }

    /** 注销指定名称的原生函数，若存在则返回原实现。 */
    fun unregister(name: String): NativeFunction? = functions.remove(name)

    /** 按名称查找原生函数。 */
    operator fun get(name: String): NativeFunction? = functions[name]

    /** 判断指定名称的原生函数是否已注册。 */
    operator fun contains(name: String): Boolean = name in functions

    /** 返回已注册函数名称的只读快照。 */
    fun names(): Set<String> = functions.keys.toSet()

    /** 清空所有已注册的原生函数。 */
    fun clear() {
        functions.clear()
    }

    /** 复制当前注册表，便于在共享基础函数集之上按实例独立扩展。 */
    fun copy(): NativeRegistry = NativeRegistry().apply {
        registerAll(this@NativeRegistry)
    }
}
