package io.github.arenoros.anime365.ui

/** Повторный Back может прийти до перерисовки NavDisplay: корень таба удалять нельзя. */
internal fun <T> MutableList<T>.popAboveRoot() {
    if (size > 1) removeAt(lastIndex)
}
