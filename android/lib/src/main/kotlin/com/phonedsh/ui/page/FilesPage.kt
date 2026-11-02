// Files: the workspace browser, mirrored from the baseline's `FilesPage` listing.
// SPDX-License-Identifier: MIT
//
// Mirror source: `examples/probe-app/PhoneDSHProbe/FilesPage.swift` (read line by
// line; every section, row, control and string below names the lines it copies).
// The page is the baseline's listing: one `Section(fileGroupTitle)` whose rows are
// the sub-folders followed by the files (`FilesPage.swift:115`–`:136`), the search
// field, and the optional 「3D 示例」 toolbar entry.
//
// PR #15 (`8dd17496`) deleted `FilePreviewPage` and moved the preview into the
// standalone `PreviewSurface`; a file row now pushes `PdshRoute.Preview` and one
// back returns to this listing (`FilesPage.swift:59`; `PdshNavHost.kt:632` hands
// the row to the route). The in-page preview that mirrored `FilePreviewPage` is
// gone with it: this route never supplied the preview read, so every preview branch
// was unreachable, and the one preview face's contract is
// `spec/ui/v1/pages/preview-surface.json`.
//
// What the baseline calls "empty" and what it calls "unreadable" are kept apart,
// because the baseline keeps them apart:
//   * an empty workspace (`model.files.isEmpty`, `:102`) is the two-line card
//     「还没有文件」/「智能体做的文件会出现在这里。」;
//   * an empty *sub-directory* is the section title with no rows — the baseline has
//     no "this folder is empty" sentence, so neither does this page.
// A host that has not reported the listing at all is *not* an empty workspace:
// [FilesScreenState.waiting] renders the waiting copy instead of claiming 0 files
// (the baseline's `model.files` is always a materialized array, so it has no such
// state; this is the Android host-snapshot gap, not a baseline behaviour).
//
// ScreenState: `FilesScreenState.from(source, local)` is a pure function of the
// host's read-only snapshot (`files`) and the local navigation script (directory,
// query). §3.1 rule 3 makes that snapshot the UiState-side input on a page with no
// harness event anchor, so the listing and the folder sequence are asserted on the JVM.
//
// Geometry: the page margin and the row floors are page-level tokens
// (`geometry.pageOuterMarginH`, `geometry.pageRowMinHeight`); nothing here invents a
// colour or a length.
package com.phonedsh.ui.page

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import com.phonedsh.ui.ShellFilesMetrics
import com.phonedsh.ui.UiStrings
import com.phonedsh.ui.UiTags
import com.phonedsh.ui.theme.LocalUiTokens
import com.phonedsh.ui.theme.LocalUiType
import com.phonedsh.ui.theme.generated.UiTokenNames
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

// ---------------------------------------------------------------------------
// Facts
// ---------------------------------------------------------------------------

/**
 * One entry of the workspace tree, as the host listed it.
 *
 * @param id the host's stable identity (a row key, never shown).
 * @param relativePath the path relative to the workspace root; the baseline keeps
 *   it and derives the name and the folder from it (`FilesPage.swift:192`,
 *   `:217`), so this page does the same instead of trusting two more host fields.
 * @param size the byte count; `null` when the host did not measure it, which the
 *   row shows as [UiStrings.UNKNOWN] rather than as 0.
 */
@Immutable
public data class WorkspaceFile(
    public val id: String,
    public val relativePath: String,
    public val size: Long? = null,
)

/**
 * Which of the preview surface's bodies a path takes.
 *
 * The baseline decides by extension, in this order (`FilesPage.swift:284`–`:288`,
 * `:361`–`:362` of the pre-PR15 file; `PreviewSurface.swift:66` keeps the same
 * order): HTML pages render in a page surface, Markdown in a Markdown body, the
 * listed text extensions through a text read, and everything else through Quick
 * Look. The order matters: `html` is also in the text-extension list, but the HTML
 * branch wins. `PreviewPathResolver`/`PreviewKind` reuse this classifier, so it
 * stays in the page layer instead of being copied into the preview surface.
 */
public enum class FileKind {
    /** `html` / `htm`: a page surface, rooted at the owning workspace. */
    HTML,

    /** `md` / `markdown`: a Markdown body. */
    MARKDOWN,

    /** One of the listed text extensions: a monospaced, selectable text body. */
    TEXT,

    /** Everything else: Quick Look on iOS (images, PDF, Office, archives). */
    EXTERNAL,
}

