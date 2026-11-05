// The answer body's block and inline model, and the parser that builds it.
// SPDX-License-Identifier: MIT
//
// Ported from the canonical Android MarkdownModel.kt (which mirrors the iOS
// NativeMarkdownModel.swift). It is deliberately not a CommonMark engine: it
// recognises the block kinds the surface draws (heading, paragraph, list,
// quote, rule, fenced code, table, display math) and the inline kinds it styles
// (strong, emphasis, delete, code, link, inline math, footnote). Raw HTML is
// literal text and is never parsed or executed.
//
// Pure: the same string always yields the same blocks, so a replay and a unit
// test see one answer.

/** Maximum recursive block nesting (quotes and list-item children). */
export const MAX_MARKDOWN_BLOCK_DEPTH: number = 32;

/** Native markdown presentation variant; compact is used for expanded thinking. */
export enum MarkdownVariant {
  Normal = 'normal',
  Compact = 'compact'
}

/** A GFM table column alignment. */
export enum MarkdownTableAlign {
  Left = 'left',
  Center = 'center',
  Right = 'right'
}

/** The concrete kind of a block. */
export enum MarkdownBlockKind {
  Paragraph = 'paragraph',
  Heading = 'heading',
  Code = 'code',
  Table = 'table',
  Math = 'math',
  Image = 'image',
  List = 'list',
  Quote = 'quote',
  Rule = 'rule'
}

/** The concrete kind of an inline run. */
export enum MarkdownInlineKind {
  Text = 'text',
  Strong = 'strong',
  Emphasis = 'emphasis',
  Delete = 'delete',
  Code = 'code',
  Link = 'link',
  Image = 'image',
  Math = 'math',
  Footnote = 'footnote'
}

/** One inline run of a paragraph, a heading, a list item or a table cell. */
export abstract class MarkdownInline {
  readonly kind: MarkdownInlineKind;

  constructor(kind: MarkdownInlineKind) {
    this.kind = kind;
  }
}

/** Literal text, including raw HTML tags: they are drawn verbatim. */
export class MarkdownTextInline extends MarkdownInline {
  text: string;

  constructor(text: string) {
    super(MarkdownInlineKind.Text);
    this.text = text;
  }
}

/** `**strong**` / `__strong__`. */
export class MarkdownStrongInline extends MarkdownInline {
  children: MarkdownInline[];

  constructor(children: MarkdownInline[]) {
    super(MarkdownInlineKind.Strong);
    this.children = children;
  }
}

/** `*emphasis*` / `_emphasis_`. */
export class MarkdownEmphasisInline extends MarkdownInline {
  children: MarkdownInline[];

  constructor(children: MarkdownInline[]) {
    super(MarkdownInlineKind.Emphasis);
    this.children = children;
  }
}

/** `~~deleted~~`. */
export class MarkdownDeleteInline extends MarkdownInline {
  children: MarkdownInline[];

  constructor(children: MarkdownInline[]) {
    super(MarkdownInlineKind.Delete);
    this.children = children;
  }
}

/** `` `code` `` — a monospaced span, not a block. */
export class MarkdownCodeInline extends MarkdownInline {
  code: string;

  constructor(code: string) {
    super(MarkdownInlineKind.Code);
    this.code = code;
  }
}

/** `[label](destination)`. */
export class MarkdownLinkInline extends MarkdownInline {
  destination: string;
  children: MarkdownInline[];

  constructor(destination: string, children: MarkdownInline[]) {
    super(MarkdownInlineKind.Link);
    this.destination = destination;
    this.children = children;
  }
}

/** Image content is not downloaded; the alt text is displayed in italics. */
export class MarkdownImageInline extends MarkdownInline {
  alt: string;

  constructor(alt: string) {
    super(MarkdownInlineKind.Image);
    this.alt = alt;
  }
}

/** `$…$` / `\(…\)` — the TeX source, left for a platform renderer. */
export class MarkdownMathInline extends MarkdownInline {
  latex: string;

  constructor(latex: string) {
    super(MarkdownInlineKind.Math);
    this.latex = latex;
  }
}

/** `[^id]` — a superscript reference. */
export class MarkdownFootnoteInline extends MarkdownInline {
  id: string;

  constructor(id: string) {
    super(MarkdownInlineKind.Footnote);
    this.id = id;
  }
}

/** One block of an answer. */
export abstract class MarkdownBlock {
  readonly kind: MarkdownBlockKind;

  constructor(kind: MarkdownBlockKind) {
    this.kind = kind;
  }
}

/** A paragraph; soft line breaks inside it become spaces, like CommonMark. */
export class MarkdownParagraphBlock extends MarkdownBlock {
  inlines: MarkdownInline[];

  constructor(inlines: MarkdownInline[]) {
    super(MarkdownBlockKind.Paragraph);
    this.inlines = inlines;
  }
}

/** `#` … `######`. */
export class MarkdownHeadingBlock extends MarkdownBlock {
  level: number;
  inlines: MarkdownInline[];

  constructor(level: number, inlines: MarkdownInline[]) {
    super(MarkdownBlockKind.Heading);
    this.level = level;
    this.inlines = inlines;
  }
}

/** A fenced code block; `language` may be empty. */
export class MarkdownCodeBlock extends MarkdownBlock {
  language: string;
  body: string;

  constructor(language: string, body: string) {
    super(MarkdownBlockKind.Code);
    this.language = language;
    this.body = body;
  }
}

