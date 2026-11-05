// SPDX-License-Identifier: MIT
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const directory = resolve(dirname(fileURLToPath(import.meta.url)), '../entry/src/main/ets/model');
function moduleUrl(path) {
  let text = readFileSync(path, 'utf8');
  text = text.replace(/from\s+(['"])(\.[^'"]+)\1/g, (_all, quote, target) =>
    'from ' + quote + moduleUrl(resolve(dirname(path), target + '.ets')) + quote);
  const output = stripTypeScriptTypes(text, { mode: 'transform' });
  return 'data:text/javascript;base64,' + Buffer.from(output).toString('base64');
}

const answers = await import(moduleUrl(resolve(directory, 'QuestionAnswers.ets')));
const contracts = await import(moduleUrl(resolve(directory, 'Contracts.ets')));
const { publishSnapshot } = await import(moduleUrl(resolve(directory, 'SnapshotProjection.ets')));

function questionsFrom(rows) {
  return answers.parseQuestionItems({ questions: rows });
}

function plain(value) {
  return JSON.parse(JSON.stringify(value));
}

function withOption(questions, drafts, id, label) {
  const question = questions.find(value => value.id === id);
  return answers.replaceQuestionDraft(
    drafts,
    answers.withQuestionOption(question, answers.findQuestionDraft(drafts, id), label),
  );
}

function withCustom(questions, drafts, id, custom) {
  const question = questions.find(value => value.id === id);
  return answers.replaceQuestionDraft(
    drafts,
    answers.withQuestionCustom(question, answers.findQuestionDraft(drafts, id), custom),
  );
}

test('parses the exact DSH question fields and keeps option descriptions', () => {
  const questions = questionsFrom([
    {
      id: 'q1', question: 'Which database?', detail: 'The read path is hot.', header: 'Storage',
      multiSelect: true,
      options: [{ label: 'Postgres', description: 'SQL' }, { label: 'Redis' }],
    },
    { id: 'q2', question: 'Why?' },
  ]);
  assert.equal(questions.length, 2);
  assert.deepEqual(
    plain(questions[0]),
    {
      id: 'q1', question: 'Which database?', detail: 'The read path is hot.', header: 'Storage',
      multiSelect: true,
      options: [
        { label: 'Postgres', description: 'SQL' },
        { label: 'Redis', description: '' },
      ],
    },
  );
  assert.deepEqual(plain(questions[1].options), []);
  assert.equal(questions[1].multiSelect, false);
});

test('recommended defaults use the wire label while display strips the marker', () => {
  const questions = questionsFrom([
    {
      id: 'q1', question: 'Pick one',
      options: [{ label: 'A' }, { label: 'B (recommended)', description: 'safer' }],
    },
  ]);
  const drafts = answers.defaultQuestionDrafts(questions);
  assert.deepEqual(drafts[0].selected, ['B (recommended)']);
  assert.equal(answers.questionDisplayLabel('B (recommended)'), 'B');
  assert.equal(answers.questionDisplayLabel('B（推荐）'), 'B');
  assert.equal(answers.questionDisplayLabel('B (RECOMMENDED)'), 'B');
  assert.equal(answers.hasRecommendedSuffix('B (recommended)'), true);
  assert.equal(answers.hasRecommendedSuffix('B'), false);
});

test('distinct per-question answers encode and decode by id, never copied', () => {
  const questions = questionsFrom([
    { id: 'q1', question: 'One', options: [{ label: 'A' }, { label: 'B' }] },
    { id: 'q2', question: 'Two', options: [{ label: 'C' }, { label: 'D' }] },
  ]);
  let drafts = answers.defaultQuestionDrafts(questions);
  drafts = withOption(questions, drafts, 'q1', 'B');
  drafts = withOption(questions, drafts, 'q2', 'C');

  const answer = answers.encodeQuestionAnswers(questions, drafts);
  assert.deepEqual(JSON.parse(answer), { q1: ['B'], q2: ['C'] });
  const outcome = answers.buildQuestionOutcome(questions, answer);
  assert.deepEqual(plain(outcome.value.answers), [
    { id: 'q1', selected: ['B'] },
    { id: 'q2', selected: ['C'] },
  ]);
});

test('a multi question keeps every wire label plus separate custom text', () => {
  const questions = questionsFrom([
    {
      id: 'q1', question: 'Choose many', multiSelect: true,
      options: [{ label: 'A' }, { label: 'B' }],
    },
  ]);
  let drafts = answers.defaultQuestionDrafts(questions);
  drafts = withOption(questions, drafts, 'q1', 'A');
  drafts = withOption(questions, drafts, 'q1', 'B');
  drafts = withCustom(questions, drafts, 'q1', 'also this');

  const answer = answers.encodeQuestionAnswers(questions, drafts);
  assert.deepEqual(JSON.parse(answer), { q1: ['A', 'B', 'also this'] });
  assert.deepEqual(plain(answers.buildQuestionOutcome(questions, answer).value.answers), [
    { id: 'q1', selected: ['A', 'B'], custom: 'also this' },
  ]);
});

test('custom text replaces a single-select option but stays separate from labels', () => {
  const questions = questionsFrom([
    { id: 'q1', question: 'One', options: [{ label: 'A' }, { label: 'B' }] },
  ]);
  let drafts = answers.defaultQuestionDrafts(questions);
  drafts = withOption(questions, drafts, 'q1', 'A');
  drafts = withCustom(questions, drafts, 'q1', 'typed answer');

  const answer = answers.encodeQuestionAnswers(questions, drafts);
  assert.deepEqual(JSON.parse(answer), { q1: ['typed answer'] });
  assert.deepEqual(plain(answers.buildQuestionOutcome(questions, answer).value.answers), [
    { id: 'q1', selected: [], custom: 'typed answer' },
  ]);
});

test('an optionless question sends only its custom text', () => {
  const questions = questionsFrom([{ id: 'q1', question: 'Free form' }]);
  let drafts = answers.defaultQuestionDrafts(questions);
  drafts = withCustom(questions, drafts, 'q1', 'anything');

  const answer = answers.encodeQuestionAnswers(questions, drafts);
  assert.deepEqual(JSON.parse(answer), { q1: ['anything'] });
  assert.deepEqual(plain(answers.buildQuestionOutcome(questions, answer).value.answers), [
    { id: 'q1', selected: [], custom: 'anything' },
  ]);
});

test('malformed group JSON is refused without sending empty answers', () => {
  const questions = questionsFrom([
    { id: 'q1', question: 'One', options: [{ label: 'A' }] },
    { id: 'q2', question: 'Two', options: [{ label: 'B' }] },
  ]);
  assert.throws(() => answers.buildQuestionOutcome(questions, '{not json'));
});

test('an actual single question keeps the scalar answer path', () => {
  const questions = questionsFrom([
    { id: 'q1', question: 'One', options: [{ label: 'A' }, { label: 'B' }] },
  ]);
  assert.deepEqual(plain(answers.buildQuestionOutcome(questions, 'B').value.answers), [
    { id: 'q1', selected: ['B'] },
  ]);
  assert.deepEqual(plain(answers.buildQuestionOutcome(questions, 'free text').value.answers), [
    { id: 'q1', selected: [], custom: 'free text' },
  ]);
});

test('a map missing a sibling question is refused, while explicit skips remain valid', () => {
  const questions = questionsFrom([
    { id: 'q1', question: 'One', options: [{ label: 'A' }] },
    { id: 'q2', question: 'Two', options: [{ label: 'B' }] },
  ]);
  assert.throws(() => answers.buildQuestionOutcome(questions, JSON.stringify({ q1: ['A'] })));
  assert.deepEqual(plain(answers.buildQuestionOutcome(questions, JSON.stringify({q1:['A'],q2:[]})).value.answers),
    [{id:'q1',selected:['A']},{id:'q2',selected:[]}]);
});

test('duplicate, empty and non-string identities reject the whole request', () => {
  for (const rows of [
    [{id:'q1',question:'One'},{id:'q1',question:'Two'}],
    [{id:'',question:'Empty'}], [{question:'Missing'}], [{id:42,question:'Number'}]
  ]) assert.throws(() => questionsFrom(rows));
});

test('stale answer keys and malformed scalar containers cannot report success', () => {
  const q = questionsFrom([{id:'q1',question:'One',options:[{label:'A'}]}]);
  for (const value of ['{}','[]','{broken','{"other":["A"]}','{"q1":[42]}']) {
    assert.throws(() => answers.buildQuestionOutcome(q,value));
  }
});

test('opaque prototype-looking question identities round trip as ordinary JSON keys', () => {
  const q = questionsFrom([{id:'__proto__',question:'Opaque',options:[{label:'A'}]}]);
  const drafts = [Object.assign(new answers.QuestionDraft(), {id:'__proto__',selected:['A']})];
  const encoded = answers.encodeQuestionAnswers(q,drafts);
  assert.equal(Object.keys(JSON.parse(encoded))[0],'__proto__');
  assert.deepEqual(plain(answers.buildQuestionOutcome(q,encoded).value.answers),[{id:'__proto__',selected:['A']}]);
});

test('snapshot publication keeps the new question fields detached', () => {
  const questions = questionsFrom([
    {
      id: 'q1', question: 'One', detail: 'Details', header: 'Header',
      options: [{ label: 'A', description: 'first' }],
    },
  ]);
  const request = new contracts.PendingRequest();
  request.id = 'event-1';
  request.kind = 'question';
  request.questions = questions;
  const snapshot = new contracts.ShellSnapshot();
  snapshot.pending = [request];

  let view;
  publishSnapshot(snapshot, next => { view = next; });
  assert.equal(view.pending[0].questions[0].detail, 'Details');
  assert.equal(view.pending[0].questions[0].options[0].description, 'first');

  view.pending[0].questions[0].question = 'UI change';
  assert.equal(snapshot.pending[0].questions[0].question, 'One');
});