/** The baseline's three extension sets, kept in one place so a test can pin them. */
private val HTML_EXTENSIONS: Set<String> = setOf("html", "htm")
private val MARKDOWN_EXTENSIONS: Set<String> = setOf("md", "markdown")

/**
 * `FilesPage.swift:286`–`:288`: the extensions the baseline treats as text, plus
 * the four Android-toolchain extensions (`kt`, `kts`, `java`, `gradle`).
 *
 * The baseline list is an Apple workspace's list; an Android host's workspace is
 * produced by the Kotlin/JVM/Gradle toolchain, so a verbatim copy would render
 * its main source files (`Main.kt`, `build.gradle`) as a binary card. The
 * addition is registered as `platform.workspace-text-extensions` in
 * `spec/platform/whitelist.json`; it changes which files take the text body and
 * nothing else.
 */
private val TEXT_EXTENSIONS: Set<String> = setOf(
    "txt", "md", "markdown", "json", "yaml", "yml", "xml", "html", "htm", "csv", "tsv", "log",
    "swift", "py", "js", "ts", "sh", "toml", "ini", "patch", "diff", "css", "c", "h", "go", "rs",
    // Android toolchain (platform.workspace-text-extensions).
    "kt", "kts", "java", "gradle",
)

/**
 * Classifies a workspace path into the baseline's preview body.
 *
 * Pure and locale-free; the extension is lower-cased exactly like
 * `URL.pathExtension.lowercased()` in the baseline.
 */
public fun classifyFileKind(relativePath: String): FileKind {
    val name = relativePath.substringAfterLast('/')
    val dot = name.lastIndexOf('.')
    // A leading-dot file (".env") has an empty extension in Foundation, which
    // lands in the Quick Look branch, not the text one.
    val extension = if (dot <= 0) "" else name.substring(dot + 1).lowercase()
    return when {
        extension in HTML_EXTENSIONS -> FileKind.HTML
        extension in MARKDOWN_EXTENSIONS -> FileKind.MARKDOWN
        extension in TEXT_EXTENSIONS -> FileKind.TEXT
        else -> FileKind.EXTERNAL
    }
}

/**
 * What a read returned for the file being previewed.
 *
 * The host decides text vs binary (use [classifyFileContent] for a byte read);
 * the page never re-decodes a payload and never renders bytes it could not
 * decode.
 */
public sealed interface FileContentView {
    /** Decodable text; [truncated] is the host's own "I stopped reading" flag. */
    public data class Text(
        public val content: String,
        public val truncated: Boolean = false,
    ) : FileContentView

    /** Not text; [byteCount] may be `null` when the host did not measure it. */
    public data class Binary(public val byteCount: Long? = null) : FileContentView

    /** The host could not read it; [reason] may be `null`. */
    public data class Unreadable(public val reason: String? = null) : FileContentView
}

/**
 * The page's read-only input.
 *
 * @param files every file of the workspace (`ChatModel.files`); `null` means the
 *   host has not reported the listing, which is the waiting state, not an empty
 *   workspace.
 */
@Immutable
public data class FilesSource(
    public val files: List<WorkspaceFile>? = null,
)

// ---------------------------------------------------------------------------
// ScreenState
// ---------------------------------------------------------------------------

/**
 * The complete files screen state.
 *
 * @param groupTitle the section title: the current folder's name, or
 *   「手机工作区」 at the root (`FilesPage.swift:19`–`:23`).
 * @param directory the current directory relative to the workspace root, with a
 *   trailing `/` except at the root (the baseline's `directory`).
 * @param query the search text (`:56`).
 * @param folders the immediate sub-folders, sorted; empty while searching (`:39`).
 * @param files the current directory's files, in the host's order (`:31`).
 * @param waiting the host has not reported the listing (Android host gap).
 * @param workspaceEmpty the host reported a workspace with no files at all
 *   (`:102`); the two-line card replaces the section.
 */
