// src/services/uci.ts
import { spawn, ChildProcessWithoutNullStreams } from "node:child_process";
import * as readline from "node:readline";
import { once } from "node:events";

export type UciOptions = {
  /** Анализ несколькими вариантами */
  multiPv?: number;
  /** Количество потоков */
  Threads?: number;
  /** Размер кеша (MB) */
  Hash?: number;
  /** Путь к таблицам Сизиги */
  SyzygyPath?: string;
  /** Путь к NNUE (если движок поддерживает) */
  EvalFile?: string;
  /** Включить режим анализа (обычно UCI_AnalyseMode = true) */
  UCI_AnalyseMode?: boolean;
  /** Любые дополнительные UCI-опции по имени */
  [name: string]: string | number | boolean | undefined;
};

export type GoParams = {
  /** Глубина поиска */
  depth?: number;
  /** Время на позицию (ms) */
  movetime?: number;
  /** Лимит по узлам */
  nodes?: number;
  /** Поиск мата в N */
  mate?: number;
  /** Кол-во вариантов (перекрывает установленный в опциях MultiPV) */
  multiPv?: number;
  /** Жёсткий таймаут выполнения go (ms), по умолчанию берётся из movetime*2 либо 30s */
  timeoutMs?: number;
};

export type GoResult = {
  lines: string[];       // все info-строки плюс bestmove
  bestmove: string;      // ход bestmove
  ponder?: string;       // ход ponder (если был)
};

const DEFAULT_READY_TIMEOUT = 10_000;
const DEFAULT_GO_TIMEOUT   = 30_000;

function buildSetOption(name: string, value: string | number | boolean) {
  return `setoption name ${name} value ${String(value)}`;
}

function safeKill(proc: ChildProcessWithoutNullStreams) {
  try {
    if (!proc.killed) proc.kill();
  } catch { /* noop */ }
}

/**
 * Класс-обёртка для взаимодействия с UCI-движком.
 * Поддерживает строго-очередное выполнение команд isready/go и сбор телеметрии.
 */
export class UciEngine {
  private proc!: ChildProcessWithoutNullStreams;
  private rl?: readline.Interface;

  private uciOk = false;
  private isQuitting = false;

  private pendingReadyResolvers: Array<() => void> = [];

  // Активный поиск (go) — в UCI допустим ровно один одновременно
  private activeGo:
    | {
        resolve: (r: GoResult) => void;
        reject: (e: unknown) => void;
        timeout?: NodeJS.Timeout;
        lines: string[];
      }
    | undefined;

  constructor(
    private readonly path: string,
    private readonly options: UciOptions = {},
    private readonly readyTimeoutMs: number = DEFAULT_READY_TIMEOUT
  ) {}

  /**
   * Запуск процесса, uci-handshake, установка всех опций и isready.
   */
  async start(): Promise<void> {
    if (this.proc) return;

    this.proc = spawn(this.path, [], {
      stdio: ["pipe", "pipe", "pipe"],
      windowsHide: true,
    });

    // Прокинем вывод на всякий случай в консоль с префиксом
    this.proc.stderr?.on("data", (buf) => {
      const s = String(buf);
      // eslint-disable-next-line no-console
      console.error(`[uci][stderr] ${s.trimEnd()}`);
    });

    this.rl = readline.createInterface({ input: this.proc.stdout });
    this.rl.on("line", (line) => this.onLine(line));

    this.send("uci");

    // Ожидаем uciok
    await this.waitFor(() => this.uciOk, this.readyTimeoutMs, "uci handshake timeout");

    // Установим режим анализа по умолчанию, если не передан
    if (this.options.UCI_AnalyseMode === undefined) {
      this.send(buildSetOption("UCI_AnalyseMode", true));
    }

    // Нормализуем распространённые алиасы, если их передали в «человеческом» виде
    const normalized: Record<string, string | number | boolean> = {};
    for (const [k, v] of Object.entries(this.options)) {
      if (v === undefined) continue;
      switch (k) {
        case "threads":
        case "THREADS":
          normalized["Threads"] = Number(v);
          break;
        case "hash":
        case "hashMB":
        case "HASH":
          normalized["Hash"] = Number(v);
          break;
        case "multiPv":
        case "multipv":
          normalized["MultiPV"] = Number(v);
          break;
        case "syzygy":
        case "syzygyPath":
          normalized["SyzygyPath"] = String(v);
          break;
        case "nnuePath":
        case "evalFile":
          normalized["EvalFile"] = String(v);
          break;
        default:
          normalized[k] = v as any;
      }
    }

    // Применяем все опции
    for (const [name, value] of Object.entries(normalized)) {
      this.send(buildSetOption(name, value));
    }

    await this.isReady();
  }

