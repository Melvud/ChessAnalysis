// src/services/analyze.service.ts
import { EnginePool } from "../engine/pool";
import { progressStore, ProgressStage } from "../api/progressStore";
import { parseEvaluationResults } from "../engine/parse";
import { computeAccuracy } from "@/algorithms/accuracy";
import { computeAcpl } from "@/algorithms/acpl";
import { computeEstimatedElo } from "@/algorithms/estimateElo";
import { Chess } from "chess.js";
import { getMovesClassification } from "@/algorithms/moveClassification";

// Импортируем типы для клиента
import type { 
  PositionEval as ClientPositionEval,
  LineEval as ClientLineEval,
  MoveReport,
  AccuracySummary,
  FullReport,
  GameHeader,
  MoveClass
} from "../types/clientReport";

import type {
  PositionEval,
  LineEval
} from "../types/eval";

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

// Классификация ходов определяется функцией getMovesClassification из algorithms/moveClassification

export class AnalyzeService {
  constructor(private readonly pool: EnginePool) {}

  // Анализ одной позиции
  async evaluatePosition(
    fen: string,
    depth: number = 15,
    multiPv: number = 3,
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

      if (onInfo) {
        result.lines.forEach(line => onInfo(line));
      }

      const parsed = parseEvaluationResults(result.lines, fen, multiPv);
      return parsed;
    } finally {
      this.pool.release(engine);
    }
  }

  // Анализ игры по всем FEN'ам и UCI-ходам
  async evaluateGame(input: EvaluateGameInput): Promise<FullReport> {
    const { fens, uciMoves, depth = 14, multiPv = 3, playersRatings } = input;
    
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

    // Создаем классифицированные позиции для определения категорий ходов
    const classifiedPositions = getMovesClassification(positions, uciMoves, fens);

    // Создание отчётов по ходам с правильной классификацией
    const moves: MoveReport[] = [];
    for (let i = 0; i < uciMoves.length; i++) {
      const beforePos = positions[i];
      const afterPos = positions[i + 1];
      if (!afterPos) break;
      
      const winBefore = this.getWinPercentage(beforePos);
      const winAfter = this.getWinPercentage(afterPos);
      
      // Определяем, кто ходил (четный индекс = белые, нечетный = чёрные)
      const isWhiteMove = i % 2 === 0;
      
      // Классификация из getMovesClassification. Берём следующую позицию (после хода)
      const classification = (classifiedPositions[i + 1] as any).moveClassification as MoveClass;
      
      // Конвертируем UCI в SAN
      const chess = new Chess();
      chess.load(fens[i]);
      const fromSquare = uciMoves[i].slice(0, 2);
      const toSquare = uciMoves[i].slice(2, 4);
      const promotion = uciMoves[i][4];
      
      let san = uciMoves[i];
      try {
        const move = chess.move({
          from: fromSquare,
          to: toSquare,
          promotion: promotion as any,
        });
        if (move) san = move.san;
      } catch {
        // если не удалось конвертировать, оставляем UCI
      }
      
      const moveReport: MoveReport = {
        san,
        uci: uciMoves[i],
        beforeFen: fens[i],
        afterFen: fens[i + 1],
        winBefore,
        winAfter,
        accuracy: this.getMoveAccuracy(winBefore, winAfter, isWhiteMove),
        classification,
        tags: [],
      };
      
      moves.push(moveReport);
    }
    
    // Вычисление метрик точности, ACPL и предполагаемого рейтинга
    const accuracy = computeAccuracy(positions);
    const acpl = computeAcpl(positions);
    const estimatedElo = computeEstimatedElo(
      positions, 
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
    
    const clientPositions: ClientPositionEval[] = positions.map((p, idx) => ({
      fen: fens[idx],
      idx,
      lines: p.lines.map(l => ({
        pv: l.pv,
        cp: l.cp,
        mate: l.mate,
        best: p.bestMove
      } as ClientLineEval))
    }));
    
    const report: FullReport = {
      header: {} as GameHeader,
      positions: clientPositions,
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

  // Асинхронный анализ arbitrary массива FEN'ов с поддержкой прогресса
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
    const multiPv = options?.multiPv ?? 3;

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

    // Ждём завершения
    await this.waitForCompletion(progressId);
    
    const result = progressStore.getResult(progressId);
    if (!result) {
      throw new Error("Analysis failed");
    }
    
    return result as FullReport;
  }

  // Внутренняя функция для фонового анализа без блокировки
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
          multiPv: cfg.multiPv ?? 3
        });

        const parsed = parseEvaluationResults(result.lines, fens[i], cfg.multiPv ?? 3);
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

    // Создаём полный отчёт с правильными данными
    const report = await this.evaluateGame({
      fens,
      uciMoves,
      depth: cfg.depth,
      multiPv: cfg.multiPv ?? 3,
      playersRatings: {}
    });
    
    progressStore.setResult(progressId, report);
    progressStore.complete(progressId);
  }

  // Ожидаем завершения фонового анализа
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

  // Преобразует оценку позиции (cp/mate) в вероятность выигрыша текущей стороны
  private getWinPercentage(position: PositionEval): number {
    const line = position.lines?.[0];
    if (!line) return 50;
    
    if (line.cp !== undefined) {
      // Lichess formula for win percentage
      const cpClamped = Math.max(-1000, Math.min(1000, line.cp));
      const winChances = 2 / (1 + Math.exp(-0.00368208 * cpClamped)) - 1;
      return 50 + 50 * winChances;
    }
    
    if (line.mate !== undefined) {
      return line.mate > 0 ? 100 : 0;
    }
    
    return 50;
  }

  // Преобразует разницу win% в точность хода
  private getMoveAccuracy(winBefore: number, winAfter: number, isWhiteMove: boolean): number {
    const winDiff = isWhiteMove 
      ? Math.max(0, winBefore - winAfter)
      : Math.max(0, winAfter - winBefore);
    
    // Lichess accuracy formula
    const rawAccuracy = 103.1668100711649 * Math.exp(-0.04354415386753951 * winDiff) - 3.166924740191411;
    return Math.min(100, Math.max(0, rawAccuracy + 1));
  }
}
