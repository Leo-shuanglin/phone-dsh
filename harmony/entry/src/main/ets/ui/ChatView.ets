// SPDX-License-Identifier: MIT
import { ImageLoadControl } from '../model/ImageLoadControl';
// Native chat: PR22 HomeSpec transcript, process folds, pending cards and composer.
// Local state owns only the draft mirror, open menus and fold positions.

import {
  ShellSnapshot, ShellActions, ChatRow, RowKind, Page, PendingRequest,
  EnginePhase, ImageBytes, LoadPhase
} from '../model/Contracts';
import {
  ComposerReferenceMenu,
  referenceMenuFromToken,
  referenceSelection,
  referenceToken
} from '../model/ComposerReferences';
import {
  SessionModelEffort,
  SessionModelOption,
  SessionModelSelection,
  SessionPermissionOption,
  effortLabel,
  permissionTitle
} from '../model/SessionControls';
import { ComposerCommand, commandSuggestions } from '../model/ComposerInput';
import {
  PendingQuestion,
  PendingQuestionOption,
  QuestionDraft,
  defaultQuestionDrafts,
  encodeQuestionAnswers,
  findQuestionDraft,
  hasRecommendedSuffix,
  missingQuestionIndex,
  questionDisplayLabel,
  questionDraftAnswered,
  replaceQuestionDraft,
  withQuestionCustom,
  withQuestionOption,
  withQuestionSkipped
} from '../model/QuestionAnswers';
import { Palette, Metrics, Copy } from './Palette';
import {
  clockLabel,
  cacheHitPercent,
  compactMetricTokens,
  contextPercent,
  elapsedMillis,
  formatMetricPercent,
  tokensPerSecond,
  totalTokens,
  turnProcessLabel,
  TurnOutcome
} from '../model/ChatMetrics';
import { MarkdownBody } from './MarkdownBody';
import { ChatRowBinding } from './ChatRowBinding';
import { PillButton, Tag, NoticeBanner } from './ShellSupport';
import { NativeIcon } from './NativeIcon';
import { pasteboard, BusinessError } from '@kit.BasicServicesKit';

@Component
export struct ChatView {
  @Prop @Watch('onSnapshotUpdated') snapshot: ShellSnapshot = new ShellSnapshot();
  @Prop dark: boolean = false;
  // Android typography roles in pt; 0 keeps the reviewed Metrics default.
  @Prop bodyFontSize: number = 0;
  @Prop codeFontSize: number = 0;
  @Prop terminalFontSize: number = 0;
  actions?: ShellActions;
  // Markdown image/resource wiring from the page adapter. These callbacks are
  // the only additions for native images; snapshot state stays data-only.
  loadImage?: (source: string, control?: ImageLoadControl) => Promise<ImageBytes>;
  onOpenResource: (source: string) => void = () => {};
  onOpenDrawer: () => void = () => {};
  registerBackHandler?: (handler: () => boolean) => void;
  @State draft: string = '';
  @State addOpen: boolean = false;
  // The in-place `@` chooser is local chrome: opening it never edits the draft.
  @State referenceOpen: boolean = false;
  @State modelOpen: boolean = false;
  @State permissionOpen: boolean = false;
  @State commandOpen: boolean = false;
  @State commandFromAdd: boolean = false;
  @State headerMenuOpen: boolean = false;
  @State actionError: string = '';
  @State copiedRowId: string = '';
  @State questionIndex: number = 0;
  @State questionDrafts: QuestionDraft[] = [];
  @State questionError: string = '';
  @State questionSubmittingId: string = '';
  @State expandedTurns: string[] = [];
  // One renderer-owned binding per stable row id. The observed objects live
  // outside the data-only snapshot; ForEach iterates these wrappers.
  @State rowBindings: ChatRowBinding[] = [];
  private transcriptScroller: Scroller = new Scroller();
  private followTail: boolean = true;
  private scrollTimer: number = -1;
  private lastTranscriptKey: string = '';
  private lastSessionId: string = '';
  private referenceRequested: boolean = false;
  private questionStateKey: string = '';
  private bindingById: Map<string, ChatRowBinding> = new Map<string, ChatRowBinding>();

  aboutToAppear(): void {
    this.draft = this.snapshot.draft;
    this.noteDraftChanged(this.draft);
    this.syncRowBindings();
    this.syncQuestionState();
    this.scheduleTail();
    if (this.registerBackHandler !== undefined) {
      this.registerBackHandler((): boolean => {
        if (this.addOpen || this.referenceOpen || this.modelOpen || this.permissionOpen || this.commandOpen || this.headerMenuOpen) {
          this.addOpen = false;
          this.referenceOpen = false;
          this.referenceRequested = false;
          this.modelOpen = false;
          this.permissionOpen = false;
          this.commandOpen = false;
          this.headerMenuOpen = false;
          return true;
        }
        return false;
      });
    }
  }

  aboutToDisappear(): void {
    if (this.scrollTimer >= 0) clearTimeout(this.scrollTimer);
    this.scrollTimer = -1;
  }

  private scheduleTail(): void {
    if (!this.followTail || this.scrollTimer >= 0) return;
    this.scrollTimer = setTimeout((): void => {
      this.scrollTimer = -1;
      if (this.followTail) this.transcriptScroller.scrollEdge(Edge.Bottom);
    }, 50);
  }

  /**
   * Reconcile the renderer bindings with the detached snapshot rows.
   *
   * A row keeps its binding while its stable id exists, so ForEach does not
   * need to recreate the child and the child's local expand state survives.
   * Replacing binding.row is what makes the reused @ObjectLink child draw the
   * latest status/text after a detached snapshot replacement.
   */
  private syncRowBindings(): void {
    const rows: ChatRow[] = this.snapshot.rows;
    const live: Map<string, ChatRowBinding> = new Map<string, ChatRowBinding>();
    const bindings: ChatRowBinding[] = [];
    for (let index: number = 0; index < rows.length; index++) {
      const row: ChatRow = rows[index];
      let binding: ChatRowBinding | undefined = this.bindingById.get(row.id);
      if (binding === undefined) {
        binding = new ChatRowBinding(row.id, row);
      }
      binding.update(
        row,
        this.showProcessFor(row),
        this.isTurnStart(index),
        this.turnExpanded(row.turnId),
        this.copiedRowId === row.id
      );
      live.set(row.id, binding);
      bindings.push(binding);
    }
    this.bindingById = live;
    if (!this.sameBindingOrder(bindings)) {
      this.rowBindings = bindings;
    }
  }

  /** Replacing the array is only needed when ids are added, removed or moved. */
  private sameBindingOrder(next: ChatRowBinding[]): boolean {
    if (next.length !== this.rowBindings.length) {
      return false;
    }
    for (let index: number = 0; index < next.length; index++) {
      if (next[index].id !== this.rowBindings[index].id) {
        return false;
      }
    }
    return true;
  }

  onSnapshotUpdated(): void {
    if (this.lastSessionId !== this.snapshot.activeSessionId) {
      this.lastSessionId = this.snapshot.activeSessionId;
      this.followTail = true;
      this.referenceOpen = false;
      this.referenceRequested = false;
    }
    this.syncRowBindings();
    const transcriptKey: string = this.snapshot.activeSessionId + ':' + this.snapshot.rows.length.toString()
      + ':' + this.snapshot.streamingText.length.toString() + ':' + this.snapshot.reasoningText.length.toString();
    if (transcriptKey !== this.lastTranscriptKey) {
      this.lastTranscriptKey = transcriptKey;
      this.scheduleTail();
    }
    if (this.snapshot.draft !== this.draft) {
      this.draft = this.snapshot.draft;
      this.noteDraftChanged(this.draft);
    }
    const typed = this.draft.trim();
    if (!this.commandFromAdd && (!typed.startsWith('/') || typed.indexOf(' ') >= 0 || this.snapshot.attachmentPaths.length > 0)) {
      this.commandOpen = false;
    }
    this.syncQuestionState();
  }

  private availableCommands(): ComposerCommand[] {
    return commandSuggestions(this.commandFromAdd ? '/' : this.draft, this.snapshot.commands);
  }

  private syncCommandMenu(value: string): void {
    this.commandFromAdd = false;
    const typed = value.trim();
    const open = typed.startsWith('/') && typed.indexOf(' ') < 0 &&
      this.snapshot.attachmentPaths.length === 0;
    if (open && !this.commandOpen) {
      this.commandOpen = true;
      this.run(this.requireActions().refreshCommands());
    } else if (!open) {
      this.commandOpen = false;
    }
  }

  private requireActions(): ShellActions {
    const actions: ShellActions | undefined = this.actions;
    if (actions === undefined) {
      throw new Error('ChatView requires ShellActions');
    }
    return actions;
  }

  private run(promise: Promise<void>): void {
    promise.catch((error: BusinessError) => {
      this.actionError = error.message;
    });
  }

  /**
   * A typed `@` opens the in-place chooser, exactly like Android's trigger. The
   * chooser owns no candidates until the host has read them; closing it leaves
   * the draft untouched.
   */
  private noteDraftChanged(value: string): void {
    if (referenceToken(value) !== null) {
      this.referenceOpen = true;
      this.requestReferences();
    } else {
      this.referenceOpen = false;
      this.referenceRequested = false;
    }
  }

  private requestReferences(): void {
    if (this.referenceRequested) {
      return;
    }
    const actions: ShellActions | undefined = this.actions;
    if (actions === undefined) {
      return;
    }
    this.referenceRequested = true;
    this.run(actions.refreshReferences());
  }

  /** Android `referenceMenu()`: token-matched files first, then conversations. */
  private referenceCandidates(): ComposerReferenceMenu {
    if (!this.referenceOpen) {
      return new ComposerReferenceMenu();
    }
    const token: string | null = referenceToken(this.draft);
    return referenceMenuFromToken(token === null ? '' : token,
      this.snapshot.referenceFiles, this.snapshot.referenceSessions);
  }

  /**
   * Android `ChatPage`: the choice replaces the whole draft with a trailing
   * space; conversations additionally carry the `@` prefix.
   */
  private chooseReference(value: string): void {
    // Android checks membership in the *projected* session rows, not in every
    // stored title, so an unshown session title never changes a file choice.
    const selected: string = referenceSelection(value, this.referenceCandidates().sessions);
    this.draft = selected;
    this.requireActions().setDraft(selected);
    this.referenceOpen = false;
    this.referenceRequested = false;
  }

  private pal(): Palette {
    return new Palette(this.dark);
  }

  private folderTitle(): string {
    for (let i: number = 0; i < this.snapshot.folders.length; i++) {
      if (this.snapshot.folders[i].id === this.snapshot.activeFolderId) {
        const name: string = this.snapshot.folders[i].name;
        return name.length > 0 ? name : '未命名文件夹';
      }
    }
    return this.snapshot.activeFolderId === 'default' ? '默认文件夹' : Copy.unknown;
  }

  private title(): string {
    return this.snapshot.title.length > 0 && this.snapshot.title !== '新会话' ?
      this.snapshot.title : Copy.tr('新会话');
  }

