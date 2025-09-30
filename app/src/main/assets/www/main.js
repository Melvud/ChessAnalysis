// Очереди для обмена между Java/Kotlin и движком
const inQueue = [];
let engine = null;
let engineReady = false;

// Простая подписка Android <-> JS через window.EngineBridge.*
// Android будет вызывать window.EngineBridge.push(cmd)
window.EngineBridge = {
  push: (cmd) => {
    if (!cmd || typeof cmd !== 'string') return;
    if (engineReady) {
      engine.postMessage(cmd);
    } else {
      inQueue.push(cmd);
    }
  }
};

// Сообщать строки обратно в Android будем так:
function sendToAndroid(line) {
  try {
    if (window.Android && typeof window.Android.onEngineLine === 'function') {
      window.Android.onEngineLine(line);
    } else if (window.Android && window.Android.onEngineLine) {
      window.Android.onEngineLine(line);
    }
  } catch (e) {
    console.log('Android callback error:', e);
  }
}

// Загружаем wasm‑движок (stockfish.js создаёт Worker‑подобный интерфейс).
// В версии 17+ функция Stockfish может синхронно возвращать объект
// либо Promise, поэтому поддерживаем обе формы.
function boot() {
  try {
    const maybeEngine = Stockfish();
    if (maybeEngine && typeof maybeEngine.then === 'function') {
      // Асинхронная инициализация
      maybeEngine.then((inst) => {
        engine = inst;
        setupEngine();
      }).catch((err) => {
        console.error('Stockfish init error:', err);
        sendToAndroid('info string stockfish init error: ' + ('' + err));
      });
    } else {
      // Синхронная версия
      engine = maybeEngine;
      setupEngine();
    }
  } catch (e) {
    console.error('Stockfish init error:', e);
    sendToAndroid('info string stockfish init error: ' + ('' + e));
  }
}

// Настройка слушателей и отправка первичных команд UCI
function setupEngine() {
  if (!engine) return;
  engine.onmessage = function (e) {
    const line = (typeof e === 'string') ? e : (e && e.data ? e.data : '');
    if (!line) return;

    // Первый uciok/readyok — признак готовности
    if (!engineReady && (line.indexOf('uciok') !== -1 || line.indexOf('readyok') !== -1)) {
      engineReady = true;
      // Сливаем очередь команд, которые пришли до инициализации
      while (inQueue.length) {
        engine.postMessage(inQueue.shift());
      }
    }

    sendToAndroid(line);
  };

  // Стартовая инициализация UCI
  engine.postMessage('uci');
  // Можно сразу запросить isready, чтобы быстрее перейти в ready
  engine.postMessage('isready');
}

boot();

// На всякий случай: приём log’ов из движка, если glue их пишет в stdout
if (typeof console !== 'undefined') {
  const origLog = console.log;
  console.log = function () {
    try {
      const msg = Array.from(arguments).join(' ');
      sendToAndroid('info string jslog ' + msg);
    } catch (e) {}
    origLog.apply(console, arguments);
  };
}
