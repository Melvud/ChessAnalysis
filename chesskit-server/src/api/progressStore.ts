// src/api/progressStore.ts
export type ProgressStage = "idle" | "evaluating" | "done" | "error" | "canceled";

export type Progress = {
  id: string;
  total: number;
  done: number;
  percent: number;
  stage: ProgressStage;
  startedAt?: number;
  updatedAt?: number;
  etaMs?: number;
  error?: string;
  meta?: any;
  result?: any;
};

class ProgressStore {
  private map = new Map<string, Progress>();

  createOrReset(id: string, initial: Partial<Progress>): Progress {
    const now = Date.now();
    const p: Progress = {
      id,
      total: initial.total || 0,
      done: initial.done || 0,
      percent: initial.percent || 0,
      stage: initial.stage || "evaluating",
      startedAt: initial.startedAt || now,
      updatedAt: initial.updatedAt || now,
      meta: initial.meta,
      ...initial
    };
    this.map.set(id, p);
    return p;
  }

  get(id: string): Progress | undefined {
    return this.map.get(id);
  }

  update(id: string, updates: Partial<Progress>): void {
    const p = this.map.get(id);
    if (!p) return;
    
    Object.assign(p, updates);
    p.updatedAt = Date.now();
    
    // Вычисляем ETA
    if (p.done > 0 && p.startedAt) {
      const elapsed = Date.now() - p.startedAt;
      const avgTimePerItem = elapsed / p.done;
      const remaining = p.total - p.done;
      p.etaMs = Math.max(0, Math.round(avgTimePerItem * remaining));
    }
  }

  complete(id: string): void {
    const p = this.map.get(id);
    if (!p) return;
    p.done = p.total;
    p.percent = 100;
    p.stage = "done";
    p.etaMs = 0;
    p.updatedAt = Date.now();
  }

  // Алиас для обратной совместимости
  finish(id: string): void {
    this.complete(id);
  }

  fail(id: string, code: string, msg: string): void {
    const p = this.map.get(id);
    if (!p) {
      this.map.set(id, {
        id,
        total: 0,
        done: 0,
        percent: 0,
        stage: "error",
        error: `${code}: ${msg}`,
        updatedAt: Date.now()
      });
    } else {
      p.stage = "error";
      p.error = `${code}: ${msg}`;
      p.updatedAt = Date.now();
    }
  }

  setResult(id: string, result: any): void {
    const p = this.map.get(id);
    if (p) {
      p.result = result;
    }
  }

  getResult(id: string): any {
    return this.map.get(id)?.result;
  }
}

export const progressStore = new ProgressStore();
export default progressStore;