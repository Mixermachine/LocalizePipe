package de.aarondietz.localizepipe

import com.intellij.DynamicBundle
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.LocalizePipeBundle"

internal object LocalizePipeBundle {
    private val instance = DynamicBundle(LocalizePipeBundle::class.java, BUNDLE)

    @JvmStatic
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any?): String {
        return instance.getMessage(key, *params)
    }
}
