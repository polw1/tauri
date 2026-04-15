import "./styles.css";
import { WebviewWindow } from "@tauri-apps/api/webviewWindow";
import { availableMonitors, currentMonitor } from "@tauri-apps/api/window";

function parseNumber(value: string, fallback: number) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function getInitialValue(param: string, fallback: number) {
  const params = new URLSearchParams(window.location.search);
  return parseNumber(params.get(param) ?? "", fallback);
}

async function render() {
  const root = document.querySelector<HTMLDivElement>("#app");

  if (!root) {
    return;
  }

  const x = getInitialValue("x", 3840);
  const y = getInitialValue("y", 0);
  const fullscreen = new URLSearchParams(window.location.search).get("fullscreen") !== "false";
  const monitors = await availableMonitors();
  const activeMonitor = await currentMonitor();

  root.innerHTML = `
    <main class="page">
      <div class="controls">
        <label class="axis-field">
          <span>x</span>
          <input id="x-input" type="number" value="${x}" />
        </label>
        <label class="axis-field">
          <span>y</span>
          <input id="y-input" type="number" value="${y}" />
        </label>
        <label class="checkbox">
          <input id="fullscreen-input" type="checkbox" ${fullscreen ? "checked" : ""} />
          fullscreen
        </label>
        <button id="open-window" type="button">Create webview</button>
      </div>
      <section class="monitors">
        <h2>Available monitors</h2>
        ${monitors
          .map((monitor, index) => {
            const isCurrent = activeMonitor?.name === monitor.name && activeMonitor?.position.x === monitor.position.x && activeMonitor?.position.y === monitor.position.y;
            return `
              <div class="monitor">
                <strong>Monitor ${index + 1}${isCurrent ? " (current)" : ""}</strong>
                <span>name: ${monitor.name ?? "unknown"}</span>
                <span>position: x=${monitor.position.x}, y=${monitor.position.y}</span>
                <span>size: ${monitor.size.width}x${monitor.size.height}</span>
                <span>scale: ${monitor.scaleFactor}</span>
              </div>
            `;
          })
          .join("")}
      </section>
    </main>
  `;

  document.querySelector<HTMLButtonElement>("#open-window")?.addEventListener("click", () => {
    const xInput = document.querySelector<HTMLInputElement>("#x-input");
    const yInput = document.querySelector<HTMLInputElement>("#y-input");
    const fullscreenInput = document.querySelector<HTMLInputElement>("#fullscreen-input");

    if (!xInput || !yInput || !fullscreenInput) {
      return;
    }

    const nextX = parseNumber(xInput.value, 3840);
    const nextY = parseNumber(yInput.value, 0);
    const nextFullscreen = fullscreenInput.checked;
    const label = `second-window-${Date.now()}`;

    new WebviewWindow(label, {
      title: "Second Window",
      url: `/index.html?x=${nextX}&y=${nextY}&fullscreen=${nextFullscreen}`,
      x: nextX,
      y: nextY,
      fullscreen: nextFullscreen,
    });
  });
}

void render();
