<script lang="ts">
  import { createTranslator, type Language, type Translator } from './lib/i18n';
  import type { CodeHighlights } from './lib/code-highlights';
  import { onMount } from 'svelte';
  import { LintController, type LintState } from './lib/lint-controller';
  import { ruleDescriptions } from './lib/markdown-lint';
  import {
    EditorState,
    Transaction,
    Compartment,
    StateEffect,
    StateField,
  } from '@codemirror/state';
  import {
    Decoration,
    EditorView,
    keymap,
    highlightActiveLine,
    lineNumbers,
    placeholder,
    runScopeHandlers,
    type DecorationSet,
    type Panel,
    type ViewUpdate,
  } from '@codemirror/view';
  import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands';
  import { markdown } from '@codemirror/lang-markdown';
  import { defaultHighlightStyle, syntaxHighlighting, HighlightStyle } from '@codemirror/language';
  import {
    closeSearchPanel,
    findNext,
    findPrevious,
    getSearchQuery,
    highlightSelectionMatches,
    openSearchPanel,
    search,
    searchKeymap,
    setSearchQuery,
    SearchQuery,
  } from '@codemirror/search';
  import type { EditorFindApi } from './lib/types';

  export let language: Language = 'en';
  export let value = '';
  export let highlights: CodeHighlights | undefined = undefined;
  export let composing = false;
  export let readonly = false;
  export let onEdit: (content: string) => void;
  export let onBlur: () => void;
  export let onComposition: (active: boolean) => void;
  export let onFocus: (() => void) | undefined = undefined;
  export let onFindApi: ((api: EditorFindApi) => void) | undefined = undefined;
  export let onOutlineApi:
    ((api: { jump: (line: number) => void } | undefined) => void) | undefined = undefined;
  export let onReanchorApi: ((api: { force: () => void } | undefined) => void) | undefined =
    undefined;
  let host: HTMLDivElement;
  let view: EditorView | undefined;
  let pendingReanchor = false;
  export let lintEnabled = false;
  export let onView: ((view: EditorView | undefined) => void) | undefined = undefined;
  export let onLintApi: ((open: (() => void) | undefined) => void) | undefined = undefined;
  let lintState: LintState = { phase: 'off', issues: [], truncated: false };
  let lintPanel = false;
  let lintModule: typeof import('@codemirror/lint') | undefined;
  let lintLoader: Promise<void> | undefined;
  const lintConfig = new Compartment();
  const lintController = new LintController((next) => {
    lintState = next;
    // Never mutate decorations during IME composition: WebView2/Chromium can
    // re-anchor the composition window when the content DOM shifts mid-compose.
    // compositionStarted covers the compositionstart→first-DOM-change window
    // that view.composing misses.
    if (view && lintModule && !view.compositionStarted)
      view.dispatch({
        effects: lintModule.setDiagnosticsEffect.of(
          next.issues.map((issue) => ({
            from: issue.from,
            to: issue.to,
            severity: 'warning' as const,
            message: `${issue.rule} · ${ruleDescriptions[issue.rule]?.[language === 'zh-CN' ? 0 : 1] ?? issue.rule}`,
          })),
        ),
      });
  });
  async function configureLint(enabled: boolean) {
    if (!enabled) {
      view?.dispatch({ effects: lintConfig.reconfigure([]) });
      lintPanel = false;
      return;
    }
    lintLoader ??= import('@codemirror/lint').then((module) => {
      lintModule = module;
    });
    try {
      await lintLoader;
      if (view && lintEnabled && lintModule)
        view.dispatch({ effects: lintConfig.reconfigure(lintModule.linter(null)) });
    } catch {
      lintState = { phase: 'failed', issues: [], truncated: false };
    }
  }
  $: t = createTranslator(language);
  $: if (view) void configureLint(lintEnabled);
  $: lintController.update(value, lintEnabled, composing);
  $: if (view && lintEnabled && lintModule && language && !composing) {
    view.dispatch({
      effects: lintModule.setDiagnosticsEffect.of(
        lintState.issues.map((issue) => ({
          from: issue.from,
          to: issue.to,
          severity: 'warning' as const,
          message: `${issue.rule} · ${ruleDescriptions[issue.rule]?.[language === 'zh-CN' ? 0 : 1] ?? issue.rule}`,
        })),
      ),
    });
  }
  const writable = new Compartment();
  const localized = new Compartment();
  const setCodeColors = StateEffect.define<DecorationSet>();
  const codeColors = StateField.define<DecorationSet>({
    create: () => Decoration.none,
    update(current, transaction) {
      return (
        transaction.effects.find((effect) => effect.is(setCodeColors))?.value ??
        (transaction.docChanged ? Decoration.none : current)
      );
    },
    provide: (field) => EditorView.decorations.from(field),
  });
  // A re-anchor effect: after a window resize WebView2 can stop reporting the
  // caret bounds, parking the IME candidate window at the screen origin
  // (WebView2Feedback#5675). Toggling a decoration forces docView.update to
  // redraw and re-write the DOM selection, which re-reports the caret. No
  // focus change, no flush, no visible glyph.
  const setReanchor = StateEffect.define<DecorationSet>();
  const reanchorDecorations = StateField.define<DecorationSet>({
    create: () => Decoration.none,
    update(current, transaction) {
      for (const effect of transaction.effects) if (effect.is(setReanchor)) return effect.value;
      return current;
    },
    provide: (field) => EditorView.decorations.from(field),
  });
  function reanchorNow(): void {
    if (!view || view.compositionStarted) return;
    const length = Math.min(1, view.state.doc.length);
    if (length === 0) return;
    // Add then remove an invisible mark so the first dispatch redraws and
    // re-writes the DOM selection (re-reporting caret bounds), while the
    // second restores the decoration set.
    view.dispatch({
      effects: setReanchor.of(
        Decoration.set([Decoration.mark({ class: 'cm-reanchor' }).range(0, length)]),
      ),
    });
    view.dispatch({ effects: setReanchor.of(Decoration.none) });
  }
  function languageExtensions(locale: Language) {
    const t = createTranslator(locale);
    return [
      placeholder(t('editorPlaceholder')),
      EditorView.contentAttributes.of({ 'aria-label': t('noteEditor') }),
    ];
  }

  const theme = EditorView.theme({
    '&': { height: '100%', fontSize: '15px', backgroundColor: 'transparent' },
    '.cm-scroller': {
      fontFamily: 'inherit',
      lineHeight: '1.70',
    },
    '.cm-content': { padding: '32px 24px 96px', caretColor: 'var(--green)' },
    '.cm-line': { padding: '0 6px' },
    '.cm-activeLine': { backgroundColor: 'var(--editor-active)' },
    '.cm-selectionBackground': { backgroundColor: 'var(--editor-selection) !important' },
    '&.cm-focused': { outline: 'none' },
    '.cm-placeholder': { color: 'var(--muted)' },
    '.cm-gutters': {
      backgroundColor: 'transparent',
      color: 'var(--muted)',
      borderRight: '1px solid var(--border)',
    },
    '.cm-lineNumbers .cm-gutterElement': { color: 'var(--muted)' },
  });

  /**
   * Lightweight find-panel shell. The default `searchPanel` ships a replace box and
   * no hide switch, neither of which 0.1.11 wants; the search/highlight/navigation
   * engine stays CodeMirror's (`setSearchQuery`/`findNext`/`findPrevious`/`matchHighlighter`).
   */
  class FindPanel implements Panel {
    readonly dom: HTMLElement;
    readonly top = true;
    private view: EditorView;
    private t: Translator;
    private input: HTMLInputElement;
    private count: HTMLElement;
    private caseField: HTMLInputElement;
    private query: SearchQuery;
    private composing = false;

    constructor(view: EditorView, t: Translator) {
      this.view = view;
      this.t = t;
      this.query = getSearchQuery(view.state);

      this.input = document.createElement('input');
      this.input.type = 'text';
      this.input.className = 'memo-find-input';
      this.input.value = this.query.search;
      this.input.placeholder = t('findPlaceholder');
      this.input.setAttribute('aria-label', t('findPlaceholder'));
      this.input.setAttribute('main-field', 'true');

      this.count = document.createElement('span');
      this.count.className = 'memo-find-count';

      const prev = document.createElement('button');
      prev.type = 'button';
      prev.className = 'memo-find-button';
      prev.textContent = '↑';
      prev.title = t('findPrev');
      prev.setAttribute('aria-label', t('findPrev'));
      prev.addEventListener('click', () => {
        findPrevious(this.view);
        this.refreshCount();
      });

      const next = document.createElement('button');
      next.type = 'button';
      next.className = 'memo-find-button';
      next.textContent = '↓';
      next.title = t('findNext');
      next.setAttribute('aria-label', t('findNext'));
      next.addEventListener('click', () => {
        findNext(this.view);
        this.refreshCount();
      });

      const caseLabel = document.createElement('label');
      caseLabel.className = 'memo-find-case';
      this.caseField = document.createElement('input');
      this.caseField.type = 'checkbox';
      this.caseField.checked = this.query.caseSensitive;
      this.caseField.addEventListener('change', () => this.commit());
      caseLabel.append(this.caseField, document.createTextNode(t('findCaseSensitive')));

      const close = document.createElement('button');
      close.type = 'button';
      close.className = 'memo-find-close';
      close.textContent = '×';
      close.title = t('findClose');
      close.setAttribute('aria-label', t('findClose'));
      close.addEventListener('click', () => closeSearchPanel(this.view));

      this.dom = document.createElement('div');
      this.dom.className = 'memo-find-panel';
      this.dom.append(this.input, this.count, prev, next, caseLabel, close);

      this.input.addEventListener('input', () => this.commit());
      this.input.addEventListener('compositionstart', () => {
        this.composing = true;
      });
      this.input.addEventListener('compositionend', () => {
        this.composing = false;
      });
      this.dom.addEventListener('keydown', this.keydown);
    }

    private keydown = (event: KeyboardEvent) => {
      // Route scoped search keys (Escape closes the panel, F3/G navigate).
      if (runScopeHandlers(this.view, event, 'search-panel')) {
        event.preventDefault();
        return;
      }
      if (event.key === 'Enter' && !this.composing && event.target === this.input) {
        event.preventDefault();
        (event.shiftKey ? findPrevious : findNext)(this.view);
        this.refreshCount();
      }
    };

    private commit(): void {
      const query = new SearchQuery({
        search: this.input.value,
        caseSensitive: this.caseField.checked,
        literal: true,
      });
      if (!query.eq(this.query)) {
        this.query = query;
        this.view.dispatch({ effects: setSearchQuery.of(query) });
      }
      findNext(this.view);
      this.refreshCount();
    }

    private setQuery(query: SearchQuery): void {
      this.query = query;
      if (this.input.value !== query.search) this.input.value = query.search;
      if (this.caseField.checked !== query.caseSensitive)
        this.caseField.checked = query.caseSensitive;
    }

    private refreshCount(): void {
      const { total, current } = countMatches(this.view, this.query);
      this.count.textContent =
        total === 0 ? this.t('searchNoResults') : this.t('findCount', { current, total });
    }

    mount(): void {
      this.input.focus();
      this.input.select();
      this.refreshCount();
    }

    update(update: ViewUpdate): void {
      let changed = false;
      for (const transaction of update.transactions)
        for (const effect of transaction.effects)
          if (effect.is(setSearchQuery) && !effect.value.eq(this.query)) {
            this.setQuery(effect.value);
            changed = true;
          }
      if (changed || update.selectionSet || update.docChanged) this.refreshCount();
    }
  }

  function countMatches(view: EditorView, query: SearchQuery): { total: number; current: number } {
    if (!query.valid) return { total: 0, current: 0 };
    const selection = view.state.selection.main;
    let total = 0;
    let current = 0;
    const cursor = query.getCursor(view.state) as unknown as IterableIterator<{
      from: number;
      to: number;
    }>;
    for (const match of cursor) {
      total++;
      if (current === 0 && match.from === selection.from && match.to === selection.to)
        current = total;
    }
    return { total, current };
  }

  function createFindPanel(view: EditorView, t: Translator): Panel {
    return new FindPanel(view, t);
  }

  onMount(() => {
    const editor = new EditorView({
      parent: host,
      state: EditorState.create({
        doc: value,
        extensions: [
          history(),
          highlightActiveLine(),
          lineNumbers(),
          markdown(),
          codeColors,
          reanchorDecorations,
          syntaxHighlighting(
            HighlightStyle.define(
              defaultHighlightStyle.specs.map((spec) =>
                typeof spec.color === 'string'
                  ? { ...spec, color: `var(--syntax-${spec.color.replace('#', '')})` }
                  : spec,
              ),
            ),
          ),
          // Q2 (0.2.0): CodeMirror's search match highlighter iterates only
          // `view.visibleRanges`, so large notes already decorate just the
          // visible/current hits. `countMatches`/`findNext`/`findPrevious` below
          // stay full-document, keeping the complete count and navigation accurate.
          search({
            top: true,
            literal: true,
            caseSensitive: false,
            createPanel: (editorView) => createFindPanel(editorView, createTranslator(language)),
          }),
          highlightSelectionMatches(),
          keymap.of([...searchKeymap, ...defaultKeymap, ...historyKeymap, indentWithTab]),
          EditorView.lineWrapping,
          theme,
          localized.of(languageExtensions(language)),
          writable.of(EditorState.readOnly.of(readonly)),
          lintConfig.of([]),
          EditorView.updateListener.of((update) => {
            if (
              update.docChanged &&
              !update.transactions.every((t) => t.annotation(Transaction.remote))
            ) {
              onEdit(update.state.doc.toString());
            }
          }),
          EditorView.domEventHandlers({
            blur: () => {
              onBlur();
            },
            focus: () => {
              onFocus?.();
            },
            compositionstart: () => {
              onComposition(true);
            },
            compositionend: () => {
              setTimeout(() => {
                onComposition(false);
                if (pendingReanchor) {
                  pendingReanchor = false;
                  reanchorNow();
                }
              }, 0);
            },
          }),
        ],
      }),
    });
    view = editor;
    onView?.(editor);
    onLintApi?.(() => {
      lintPanel = true;
    });
    onFindApi?.({
      open: () => openSearchPanel(editor),
      run: (query) => {
        editor.dispatch({
          effects: setSearchQuery.of(
            new SearchQuery({ search: query, literal: true, caseSensitive: false }),
          ),
        });
        findNext(editor);
        editor.focus();
      },
      focus: () => editor.focus(),
    });
    onOutlineApi?.({
      jump(line) {
        if (!view || view.state.doc.toString() !== value.replace(/\r\n?/g, '\n')) return;
        const pos = view.state.doc.line(line + 1).from;
        view.dispatch({ effects: EditorView.scrollIntoView(pos, { y: 'center' }) });
        view.focus();
      },
    });
    onReanchorApi?.({
      force() {
        // Defer while composing; compositionend consumes the pending request.
        if (view?.compositionStarted) pendingReanchor = true;
        else reanchorNow();
      },
    });
    return () => {
      lintController.cancel();
      onView?.(undefined);
      onLintApi?.(undefined);
      onOutlineApi?.(undefined);
      onReanchorApi?.(undefined);
      editor.destroy();
    };
  });

  $: normalizedValue = value.replace(/\r\n?/g, '\n');
  $: if (view && view.state.doc.toString() !== normalizedValue) {
    // CodeMirror owns composition. A state/event refresh must not interrupt the IME.
    if (!view.composing) {
      view.dispatch({
        changes: { from: 0, to: view.state.doc.length, insert: normalizedValue },
        annotations: Transaction.remote.of(true),
      });
    }
  }
  $: if (view && !composing && view.state.doc.toString() === normalizedValue) {
    const marks = highlights?.source_spans.map(({ start, end, kind }) =>
      Decoration.mark({ class: `code-token-${kind}` }).range(start, end),
    );
    view.dispatch({ effects: setCodeColors.of(marks ? Decoration.set(marks) : Decoration.none) });
  }
  $: if (view) view.dispatch({ effects: writable.reconfigure(EditorState.readOnly.of(readonly)) });
  // Reconfigure this compartment; the document, selection, undo history and IME view stay alive.
  $: if (view) view.dispatch({ effects: localized.reconfigure(languageExtensions(language)) });
