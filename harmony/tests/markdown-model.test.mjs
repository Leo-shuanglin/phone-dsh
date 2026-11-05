// SPDX-License-Identifier: MIT
// Focused tests for the pure ArkTS Markdown block/inline model.
//
// The cases are ported from the canonical Android tests
// (MarkdownBodyTest.kt, MarkdownCrashRegressionTest.kt), not re-derived from
// this implementation: GFM tables, display/inline TeX, literal HTML, footnotes,
// fenced code, image promotion, nested/task lists, malformed input, the link
// router, and the hostile-nesting caps.
import assert from 'node:assert/strict';
import { join } from 'node:path';
import { createRequire } from 'node:module';
import test from 'node:test';

const ROOT = new URL('..', import.meta.url).pathname;
const ENTRY = join(ROOT, 'entry/src/main/ets/model/MarkdownModel.ets');

const ESBUILD_REQUIRES = [join(ROOT, '../plugins/package.json')];

function loadEsbuild() {
  for (const marker of ESBUILD_REQUIRES) {
    try {
      return createRequire(marker)('esbuild');
    } catch (error) {
      // next candidate
    }
  }
  throw new Error('esbuild was not found; set one of the ESBUILD_REQUIRES markers');
}

async function loadModel() {
  const esbuild = loadEsbuild();
  const built = esbuild.buildSync({
    entryPoints: [ENTRY],
    absWorkingDir: ROOT,
    bundle: true,
    write: false,
    format: 'esm',
    platform: 'neutral',
    target: 'es2022',
    loader: { '.ets': 'ts' },
    resolveExtensions: ['.ets', '.ts', '.js'],
  });
  const code = built.outputFiles[0].text;
  const url = 'data:text/javascript;base64,' + Buffer.from(code).toString('base64');
  return await import(url);
}

const model = await loadModel();
const {
  MarkdownParser,
  MarkdownBlockKind,
  MarkdownInlineKind,
  MarkdownTableAlign,
  MarkdownLinkKind,
  MAX_MARKDOWN_BLOCK_DEPTH,
  markdownLinkTarget,
  parseMarkdownOrPlain,
  plainText,
  flattenInlines,
} = model;

function only(blocks, kind, label) {
  assert.equal(blocks.length, 1, label + ': expected one block, got ' + blocks.length);
  assert.equal(blocks[0].kind, kind, label + ': wrong block kind');
  return blocks[0];
}

function flatten(blocks) {
  const parts = [];
  const pending = blocks.slice();
  while (pending.length > 0) {
    const block = pending.shift();
    if (block.kind === MarkdownBlockKind.Paragraph || block.kind === MarkdownBlockKind.Heading) {
      parts.push(plainText(block.inlines));
    } else if (block.kind === MarkdownBlockKind.Table) {
      for (const cell of block.header) parts.push(plainText(cell));
      for (const row of block.rows) for (const cell of row) parts.push(plainText(cell));
    } else if (block.kind === MarkdownBlockKind.List) {
      for (const item of block.items) {
        parts.push(plainText(item.inlines));
        if (item.imageBlock !== null) pending.push(item.imageBlock);
        pending.push(...item.children);
      }
    } else if (block.kind === MarkdownBlockKind.Quote) {
      pending.push(...block.blocks);
    } else if (block.kind === MarkdownBlockKind.Code) {
      parts.push(block.body);
    } else if (block.kind === MarkdownBlockKind.Math) {
      parts.push(block.latex);
    } else if (block.kind === MarkdownBlockKind.Image) {
      parts.push(block.alt);
    }
  }
  return parts.join('\n');
}

function maxQuoteDepth(blocks) {
  let depth = 0;
  for (const block of blocks) {
    if (block.kind === MarkdownBlockKind.Quote) {
      depth = Math.max(depth, 1 + maxQuoteDepth(block.blocks));
    }
  }
  return depth;
}

function maxListDepth(blocks) {
  let depth = 0;
  for (const block of blocks) {
    if (block.kind === MarkdownBlockKind.List) {
      depth = Math.max(depth, 1 + Math.max(0, ...block.items.map((item) => maxListDepth(item.children))));
    }
  }
  return depth;
}