@Immutable
public data class FilesScreenState(
    public val groupTitle: String = UiStrings.FILES_WORKSPACE,
    public val directory: String = "",
    public val query: String = "",
    public val folders: List<String> = emptyList(),
    public val files: List<WorkspaceFile> = emptyList(),
    public val waiting: Boolean = true,
    public val workspaceEmpty: Boolean = false,
) {
    /** True when the current directory is below the workspace root. */
    public val canNavigateUp: Boolean get() = directory.isNotEmpty()

    /** True when the section has no rows: the baseline draws the header alone. */
    public val sectionEmpty: Boolean get() = folders.isEmpty() && files.isEmpty()

    public companion object {
        /**
         * Projects (host snapshot, local navigation script) into the screen state.
         *
         * Pure: the same snapshot and script always yield the same rows. `folders`
         * and `files` are derived exactly like the baseline's computed properties:
         * a path under [FilesLocalState.directory] whose tail has no `/` is a file
         * of this directory, and a tail with one contributes its first segment to
         * `folders`.
         */
        public fun from(
            source: FilesSource,
            local: FilesLocalState = FilesLocalState(),
        ): FilesScreenState {
            val all = source.files
            val directory = local.directory
            val query = local.query

            // `FilesPage.swift:31`: children of the current directory, filtered by
            // the search text only when there is one.
            val files = all.orEmpty()
                .filter { file ->
                    file.relativePath.startsWith(directory) &&
                        file.relativePath.drop(directory.length).let { tail ->
                            if (query.isEmpty()) !tail.contains('/') else tail.contains(query, ignoreCase = true)
                        }
                }
            // `FilesPage.swift:39`: folders disappear while searching, and only the
            // first path segment counts (no recursion).
            val folders = if (query.isNotEmpty()) {
                emptyList()
            } else {
                all.orEmpty()
                    .mapNotNull { file ->
                        if (!file.relativePath.startsWith(directory)) return@mapNotNull null
                        val tail = file.relativePath.drop(directory.length)
                        val slash = tail.indexOf('/')
                        if (slash < 0) null else tail.substring(0, slash)
                    }
                    .distinct()
                    .sorted()
            }

            return FilesScreenState(
                groupTitle = groupTitle(directory),
                directory = directory,
                query = query,
                folders = folders,
                files = files,
                waiting = all == null,
                workspaceEmpty = all != null && all.isEmpty(),
            )
        }

        /**
         * The section title (`FilesPage.swift:19`–`:23`): the last segment of the
         * current directory, or 「手机工作区」 at the root. The disk name
         * (`default-workspace`) never reaches the screen.
         */
        public fun groupTitle(directory: String): String {
            val trimmed = directory.trim('/')
            if (trimmed.isEmpty()) return UiStrings.FILES_WORKSPACE
            return trimmed.substringAfterLast('/').ifEmpty { UiStrings.FILES_WORKSPACE }
        }
    }
}

/** Decimal units for [formatFileSize], matching `ByteCountFormatter`'s `.file` style. */
private const val DECIMAL_UNIT: Long = 1000
private const val TEN: Long = 10
private const val THOUSAND_TENTHS: Long = 10_000
private val DECIMAL_UNITS: List<String> = listOf("KB", "MB", "GB", "TB")

/**
 * Classifies a byte read as text or binary without inventing an encoding.
 *
 * A NUL byte or an invalid UTF-8 sequence means "this is not text"; a decodable
 * payload becomes a text preview. Pure (no clock, no I/O).
 */
public fun classifyFileContent(bytes: ByteArray): FileContentView {
    if (bytes.isEmpty()) return FileContentView.Text("")
    if (bytes.any { it == NUL }) return FileContentView.Binary(bytes.size.toLong())
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        FileContentView.Text(decoder.decode(ByteBuffer.wrap(bytes)).toString())
    } catch (_: CharacterCodingException) {
        // Every byte was still measured; only the text rendering is unavailable.
        FileContentView.Binary(bytes.size.toLong())
    }
}

/**
 * Formats a byte count the way the baseline's `ByteCountFormatter` renders a file
 * size (`countStyle: .file`): decimal units, at most three significant digits, and
 * Foundation's own unit words.
 *
 * This is deliberately a second formatter next to [formatByteSize], not a flag on
 * it: the two answer different questions.
 *
 *  * [formatByteSize] renders a *technical budget* in binary units, because
 *    `spec/v1/budgets.md` §4 fixes `MB`/`MiB` at 1 048 576 bytes and a label has
 *    to match the base the number was computed with (memory pages, budgets, logs).
 *  * this function renders a *file size for a person* exactly as the iOS screen
 *    shows it. The same file must not read `2 KB` on the phone and `1.5 KiB` on
 *    the other; that is the visible difference the mirror exists to remove.
 *
 * Where each one is used, so a reader never has to guess which system a number
 * belongs to: file sizes (the files page's rows and its binary card, and the
 * shell page's 输出上限 / 单文件上限) come from this decimal function; technical
 * budgets (memory, per-turn network volume, the budgets page and logs) come from
 * [formatByteSize]'s binary units. Seeing both on one device is deliberate, and
 * a page that mixes them is a bug.
 *
 * The rounding rules are pinned by tests against a Foundation probe (`swift -e`
 * with `ByteCountFormatter`, zh_CN): zero prints `Zero KB`; below 1 000 bytes the
 * word is `byte`/`bytes`; KB is always whole; MB and above keep one decimal below
 * ten and drop it from ten up; a value that rounds up to 1 000 of its unit
 * promotes to the next one (`999 999 B` → `1 MB`, `999 999 999 B` → `1 GB`).
 */