/** A GFM table. */
export class MarkdownTableBlock extends MarkdownBlock {
  header: MarkdownInline[][];
  rows: MarkdownInline[][][];
  alignments: (MarkdownTableAlign | null)[];

  constructor(
    header: MarkdownInline[][],
    rows: MarkdownInline[][][],
    alignments: (MarkdownTableAlign | null)[]
  ) {
    super(MarkdownBlockKind.Table);
    this.header = header;
    this.rows = rows;
    this.alignments = alignments;
  }
}

/** Display math: the TeX source between `$$…$$` or `\[…\]`. */
export class MarkdownMathBlock extends MarkdownBlock {
  latex: string;

  constructor(latex: string) {
    super(MarkdownBlockKind.Math);
    this.latex = latex;
  }
}

/** A paragraph whose only child is a Markdown image, preserving its metadata. */
export class MarkdownImageBlock extends MarkdownBlock {
  source: string;
  alt: string;
  title: string;

  constructor(source: string, alt: string, title: string) {
    super(MarkdownBlockKind.Image);
    this.source = source;
    this.alt = alt;
    this.title = title;
  }
}

/** One list item: the GFM task marker (when present) and its content. */
export class MarkdownListItem {
  checked: boolean | null;
  inlines: MarkdownInline[];
  children: MarkdownBlock[];
  imageBlock: MarkdownImageBlock | null;

  constructor(
    checked: boolean | null,
    inlines: MarkdownInline[],
    children: MarkdownBlock[] = [],
    imageBlock: MarkdownImageBlock | null = null
  ) {
    this.checked = checked;
    this.inlines = inlines;
    this.children = children;
    this.imageBlock = imageBlock;
  }
}

/** A bullet or ordered list. */
export class MarkdownListBlock extends MarkdownBlock {
  ordered: boolean;
  start: number;
  items: MarkdownListItem[];

  constructor(ordered: boolean, start: number, items: MarkdownListItem[]) {
    super(MarkdownBlockKind.List);
    this.ordered = ordered;
    this.start = start;
    this.items = items;
  }
}

/** A `>` quote: one level, recursively parsed. */
export class MarkdownQuoteBlock extends MarkdownBlock {
  blocks: MarkdownBlock[];

  constructor(blocks: MarkdownBlock[]) {
    super(MarkdownBlockKind.Quote);
    this.blocks = blocks;
  }
}

/** `---` / `***` / `___`. */
export class MarkdownRuleBlock extends MarkdownBlock {
  constructor() {
    super(MarkdownBlockKind.Rule);
  }
}

// --------------------------------------------------------------------- helpers

function startsAt(text: string, token: string, index: number): boolean {
  if (index < 0 || index + token.length > text.length) {
    return false;
  }
  return text.substring(index, index + token.length) === token;
}

function trimEndBlank(text: string): string {
  let end: number = text.length;
  while (end > 0) {
    const code: number = text.charCodeAt(end - 1);
    if (code === 32 || code === 9) {
      end--;
    } else {
      break;
    }
  }
  return text.substring(0, end);
}

function trimEndWhitespace(text: string): string {
  let end: number = text.length;
  while (end > 0) {
    const code: number = text.charCodeAt(end - 1);
    if (code === 32 || code === 9 || code === 10 || code === 13) {
      end--;
    } else {
      break;
    }
  }
  return text.substring(0, end);
}

function leadingIndent(line: string): number {
  let count: number = 0;
  while (count < line.length) {
    const code: number = line.charCodeAt(count);
    if (code === 32 || code === 9) {
      count++;
    } else {
      break;
    }
  }
  return count;
}

function matchEntire(pattern: RegExp, text: string): RegExpExecArray | null {
  const match: RegExpExecArray | null = pattern.exec(text);
  if (match !== null && match[0] === text) {
    return match;
  }
  return null;
}

function matchGroup(match: RegExpExecArray | null, index: number): string {
  if (match === null || index >= match.length) {
    return '';
  }
  return match[index];
}

function isBlankLine(text: string): boolean {
  return text.trim().length === 0;
}

function removeSurrounding(text: string, open: string, close: string): string {
  if (text.length >= open.length + close.length && text.startsWith(open) && text.endsWith(close)) {
    return text.substring(open.length, text.length - close.length);
  }
  return text;
}

/** A safe destination for a markdown link. */
export enum MarkdownLinkKind {
  External = 'external',
  WorkspacePath = 'workspace',
  ResourceUri = 'resource'
}

/** Routes external links and safe relative workspace paths. */
export class MarkdownLinkTarget {
  kind: MarkdownLinkKind;
  value: string;

  constructor(kind: MarkdownLinkKind, value: string) {
    this.kind = kind;
    this.value = value;
  }
}

const EXTERNAL_MARKDOWN_SCHEMES: string[] = ['http', 'https', 'mailto'];
const WORKSPACE_MARKDOWN_SCHEMES: string[] = ['file', 'sandbox', 'dsh-resource'];
const SCHEME_PATTERN: RegExp = new RegExp('^([A-Za-z][A-Za-z0-9+.\\-]*):');

function hasControlCharacter(text: string): boolean {
  for (let i: number = 0; i < text.length; i++) {
    const code: number = text.charCodeAt(i);
    if (code < 32 || code === 127) {
      return true;
    }
  }
  return false;
}

