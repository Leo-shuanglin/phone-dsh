// The Compose theme: the only place the UI reads a token value from.
// SPDX-License-Identifier: MIT
//
// `typography.scale` says rendering = baseSize x platform scale and the token
// stores no absolute unit. On Android that rule is implemented by emitting the
// base size in `sp`: the framework multiplies every `sp` by `fontScale`, so the
// UI never computes a scaled number itself and a changed font scale reaches the
// layout (line heights grow, nothing is clipped). See the `font-scale` row of
// `spec/ui/v1/README.md` §5.
package com.phonedsh.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.phonedsh.ui.theme.generated.HomeSpecData
import com.phonedsh.ui.theme.generated.TokenType
import com.phonedsh.ui.theme.generated.UiTokenData

/** The parity typography roles plus the appended PR22 HomeSpec roles. */
@Immutable
public data class UiType(
    public val body: TextStyle,
    public val secondary: TextStyle,
    public val caption: TextStyle,
    public val code: TextStyle,
    public val codeLabel: TextStyle,
    public val codeAction: TextStyle,
    public val codeExpand: TextStyle,
    public val shimmer: TextStyle,
    public val jsonLabel: TextStyle,
    public val jsonBody: TextStyle,
    /** System type role: Material `titleMedium` is the declared Android mapping (±2pt tolerance). */
    public val headline: TextStyle,
    /** System type role: Material `titleSmall` is the declared Android mapping (±2pt tolerance). */
    public val subheadline: TextStyle,
    /**
     * System type role: iOS `.largeTitle` — 34 pt / 700. Declared by the spec as
     * `typography.largeTitle`; a page dimension title (settings index) reads it.
     *
     * Deliberately the last fields: appending keeps every earlier field's position
     * and meaning, so a positional construction or a destructuring that predates the
     * two system roles cannot silently shift ([fromSpec] uses named arguments).
     */
    public val largeTitle: TextStyle,
    /**
     * System type role: iOS `.body` row label — 17 pt / 400. Declared by the spec as
     * `typography.rowLabel`; settings / session / file list rows read it.
     *
     * @see largeTitle for why the new fields are appended, not inserted.
     */
    public val rowLabel: TextStyle,
    /**
     * 14 pt "text preference" role (`NativeParityTokens.bodySize`): tool rows,
     * folds and in-card copy use `.system(size: 14)` on iOS. Appended for the
     * same positional reason as [largeTitle].
     */
    public val controlBody: TextStyle,
    /**
     * The empty-state title: iOS `ContentUnavailableView` draws it at ~20 pt
     * (measured ink 19.7 against Android's 17). Appended, never inserted.
     */
    public val emptyStateTitle: TextStyle,
    // ---- PR22 HomeSpec type roles (430x932pt) ----------------------------
    // Appended after the parity roles so no earlier position shifts. Sizes and
    // line boxes come from `HomeSpec.swift`; the two weights the Swift file
    // writes as `.semibold`/`.medium` and the CSS weights come from
    // `docs/design/ios-home-design-spec-no-overview.html`, both through the
    // generated `HomeSpecData.types` table.
    /** Nav title: 17.5 / 600 / 22. */
    public val navTitle: TextStyle,
    /** Nav subtitle (folder name): 14.5 / 400 / 20. */
    public val navSubtitle: TextStyle,
    /**
     * PR22 home body: 16 / 24.
     *
     * `body` above stays the parity 17 pt role because the existing bubble
     * surface test and the unported page layer compile against it; the PR22 home
     * page reads this role. Appended, so no earlier position shifts.
     */
    public val homeBody: TextStyle,
    /** Process row: 15 / 400 / 24. */
    public val processBody: TextStyle,
    /** Step-list row: 14 / 400 / 22. */
    public val listItem: TextStyle,
    /** Primary pill label: 16 / 600. */
    public val buttonLabel: TextStyle,
    /** Tag: 12 / 500. */
    public val tagLabel: TextStyle,
    /** Tab bar label: 10.5 / 500 / 14. */
    public val tabLabel: TextStyle,
) {
    public companion object {
        /** Builds the styles from `typography.styles`; `sp` carries the system font scale. */
        public fun fromSpec(): UiType = UiType(
            body = UiTokenData.typeBody.style(),
            secondary = UiTokenData.typeSecondary.style(),
            caption = UiTokenData.typeCaption.style(),
            code = UiTokenData.typeCode.style(),
            codeLabel = UiTokenData.typeCodeLabel.style(),
            codeAction = UiTokenData.typeCodeAction.style(),
            codeExpand = UiTokenData.typeCodeExpand.style(),
            shimmer = UiTokenData.typeShimmer.style(),
            jsonLabel = UiTokenData.typeJsonLabel.style(),
            jsonBody = UiTokenData.typeJsonBody.style(),
            headline = UiTokenData.typeHeadline.style(),
            subheadline = UiTokenData.typeSubheadline.style(),
            largeTitle = UiTokenData.typeLargeTitle.style(),
            rowLabel = UiTokenData.typeRowLabel.style(),
            controlBody = UiTokenData.typeControlBody.style(),
            emptyStateTitle = UiTokenData.typeEmptyStateTitle.style(),
            navTitle = HomeSpecData.types.getValue("typography.navTitle").style(),
            navSubtitle = HomeSpecData.types.getValue("typography.navSubtitle").style(),
            homeBody = HomeSpecData.types.getValue("typography.body").style(),
            processBody = HomeSpecData.types.getValue("typography.processBody").style(),
            listItem = HomeSpecData.types.getValue("typography.listItem").style(),
            buttonLabel = HomeSpecData.types.getValue("typography.buttonLabel").style(),
            tagLabel = HomeSpecData.types.getValue("typography.tagLabel").style(),
            tabLabel = HomeSpecData.types.getValue("typography.tabLabel").style(),
        )
    }
}

/** `baseSize` is emitted in `sp` so the platform scale is the only multiplier. */
internal fun TokenType.style(): TextStyle = TextStyle(
    fontSize = baseSize.sp,
    fontWeight = FontWeight(weight),
    fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
    // HomeSpec's 16/24, 14/22, 17.5/22 roles carry an explicit line box; the
    // older parity roles leave it Unspecified so the platform leading applies.
    lineHeight = lineHeight?.sp ?: TextUnit.Unspecified,
)

/** The resolved tokens of the current theme mode. */
public val LocalUiTokens: ProvidableCompositionLocal<UiTokens> =
    compositionLocalOf { error("PdshTheme is missing: no UiTokens in the composition") }

/** The typography styles of the current theme. */
public val LocalUiType: ProvidableCompositionLocal<UiType> =
    compositionLocalOf { error("PdshTheme is missing: no UiType in the composition") }

/**
 * Provides the PDSH tokens to the tree.
 *
 * There is deliberately no MaterialTheme wrapper: the spec's chrome is drawn
 * from the token table, and a Material colour scheme would silently introduce a
 * second source of colour for `background` (which the spec marks unmeasured).
 */
@Composable
public fun PdshTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val tokens = remember(darkTheme) { UiTokens.resolve(darkTheme) }
    val type = remember { UiType.fromSpec() }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalUiTokens provides tokens,
        LocalUiType provides type,
        content = content,
    )
}

/** Reads the current tokens; only valid inside [PdshTheme]. */
public object PdshThemeValues {
    public val tokens: UiTokens
        @Composable @ReadOnlyComposable get() = LocalUiTokens.current

    public val type: UiType
        @Composable @ReadOnlyComposable get() = LocalUiType.current
}