public fun formatFileSize(bytes: Long): String {
    require(bytes >= 0) { "a byte count is never negative" }
    if (bytes == 0L) return "Zero KB"
    if (bytes < DECIMAL_UNIT) return if (bytes == 1L) "1 byte" else "$bytes bytes"
    var unit = 0
    var divisor = DECIMAL_UNIT
    while (unit < DECIMAL_UNITS.lastIndex && bytes / divisor >= DECIMAL_UNIT) {
        divisor *= DECIMAL_UNIT
        unit += 1
    }
    while (true) {
        // A whole-unit round for KB; one decimal below ten for MB and above, a
        // whole number from ten up. `tenths` is the value in tenths of the unit.
        val tenths = when {
            unit == 0 -> roundWholeUnits(bytes, divisor) * 10
            bytes < TEN * divisor -> roundTenths(bytes, divisor)
            else -> roundTenths(bytes, divisor) / 10 * 10
        }
        if (tenths >= THOUSAND_TENTHS && unit < DECIMAL_UNITS.lastIndex) {
            unit += 1
            divisor *= DECIMAL_UNIT
            continue
        }
        val whole = tenths / 10
        val fraction = tenths % 10
        val label = if (fraction == 0L) "$whole ${DECIMAL_UNITS[unit]}" else "$whole.$fraction ${DECIMAL_UNITS[unit]}"
        return label
    }
}

/** Rounds `bytes / divisor` to tenths of the unit, half away from zero. */
private fun roundTenths(bytes: Long, divisor: Long): Long =
    (bytes * 10 + divisor / 2) / divisor

/** Rounds `bytes / divisor` to whole units, half away from zero. */
private fun roundWholeUnits(bytes: Long, divisor: Long): Long =
    (bytes + divisor / 2) / divisor

/** The local UI events of the files page. */
public sealed interface FilesUiEvent {
    /** Enter a sub-folder: the baseline pushes a page with `directory + name + "/"`. */
    public data class OpenFolder(public val name: String) : FilesUiEvent

    /** Pop one directory (`FilesPage.swift:117`–`:122` pushes; the stack pops). */
    public data object NavigateUp : FilesUiEvent

    /** The search field changed (`:56`). */
    public data class SetQuery(public val query: String) : FilesUiEvent
}

/**
 * The local script; [apply] is pure, so a test drives it like a reducer.
 *
 * @param directory the current directory (the baseline's `@State directory`).
 * @param query the search text.
 */
@Immutable
public data class FilesLocalState(
    public val directory: String = "",
    public val query: String = "",
) {
    /** Applies one local event. */
    public fun apply(event: FilesUiEvent): FilesLocalState = when (event) {
        is FilesUiEvent.OpenFolder ->
            // A pushed FilesPage starts with an empty query (`:25`).
            copy(
                directory = directory + event.name.trim('/') + "/",
                query = "",
            )
        FilesUiEvent.NavigateUp -> copy(directory = parentOf(directory))
        is FilesUiEvent.SetQuery -> copy(query = event.query)
    }

    private fun parentOf(path: String): String {
        val trimmed = path.trimEnd('/')
        val slash = trimmed.lastIndexOf('/')
        return if (slash < 0) "" else trimmed.substring(0, slash + 1)
    }
}

// ---------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------

/**
 * The files screen.
 *
 * @param state the pure screen state ([FilesScreenState.from]).
 * @param onOpenFolder enter a sub-folder (`:117`).
 * @param onOpenFile open a file: the files route hands the row to `PdshRoute.Preview`
 *   and one back returns here (`:125`–`:134`, PR #15's push).
 * @param onQueryChange the search text changed (`:56`).
 * @param onNavigateUp go to the parent directory (the baseline's back button).
 * @param onBack leave the page.
 * @param onOpenDemo the toolbar's 「3D 示例」 (`:79`–`:86`). The entry is always
 *   drawn, because the baseline always draws it (`FilesPage.swift:79`): when the
 *   host supplies no demo surface the page opens its own sheet, so a null
 *   callback can no longer make a labelled control exist in the source and never
 *   render — the hole `page_structure_check.py` could not see.
 */