  private placeholder(): string {
    if (!this.snapshot.running) {
      return Copy.tr('发消息或创建任务，/ 调用指令，@ 文件或对话');
    }
    if (this.snapshot.settings.busyEnter === 'steer') {
      return Copy.tr('补充当前任务…');
    }
    return Copy.tr('排入下一轮…');
  }

  private currentModel(): string {
    const selection = this.snapshot.modelSelection;
    return selection.model.length > 0 ? selection.model : this.snapshot.settings.provider.model;
  }

  private currentProvider(): string {
    const selection = this.snapshot.modelSelection;
    return selection.provider.length > 0 ? selection.provider : this.snapshot.settings.provider.id;
  }

  private selectedRoute(): SessionModelOption | null {
    const selection = this.snapshot.modelSelection;
    const provider = selection.model.length > 0 ? selection.provider : this.snapshot.settings.provider.id;
    const model = selection.model.length > 0 ? selection.model : this.snapshot.settings.provider.model;
    let fallback: SessionModelOption | null = null;
    for (let index = 0; index < this.snapshot.modelOptions.length; index++) {
      const option = this.snapshot.modelOptions[index];
      if (option.model !== model) {
        continue;
      }
      if (option.provider === provider) {
        return option;
      }
      if (fallback === null) {
        fallback = option;
      }
    }
    return fallback;
  }

  private effortName(id: string): string {
    const route = this.selectedRoute();
    if (route !== null) {
      for (let index = 0; index < route.efforts.length; index++) {
        if (route.efforts[index].id === id) {
          return Copy.tr(effortLabel(id, route.efforts[index].name));
        }
      }
    }
    return Copy.tr(effortLabel(id, id));
  }

  private selectedEfforts(): SessionModelEffort[] {
    const route = this.selectedRoute();
    return route === null ? [] : route.efforts;
  }

  private currentEffort(): string {
    const selection = this.snapshot.modelSelection;
    if (selection.model.length === 0) {
      return this.snapshot.settings.provider.effort;
    }
    if (selection.effort.length > 0) {
      return this.effortName(selection.effort);
    }
    const route = this.selectedRoute();
    if (route !== null && route.defaultEffort.length > 0) {
      return this.effortName(route.defaultEffort);
    }
    return Copy.tr('默认');
  }

  private modelSelected(option: SessionModelOption): boolean {
    const selection = this.snapshot.modelSelection;
    if (selection.model.length > 0) {
      if (selection.provider.length > 0) {
        return selection.provider === option.provider && selection.model === option.model;
      }
      return selection.model === option.model;
    }
    return this.snapshot.settings.provider.id === option.provider &&
      this.snapshot.settings.provider.model === option.model;
  }

  private effortSelected(effortId: string): boolean {
    const selection = this.snapshot.modelSelection;
    if (selection.model.length > 0) {
      if (selection.effort.length > 0) {
        return selection.effort === effortId;
      }
      const route = this.selectedRoute();
      return route !== null && route.defaultEffort === effortId;
    }
    return this.snapshot.settings.provider.effort === effortId;
  }

  private permission(): string {
    const current = this.snapshot.currentPermission;
    if (current.length > 0) {
      for (let index = 0; index < this.snapshot.permissionOptions.length; index++) {
        if (this.snapshot.permissionOptions[index].id === current) {
          return this.snapshot.permissionOptions[index].title;
        }
      }
      return permissionTitle(current, current);
    }
    const fallback = this.snapshot.settings.permission;
    return fallback.length > 0 ? permissionTitle(fallback, fallback) : '未设置';
  }

  private permissionSelected(id: string): boolean {
    return this.snapshot.currentPermission === id;
  }

  private permissionItemId(id: string): string {
    if (id === 'read-only') {
      return 'chat.composer.permission.readonly';
    }
    if (id === 'workspace-write') {
      return 'chat.composer.permission.write';
    }
    if (id === 'full-access') {
      return 'chat.composer.permission.full';
    }
    return 'chat.composer.permission.' + id;
  }

  private navigate(page: Page): void {
    this.headerMenuOpen = false;
    this.requireActions().navigate(page);
  }