function containsScheme(text: string): boolean {
  return SCHEME_PATTERN.exec(text) !== null;
}

function isExternalScheme(scheme: string): boolean {
  return EXTERNAL_MARKDOWN_SCHEMES.indexOf(scheme) >= 0;
}

function isWorkspaceScheme(scheme: string): boolean {
  return WORKSPACE_MARKDOWN_SCHEMES.indexOf(scheme) >= 0;
}

function decodePercent(text: string): string | null {
  try {
    return decodeURIComponent(text);
  } catch (error) {
    return null;
  }
}

function hasUnsafeComponent(path: string): boolean {
  const components: string[] = path.split('/');
  let nonEmpty: number = 0;
  for (let i: number = 0; i < components.length; i++) {
    const component: string = components[i];
    if (component.length === 0) {
      // Leading, trailing and doubled slashes are tolerated for resource URIs.
      continue;
    }
    nonEmpty++;
    if (component === '.' || component === '..') {
      return true;
    }
  }
  return nonEmpty === 0;
}

function isSafeWorkspaceUri(trimmed: string, scheme: string, rest: string): boolean {
  if (trimmed.indexOf('?') >= 0 || trimmed.indexOf('#') >= 0) {
    return false;
  }
  let path: string = rest;
  if (scheme === 'file' || scheme === 'sandbox') {
    if (rest.startsWith('//')) {
      const afterAuthority: string = rest.substring(2);
      const slash: number = afterAuthority.indexOf('/');
      const authority: string = slash >= 0 ? afterAuthority.substring(0, slash) : afterAuthority;
      if (authority.length > 0) {
        return false;
      }
      path = slash >= 0 ? afterAuthority.substring(slash) : '';
    } else if (scheme === 'sandbox') {
      // Opaque sandbox:relative is a valid native address.
      path = rest;
    } else {
      // A non-authority file URI has no absolute workspace path to open.
      return false;
    }
    const decoded: string | null = decodePercent(path);
    if (decoded === null) {
      return false;
    }
    path = decoded;
  } else if (scheme === 'dsh-resource') {
    if (!rest.startsWith('//')) {
      return false;
    }
    const afterAuthority: string = rest.substring(2);
    const slash: number = afterAuthority.indexOf('/');
    const authority: string = slash >= 0 ? afterAuthority.substring(0, slash) : afterAuthority;
    if (authority.toLowerCase() !== 'file') {
      return false;
    }
    const decoded: string | null = decodePercent(slash >= 0 ? afterAuthority.substring(slash) : '');
    if (decoded === null) {
      return false;
    }
    path = decoded;
    const segments: string[] = path.split('/');
    const parts: string[] = [];
    for (let i: number = 0; i < segments.length; i++) {
      if (segments[i].length > 0) {
        parts.push(segments[i]);
      }
    }
    if (parts.length < 1) {
      return false;
    }
    let tail: string[] = [];
    if (parts[0] === 'session') {
      if (parts.length < 3) {
        return false;
      }
      tail = parts.slice(2);
    } else if (parts[0] === 'absolute') {
      if (parts.length < 2) {
        return false;
      }
      tail = parts.slice(1);
    } else {
      return false;
    }
    const joined: string = tail.join('/');
    if (joined.length === 0 || joined === 'dsh/workspace') {
      return false;
    }
    if (joined.startsWith('dsh/') && !joined.startsWith('dsh/workspace/')) {
      return false;
    }
    path = joined;
  } else {
    return false;
  }
  return !hasUnsafeComponent(path);
}

/**
 * Routes external links, app-resolved resource URIs, and safe relative
 * workspace paths without converting native resource schemes into guessed
 * local paths.
 */
export function markdownLinkTarget(destination: string): MarkdownLinkTarget | null {
  const trimmed: string = destination.trim();
  if (trimmed.length === 0 || hasControlCharacter(trimmed)) {
    return null;
  }
  const match: RegExpExecArray | null = SCHEME_PATTERN.exec(trimmed);
  if (match !== null) {
    const scheme: string = matchGroup(match, 1).toLowerCase();
    const rest: string = trimmed.substring(match[0].length);
    if (isExternalScheme(scheme)) {
      return new MarkdownLinkTarget(MarkdownLinkKind.External, trimmed);
    }
    if (!isWorkspaceScheme(scheme)) {
      return null;
    }
    if (!isSafeWorkspaceUri(trimmed, scheme, rest)) {
      return null;
    }
    return new MarkdownLinkTarget(MarkdownLinkKind.ResourceUri, trimmed);
  }
  if (trimmed.startsWith('/') || trimmed.startsWith('\\') || trimmed.indexOf(':') >= 0) {
    return null;
  }
  const path: string = trimmed.split('\\').join('/');
  const components: string[] = path.split('/');
  for (let i: number = 0; i < components.length; i++) {
    const component: string = components[i];
    if (component === '..' || component === '.' || component.length === 0) {
      return null;
    }
  }
  return new MarkdownLinkTarget(MarkdownLinkKind.WorkspacePath, path);
}

// ---------------------------------------------------------------------- parser

const FENCE: string = '```';
const FOOTNOTE_HEADING: string = '脚注';
const FOOTNOTE_SENTINEL: string = 'DSHFOOTNOTE';
const FOOTNOTE_SENTINEL_END: string = 'END';

