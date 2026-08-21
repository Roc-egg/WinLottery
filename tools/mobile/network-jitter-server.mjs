import { writeFileSync } from "node:fs";
import { createServer } from "node:http";

/** 本地服务固定监听地址，避免暴露到局域网。 */
const LOOPBACK_HOST = "127.0.0.1";

/** 延迟场景的固定等待时长。 */
const DELAY_MILLIS = 800;

/** 允许的匿名会话标识格式。 */
const TOKEN_PATTERN = /^[a-z0-9-]{8,80}$/;

/** 启动后写入实际端口的临时文件。 */
const portFile = process.argv[2];

if (!portFile) {
  throw new Error("必须提供端口输出文件");
}

/** 各匿名会话的请求计数。 */
const countersByToken = new Map();

/** 返回指定匿名会话的计数器。 */
function getCounters(token) {
  let counters = countersByToken.get(token);
  if (!counters) {
    counters = { retry: 0, drop: 0, delay: 0 };
    countersByToken.set(token, counters);
  }
  return counters;
}

/** 写入禁止缓存的 JSON 响应。 */
function writeJson(response, statusCode, body) {
  const payload = JSON.stringify(body);
  response.writeHead(statusCode, {
    "Cache-Control": "no-store",
    "Content-Length": Buffer.byteLength(payload),
    "Content-Type": "application/json; charset=utf-8",
  });
  response.end(payload);
}

/** 处理健康检查和三种受控网络抖动场景。 */
const server = createServer((request, response) => {
  const url = new URL(request.url ?? "/", `http://${LOOPBACK_HOST}`);
  if (request.method === "GET" && url.pathname === "/health") {
    writeJson(response, 200, { ready: true });
    return;
  }

  const [, action, token, extraSegment] = url.pathname.split("/");
  if (
    request.method !== "GET" ||
    extraSegment !== undefined ||
    !TOKEN_PATTERN.test(token ?? "")
  ) {
    writeJson(response, 404, { error: "not_found" });
    return;
  }

  const counters = getCounters(token);
  if (action === "retry") {
    counters.retry += 1;
    writeJson(response, counters.retry === 1 ? 503 : 200, { ok: counters.retry > 1 });
    return;
  }
  if (action === "drop") {
    counters.drop += 1;
    if (counters.drop === 1) {
      request.socket.destroy();
      return;
    }
    writeJson(response, 200, { ok: true });
    return;
  }
  if (action === "delay") {
    counters.delay += 1;
    setTimeout(() => writeJson(response, 200, { ok: true }), DELAY_MILLIS);
    return;
  }
  if (action === "stats") {
    writeJson(response, 200, counters);
    return;
  }
  writeJson(response, 404, { error: "not_found" });
});

/** 关闭本地服务并结束进程。 */
function stopServer() {
  server.close(() => process.exit(0));
}

server.on("error", (error) => {
  process.stderr.write(`本地网络抖动服务失败：${error.message}\n`);
  process.exit(1);
});
server.listen(0, LOOPBACK_HOST, () => {
  const address = server.address();
  if (!address || typeof address === "string") {
    throw new Error("无法读取本地服务端口");
  }
  writeFileSync(portFile, `${address.port}\n`, { encoding: "utf8", flag: "wx" });
});

process.on("SIGINT", stopServer);
process.on("SIGTERM", stopServer);
