package de.aarondietz.localizepipe.settings

object Const {
    const val SOURCE_LOCALE_TAG = "en"
    const val OLLAMA_BASE_URL = "http://127.0.0.1:11434"
    const val HUGGING_FACE_BASE_URL = "https://api-inference.huggingface.co"
    const val HUGGING_FACE_DEFAULT_MODEL = "google/translategemma-4b-it"

    const val OPENAI_COMPATIBLE_BASE_URL = "http://127.0.0.1:8080"
    const val OPENAI_COMPATIBLE_DEFAULT_MODEL = "translategemma-4b"

    const val TEMPERATURE = 0.1f
    const val MIN_TEMPERATURE = 0.0
    const val MAX_TEMPERATURE = 2.0
    const val STEP_TEMPERATURE = 0.1

    const val TIMEOUT_SECONDS = 45L
    const val MIN_TIMEOUT_SECONDS = 5L
    const val MAX_TIMEOUT_SECONDS = 600L
    const val STEP_TIMEOUT_SECONDS = 1L

    const val RETRY_COUNT = 1
    const val MIN_RETRY_COUNT = 0
    const val MAX_RETRY_COUNT = 10
    const val STEP_RETRY_COUNT = 1

    const val REMOVE_ADDED_TRAILING_PERIOD = true

    // Project scan defaults
    const val INCLUDE_ANDROID_RESOURCES = true
    const val INCLUDE_COMPOSE_RESOURCES = true
    const val INCLUDE_IDENTICAL_TO_BASE = false
    const val TRACK_SOURCE_CHANGES = true
}