const FOOTNOTE_DEFINITION: RegExp = new RegExp('^\\[\\^([^\\]]+)\\]\\s*:\\s*(.*)$');
const DELIMITER_CELL: RegExp = new RegExp('^:?-{1,}:?$');
const HEADING_PATTERN: RegExp = new RegExp('^(#{1,6})\\s+(.*)$');
const RULE_PATTERN: RegExp = new RegExp('^(?:-{3,}|\\*{3,}|_{3,})$');
const BULLET_PATTERN: RegExp = new RegExp('^\\s*[-*+]\\s+(.*)$');
const ORDERED_PATTERN: RegExp = new RegExp('^\\s*(\\d{1,9})[.)]\\s+(.*)$');
const TASK_PATTERN: RegExp = new RegExp('^\\[([ xX])]\\s+(.*)$');

class PendingListItem {
  item: MarkdownListItem;
  childLines: string[];

  constructor(item: MarkdownListItem, childLines: string[] = []) {
    this.item = item;
    this.childLines = childLines;
  }
}

/** Parses an answer body into blocks. */
export class MarkdownParser {
  /** @param source the projected answer body. @return the blocks in source order. */
  static parse(source: string): MarkdownBlock[] {
    const normalized: string = source.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
    const lines: string[] = normalized.split('\n');
    const footnoteIds: string[] = [];
    const footnoteDefinitions: string[] = [];
    const body: string[] = [];
    for (let i: number = 0; i < lines.length; i++) {
      const match: RegExpExecArray | null = matchEntire(FOOTNOTE_DEFINITION, lines[i]);
      if (match === null) {
        body.push(lines[i]);
      } else {
        const id: string = matchGroup(match, 1);
        const index: number = footnoteIds.indexOf(id);
        if (index >= 0) {
          footnoteDefinitions[index] = matchGroup(match, 2);
        } else {
          footnoteIds.push(id);
          footnoteDefinitions.push(matchGroup(match, 2));
        }
      }
    }
    const bodyWithReferences: string[] = [];
    for (let i: number = 0; i < body.length; i++) {
      let line: string = body[i];
      for (let f: number = 0; f < footnoteIds.length; f++) {
        line = line.split('[^' + footnoteIds[f] + ']').join(
          FOOTNOTE_SENTINEL + footnoteIds[f] + FOOTNOTE_SENTINEL_END
        );
      }
      bodyWithReferences.push(line);
    }
    const blocks: MarkdownBlock[] = MarkdownParser.parseBlocks(bodyWithReferences, 0);
    if (footnoteIds.length > 0) {
      // The section is appended after the body: a bold 脚注 line, then each
      // identifier and its parsed definition, id-sorted.
      blocks.push(new MarkdownParagraphBlock([
        new MarkdownStrongInline([new MarkdownTextInline(FOOTNOTE_HEADING)])
      ]));
      const sortedIds: string[] = footnoteIds.slice();
      sortedIds.sort();
      for (let i: number = 0; i < sortedIds.length; i++) {
        const id: string = sortedIds[i];
        const definitionIndex: number = footnoteIds.indexOf(id);
        const definition: string = footnoteDefinitions[definitionIndex];
        const inlines: MarkdownInline[] = [new MarkdownTextInline('[' + id + '] ')];
        const parsed: MarkdownInline[] = MarkdownParser.inlines(definition);
        for (let p: number = 0; p < parsed.length; p++) {
          inlines.push(parsed[p]);
        }
        blocks.push(new MarkdownParagraphBlock(inlines));
      }
    }
    return blocks;
  }