test('a GFM table keeps its header, rows and column alignment', () => {
  const blocks = MarkdownParser.parse(
    '| 模块 | 状态 |\n|:--|--:|\n| Markdown | 原生 |\n| TeX | $x^2$ |',
  );
  const table = only(blocks, MarkdownBlockKind.Table, 'table');
  assert.equal(table.header.length, 2);
  assert.equal(table.rows.length, 2);
  assert.equal(table.alignments[0], MarkdownTableAlign.Left);
  assert.equal(table.alignments[1], MarkdownTableAlign.Right);
  assert.equal(plainText(table.rows[0][0]), 'Markdown');
  assert.equal(plainText(table.rows[0][1]), '原生');
});

test('a display math block is one math block, multi-line included', () => {
  const single = only(MarkdownParser.parse('$$E = mc^2$$'), MarkdownBlockKind.Math, 'single');
  assert.equal(single.latex, 'E = mc^2');
  const multi = only(MarkdownParser.parse('$$\nE = mc^2\n$$'), MarkdownBlockKind.Math, 'multi');
  assert.equal(multi.latex, 'E = mc^2');
});

test('inline math inside a table cell becomes a math run', () => {
  const table = only(
    MarkdownParser.parse('| a | b |\n|--|--|\n| TeX | $x^2 + y^2 = z^2$ |'),
    MarkdownBlockKind.Table,
    'table',
  );
  const cell = table.rows[0][1];
  assert.equal(cell.length, 1);
  assert.equal(cell[0].kind, MarkdownInlineKind.Math);
  assert.equal(cell[0].latex, 'x^2 + y^2 = z^2');
});

test('raw HTML stays literal text, it is never parsed', () => {
  const paragraph = only(
    MarkdownParser.parse('原始 HTML 保持字面：<b>不会执行</b>。'),
    MarkdownBlockKind.Paragraph,
    'html',
  );
  assert.equal(plainText(paragraph.inlines), '原始 HTML 保持字面：<b>不会执行</b>。');
});

test('a footnote reference is an inline node and the section is appended', () => {
  const blocks = MarkdownParser.parse('正文。[^note]\n\n[^note]: 流式与历史恢复使用同一事件投影。');
  const paragraph = blocks[0];
  assert.equal(paragraph.kind, MarkdownBlockKind.Paragraph);
  assert.equal(plainText(paragraph.inlines.slice(1)), '[^note]');
  assert.equal(plainText(paragraph.inlines), '正文。[^note]');
  assert.equal(blocks[1].kind, MarkdownBlockKind.Paragraph);
  assert.equal(plainText(blocks[1].inlines), '脚注');
  assert.equal(plainText(blocks[2].inlines), '[note] 流式与历史恢复使用同一事件投影。');
});

test('a fenced block keeps its language and body', () => {
  const code = only(
    MarkdownParser.parse('```swift\nstruct NativeChat: View {\n    let official = true\n}\n```'),
    MarkdownBlockKind.Code,
    'fence',
  );
  assert.equal(code.language, 'swift');
  assert.equal(code.body, 'struct NativeChat: View {\n    let official = true\n}');
});

test('an unterminated fence stays prose so a stream never flashes a card', () => {
  const blocks = MarkdownParser.parse('正文\n```swift\nstruct S {');
  assert.equal(blocks.length, 1);
  assert.equal(blocks[0].kind, MarkdownBlockKind.Paragraph);
  assert.equal(plainText(blocks[0].inlines), '正文 ```swift struct S {');
});

test('a standalone image paragraph preserves source alt and title', () => {
  const image = only(
    MarkdownParser.parse('![架构图](https://example.com/diagram.png "topology")'),
    MarkdownBlockKind.Image,
    'image',
  );
  assert.equal(image.source, 'https://example.com/diagram.png');
  assert.equal(image.alt, '架构图');
  assert.equal(image.title, 'topology');
});

test('inline image alt text stays unchanged inside prose', () => {
  const paragraph = only(
    MarkdownParser.parse('See ![架构图](https://example.com/diagram.png) here.'),
    MarkdownBlockKind.Paragraph,
    'inline image',
  );
  assert.equal(plainText(paragraph.inlines), 'See 架构图 here.');
  assert.equal(paragraph.inlines[1].kind, MarkdownInlineKind.Image);
});

