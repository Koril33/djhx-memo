<script lang="ts">
  import { onMount } from 'svelte';
  import { EditorState, Transaction, Compartment } from '@codemirror/state';
  import {
    EditorView,
    keymap,
    lineNumbers,
    highlightActiveLine,
    placeholder,
  } from '@codemirror/view';
  import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands';
  import { markdown } from '@codemirror/lang-markdown';
  import { defaultHighlightStyle, syntaxHighlighting } from '@codemirror/language';

  export let value = '';
  export let readonly = false;
  export let onEdit: (content: string) => void;
  export let onBlur: () => void;
  export let onComposition: (active: boolean) => void;
  let host: HTMLDivElement;
  let view: EditorView | undefined;
  const writable = new Compartment();

  const theme = EditorView.theme({
    '&': { height: '100%', fontSize: '15px', backgroundColor: 'transparent' },
    '.cm-scroller': {
      fontFamily: '"Cascadia Code", "Noto Sans Mono CJK SC", "Consolas", monospace',
      lineHeight: '1.9',
    },
    '.cm-content': { padding: '30px 24px 120px', caretColor: '#397c65' },
    '.cm-line': { padding: '0 6px' },
    '.cm-gutters': {
      border: 'none',
      backgroundColor: 'transparent',
      color: '#b0b9b0',
      paddingTop: '30px',
    },
    '.cm-activeLine': { backgroundColor: '#f0f4ef' },
    '.cm-activeLineGutter': { backgroundColor: 'transparent', color: '#397c65' },
    '.cm-selectionBackground': { backgroundColor: '#cde4d6 !important' },
    '&.cm-focused': { outline: 'none' },
    '.cm-placeholder': { color: '#9ba59c' },
  });

  onMount(() => {
    view = new EditorView({
      parent: host,
      state: EditorState.create({
        doc: value,
        extensions: [
          lineNumbers(),
          history(),
          highlightActiveLine(),
          markdown(),
          syntaxHighlighting(defaultHighlightStyle),
          keymap.of([...defaultKeymap, ...historyKeymap, indentWithTab]),
          EditorView.lineWrapping,
          theme,
          placeholder('从一个念头开始…\n\n支持 Markdown，输入后自动保存到本机。'),
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
</script>

<div class="code-editor" bind:this={host}></div>

<style>
  .code-editor {
    height: 100%;
    min-height: 0;
    overflow: hidden;
  }
</style>