  private static parseBlocks(lines: string[], depth: number): MarkdownBlock[] {
    if (depth >= MAX_MARKDOWN_BLOCK_DEPTH) {
      if (lines.length === 0) {
        return [];
      }
      return [new MarkdownParagraphBlock([new MarkdownTextInline(lines.join('\n'))])];
    }
    const out: MarkdownBlock[] = [];
    let paragraph: string[] = [];

    const flushParagraph = (): void => {
      if (paragraph.length === 0) {
        return;
      }
      const builder: string[] = [];
      for (let index: number = 0; index < paragraph.length; index++) {
        const line: string = paragraph[index];
        const trailing: number = line.length - trimEndBlank(line).length;
        const explicitBreak: boolean = trimEndWhitespace(line).endsWith('\\');
        let content: string = trimEndBlank(line.trim());
        if (explicitBreak && content.endsWith('\\')) {
          content = content.substring(0, content.length - 1);
        }
        builder.push(content);
        if (index < paragraph.length - 1) {
          builder.push(trailing >= 2 || explicitBreak ? '\n' : ' ');
        }
      }
      out.push(new MarkdownParagraphBlock(MarkdownParser.inlines(builder.join(''))));
      paragraph = [];
    };

    let i: number = 0;
    while (i < lines.length) {
      const line: string = lines[i];
      const trimmed: string = line.trim();
      if (trimmed.length === 0) {
        flushParagraph();
        i++;
      } else if (trimmed.startsWith('![')) {
        const image: MarkdownImageBlock | null = paragraph.length === 0
          ? MarkdownParser.standaloneImage(trimmed)
          : null;
        if (image !== null && MarkdownParser.endsStandaloneImageParagraph(lines, i)) {
          out.push(image);
        } else {
          // Image labels inside a prose paragraph keep the inline-alt path.
          paragraph.push(line);
        }
        i++;
      } else if (trimmed.startsWith(FENCE)) {
        const language: string = trimEndBlank(trimmed.substring(FENCE.length)).trim();
        const body: string[] = [];
        let j: number = i + 1;
        while (j < lines.length && lines[j].trim() !== FENCE) {
          body.push(lines[j]);
          j++;
        }
        if (j >= lines.length) {
          // Unterminated fence: the stream has not finished arriving. Keep it
          // prose so a half answer never flashes an empty card.
          paragraph.push(trimmed);
          i++;
        } else {
          flushParagraph();
          out.push(new MarkdownCodeBlock(language, body.join('\n')));
          i = j + 1;
        }
      } else if (trimmed.startsWith('$$')) {
        flushParagraph();
        const close: number = trimmed.indexOf('$$', 2);
        if (close > 0) {
          out.push(new MarkdownMathBlock(trimmed.substring(2, close).trim()));
          i++;
        } else {
          const latex: string[] = [trimmed.substring(2)];
          let j: number = i + 1;
          while (j < lines.length && lines[j].trim() !== '$$') {
            latex.push(lines[j]);
            j++;
          }
          out.push(new MarkdownMathBlock(latex.join('\n').trim()));
          i = j >= lines.length ? lines.length : j + 1;
        }
      } else if (trimmed.startsWith('\\[')) {
        flushParagraph();
        if (trimmed.length > 4 && trimmed.endsWith('\\]')) {
          out.push(new MarkdownMathBlock(trimmed.substring(2, trimmed.length - 2)));
          i++;
        } else {
          const latex: string[] = [trimmed.substring(2)];
          let j: number = i + 1;
          while (j < lines.length && !lines[j].trim().endsWith('\\]')) {
            latex.push(lines[j]);
            j++;
          }
          if (j < lines.length) {
            latex.push(lines[j].trim().substring(0, lines[j].trim().length - 2));
          }
          out.push(new MarkdownMathBlock(latex.join('\n').trim()));
          i = j >= lines.length ? lines.length : j + 1;
        }
      } else if (MarkdownParser.isTableHeader(lines, i)) {
        flushParagraph();
        const header: MarkdownInline[][] = MarkdownParser.cells(lines[i]).map(
          (cell: string): MarkdownInline[] => MarkdownParser.inlines(cell)
        );
        const alignments: (MarkdownTableAlign | null)[] = MarkdownParser.cells(lines[i + 1]).map(
          (cell: string): MarkdownTableAlign | null => MarkdownParser.alignment(cell)
        );
        const rows: MarkdownInline[][][] = [];
        let j: number = i + 2;
        while (j < lines.length && lines[j].indexOf('|') >= 0 && lines[j].trim().length > 0) {
          rows.push(MarkdownParser.cells(lines[j]).map(
            (cell: string): MarkdownInline[] => MarkdownParser.inlines(cell)
          ));
          j++;
        }
        out.push(new MarkdownTableBlock(header, rows, alignments));
        i = j;
      } else {
        const heading: RegExpExecArray | null = matchEntire(HEADING_PATTERN, trimmed);
        if (heading !== null) {
          flushParagraph();
          out.push(new MarkdownHeadingBlock(
            matchGroup(heading, 1).length,
            MarkdownParser.inlines(matchGroup(heading, 2).trim())
          ));
          i++;
        } else if (matchEntire(RULE_PATTERN, trimmed) !== null) {
          flushParagraph();
          out.push(new MarkdownRuleBlock());
          i++;
        } else if (trimmed.startsWith('>')) {
          flushParagraph();
          const quoted: string[] = [];
          let j: number = i;
          while (j < lines.length && lines[j].trim().startsWith('>')) {
            let text: string = lines[j].trim().substring(1);
            if (text.startsWith(' ')) {
              text = text.substring(1);
            }
            quoted.push(text);
            j++;
          }
          out.push(new MarkdownQuoteBlock(MarkdownParser.parseBlocks(quoted, depth + 1)));
          i = j;
        } else if (matchEntire(BULLET_PATTERN, line) !== null || matchEntire(ORDERED_PATTERN, line) !== null) {
          flushParagraph();
          const listIndent: number = leadingIndent(line);
          const ordered: boolean = matchEntire(ORDERED_PATTERN, line) !== null;
          const start: number = ordered
            ? MarkdownParser.parseStart(matchGroup(matchEntire(ORDERED_PATTERN, line), 1))
            : 1;
          const pending: PendingListItem[] = [];
          let j: number = i;
          while (j < lines.length) {
            const candidate: string = lines[j];
            const candidateIndent: number = leadingIndent(candidate);
            const hasMarker: boolean = matchEntire(BULLET_PATTERN, candidate) !== null
              || matchEntire(ORDERED_PATTERN, candidate) !== null;
            if (hasMarker && candidateIndent === listIndent) {
              const rawItemMatch: RegExpExecArray | null = ordered
                ? matchEntire(ORDERED_PATTERN, candidate)
                : matchEntire(BULLET_PATTERN, candidate);
              if (rawItemMatch === null) {
                break;
              }
              const rawItem: string = matchGroup(rawItemMatch, ordered ? 2 : 1);
              let text: string = rawItem;
              let checked: boolean | null = null;
              const task: RegExpExecArray | null = matchEntire(TASK_PATTERN, rawItem);
              if (task !== null) {
                checked = matchGroup(task, 1).toLowerCase() === 'x';
                text = matchGroup(task, 2);
              }
              pending.push(new PendingListItem(
                new MarkdownListItem(checked, MarkdownParser.inlines(text), [], MarkdownParser.standaloneImage(text))
              ));
              j++;
            } else if (pending.length > 0 && candidateIndent > listIndent) {
              // Strip the parent marker's continuation indent so the recursive
              // parser sees the nested list at its own root.
              const drop: number = Math.min(listIndent + 2, candidate.length);
              pending[pending.length - 1].childLines.push(
                candidate.trim().length === 0 ? '' : candidate.substring(drop)
              );
              j++;
            } else if (candidate.trim().length === 0 && pending.length > 0 && j + 1 < lines.length
              && leadingIndent(lines[j + 1]) > listIndent) {
              pending[pending.length - 1].childLines.push('');
              j++;
            } else {
              break;
            }
          }
          const items: MarkdownListItem[] = [];
          for (let p: number = 0; p < pending.length; p++) {
            const item: MarkdownListItem = pending[p].item;
            const childLines: string[] = pending[p].childLines;
            const standalone: MarkdownImageBlock | null = childLines.length === 0 ? item.imageBlock : null;
            items.push(new MarkdownListItem(
              item.checked,
              standalone !== null ? [] : item.inlines,
              MarkdownParser.parseBlocks(childLines, depth + 1),
              standalone
            ));
          }
          out.push(new MarkdownListBlock(ordered, start, items));
          i = j;
        } else {
          paragraph.push(line);
          i++;
        }
      }
    }
    flushParagraph();
    return out;
  }