@Composable
public fun FilesPage(
    state: FilesScreenState,
    onOpenFolder: (String) -> Unit,
    onOpenFile: (WorkspaceFile) -> Unit,
    onQueryChange: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenDemo: (() -> Unit)? = null,
) {
    // back-gesture (§5): a sub-directory is one level below its parent, so the
    // header's back walks the same stack the baseline's NavigationStack walks.
    val onHeaderBack = if (state.canNavigateUp) onNavigateUp else onBack
    // The toolbar entry is drawn unconditionally; this sheet is the destination
    // when the host passes no `onOpenDemo`. iOS presents the bundled
    // `demo/spin.html` in a `PDSHPageView`; `:lib` ships no WebView and no
    // bundled library page, so the sheet carries the app's own 3D answer
    // (`UiStrings.PREVIEW_REASON_MODEL`) instead of pretending to render a cube.
    var showingDemo by rememberSaveable { mutableStateOf(false) }
    val openDemo: () -> Unit = onOpenDemo ?: { showingDemo = true }

    PageFrame(
        title = state.groupTitle,
        onBack = onHeaderBack,
        rootTag = UiPageTags.FILES_ROOT,
        modifier = modifier,
        actions = {
            HeaderAction(
                label = UiStrings.FILES_DEMO,
                tag = UiPageTags.FILES_DEMO,
                onClick = openDemo,
                description = UiStrings.FILES_DEMO_OPEN,
            )
        },
    ) {
        ListingBody(
            state = state,
            onOpenFolder = onOpenFolder,
            onOpenFile = onOpenFile,
            onQueryChange = onQueryChange,
        )
    }
    if (showingDemo) {
        DemoSheet(onDismiss = { showingDemo = false })
    }
}

/**
 * The 「3D 示例」 sheet (`FilesPage.swift:62`–`:75`).
 *
 * The baseline's sheet is a `NavigationStack` titled 3D 示例 with a 完成 cancel
 * key; the body is a bundled web page. Android keeps the sheet, its title and
 * its cancel key, and puts the app's documented 3D answer in the body.
 */
@Composable
private fun DemoSheet(onDismiss: () -> Unit) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            BasicText(
                text = UiStrings.FILES_PREVIEW_DONE,
                style = type.secondary.copy(color = tokens.colors.accent),
                modifier = Modifier
                    .testTag(UiPageTags.FILES_DEMO_DONE)
                    .clickable(role = Role.Button, onClick = onDismiss)
                    .padding(tokens.spacing.iconText),
            )
        },
        title = {
            BasicText(
                text = UiStrings.FILES_DEMO,
                style = type.rowLabel.copy(color = tokens.colors.primary),
            )
        },
        text = {
            BasicText(
                text = UiStrings.PREVIEW_REASON_MODEL,
                style = type.secondary.copy(color = tokens.colors.secondary),
            )
        },
        containerColor = tokens.colors.input,
    )
}

/** The listing: the search field above the one section (`FilesPage.swift:100`). */
@Composable
private fun ListingBody(
    state: FilesScreenState,
    onOpenFolder: (String) -> Unit,
    onOpenFile: (WorkspaceFile) -> Unit,
    onQueryChange: (String) -> Unit,
) {
    // The search field is pinned at the top; the IME must shrink the list under it
    // instead of covering the last rows, which a no-op scroll cannot lift
    // (spec §5 row `insets`: the page consumes its IME inset, not the host).
    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        SearchField(query = state.query, onQueryChange = onQueryChange)
        when {
            // A host that has not reported the listing: waiting, not "no files".
            state.waiting -> EmptyHint(
                title = UiStrings.WAITING_ENGINE,
                hint = null,
                tag = UiTags.FILES_EMPTY,
            )

            // `:102`–`:113`: the whole workspace has no file; the baseline's card
            // is a List row, not a Section.
            state.workspaceEmpty -> NoFilesCard()

            // `:115`–`:136`: one section, folders first, then the files. When both
            // are empty the baseline still draws the section title, so this does
            // too (`sectionEmpty`).
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(UiTags.FILES_LIST),
            ) {
                item(key = "section") {
                    SessionSectionLabel(
                        text = state.groupTitle,
                        modifier = Modifier
                            .testTag(UiPageTags.FILES_SECTION)
                            .semantics { heading() },
                    )
                }
                items(items = state.folders, key = { "folder.$it" }) { folder ->
                    FolderRow(folder = folder, onOpenFolder = onOpenFolder)
                }
                items(items = state.files, key = { "file.${it.id}" }) { file ->
                    FileRow(file = file, onOpenFile = onOpenFile)
                }
            }
        }
    }
}

