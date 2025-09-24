// src/services/analyze.service.ts
import { EnginePool } from "../engine/pool";
import { UciEngine } from "../engine/uci";
import { progressStore, ProgressStage } from "../api/progressStore";
import { parseEvaluationResults } from "../engine/parse";
import { computeAccuracy } from "../algorithms/accuracy";
import { computeAcpl } from "../algorithms/acpl";
import { computeEstimatedElo } from "../algorithms/estimateElo";
import { getMovesClassification } from "../algorithms/moveClassification";
import { 
  PositionEval, 
  LineEval, 
  GameEval,
  MoveReport,
  AccuracySummary,
  FullReport,
  GameHeader
} from "../types/clientReport";
import { Chess } from "chess.js";

export type EvaluateOptions = {
  depth?: number;
  movetimeMs?: number;
  multiPv?: number;
};

export type EvaluateFensInput = {
  fens: string[];
  uciMoves?: string[];
};

export type EvaluateGameInput = {
  fens: string[];
  uciMoves: string[];
  depth?: number;
  multiPv?: number;
  playersRatings?: { white?: number; black?: number };
};

function now() {
  return Date.now();
}

export class AnalyzeService {
  constructor(private readonly pool: EnginePool) {}

  /**
   * Анализ одной позиции
   */
  async evaluatePosition(
    fen: string,
    depth: number = 14,
    multiPv: number = 1,
    onInfo?: (line: string) => void
  ): Promise<PositionEval> {
    const engine = await this.pool.acquire();
    try {
      await engine.newGame();
      engine.position(fen);
      
      const result = await engine.go({
        depth,
        multiPv,
        movetime: undefined
      });

      // Отправляем info строки если есть колбэк
      if (onInfo) {
        result.lines.forEach(line => onInfo(line));
      }

      // Парсим результаты
      const parsed = parseEvaluationResults(result.lines, fen, multiPv);
      return parsed;
    } finally {
      this.pool.release(engine);
    }
  }

  /**
   * Анализ полной партии с классификацией ходов
   */
  async evaluateGame(input: EvaluateGameInput): Promise<FullReport> {
    const { fens, uciMoves, depth = 14, multiPv = 1, playersRatings } = input;
    
    const positions: PositionEval[] = [];
    const analysisLog: string[] = [];
    
    const engine = await this.pool.acquire();
    try {
      await engine.newGame();
      
      // Анализируем каждую позицию
      for (let i = 0; i < fens.length; i++) {
        const fen = fens[i];
        engine.position(fen);
        
        const result = await engine.go({
          depth,
          multiPv,
          movetime: undefined
        });
        
        const parsed = parseEvaluationResults(result.lines, fen, multiPv);
        positions.push(parsed);
        
        analysisLog.push(`Position ${i}/${fens.length} analyzed`);
      }
    } finally {
      this.pool.release(engine);
    }

    // Классификация ходов
    const classifiedPositions = getMovesClassification(positions, uciMoves, fens);
    
    // Создание отчетов по ходам
    const moves: MoveReport[] = [];
    for (let i = 0; i < uciMoves.length; i++) {
      const beforePos = classifiedPositions[i];
      const afterPos = classifiedPositions[i + 1];
      if (!afterPos) break;
      
      const winBefore = this.getWinPercentage(beforePos);
      const winAfter = this.getWinPercentage(afterPos);
      
      // Конвертируем UCI в SAN
      const chess = new Chess();
      chess.load(beforePos.fen || fens[i]);
      const move = chess.move({ 
        from: uciMoves[i].slice(0, 2), 
        to: uciMoves[i].slice(2, 4),
        promotion: uciMoves[i][4] 
      });
      
      const moveReport: MoveReport = {
        san: move?.san || uciMoves[i],
        uci: uciMoves[i],
        beforeFen: beforePos.fen || fens[i],
        afterFen: afterPos.fen || fens[i + 1],
        winBefore,
        winAfter,
        accuracy: this.getMoveAccuracy(winBefore, winAfter, i % 2 === 0),
        classification: afterPos.moveClassification || "OKAY",
        tags: []
      };
      
      moves.push(moveReport);
    }
    
    // Вычисление метрик
    const accuracy = computeAccuracy(classifiedPositions);
    const acpl = computeAcpl(classifiedPositions);
    const estimatedElo = computeEstimatedElo(
      classifiedPositions, 
      playersRatings?.white, 
      playersRatings?.black
    );
    
    // Формируем финальную точность  
    const accuracySummary: AccuracySummary = {
      whiteMovesAcc: {
        itera: accuracy.white,
        harmonic: accuracy.white,
        weighted: accuracy.white
      },
      blackMovesAcc: {
        itera: accuracy.black,
        harmonic: accuracy.black, 
        weighted: accuracy.black
      }
    };
    
    const report: FullReport = {
      header: {} as GameHeader, // Будет заполнен в роуте
      positions: classifiedPositions.map((p, idx) => ({
        fen: p.fen || fens[idx],
        idx,
        lines: p.lines.map(l => ({
          pv: l.pv,
          cp: l.cp,
          mate: l.mate,
          best: p.bestMove
        }))
      })),
      moves,
      accuracy: accuracySummary,
      acpl,
      estimatedElo: {
        whiteEst: estimatedElo?.white ? Math.round(estimatedElo.white) : undefined,
        blackEst: estimatedElo?.black ? Math.round(estimatedElo.black) : undefined
      },
      analysisLog
    };
    
    return report;
  }

