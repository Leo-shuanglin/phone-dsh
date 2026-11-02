// Geometry ownership: every token is surface-owned or used by two pages.
// SPDX-License-Identifier: MIT
//
// `spec/ui/v1/tokens.json` has two kinds of geometry:
//   * signature-surface geometry, listed in `signatureSurfaces.*.tokens` (the six
//     pixel-parity surfaces of README §4);
//   * page-level geometry: a `geometry.*` entry no surface declares. It exists
//     because several pages needed the same length, which is exactly when a new
//     token is allowed (`spec/ui/v1/README.md` §4 page-level subsection).
//
// This test turns the review rule into one invariant: every geometry token has an
// owner, either a signature surface that declares it or at least two page sources
// that read it. An orphan (neither) is a spec entry nobody owns: a page-private
// length that leaked into the spec, or a surface token whose surface forgot it.
// The page-level half also pins the shape: a fixed `pt` length, never a
// `screenRatio`, which stays owned by the surface that consumes it.
package com.phonedsh.ui.page

import com.phonedsh.agentcore.JsonNull
import com.phonedsh.agentcore.JsonNumber
import com.phonedsh.agentcore.JsonObject
import com.phonedsh.ui.SpecFixtures
import com.phonedsh.ui.surfaces
import com.phonedsh.ui.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class PageGeometryTokenTest {
    @Test
    fun `every geometry token is surface-owned or used by at least two pages`() {
        val geometry = SpecFixtures.tokens["geometry"] as JsonObject
        val geometryNames = geometry.members.keys.map { "geometry.$it" }.toSet()
        val surfaceGeometry = SpecFixtures.surfaces()
            .flatMap { it.tokens }
            .filter { it.startsWith("geometry.") }
            .toSet()
        assertEquals(
            "a signature surface declares geometry that tokens.json does not define",
            emptySet<String>(),
            surfaceGeometry - geometryNames,
        )
        val pageLevel = geometryNames - surfaceGeometry
        assertTrue(
            "tokens.json has no page-level geometry token; this test would be vacuous",
            pageLevel.isNotEmpty(),
        )
        val sources = pageSources()
        val homeOwned = homeSpecNumbers().keys
        geometryNames.sorted().forEach { token ->
            val users = sources.filterValues { source -> source.contains("tokens.$token") }
                .keys
                .sorted()
            val homeLength = token.removePrefix("geometry.") in homeOwned
            assertTrue(
                "$token is an orphan geometry token: no signature surface declares it and only " +
                    "${users.size} page(s) $users read it. Every geometry token must be either " +
                    "surface-owned, used by at least 2 pages, or a PR22 HomeSpec length owned by " +
                    "`HomeMetrics`; a one-page length keeps reusing a declared token with the " +
                    "`unverified geometry` marker instead of entering the spec.",
                token in surfaceGeometry || users.size >= 2 || homeLength,
            )
        }
        pageLevel.sorted().forEach { token ->
            val entry = geometry.members.getValue(token.removePrefix("geometry.")) as JsonObject
            assertEquals("$token is page-level and must be a pt length", "pt", entry.text("unit"))
            assertTrue(
                "$token is page-level and must be a fixed length (scalesWith: null), " +
                    "because only a signature surface may derive a ratio",
                entry["scalesWith"] is JsonNull,
            )
        }
    }

    /**
     * PR22's home grid is page-tier geometry owned by `HomeMetrics`. Before the
     * a-tokens pass it lives only in `HomeSpec.swift`; once `tokens.json` declares
     * one of those lengths, the two must agree, so a future spec edit cannot leave
     * the generated table and the token table with different numbers.
     */
    @Test
    fun `PR22 HomeSpec lengths agree with tokens json when it declares them`() {
        val home = homeSpecNumbers()
        assertTrue("HomeSpec.swift must expose its grid/size/radius table", home.size >= 30)
        val generated = String(
            Files.readAllBytes(
                SpecFixtures.repoRoot.resolve(
                    "android/lib/build/generated/ui-tokens/kotlin/com/phonedsh/ui/theme/generated/HomeSpecData.kt",
                ),
            ),
            Charsets.UTF_8,
        )
        val geometry = SpecFixtures.tokens["geometry"] as JsonObject
        home.forEach { (key, value) ->
            val emitted = Regex(
                "const val ${upperSnake(key)}: Float = ([0-9.]+)f",
            ).find(generated)?.groupValues?.get(1)?.toFloat()
                ?: error("HomeSpecData has no generated ${upperSnake(key)}")
            assertEquals("HomeSpecData.${upperSnake(key)}", value, emitted, 0f)
            val declared = geometry.members[key] as? JsonObject ?: return@forEach
            assertEquals("geometry.$key unit", "pt", declared.text("unit"))
            assertEquals(
                "geometry.$key value must equal HomeSpec.${key}",
                value,
                (declared["value"] as JsonNumber).raw.toFloat(),
                0f,
            )
        }
    }

    /** The `static let ...: CGFloat` table from HomeSpec.swift, keyed by its Swift name. */
    private fun homeSpecNumbers(): Map<String, Float> {
        val source = String(
            Files.readAllBytes(
                SpecFixtures.repoRoot.resolve("examples/probe-app/PhoneDSHProbe/HomeSpec.swift"),
            ),
            Charsets.UTF_8,
        )
        return Regex("""static let (\w+): CGFloat = ([0-9.]+)""")
            .findAll(source)
            .associate { it.groupValues[1] to it.groupValues[2].toFloat() }
    }

    private fun upperSnake(name: String): String =
        name.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").uppercase()

    /** Every `.kt` file under the page package, keyed by file name. */
    private fun pageSources(): Map<String, String> {
        val dir = SpecFixtures.repoRoot.resolve("android/lib/src/main/kotlin/com/phonedsh/ui/page")
        return Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".kt") }
                .sorted()
                .toList()
                .associate { it.fileName.toString() to String(Files.readAllBytes(it), Charsets.UTF_8) }
        }
    }
}
