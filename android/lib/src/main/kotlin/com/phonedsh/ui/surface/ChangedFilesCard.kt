// The "已编辑 N 个文件" card under a closed turn's answer.
// SPDX-License-Identifier: MIT
//
// Mirror: `examples/probe-app/PhoneDSHProbe/NativeChangedFilesCard.swift`.
//
// What iOS draws, and what this card therefore draws:
//
//   * header 60 pt tall on the selector fill (`:44-46`), a 40 x 40 rounded glyph
//     box (`:19-29`), the title "已编辑 N 个文件" or "已编辑 <file>" when the turn
//     touched exactly one file (`:31-32`), and `+N -M` in the success/danger inks
//     (`:36-41`);
//   * a hairline, then the first four files, one row each: path in the tertiary
//     ink at 12 pt, `+n -m` in monospaced 11 pt (`:51-74`);
//   * a "全部 N 个文件" toggle when the turn touched more than four (`:75-90`),
//     which rotates to 收起 once open;
//   * the card is `colors.input` with a 0.5 pt border and a 12 pt radius (`:93-97`).
//
// The counts are *not* invented here: they come from the same line diff the iOS
// projector runs (`NativeTranscriptProjector.swift:240-254`), ported in
// [lineDelta] and covered by a parity test.
package com.phonedsh.ui.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.phonedsh.ui.UiStrings
import com.phonedsh.ui.theme.LocalUiTokens
import com.phonedsh.ui.theme.LocalUiType

/** Test tag of the changed-files card. */
internal const val CHANGED_FILES_TAG: String = "pdsh.surface.changedFiles"

/** Test tag of one file row inside the card. */
internal const val CHANGED_FILES_ROW_TAG: String = "pdsh.surface.changedFiles.row"

/** Test tag of the "全部 N 个文件" toggle. */
internal const val CHANGED_FILES_TOGGLE_TAG: String = "pdsh.surface.changedFiles.toggle"

/**
 * One file a closed turn changed (iOS `NativeChangedFile`).
 *
 * @param path the workspace-relative path, used to open the file.
 * @param display the line the card prints (iOS falls back to the path).
 * @param added lines the turn added (`+N`).
 * @param deleted lines the turn removed (`-N`).
 */
public data class ChatFileChange(
    public val path: String,
    public val display: String,
    public val added: Int,
    public val deleted: Int,
) {
    /** The file name shown when the turn touched exactly one file. */
    public val fileName: String get() = display.substringAfterLast('/')
}

/**
 * The card that lists the files a closed turn edited.
 *
 * @param changes the turn's files in projection order; empty hides the card.
 * @param modifier the row's placement.
 * @param onOpenFile the host's router for a path; the card never navigates.
 */
@Composable
public fun ChangedFilesCard(
    changes: List<ChatFileChange>,
    modifier: Modifier = Modifier,
    onOpenFile: (String) -> Unit = {},
) {
    if (changes.isEmpty()) return
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    val shape = RoundedCornerShape(tokens.radius.card)
    // iOS keeps the fold in local state (`@State private var expanded`), so it is
    // screen state, not a projection: `remember` is the same lifetime.
    var expanded by remember(changes) { mutableStateOf(false) }
    val single = changes.singleOrNull()
    val visible = if (expanded) changes else changes.take(VISIBLE_FILES)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(CHANGED_FILES_TAG)
            .clip(shape)
            .background(tokens.colors.input)
            .border(width = tokens.stroke.hairline, color = tokens.colors.border, shape = shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // iOS header fill = `NativeChatTheme.selector` (#F5F6F7); the
                // approval-card token already carries that value on this side.
                .background(tokens.colors.approvalCard)
                .clickable(role = Role.Button) { onOpenFile(changes.first().path) }
                // iOS header padding is 10 pt (`:44`) with a 40 pt icon box, which
                // is what makes its fixed 60 pt height (`:45`).
                .padding(tokens.spacing.badgeInsetH),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.badgeInsetH),
        ) {
            Box(
                modifier = Modifier
                    // unverified geometry: the composer's 40 pt trailing reserve is
                    // the only 40 pt token; the icon box is 40 x 40 (`:29`).
                    .size(tokens.geometry.composerTrailingReserve)
                    .clip(RoundedCornerShape(tokens.radius.card))
                    .background(tokens.colors.input)
                    .border(
                        width = tokens.stroke.hairline,
                        color = tokens.colors.border,
                        shape = RoundedCornerShape(tokens.radius.card),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = FILE_GLYPH,
                    style = type.caption.copy(color = tokens.colors.tertiary),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(tokens.geometry.summaryDotDiameter)) {
                BasicText(
                    text = single?.let { UiStrings.CHANGED_FILES_SINGLE.format(it.fileName) }
                        ?: UiStrings.CHANGED_FILES_TITLE.format(changes.size),
                    style = type.secondary.copy(
                        color = tokens.colors.primary,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(tokens.spacing.iconText)) {
                    BasicText(
                        text = "+${changes.sumOf { it.added }}",
                        style = type.codeLabel.copy(color = tokens.colors.success),
                    )
                    BasicText(
                        text = "-${changes.sumOf { it.deleted }}",
                        style = type.codeLabel.copy(color = tokens.colors.danger),
                    )
                }
            }
        }

        if (single == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(tokens.stroke.hairline)
                    .background(tokens.colors.border),
            )
            visible.forEach { file ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { onOpenFile(file.path) }
                        .testTag(CHANGED_FILES_ROW_TAG)
                        // iOS row: 14 h inset and a 44 pt floor *including* it
                        // (`:68-70`), so the floor is outside the padding.
                        .defaultMinSize(minHeight = tokens.geometry.pageRowMinHeight)
                        .padding(horizontal = tokens.geometry.cardPadding)
                        .padding(vertical = tokens.spacing.badgeInsetV),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(tokens.spacing.iconText),
                ) {
                    BasicText(
                        text = file.display,
                        style = type.caption.copy(color = tokens.colors.tertiary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(tokens.spacing.iconText)) {
                        BasicText(
                            text = "+${file.added}",
                            style = type.codeLabel.copy(color = tokens.colors.success),
                        )
                        BasicText(
                            text = "-${file.deleted}",
                            style = type.codeLabel.copy(color = tokens.colors.danger),
                        )
                    }
                }
            }
            if (changes.size > VISIBLE_FILES) {
                val toggle = if (expanded) {
                    UiStrings.CHANGED_FILES_COLLAPSE
                } else {
                    UiStrings.CHANGED_FILES_ALL.format(changes.size)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { expanded = !expanded }
                        .testTag(CHANGED_FILES_TOGGLE_TAG)
                        .semantics {
                            contentDescription = toggle
                            stateDescription = if (expanded) {
                                UiStrings.EXPANDED
                            } else {
                                UiStrings.COLLAPSED
                            }
                        }
                        // iOS toggle: 14 h / 10 v inset inside a 44 pt floor (`:84-86`).
                        .defaultMinSize(minHeight = tokens.geometry.pageRowMinHeight)
                        .padding(horizontal = tokens.geometry.cardPadding)
                        .padding(vertical = tokens.spacing.badgeInsetH),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(tokens.spacing.copyGap),
                ) {
                    BasicText(
                        text = toggle,
                        style = type.caption.copy(color = tokens.colors.tertiary),
                    )
                    BasicText(
                        text = if (expanded) CHEVRON_UP else CHEVRON_DOWN,
                        style = type.caption.copy(color = tokens.colors.tertiary),
                    )
                }
            }
        }
    }
}