  /**
   * Анализ массива FEN с прогрессом
   */
  async evaluateFens(
    progressId: string,
    payload: EvaluateFensInput,
    options?: EvaluateOptions
  ): Promise<FullReport> {
    const fens = payload?.fens ?? [];
    const uciMoves = payload?.uciMoves ?? [];
    
    if (!Array.isArray(fens) || fens.length === 0) {
      throw new Error("fens is required and must be a non-empty array");
    }

    const depth = options?.depth ?? 14;
    const movetime = options?.movetimeMs;
    const multiPv = options?.multiPv ?? 1;

    // Создаём прогресс
    progressStore.createOrReset(progressId, {
      total: fens.length,
      done: 0,
      percent: 0,
      stage: "evaluating" as ProgressStage,
      startedAt: now(),
      updatedAt: now(),
      meta: { depth, movetime, multiPv }
    });

    // Запускаем анализ в фоне
    setImmediate(() => {
      this.runEvaluation(progressId, fens, uciMoves, { depth, movetime, multiPv })
        .catch((err) => {
          progressStore.fail(progressId, "analyze_failed", err?.message || String(err));
        });
    });

    // Ждем завершения анализа
    await this.waitForCompletion(progressId);
    
    // Получаем результат из хранилища
    const result = progressStore.getResult(progressId);
    if (!result) {
      throw new Error("Analysis failed");
    }
    
    return result as FullReport;
  }

  private async runEvaluation(
    progressId: string,
    fens: string[],
    uciMoves: string[],
    cfg: { depth?: number; movetime?: number; multiPv?: number }
  ) {
    const positions: PositionEval[] = [];
    const engine = await this.pool.acquire();
    
    try {
      await engine.newGame();
      
      for (let i = 0; i < fens.length; i++) {
        const current = progressStore.get(progressId);
        if (!current || current.stage === "canceled") {
          return;
        }

        engine.position(fens[i]);
        const result = await engine.go({
          depth: cfg.depth,
          movetime: cfg.movetime,
          multiPv: cfg.multiPv ?? 1
        });

        const parsed = parseEvaluationResults(result.lines, fens[i], cfg.multiPv ?? 1);
        positions.push(parsed);

        progressStore.update(progressId, {
          done: i + 1,
          percent: Math.floor(((i + 1) * 100) / fens.length),
          updatedAt: now()
        });
      }
    } finally {
      this.pool.release(engine);
    }

    // Создаем полный отчет
    const report = await this.createFullReport(fens, uciMoves, positions, cfg);
    
    // Сохраняем результат и завершаем
    progressStore.setResult(progressId, report);
    progressStore.complete(progressId);
  }

  private async createFullReport(
    fens: string[],
    uciMoves: string[],
    positions: PositionEval[],
    cfg: { depth?: number; multiPv?: number }
  ): Promise<FullReport> {
    // Используем метод evaluateGame для полного анализа
    return this.evaluateGame({
      fens,
      uciMoves,
      depth: cfg.depth,
      multiPv: cfg.multiPv,
      playersRatings: {}
    });
  }

  private async waitForCompletion(progressId: string, timeoutMs: number = 300000): Promise<void> {
    const startTime = Date.now();
    
    while (true) {
      const progress = progressStore.get(progressId);
      
      if (!progress) {
        throw new Error("Progress not found");
      }
      
      if (progress.stage === "done") {
        return;
      }
      
      if (progress.stage === "error") {
        throw new Error(progress.error || "Analysis failed");
      }
      
      if (Date.now() - startTime > timeoutMs) {
        throw new Error("Analysis timeout");
      }
      
      await new Promise(resolve => setTimeout(resolve, 100));
    }
  }

  private getWinPercentage(position: PositionEval): number {
    const line = position.lines?.[0];
    if (!line) return 50;
    
    if (line.cp !== undefined) {
      const cpClamped = Math.max(-1000, Math.min(1000, line.cp));
      const winChances = 2 / (1 + Math.exp(-0.00368208 * cpClamped)) - 1;
      return 50 + 50 * winChances;
    }
    
    if (line.mate !== undefined) {
      return line.mate > 0 ? 100 : 0;
    }
    
    return 50;
  }

  private getMoveAccuracy(winBefore: number, winAfter: number, isWhiteMove: boolean): number {
    const winDiff = isWhiteMove 
      ? Math.max(0, winBefore - winAfter)
      : Math.max(0, winAfter - winBefore);
    
    const rawAccuracy = 103.1668100711649 * Math.exp(-0.04354415386753951 * winDiff) - 3.166924740191411;
    return Math.min(100, Math.max(0, rawAccuracy + 1));
  }
}