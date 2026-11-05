// Fenced-code token highlighter.
// SPDX-License-Identifier: MIT
//
// Port of the canonical Android highlighter
// (`android/lib/src/main/kotlin/com/phonedsh/ui/surface/CodeHighlighter.kt`,
// itself a mirror of `NativeCodeHighlighter.swift`). The keyword table and the
// four regular expressions are copied verbatim; no value source lives here.
//
// Later passes overwrite earlier ones exactly like `AnnotatedString.addStyle`
// does on Android: strings first, then comments, numbers, and finally the
// keyword table. A keyword or number inside a string therefore ends up with
// the later ink. The returned runs always cover the complete source, so the
// card can copy the original text byte for byte (UTF-16 indices, like the
// platform string APIs).

/** The four syntax inks plus the unstyled base ink. */
export type CodeInk = 'base' | 'string' | 'comment' | 'number' | 'keyword';

/** One contiguous slice of the source with one ink. */
export class CodeRun {
  text: string = '';
  ink: CodeInk = 'base';

  constructor(text: string = '', ink: CodeInk = 'base') {
    this.text = text;
    this.ink = ink;
  }
}

/** The iOS highlighter's keyword table, verbatim (`NativeCodeHighlighter.swift:7-15`). */
const KEYWORDS: Set<string> = new Set<string>([
  'actor', 'as', 'async', 'await', 'break', 'case', 'catch', 'class', 'const', 'continue',
  'def', 'default', 'defer', 'do', 'else', 'enum', 'export', 'extends', 'false', 'final',
  'finally', 'for', 'from', 'func', 'function', 'guard', 'if', 'implements', 'import', 'in',
  'init', 'interface', 'internal', 'is', 'let', 'namespace', 'new', 'nil', 'nonisolated',
  'null', 'open', 'operator', 'override', 'package', 'private', 'protocol', 'public', 'raise',
  'return', 'self', 'static', 'struct', 'super', 'switch', 'this', 'throw', 'throws', 'true',
  'try', 'typealias', 'typeof', 'using', 'var', 'void', 'where', 'while', 'yield'
]);

/** String literals, verbatim from `CodeHighlighter.kt:39`. */
const STRING_PATTERN: RegExp = /"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'/g;
/** Line, block and hash comments, verbatim from `CodeHighlighter.kt:42`. */
const COMMENT_PATTERN: RegExp = /\/\/[^\n]*|\/\*[\s\S]*?\*\/|#[^\n]*/g;
/** Numeric literals, verbatim from `CodeHighlighter.kt:45`. */
const NUMBER_PATTERN: RegExp = /\b(?:0x[0-9a-fA-F]+|\d+(?:\.\d+)?)\b/g;
/** Identifiers, verbatim from `CodeHighlighter.kt:48`. */
const WORD_PATTERN: RegExp = /\b[A-Za-z_][A-Za-z0-9_]*\b/g;

/**
 * Paints every match of [pattern] with [ink]. Later calls overwrite earlier
 * characters, preserving Android's `paint(...)` application order.
 */
function paintMatches(source: string, pattern: RegExp, ink: CodeInk, inks: CodeInk[]): void {
  pattern.lastIndex = 0;
  let match: RegExpExecArray | null = pattern.exec(source);
  while (match !== null) {
    const start: number = match.index;
    const end: number = start + match[0].length;
    for (let index: number = start; index < end; index++) {
      inks[index] = ink;
    }
    match = pattern.exec(source);
  }
}

/**
 * Splits [source] into contiguous runs of one ink, keeping every UTF-16 code
 * unit. An empty source has no runs.
 */
export function highlightCode(source: string): CodeRun[] {
  if (source.length === 0) {
    return [];
  }
  const inks: CodeInk[] = [];
  for (let index: number = 0; index < source.length; index++) {
    inks.push('base');
  }
  // Android order: strings, comments, numbers, then the keyword table (which
  // wins because its spans are applied last). Mirrored exactly.
  paintMatches(source, STRING_PATTERN, 'string', inks);
  paintMatches(source, COMMENT_PATTERN, 'comment', inks);
  paintMatches(source, NUMBER_PATTERN, 'number', inks);

  WORD_PATTERN.lastIndex = 0;
  let word: RegExpExecArray | null = WORD_PATTERN.exec(source);
  while (word !== null) {
    if (KEYWORDS.has(word[0].toLowerCase())) {
      const start: number = word.index;
      const end: number = start + word[0].length;
      for (let index: number = start; index < end; index++) {
        inks[index] = 'keyword';
      }
    }
    word = WORD_PATTERN.exec(source);
  }

  const runs: CodeRun[] = [];
  let runStart: number = 0;
  let current: CodeInk = inks[0];
  for (let index: number = 1; index <= source.length; index++) {
    if (index === source.length || inks[index] !== current) {
      runs.push(new CodeRun(source.slice(runStart, index), current));
      runStart = index;
      if (index < source.length) {
        current = inks[index];
      }
    }
  }
  return runs;
}
