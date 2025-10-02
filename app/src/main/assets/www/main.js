(function () {
  // ========= ЛОГГЕР =========
  function log(msg) {
    console.log('[MAIN] ' + msg);
    if (window.Android && typeof window.Android.onEngineLine === 'function') {
      try { window.Android.onEngineLine('info string [MAIN] ' + msg); } catch (_) {}
    }
  }
  function sendToAndroid(line) {
    if (!line) return;
    try {
      if (window.Android && typeof window.Android.onEngineLine === 'function') {
        window.Android.onEngineLine(line); // сырые UCI-строки
      } else {
        console.log('[SF]', line);
      }
    } catch (_) {}
  }

  log('Loaded');

  // ========= РАННИЙ МОСТ =========
  (function ensureEarlyBridge() {
    if (!window.EngineBridge) {
      const q = [];
      window.EngineBridge = { _q: q, push: function (cmd) { q.push(String(cmd || '')); } };
    } else if (!('_q' in window.EngineBridge)) {
      window.EngineBridge._q = [];
    }
  })();

  // ========= ПОМОЩНИКИ =========
  function toText(evt) {
    if (typeof evt === 'string') return evt;
    if (evt && typeof evt.data !== 'undefined') return String(evt.data);
    return String(evt);
  }

  function attachReceiver(engine) {
    let attached = false;
    try {
      if (engine && 'onmessage' in engine) {
        engine.onmessage = function (evt) {
          const line = toText(evt);
          if (line) sendToAndroid(line);
        };
        attached = true;
      }
    } catch (_) {}
    if (!attached) {
      try {
        if (engine && typeof engine.addEventListener === 'function') {
          engine.addEventListener('message', function (evt) {
            const line = toText(evt);
            if (line) sendToAndroid(line);
          });
          attached = true;
        }
      } catch (_) {}
    }
    if (!attached && typeof engine === 'function') {
      try {
        engine.onmessage = function (evt) {
          const line = toText(evt);
          if (line) sendToAndroid(line);
        };
        attached = true;
      } catch (_) {}
    }
    return attached;
  }

  function makeSender(engine) {
    if (engine && typeof engine.postMessage === 'function') {
      log('Transport: postMessage');
      return function (s) { engine.postMessage(String(s)); };
    }
    if (typeof engine === 'function') {
      log('Transport: callable function');
      return function (s) { engine(String(s)); };
    }
    return null;
  }

  function hookBridgeAndFlush(sender) {
    const early = window.EngineBridge || { _q: [] };
    const buffered = Array.isArray(early._q) ? early._q.slice() : [];
    window.EngineBridge = {
      _q: [],
      push: function (cmd) {
        const s = String(cmd || '');
        try { sender(s); log('→ ' + s); } catch (e) {
          log('ERROR send: ' + (e && e.message ? e.message : e));
        }
      }
    };
    if (buffered.length) {
      log('Flushing queued commands: ' + buffered.length);
      for (let i = 0; i < buffered.length; i++) {
        try { sender(buffered[i]); log('→ ' + buffered[i]); } catch (e) {
          log('ERROR send (flush): ' + (e && e.message ? e.message : e));
        }
      }
    }
  }

  // ====== Fallback для модульной консольной сборки (как раньше) ======
  var stdinBuf = [];
  var encoder = (typeof TextEncoder !== 'undefined') ? new TextEncoder() : null;
  function enqueueLineToStdin(s) {
    var str = String(s || '');
    if (!/\n$/.test(str)) str += '\n';
    if (encoder) {
      var bytes = encoder.encode(str);
      for (var i = 0; i < bytes.length; i++) stdinBuf.push(bytes[i] & 0xFF);
    } else {
      for (var j = 0; j < str.length; j++) stdinBuf.push(str.charCodeAt(j) & 0xFF);
    }
  }
  function onPrint(text) {
    if (typeof text !== 'string') text = String(text);
    var parts = text.split(/\r?\n/);
    for (var i = 0; i < parts.length; i++) {
      var line = parts[i];
      if (line.length) sendToAndroid(line);
    }
  }
  function onPrintErr(text) { log('ERR: ' + text); }

  function initConsoleModule(mod) {
    log('Using console-module fallback');
    // приём вывода уже через print/printErr
    // запуск main()
    var started = false;
    try {
      if (typeof mod.callMain === 'function') {
        mod.callMain([]); started = true; log('callMain started');
      }
    } catch (e) { log('callMain failed: ' + (e && e.message ? e.message : e)); }
    if (!started) {
      try { mod.ccall('main', 'number', ['number','number'], [0,0]); started = true; log('ccall(main) started'); }
      catch (e2) { log('ccall(main) failed: ' + (e2 && e2.message ? e2.message : e2)); }
    }

    const hasCommand = !!(mod.cwrap && (function(){ try { return typeof mod.cwrap('command', null, ['string']) === 'function'; } catch(_) { return false; } })());
    const sender = hasCommand
      ? function (s) { if (!/\n$/.test(s)) s += '\n'; mod.ccall('command', null, ['string'], [s]); }
      : function (s) { enqueueLineToStdin(s); };

    hookBridgeAndFlush(sender);
    log('Async ready');
  }

  // ========= НОРМАЛИЗАЦИЯ ДВИЖКА ДЛЯ nmrugg lite-single =========
  // Цель: получить ИМЕННО объект с postMessage/onmessage.
  function normalizeEngine(maybeEngine, onReady) {
    try {
      // 1) уже «настоящий» engine?
      if (maybeEngine && (typeof maybeEngine.postMessage === 'function' || typeof maybeEngine.addEventListener === 'function' || 'onmessage' in maybeEngine)) {
        return onReady(maybeEngine);
      }
      // 2) это функция-фабрика следующего уровня? -> вызвать её
      if (typeof maybeEngine === 'function') {
        let result;
        try { result = maybeEngine(); } catch (e) { log('factory() call failed: ' + (e && e.message ? e.message : e)); }
        if (result && typeof result.then === 'function') {
          return result.then(function (eng) { normalizeEngine(eng, onReady); })
                       .catch(function (err) { log('ERROR: inner factory rejected: ' + (err && err.message ? err.message : err)); });
        } else {
          return normalizeEngine(result, onReady);
        }
      }
      // 3) thenable? -> дождаться
      if (maybeEngine && typeof maybeEngine.then === 'function') {
        return maybeEngine.then(function (eng) { normalizeEngine(eng, onReady); })
                          .catch(function (err) { log('ERROR: engine promise rejected: ' + (err && err.message ? err.message : err)); });
      }
      // 4) Иначе: это Emscripten Module — идём в консольный fallback
      return onReady(null, maybeEngine);
    } catch (e) {
      log('normalizeEngine exception: ' + (e && e.message ? e.message : e));
      return onReady(null, maybeEngine);
    }
  }

  // ========= СОЗДАНИЕ ИНСТАНСА ЧЕРЕЗ ВАШУ ФАБРИКУ =========
  var engineCandidate;
  try {
    if (typeof Stockfish !== 'function') throw new Error('Stockfish factory is not a function');

    // КЛЮЧЕВОЕ: передаём print/printErr/stdin, чтобы даже модульный вариант отдавал строки
    var moduleConfig = {
      wasmBinary: window.wasmBinary,
      print: onPrint,
      printErr: onPrintErr,
      stdin: function () { return (stdinBuf.length > 0) ? stdinBuf.shift() : null; }
    };

    engineCandidate = Stockfish(moduleConfig);
    log('Creating Stockfish with wasmBinary');
  } catch (e) {
    log('ERROR: Cannot create Stockfish: ' + (e && e.message ? e.message : e));
    return;
  }

  // ========= ФИНАЛИЗАЦИЯ =========
  normalizeEngine(engineCandidate, function (workerLike, moduleLike) {
    if (workerLike) {
      // Правильный путь для nmrugg lite-single — worker/fn с onmessage
      const gotHandler = attachReceiver(workerLike);
      const sender = makeSender(workerLike);
      if (!sender) { log('ERROR: No known way to send commands to engine'); return; }
      if (!gotHandler) log('WARN: onmessage not available; but transport is set');
      hookBridgeAndFlush(sender);
      log('Async ready');
    } else {
      // Fallback: модульная консольная сборка (stdin/print)
      initConsoleModule(moduleLike);
    }
  });
})();