  private copyRow(row: ChatRow): void {
    const data: pasteboard.PasteData =
      pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, row.text);
    pasteboard.getSystemPasteboard().setData(data).then(() => {
      this.copiedRowId = row.id;
      this.syncRowBindings();
    }).catch((error: BusinessError) => {
      this.actionError = error.message;
    });
  }

  /** The summary belongs above the first stamped row of a closed turn. */
  private isTurnStart(index: number): boolean {
    if (index <= 0 || index >= this.snapshot.rows.length) {
      return true;
    }
    const row = this.snapshot.rows[index];
    if (row.turnId.length === 0) {
      return false;
    }
    const previous = this.snapshot.rows[index - 1];
    if (previous.turnId !== row.turnId) {
      return true;
    }
    // A hidden process row must not swallow the turn's summary; move it to the
    // first visible row of the same turn.
    if (!this.showProcessFor(previous)
      && (previous.kind === RowKind.Thinking || previous.kind === RowKind.Tool)) {
      return true;
    }
    return false;
  }

  /** Android folds a completed turn's process rows until the user expands it. */
  private showProcessFor(row: ChatRow): boolean {
    if (!this.snapshot.settings.showProcess) {
      return false;
    }
    if (row.turnId.length === 0 || row.turnOutcome !== TurnOutcome.Completed) {
      return true;
    }
    return this.expandedTurns.indexOf(row.turnId) >= 0;
  }

  private turnExpanded(turnId: string): boolean {
    return turnId.length > 0 && this.expandedTurns.indexOf(turnId) >= 0;
  }

  private toggleTurn(turnId: string): void {
    if (turnId.length === 0) {
      return;
    }
    const index = this.expandedTurns.indexOf(turnId);
    if (index >= 0) {
      this.expandedTurns.splice(index, 1);
    } else {
      this.expandedTurns.push(turnId);
    }
    // Process rows are reused children; refresh their binding flags explicitly.
    this.syncRowBindings();
  }

  private permissionRequests(): PendingRequest[] {
    const result: PendingRequest[] = [];
    for (let index = 0; index < this.snapshot.pending.length; index++) {
      if (!this.isQuestion(this.snapshot.pending[index])) {
        result.push(this.snapshot.pending[index]);
      }
    }
    return result;
  }

  /** One real question request owns the composer seat, like Android. */
  private questionRequest(): PendingRequest | null {
    for (let index = 0; index < this.snapshot.pending.length; index++) {
      if (this.isQuestion(this.snapshot.pending[index])) {
        return this.snapshot.pending[index];
      }
    }
    return null;
  }

  private hasQuestionPending(): boolean {
    return this.questionRequest() !== null;
  }

  private activeQuestionRequest(): PendingRequest {
    const request = this.questionRequest();
    return request === null ? new PendingRequest() : request;
  }

  private questionStateSignature(request: PendingRequest): string {
    let key = request.id;
    for (let index = 0; index < request.questions.length; index++) {
      const question = request.questions[index];
      key += '|' + question.id + ':' + question.question.length.toString()
        + ':' + question.options.length.toString() + ':' + (question.multiSelect ? '1' : '0');
      for (let optionIndex = 0; optionIndex < question.options.length; optionIndex++) {
        key += ',' + question.options[optionIndex].label.length.toString();
      }
    }
    return key;
  }

  /**
   * A new request, changed question set, dismissal, or session switch rebuilds
   * the drafts. A failed submit keeps the same key, so every draft survives.
   */
  private syncQuestionState(): void {
    const request = this.questionRequest();
    if (request === null) {
      if (this.questionStateKey.length > 0 || this.questionDrafts.length > 0) {
        this.questionStateKey = '';
        this.questionIndex = 0;
        this.questionDrafts = [];
        this.questionError = '';
        this.questionSubmittingId = '';
      }
      return;
    }
    const key = this.questionStateSignature(request);
    if (key === this.questionStateKey) {
      return;
    }
    this.questionStateKey = key;
    this.questionIndex = 0;
    this.questionDrafts = request.questions.length > 0 ? defaultQuestionDrafts(request.questions) : [];
    this.questionError = '';
    this.questionSubmittingId = '';
  }

  private currentQuestion(request: PendingRequest): PendingQuestion {
    const questions = request.questions;
    if (questions.length === 0) {
      return new PendingQuestion();
    }
    let index = this.questionIndex;
    if (index < 0) {
      index = 0;
    }
    if (index >= questions.length) {
      index = questions.length - 1;
    }
    return questions[index];
  }

  private currentQuestionDraft(request: PendingRequest): QuestionDraft {
    return findQuestionDraft(this.questionDrafts, this.currentQuestion(request).id);
  }

  private questionBusy(request: PendingRequest): boolean {
    return this.questionSubmittingId === request.id;
  }

  private questionLast(request: PendingRequest): boolean {
    return this.questionIndex >= request.questions.length - 1;
  }

  private questionGroupComplete(request: PendingRequest): boolean {
    return missingQuestionIndex(request.questions, this.questionDrafts) < 0;
  }

  private questionCanAdvance(request: PendingRequest): boolean {
    return questionDraftAnswered(this.currentQuestionDraft(request));
  }

  private questionOptionSelected(request: PendingRequest, label: string): boolean {
    return this.currentQuestionDraft(request).selected.indexOf(label) >= 0;
  }

  private saveQuestionDraft(next: QuestionDraft): void {
    this.questionDrafts = replaceQuestionDraft(this.questionDrafts, next);
    this.questionError = '';
  }

  private selectQuestionOption(request: PendingRequest, label: string): void {
    if (this.questionBusy(request)) {
      return;
    }
    this.saveQuestionDraft(withQuestionOption(this.currentQuestion(request), this.currentQuestionDraft(request), label));
  }

  private updateQuestionCustom(request: PendingRequest, value: string): void {
    if (this.questionBusy(request)) {
      return;
    }
    this.saveQuestionDraft(withQuestionCustom(this.currentQuestion(request), this.currentQuestionDraft(request), value));
  }

  private previousQuestion(): void {
    if (this.questionIndex > 0) {
      this.questionIndex = this.questionIndex - 1;
      this.questionError = '';
    }
  }

  private nextQuestion(request: PendingRequest): void {
    if (this.questionBusy(request) || this.questionLast(request)) {
      return;
    }
    this.questionIndex = this.questionIndex + 1;
    this.questionError = '';
  }

  private skipQuestion(request: PendingRequest): void {
    if (this.questionBusy(request) || request.questions.length === 0) {
      return;
    }
    const nextDrafts = replaceQuestionDraft(this.questionDrafts, withQuestionSkipped(this.currentQuestionDraft(request)));
    this.questionDrafts = nextDrafts;
    this.questionError = '';
    if (this.questionLast(request)) {
      this.sendQuestionAnswers(request, nextDrafts);
    } else {
      this.questionIndex = this.questionIndex + 1;
    }
  }

  private submitQuestion(request: PendingRequest): void {
    this.sendQuestionAnswers(request, this.questionDrafts);
  }

  private sendQuestionAnswers(request: PendingRequest, drafts: QuestionDraft[]): void {
    if (this.questionBusy(request) || request.questions.length === 0) {
      return;
    }
    const missing = missingQuestionIndex(request.questions, drafts);
    if (missing >= 0) {
      this.questionIndex = missing;
      this.questionError = Copy.tr('请先完成这道问题。');
      return;
    }
    const actions = this.requireActions();
    this.questionError = '';
    this.questionSubmittingId = request.id;
    const answer = encodeQuestionAnswers(request.questions, drafts);
    this.run(this.answerQuestion(actions, request.id, answer));
  }

  private async answerQuestion(actions: ShellActions, id: string, answer: string): Promise<void> {
    try {
      await actions.answerRequest(id, answer);
    } finally {
      if (this.questionSubmittingId === id) {
        this.questionSubmittingId = '';
      }
    }
  }

  private dismissQuestion(request: PendingRequest): void {
    if (this.questionBusy(request)) {
      return;
    }
    this.run(this.requireActions().dismissPending(request.id));
  }

  private chooseModel(option: SessionModelOption, effortId: string): void {
    this.modelOpen = false;
    const selection = new SessionModelSelection();
    selection.provider = option.provider;
    selection.model = option.model;
    selection.effort = effortId;
    this.run(this.requireActions().selectModel(selection));
  }

  private chooseEffort(effortId: string): void {
    const route = this.selectedRoute();
    if (route === null) {
      return;
    }
    this.chooseModel(route, effortId);
  }

  private selectPermission(id: string): void {
    this.permissionOpen = false;
    this.run(this.requireActions().selectPermission(id));
  }

  private isQuestion(request: PendingRequest): boolean {
    return request.kind === 'question' || request.kind === 'answer'
      || request.kind === 'input' || request.kind === 'confirm_question';
  }

  @Builder
  HeaderMenuPanel() {
    Column() {
      this.HeaderMenuItem(Copy.tr('插件与工具'), 'chat.menu.configuration', Page.Configuration, 'PUZZLE')
      this.HeaderMenuItem(Copy.tr('工作区'), 'chat.menu.workbench', Page.Workbench, 'TERMINAL')
      this.HeaderMenuItem(Copy.tr('产物'), 'chat.menu.artifacts', Page.SessionArtifacts, 'DOCUMENT')
      this.HeaderMenuItem(Copy.tr('运行状态'), 'chat.menu.runtime', Page.Runtime, 'WAVEFORM')
      Row() {
        Text(Copy.tr('导出对话记录'))
          .fontSize(15)
          .fontColor(this.pal().textPrimary)
          .layoutWeight(1)
      }
      .width('100%')
      .height(44)
      .padding({ left: 16, right: 16 })
      .id('chat.menu.export')
      .onClick(() => {
        this.headerMenuOpen = false;
        this.run(this.requireActions().exportSession());
      })
      Text('')
        .height(Metrics.hairline)
        .width('100%')
        .backgroundColor(this.pal().borderList)
      Row() {
        Text(Copy.tr('设置'))
          .fontSize(15)
          .fontColor(this.pal().textPrimary)
          .layoutWeight(1)
      }
      .width('100%')
      .height(44)
      .padding({ left: 16, right: 16 })
      .id('chat.menu.settings')
      .onClick(() => {
        this.navigate(Page.Settings);
      })
    }
    .width(220)
    .backgroundColor(this.pal().surface)
    .borderRadius(16)
    .border({ width: 0.5, color: this.pal().borderList })
    .shadow({ radius: 18, color: '#33000000', offsetY: 4 })
    .margin({ top: 64, right: 12 })
  }

  @Builder
  HeaderMenuItem(label: string, id: string, page: Page, glyph: string) {
    Row() {
      NativeIcon({ name: label, symbol: glyph, dark: this.dark, iconSize: 16, muted: true })
        .width(26)
        .height(24)
      Text(label)
        .fontSize(15)
        .fontColor(this.pal().textPrimary)
        .layoutWeight(1)
    }
    .width('100%')
    .height(44)
    .padding({ left: 12, right: 16 })
    .id(id)
    .onClick(() => {
      this.navigate(page);
    })
  }

  @Builder
  ChatHeader() {
    Row() {
      NativeIcon({ name: '打开会话列表', symbol: 'CHAT_DRAWER', dark: this.dark, iconSize: 22 })
        .width(Metrics.navButton)
        .height(Metrics.navButton)
        .borderRadius(Metrics.navButton / 2)
        .backgroundColor(this.pal().surface)
        .accessibilityText(Copy.tr('打开会话列表'))
        .id('chat.drawer.open')
        .onClick(() => {
          this.onOpenDrawer();
        })
      Column() {
        Text(this.title())
          .fontSize(Metrics.navTitleSize)
          .fontWeight(FontWeight.Bold)
          .fontColor(this.pal().textPrimary)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
        Row() {
          NativeIcon({ name: '文件夹', symbol: 'FOLDER', dark: this.dark, iconSize: 13, muted: true })
            .width(13)
            .height(13)
          Text(this.folderTitle())
            .fontSize(Metrics.navSubtitleSize)
            .fontColor(this.pal().textSubtle)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .margin({ left: 5 })
        }
        .margin({ top: 1 })
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)
      .margin({ left: 12, right: 8 })
      Row() {
        NativeIcon({ name: '连接设备', symbol: 'DEVICE_RADIO', dark: this.dark, iconSize: 19 })
          .width(40)
          .height(Metrics.navButton)
          .accessibilityText(Copy.tr('连接设备'))
          .id('chat.device')
          .onClick(() => {
            this.requireActions().navigate(Page.DeviceConnection);
          })
        NativeIcon({ name: '更多', symbol: 'CHAT_MORE', dark: this.dark, iconSize: 18 })
          .width(46)
          .height(Metrics.navButton)
          .accessibilityText(Copy.tr('更多'))
          .id('chat.more')
          .onClick(() => {
            this.headerMenuOpen = !this.headerMenuOpen;
          })
      }
      .height(Metrics.navButton)
      .padding({ left: 2, right: 2 })
      .borderRadius(Metrics.navButton / 2)
      .backgroundColor(this.pal().surface)
    }
    .width('100%')
    .padding({ left: Metrics.chromeInset, right: Metrics.chromeInset, top: Metrics.navTop + 4, bottom: 4 })
  }

  @Builder
  EngineEmpty() {
    Column() {
      Text(this.snapshot.engine === EnginePhase.Failed ? Copy.tr('引擎启动失败') : Copy.tr('引擎还没启动'))
        .fontSize(18)
        .fontWeight(FontWeight.Medium)
        .fontColor(this.pal().textPrimary)
      Text(this.snapshot.engineText.length > 0 ? this.snapshot.engineText : Copy.tr('启动之后就能开始。'))
        .fontSize(Metrics.secondarySize)
        .fontColor(this.pal().textSecondary)
        .textAlign(TextAlign.Center)
        .lineHeight(20)
        .margin({ top: 6 })
      Row() {
        PillButton({
          label: this.snapshot.engine === EnginePhase.Failed ? Copy.tr('重新启动引擎') : Copy.tr('启动本地引擎'),
          dark: this.dark,
          buttonId: 'chat.empty.startEngine',
          onTap: () => {
            this.run(this.requireActions().startEngine());
          }
        })
      }
      .margin({ top: 16 })
    }
    .width('100%')
    .padding({ top: 56, bottom: 40, left: 32, right: 32 })
    .alignItems(HorizontalAlign.Center)
  }

  @Builder
  ChatEmpty() {
    if (this.snapshot.engine === EnginePhase.Booting || this.snapshot.engine === EnginePhase.Recovering) {
      Column() {
        LoadingProgress()
          .width(24)
          .height(24)
          .color(this.pal().textSecondary)
        Text(Copy.tr('等待引擎连接…'))
          .fontSize(Metrics.bodySize)
          .fontColor(this.pal().textPrimary)
          .margin({ top: 10 })
        Text(Copy.tr('引擎就绪后这里显示对话'))
          .fontSize(Metrics.secondarySize)
          .fontColor(this.pal().textSubtle)
          .margin({ top: 4 })
      }
      .width('100%')
      .padding({ top: 56, bottom: 40 })
      .alignItems(HorizontalAlign.Center)
    } else if (this.snapshot.engine === EnginePhase.Stopped || this.snapshot.engine === EnginePhase.Failed) {
      this.EngineEmpty()
    } else if (!this.snapshot.settings.provider.keyConfigured) {
      Column() {
        Text(Copy.tr('还差一步:配好模型服务'))
          .fontSize(18)
          .fontWeight(FontWeight.Medium)
          .fontColor(this.pal().textPrimary)
          .textAlign(TextAlign.Center)
        Text(Copy.tr('选一个模型服务，就能开始。'))
          .fontSize(Metrics.secondarySize)
          .fontColor(this.pal().textSecondary)
          .margin({ top: 6 })
        Row() {
          PillButton({
            label: Copy.tr('去配置模型服务'),
            dark: this.dark,
            buttonId: 'chat.empty.provider',
            onTap: () => {
              this.navigate(Page.ModelProvider);
            }
          })
        }
        .margin({ top: 16 })
      }
      .width('100%')
      .padding({ top: 56, bottom: 40, left: 32, right: 32 })
      .alignItems(HorizontalAlign.Center)
    } else {
      Column() {
        Text(Copy.tr('还没有消息'))
          .fontSize(18)
          .fontWeight(FontWeight.Medium)
          .fontColor(this.pal().textPrimary)
        Text(Copy.tr('发一条消息，开始这个会话。'))
          .fontSize(Metrics.secondarySize)
          .fontColor(this.pal().textSubtle)
          .margin({ top: 6 })
      }
      .width('100%')
      .padding({ top: 56, bottom: 40 })
      .alignItems(HorizontalAlign.Center)
    }
  }

  @Builder
  StatusArea() {
    if (this.snapshot.failure.length > 0 && this.actionError !== this.snapshot.failure
      && this.snapshot.engine === EnginePhase.Ready) {
      Row() {
        NoticeBanner({ text: this.snapshot.failure, dark: this.dark, kind: 'error',
          showClose: true, bannerId: 'chat.failure',
          onClose: (): void => { this.requireActions().clearNotice(); } })
      }
      .padding({ left: Metrics.contentInset, right: Metrics.contentInset })
    }
    if (this.snapshot.notice.length > 0) {
      Row() {
        NoticeBanner({ text: this.snapshot.notice, dark: this.dark, kind: 'info',
          showClose: true, bannerId: 'chat.notice',
          onClose: (): void => { this.requireActions().clearNotice(); } })
      }
      .padding({ left: Metrics.contentInset, right: Metrics.contentInset })
    }
  }

  @Builder
  Transcript() {
    Scroll(this.transcriptScroller) {
      Column() {
        if (this.snapshot.rows.length === 0 && this.snapshot.streamingText.length === 0
          && this.snapshot.reasoningText.length === 0 && !this.snapshot.running) {
          this.ChatEmpty()
        } else {
          ForEach(this.rowBindings, (binding: ChatRowBinding) => {
            ChatRowView({
              binding: binding,
              dark: this.dark,
              bodyFontSize: this.bodyFontSize,
              codeFontSize: this.codeFontSize,
              loadImage: this.loadImage,
              onOpenResource: this.onOpenResource,
              onToggleTurn: (turnId: string) => {
                this.toggleTurn(turnId);
              },
              onCopy: (target: ChatRow) => {
                this.copyRow(target);
              },
              onOpenPath: (path: string) => {
                if (path.length > 0) {
                  this.run(this.requireActions().openFile(path));
                }
              }
            })
          }, (binding: ChatRowBinding) => binding.id)
          if (this.snapshot.reasoningText.length > 0) {
            this.ReasoningStream()
          }
          if (this.snapshot.streamingText.length > 0) {
            this.AssistantStream()
          }
          if (this.snapshot.running && this.snapshot.streamingText.length === 0) {
            Row() {
              LoadingProgress()
                .width(18)
                .height(18)
                .color(this.pal().textSecondary)
              Text(this.snapshot.stopping ? Copy.tr('正在停止') : Copy.tr('正在执行'))
                .fontSize(Metrics.processSize)
                .fontColor(this.pal().textSecondary)
                .margin({ left: 8 })
            }
            .margin({ top: 16 })
          }
        }
      }
      .width('100%')
      .padding({ left: Metrics.contentInset, right: Metrics.contentInset, top: 8, bottom: 16 })
      .alignItems(HorizontalAlign.Start)
    }
    .layoutWeight(1)
    .width('100%')
    .scrollBar(BarState.Off)
    .edgeEffect(EdgeEffect.Spring)
    .align(this.snapshot.rows.length === 0 ? Alignment.Center : Alignment.TopStart)
    .onTouch((event: TouchEvent): void => {
      if (event.type === TouchType.Down) this.followTail = false;
    })
    .onScrollStop((): void => { this.followTail = this.transcriptScroller.isAtEnd(); })
  }

  @Builder
  ReasoningStream() {
    Column() {
      Row() {
        Text(Copy.tr('思考'))
          .fontSize(Metrics.processSize)
          .fontColor(this.pal().textSecondary)
        Text(Copy.tr('运行中'))
          .fontSize(Metrics.captionSize)
          .fontColor(this.pal().sessionSuccess)
          .margin({ left: 8 })
      }
      .width('100%')
      .height(Metrics.processRowHeight)
      Text(this.snapshot.reasoningText)
        .fontSize(Metrics.listItemSize)
        .fontColor(this.pal().textTertiary)
        .lineHeight(Metrics.listItemLine)
        .width('100%')
        .margin({ top: 4 })
    }
    .width('100%')
    .margin({ top: 12 })
  }

  @Builder
  AssistantStream() {
    Column() {
      Row() {
        Text('DSH')
          .fontSize(Metrics.captionSize)
          .fontColor(this.pal().textSecondary)
        Blank().width(8)
        Tag({ text: Copy.tr('正在执行'), dark: this.dark, tone: 'success' })
      }
      .width('100%')
      .margin({ bottom: 4 })
      MarkdownBody({
        markdown: this.snapshot.streamingText,
        dark: this.dark,
        bodyFontSize: this.bodyFontSize,
        codeFontSize: this.codeFontSize,
        loadImage: this.loadImage,
        onOpenResource: this.onOpenResource,
        onOpenPath: (path: string) => {
          if (path.length > 0) {
            this.run(this.requireActions().openFile(path));
          }
        }
      })
    }
    .width('100%')
    .margin({ top: 16 })
  }

  @Builder
  QuestionDock() {
    this.QuestionTakeover(this.activeQuestionRequest())
  }

  @Builder
  QuestionTakeover(request: PendingRequest) {
    Column() {
      Column() {
        Row() {
          Column() {
            if (this.currentQuestion(request).header.length > 0) {
              Text(this.currentQuestion(request).header)
                .fontSize(Metrics.captionSize)
                .fontColor(this.pal().textTertiary)
                .width('100%')
            }
            Text(this.currentQuestion(request).question.length > 0
              ? this.currentQuestion(request).question
              : (request.title.length > 0 ? request.title : Copy.tr('需要你的回答')))
              .fontSize(Metrics.pageTitleSize)
              .fontWeight(FontWeight.Medium)
              .fontColor(this.pal().textPrimary)
              .width('100%')
              .margin({ top: 2 })
          }
          .layoutWeight(1)
          .alignItems(HorizontalAlign.Start)
          NativeIcon({ name: '放弃整组问题', symbol: 'CLOSE', dark: this.dark, iconSize: 18 })
            .width(36)
            .height(36)
            .accessibilityText(Copy.tr('放弃整组问题'))
            .opacity(this.questionBusy(request) ? 0.4 : 1)
            .id('chat.question.' + request.id + '.dismiss')
            .onClick(() => {
              this.dismissQuestion(request);
            })
        }
        .width('100%')
        .alignItems(VerticalAlign.Top)

        if (request.questions.length > 0) {
          Scroll() {
            Column() {
              if (this.currentQuestion(request).detail.length > 0) {
                MarkdownBody({
                  markdown: this.currentQuestion(request).detail,
                  dark: this.dark,
                  bodyFontSize: this.bodyFontSize,
                  codeFontSize: this.codeFontSize,
                  loadImage: this.loadImage,
                  onOpenResource: this.onOpenResource,
                  onOpenPath: (path: string) => {
                    if (path.length > 0) {
                      this.run(this.requireActions().openFile(path));
                    }
                  }
                })
                  .width('100%')
                  .margin({ top: 4, bottom: 4 })
              }
              if (this.currentQuestion(request).options.length > 0) {
                Column() {
                  ForEach(this.currentQuestion(request).options, (option: PendingQuestionOption, index: number) => {
                    this.QuestionOptionRow(request, option, index)
                  }, (option: PendingQuestionOption, index: number) => option.label + ':' + index.toString()
                    + ':' + (this.questionOptionSelected(request, option.label) ? '1' : '0')
                    + ':' + (this.questionBusy(request) ? '1' : '0'))
                }
                .width('100%')
                .margin({ top: 6 })
              }
              Row() {
                if (this.currentQuestion(request).options.length > 0) {
                  NativeIcon({ name: '自定义回答', symbol: 'PLUS', dark: this.dark, iconSize: 16, muted: true })
                    .width(22)
                    .height(22)
                }
                TextInput({
                  text: this.currentQuestionDraft(request).custom,
                  placeholder: this.currentQuestion(request).options.length === 0
                    ? Copy.tr('输入你的答案') : Copy.tr('自定义回答')
                })
                  .fontSize(Metrics.processSize)
                  .fontColor(this.pal().textPrimary)
                  .placeholderColor(this.pal().inputPlaceholder)
                  .height(44)
                  .layoutWeight(1)
                  .backgroundColor('#00000000')
                  .padding({ left: 4, right: 4 })
                  .id('chat.question.' + request.id + '.custom')
                  .onChange((value: string) => {
                    this.updateQuestionCustom(request, value);
                  })
              }
              .width('100%')
              .constraintSize({ minHeight: 44 })
              .margin({ top: 4 })
              if (this.questionError.length > 0) {
                Text(this.questionError)
                  .fontSize(Metrics.captionSize)
                  .fontColor(this.pal().pageWarning)
                  .width('100%')
                  .margin({ top: 4 })
              }
            }
            .width('100%')
          }
          .width('100%')
          .constraintSize({ maxHeight: 300 })
          .scrollBar(BarState.Off)

          Row() {
            NativeIcon({ name: '上一题', symbol: 'CHEVRON_LEFT', dark: this.dark, iconSize: 18 })
              .width(36)
              .height(36)
              .opacity(this.questionIndex > 0 && !this.questionBusy(request) ? 1 : 0.35)
              .accessibilityText(Copy.tr('上一题'))
              .id('chat.question.' + request.id + '.previous')
              .onClick(() => {
                if (!this.questionBusy(request)) {
                  this.previousQuestion();
                }
              })
            Text((this.questionIndex + 1).toString() + ' / ' + request.questions.length.toString())
              .fontSize(Metrics.secondarySize)
              .fontColor(this.pal().textSecondary)
              .margin({ left: 4, right: 4 })
            NativeIcon({ name: '下一题', symbol: 'CHEVRON_RIGHT', dark: this.dark, iconSize: 18 })
              .width(36)
              .height(36)
              .opacity(!this.questionLast(request) && !this.questionBusy(request) ? 1 : 0.35)
              .accessibilityText(Copy.tr('下一题'))
              .id('chat.question.' + request.id + '.next')
              .onClick(() => {
                this.nextQuestion(request);
              })
            Blank().layoutWeight(1)
          }
          .width('100%')
          .margin({ top: 2 })

          Row() {
            Text(Copy.tr('跳过'))
              .fontSize(Metrics.processSize)
              .fontColor(this.questionBusy(request) ? this.pal().textTertiary : this.pal().textSecondary)
              .padding({ left: 6, right: 6, top: 10, bottom: 10 })
              .id('chat.question.' + request.id + '.skip')
              .onClick(() => {
                this.skipQuestion(request);
              })
            Blank().layoutWeight(1)
            PillButton({
              label: this.questionBusy(request) ? Copy.tr('正在提交…')
                : (this.questionLast(request) ? Copy.tr('提交回答') : Copy.tr('下一题')),
              dark: this.dark,
              compact: true,
              disabled: this.questionBusy(request) || (this.questionLast(request)
                ? !this.questionGroupComplete(request) : !this.questionCanAdvance(request)),
              buttonId: 'chat.question.' + request.id + '.submit',
              onTap: () => {
                if (this.questionLast(request)) {
                  this.submitQuestion(request);
                } else {
                  this.nextQuestion(request);
                }
              }
            })
          }
          .width('100%')
          .alignItems(VerticalAlign.Center)
          .margin({ top: 4 })
        } else {
          Text(Copy.tr('这个问题没有可回答的选项。'))
            .fontSize(Metrics.processSize)
            .fontColor(this.pal().textTertiary)
            .width('100%')
            .margin({ top: 8, bottom: 8 })
        }
      }
      .width('100%')
      .padding({ left: 14, right: 14, top: 12, bottom: 10 })
      .backgroundColor(this.pal().surface)
      .borderRadius(Metrics.composerRadius)
      .border({ width: Metrics.hairline, color: this.pal().borderList })
    }
    .width('100%')
    .padding({ left: Metrics.chromeInset, right: Metrics.chromeInset, bottom: 8 })
  }

  @Builder
  QuestionOptionRow(request: PendingRequest, option: PendingQuestionOption, index: number) {
    Row() {
      if (this.currentQuestion(request).multiSelect) {
        Stack() {
          if (this.questionOptionSelected(request, option.label)) {
            NativeIcon({ name: '已选中', symbol: 'CHECK', dark: this.dark, iconSize: 14 })
              .width(14)
              .height(14)
          }
        }
        .width(18)
        .height(18)
        .borderRadius(4)
        .border({ width: Metrics.hairline, color: this.pal().borderList })
      } else {
        Stack() {
          Text((index + 1).toString())
            .fontSize(11)
            .fontColor(this.pal().textSecondary)
        }
        .width(22)
        .height(22)
        .borderRadius(11)
        .backgroundColor(this.pal().sessionSelector)
      }
      Column() {
        Text(questionDisplayLabel(option.label))
          .fontSize(Metrics.processSize)
          .fontColor(this.pal().textPrimary)
          .width('100%')
        if (option.description.length > 0) {
          Text(option.description)
            .fontSize(Metrics.secondarySize)
            .fontColor(this.pal().textTertiary)
            .width('100%')
            .margin({ top: 2 })
        }
        if (hasRecommendedSuffix(option.label)) {
          Text(Copy.tr('推荐'))
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().sessionAccent)
            .margin({ top: 2 })
        }
      }
      .layoutWeight(1)
      .alignItems(HorizontalAlign.Start)
      .margin({ left: 10 })
    }
    .width('100%')
    .constraintSize({ minHeight: 44 })
    .padding({ left: 8, right: 8, top: 6, bottom: 6 })
    .borderRadius(10)
    .backgroundColor(this.questionOptionSelected(request, option.label) && !this.currentQuestion(request).multiSelect
      ? this.pal().sessionSelector : '#00000000')
    .opacity(this.questionBusy(request) ? 0.6 : 1)
    .id('chat.question.' + request.id + '.option.' + index.toString())
    .onClick(() => {
      this.selectQuestionOption(request, option.label);
    })
  }

  @Builder
  PendingArea() {
    if (this.permissionRequests().length > 0) {
      Column() {
        ForEach(this.permissionRequests(), (request: PendingRequest) => {
          this.PendingCard(request)
        }, (request: PendingRequest) => request.id)
        if (this.permissionRequests().length > 1) {
          Text(Copy.fill('还有 {0} 个待确认', [(this.permissionRequests().length - 1).toString()]))
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().textSubtle)
            .margin({ top: 6, left: 2 })
        }
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 8 })
    }
  }

  @Builder
  PendingCard(request: PendingRequest) {
    Column() {
      Row() {
        Text(request.title.length > 0 ? request.title : Copy.tr('需要授权'))
          .fontSize(Metrics.pageTitleSize)
          .fontWeight(FontWeight.Medium)
          .fontColor(this.pal().sessionPrimary)
          .layoutWeight(1)
        Tag({ text: Copy.tr('授权'), dark: this.dark, tone: 'warning' })
      }
      .width('100%')
      if (request.detail.length > 0) {
        Text(request.detail)
          .fontSize(Metrics.processSize)
          .fontColor(this.pal().sessionSecondary)
          .lineHeight(21)
          .width('100%')
          .margin({ top: 6 })
      }
      Row() {
          if (request.choices.length >= 2) {
            PillButton({
              label: request.choices[0],
              dark: this.dark,
              compact: true,
              buttonId: 'chat.pending.' + request.id + '.allow',
              onTap: () => {
                this.run(this.requireActions().answerRequest(request.id, request.choices[0]));
              }
            })
            Blank().width(8)
            PillButton({
              label: request.choices[1],
              dark: this.dark,
              compact: true,
              kind: 'secondary',
              buttonId: 'chat.pending.' + request.id + '.deny',
              onTap: () => {
                this.run(this.requireActions().answerRequest(request.id, request.choices[1]));
              }
            })
          } else if (request.choices.length === 1) {
            PillButton({
              label: request.choices[0],
              dark: this.dark,
              compact: true,
              buttonId: 'chat.pending.' + request.id + '.choice0',
              onTap: () => {
                this.run(this.requireActions().answerRequest(request.id, request.choices[0]));
              }
            })
          } else {
            PillButton({
              label: Copy.tr('允许一次'),
              dark: this.dark,
              compact: true,
              buttonId: 'chat.pending.' + request.id + '.allow',
              onTap: () => {
                this.run(this.requireActions().answerRequest(request.id, '允许一次'));
              }
            })
            Blank().width(8)
            PillButton({
              label: Copy.tr('拒绝'),
              dark: this.dark,
              compact: true,
              kind: 'secondary',
              buttonId: 'chat.pending.' + request.id + '.deny',
              onTap: () => {
                this.run(this.requireActions().answerRequest(request.id, '拒绝'));
              }
            })
          }
        }
        .width('100%')
        .justifyContent(FlexAlign.End)
        .margin({ top: 12 })
    }
    .width('100%')
    .padding(Metrics.cardPadding)
    .backgroundColor(this.pal().sessionSelector)
    .borderRadius(Metrics.cardRadius)
  }

  @Builder
  AddMenu() {
    Column() {
      this.MenuAction(Copy.tr('调用指令'), 'chat.composer.add.command', 'command')
      this.MenuAction(Copy.tr('引用文件或对话'), 'chat.composer.add.reference', 'reference')
      this.MenuAction(Copy.tr('添加图片'), 'chat.composer.add.image', 'image')
      this.MenuAction(Copy.tr('添加文件（写入工作区）'), 'chat.composer.add.file', 'file')
    }
    .width('100%')
    .backgroundColor(this.pal().surface)
    .borderRadius(16)
    .border({ width: 0.5, color: this.pal().borderList })
    .padding({ top: 4, bottom: 4 })
    .margin({ bottom: 8 })
  }

  @Builder
  MenuAction(label: string, id: string, kind: string) {
    Row() {
      Text(label)
        .fontSize(Metrics.processSize)
        .fontColor(this.pal().textPrimary)
        .layoutWeight(1)
    }
    .width('100%')
    .height(42)
    .padding({ left: 16, right: 16 })
    .id(id)
    .onClick(() => {
      if (kind === 'command') {
        this.commandFromAdd = true;
        this.addOpen = false;
        this.commandOpen = true;
        this.run(this.requireActions().refreshCommands());
      } else if (kind === 'reference') {
        // Android opens the in-place `@` chooser instead of navigating away.
        // The draft stays untouched until the person picks a row.
        this.addOpen = false;
        this.referenceOpen = true;
        this.requestReferences();
      } else if (kind === 'image') {
        this.addOpen = false;
        this.run(this.requireActions().pickFiles(true));
      } else if (kind === 'file') {
        this.addOpen = false;
        this.run(this.requireActions().pickFiles(false));
      } else if (kind === 'modelConfig') {
        this.modelOpen = false;
        this.requireActions().navigate(Page.ModelProvider);
      }
    })
  }

  @Builder
  CommandMenu() {
    Column() {
      Row() {
        Text('已注册指令')
          .fontSize(Metrics.captionSize)
          .fontColor(this.pal().textSubtle)
          .layoutWeight(1)
        Text(this.availableCommands().length.toString() + ' 条')
          .fontSize(Metrics.captionSize)
          .fontColor(this.pal().textSecondary)
      }
      .width('100%')
      .height(34)
      .padding({ left: 16, right: 16 })
      if (this.availableCommands().length === 0) {
        Text(this.snapshot.commandError.length > 0 ? this.snapshot.commandError : '指令目录为空或尚未读取')
          .fontSize(Metrics.captionSize)
          .fontColor(this.pal().textSubtle)
          .width('100%')
          .padding({ left: 16, right: 16, top: 6, bottom: 10 })
          .id('chat.composer.command.error')
      } else {
        Scroll() {
          Column() {
            ForEach(this.availableCommands(), (command: ComposerCommand) => {
              this.CommandAction(command)
            }, (command: ComposerCommand) => command.name)
          }
          .width('100%')
        }
        .width('100%')
        .constraintSize({ maxHeight: 260 })
        .scrollBar(BarState.Off)
      }
      if (this.availableCommands().length > 0 && this.snapshot.commandError.length > 0) {
        Text(this.snapshot.commandError)
          .fontSize(Metrics.captionSize)
          .fontColor(this.pal().textSubtle)
          .width('100%')
          .padding({ left: 16, right: 16, bottom: 6 })
      }
    }
    .width('100%')
    .backgroundColor(this.pal().surface)
    .borderRadius(16)
    .border({ width: 0.5, color: this.pal().borderList })
    .margin({ bottom: 8 })
  }

  @Builder
  CommandAction(command: ComposerCommand) {
    Row() {
      Column() {
        Text('/' + command.name)
          .fontSize(Metrics.processSize)
          .fontColor(this.pal().textPrimary)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
        Text(command.description)
          .fontSize(Metrics.captionSize)
          .fontColor(this.pal().textSubtle)
          .maxLines(2)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .margin({ top: 1 })
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)
      if (command.hasInput) {
        Text('需输入')
          .fontSize(Metrics.captionSize)
          .fontColor(this.pal().textSubtle)
          .margin({ left: 8 })
      }
    }
    .width('100%')
    .constraintSize({ minHeight: 48 })
    .padding({ left: 16, right: 16, top: 6, bottom: 6 })
    .id('chat.composer.command.option.' + command.name)
    .onClick(() => {
      const next = command.hasInput ? '/' + command.name + ' ' : '/' + command.name;
      this.draft = next;
      this.requireActions().setDraft(next);
      this.commandOpen = false;
      this.commandFromAdd = false;
    })
  }

  @Builder
  ReferenceMenu() {
    Column() {
      if (this.snapshot.referencesPhase === LoadPhase.Loading) {
        Text(Copy.tr('正在读取引用候选…'))
          .fontSize(Metrics.captionSize)
          .fontColor(this.pal().textSecondary)
          .width('100%')
          .padding({ left: 16, right: 16, top: 10, bottom: 10 })
      }
      if (this.snapshot.referencesPhase === LoadPhase.Failed) {
        Text(this.snapshot.referencesError.length > 0 ?
          this.snapshot.referencesError : Copy.tr('引用候选读取失败'))
          .fontSize(Metrics.captionSize)
          .fontColor(this.pal().sessionDanger)
          .width('100%')
          .padding({ left: 16, right: 16, top: 10, bottom: 6 })
          .id('chat.composer.reference.error')
      }
      if (this.referenceCandidates().files.length === 0 &&
        this.referenceCandidates().sessions.length === 0) {
        if (this.snapshot.referencesPhase !== LoadPhase.Loading) {
          Text(Copy.tr('没有匹配的文件或对话'))
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().textSecondary)
            .width('100%')
            .padding({ left: 16, right: 16, top: 10, bottom: 10 })
            .id('chat.composer.reference.empty')
        }
      } else {
        Scroll() {
          Column() {
            // Files are values (workspace paths), so the row container owns the
            // click and the text only draws the raw path, like Android.
            ForEach(this.referenceCandidates().files, (path: string, index: number) => {
              this.ReferenceRow(path, false, index)
            }, (path: string) => 'file:' + path)
            ForEach(this.referenceCandidates().sessions, (title: string, index: number) => {
              this.ReferenceRow(title, true, index)
            }, (title: string) => 'session:' + title)
          }
          .width('100%')
        }
        .width('100%')
        .constraintSize({ maxHeight: 280 })
        .scrollBar(BarState.Off)
      }
    }
    .width('100%')
    .backgroundColor(this.pal().surface)
    .borderRadius(16)
    .border({ width: 0.5, color: this.pal().borderList })
    .padding({ top: 4, bottom: 4 })
    .margin({ bottom: 8 })
    .id('chat.composer.reference.menu')
  }

  @Builder
  ReferenceRow(value: string, session: boolean, index: number) {
    Row() {
      Text(value)
        .fontSize(Metrics.processSize)
        .fontColor(this.pal().textPrimary)
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .layoutWeight(1)
    }
    .width('100%')
    .height(44)
    .padding({ left: 16, right: 16 })
    .accessibilityText(session ? '@' + value : value)
    .id((session ? 'chat.composer.reference.session.' : 'chat.composer.reference.file.') +
      index.toString())
    .onClick(() => {
      this.chooseReference(value);
    })
  }

  @Builder
  ModelMenu() {
    Column() {
      Row() {
        Text(Copy.tr('本会话模型'))
          .fontSize(Metrics.captionSize)
          .fontColor(this.pal().textSubtle)
          .layoutWeight(1)
        Text(this.currentProvider())
          .fontSize(Metrics.captionSize)
          .fontColor(this.pal().textSecondary)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
      }
      .width('100%')
      .height(34)
      .padding({ left: 16, right: 16 })
      Row() {
        Text(this.currentModel())
          .fontSize(Metrics.processSize)
          .fontColor(this.pal().textPrimary)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .layoutWeight(1)
          .id('chat.composer.model.current')
        Text(this.currentEffort())
          .fontSize(Metrics.captionSize)
          .fontColor(this.pal().textSecondary)
          .id('chat.composer.effort.current')
      }
      .width('100%')
      .height(34)
      .padding({ left: 16, right: 16 })
      Scroll() {
        Column() {
          if (this.snapshot.modelOptions.length === 0) {
            Text(this.snapshot.modelError.length > 0 ? this.snapshot.modelError : Copy.tr('模型目录为空或尚未读取'))
              .fontSize(Metrics.captionSize)
              .fontColor(this.pal().textSubtle)
              .width('100%')
              .padding({ left: 16, right: 16, top: 8, bottom: 8 })
              .id('chat.composer.model.error')
          } else {
            ForEach(this.snapshot.modelOptions, (option: SessionModelOption) => {
              this.ModelAction(option)
            }, (option: SessionModelOption) => option.provider + '/' + option.model)
          }
          if (this.snapshot.modelOptions.length > 0 && this.snapshot.modelError.length > 0) {
            Text(this.snapshot.modelError)
              .fontSize(Metrics.captionSize)
              .fontColor(this.pal().textSubtle)
              .width('100%')
              .padding({ left: 16, right: 16, bottom: 6 })
          }
          if (this.selectedEfforts().length > 0) {
            Text(Copy.tr('推理强度'))
              .fontSize(Metrics.captionSize)
              .fontColor(this.pal().textSubtle)
              .width('100%')
              .padding({ left: 16, right: 16, top: 6, bottom: 2 })
            ForEach(this.selectedEfforts(), (effort: SessionModelEffort) => {
              this.EffortAction(effort)
            }, (effort: SessionModelEffort) => effort.id)
          }
        }
        .width('100%')
      }
      .width('100%')
      .constraintSize({ maxHeight: 300 })
      .scrollBar(BarState.Off)
      this.MenuAction(Copy.tr('打开模型服务设置'), 'chat.composer.model.config', 'modelConfig')
    }
    .width('100%')
    .backgroundColor(this.pal().surface)
    .borderRadius(16)
    .border({ width: 0.5, color: this.pal().borderList })
    .margin({ bottom: 8 })
  }

  @Builder
  ModelAction(option: SessionModelOption) {
    Row() {
      Column() {
        Text(option.model)
          .fontSize(Metrics.processSize)
          .fontColor(this.pal().textPrimary)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
        Row() {
          Text(option.provider)
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().textSubtle)
          if (option.efforts.length > 0) {
            Text(' · ' + Copy.fill('{0} 档强度', [option.efforts.length.toString()]))
              .fontSize(Metrics.captionSize)
              .fontColor(this.pal().textSubtle)
          }
        }
        .margin({ top: 1 })
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)
      if (this.modelSelected(option)) {
        NativeIcon({ name: '已选', symbol: 'CHECK', dark: this.dark, iconSize: 15 })
          .width(15)
          .height(15)
      }
    }
    .width('100%')
    .height(52)
    .padding({ left: 16, right: 16 })
    .id('chat.composer.model.option.' + option.provider + '.' + option.model)
    .onClick(() => {
      this.chooseModel(option, '');
    })
  }

  @Builder
  EffortAction(effort: SessionModelEffort) {
    Row() {
      Text(effort.name)
        .fontSize(Metrics.processSize)
        .fontColor(this.pal().textPrimary)
        .layoutWeight(1)
      if (this.effortSelected(effort.id)) {
        NativeIcon({ name: '已选', symbol: 'CHECK', dark: this.dark, iconSize: 15 })
          .width(15)
          .height(15)
      }
    }
    .width('100%')
    .height(42)
    .padding({ left: 16, right: 16 })
    .id('chat.composer.effort.option.' + effort.id)
    .onClick(() => {
      this.chooseEffort(effort.id);
    })
  }

  @Builder
  PermissionMenu() {
    Column() {
      Row() {
        Text(Copy.tr('本会话权限'))
          .fontSize(Metrics.captionSize)
          .fontColor(this.pal().textSubtle)
          .layoutWeight(1)
        Text(this.permission())
          .fontSize(Metrics.processSize)
          .fontColor(this.pal().textPrimary)
          .id('chat.composer.permission.current')
      }
      .width('100%')
      .height(40)
      .padding({ left: 16, right: 16 })
      if (this.snapshot.permissionOptions.length === 0) {
        Text(this.snapshot.permissionError.length > 0 ? this.snapshot.permissionError : Copy.tr('权限目录为空或尚未读取'))
          .fontSize(Metrics.captionSize)
          .fontColor(this.pal().textSubtle)
          .width('100%')
          .padding({ left: 16, right: 16, top: 4, bottom: 8 })
          .id('chat.composer.permission.error')
      } else {
        Scroll() {
          Column() {
            ForEach(this.snapshot.permissionOptions, (option: SessionPermissionOption) => {
              this.PermissionAction(option)
            }, (option: SessionPermissionOption) => option.id)
          }
          .width('100%')
        }
        .width('100%')
        .constraintSize({ maxHeight: 260 })
        .scrollBar(BarState.Off)
      }
      if (this.snapshot.permissionOptions.length > 0 && this.snapshot.permissionError.length > 0) {
        Text(this.snapshot.permissionError)
          .fontSize(Metrics.captionSize)
          .fontColor(this.pal().textSubtle)
          .width('100%')
          .padding({ left: 16, right: 16, bottom: 4 })
      }
      Text(Copy.tr('只影响当前会话；新会话默认值仍在设置里。'))
        .fontSize(Metrics.captionSize)
        .fontColor(this.pal().textSubtle)
        .width('100%')
        .padding({ left: 16, right: 16, top: 4, bottom: 8 })
    }
    .width('100%')
    .backgroundColor(this.pal().surface)
    .borderRadius(16)
    .border({ width: 0.5, color: this.pal().borderList })
    .margin({ bottom: 8 })
  }

  @Builder
  PermissionAction(option: SessionPermissionOption) {
    Row() {
      Text(option.title)
        .fontSize(Metrics.processSize)
        .fontColor(this.pal().textPrimary)
        .layoutWeight(1)
      if (this.permissionSelected(option.id)) {
        NativeIcon({ name: '已选', symbol: 'CHECK', dark: this.dark, iconSize: 15 })
          .width(15)
          .height(15)
      }
    }
    .width('100%')
    .height(44)
    .padding({ left: 16, right: 16 })
    .id(this.permissionItemId(option.id))
    .onClick(() => {
      this.selectPermission(option.id);
    })
  }

  @Builder
  Attachments() {
    if (this.snapshot.attachmentPaths.length > 0) {
      Scroll() {
        Row() {
          ForEach(this.snapshot.attachmentPaths, (path: string) => {
            Row() {
              NativeIcon({ name: '附件', symbol: 'DOCUMENT', dark: this.dark, iconSize: 16, muted: true })
                .width(16)
                .height(16)
              Text(path.substring(path.lastIndexOf('/') + 1))
                .fontSize(Metrics.captionSize)
                .fontColor(this.pal().textSecondary)
                .maxLines(1)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
                .margin({ left: 5 })
              NativeIcon({ name: '移除附件', symbol: 'CLOSE', dark: this.dark, iconSize: 14, muted: true })
                .width(24)
                .height(24)
                .accessibilityText(Copy.tr('移除附件'))
                .id('chat.attachment.remove.' + path)
                .onClick(() => {
                  this.requireActions().removeAttachment(path);
                })
            }
            .height(30)
            .padding({ left: 10, right: 2 })
            .borderRadius(15)
            .backgroundColor(this.pal().surface)
            .margin({ right: 8 })
          }, (path: string) => path)
        }
        .padding({ bottom: 8 })
      }
      .scrollable(ScrollDirection.Horizontal)
      .scrollBar(BarState.Off)
      .width('100%')
    }
  }

  @Builder
  ComposerDock() {
    Column() {
      if (this.actionError.length > 0) {
        NoticeBanner({
          text: this.actionError,
          dark: this.dark,
          kind: 'error',
          showClose: true,
          bannerId: 'chat.actionError',
          onClose: () => {
            this.actionError = '';
          }
        })
      }
      if (this.commandOpen) {
        this.CommandMenu()
      }
      this.Attachments()
      if (this.snapshot.queueCount > 0) {
        Text(Copy.fill('排队中 {0} 条', [this.snapshot.queueCount.toString()]))
          .fontSize(Metrics.captionSize)
          .fontColor(this.pal().textSecondary)
          .padding({ left: 4, bottom: 6 })
      }
      if (this.referenceOpen) {
        this.ReferenceMenu()
      }
      if (this.addOpen) {
        this.AddMenu()
      }
      if (this.modelOpen) {
        this.ModelMenu()
      }
      if (this.permissionOpen) {
        this.PermissionMenu()
      }
      Column() {
        TextArea({ text: this.draft, placeholder: this.placeholder() })
          .fontSize(this.bodyFontSize > 0 ? this.bodyFontSize : Metrics.bodySize)
          .fontColor(this.pal().textPrimary)
          .placeholderColor(this.pal().inputPlaceholder)
          .lineHeight(this.bodyFontSize > 0 ? this.bodyFontSize + 8 : Metrics.bodyLine)
          .backgroundColor('#00000000')
          .borderRadius(0)
          .padding(0)
          .margin({ top: 4 })
          .constraintSize({ minHeight: 44, maxHeight: 160 })
          .id('chat.composer.input')
          .onChange((value: string) => {
            this.draft = value;
            this.requireActions().setDraft(value);
            this.syncCommandMenu(value);
            this.noteDraftChanged(value);
          })
        Row() {
          NativeIcon({ name: '添加指令、引用或附件', symbol: 'PLUS', dark: this.dark, iconSize: 22 })
            .width(Metrics.composerControlHeight)
            .height(Metrics.composerControlHeight)
            .accessibilityText(Copy.tr('添加指令、引用或附件'))
            .id('chat.composer.add')
            .onClick(() => {
              this.addOpen = !this.addOpen;
              this.referenceOpen = false;
              this.referenceRequested = false;
              this.modelOpen = false;
              this.permissionOpen = false;
            })
          Row() {
            NativeIcon({
              name: '权限：' + this.permission(),
              symbol: 'LOCK_SHIELD',
              dark: this.dark,
              iconSize: 18,
              muted: this.permission().length === 0
            })
              .width(18)
              .height(18)
            NativeIcon({
              name: '展开权限',
              symbol: 'CHEVRON_DOWN',
              dark: this.dark,
              iconSize: 10,
              muted: true
            })
              .width(12)
              .height(12)
              .margin({ left: 1 })
          }
          .width(38)
          .height(Metrics.composerControlHeight)
          .accessibilityText(Copy.fill('权限：{0}', [this.permission()]))
          .id('chat.composer.permission')
          .onClick(() => {
            this.permissionOpen = !this.permissionOpen;
            if (this.permissionOpen) this.run(this.requireActions().refresh());
            this.addOpen = false;
            this.referenceOpen = false;
            this.referenceRequested = false;
            this.modelOpen = false;
          })
          Text(this.currentModel())
            .fontSize(14)
            .fontColor(this.pal().textPrimary)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .layoutWeight(1)
            .margin({ left: 2 })
            .id('chat.composer.model')
            .onClick(() => {
              this.modelOpen = !this.modelOpen;
              if (this.modelOpen) this.run(this.requireActions().refresh());
              this.addOpen = false;
              this.referenceOpen = false;
              this.referenceRequested = false;
              this.permissionOpen = false;
            })
          Row() {
            Text(this.currentEffort())
              .fontSize(12)
              .fontColor(this.pal().textSecondary)
              .maxLines(1)
            NativeIcon({
              name: '推理强度 ' + this.currentEffort(),
              symbol: 'CHEVRON_DOWN',
              dark: this.dark,
              iconSize: 14,
              muted: true
            })
              .width(14)
              .height(14)
              .margin({ left: 2 })
          }
          .height(Metrics.composerControlHeight)
          .padding({ left: 2, right: 2 })
          .accessibilityText(Copy.fill('推理强度 {0}', [this.currentEffort()]))
          .id('chat.composer.effort')
          .onClick(() => {
            this.modelOpen = !this.modelOpen;
            if (this.modelOpen) this.run(this.requireActions().refresh());
            this.addOpen = false;
            this.referenceOpen = false;
            this.referenceRequested = false;
            this.permissionOpen = false;
          })
          if (this.snapshot.running) {
            NativeIcon({ name: '停止执行', symbol: 'STOP', dark: this.dark, iconSize: 16 })
              .width(Metrics.sendButton)
              .height(Metrics.sendButton)
              .borderRadius(Metrics.sendButton / 2)
              .backgroundColor(this.pal().primaryButton)
              .opacity(this.snapshot.stopping ? 0.55 : 1)
              .accessibilityText(Copy.tr('停止执行'))
              .id('chat.composer.stop')
              .onClick(() => {
                if (!this.snapshot.stopping) {
                  this.run(this.requireActions().stopTurn());
                }
              })
          } else {
            NativeIcon({ name: '发送消息', symbol: 'SEND', dark: !this.dark, iconSize: 20 })
              .width(Metrics.sendButton)
              .height(Metrics.sendButton)
              .borderRadius(Metrics.sendButton / 2)
              .backgroundColor(this.pal().primaryButton)
              .opacity(this.draft.length > 0 ? 1 : 0.4)
              .accessibilityText(Copy.tr('发送消息'))
              .id('chat.composer.send')
              .onClick(() => {
                if (this.draft.length > 0) {
                  this.referenceOpen = false;
                  this.referenceRequested = false;
                  this.followTail = true;
                  this.scheduleTail();
                  this.run(this.requireActions().send(this.draft));
                }
              })
          }
        }
        .width('100%')
        .height(Metrics.composerControlHeight)
        .margin({ top: 2 })
      }
      .width('100%')
      .constraintSize({ minHeight: Metrics.composerMinHeight })
      .padding({ left: 14, right: 10, top: 8, bottom: 6 })
      .backgroundColor(this.pal().surface)
      .borderRadius(Metrics.composerRadius)
      this.ComposerMetricsFooter()
    }
    .width('100%')
    .padding({ left: Metrics.chromeInset, right: Metrics.chromeInset, bottom: 8 })
  }

  private speedLabel(): string {
    const speed = tokensPerSecond(this.snapshot.sessionStats ?? null);
    return speed === null ? '' : Math.round(speed).toString() + ' tok/s';
  }

  private cacheLabel(): string {
    const hit = cacheHitPercent(this.snapshot.tokenUsage ?? null);
    return hit === null ? '' : Copy.fill('缓存命中 {0}%', [formatMetricPercent(hit)]);
  }

  private activityLabel(): string {
    const stats = this.snapshot.sessionStats ?? null;
    if (stats === null || stats.steps <= 0) {
      return '';
    }
    return Copy.fill('{0} 轮 {1} 步', [stats.turns.toString(), stats.steps.toString()]);
  }

  private usageLabel(): string {
    const total = totalTokens(this.snapshot.tokenUsage ?? null);
    return total === null ? '' : compactMetricTokens(total) + ' tok';
  }

  private contextLabel(): string {
    const percent = contextPercent(this.snapshot.contextPressure ?? null);
    return percent === null ? '' : percent.toString() + '%';
  }

  private compactFooter(): boolean {
    return this.snapshot.settings.performanceUsage === 'compact';
  }

  private hasFooterMetrics(): boolean {
    return this.snapshot.sessionStats !== null && this.snapshot.sessionStats !== undefined
      || this.snapshot.tokenUsage !== null && this.snapshot.tokenUsage !== undefined
      || this.snapshot.contextPressure !== null && this.snapshot.contextPressure !== undefined
      || this.snapshot.contextBreakdown !== null && this.snapshot.contextBreakdown !== undefined;
  }

  @Builder
  ComposerMetricsFooter() {
    if (this.hasFooterMetrics()) {
      Row() {
        if (this.compactFooter()) {
          if (this.speedLabel().length > 0) {
            ChatMetricPill({ label: this.speedLabel(), symbol: 'GAUGE',
              pillId: 'chat.composer.metrics.compact', dark: this.dark })
          }
          if (this.cacheLabel().length > 0) {
            ChatMetricPill({ label: this.cacheLabel(), symbol: 'DATABASE',
              pillId: 'chat.composer.metrics.compact-cache', dark: this.dark })
          }
        } else {
          if (this.activityLabel().length > 0 || this.speedLabel().length > 0) {
            ChatMetricPill({
              label: (this.activityLabel().length > 0 ? this.activityLabel() : '')
                + (this.activityLabel().length > 0 && this.speedLabel().length > 0 ? ' · ' : '')
                + this.speedLabel(),
              symbol: 'GAUGE', pillId: 'chat.composer.metrics.activity', dark: this.dark
            })
          }
          if (this.usageLabel().length > 0) {
            ChatMetricPill({
              label: this.usageLabel() + (this.cacheLabel().length > 0 ? ' · ' + this.cacheLabel() : ''),
              symbol: 'DATABASE', pillId: 'chat.composer.metrics.usage', dark: this.dark
            })
          }
          if (this.contextLabel().length > 0) {
            ChatMetricPill({ label: this.contextLabel(), symbol: 'GAUGE',
              pillId: 'chat.composer.metrics.context', dark: this.dark })
          }
        }
      }
      .width('100%')
      .height(26)
      .padding({ left: 4, right: 4, top: 2 })
      .justifyContent(FlexAlign.Start)
    }
  }

  build() {
    Stack({ alignContent: Alignment.TopEnd }) {
      Column() {
        this.ChatHeader()
        this.StatusArea()
        if (this.snapshot.rows.length > 0 && this.snapshot.engine !== EnginePhase.Ready) {
          Column() {
            Text(this.snapshot.failure.length > 0 ? this.snapshot.failure : this.snapshot.engineText)
              .fontSize(Metrics.captionSize)
              .fontColor(this.pal().pageDanger)
              .width('100%')
            if (this.snapshot.engine === EnginePhase.Failed || this.snapshot.engine === EnginePhase.Stopped) {
              Button(Copy.tr('重新启动引擎'))
                .fontSize(Metrics.captionSize)
                .fontColor(this.pal().primaryButtonInk)
                .backgroundColor(this.pal().primaryButton)
                .id('chat.engine.retry')
                .onClick((): void => { this.run(this.requireActions().startEngine()); })
            }
          }
          .width('100%')
          .padding({ left: Metrics.contentInset, right: Metrics.contentInset, top: 4, bottom: 4 })
        }
        this.Transcript()
        this.PendingArea()
        if (this.hasQuestionPending()) {
          this.QuestionDock()
        } else {
          this.ComposerDock()
        }
      }
      .width('100%')
      .height('100%')
      .backgroundColor(this.pal().page)

      if (this.headerMenuOpen) {
        Text('')
          .width('100%')
          .height('100%')
          .backgroundColor('#01000000')
          .id('chat.menu.scrim')
          .onClick(() => {
            this.headerMenuOpen = false;
          })
        this.HeaderMenuPanel()
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(this.pal().page)
  }
}

@Component
struct ChatRowView {
  // The binding is the observed, renderer-owned identity of one stable row.
  // Snapshot replacement updates binding.row in place; the reused child sees it.
  @ObjectLink binding: ChatRowBinding;
  @Prop dark: boolean = false;
  @Prop bodyFontSize: number = 0;
  @Prop codeFontSize: number = 0;
  onCopy: (row: ChatRow) => void = () => {};
  onToggleTurn: (turnId: string) => void = () => {};
  onOpenPath: (path: string) => void = () => {};
  loadImage?: (source: string, control?: ImageLoadControl) => Promise<ImageBytes>;
  onOpenResource: (source: string) => void = () => {};
  @State expanded: boolean = false;
  @State detailCopied: boolean = false;

  private pal(): Palette {
    return new Palette(this.dark);
  }

  private timeLabel(): string {
    if (this.binding.row.turnEndedAt > 0) {
      return clockLabel(this.binding.row.turnEndedAt);
    }
    return clockLabel(this.binding.row.createdAt);
  }

  private summaryLabel(): string {
    return turnProcessLabel(
      this.binding.row.turnOutcome,
      elapsedMillis(this.binding.row.turnStartedAt, this.binding.row.turnEndedAt)
    );
  }

  @Builder
  TurnSummaryRow() {
    if (this.binding.row.turnId.length > 0 && this.binding.row.turnEndedAt > 0 && this.summaryLabel().length > 0) {
      Row() {
        Text(this.summaryLabel())
          .fontSize(Metrics.processSize)
          .fontColor(this.binding.row.turnOutcome === TurnOutcome.Failed
            ? this.pal().sessionDanger : this.pal().textTertiary)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .layoutWeight(1)
        if (this.binding.row.turnOutcome === TurnOutcome.Completed) {
          NativeIcon({
            name: this.binding.turnExpanded ? '收起工作过程' : '展开工作过程',
            symbol: this.binding.turnExpanded ? 'CHEVRON_UP' : 'CHEVRON_DOWN',
            dark: this.dark,
            iconSize: 13,
            muted: true
          })
            .width(24)
            .height(24)
        }
      }
      .width('100%')
      .height(Metrics.processRowHeight)
      .margin({ top: 8 })
      .id('chat.turn.summary.' + this.binding.row.turnId)
      .onClick(() => {
        if (this.binding.row.turnOutcome === TurnOutcome.Completed) {
          this.onToggleTurn(this.binding.row.turnId);
        }
      })
    }
  }

  @Builder
  UserRow() {
    Column() {
      Row() {
        Blank()
        Column() {
          Text(this.binding.row.text)
            .fontSize(this.bodyFontSize > 0 ? this.bodyFontSize : Metrics.bodySize)
            .fontColor(this.pal().textPrimary)
            .lineHeight(this.bodyFontSize > 0 ? this.bodyFontSize + 8 : Metrics.bodyLine)
            .textAlign(TextAlign.Start)
        }
        .constraintSize({ maxWidth: Metrics.bubbleMaxWidth })
        .padding({
          left: Metrics.bubblePaddingH,
          right: Metrics.bubblePaddingH,
          top: Metrics.bubblePaddingV,
          bottom: Metrics.bubblePaddingV
        })
        .backgroundColor(this.pal().userBubble)
        .borderRadius({ topLeft: Metrics.bubbleRadius, topRight: 0, bottomLeft: Metrics.bubbleRadius, bottomRight: Metrics.bubbleRadius })
      }
      .width('100%')
      .margin({ top: 14 })
    }
  }

  @Builder
  ProcessRow(label: string, summary: string, status: string, glyph: string, hasDetail: boolean) {
    Column() {
      if (this.binding.showTurnSummary) {
        this.TurnSummaryRow()
      }
      Row() {
        NativeIcon({ name: label, symbol: glyph, dark: this.dark, iconSize: 15, muted: true })
          .width(Metrics.processIcon)
          .height(15)
        Text(label)
          .fontSize(Metrics.processSize)
          .fontColor(this.pal().textSecondary)
          .margin({ left: 6 })
        if (summary.length > 0) {
          Text(summary)
            .fontSize(Metrics.processSize)
            .fontColor(this.pal().textTertiary)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .layoutWeight(1)
            .margin({ left: 8 })
        } else {
          Blank().layoutWeight(1)
        }
        if (status.length > 0) {
          Text(Copy.toolStatusLabel(status))
            .fontSize(Metrics.captionSize)
            .fontColor(this.statusColor(status))
            .margin({ left: 8 })
        }
        NativeIcon({
          name: this.expanded ? '已展开' : '已折叠',
          symbol: this.expanded ? 'CHEVRON_UP' : 'CHEVRON_DOWN',
          dark: this.dark,
          iconSize: 13,
          muted: true
        })
          .width(24)
          .height(24)
      }
      .width('100%')
      .constraintSize({ minHeight: Metrics.processRowHeight + 8 })
      .id('chat.row.' + this.binding.row.id)
      .onClick(() => {
        if (hasDetail) {
          this.expanded = !this.expanded;
        }
      })
      if (this.expanded && hasDetail) {
        Column() {
          if (this.binding.row.detail.length > 0) {
            Text(this.binding.row.detail)
              .fontSize(Metrics.listItemSize)
              .fontColor(this.pal().textTertiary)
              .lineHeight(Metrics.listItemLine)
              .width('100%')
          } else {
            Text(Copy.tr('没有更多详情'))
              .fontSize(Metrics.listItemSize)
              .fontColor(this.pal().textTertiary)
          }
          if (this.binding.row.path.length > 0) {
            Row() {
              Text(this.binding.row.path)
                .fontSize(Metrics.captionSize)
                .fontColor(this.pal().sessionAccent)
                .maxLines(1)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
                .layoutWeight(1)
                .id('chat.row.path.' + this.binding.row.id)
                .onClick(() => {
                  this.onOpenPath(this.binding.row.path);
                })
            }
            .width('100%')
            .margin({ top: 4 })
          }
        }
        .width('100%')
        .padding({ left: 22, top: 4, bottom: 6 })
        .backgroundColor(this.pal().surface)
        .borderRadius(10)
        .margin({ top: 4 })
      }
    }
    .width('100%')
    .margin({ top: 8 })
  }

  /** Colour follows the raw Worker status enum, never the localized label. */
  private statusColor(status: string): string {
    if (status === 'failed' || status === 'error') { return this.pal().sessionDanger; }
    if (status === 'running') { return this.pal().sessionSuccess; }
    return this.pal().textSubtle;
  }

  private copyDetail(): void {
    const text: string = this.binding.row.detail.length > 0 ? this.binding.row.detail : this.binding.row.text;
    const data: pasteboard.PasteData =
      pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, text);
    pasteboard.getSystemPasteboard().setData(data).then(() => {
      this.detailCopied = true;
    }).catch((error: BusinessError) => {
      this.detailCopied = false;
    });
  }

  @Builder
  SystemRow() {
    Column() {
      Row() {
        Text(this.binding.row.kind === RowKind.Error ? '!' : '·')
          .fontSize(13)
          .fontColor(this.binding.row.kind === RowKind.Error ? this.pal().sessionDanger : this.pal().textSubtle)
          .width(16)
          .accessibilityText(this.binding.row.kind === RowKind.Error ? Copy.tr('错误') : Copy.tr('系统提示'))
        Column() {
          Text(this.binding.row.text.length > 0 ? this.binding.row.text : Copy.tr('回合事件'))
            .fontSize(Metrics.captionSize)
            .fontColor(this.binding.row.kind === RowKind.Error ? this.pal().sessionDanger : this.pal().textSecondary)
            .lineHeight(18)
            .width('100%')
          if (this.binding.row.detail.length > 0) {
            Text(this.binding.row.detail)
              .fontSize(Metrics.captionSize)
              .fontColor(this.binding.row.kind === RowKind.Error ? this.pal().sessionDanger : this.pal().textSecondary)
              .lineHeight(18)
              .width('100%')
              .margin({ top: 2 })
              .id('chat.system.detail.' + this.binding.row.id)
          }
          if (this.binding.row.path.length > 0) {
            Text(this.binding.row.path)
              .fontSize(Metrics.captionSize)
              .fontColor(this.pal().sessionAccent)
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
              .width('100%')
              .margin({ top: 2 })
              .id('chat.system.path.' + this.binding.row.id)
              .onClick(() => {
                this.onOpenPath(this.binding.row.path);
              })
          }
        }
        .layoutWeight(1)
        .alignItems(HorizontalAlign.Start)
      }
      .width('100%')
      .alignItems(VerticalAlign.Top)
      if (this.binding.row.detail.length > 0) {
        Row() {
          Blank()
          Text(this.detailCopied ? Copy.tr('已复制') : Copy.tr('复制详情'))
            .fontSize(Metrics.captionSize)
            .fontColor(this.detailCopied ? this.pal().sessionSuccess : this.pal().textSubtle)
            .id('chat.copy.detail.' + this.binding.row.id)
            .onClick(() => {
              this.copyDetail();
            })
        }
        .width('100%')
        .margin({ top: 4 })
      }
    }
    .width('100%')
    .padding({ top: 8, bottom: 8 })
    .border({ width: { bottom: Metrics.hairline }, color: this.pal().borderList })
  }

  build() {
    if (this.binding.row.kind === RowKind.User) {
      this.UserRow()
    } else if (this.binding.row.kind === RowKind.Assistant) {
      Column() {
        if (this.binding.showTurnSummary) {
          this.TurnSummaryRow()
        }
        MarkdownBody({
          markdown: this.binding.row.text,
          dark: this.dark,
          bodyFontSize: this.bodyFontSize,
          codeFontSize: this.codeFontSize,
          loadImage: this.loadImage,
          onOpenResource: this.onOpenResource,
          onOpenPath: (path: string) => {
            if (path.length > 0) {
              this.onOpenPath(path);
            }
          }
        })
        Row() {
          Text(this.binding.copied ? Copy.tr('已复制') : Copy.tr('复制'))
            .fontSize(Metrics.captionSize)
            .fontColor(this.binding.copied ? this.pal().sessionSuccess : this.pal().textSubtle)
            .id('chat.copy.' + this.binding.row.id)
            .onClick(() => {
              this.onCopy(this.binding.row);
            })
          Blank()
          if (this.timeLabel().length > 0) {
            Text(this.timeLabel())
              .fontSize(Metrics.captionSize)
              .fontColor(this.pal().textPlaceholder)
              .margin({ left: 10 })
          }
        }
        .width('100%')
        .margin({ top: 4 })
        if (this.binding.row.status.length > 0 && this.binding.row.status !== 'streaming') {
          Text(Copy.toolStatusLabel(this.binding.row.status))
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().textSubtle)
            .margin({ top: 4 })
        }
      }
      .width('100%')
      .margin({ top: 14 })
    } else if (this.binding.row.kind === RowKind.Thinking) {
      if (this.binding.showProcess) {
        this.ProcessRow('思考', this.binding.row.text, this.binding.row.status, 'THINK', true)
      } else {
        Text('').height(0)
      }
    } else if (this.binding.row.kind === RowKind.Tool) {
      if (this.binding.showProcess) {
        this.ProcessRow(
          this.binding.row.tool.length > 0 ? this.binding.row.tool : '工具',
          this.binding.row.text,
          this.binding.row.status,
          'TERMINAL',
          true
        )
      } else {
        Text('').height(0)
      }
    } else {
      this.SystemRow()
    }
  }
}

@Component
struct ChatMetricPill {
  @Prop label: string = '';
  @Prop symbol: string = 'GAUGE';
  @Prop pillId: string = '';
  @Prop dark: boolean = false;

  build() {
    Row() {
      NativeIcon({ name: this.label, symbol: this.symbol, dark: this.dark, iconSize: 13, muted: true })
        .width(14)
        .height(14)
      Text(this.label)
        .fontSize(11)
        .fontColor(new Palette(this.dark).textTertiary)
        .maxLines(1)
        .margin({ left: 4 })
    }
    .height(22)
    .padding({ left: 6, right: 6 })
    .borderRadius(11)
    .backgroundColor(new Palette(this.dark).sessionSelector)
    .id(this.pillId)
  }
}
