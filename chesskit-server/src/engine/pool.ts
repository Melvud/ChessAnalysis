import { UciEngine, UciOptions } from "./uci";

type PoolCfg = {
  path: string;
  size: number;
  options?: UciOptions;
};

export class EnginePool {
  private engines: UciEngine[] = [];
  private queue: Array<(e: UciEngine) => void> = [];
  private started = false;

  constructor(private readonly cfg: PoolCfg) {}

  async start() {
    if (this.started) return;
    this.started = true;

    for (let i = 0; i < this.cfg.size; i++) {
      const eng = new UciEngine(this.cfg.path, this.cfg.options ?? {});
      await eng.start();
      await eng.newGame();
      this.engines.push(eng);
    }
  }

  async stop() {
    await Promise.all(this.engines.map((e) => e.quit()));
    this.engines = [];
    this.started = false;
  }

  /**
   * Выдаёт движок эксклюзивно, возвращайте через release().
   */
  acquire(): Promise<UciEngine> {
    return new Promise((resolve) => {
      const eng = this.engines.pop();
      if (eng) resolve(eng);
      else this.queue.push(resolve);
    });
  }

  release(eng: UciEngine) {
    const waiter = this.queue.shift();
    if (waiter) waiter(eng);
    else this.engines.push(eng);
  }
}
