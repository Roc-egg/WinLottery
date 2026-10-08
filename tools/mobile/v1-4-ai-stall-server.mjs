import net from "node:net";

/** 本机验收服务监听端口。 */
const listenPort = Number.parseInt(process.argv[2] ?? "", 10);

if (!Number.isInteger(listenPort) || listenPort < 1024 || listenPort > 65535) {
  throw new Error("V1.4 本机停滞服务端口无效");
}

/** 当前仍保持连接的验收套接字。 */
const acceptedSockets = new Set();

/**
 * 只接受 TCP 连接但不回应 TLS 握手，使客户端稳定停留在可取消状态。
 *
 * 请求头、密钥和历史数据都不会在 TLS 建立前发送到该服务。
 */
const server = net.createServer((socket) => {
  acceptedSockets.add(socket);
  socket.on("close", () => acceptedSockets.delete(socket));
  socket.on("error", () => acceptedSockets.delete(socket));
});

/** 关闭全部验收连接和监听端口。 */
function stopServer() {
  for (const socket of acceptedSockets) socket.destroy();
  server.close(() => process.exit(0));
}

server.on("error", (error) => {
  process.stderr.write(`V1.4 本机停滞服务失败：${error.message}\n`);
  process.exitCode = 1;
});
server.listen(listenPort, "0.0.0.0", () => {
  process.stdout.write(`V1.4 本机停滞服务已监听 ${listenPort}\n`);
});
process.on("SIGTERM", stopServer);
process.on("SIGINT", stopServer);
