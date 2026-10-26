package io.github.arenoros.anime365.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.navigation3.runtime.NavKey
import io.github.arenoros.anime365.R
import io.github.arenoros.anime365.core.designsystem.icon.KadrIcons
import io.github.arenoros.anime365.feature.account.navigation.ProfileKey
import io.github.arenoros.anime365.feature.catalog.navigation.CatalogKey
import io.github.arenoros.anime365.feature.catalog.navigation.HomeKey
import io.github.arenoros.anime365.feature.library.navigation.LibraryKey

/** Табы навигации (docs/screens.md, «Навигация»). У каждого — свой стек экранов. */
internal enum class TopLevelDestination(
    val key: NavKey,
    @StringRes val label: Int,
    @DrawableRes val selectedIcon: Int,
    @DrawableRes val unselectedIcon: Int,
) {
    Home(HomeKey, R.string.tab_home, KadrIcons.Home, KadrIcons.HomeOutlined),
    Catalog(CatalogKey.Root, R.string.tab_catalog, KadrIcons.ViewList, KadrIcons.ViewList),
    Library(LibraryKey, R.string.tab_library, KadrIcons.Bookmark, KadrIcons.BookmarkBorder),
    Profile(ProfileKey, R.string.tab_profile, KadrIcons.Person, KadrIcons.PersonOutline),
}