</script>

<div class="editor-source-content">
  {#if lintEnabled}<div class="lint-status" aria-live="polite">
      <button
        type="button"
        class="text-button"
        aria-expanded={lintPanel}
        on:click={() => {
          lintPanel = !lintPanel;
        }}
      >
        {t('lintProblems')} · {lintState.phase === 'ready'
          ? `${lintState.issues.length}${lintState.truncated ? '+' : ''}`
          : t(
              lintState.phase === 'paused'
                ? 'lintPaused'
                : lintState.phase === 'failed'
                  ? 'lintFailed'
                  : lintState.phase === 'checking'
                    ? 'lintChecking'
                    : 'lintPending',
            )}
      </button>
      {#if lintState.phase === 'paused' || lintState.phase === 'failed'}<button
          type="button"
          class="text-button"
          on:click={() => lintController.update(value, lintEnabled, composing, true)}
          >{t('lintCheck')}</button
        >{/if}
    </div>{/if}
  <div class="code-editor" bind:this={host}></div>
  {#if lintEnabled && lintPanel}<section class="lint-panel" aria-label={t('lintProblems')}>
      <button
        type="button"
        class="text-button"
        on:click={() => {
          lintPanel = false;
          view?.focus();
        }}>{t('close')}</button
      >
      {#if lintState.phase === 'ready' && !lintState.issues.length}<p>{t('lintClear')}</p>{/if}
      {#each lintState.issues as issue (`${issue.rule}:${issue.from}:${issue.to}`)}<button
          type="button"
          class="lint-issue"
          on:click={() => {
            if (!view || view.state.doc.toString() !== value.replace(/\r\n?/g, '\n')) return;
            view.dispatch({
              selection: { anchor: issue.from, head: issue.to },
              scrollIntoView: true,
            });
            view.focus();
          }}
          >{issue.rule} · {issue.line}:{issue.column} · {ruleDescriptions[issue.rule]?.[
            language === 'zh-CN' ? 0 : 1
          ] ?? issue.rule}</button
        >{/each}
      {#if lintState.truncated}<p>{t('lintTruncated')}</p>{/if}
    </section>{/if}
</div>

<style>
  .code-editor {
    flex: 1;
    min-height: 0;
    overflow: hidden;
  }
  .editor-source-content {
    height: 100%;
    display: flex;
    flex-direction: column;
    min-height: 0;
  }
  .lint-status {
    display: flex;
    flex-wrap: wrap;
    border-bottom: 1px solid var(--border);
    padding: 4px 16px;
  }
  .lint-panel {
    max-height: 180px;
    overflow: auto;
    border-top: 1px solid var(--border);
    padding: 8px 16px;
    font-size: 13px;
  }
  .lint-issue {
    display: block;
    width: 100%;
    text-align: left;
    color: var(--ink);
    background: transparent;
    border: 0;
    padding: 5px;
    cursor: pointer;
  }
</style>
