// phone-dsh — :app: the device record of the one model service this host applies.
// SPDX-License-Identifier: MIT
//
// Why this store exists: `ModelProviderSettingsPage` says 添加 / 编辑 / 删除 and
// 配置会在下次启动主机时生效. Before this file the page's write gestures had nowhere
// to land, so the host reported `writable = NO` and every row was grey — an honest
// answer, but one that made the page's form impossible to use (and impossible to
// IME-test). This is the storage half of the (a) answer: the non-secret record
// (name, model, endpoint) lives in one file; the key does not live here at all —
// it stays in [ApiKeyStore], the AndroidKeyStore envelope that already backs the
// gateway, so this file never sees a credential.
//
// What the record is: exactly one service, because this host *has* exactly one.
// The engine addresses one model endpoint and one model id (the launch values);
// a second service would share the single key account and the single Worker env
// slot, i.e. it would be a row that promises something the engine cannot do. So
// the page's list holds one row, and 保存 replaces that row.
//
// Where a save takes effect: [ProbeLaunchConfig] overlays this record on the
// launch extras at the next process start (an explicit `pdsh.deepseek.baseUrl` /
// `pdsh.model.name` still wins). That is what makes 下次启动主机时生效 true rather
// than copy: a saved endpoint/model is the value the next host boot actually uses.
//
// Failure behaviour: a missing file is [ProviderStoreRead.Empty] (first run), a
// corrupt one is [ProviderStoreRead.Unreadable] (the page then prints its
// CONFIG_UNREADABLE line instead of silently falling back), and a failed write
// is [ProviderWriteOutcome.Unavailable] (the page is told 未保存, never that it
// saved). Nothing here logs a value: only stable, token-free labels reach logcat.

package com.phonedsh.probe

import android.content.Context
import android.util.Log
import com.phonedsh.ui.page.ProviderDraft
import java.io.File
import java.net.URI
import java.util.Properties
import java.util.concurrent.atomic.AtomicReference

/**
 * The non-secret half of the model service: what the engine needs to address it.
 *
 * @param id the page's stable row id.
 * @param name the display name the row shows.
 * @param model the model id sent as `PDSH_MODEL_NAME`; empty means "the launch
 *   default", never a made-up id.
 * @param endpoint the Messages base URL (may carry a path prefix), stored
 *   verbatim once it parses as an absolute http(s) URL.
 */
internal data class ProviderRecord(
    val id: String,
    val name: String,
    val model: String,
    val endpoint: String,
)

/** What one read of the device record found. */
internal sealed interface ProviderStoreRead {
    /** The host read the store and holds no record (first run, or after 删除). */
    data object Empty : ProviderStoreRead

    /** The stored record; the only state a save produces. */
    data class Stored(val record: ProviderRecord) : ProviderStoreRead

    /** The record exists but cannot be read; never treated as "no service". */
    data object Unreadable : ProviderStoreRead
}

/** The outcome of one write gesture; the host turns it into one honest line. */
internal sealed interface ProviderWriteOutcome {
    /** The record is on disk and the next facts read will show it. */
    data object Saved : ProviderWriteOutcome

    /** The record is gone (the page's one row falls back to the launch service). */
    data object Deleted : ProviderWriteOutcome

    /**
     * The gesture was refused before anything was written.
     *
     * [reason] is host copy that names the rule (never the value), so the notice
     * bar can say why the tap did not save.
     */
    data class Rejected(val reason: String) : ProviderWriteOutcome

    /** The device store could not be written; nothing was saved. */
    data object Unavailable : ProviderWriteOutcome
}

/**
 * The pure rules the page's draft must satisfy before the host stores it.
 *
 * They live here, not in the page, because they are the *host's* acceptance
 * rules: the page only requires a name and a non-empty endpoint for a new
 * service, and every other validity question is the device's.
 */
internal object ProviderDraftRules {
    /** The one accepted shape: an absolute http(s) URL with a host and no user info. */
    fun endpointRejection(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return REASON_ENDPOINT
        val uri = try {
            URI(text)
        } catch (_: Exception) {
            return REASON_ENDPOINT
        }
        val scheme = uri.scheme?.lowercase()
        if ((scheme != "http" && scheme != "https") || uri.host.isNullOrEmpty()) {
            return REASON_ENDPOINT
        }
        if (uri.userInfo != null) return REASON_CREDENTIALS
        return null
    }

    /**
     * The key rule [ApiKeyStore] already enforces, checked before the write so a
     * rejected key is a notice rather than an exception.
     */
    fun keyRejection(raw: String): String? {
        val text = raw.trim()
        return if (text.length >= ApiKeyStore.MIN_KEY_LENGTH && text.all { it in '!'..'~' }) {
            null
        } else {
            REASON_KEY
        }
    }

    private const val REASON_ENDPOINT: String = "地址必须是 http(s) 绝对地址，本次未保存"
    private const val REASON_CREDENTIALS: String = "地址不能内嵌用户名或密码，本次未保存"
    private const val REASON_KEY: String = "密钥需至少 8 个可打印 ASCII 字符，本次未保存"
}

/**
 * The one model-service record, over one properties file under the app's files
 * dir.
 *
 * The file is not a second registry: it holds exactly the profile the page's one
 * row shows. The format is `java.util.Properties` on purpose: it is plain text a
 * device can be inspected with, it escapes names that carry separators, and it
 * keeps this store readable by the JVM suite without the Android framework
 * (`org.json` is stubbed in a unit-test class path).
 *
 * @param file the record; the instrumented/JVM suites pass a temp file, the app
 *   passes `<filesDir>/settings/model-provider.v1.properties`.
 */
