// SPDX-License-Identifier: MIT
// Data-only projection and answer codec for DSH user questions. Android keeps
// one draft per question and sends `{qid: [wireLabels, custom]}` through the
// existing answerRequest string; this module owns that shape so the host and
// the UI cannot disagree about it.

export class PendingQuestionOption {
  label: string = '';
  description: string = '';
}

export class PendingQuestion {
  id: string = '';
  question: string = '';
  detail: string = '';
  header: string = '';
  options: PendingQuestionOption[] = [];
  multiSelect: boolean = false;
}

export class QuestionDraft {
  id: string = '';
  selected: string[] = [];
  custom: string = '';
  skipped: boolean = false;
}

export class QuestionAnswer {
  id: string = '';
  selected: string[] = [];
  custom?: string;
}

export class QuestionAnswers {
  answers: QuestionAnswer[] = [];
}

export class QuestionOutcome {
  kind: string = 'result';
  value: QuestionAnswers = new QuestionAnswers();
}

const RECOMMENDED_SUFFIX: RegExp =
  new RegExp('\\s*(?:\\((?:recommended|推荐)\\)|（(?:recommended|推荐)）)\\s*$', 'i');

function asRecord(value: Object | null | undefined): Record<string, Object> | null {
  if (value === null || value === undefined) {
    return null;
  }
  if (typeof value !== 'object' || Array.isArray(value)) {
    return null;
  }
  return value as Record<string, Object>;
}

function asArray(value: Object | null | undefined): Object[] {
  if (value === null || value === undefined || !Array.isArray(value)) {
    return [];
  }
  return value as Object[];
}

function textOf(value: Object | null | undefined): string {
  return typeof value === 'string' ? value as string : '';
}

function assertQuestionIds(questions: PendingQuestion[]): void {
  const ids: Set<string> = new Set<string>();
  if (questions.length === 0) {
    throw new Error('At least one question is required');
  }
  for (let index = 0; index < questions.length; index++) {
    const id = questions[index].id;
    if (id.length === 0 || ids.has(id)) {
      throw new Error('Question IDs must be unique non-empty strings');
    }
    ids.add(id);
  }
}

/** Parses one complete, unambiguous DSH question group. */
export function parseQuestionItems(body: Record<string, Object> | null): PendingQuestion[] {
  const rows: Object[] = asArray(body === null ? null : body['questions']);
  const questions: PendingQuestion[] = [];
  for (let index = 0; index < rows.length; index++) {
    const raw = asRecord(rows[index]);
    if (raw === null) {
      throw new Error('A question must be an object');
    }
    const question = new PendingQuestion();
    question.id = textOf(raw['id']);
    question.question = textOf(raw['question']);
    question.detail = textOf(raw['detail']);
    question.header = textOf(raw['header']);
    question.multiSelect = raw['multiSelect'] === true;
    const optionRows: Object[] = asArray(raw['options']);
    for (let optionIndex = 0; optionIndex < optionRows.length; optionIndex++) {
      const rawOption = asRecord(optionRows[optionIndex]);
      if (rawOption === null) {
        continue;
      }
      const label = textOf(rawOption['label']);
      if (label.length === 0) {
        continue;
      }
      const option = new PendingQuestionOption();
      option.label = label;
      option.description = textOf(rawOption['description']);
      question.options.push(option);
    }
    questions.push(question);
  }
  assertQuestionIds(questions);
  return questions;
}

/** Android strips only the trailing recommendation marker, not the wire label. */
export function questionDisplayLabel(label: string): string {
  return label.replace(RECOMMENDED_SUFFIX, '');
}

export function hasRecommendedSuffix(label: string): boolean {
  return questionDisplayLabel(label) !== label;
}

export function recommendedQuestionLabel(question: PendingQuestion): string {
  for (let index = 0; index < question.options.length; index++) {
    const label = question.options[index].label;
    if (hasRecommendedSuffix(label)) {
      return label;
    }
  }
  return '';
}

export function defaultQuestionDrafts(questions: PendingQuestion[]): QuestionDraft[] {
  const drafts: QuestionDraft[] = [];
  for (let index = 0; index < questions.length; index++) {
    const question = questions[index];
    const draft = new QuestionDraft();
    draft.id = question.id;
    const recommended = recommendedQuestionLabel(question);
    if (recommended.length > 0) {
      draft.selected = [recommended];
    }
    drafts.push(draft);
  }
  return drafts;
}

export function findQuestionDraft(drafts: QuestionDraft[], id: string): QuestionDraft {
  for (let index = 0; index < drafts.length; index++) {
    if (drafts[index].id === id) {
      return drafts[index];
    }
  }
  const draft = new QuestionDraft();
  draft.id = id;
  return draft;
}

export function replaceQuestionDraft(drafts: QuestionDraft[], next: QuestionDraft): QuestionDraft[] {
  const result: QuestionDraft[] = [];
  let replaced = false;
  for (let index = 0; index < drafts.length; index++) {
    if (drafts[index].id === next.id) {
      result.push(next);
      replaced = true;
    } else {
      result.push(drafts[index]);
    }
  }
  if (!replaced) {
    result.push(next);
  }
  return result;
}

function cloneDraft(draft: QuestionDraft): QuestionDraft {
  const next = new QuestionDraft();
  next.id = draft.id;
  next.selected = draft.selected.slice(0);
  next.custom = draft.custom;
  next.skipped = draft.skipped;
  return next;
}