  private static parseStart(value: string): number {
    const parsed: number = parseInt(value, 10);
    return isNaN(parsed) ? 1 : parsed;
  }

  private static cells(line: string): string[] {
    let text: string = line.trim();
    if (text.startsWith('|')) {
      text = text.substring(1);
    }
    if (text.endsWith('|')) {
      text = text.substring(0, text.length - 1);
    }
    const raw: string[] = text.split('|');
    const cells: string[] = [];
    for (let i: number = 0; i < raw.length; i++) {
      cells.push(raw[i].trim());
    }
    return cells;
  }

  private static isTableHeader(lines: string[], index: number): boolean {
    if (index + 1 >= lines.length) {
      return false;
    }
    const header: string = lines[index];
    if (header.indexOf('|') < 0) {
      return false;
    }
    const delimiter: string = lines[index + 1].trim();
    if (delimiter.length === 0) {
      return false;
    }
    for (let i: number = 0; i < delimiter.length; i++) {
      const ch: string = delimiter.charAt(i);
      if ('-:| \t'.indexOf(ch) < 0) {
        return false;
      }
    }
    if (delimiter.indexOf('-') < 0) {
      return false;
    }
    const columns: number = MarkdownParser.cells(header).length;
    const markers: string[] = MarkdownParser.cells(delimiter);
    if (markers.length !== columns) {
      return false;
    }
    for (let i: number = 0; i < markers.length; i++) {
      if (matchEntire(DELIMITER_CELL, markers[i]) === null) {
        return false;
      }
    }
    return true;
  }

  private static endsStandaloneImageParagraph(lines: string[], index: number): boolean {
    if (index + 1 >= lines.length || lines[index + 1].trim().length === 0) {
      return true;
    }
    const next: string = lines[index + 1].trim();
    return next.startsWith(FENCE) || next.startsWith('$$') || next.startsWith('\\[')
      || matchEntire(HEADING_PATTERN, next) !== null
      || matchEntire(RULE_PATTERN, next) !== null
      || next.startsWith('>')
      || MarkdownParser.isTableHeader(lines, index + 1)
      || matchEntire(BULLET_PATTERN, lines[index + 1]) !== null
      || matchEntire(ORDERED_PATTERN, lines[index + 1]) !== null;
  }

  private static standaloneImage(line: string): MarkdownImageBlock | null {
    if (!line.startsWith('![')) {
      return null;
    }
    const labelEnd: number = line.indexOf(']', 2);
    if (labelEnd < 2 || labelEnd + 2 >= line.length || line.charAt(labelEnd + 1) !== '('
      || !line.endsWith(')')) {
      return null;
    }
    const label: string = line.substring(2, labelEnd);
    const contents: string = line.substring(labelEnd + 2, line.length - 1).trim();
    if (contents.length === 0) {
      return null;
    }
    let destinationEnd: number = 0;
    if (contents.startsWith('<')) {
      const close: number = contents.indexOf('>');
      if (close <= 0) {
        return null;
      }
      destinationEnd = close + 1;
    } else {
      let depth: number = 0;
      let index: number = 0;
      while (index < contents.length) {
        const ch: string = contents.charAt(index);
        if (ch === '\\') {
          index++;
        } else if (ch === '(') {
          depth++;
        } else if (ch === ')') {
          if (depth > 0) {
            depth--;
          } else {
            break;
          }
        } else if ((ch === ' ' || ch === '\t') && depth === 0) {
          break;
        }
        index++;
      }
      destinationEnd = index;
    }
    if (destinationEnd <= 0) {
      return null;
    }
    const rawDestination: string = removeSurrounding(
      contents.substring(0, destinationEnd), '<', '>'
    ).trim();
    if (rawDestination.length === 0) {
      return null;
    }
    const rawTitle: string = contents.substring(destinationEnd).trim();
    let title: string = '';
    if (rawTitle.length >= 2 && rawTitle.charAt(0) === '"' && rawTitle.charAt(rawTitle.length - 1) === '"') {
      title = rawTitle.substring(1, rawTitle.length - 1);
    } else if (rawTitle.length >= 2 && rawTitle.charAt(0) === '\''
      && rawTitle.charAt(rawTitle.length - 1) === '\'') {
      title = rawTitle.substring(1, rawTitle.length - 1);
    } else if (rawTitle.length >= 2 && rawTitle.charAt(0) === '('
      && rawTitle.charAt(rawTitle.length - 1) === ')') {
      title = rawTitle.substring(1, rawTitle.length - 1);
    } else if (rawTitle.length > 0) {
      return null;
    }
    return new MarkdownImageBlock(rawDestination, label, title);
  }