/**
 * 「搜索文件」 (`FilesPage.swift:56`), drawn as the baseline's grey capsule.
 *
 * `ios-files.png` measures the field as a 40 pt capsule at x 16–385.7, fill
 * `#EFEFF0`, with a 20 pt magnifier before the placeholder. The token table has no
 * search-field token, so the capsule takes `colors.selector` (the closest surface
 * fill), the page row's 44 pt floor and the page margin; the shape is a capsule
 * (height/2), not a new length.
 */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = type.body.copy(color = tokens.colors.primary),
        cursorBrush = SolidColor(tokens.colors.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = tokens.geometry.pageOuterMarginH,
                vertical = tokens.spacing.badgeInsetV,
            )
            // Shared metric: the baseline's 44 pt capsule (`ShellFilesMetrics`).
            .clip(RoundedCornerShape(ShellFilesMetrics.searchRadius))
            .background(tokens.colors.selector)
            .padding(horizontal = tokens.spacing.cardInset)
            .defaultMinSize(minHeight = ShellFilesMetrics.searchHeight)
            .testTag(UiPageTags.FILES_SEARCH)
            .semantics { contentDescription = UiStrings.FILES_SEARCH },
        decorationBox = { inner ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(tokens.spacing.iconText),
            ) {
                SearchGlyph()
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        BasicText(
                            text = UiStrings.FILES_SEARCH,
                            style = type.body.copy(color = tokens.colors.tertiary),
                        )
                    }
                    inner()
                }
            }
        },
    )
}

/** 「还没有文件」 + 「智能体做的文件会出现在这里。」 (`FilesPage.swift:103`–`:113`). */
@Composable
private fun NoFilesCard() {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiPageTags.FILES_NONE)
            .padding(
                horizontal = tokens.geometry.pageOuterMarginH,
                // `ios-files.png`: the two-line card sits 24 pt under the search field.
                vertical = tokens.spacing.cardInset * 2,
            )
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.iconText),
    ) {
        BasicText(
            text = UiStrings.FILES_NONE,
            style = type.headline.copy(color = tokens.colors.primary),
        )
        BasicText(
            text = UiStrings.FILES_NONE_HINT,
            style = type.secondary.copy(
                color = tokens.colors.secondary,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

/** A sub-folder row: the baseline's `Label(folder, systemImage: "folder")` (`:120`). */
@Composable
private fun FolderRow(folder: String, onOpenFolder: (String) -> Unit) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiPageTags.FILES_FOLDER_ROW)
            .clickable(role = Role.Button, onClick = { onOpenFolder(folder) })
            // Page-level geometry: a folder row is a page row (`minHeight: 44`, `:121`).
            .defaultMinSize(minHeight = tokens.geometry.pageRowMinHeight)
            .padding(
                horizontal = tokens.geometry.pageOuterMarginH,
                vertical = tokens.spacing.badgeInsetV,
            )
            .semantics(mergeDescendants = true) { contentDescription = folder },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.iconText),
    ) {
        FolderGlyph()
        BasicText(
            text = folder,
            style = type.body.copy(color = tokens.colors.primary),
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        BasicText(
            text = CHEVRON,
            style = type.caption.copy(color = tokens.colors.tertiary),
        )
    }
}

/**
 * One file row (`FilesPage.swift:184`–`:222`): name, then the folder it lives in,
 * then the size, then a chevron. The full relative path never reaches the screen
 * (`:215`): only the last two segments do.
 */
