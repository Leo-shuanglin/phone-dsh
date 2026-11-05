// SPDX-License-Identifier: MIT
//! Native LaTeX -> self-contained SVG rendering for the answer body.
//!
//! The C ABI is `pdsh_math_render_svg(latex, options_json)`. It returns a
//! Rust-owned JSON string (`{svg,width,height,baseline}` or `{error}`) that the
//! N-API layer forwards to ArkTS. The SVG contains outlined glyph paths from
//! the embedded KaTeX fonts, so it never asks the device for a web font, the
//! network or a WebView.
//!
//! The MathJax/KaTeX subset is parsed and laid out by RaTeX 0.1.15. All entry
//! points are panic-caught and bounded: UTF-8 LaTeX is at most 8 KiB, the
//! serialized SVG at most 2 MiB, and the logical result at most 8192x8192 px
//! with a 4,194,304 px^2 area. Oversized formulas return an error object only
//! (never an SVG), so the ArkTS fallback can keep the TeX source visible.

use std::ffi::{CStr, CString};
use std::os::raw::c_char;
use std::panic::{catch_unwind, AssertUnwindSafe};

use ratex_layout::{layout, to_display_list, validate_layout, LayoutOptions};
use ratex_parser::parse;
use ratex_svg::{render_to_svg_with_color_syntax, SvgColorSyntax, SvgOptions};
use ratex_types::color::Color;
use ratex_types::math_style::MathStyle;
use serde_json::{json, Value};

/// Maximum accepted LaTeX input, in UTF-8 bytes.
pub const MAX_LATEX_BYTES: usize = 8 * 1024;
/// Maximum serialized SVG output, in bytes.
pub const MAX_SVG_BYTES: usize = 2 * 1024 * 1024;
/// Maximum logical width or height returned to the UI, in CSS pixels.
pub const MAX_LOGICAL_DIMENSION: f64 = 8192.0;
/// Maximum logical width * height returned to the UI, in square CSS pixels.
pub const MAX_LOGICAL_AREA: f64 = 4_194_304.0;
const DEFAULT_FONT_SIZE: f64 = 20.0;
const MIN_FONT_SIZE: f64 = 8.0;
const MAX_FONT_SIZE: f64 = 96.0;

struct MathRequest {
    latex: String,
    font_size: f64,
    color: Color,
    display_mode: bool,
}

fn error_value(message: impl AsRef<str>) -> Value {
    let mut text = message.as_ref().to_owned();
    text.retain(|ch| ch != '\u{0}' && (ch >= ' ' || ch == '\n' || ch == '\t'));
    if text.len() > 512 {
        let mut boundary = 512;
        while boundary > 0 && !text.is_char_boundary(boundary) {
            boundary -= 1;
        }
        text.truncate(boundary);
    }
    json!({ "error": text })
}

fn parse_request(latex: &str, options_json: &str) -> Result<MathRequest, String> {
    if latex.is_empty() {
        return Err("LaTeX source is empty".to_owned());
    }
    if latex.len() > MAX_LATEX_BYTES {
        return Err(format!(
            "LaTeX source exceeds the {} byte limit",
            MAX_LATEX_BYTES
        ));
    }
    if latex.trim().is_empty() {
        return Err("LaTeX source is blank".to_owned());
    }
    if options_json.len() > MAX_LATEX_BYTES {
        return Err("math options exceed the size limit".to_owned());
    }
    let value: Value = serde_json::from_str(options_json)
        .map_err(|_| "math options are not valid JSON".to_owned())?;
    let object = value
        .as_object()
        .ok_or_else(|| "math options must be a JSON object".to_owned())?;

    let font_size = match object.get("fontSize") {
        None | Some(Value::Null) => DEFAULT_FONT_SIZE,
        Some(value) => value
            .as_f64()
            .filter(|number| number.is_finite())
            .ok_or_else(|| "fontSize must be a finite number".to_owned())?,
    };
    let font_size = font_size.clamp(MIN_FONT_SIZE, MAX_FONT_SIZE);

    let color = match object.get("color") {
        None | Some(Value::Null) => Color::BLACK,
        Some(value) => {
            let text = value
                .as_str()
                .ok_or_else(|| "color must be a hex string".to_owned())?;
            Color::from_hex(text.trim())
                .ok_or_else(|| "color must be #rgb, #rgba, #rrggbb or #rrggbbaa".to_owned())?
        }
    };

    let display_mode = match object.get("displayMode") {
        None | Some(Value::Null) => false,
        Some(value) => value
            .as_bool()
            .ok_or_else(|| "displayMode must be a boolean".to_owned())?,
    };

    Ok(MathRequest {
        latex: latex.to_owned(),
        font_size,
        color,
        display_mode,
    })
}

fn round3(value: f64) -> f64 {
    (value * 1000.0).round() / 1000.0
}