  private static alignment(cell: string): MarkdownTableAlign | null {
    if (cell.startsWith(':') && cell.endsWith(':')) {
      return MarkdownTableAlign.Center;
    }
    if (cell.endsWith(':')) {
      return MarkdownTableAlign.Right;
    }
    if (cell.startsWith(':')) {
      return MarkdownTableAlign.Left;
    }
    return null;
  }

  /** Parses the inline runs of one text run. */
  static inlines(text: string): MarkdownInline[] {
    const out: MarkdownInline[] = [];
    let buffer: string[] = [];
    const flush = (): void => {
      if (buffer.length === 0) {
        return;
      }
      out.push(new MarkdownTextInline(buffer.join('')));
      buffer = [];
    };

    let i: number = 0;
    while (i < text.length) {
      if (startsAt(text, '**', i)) {
        const end: number = text.indexOf('**', i + 2);
        if (end > i + 2) {
          flush();
          out.push(new MarkdownStrongInline(MarkdownParser.inlines(text.substring(i + 2, end))));
          i = end + 2;
        } else {
          buffer.push(text.charAt(i));
          i++;
        }
      } else if (startsAt(text, '~~', i)) {
        const end: number = text.indexOf('~~', i + 2);
        if (end > i + 2) {
          flush();
          out.push(new MarkdownDeleteInline(MarkdownParser.inlines(text.substring(i + 2, end))));
          i = end + 2;
        } else {
          buffer.push(text.charAt(i));
          i++;
        }
      } else if (text.charAt(i) === '`') {
        const end: number = text.indexOf('`', i + 1);
        if (end > i + 1) {
          flush();
          out.push(new MarkdownCodeInline(text.substring(i + 1, end)));
          i = end + 1;
        } else {
          buffer.push(text.charAt(i));
          i++;
        }
      } else if (startsAt(text, FOOTNOTE_SENTINEL, i)) {
        const end: number = text.indexOf(FOOTNOTE_SENTINEL_END, i + FOOTNOTE_SENTINEL.length);
        if (end > i) {
          flush();
          out.push(new MarkdownFootnoteInline(
            text.substring(i + FOOTNOTE_SENTINEL.length, end)
          ));
          i = end + FOOTNOTE_SENTINEL_END.length;
        } else {
          buffer.push(text.charAt(i));
          i++;
        }
      } else if (startsAt(text, '![', i)) {
        const label: number = text.indexOf(']', i + 2);
        if (label > i && label + 1 < text.length && text.charAt(label + 1) === '(') {
          const close: number = text.indexOf(')', label + 2);
          if (close > label) {
            flush();
            out.push(new MarkdownImageInline(text.substring(i + 2, label)));
            i = close + 1;
          } else {
            buffer.push(text.charAt(i));
            i++;
          }
        } else {
          buffer.push(text.charAt(i));
          i++;
        }
      } else if (text.charAt(i) === '[') {
        const label: number = text.indexOf(']', i + 1);
        if (label > i && label + 1 < text.length && text.charAt(label + 1) === '(') {
          const close: number = text.indexOf(')', label + 2);
          if (close > label) {
            flush();
            out.push(new MarkdownLinkInline(
              text.substring(label + 2, close).trim(),
              MarkdownParser.inlines(text.substring(i + 1, label))
            ));
            i = close + 1;
          } else {
            buffer.push(text.charAt(i));
            i++;
          }
        } else {
          buffer.push(text.charAt(i));
          i++;
        }
      } else if (startsAt(text, '\\(', i)) {
        const end: number = text.indexOf('\\)', i + 2);
        if (end > i + 2) {
          flush();
          out.push(new MarkdownMathInline(text.substring(i + 2, end)));
          i = end + 2;
        } else {
          buffer.push(text.charAt(i));
          i++;
        }
      } else if (text.charAt(i) === '$' && !startsAt(text, '$$', i)) {
        const end: number = text.indexOf('$', i + 1);
        const content: string = end > i + 1 ? text.substring(i + 1, end) : '';
        if (end > i + 1 && content.indexOf('\n') < 0 && !content.startsWith('$')) {
          flush();
          out.push(new MarkdownMathInline(content));
          i = end + 1;
        } else {
          buffer.push(text.charAt(i));
          i++;
        }
      } else if (text.charAt(i) === '*') {
        const end: number = text.indexOf('*', i + 1);
        if (end > i + 1) {
          flush();
          out.push(new MarkdownEmphasisInline(MarkdownParser.inlines(text.substring(i + 1, end))));
          i = end + 1;
        } else {
          buffer.push(text.charAt(i));
          i++;
        }
      } else {
        buffer.push(text.charAt(i));
        i++;
      }
    }
    flush();
    return out;
  }
}