export function withQuestionOption(question: PendingQuestion, draft: QuestionDraft, label: string): QuestionDraft {
  const next = cloneDraft(draft);
  if (question.multiSelect) {
    const selected: string[] = [];
    let found = false;
    for (let index = 0; index < next.selected.length; index++) {
      if (next.selected[index] === label) {
        found = true;
      } else {
        selected.push(next.selected[index]);
      }
    }
    if (!found) {
      selected.push(label);
    }
    next.selected = selected;
  } else {
    next.selected = [label];
  }
  // Android clears the custom box when an option is tapped; multi keeps any
  // custom text added after the selection and the encoder sends both.
  next.custom = '';
  next.skipped = false;
  return next;
}

export function withQuestionCustom(question: PendingQuestion, draft: QuestionDraft, custom: string): QuestionDraft {
  const next = cloneDraft(draft);
  next.custom = custom;
  if (!question.multiSelect) {
    next.selected = [];
  }
  next.skipped = false;
  return next;
}

export function withQuestionSkipped(draft: QuestionDraft): QuestionDraft {
  const next = cloneDraft(draft);
  next.selected = [];
  next.custom = '';
  next.skipped = true;
  return next;
}

export function questionDraftAnswered(draft: QuestionDraft): boolean {
  return draft.selected.length > 0 || draft.custom.trim().length > 0;
}

/** First unanswered, unskipped question, or -1 when the group may be sent. */
export function missingQuestionIndex(questions: PendingQuestion[], drafts: QuestionDraft[]): number {
  for (let index = 0; index < questions.length; index++) {
    const draft = findQuestionDraft(drafts, questions[index].id);
    if (!draft.skipped && !questionDraftAnswered(draft)) {
      return index;
    }
  }
  return -1;
}

/**
 * Android's established answer string: one object key per question, value is
 * the wire labels followed by optional custom text. A single-question custom
 * answer replaces its option; a multi question may keep both.
 */
export function encodeQuestionAnswers(questions: PendingQuestion[], drafts: QuestionDraft[]): string {
  assertQuestionIds(questions);
  const values: string[] = [];
  for (let index = 0; index < questions.length; index++) {
    const question = questions[index];
    const draft = findQuestionDraft(drafts, question.id);
    const entries: string[] = [];
    if (!draft.skipped) {
      const custom = draft.custom.trim();
      const keepOption = question.multiSelect || custom.length === 0;
      if (keepOption) {
        for (let selectedIndex = 0; selectedIndex < draft.selected.length; selectedIndex++) {
          entries.push(draft.selected[selectedIndex]);
        }
      }
      if (custom.length > 0) {
        entries.push(custom);
      }
    }
    values.push(JSON.stringify(question.id) + ':' + JSON.stringify(entries));
  }
  return '{' + values.join(',') + '}';
}

function parseAnswerMap(answer: string): Record<string, Object> | null {
  if (answer.length === 0) {
    return null;
  }
  try {
    return asRecord(JSON.parse(answer) as Object);
  } catch (error) {
    return null;
  }
}

function valuesForQuestion(perQuestion: Record<string, Object> | null, id: string, scalar: string): string[] {
  if (perQuestion === null) {
    return [scalar];
  }
  const value: Object | undefined = perQuestion[id];
  if (typeof value === 'string') {
    return [value as string];
  }
  const values: string[] = [];
  const rows: Object[] = asArray(value);
  for (let index = 0; index < rows.length; index++) {
    if (typeof rows[index] === 'string') {
      values.push(rows[index] as string);
    }
  }
  return values;
}

/**
 * Maps the per-question answer JSON onto the original DSH answer envelope.
 * A scalar answer is compatible only with an actual single-question request;
 * on a group it cannot fill every id because that fabricated answers.
 */
export function buildQuestionOutcome(questions: PendingQuestion[], answer: string): QuestionOutcome {
  assertQuestionIds(questions);
  const perQuestion = parseAnswerMap(answer);
  if (perQuestion === null) {
    const trimmed = answer.trim();
    if (questions.length !== 1 || trimmed.startsWith('{') || trimmed.startsWith('[')) {
      throw new Error('Answer must name every question');
    }
  } else {
    const keys = new Set<string>(Object.keys(perQuestion));
    if (keys.size !== questions.length) {
      throw new Error('Answer IDs do not match this question group');
    }
    for (let index = 0; index < questions.length; index++) {
      const id = questions[index].id;
      if (!keys.has(id)) {
        throw new Error('Answer IDs do not match this question group');
      }
      const value = perQuestion[id];
      if (typeof value === 'string') {
        continue;
      }
      if (!Array.isArray(value)) {
        throw new Error('Each answer must contain strings');
      }
      const entries = value as Object[];
      for (let valueIndex = 0; valueIndex < entries.length; valueIndex++) {
        if (typeof entries[valueIndex] !== 'string') {
          throw new Error('Each answer must contain strings');
        }
      }
    }
  }
  const answers: QuestionAnswer[] = [];
  for (let index = 0; index < questions.length; index++) {
    const question = questions[index];
    const labels: string[] = [];
    for (let optionIndex = 0; optionIndex < question.options.length; optionIndex++) {
      labels.push(question.options[optionIndex].label);
    }
    const values: string[] = perQuestion === null && questions.length > 1
      ? [] : valuesForQuestion(perQuestion, question.id, answer);
    const selected: string[] = [];
    const custom: string[] = [];
    for (let valueIndex = 0; valueIndex < values.length; valueIndex++) {
      if (values[valueIndex].length === 0) {
        continue;
      }
      if (labels.indexOf(values[valueIndex]) >= 0) {
        selected.push(values[valueIndex]);
      } else {
        custom.push(values[valueIndex]);
      }
    }
    const entry = new QuestionAnswer();
    entry.id = question.id;
    entry.selected = selected;
    if (custom.length > 0) {
      entry.custom = custom.join('\n');
    }
    answers.push(entry);
  }
  const outcome = new QuestionOutcome();
  outcome.value.answers = answers;
  return outcome;
}
