// PocketIDE's advanced editor (1.5.144, Cursor parity P6): CodeMirror 6 (MIT) in a WebView. Multiple cursors, folding, bracket
// matching, search and replace, and a side-by-side diff. The app talks to it through window.PocketCM; it reports changes through
// the PocketBridge object Android adds.
import { EditorView, basicSetup } from "codemirror";
import { EditorState, Compartment } from "@codemirror/state";
import { keymap } from "@codemirror/view";
import { indentWithTab, undo, redo, toggleComment } from "@codemirror/commands";
import { openSearchPanel, selectNextOccurrence } from "@codemirror/search";
import { foldAll, unfoldAll } from "@codemirror/language";
import { javascript } from "@codemirror/lang-javascript";
import { html } from "@codemirror/lang-html";
import { css } from "@codemirror/lang-css";
import { python } from "@codemirror/lang-python";
import { json } from "@codemirror/lang-json";
import { markdown } from "@codemirror/lang-markdown";
import { oneDark } from "@codemirror/theme-one-dark";
import { MergeView } from "@codemirror/merge";

const langs = {
  js: () => javascript(), mjs: () => javascript(), cjs: () => javascript(), jsx: () => javascript({ jsx: true }),
  ts: () => javascript({ typescript: true }), tsx: () => javascript({ typescript: true, jsx: true }),
  html: () => html(), htm: () => html(), css: () => css(), py: () => python(), json: () => json(), md: () => markdown(),
};
const language = new Compartment(), theme = new Compartment(), editable = new Compartment();
let view = null, merge = null, timer = 0;

function bridge() { return window.PocketBridge || { changed() {}, ready() {} }; }

function extensions(ext, dark, readOnly) {
  return [
    basicSetup,
    keymap.of([indentWithTab]),
    language.of((langs[ext] || (() => []))()),
    theme.of(dark ? oneDark : []),
    editable.of(EditorView.editable.of(!readOnly)),
    EditorView.lineWrapping,
    EditorView.contentAttributes.of({ "aria-label": "Code editor", autocapitalize: "off", autocorrect: "off", spellcheck: "false" }),
    EditorView.updateListener.of(u => {
      if (!u.docChanged) return;
      clearTimeout(timer);
      // the whole text goes back after a short pause, so the app saves what is on screen
      timer = setTimeout(() => bridge().changed(view.state.doc.toString()), 300);
    }),
  ];
}

function clear() {
  if (view) { view.destroy(); view = null; }
  if (merge) { merge.destroy(); merge = null; }
  document.getElementById("root").innerHTML = "";
}

window.PocketCM = {
  open(text, ext, dark, readOnly) {
    clear();
    document.body.className = dark ? "dark" : "";
    view = new EditorView({ state: EditorState.create({ doc: text, extensions: extensions(ext, dark, readOnly) }), parent: document.getElementById("root") });
    view.focus();
  },
  text() { return view ? view.state.doc.toString() : ""; },
  run(name) {
    if (!view) return false;
    const cmds = { search: openSearchPanel, next: selectNextOccurrence, foldAll, unfoldAll, undo, redo, comment: toggleComment };
    const c = cmds[name];
    const ok = c ? c(view) : false;
    view.focus();
    return ok;
  },
  goTo(line) {
    if (!view) return;
    const l = view.state.doc.line(Math.max(1, Math.min(line, view.state.doc.lines)));
    view.dispatch({ selection: { anchor: l.from }, scrollIntoView: true });
    view.focus();
  },
  diff(original, changed, ext, dark) {
    clear();
    document.body.className = dark ? "dark" : "";
    const base = [basicSetup, (langs[ext] || (() => []))(), dark ? oneDark : [], EditorView.editable.of(false), EditorState.readOnly.of(true), EditorView.lineWrapping];
    merge = new MergeView({ a: { doc: original, extensions: base }, b: { doc: changed, extensions: base }, parent: document.getElementById("root"), collapseUnchanged: { margin: 3, minSize: 6 } });
  },
};
bridge().ready();