/**
 * Parses [markdown] and falls back to one plain-text paragraph if the parser
 * fails for any reason, including hostile nesting. The original source stays on
 * screen instead of taking the app down.
 */
export function parseMarkdownOrPlain(
  markdown: string,
  parse: (source: string) => MarkdownBlock[] = (source: string): MarkdownBlock[] =>
    MarkdownParser.parse(source)
): MarkdownBlock[] {
  try {
    return parse(markdown);
  } catch (error) {
    return [new MarkdownParagraphBlock([new MarkdownTextInline(markdown)])];
  }
}

/** Flattens inline runs to plain text; used for selection/copy fallbacks. */
export function plainText(inlines: MarkdownInline[]): string {
  const out: string[] = [];
  for (let i: number = 0; i < inlines.length; i++) {
    const part: MarkdownInline = inlines[i];
    if (part instanceof MarkdownTextInline) {
      out.push(part.text);
    } else if (part instanceof MarkdownStrongInline) {
      out.push(plainText(part.children));
    } else if (part instanceof MarkdownEmphasisInline) {
      out.push(plainText(part.children));
    } else if (part instanceof MarkdownDeleteInline) {
      out.push(plainText(part.children));
    } else if (part instanceof MarkdownCodeInline) {
      out.push(part.code);
    } else if (part instanceof MarkdownLinkInline) {
      out.push(plainText(part.children));
    } else if (part instanceof MarkdownImageInline) {
      out.push(part.alt);
    } else if (part instanceof MarkdownMathInline) {
      out.push(part.latex);
    } else if (part instanceof MarkdownFootnoteInline) {
      out.push('[^' + part.id + ']');
    }
  }
  return out.join('');
}

// ------------------------------------------------------------------ style runs

/** Accumulated inline styles for one rendered span. */
export class MarkdownSpanStyle {
  bold: boolean = false;
  italic: boolean = false;
  strike: boolean = false;
  code: boolean = false;
  link: boolean = false;
  footnote: boolean = false;
  target: MarkdownLinkTarget | null = null;

  clone(): MarkdownSpanStyle {
    const next: MarkdownSpanStyle = new MarkdownSpanStyle();
    next.bold = this.bold;
    next.italic = this.italic;
    next.strike = this.strike;
    next.code = this.code;
    next.link = this.link;
    next.footnote = this.footnote;
    next.target = this.target;
    return next;
  }
}

/** One flattened, styled text run ready for an ArkUI `Span`. */
export class MarkdownRun {
  text: string;
  style: MarkdownSpanStyle;

  constructor(text: string, style: MarkdownSpanStyle) {
    this.text = text;
    this.style = style;
  }
}

/**
 * Flattens nested inline markup into contiguous styled runs. Links with an
 * unsafe or host-resolved-only destination stay plain text, matching Android:
 * file/sandbox/dsh-resource addresses need an explicit resource callback.
 */
export function flattenInlines(
  inlines: MarkdownInline[],
  base: MarkdownSpanStyle = new MarkdownSpanStyle()
): MarkdownRun[] {
  const out: MarkdownRun[] = [];
  for (let i: number = 0; i < inlines.length; i++) {
    const part: MarkdownInline = inlines[i];
    if (part instanceof MarkdownTextInline) {
      out.push(new MarkdownRun(part.text, base.clone()));
    } else if (part instanceof MarkdownStrongInline) {
      const next: MarkdownSpanStyle = base.clone();
      next.bold = true;
      MarkdownParserAppend(out, flattenInlines(part.children, next));
    } else if (part instanceof MarkdownEmphasisInline) {
      const next: MarkdownSpanStyle = base.clone();
      next.italic = true;
      MarkdownParserAppend(out, flattenInlines(part.children, next));
    } else if (part instanceof MarkdownDeleteInline) {
      const next: MarkdownSpanStyle = base.clone();
      next.strike = true;
      MarkdownParserAppend(out, flattenInlines(part.children, next));
    } else if (part instanceof MarkdownCodeInline) {
      const next: MarkdownSpanStyle = base.clone();
      next.code = true;
      out.push(new MarkdownRun(part.code, next));
    } else if (part instanceof MarkdownLinkInline) {
      const target: MarkdownLinkTarget | null = markdownLinkTarget(part.destination);
      if (target === null || target.kind === MarkdownLinkKind.ResourceUri) {
        MarkdownParserAppend(out, flattenInlines(part.children, base));
      } else {
        const next: MarkdownSpanStyle = base.clone();
        next.link = true;
        next.target = target;
        MarkdownParserAppend(out, flattenInlines(part.children, next));
      }
    } else if (part instanceof MarkdownImageInline) {
      const next: MarkdownSpanStyle = base.clone();
      next.italic = true;
      out.push(new MarkdownRun(part.alt, next));
    } else if (part instanceof MarkdownMathInline) {
      const next: MarkdownSpanStyle = base.clone();
      next.code = true;
      out.push(new MarkdownRun(part.latex, next));
    } else if (part instanceof MarkdownFootnoteInline) {
      const next: MarkdownSpanStyle = base.clone();
      next.footnote = true;
      out.push(new MarkdownRun('[' + part.id + ']', next));
    }
  }
  return out;
}

function MarkdownParserAppend(target: MarkdownRun[], source: MarkdownRun[]): void {
  for (let i: number = 0; i < source.length; i++) {
    target.push(source[i]);
  }
}