internal class ProviderConfigStore(
    private val file: File,
    /**
     * Where a stable failure label goes; the app passes logcat, the JVM suite
     * passes a collector so it can assert the state *and* not touch `android.util.Log`
     * (the unit-test class path has no framework).
     */
    private val log: (String) -> Unit = { message -> Log.w(TAG, message) },
) {

    /** Reads the record; every failure is its own state, never an empty one. */
    fun read(): ProviderStoreRead {
        if (!file.isFile) return ProviderStoreRead.Empty
        val properties = Properties()
        try {
            file.reader(Charsets.UTF_8).use { reader -> properties.load(reader) }
        } catch (error: Exception) {
            log("provider record unreadable: io")
            return ProviderStoreRead.Unreadable
        }
        val version = properties.getProperty(FIELD_VERSION)?.trim()
        if (version != VERSION.toString()) return unreadable()
        val record = ProviderRecord(
            id = properties.getProperty(FIELD_ID).orEmpty().trim(),
            name = properties.getProperty(FIELD_NAME).orEmpty().trim(),
            model = properties.getProperty(FIELD_MODEL).orEmpty().trim(),
            endpoint = properties.getProperty(FIELD_ENDPOINT).orEmpty().trim(),
        )
        return when {
            record.id.isEmpty() || record.name.isEmpty() -> unreadable()
            ProviderDraftRules.endpointRejection(record.endpoint) != null -> unreadable()
            else -> ProviderStoreRead.Stored(record)
        }
    }

    /** The stored record, or `null` when there is none (or it cannot be read). */
    fun current(): ProviderRecord? = (read() as? ProviderStoreRead.Stored)?.record

    /**
     * Stores one validated record, replacing the previous one.
     *
     * The write is atomic from the reader's point of view: a temporary file is
     * written and renamed over the record, so a process death mid-write leaves
     * either the old record or the new one, never a torn document.
     */
    fun save(record: ProviderRecord): ProviderWriteOutcome {
        val payload = Properties()
        payload.setProperty(FIELD_VERSION, VERSION.toString())
        payload.setProperty(FIELD_ID, record.id)
        payload.setProperty(FIELD_NAME, record.name)
        payload.setProperty(FIELD_MODEL, record.model)
        payload.setProperty(FIELD_ENDPOINT, record.endpoint)
        return write(payload)
    }

    /** Removes the record; a missing record is already the requested state. */
    fun delete(): ProviderWriteOutcome = try {
        if (file.isFile && !file.delete()) {
            ProviderWriteOutcome.Unavailable
        } else {
            ProviderWriteOutcome.Deleted
        }
    } catch (error: Exception) {
        log("provider record delete failed")
        ProviderWriteOutcome.Unavailable
    }

    private fun write(payload: Properties): ProviderWriteOutcome {
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            log("provider record directory not writable")
            return ProviderWriteOutcome.Unavailable
        }
        return try {
            val temporary = File(parent, file.name + SUFFIX_TEMPORARY)
            temporary.writer(Charsets.UTF_8).use { writer -> payload.store(writer, null) }
            if (!temporary.renameTo(file)) {
                temporary.delete()
                log("provider record rename failed")
                ProviderWriteOutcome.Unavailable
            } else {
                ProviderWriteOutcome.Saved
            }
        } catch (error: Exception) {
            log("provider record write failed")
            ProviderWriteOutcome.Unavailable
        }
    }

    private fun unreadable(): ProviderStoreRead {
        log("provider record unreadable: content")
        return ProviderStoreRead.Unreadable
    }

    companion object {
        /** The stable, token-free log tag. */
        const val TAG: String = "PDSHProvider"

        private const val FIELD_VERSION: String = "version"
        private const val FIELD_ID: String = "id"
        private const val FIELD_NAME: String = "name"
        private const val FIELD_MODEL: String = "model"
        private const val FIELD_ENDPOINT: String = "endpoint"
        private const val VERSION: Int = 1
        private const val SUFFIX_TEMPORARY: String = ".tmp"

        /** The file name under `<filesDir>/settings`; also the test's temp name. */
        const val FILE_NAME: String = "settings/model-provider.v1.properties"

        private val installed = AtomicReference<ProviderConfigStore?>(null)

        /**
         * Binds the process-wide record to [context]'s files dir.
         *
         * Idempotent, like [ApiKeyStore.install]: a second Activity or a
         * configuration change must not replace the store under a running engine.
         */
        @Synchronized
        fun install(context: Context): ProviderConfigStore {
            installed.get()?.let { return it }
            val created = ProviderConfigStore(File(context.applicationContext.filesDir, FILE_NAME))
            installed.set(created)
            return created
        }

        /** The installed store, or `null` before the composition root ran. */
        fun active(): ProviderConfigStore? = installed.get()

        /** The draft fields the host stores; shared by [save]'s caller and its tests. */
        fun recordOf(
            draft: ProviderDraft,
            previous: ProviderRecord?,
            launchEndpoint: String?,
            launchModel: String,
        ): ProviderRecord = ProviderRecord(
            id = draft.id.trim().ifEmpty { previous?.id ?: DEFAULT_ID },
            name = draft.name.trim(),
            // An empty model keeps what the row already showed; never an invented id.
            model = draft.model.trim().ifEmpty { previous?.model ?: launchModel },
            // An existing row's editor starts with an empty URL on purpose (the page
            // never reads a stored URL back), so blank means "keep".
            endpoint = draft.endpoint.trim().ifEmpty {
                previous?.endpoint ?: launchEndpoint.orEmpty()
            },
        )

        /** The id the one row gets when the person left the field blank. */
        const val DEFAULT_ID: String = "local"
    }
}
