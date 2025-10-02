(function() {
    function log(msg) {
        console.log('[MAIN] ' + msg);
        if (window.Android && typeof window.Android.onEngineLine === 'function') {
            try {
                window.Android.onEngineLine('info string [MAIN] ' + msg);
            } catch(e) {}
        }
    }

    log('Loaded');

    if (typeof Stockfish !== 'function') {
        log('ERROR: Stockfish is not a function!');
        return;
    }

    log('Creating Stockfish with wasmBinary');

    // Создаем экземпляр Stockfish с правильной конфигурацией
    Stockfish({
        wasmBinary: window.wasmBinary,
        locateFile: function(path) {
            log('locateFile: ' + path);
            return path;
        },
        // КРИТИЧНО: listener для получения вывода от движка
        listener: function(line) {
            // Передаем чистый UCI в Android
            if (window.Android && typeof window.Android.onEngineLine === 'function') {
                try {
                    window.Android.onEngineLine(line);
                } catch(e) {
                    console.error('Error calling Android:', e);
                }
            }
        }
    }).then(function(Module) {
        log('Module ready!');

        // Сохраняем модуль
        var engine = Module;

        // Перенастраиваем мост для работы с готовым движком
        var oldQueue = window.EngineBridge._q.slice();

        window.EngineBridge = {
            engine: engine,
            push: function(cmd) {
                log('→ ' + cmd);
                // Используем ccall для отправки команд
                try {
                    // ccall(name, returnType, argTypes, args, options)
                    this.engine.ccall('command', null, ['string'], [cmd]);
                } catch(e) {
                    log('ERROR sending command: ' + e);
                }
            }
        };

        log('Bridge ready, processing ' + oldQueue.length + ' queued commands');

        // Обрабатываем очередь
        for (var i = 0; i < oldQueue.length; i++) {
            var cmd = oldQueue[i];
            if (cmd && cmd.length > 0) {
                log('Dequeue → ' + cmd);
                window.EngineBridge.push(cmd);
            }
        }

        log('Engine ready');

        // Сигнал готовности в Android
        if (window.Android && typeof window.Android.onEngineLine === 'function') {
            try {
                window.Android.onEngineLine('ENGINE_READY');
            } catch(e) {}
        }

        log('Engine ready signal sent');

    }).catch(function(err) {
        log('ERROR creating module: ' + err);
        console.error('Module creation error:', err);
    });
})();