@Composable
private fun FileRow(file: WorkspaceFile, onOpenFile: (WorkspaceFile) -> Unit) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    val folder = folderName(file.relativePath)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiPageTags.FILES_FILE_ROW)
            .clickable(role = Role.Button, onClick = { onOpenFile(file) })
            .padding(
                horizontal = tokens.geometry.pageOuterMarginH,
                vertical = tokens.spacing.badgeInsetV,
            )
            .semantics(mergeDescendants = true) { contentDescription = fileName(file.relativePath) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.iconText),
    ) {
        // Shared metric: `FilesPage.swift:189` reserves a 34 x 38 pt glyph slot.
        Box(
            modifier = Modifier.size(
                width = ShellFilesMetrics.fileRowGlyphSlotWidth,
                height = ShellFilesMetrics.fileRowGlyphSlotHeight,
            ),
            contentAlignment = Alignment.Center,
        ) {
            FileGlyph()
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.badgeInsetV),
        ) {
            BasicText(
                text = fileName(file.relativePath),
                style = type.body.copy(color = tokens.colors.primary),
                maxLines = 1,
            )
            if (folder != null) {
                BasicText(
                    text = folder,
                    style = type.caption.copy(color = tokens.colors.secondary),
                    maxLines = 1,
                )
            }
            BasicText(
                // A measured size, or 未知: never a rounded guess.
                text = file.size?.let { formatFileSize(it) } ?: UiStrings.UNKNOWN,
                style = type.caption.copy(color = tokens.colors.tertiary),
            )
        }
        BasicText(
            text = CHEVRON,
            style = type.caption.copy(color = tokens.colors.tertiary),
        )
    }
}

/** A header text action; the page never draws a bar button it cannot label. */
@Composable
private fun HeaderAction(
    label: String,
    tag: String,
    onClick: () -> Unit,
    description: String = label,
) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    BasicText(
        text = label,
        style = type.secondary.copy(color = tokens.colors.accent),
        maxLines = 1,
        modifier = Modifier
            .testTag(tag)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(tokens.spacing.iconText),
    )
}

/**
 * The folder glyph (`FileTypeGlyph.folder(size: 20)` on iOS).
 *
 * `:lib` ships no image assets and no icon token, so the shape is drawn at the
 * icon-to-text gap times four (24 dp, the baseline's 20–24 pt icon) from the
 * theme's accent ink. It is the baseline's blue folder, not a platform icon font.
 */
@Composable
private fun FolderGlyph() {
    val tokens = LocalUiTokens.current
    val ink = tokens.colors.accent
    Canvas(modifier = Modifier.size(tokens.spacing.iconText * 4)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(0f, h * FOLDER_BOTTOM_RATIO)
            lineTo(0f, h * FOLDER_TAB_TOP_RATIO)
            lineTo(w * FOLDER_TAB_WIDTH_RATIO, h * FOLDER_TAB_TOP_RATIO)
            lineTo(w * FOLDER_TAB_SLANT_RATIO, h * FOLDER_TAB_BOTTOM_RATIO)
            lineTo(w, h * FOLDER_TAB_BOTTOM_RATIO)
            lineTo(w, h * FOLDER_BOTTOM_RATIO)
            close()
        }
        drawPath(path = path, color = ink)
    }
}

/**
 * The file glyph (`FileTypeGlyph.icon(for:size: 24)` on iOS).
 *
 * The baseline picks a different bundled asset per extension (DSHCodeKotlin,
 * DSHFilePDF, …). Those assets do not exist in `:lib`, so this is the generic
 * document outline: a page with a folded corner, in tertiary ink.
 */
@Composable
private fun FileGlyph() {
    val tokens = LocalUiTokens.current
    val ink = tokens.colors.tertiary
    Canvas(modifier = Modifier.size(tokens.spacing.iconText * 4)) {
        val w = size.width
        val h = size.height
        val stroke = tokens.stroke.hairline.toPx() * 3
        val fold = w * FILE_FOLD_RATIO
        val path = Path().apply {
            moveTo(w * FILE_LEFT_RATIO, h * FILE_TOP_RATIO)
            lineTo(w * FILE_FOLD_START_RATIO, h * FILE_TOP_RATIO)
            lineTo(w - fold * FILE_FOLD_START_RATIO, h * FILE_FOLD_RATIO)
            lineTo(w - fold * FILE_FOLD_START_RATIO, h * FILE_BOTTOM_RATIO)
            lineTo(w * FILE_LEFT_RATIO, h * FILE_BOTTOM_RATIO)
            close()
        }
        drawPath(path = path, color = ink, style = Stroke(width = stroke))
        drawLine(
            color = ink,
            start = Offset(w * FILE_FOLD_START_RATIO, h * FILE_TOP_RATIO),
            end = Offset(w * FILE_FOLD_START_RATIO, h * FILE_FOLD_RATIO),
            strokeWidth = stroke,
        )
        drawLine(
            color = ink,
            start = Offset(w * FILE_FOLD_START_RATIO, h * FILE_FOLD_RATIO),
            end = Offset(w - fold * FILE_FOLD_START_RATIO, h * FILE_FOLD_RATIO),
            strokeWidth = stroke,
        )
    }
}

