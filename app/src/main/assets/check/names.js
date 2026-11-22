// PocketIDE: names a script uses but never defines (1.5.103). Loaded after acorn.min.js, in the hidden browser that checks the
// assistant's edits (SyntaxChecker) and under Node in the benchmark. `node --check` only checks syntax, so a line such as
// `const cooked = FOOD[sid]` with FOOD defined nowhere passed every check and broke every inventory tap in a real project.
// pocketideNames(src, module) returns "OK" or lines "name line" for each name read but declared nowhere in the file, that is not a
// browser, Node or test global. The app filters the result further (core/UndefinedNames: names other scripts or window.x define).
(function (root) {
  var KNOWN = ('window self globalThis document navigator location history screen localStorage sessionStorage indexedDB caches ' +
    'console alert confirm prompt fetch setTimeout clearTimeout setInterval clearInterval queueMicrotask structuredClone ' +
    'requestAnimationFrame cancelAnimationFrame requestIdleCallback cancelIdleCallback getComputedStyle matchMedia ' +
    'devicePixelRatio innerWidth innerHeight outerWidth outerHeight scrollX scrollY pageXOffset pageYOffset open close ' +
    'performance crypto atob btoa encodeURIComponent decodeURIComponent encodeURI decodeURI escape unescape isNaN isFinite ' +
    'parseInt parseFloat Image Audio Option Event CustomEvent KeyboardEvent MouseEvent PointerEvent TouchEvent WheelEvent ' +
    'FocusEvent InputEvent ErrorEvent MessageEvent EventTarget AbortController AbortSignal Node Element HTMLElement ' +
    'HTMLCanvasElement HTMLImageElement HTMLInputElement HTMLAudioElement HTMLVideoElement SVGElement DocumentFragment ' +
    'Text NodeList DOMParser XMLSerializer MutationObserver ResizeObserver IntersectionObserver XMLHttpRequest WebSocket ' +
    'Worker SharedWorker MessageChannel BroadcastChannel FileReader Blob File FormData URL URLSearchParams Headers Request ' +
    'Response TextEncoder TextDecoder ImageData ImageBitmap createImageBitmap Path2D OffscreenCanvas CanvasRenderingContext2D ' +
    'WebGLRenderingContext WebGL2RenderingContext AudioContext webkitAudioContext OfflineAudioContext speechSynthesis ' +
    'SpeechSynthesisUtterance Notification ServiceWorker postMessage addEventListener removeEventListener dispatchEvent ' +
    'customElements DOMRect Touch name status frames parent top opener visualViewport origin isSecureContext reportError ' +
    'Object Function Array Number String Boolean Symbol BigInt Math JSON Date RegExp Error TypeError RangeError SyntaxError ' +
    'ReferenceError EvalError URIError AggregateError Promise Proxy Reflect Map Set WeakMap WeakSet WeakRef ' +
    'FinalizationRegistry ArrayBuffer SharedArrayBuffer DataView Atomics Int8Array Uint8Array Uint8ClampedArray Int16Array ' +
    'Uint16Array Int32Array Uint32Array Float32Array Float64Array BigInt64Array BigUint64Array Intl WebAssembly eval ' +
    'undefined NaN Infinity arguments ' +
    'require module exports __dirname __filename process Buffer global setImmediate clearImmediate ' +
    'describe it test expect jest vi beforeEach afterEach beforeAll afterAll suite context assert chai sinon').split(' ');
  var known = Object.create(null);
  for (var i = 0; i < KNOWN.length; i++) known[KNOWN[i]] = true;

  function isGlobal(n) {
    if (known[n]) return true;
    try { return n in root; } catch (e) { return false; }
  }

  function Scope(parent) { this.parent = parent; this.names = Object.create(null); }
  Scope.prototype.has = function (n) { for (var s = this; s; s = s.parent) if (s.names[n]) return true; return false; };

  var SKIP = { type: 1, start: 1, end: 1, loc: 1, range: 1, raw: 1, value: 0 };

  function names(src, module) {
    var ast = root.acorn.parse(src, { ecmaVersion: 'latest', sourceType: module ? 'module' : 'script', locations: true,
      allowHashBang: true, allowReturnOutsideFunction: true, allowAwaitOutsideFunction: true });
    var refs = [], typeofd = Object.create(null), assigned = Object.create(null);

    // a binding pattern declares its names in [scope]; defaults and computed keys inside it are read in [use]
    function declare(p, scope, use) {
      if (!p) return;
      switch (p.type) {
        case 'Identifier': scope.names[p.name] = true; break;
        case 'ObjectPattern':
          for (var i = 0; i < p.properties.length; i++) {
            var q = p.properties[i];
            if (q.type === 'RestElement') declare(q.argument, scope, use);
            else { if (q.computed) walk(q.key, use); declare(q.value, scope, use); }
          }
          break;
        case 'ArrayPattern': for (var j = 0; j < p.elements.length; j++) declare(p.elements[j], scope, use); break;
        case 'RestElement': declare(p.argument, scope, use); break;
        case 'AssignmentPattern': declare(p.left, scope, use); walk(p.right, use); break;
        default: walk(p, use); // a member expression as an assignment target
      }
    }

    // the function scope a declaration belongs to (let, const and class are lifted too: only names nothing declares are reported)
    function fnScope(s) { while (s.fn !== true && s.parent) s = s.parent; return s; }

    function fn(node, scope) {
      var inner = new Scope(scope); inner.fn = true;
      if (node.type === 'FunctionExpression' && node.id) inner.names[node.id.name] = true;
      for (var i = 0; i < node.params.length; i++) declare(node.params[i], inner, inner);
      walk(node.body, inner);
    }

    function walk(node, scope) {
      if (!node || typeof node.type !== 'string') return;
      switch (node.type) {
        case 'Identifier':
          refs.push({ n: node.name, line: node.loc.start.line, s: scope }); return;
        case 'FunctionDeclaration':
          if (node.id) fnScope(scope).names[node.id.name] = true;
          fn(node, scope); return;
        case 'FunctionExpression': case 'ArrowFunctionExpression':
          fn(node, scope); return;
        case 'ClassDeclaration': case 'ClassExpression':
          if (node.id) fnScope(scope).names[node.id.name] = true;
          walk(node.superClass, scope); walk(node.body, scope); return;
        case 'VariableDeclaration':
          for (var i = 0; i < node.declarations.length; i++) {
            declare(node.declarations[i].id, fnScope(scope), scope);
            walk(node.declarations[i].init, scope);
          }
          return;
        case 'ImportDeclaration':
          for (var k = 0; k < node.specifiers.length; k++) fnScope(scope).names[node.specifiers[k].local.name] = true;
          return;
        case 'ExportNamedDeclaration':
          if (node.declaration) walk(node.declaration, scope);
          else if (!node.source) for (var e = 0; e < node.specifiers.length; e++) walk(node.specifiers[e].local, scope);
          return;
        case 'ExportAllDeclaration': return;
        case 'CatchClause': {
          var c = new Scope(scope); declare(node.param, c, c); walk(node.body, c); return;
        }
        case 'MemberExpression':
          walk(node.object, scope); if (node.computed) walk(node.property, scope); return;
        case 'Property': case 'MethodDefinition': case 'PropertyDefinition':
          if (node.computed) walk(node.key, scope);
          walk(node.value, scope); return;
        case 'LabeledStatement': walk(node.body, scope); return;
        case 'BreakStatement': case 'ContinueStatement': case 'MetaProperty': case 'PrivateIdentifier': return;
        case 'UnaryExpression':
          if (node.operator === 'typeof' && node.argument.type === 'Identifier') { typeofd[node.argument.name] = true; return; }
          break;
        case 'AssignmentExpression':
          if (node.left.type === 'Identifier') assigned[node.left.name] = true;
          break;
        case 'ForInStatement': case 'ForOfStatement':
          if (node.left.type === 'Identifier') assigned[node.left.name] = true;
          break;
      }
      for (var key in node) {
        if (key in SKIP && SKIP[key]) continue;
        var v = node[key];
        if (Array.isArray(v)) { for (var m = 0; m < v.length; m++) if (v[m] && typeof v[m].type === 'string') walk(v[m], scope); }
        else if (v && typeof v.type === 'string') walk(v, scope);
      }
    }

    var top = new Scope(null); top.fn = true;
    walk(ast, top);
    var seen = Object.create(null), out = [];
    for (var r = 0; r < refs.length; r++) {
      var x = refs[r];
      if (seen[x.n] || x.s.has(x.n) || isGlobal(x.n) || typeofd[x.n]) continue;
      // a bare assignment in a script makes a global (sloppy mode); in a module it throws, so it is still reported
      if (!module && assigned[x.n]) continue;
      seen[x.n] = true; out.push(x.n + ' ' + x.line);
    }
    return out.length ? out.join('\n') : 'OK';
  }

  root.pocketideNames = function (src, module) {
    if (typeof root.acorn === 'undefined') return 'SKIP';
    try { return names(src, module); } catch (e) { return 'SKIP'; }
  };
})(typeof globalThis !== 'undefined' ? globalThis : this);