  /**
   * Сигнал новой партии.
   */
  async newGame(): Promise<void> {
    this.send("ucinewgame");
    await this.isReady();
  }

  /**
   * Гарантирует готовность движка (isready/readyok).
   */
  async isReady(): Promise<void> {
    await new Promise<void>((resolve, reject) => {
      const timer = setTimeout(
        () => reject(new Error("isready timeout")),
        this.readyTimeoutMs
      );
      this.pendingReadyResolvers.push(() => {
        clearTimeout(timer);
        resolve();
      });
      this.send("isready");
    });
  }

  /**
   * Установка позиции по FEN и (опционально) списку ходов UCI.
   */
  position(fen: string, moves?: string[]): void {
    const suffix = moves?.length ? ` moves ${moves.join(" ")}` : "";
    this.send(`position fen ${fen}${suffix}`);
  }

  /**
   * Запуск поиска. Возвращает все info-строки и bestmove.
   * Параллельный вызов второго go приведёт к исключению (UCI допускает 1 поиск).
   */
  async go(params: GoParams = {}): Promise<GoResult> {
    if (this.activeGo) {
      throw new Error("go is already running");
    }

    const cmd: string[] = ["go"];

    if (params.depth !== undefined) cmd.push("depth", String(params.depth));
    if (params.movetime !== undefined) cmd.push("movetime", String(params.movetime));
    if (params.nodes !== undefined) cmd.push("nodes", String(params.nodes));
    if (params.mate !== undefined) cmd.push("mate", String(params.mate));
    if (params.multiPv !== undefined) cmd.push("multiPv", String(params.multiPv));

    const goTimeout =
      params.timeoutMs ??
      (params.movetime ? Math.max(params.movetime * 2, 5_000) : DEFAULT_GO_TIMEOUT);

    const result = await new Promise<GoResult>((resolve, reject) => {
      const state = {
        resolve,
        reject,
        lines: [] as string[],
        timeout: setTimeout(() => {
          // Страховочный таймаут: останавливаем поиск и завершаем чем есть
          try {
            this.send("stop");
          } catch { /* noop */ }
          reject(new Error("go timeout"));
        }, goTimeout),
      };
      this.activeGo = state;
      this.send(cmd.join(" "));
    });

    return result;
  }

  /**
   * Корректное завершение процесса.
   */
  async quit(): Promise<void> {
    if (!this.proc || this.isQuitting) return;
    this.isQuitting = true;

    // Попробуем корректно
    try {
      this.send("quit");
    } catch { /* ignore */ }

    // Ждём выхода немного, потом рубим
    const EXIT_TIMEOUT = 2_000;
    const exitPromise = once(this.proc, "exit").catch(() => {});
    const timeout = setTimeout(() => safeKill(this.proc), EXIT_TIMEOUT);

    await exitPromise;
    clearTimeout(timeout);

    try {
      this.rl?.close();
    } catch { /* ignore */ }
  }

  // ===== Внутренняя кухня =====

  private onLine(lineRaw: string) {
    const line = lineRaw.trim();
    if (!line) return;

    // eslint-disable-next-line no-console
    // console.debug("[uci]", line);

    if (line === "uciok") {
      this.uciOk = true;
      return;
    }

    if (line === "readyok") {
      const resolvers = this.pendingReadyResolvers.splice(0);
      resolvers.forEach((r) => r());
      return;
    }

    // Сбор телеметрии поиска
    if (this.activeGo) {
      if (line.startsWith("info ")) {
        this.activeGo.lines.push(line);
        return;
      }

      if (line.startsWith("bestmove")) {
        this.activeGo.lines.push(line);

        const parts = line.split(/\s+/);
        const bestmove = parts[1] ?? "";
        const ponderIndex = parts.indexOf("ponder");
        const ponder = ponderIndex >= 0 ? parts[ponderIndex + 1] : undefined;

        clearTimeout(this.activeGo.timeout);
        const res: GoResult = { lines: this.activeGo.lines, bestmove, ponder };
        const resolve = this.activeGo.resolve;

        this.activeGo = undefined;
        resolve(res);
        return;
      }
    }
  }

  private send(cmd: string) {
    if (!this.proc || !this.proc.stdin.writable) {
      throw new Error("engine process is not running");
    }
    this.proc.stdin.write(cmd + "\n");
  }

  private async waitFor(cond: () => boolean, timeoutMs: number, msg: string) {
    const start = Date.now();
    while (!cond()) {
      if (Date.now() - start > timeoutMs) {
        throw new Error(msg);
      }
      await new Promise((r) => setTimeout(r, 10));
    }
  }
}