/** Folder-shape ratios: a tab on the left and a body below it. */
private const val FOLDER_TAB_TOP_RATIO: Float = 0.24f
private const val FOLDER_TAB_BOTTOM_RATIO: Float = 0.36f
private const val FOLDER_TAB_WIDTH_RATIO: Float = 0.42f
private const val FOLDER_TAB_SLANT_RATIO: Float = 0.5f
private const val FOLDER_BOTTOM_RATIO: Float = 0.82f

/** Document-shape ratios. */
private const val FILE_LEFT_RATIO: Float = 0.22f
private const val FILE_TOP_RATIO: Float = 0.12f
private const val FILE_FOLD_START_RATIO: Float = 0.62f
private const val FILE_FOLD_RATIO: Float = 0.26f
private const val FILE_BOTTOM_RATIO: Float = 0.88f

/** The last path segment (`URL.lastPathComponent`). */
private fun fileName(relativePath: String): String =
    relativePath.substringAfterLast('/').ifEmpty { relativePath }

/**
 * The parent folder's name, or `null` at the workspace root
 * (`FilesPage.swift:216`–`:220`).
 */
private fun folderName(relativePath: String): String? {
    val parent = relativePath.trimEnd('/').substringBeforeLast('/', missingDelimiterValue = "")
    if (parent.isEmpty() || parent == "." || parent == "/") return null
    return parent.substringAfterLast('/').ifEmpty { null }
}

/** The disclosure glyph the listing's rows end with (the baseline's chevron). */
private const val CHEVRON: String = "›"

/**
 * The magnifier inside the search capsule.
 *
 * `ios-files.png` measures a 20 pt SF Symbol; there is no icon asset in `:lib` and
 * no icon-sized token, so the glyph is drawn from two primitives at half the page
 * row floor (22 dp) with the declared hairline as its stroke. Colours are the
 * theme's tertiary ink, never literals.
 */
@Composable
private fun SearchGlyph() {
    val tokens = LocalUiTokens.current
    val ink = tokens.colors.tertiary
    Canvas(modifier = Modifier.size(tokens.geometry.pageRowMinHeight / 2)) {
        val stroke = tokens.stroke.hairline.toPx() * 4
        val radius = size.minDimension * MAGNIFIER_RADIUS_RATIO
        val center = Offset(
            size.width * MAGNIFIER_CENTER_RATIO,
            size.height * MAGNIFIER_CENTER_RATIO,
        )
        drawCircle(color = ink, radius = radius, center = center, style = Stroke(width = stroke))
        drawLine(
            color = ink,
            start = Offset(center.x + radius * 0.72f, center.y + radius * 0.72f),
            end = Offset(size.width * 0.92f, size.height * 0.92f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** Half the glyph box; the lens sits in the upper-left quadrant. */
private const val MAGNIFIER_RADIUS_RATIO: Float = 0.34f

/** The lens centre inside the square glyph box. */
private const val MAGNIFIER_CENTER_RATIO: Float = 0.42f

private val NUL: Byte = 0.toByte()

/**
 * The token inventory of this page; `PageTokenReferenceTest` fails when a use
 * site reads a token that is not listed here.
 */
internal object FilesPageTokens {
    val refs: List<String> = listOf(
        UiTokenNames.COLORS_PRIMARY,
        UiTokenNames.COLORS_SECONDARY,
        UiTokenNames.COLORS_TERTIARY,
        UiTokenNames.COLORS_ACCENT,
        UiTokenNames.COLORS_SELECTOR,
        UiTokenNames.COLORS_INPUT,
        UiTokenNames.TYPOGRAPHY_BODY,
        UiTokenNames.TYPOGRAPHY_ROW_LABEL,
        UiTokenNames.TYPOGRAPHY_SECONDARY,
        UiTokenNames.TYPOGRAPHY_CAPTION,
        UiTokenNames.TYPOGRAPHY_HEADLINE,
        UiTokenNames.SPACING_ICON_TEXT,
        UiTokenNames.SPACING_BADGE_INSET_V,
        UiTokenNames.SPACING_CARD_INSET,
        UiTokenNames.STROKE_HAIRLINE,
        UiTokenNames.GEOMETRY_PAGE_OUTER_MARGIN_H,
        UiTokenNames.GEOMETRY_PAGE_ROW_MIN_HEIGHT,
    )
}