test('image followed by prose without a blank line stays one inline paragraph', () => {
  const paragraph = only(
    MarkdownParser.parse('![架构图](diagram.png) caption on next line'),
    MarkdownBlockKind.Paragraph,
    'image prose',
  );
  assert.equal(plainText(paragraph.inlines), '架构图 caption on next line');
});

test('an image-only list item preserves a block source while mixed item content keeps inline alt', () => {
  const standalone = only(
    MarkdownParser.parse('- ![diagram](diagram.png "list image")'),
    MarkdownBlockKind.List,
    'image list',
  );
  assert.equal(standalone.items[0].imageBlock.source, 'diagram.png');
  assert.equal(standalone.items[0].imageBlock.title, 'list image');
  assert.equal(standalone.items[0].inlines.length, 0);
  const mixed = only(
    MarkdownParser.parse('- ![diagram](diagram.png) caption'),
    MarkdownBlockKind.List,
    'mixed list',
  );
  assert.equal(mixed.items[0].imageBlock, null);
  assert.equal(plainText(mixed.items[0].inlines), 'diagram caption');
});

test('strong, delete and code spans keep their kind', () => {
  const paragraph = only(
    MarkdownParser.parse('**粗体**、~~删除线~~、`Package.swift`'),
    MarkdownBlockKind.Paragraph,
    'spans',
  );
  assert.equal(paragraph.inlines[0].kind, MarkdownInlineKind.Strong);
  assert.equal(paragraph.inlines[2].kind, MarkdownInlineKind.Delete);
  assert.equal(paragraph.inlines[4].kind, MarkdownInlineKind.Code);
  assert.equal(paragraph.inlines[4].code, 'Package.swift');
});

test('soft breaks become spaces and hard breaks remain line breaks', () => {
  const soft = only(MarkdownParser.parse('first\nsecond'), MarkdownBlockKind.Paragraph, 'soft');
  assert.equal(plainText(soft.inlines), 'first second');
  const hard = only(MarkdownParser.parse('first  \nsecond'), MarkdownBlockKind.Paragraph, 'hard');
  assert.equal(plainText(hard.inlines), 'first\nsecond');
});

test('a task list keeps its checked state', () => {
  const list = only(MarkdownParser.parse('- [x] 完成\n- [ ] 没完成'), MarkdownBlockKind.List, 'tasks');
  assert.equal(list.ordered, false);
  assert.deepEqual(list.items.map((item) => item.checked), [true, false]);
});

test('a nested list stays nested under its parent item', () => {
  const list = only(MarkdownParser.parse('- parent\n  - child'), MarkdownBlockKind.List, 'nested');
  assert.equal(plainText(list.items[0].inlines), 'parent');
  const child = only(list.items[0].children, MarkdownBlockKind.List, 'child list');
  assert.equal(plainText(child.items[0].inlines), 'child');
});

test('an ordered list keeps its start number', () => {
  const list = only(MarkdownParser.parse('3. three\n4. four'), MarkdownBlockKind.List, 'ordered');
  assert.equal(list.ordered, true);
  assert.equal(list.start, 3);
  assert.equal(list.items.length, 2);
});

test('malformed tables and unmatched inline markers fall back to literal text', () => {
  const brokenTable = only(
    MarkdownParser.parse('| a | b |\n| nope |'),
    MarkdownBlockKind.Paragraph,
    'broken table',
  );
  assert.equal(plainText(brokenTable.inlines), '| a | b | | nope |');
  const unmatched = only(MarkdownParser.parse('**open and [link]('), MarkdownBlockKind.Paragraph, 'unmatched');
  assert.equal(plainText(unmatched.inlines), '**open and [link](');
});

test('parse boundary falls back to the original text on any failure', () => {
  const source = 'target text';
  const fallback = parseMarkdownOrPlain(source, () => {
    throw new Error('forced');
  });
  assert.equal(fallback.length, 1);
  assert.equal(fallback[0].kind, MarkdownBlockKind.Paragraph);
  assert.equal(plainText(fallback[0].inlines), source);
});