/// Renders one formula. The returned object is always JSON-safe.
pub(crate) fn render(latex: &str, options_json: &str) -> Value {
    let request = match parse_request(latex, options_json) {
        Ok(request) => request,
        Err(error) => return error_value(error),
    };

    let ast = match parse(&request.latex) {
        Ok(ast) => ast,
        Err(error) => return error_value(format!("LaTeX parse error: {error}")),
    };

    let style = if request.display_mode {
        MathStyle::Display
    } else {
        MathStyle::Text
    };
    let layout_options = LayoutOptions::default()
        .with_style(style)
        .with_color(request.color);
    let lbox = layout(&ast, &layout_options);
    if let Err(error) = validate_layout(&lbox) {
        return error_value(format!("LaTeX layout error: {error}"));
    }
    let display_list = to_display_list(&lbox);

    let padding = if request.display_mode { 4.0 } else { 1.0 };
    let width = display_list.width * request.font_size + 2.0 * padding;
    let height = (display_list.height + display_list.depth) * request.font_size + 2.0 * padding;
    let baseline = display_list.height * request.font_size + padding;
    if !width.is_finite() || !height.is_finite() || !baseline.is_finite() || width <= 0.0 || height <= 0.0 {
        return error_value("LaTeX rendered invalid dimensions");
    }
    // Reject before building the SVG so an oversized layout never allocates an
    // SVG payload and the caller only receives the preserved-source error.
    if width > MAX_LOGICAL_DIMENSION || height > MAX_LOGICAL_DIMENSION {
        return error_value(format!(
            "LaTeX rendered dimensions exceed the {} px edge limit",
            MAX_LOGICAL_DIMENSION
        ));
    }
    if width * height > MAX_LOGICAL_AREA {
        return error_value(format!(
            "LaTeX rendered dimensions exceed the {} px^2 area limit",
            MAX_LOGICAL_AREA
        ));
    }

    let svg_options = SvgOptions {
        font_size: request.font_size,
        padding,
        stroke_width: 1.0,
        embed_glyphs: true,
        font_dir: String::new(),
    };
    let svg =
        render_to_svg_with_color_syntax(&display_list, &svg_options, SvgColorSyntax::Rgb);
    if svg.is_empty() || !svg.contains("<svg") {
        return error_value("LaTeX rendered an empty SVG");
    }
    if svg.len() > MAX_SVG_BYTES {
        return error_value(format!(
            "rendered SVG exceeds the {} byte limit",
            MAX_SVG_BYTES
        ));
    }

    json!({
        "svg": svg,
        "width": round3(width),
        "height": round3(height),
        "baseline": round3(baseline),
    })
}

