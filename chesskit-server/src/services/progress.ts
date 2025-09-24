// Единый стор прогресса, чтобы им пользовались все роуты
export type Progress = {
  total: number;
  done: number;
  stage: string;
  startedAt: number;
  updatedAt: number;
};

const store = new Map<string, Progress>();

export function initProgress(id: string, total: number, stage = "preparing") {
  store.set(id, {
    total,
    done: 0,
    stage,
    startedAt: Date.now(),
    updatedAt: Date.now(),
  });
}

export function updateProgress(id: string, patch: Partial<Progress>) {
  const cur = store.get(id);
  if (!cur) return;
  const next = { ...cur, ...patch, updatedAt: Date.now() };
  store.set(id, next);
}

export function completeProgress(id: string) {
  const cur = store.get(id);
  if (!cur) return;
  store.set(id, { ...cur, stage: "done", done: cur.total, updatedAt: Date.now() });
}

export function getProgress(id: string) {
  return store.get(id);
}