test('a five thousand deep quote keeps its leaf text', () => {
  const blocks = MarkdownParser.parse('>'.repeat(5000) + 'x');
  assert.ok(flatten(blocks).includes('x'));
});

test('a three thousand level indented list keeps its deepest marker', () => {
  const source = Array.from({ length: 3000 }, (_, i) => ' '.repeat(i) + '- n' + i).join('\n');
  const blocks = MarkdownParser.parse(source);
  assert.ok(flatten(blocks).includes('n2999'));
});

test('quote nesting stops at the documented cap', () => {
  const blocks = MarkdownParser.parse('>'.repeat(MAX_MARKDOWN_BLOCK_DEPTH + 20) + 'x');
  assert.equal(maxQuoteDepth(blocks), MAX_MARKDOWN_BLOCK_DEPTH);
  assert.ok(flatten(blocks).includes('x'));
});

test('list child nesting stops at the documented cap', () => {
  const deepest = MAX_MARKDOWN_BLOCK_DEPTH + 20;
  const source = Array.from({ length: deepest + 1 }, (_, i) => ' '.repeat(i) + '- n' + i).join('\n');
  const blocks = MarkdownParser.parse(source);
  assert.ok(maxListDepth(blocks) <= MAX_MARKDOWN_BLOCK_DEPTH);
  assert.ok(flatten(blocks).includes('n' + deepest));
});

test('safe workspace links route to the host and unsafe schemes do not', () => {
  const workspace = markdownLinkTarget('docs/design/PR22.md');
  assert.equal(workspace.kind, MarkdownLinkKind.WorkspacePath);
  assert.equal(workspace.value, 'docs/design/PR22.md');
  const external = markdownLinkTarget('https://example.com');
  assert.equal(external.kind, MarkdownLinkKind.External);
  assert.equal(external.value, 'https://example.com');
  assert.equal(markdownLinkTarget('mailto:dev@example.com').kind, MarkdownLinkKind.External);
  assert.equal(markdownLinkTarget('javascript:alert(1)'), null);
  assert.equal(markdownLinkTarget('data:text/html,<b>x</b>'), null);
  assert.equal(markdownLinkTarget('../../outside.txt'), null);
  assert.equal(markdownLinkTarget('/absolute/path.md'), null);
  assert.equal(markdownLinkTarget('C:\\windows\\file.md'), null);
  assert.equal(markdownLinkTarget('docs/\u0001/file.md'), null);
});

test('native workspace resource URIs preserve their full addresses', () => {
  assert.equal(
    markdownLinkTarget('file:///dsh/workspace/docs/readme.md').value,
    'file:///dsh/workspace/docs/readme.md',
  );
  assert.equal(
    markdownLinkTarget('sandbox:docs/file%20name.md').value,
    'sandbox:docs/file%20name.md',
  );
  assert.equal(
    markdownLinkTarget('dsh-resource://file/session/session-A/dsh/workspace/docs/readme.md').value,
    'dsh-resource://file/session/session-A/dsh/workspace/docs/readme.md',
  );
  assert.equal(markdownLinkTarget('file://remote-host/docs/readme.md'), null);
  assert.equal(markdownLinkTarget('dsh-resource://other/session/turn-1/docs/readme.md'), null);
  assert.equal(markdownLinkTarget('sandbox:%2e%2e/private.txt'), null);
  assert.equal(markdownLinkTarget('dsh-resource://file/unknown/turn-1/docs/readme.md'), null);
  assert.equal(markdownLinkTarget('file:///dsh/workspace/../secret.md'), null);
});

test('flattening styles links and leaves unsafe link labels plain', () => {
  const inlines = MarkdownParser.parse('[Open file](docs/design/PR22.md) [bad](javascript:void)')[0].inlines;
  const runs = flattenInlines(inlines);
  const linked = runs.find((run) => run.style.link);
  assert.equal(linked.text, 'Open file');
  assert.equal(linked.style.target.kind, MarkdownLinkKind.WorkspacePath);
  assert.equal(runs.filter((run) => run.style.link).length, 1);
  assert.equal(runs.map((run) => run.text).join(''), 'Open file bad');
});