/// Renders LaTeX to a self-contained SVG JSON object.
///
/// # Safety
/// `latex` and `options_json` must be valid NUL-terminated UTF-8 C strings. The
/// return value is owned by this library and must be released with
/// `pdsh_host_free`.
#[no_mangle]
pub unsafe extern "C" fn pdsh_math_render_svg(
    latex: *const c_char,
    options_json: *const c_char,
) -> *mut c_char {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if latex.is_null() || options_json.is_null() {
            return error_value("null math input");
        }
        let latex = match CStr::from_ptr(latex).to_str() {
            Ok(value) => value,
            Err(_) => return error_value("LaTeX source is not UTF-8"),
        };
        let options = match CStr::from_ptr(options_json).to_str() {
            Ok(value) => value,
            Err(_) => return error_value("math options are not UTF-8"),
        };
        render(latex, options)
    }));
    let value = match result {
        Ok(value) => value,
        Err(_) => error_value("panic in pdsh_math_render_svg"),
    };
    let text = serde_json::to_string(&value).unwrap_or_else(|_| {
        "{\"error\":\"math result could not be encoded\"}".to_owned()
    });
    match CString::new(text) {
        Ok(value) => value.into_raw(),
        Err(_) => CString::new("{\"error\":\"math result contains NUL\"}")
            .expect("static JSON has no NUL")
            .into_raw(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;

    fn valid(latex: &str, options: &str) -> Value {
        let value = render(latex, options);
        assert!(value.get("error").is_none(), "unexpected error: {value}");
        value
    }

    #[test]
    fn display_formula_is_self_contained_svg_with_bounds() {
        let value = valid("\\frac{a^2+b^2}{c^2} = \\sum_{i=0}^{n} i", r##"{"fontSize":24,"color":"#F2F2F2","displayMode":true}"##);
        let svg = value["svg"].as_str().expect("svg");
        assert!(svg.starts_with("<svg") || svg.starts_with("<?xml"));
        assert!(svg.contains("<path"), "expected outlined glyph paths");
        assert!(!svg.contains("<text"), "embedded fonts must not emit text elements");
        assert!(!svg.contains("font-family"), "no host font dependency");
        assert!(!svg.contains("href=\"http") && !svg.contains("url(http"));
        assert!(!svg.contains("<script"));
        let width = value["width"].as_f64().expect("width");
        let height = value["height"].as_f64().expect("height");
        let baseline = value["baseline"].as_f64().expect("baseline");
        assert!(width > 0.0 && height > 0.0);
        assert!(baseline > 0.0 && baseline <= height);
        assert!(svg.contains("viewBox"));
    }

    #[test]
    fn inline_formula_uses_text_style_and_is_smaller() {
        let display = valid("x^2", r#"{"fontSize":20,"displayMode":true}"#);
        let inline = valid("x^2", r#"{"fontSize":20,"displayMode":false}"#);
        let display_height = display["height"].as_f64().unwrap();
        let inline_height = inline["height"].as_f64().unwrap();
        assert!(display_height > inline_height);
        assert!(inline["svg"].as_str().unwrap().contains("<path"));
    }

    #[test]
    fn invalid_input_returns_an_error_not_a_panic() {
        for (latex, options) in [
            ("", "{}"),
            ("   ", "{}"),
            ("\\frac{1}{", "{}"),
            ("x", "not json"),
            ("x", "[]"),
            ("x", r#"{"fontSize":"big"}"#),
            ("x", r#"{"color":"red"}"#),
            ("x", r#"{"displayMode":"yes"}"#),
        ] {
            let value = render(latex, options);
            assert!(value.get("error").and_then(Value::as_str).is_some(), "expected error for {latex:?} {options:?}");
        }
    }

    #[test]
    fn oversized_latex_is_rejected_before_layout() {
        let latex = "x".repeat(MAX_LATEX_BYTES + 1);
        let value = render(&latex, "{}");
        assert!(value["error"].as_str().unwrap().contains("byte limit"));
    }

    #[test]
    fn wide_hspace_beyond_the_edge_limit_returns_only_an_error() {
        // RaTeX 0.1.15 accepts and lays this out; validate_layout budgets
        // drawing items, not logical dimensions. Before the result cap this
        // returned a >10k px wide SVG to the UI, whose createPixelMap desiredSize
        // is unbounded.
        let value = render(
            r"x + \hspace{5000pt} + y",
            r#"{"fontSize":20,"displayMode":true}"#,
        );
        let error = value
            .get("error")
            .and_then(Value::as_str)
            .expect("oversized logical width must be an error");
        assert!(error.contains("limit"), "unexpected error: {error}");
        assert!(
            value.get("svg").is_none(),
            "error results must not carry an SVG"
        );
        assert!(value.get("width").is_none() && value.get("height").is_none());
    }

    #[test]
    fn oversized_rule_area_is_rejected_even_when_each_edge_fits() {
        // 1500pt is ~3000 px per edge at fontSize 20: inside 8192 but the
        // 9,000,000 px^2 area exceeds the 4,194,304 px^2 budget.
        let value = render(
            r"\rule{1500pt}{1500pt}",
            r#"{"fontSize":20,"displayMode":true}"#,
        );
        let error = value
            .get("error")
            .and_then(Value::as_str)
            .expect("oversized logical area must be an error");
        assert!(error.contains("area"), "unexpected error: {error}");
        assert!(
            value.get("svg").is_none(),
            "error results must not carry an SVG"
        );
    }

    #[test]
    fn font_size_is_clamped_to_the_documented_range() {
        let low = valid("x", r#"{"fontSize":1}"#);
        let high = valid("x", r#"{"fontSize":400}"#);
        let low_width = low["width"].as_f64().unwrap();
        let high_width = high["width"].as_f64().unwrap();
        assert!(low_width > 0.0 && high_width > low_width);
        assert!(high_width < 1000.0);
    }

    #[test]
    fn cjk_text_does_not_panic_even_when_font_coverage_is_platform_dependent() {
        let value = valid("\\text{中文} + x^2", r##"{"fontSize":20,"color":"#1A1A1A"}"##);
        assert!(value["svg"].as_str().unwrap().contains("<path"));
        assert!(value["width"].as_f64().unwrap() > 0.0);
    }

    #[test]
    fn ffi_returns_owned_json_for_a_freed_pointer() {
        let latex = CString::new("a+b").unwrap();
        let options = CString::new(r##"{"fontSize":18,"color":"#1A1A1A"}"##).unwrap();
        let pointer = unsafe { pdsh_math_render_svg(latex.as_ptr(), options.as_ptr()) };
        assert!(!pointer.is_null());
        let text = unsafe { CStr::from_ptr(pointer) }.to_str().unwrap();
        let value: Value = serde_json::from_str(text).unwrap();
        assert!(value["svg"].as_str().unwrap().contains("<svg"));
        unsafe { crate::pdsh_host_free(pointer) };
    }
}