/**
 * The iOS line diff (`NativeTranscriptProjector.swift:240-254`): the longest
 * common subsequence of *lines*, then `added = new - common`, `deleted = old - common`.
 *
 * Ported literally, including its treatment of a missing old text as one empty
 * line (a `write` therefore reports `-1` for the line it replaced), because the
 * card's numbers are only right when they equal the iOS projection's.
 *
 * @param old the tool's `old_string` / `old_str`, or `null` for a create.
 * @param new the tool's `new_string` / `content` / `file_text`.
 * @return added then deleted line counts.
 */
public fun lineDelta(old: String?, new: String): Pair<Int, Int> {
    val oldLines = newlineComponents(old ?: "")
    val newLines = newlineComponents(new)
    if (oldLines.size.toLong() * newLines.size.toLong() > MAX_DIFF_CELLS) {
        // The iOS algorithm is O(n*m) in time and memory; so is this port. A file
        // too large to diff is reported wholesale instead of OOMing the transcript.
        return newLines.size to oldLines.size
    }
    val table = Array(oldLines.size + 1) { IntArray(newLines.size + 1) }
    for (i in oldLines.indices.reversed()) {
        for (j in newLines.indices.reversed()) {
            table[i][j] = if (oldLines[i] == newLines[j]) {
                table[i + 1][j + 1] + 1
            } else {
                maxOf(table[i + 1][j], table[i][j + 1])
            }
        }
    }
    val common = table[0][0]
    return (newLines.size - common) to (oldLines.size - common)
}

/**
 * Foundation's `components(separatedBy: .newlines)`: *every* newline character is
 * its own separator, so `\r\n` yields an empty component (the iOS algorithm sees
 * the same list) and an empty string yields one empty line.
 */
private fun newlineComponents(text: String): List<String> {
    val out = mutableListOf<String>()
    val current = StringBuilder()
    text.forEach { char ->
        if (char in NEWLINES) {
            out.add(current.toString())
            current.clear()
        } else {
            current.append(char)
        }
    }
    out.add(current.toString())
    return out
}

/** The four rows the iOS card shows before its "全部" toggle (`:12`). */
private const val VISIBLE_FILES: Int = 4

/** Largest old x new line pair matrix this port will allocate. */
private const val MAX_DIFF_CELLS: Long = 4_000_000

/** The card's glyphs: the iOS side draws asset-catalog icons, Android draws text. */
private const val FILE_GLYPH: String = "▤"
private const val CHEVRON_DOWN: String = "⌄"
private const val CHEVRON_UP: String = "⌃"

/** Foundation `CharacterSet.newlines`. */
private val NEWLINES: Set<Char> = setOf('\n', '\r', '\u000B', '\u000C', '\u0085', '\u2028', '\u2029')
