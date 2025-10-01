console.log('[MAIN] main.js loading...');

const inQueue = [];
let engine = null;
let engineReady = false;

window.EngineBridge = {
  push: (cmd) => {
    console.log('[BRIDGE] Command:', cmd);
    if (!cmd || typeof cmd !== 'string') return;
    if (engineReady && engine) {
      engine.postMessage(cmd);
    } else {
      console.log('[BRIDGE] Queuing command');
      inQueue.push(cmd);
    }
  }
};

function sendToAndroid(line) {
  console.log('[ANDROID] →', line);
  try {
    if (window.Android && typeof window.Android.onEngineLine === 'function') {
      window.Android.onEngineLine(line);
    }
  } catch (e) {
    console.error('[ANDROID] Error:', e);
  }
}

function boot() {
  console.log('[BOOT] Starting...');

  try {
    // Проверяем наличие Stockfish
    if (typeof Stockfish === 'undefined') {
      console.error('[BOOT] Stockfish not found!');
      sendToAndroid('info string ERROR: Stockfish not found');
      return;
    }

    console.log('[BOOT] Creating Stockfish with preloaded WASM...');

    // КРИТИЧНО: передаём готовый WASM бинарник
    const config = {
      wasmBinary: window.wasmBinary,
      locateFile: function(file) {
        console.log('[BOOT] locateFile called for:', file);
        // Для WASM файлов возвращаем пустую строку, т.к. уже загружено
        if (file.endsWith('.wasm')) {
          return '';
        }
        return file;
      }
    };

    const maybeEngine = Stockfish(config);

    if (maybeEngine && typeof maybeEngine.then === 'function') {
      console.log('[BOOT] Async init');
      maybeEngine.then((inst) => {
        console.log('[BOOT] Engine ready (async)');
        engine = inst;
        setupEngine();
      }).catch((err) => {
        console.error('[BOOT] Async error:', err);
        sendToAndroid('info string ERROR: ' + err);
      });
    } else {
      console.log('[BOOT] Sync init');
      engine = maybeEngine;
      setupEngine();
    }
  } catch (e) {
    console.error('[BOOT] Exception:', e);
    sendToAndroid('info string ERROR: ' + e);
  }
}

function setupEngine() {
  console.log('[SETUP] Configuring engine...');

  if (!engine) {
    console.error('[SETUP] Engine is null!');
    return;
  }

  engine.onmessage = function (e) {
    const line = (typeof e === 'string') ? e : (e && e.data ? e.data : '');
    if (!line) return;

    console.log('[ENGINE]', line);

    if (!engineReady && (line.indexOf('uciok') !== -1 || line.indexOf('readyok') !== -1)) {
      engineReady = true;
      console.log('[SETUP] Engine ready!');

      // Отправляем очередь
      console.log('[SETUP] Processing queue, length:', inQueue.length);
      while (inQueue.length) {
        const cmd = inQueue.shift();
        console.log('[SETUP] Queued:', cmd);
        engine.postMessage(cmd);
      }
    }

    sendToAndroid(line);
  };

  console.log('[SETUP] Sending uci...');
  engine.postMessage('uci');

  console.log('[SETUP] Sending isready...');
  engine.postMessage('isready');
}

// Запуск после загрузки
console.log('[MAIN] Document state:', document.readyState);

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', function() {
    console.log('[MAIN] DOM ready, booting in 100ms...');
    setTimeout(boot, 100);
  });
} else {
  console.log('[MAIN] Already loaded, booting in 100ms...');
  setTimeout(boot, 100);
}

console.log('[MAIN] main.js loaded');