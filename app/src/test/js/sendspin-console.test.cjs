const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const { test } = require("node:test");

const html = fs.readFileSync(path.resolve(__dirname, "../../main/java/com/zqc/tvnotify/WebConsoleHtml.kt"), "utf8");
const script = html.slice(html.indexOf("let sendspinStatus = null;"), html.indexOf("setInterval(fetchSendspinStatus, 2500);"));
assert(script.includes("function saveOutputDelay()"), "Tests must execute the actual console implementation");

function harness() {
  const elements = new Map();
  function element(id) {
    if (!elements.has(id)) {
      const listeners = new Map();
      elements.set(id, {
        value: id === "sendspin-sync-mode" ? "auto" : "",
        disabled: id === "sendspin-delay",
        textContent: "",
        addEventListener(name, handler) {
          if (!listeners.has(name)) listeners.set(name, []);
          listeners.get(name).push(handler);
        },
        emit(name, value) {
          this.value = value;
          for (const listener of listeners.get(name) || []) listener();
        },
      });
    }
    return elements.get(id);
  }
  const requests = [];
  const toasts = [];
  let getStatus = { sync_mode: "auto", manual_output_delay_ms: 850, output_delay_ms: 0 };
  let postResponse = { httpOk: true, body: { ok: true }, status: 200 };
  let deferredPost = null;
  const context = vm.createContext({
    SENDSPIN_URL: "/token/sendspin",
    document: { getElementById: element, activeElement: null },
    toast: message => toasts.push(message),
    setTimeout: () => {},
    fetch: async (_, options = {}) => {
      requests.push(options);
      if (options.method === "POST") {
        if (deferredPost) return deferredPost;
        return { ok: postResponse.httpOk, status: postResponse.status, json: async () => postResponse.body };
      }
      return { ok: true, status: 200, json: async () => getStatus };
    },
  });
  vm.runInContext(script, context);
  return {
    element, requests, toasts, context,
    run: expression => vm.runInContext(expression, context),
    status: value => { getStatus = value; },
    response: (httpOk, body, status = 500) => { postResponse = { httpOk, body, status }; },
    defer: value => { deferredPost = value; },
  };
}

const flush = () => new Promise(resolve => setImmediate(resolve));

test("automatic mode displays the saved manual compensation and disables editing", async () => {
  const app = harness();
  await app.context.fetchSendspinStatus();
  assert.equal(app.element("sendspin-sync-mode").value, "auto");
  assert.equal(app.element("sendspin-delay").value, 850);
  assert.equal(app.element("sendspin-delay").disabled, true);
});

test("polling preserves dirty mode, compensation, and address after focus leaves", async () => {
  const app = harness();
  await app.context.fetchSendspinStatus();
  app.element("sendspin-sync-mode").emit("change", "manual");
  app.element("sendspin-delay").emit("input", "1234");
  app.element("sendspin-url").emit("input", "ws://edited:8927/sendspin");
  await app.context.fetchSendspinStatus();
  assert.equal(app.element("sendspin-sync-mode").value, "manual");
  assert.equal(app.element("sendspin-delay").value, "1234");
  assert.equal(app.element("sendspin-delay").disabled, false);
  assert.equal(app.element("sendspin-url").value, "ws://edited:8927/sendspin");
});

test("manual validation rejects empty, partial, fractional, and out of range values", async () => {
  for (const value of ["", " ", "12ms", "1.5", "1e3", "-1", "5001", "Infinity", "99999999999999999999"]) {
    const app = harness();
    app.element("sendspin-sync-mode").emit("change", "manual");
    app.element("sendspin-delay").emit("input", value);
    await app.context.saveOutputDelay();
    assert.equal(app.requests.length, 0, value);
    assert.match(app.toasts.at(-1), /0–5000/);
    assert.equal(app.element("sendspin-delay").value, value);
  }
});

test("manual range boundaries send exact values and automatic mode never overwrites delay", async () => {
  for (const value of ["0", "5000"]) {
    const app = harness();
    app.element("sendspin-sync-mode").emit("change", "manual");
    app.element("sendspin-delay").emit("input", value);
    await app.context.saveOutputDelay();
    assert.deepEqual(JSON.parse(app.requests[0].body), { action: "config", sync_mode: "manual", output_delay_ms: Number(value) });
  }
  const app = harness();
  app.element("sendspin-delay").value = "850";
  await app.context.saveOutputDelay();
  assert.deepEqual(JSON.parse(app.requests[0].body), { action: "config", sync_mode: "auto" });
});

