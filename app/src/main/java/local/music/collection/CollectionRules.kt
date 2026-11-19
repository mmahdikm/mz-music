package local.music.collection

import java.util.Locale

object CollectionRules {
    const val MAX_PLAYLISTS = 100
    const val MAX_SONGS = 2000
    fun name(value: String): String = value.trim().also {
        require(it.isNotEmpty() && it.length <= 60 && it.none(Char::isISOControl)) {
            "Use a playlist name of 1–60 characters without control characters."
        }
    }
    fun key(value: String) = name(value).lowercase(Locale.ROOT)
    fun <T> moved(items: List<T>, from: Int, to: Int): List<T> {
        require(from in items.indices && to in items.indices)
        return items.toMutableList().apply { add(to, removeAt(from)) }
    }
}
