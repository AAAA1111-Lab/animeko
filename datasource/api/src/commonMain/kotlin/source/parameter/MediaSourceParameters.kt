package me.him188.ani.datasources.api.source.parameter

class MediaSourceParameters(
    val list: List<MediaSourceParameter<*>>,
) {
    companion object {
        val Empty = MediaSourceParameters(emptyList())
    }
}

fun MediaSourceParameters.isEmpty() = list.isEmpty()
fun MediaSourceParameters.isNotEmpty() = list.isNotEmpty()

inline fun buildMediaSourceParameters(block: MediaSourceParametersBuilder.() -> Unit): MediaSourceParameters =
    MediaSourceParametersBuilder().apply(block).build()

/**
 * @see buildMediaSourceParameters
 */
open class MediaSourceParametersBuilder {
    private val list = mutableListOf<MediaSourceParameter<*>>()

    /**
     * 增加一个字符串参数
     */
    fun string(
        name: String,
        defaultProvider: (() -> String)? = null,
        description: String? = null,
        placeholder: String? = null,
        visibleWhen: MediaSourceParameterVisibilityCondition? = null,
        label: String? = null,
        validate: (String) -> Boolean = { true },
    ): StringParameter {
        val param = StringParameter(
            name, description,
            default = defaultProvider ?: { "" },
            isRequired = defaultProvider == null,
            placeholder = placeholder,
            visibleWhen = visibleWhen,
            label = label ?: name,
            validate = validate,
        )
        add(param)
        return param
    }

    /**
     * 增加一个开关
     */
    fun boolean(
        name: String,
        default: Boolean,
        description: String? = null,
        visibleWhen: MediaSourceParameterVisibilityCondition? = null,
        label: String? = null,
    ): BooleanParameter {
        val param = BooleanParameter(
            name = name,
            description = description,
            default = { default },
            visibleWhen = visibleWhen,
            label = label ?: name,
        )
        add(param)
        return param
    }

    /**
     * 增加一个枚举参数, 用户可从 [oneOf] 中选择一个. [oneOf] 必须至少有一个元素.
     */
    fun simpleEnum(
        name: String,
        oneOf: List<String>,
        default: String,
        description: String? = null,
        visibleWhen: MediaSourceParameterVisibilityCondition? = null,
        label: String? = null,
    ): SimpleEnumParameter {
        val param = SimpleEnumParameter(
            name = name,
            oneOf = oneOf,
            description = description,
            default = { default },
            visibleWhen = visibleWhen,
            label = label ?: name,
        )
        add(param)
        return param
    }

    /**
     * 增加一个枚举参数, 用户可从 [oneOf] 中选择一个. [oneOf] 必须至少有一个元素.
     */
    fun simpleEnum(
        name: String,
        vararg oneOf: String,
        default: String,
        description: String? = null,
        visibleWhen: MediaSourceParameterVisibilityCondition? = null,
        label: String? = null,
    ) = simpleEnum(name, oneOf.toList(), default, description, visibleWhen, label)

    fun <T> add(parameter: MediaSourceParameter<T>): MediaSourceParameter<T> {
        list.add(parameter)
        return parameter
    }

    fun build(): MediaSourceParameters = MediaSourceParameters(list)
}
