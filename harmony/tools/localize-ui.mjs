// SPDX-License-Identifier: MIT
// Reproducible chrome-localization codemod.
//
// Wraps only *static* Chinese string literals that are already being used as
// visible chrome: Text/Button/accessibility arguments, component label/title/
// hint/detail/text/placeholder properties, and the small set of page helpers
// that build those arguments. It deliberately does not touch comparisons,
// NativeIcon `name:` ids, rowId/buttonId ids, commands, URLs, or templates.
// Wrapped literals are idempotent: a second run changes nothing.
//
// It is line-oriented but token-aware: comments are skipped, each literal is
// matched with its own quote, and a literal adjacent to `+` is left for a
// human/editor because splitting a sentence needs a placeholder template.
import { readFileSync, writeFileSync } from 'node:fs';

const UI_FILES = [
  'ChatView.ets',
  'SettingsMoreView.ets',
  'SettingsView.ets',
  'DetailPages.ets',
  'DrawerView.ets',
  'ShellSupport.ets',
  'MarkdownBody.ets',
  'MathFormula.ets'
];

const CALL_ALLOW = new Set([
  'Text', 'Button', 'accessibilityText', 'MenuAction', 'ActionRow', 'MenuRow',
  'HeaderMenuItem', 'MenuOption', 'FieldLabel', 'LicenseRow', 'ChoiceRow',
  'FixedRow', 'RoleStepper'
]);

const PROP_ALLOW = new Set([
  'label', 'title', 'hint', 'detail', 'text', 'rightText', 'placeholder',
  'subtitle', 'empty', 'confirmText', 'cancelText', 'buttonLabel'
]);

const UI_ASSIGN = /this\.(?:failure|statusLine|actionError|configEditorError|toast|dialogError|error|notice)\s*(?:=|\+=)\s*$/;
const COMPARISON = /(?:===|!==|==|!=|\.includes\(|\.indexOf\(|\.startsWith\(|\.endsWith\()\s*$/;
const HAN = /\p{Script=Han}/u;

function wrapLine(line) {
  if (/^\s*(?:\/\/|\*|\/\*)/.test(line)) { return line; }
  const pattern = /(['"`])((?:\\.|(?!\1).)*)\1/g;
  const edits = [];
  let match;
  while ((match = pattern.exec(line)) !== null) {
    const literal = match[2];
    if (!HAN.test(literal)) { continue; }
    if (/\$\{/.test(literal)) { continue; }
    const start = match.index;
    const end = start + match[0].length;
    const prefix = line.slice(0, start);
    const suffix = line.slice(end);
    if (/Copy\.(?:tr|fill)\(\s*$/.test(prefix) || /LocaleText\.(?:tr|fill)\(\s*$/.test(prefix)) {
      continue;
    }
    if (/(?:^|\s)name\s*:\s*$/.test(prefix)) { continue; }
    if (/(?:^|\s)(?:id|symbol|rowId|buttonId|kind|tone|glyph|pageId)\s*:\s*$/.test(prefix)) {
      continue;
    }
    if (COMPARISON.test(prefix)) { continue; }
    if (/\+\s*$/.test(prefix) || /^\s*\+/.test(suffix)) { continue; }

    const callMatch = /([A-Za-z_$][\w$]*)\s*\(\s*$/.exec(prefix);
    const propMatch = /(?:^|[\s{,])([A-Za-z_$][\w$]*)\s*:\s*$/.exec(prefix);
    const ternary = /[?:]\s*$/.test(prefix);
    const returned = /return\s+$/.test(prefix);
    const assigned = UI_ASSIGN.test(prefix);
    let allow = false;
    if (callMatch !== null && CALL_ALLOW.has(callMatch[1])) { allow = true; }
    if (propMatch !== null && PROP_ALLOW.has(propMatch[1])) { allow = true; }
    if (returned || assigned) { allow = true; }
    if (ternary && /(?:Text\(|Button\(|accessibilityText\(|label\s*:|title\s*:|hint\s*:|detail\s*:|placeholder\s*:|text\s*:)/.test(line)) {
      allow = true;
    }
    if (!allow) { continue; }
    edits.push({ start, end, literal, quote: match[1] });
  }
  if (edits.length === 0) { return line; }
  let output = line;
  for (let index = edits.length - 1; index >= 0; index--) {
    const edit = edits[index];
    output = output.slice(0, edit.start) +
      `Copy.tr(${edit.quote}${edit.literal}${edit.quote})` +
      output.slice(edit.end);
  }
  return output;
}

function ensureCopyImport(source, file) {
  if (/\bCopy\./.test(source) === false || /import\s*\{[^}]*\bCopy\b[^}]*\}\s*from\s*['"]\.\/Palette['"]/.test(source)) {
    return source;
  }
  return source.replace(/(\nimport [^\n]+\n)/, `$1import { Copy } from './Palette';\n`);
}

const root = new URL('../entry/src/main/ets/ui/', import.meta.url);
let changed = 0;
for (const file of UI_FILES) {
  const path = new URL(file, root);
  const before = readFileSync(path, 'utf8');
  let after = before.split('\n').map(wrapLine).join('\n');
  after = ensureCopyImport(after, file);
  if (after !== before) {
    writeFileSync(path, after);
    changed++;
    process.stdout.write(`localized ${file}\n`);
  }
}
process.stdout.write(`changed ${changed} files\n`);
