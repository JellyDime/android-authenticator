/*
 * Copyright (c) 2025 Proton AG
 * This file is part of Proton AG and Proton Authenticator.
 *
 * Proton Authenticator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Proton Authenticator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Proton Authenticator.  If not, see <https://www.gnu.org/licenses/>.
 */

package proton.android.authenticator.features.home.master.presentation

import androidx.compose.runtime.Immutable
import proton.android.authenticator.business.settings.domain.Settings
import proton.android.authenticator.business.settings.domain.SettingsDigitType
import proton.android.authenticator.business.settings.domain.SettingsSearchBarType
import proton.android.authenticator.business.settings.domain.SettingsSortingType
import proton.android.authenticator.business.settings.domain.SettingsThemeType
import proton.android.authenticator.shared.ui.domain.models.UiTextMask
import proton.android.authenticator.shared.ui.domain.theme.ThemeType

data class SyncDialogState(
    val showWarningPasswordDialog: Boolean = false,
    val enableWarningMessage: Boolean = false
)

@Immutable
internal sealed interface HomeMasterState {

    val searchQuery: String

    val showBottomBar: Boolean

    val showFabButton: Boolean

    val showTopSearchBar: Boolean

    val event: HomeMasterEvent

    @Immutable
    data class Empty(
        override val event: HomeMasterEvent,
        internal val isRefreshing: Boolean,
        private val settings: Settings
    ) : HomeMasterState {

        override val searchQuery: String = ""

        override val showBottomBar: Boolean = false

        override val showFabButton: Boolean = false

        override val showTopSearchBar: Boolean = false

        internal val isSyncEnabled: Boolean = settings.isSyncEnabled

    }

    @Immutable
    data class EmptySearch(
        override val searchQuery: String,
        private val settings: Settings
    ) : HomeMasterState {

        override val event: HomeMasterEvent = HomeMasterEvent.Idle

        override val showBottomBar: Boolean = settings.searchBarType == SettingsSearchBarType.Bottom

        override val showTopSearchBar: Boolean = settings.searchBarType == SettingsSearchBarType.Top

        override val showFabButton: Boolean = showTopSearchBar && searchQuery.isEmpty()
    }

    @Immutable
    data object Loading : HomeMasterState {

        override val event: HomeMasterEvent = HomeMasterEvent.Idle

        override val searchQuery: String = ""

        override val showBottomBar: Boolean = false

        override val showFabButton: Boolean = false

        override val showTopSearchBar: Boolean = false

        internal const val PLACEHOLDER_COUNT: Int = 5

    }

    @Immutable
    data class Ready(
        override val event: HomeMasterEvent,
        override val searchQuery: String,
        internal val isRefreshing: Boolean,
        private val entryModelsMap: Map<String, HomeMasterEntryModel>,
        private val entryCodesRemainingTimes: Map<Int, Int>,
        private val settings: Settings
    ) : HomeMasterState {

        override val showBottomBar: Boolean = settings.searchBarType == SettingsSearchBarType.Bottom

        override val showTopSearchBar: Boolean = settings.searchBarType == SettingsSearchBarType.Top

        override val showFabButton: Boolean = showTopSearchBar && searchQuery.isEmpty()

        internal val areCodesHidden: Boolean = settings.isHideCodesEnabled

        internal val animateOnCodeChange: Boolean = settings.isCodeChangeAnimationEnabled

        internal val canSortItems: Boolean = when (settings.sortingType) {
            SettingsSortingType.Manual -> true
            SettingsSortingType.CreatedAsc,
            SettingsSortingType.CreatedDesc,
            SettingsSortingType.IssuerAsc,
            SettingsSortingType.IssuerDesc -> false
        }

        internal val themeType: ThemeType = when (settings.themeType) {
            SettingsThemeType.Dark -> ThemeType.Dark
            SettingsThemeType.Light -> ThemeType.Light
            SettingsThemeType.System -> ThemeType.System
        }

        internal val showBoxesInCode: Boolean = when (settings.digitType) {
            SettingsDigitType.Boxes -> true
            SettingsDigitType.Plain -> false
        }

        internal val entryModels: List<HomeMasterEntryModel> = entryModelsMap.values.toList()

        internal val entryCodeMasks: List<UiTextMask> = buildList {
            if (areCodesHidden) {
                add(UiTextMask.Hidden)
            }
            add(UiTextMask.Totp)
        }

        internal val isSyncEnabled: Boolean = settings.isSyncEnabled

        internal val needsBottomExtraSpace: Boolean =
            settings.searchBarType == SettingsSearchBarType.Top

        internal fun entryModel(id: String): HomeMasterEntryModel? = entryModelsMap[id]

        internal fun getRemainingSeconds(totalSeconds: Int): Int = entryCodesRemainingTimes.getOrDefault(
            key = totalSeconds,
            defaultValue = 0
        )

    }

}
