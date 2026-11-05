# Third-party notices: native math rendering

This product bundles RaTeX 0.1.15 for native LaTeX parsing, layout and
self-contained SVG generation. The Android app renders the same engine through
`io.ratex.RaTeXSpan` (ratex-android 0.1.15); this repository uses the official
Rust crates so the HarmonyOS renderer uses the same engine version as Android.

## RaTeX

- Component: `ratex-parser`, `ratex-layout`, `ratex-svg`, `ratex-types`
  (and their transitive `ratex-*` crates), version 0.1.15.
- License: MIT.
- Copyright: erweixin and RaTeX contributors.
- Official source: https://github.com/erweixin/RaTeX
- Version-pinned API: https://docs.rs/ratex-svg/0.1.15/ratex_svg/
- Reference renderer: https://github.com/erweixin/RaTeX/blob/main/crates/ratex-svg/src/bin/render_svg.rs
- License text: https://github.com/erweixin/RaTeX/blob/main/LICENSE

## KaTeX fonts (embedded by `ratex-katex-fonts` 0.1.15)

- Component: the KaTeX `KaTeX_*.ttf` glyph fonts embedded at compile time by
  the `embed-fonts` feature of `ratex-svg`.
- License: SIL Open Font License, Version 1.1.
- Copyright: Khan Academy and other KaTeX contributors.
- Official source: https://github.com/KaTeX/KaTeX
- Font license: https://github.com/KaTeX/KaTeX/blob/main/LICENSE
- SIL OFL 1.1: https://openfontlicense.org/open-font-license-official-text/
- Redistribution notice shipped in the crate:
  https://github.com/erweixin/RaTeX/tree/main/crates/ratex-katex-fonts/fonts

The embedded fonts are used exclusively to outline formula glyphs into the SVG
returned by `pdsh_math_render_svg`. No font file is downloaded, fetched from the
network, or loaded from a WebView at runtime.

## Bundled notice files

The HAP ships these full texts under
`entry/src/main/resources/rawfile/licenses/` and displays them in
Settings › About › Open-source licenses:

- `phone-dsh-MIT.txt`: this repository's MIT license.
- `ratex-LICENSE.txt`: RaTeX 0.1.15 MIT license text.
- `ratex-THIRD_PARTY_NOTICES.txt`: RaTeX 0.1.15 third-party notices.
- `katex-fonts-NOTICE.txt`: KaTeX font copyright and notice, including the
  upstream RaTeX notice.
- `katex-fonts-OFL-1.1.txt`: the SIL OFL 1.1 text shipped by
  `ratex-katex-fonts` 0.1.15.
