(function() {
  'use strict';

  var engine = null;
  var ready = false;
  var queue = [];

  function log(msg) {
    console.log('[ENGINE] ' + msg);
    if (window.Android) {
      try {
        window.Android.onEngineLine('info string [JS] ' + msg);
      } catch(e) {}
    }
  }

  function send(line) {
    try {
      if (window.Android) {
        window.Android.onEngineLine(line);
      }
    } catch(e) {}
  }

  function cmd(text) {
    if (!engine) {
      log('Queue: ' + text);
      queue.push(text);
      return;
    }

    if (!ready) {
      queue.push(text);
      return;
    }

    log('→ ' + text);
    try {
      engine.ccall('command', null, ['string'], [text]);
    } catch(e) {
      log('Error: ' + e);
    }
  }

  window.EngineBridge = { push: cmd };

  // Инициализация
  setTimeout(function() {
    log('Init');

    if (typeof Stockfish === 'undefined') {
      log('ERROR: Stockfish not found');
      return;
    }

    try {
      var sf = Stockfish({
        wasmBinary: window.wasmBinary,
        locateFile: function(f) { return f.endsWith('.wasm') ? '' : f; },
        print: function(line) {
          log('← ' + line);
          send(line);

          if (line.indexOf('readyok') !== -1) {
            if (!ready) {
              ready = true;
              log('READY! Queue: ' + queue.length);

              setTimeout(function() {
                while (queue.length > 0) {
                  cmd(queue.shift());
                }
              }, 10);
            }
          }
        },
        printErr: function(e) { log('ERR: ' + e); }
      });

      if (sf && typeof sf.then === 'function') {
        log('Async');
        sf.then(function(e) {
          log('Ready');
          engine = e;

          setTimeout(function() {
            log('Init UCI');
            cmd('uci');
            cmd('isready');
          }, 100);
        });
      } else {
        log('Sync');
        engine = sf;

        setTimeout(function() {
          log('Init UCI');
          cmd('uci');
          cmd('isready');
        }, 100);
      }
    } catch(e) {
      log('CRASH: ' + e);
    }
  }, 200);

  log('Loaded');
})();