test("failed HTTP or rejected saves retain edits and never announce success", async () => {
  for (const [httpOk, body] of [[false, { ok: true }], [true, { ok: false, error: "rejected" }]]) {
    const app = harness();
    app.element("sendspin-sync-mode").emit("change", "manual");
    app.element("sendspin-delay").emit("input", "1234");
    app.response(httpOk, body);
    await app.context.saveOutputDelay();
    assert.match(app.toasts.at(-1), /保存失败/);
    assert.equal(app.run("sendspinSyncDirty"), true);
    assert.equal(app.element("sendspin-sync-apply").disabled, false);
    await app.context.fetchSendspinStatus();
    assert.equal(app.element("sendspin-delay").value, "1234");
  }
});

test("edits made during an in-flight save remain dirty after success", async () => {
  const app = harness();
  let complete;
  app.defer(new Promise(resolve => { complete = resolve; }));
  app.element("sendspin-sync-mode").emit("change", "manual");
  app.element("sendspin-delay").emit("input", "1000");
  const saving = app.context.saveOutputDelay();
  app.element("sendspin-delay").emit("input", "1100");
  complete({ ok: true, status: 200, json: async () => ({ ok: true }) });
  await saving;
  await flush();
  assert.equal(app.run("sendspinSyncDirty"), true);
  assert.equal(app.element("sendspin-delay").value, "1100");
});

test("background suspension is shown as local-only rather than playback failure", () => {
  const app = harness();
  const text = app.context.syncStatusText({ playing: true, output_playing: false, output_suspended: true, clock_ready: true, sync_state: "background_suspended" });
  assert.match(text, /后台.*本机.*挂起/);
  assert.match(text, /同步组.*继续/);
  assert.doesNotMatch(text, /异常|偏差|可靠/);
});

test("source labels distinguish reliable playback position from estimates and unavailable", () => {
  const app = harness();
  const status = { clock_ready: true, sync_state: "playing", resync_count: 2, underrun_count: 3 };
  assert.match(app.context.syncStatusText({ ...status, position_source: "timestamp", sync_error_ms: 12.5 }), /可靠时间戳.*电视偏晚 12.5 ms.*重缓冲 2 \/ 欠载 3/);
  assert.match(app.context.syncStatusText({ ...status, position_source: "playback_head", sync_error_ms: -2 }), /可靠播放位置.*电视偏早 2.0 ms/);
  const estimate = app.context.syncStatusText({ ...status, sync_state: "estimated", position_source: "estimate", sync_error_ms: 0 });
  assert.match(estimate, /估算同步.*估算位置/);
  assert.doesNotMatch(estimate, /可靠|已同步/);
  assert.match(app.context.syncStatusText({ ...status, position_source: "unavailable", sync_error_ms: null }), /位置不可用.*偏差暂不可用/);
});

test("mobile layout CSS prevents flex/grid overflow and keeps tap targets usable", () => {
  assert.match(html, /viewport-fit=cover/);
  assert.match(html, /min-width:\s*0/);
  assert.match(html, /minmax\(0,\s*1fr\)/);
  assert.match(html, /\.sendspin-controls\s*\{/);
  assert.match(html, /class="sendspin-controls"/);
  assert.match(html, /flex-wrap:\s*wrap/);
  assert.match(html, /font-size:\s*16px/);
  assert.match(html, /\.code-header\s*\{/);
  assert.match(html, /class="code-header"/);
  assert.match(html, /text-overflow:\s*ellipsis/);
  assert.doesNotMatch(html, /\.copy-btn\s*\{[^}]*position:\s*absolute/);
});

test("console exposes minecraft pixel theme option and metadata", () => {
  assert.match(html, /body\[data-theme="minecraft"\]/);
  assert.equal((html.match(/<option value="minecraft"[\s>]/g) || []).length, 2);
  assert.match(html, /minecraft:\s*\{\s*emoji:\s*"🧱",\s*name:\s*"方块世界",\s*classical:\s*false\s*\}/);
});
