<script lang="ts">
  import { createTranslator, type Language } from './lib/i18n';
  import { onMount } from 'svelte';
  import { EditorState, Transaction, Compartment } from '@codemirror/state';
  import { EditorView, keymap, highlightActiveLine, placeholder } from '@codemirror/view';
  import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands';
  import { markdown } from '@codemirror/lang-markdown';
  import { defaultHighlightStyle, syntaxHighlighting, HighlightStyle } from '@codemirror/language';

  export let language: Language = 'en';
  export let value = '';
  export let readonly = false;
  export let onEdit: (content: string) => void;
  export let onBlur: () => void;
  export let onComposition: (active: boolean) => void;
  let host: HTMLDivElement;
  let view: EditorView | undefined;
  const writable = new Compartment();
  const localized = new Compartment();
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
      lineHeight: '1.85',
    },
    '.cm-content': { padding: '32px 24px 96px', caretColor: 'var(--green)' },
    '.cm-line': { padding: '0 6px' },
    '.cm-activeLine': { backgroundColor: 'var(--editor-active)' },
    '.cm-selectionBackground': { backgroundColor: 'var(--editor-selection) !important' },
    '&.cm-focused': { outline: 'none' },
    '.cm-placeholder': { color: 'var(--muted)' },
  });

  onMount(() => {
    view = new EditorView({
      parent: host,
      state: EditorState.create({
        doc: value,
        extensions: [
          history(),
          highlightActiveLine(),
          markdown(),
          syntaxHighlighting(
            HighlightStyle.define(
              defaultHighlightStyle.specs.map((spec) =>
                typeof spec.color === 'string'
                  ? { ...spec, color: `var(--syntax-${spec.color.replace('#', '')})` }
                  : spec,
              ),
            ),
          ),
          keymap.of([...defaultKeymap, ...historyKeymap, indentWithTab]),
          EditorView.lineWrapping,
          theme,
          localized.of(languageExtensions(language)),
          writable.of(EditorState.readOnly.of(readonly)),
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
            compositionstart: () => {
              onComposition(true);
            },
            compositionend: () => {
              setTimeout(() => onComposition(false), 0);
            },
          }),
        ],
      }),
    });
    return () => view?.destroy();
  });

  $: if (view && view.state.doc.toString() !== value) {
    // CodeMirror owns composition. A state/event refresh must not interrupt the IME.
    if (!view.composing) {
      view.dispatch({
        changes: { from: 0, to: view.state.doc.length, insert: value },
        annotations: Transaction.remote.of(true),
      });
    }
  }
  $: if (view) view.dispatch({ effects: writable.reconfigure(EditorState.readOnly.of(readonly)) });
  // Reconfigure this compartment; the document, selection, undo history and IME view stay alive.
  $: if (view) view.dispatch({ effects: localized.reconfigure(languageExtensions(language)) });
</script>

<div class="code-editor" bind:this={host}></div>

<style>
  .code-editor {
    height: 100%;
    min-height: 0;
    overflow: hidden;
  }
</style>
