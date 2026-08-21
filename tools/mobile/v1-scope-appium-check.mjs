/** Appium 服务根地址。 */
const appiumBaseUrl = process.argv[2];

/** Android 会话参数。 */
const androidConfig = {
  udid: process.argv[3],
  deviceName: process.argv[4],
  packageName: process.argv[5],
  activityName: process.argv[6],
};

/** iOS 会话参数。 */
const iosConfig = {
  udid: process.argv[7],
  deviceName: process.argv[8],
  platformVersion: process.argv[9],
  bundleId: process.argv[10],
};

/** 关于页必须能够访问的稳定文案片段。 */
const REQUIRED_SCOPE_TEXTS = [
  "关于与隐私",
  "V1 支持范围",
  "1 至 20 期受控连续投注",
  "超过 20 期",
  "跨年度未知期次",
  "中奖测算不等于彩票验真",
];

/** 已废弃且不得再次出现的范围描述。 */
const OBSOLETE_SCOPE_TEXT = "不支持复式、胆拖、多期";

/** 发起 WebDriver 请求并统一解析错误。 */
async function webdriverRequest(path, method = "GET", body) {
  const response = await fetch(`${appiumBaseUrl}${path}`, {
    method,
    headers: body === undefined ? undefined : { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const payload = await response.json();
  if (!response.ok || payload.value?.error) {
    const message = payload.value?.message ?? `HTTP ${response.status}`;
    throw new Error(`Appium 请求失败：${message}`);
  }
  if (path === "/session" && method === "POST") {
    return {
      ...payload.value,
      sessionId: payload.value?.sessionId ?? payload.sessionId,
    };
  }
  return payload.value;
}

/** 创建指定平台的 Appium 会话。 */
async function createSession(capabilities) {
  const value = await webdriverRequest("/session", "POST", {
    capabilities: { alwaysMatch: capabilities },
  });
  if (!value?.sessionId) {
    throw new Error("Appium 未返回会话标识");
  }
  return value.sessionId;
}

/** 等待 Compose 页面完成一次状态更新。 */
async function waitForPageUpdate() {
  await new Promise((resolve) => setTimeout(resolve, 500));
}

/** 通过辅助功能名称查找并点击页面元素。 */
async function clickAccessibilityElement(sessionId, accessibilityName) {
  const element = await webdriverRequest(`/session/${sessionId}/element`, "POST", {
    using: "accessibility id",
    value: accessibilityName,
  });
  const elementId = element?.["element-6066-11e4-a52e-4f735466cecf"];
  if (!elementId) {
    throw new Error(`找不到辅助功能元素：${accessibilityName}`);
  }
  await webdriverRequest(`/session/${sessionId}/element/${elementId}/click`, "POST", {});
  await waitForPageUpdate();
}

/** 在当前页面执行一次真实向上滑动。 */
async function scrollUp(sessionId) {
  const rect = await webdriverRequest(`/session/${sessionId}/window/rect`);
  const x = Math.round(rect.width / 2);
  const startY = Math.round(rect.height * 0.78);
  const endY = Math.round(rect.height * 0.24);
  await webdriverRequest(`/session/${sessionId}/actions`, "POST", {
    actions: [
      {
        type: "pointer",
        id: "finger",
        parameters: { pointerType: "touch" },
        actions: [
          { type: "pointerMove", duration: 0, x, y: startY, origin: "viewport" },
          { type: "pointerDown", button: 0 },
          { type: "pause", duration: 150 },
          { type: "pointerMove", duration: 550, x, y: endY, origin: "viewport" },
          { type: "pointerUp", button: 0 },
        ],
      },
    ],
  });
  await webdriverRequest(`/session/${sessionId}/actions`, "DELETE");
  await waitForPageUpdate();
}

/** 滚动关于页并核对所有 V1 范围文案。 */
async function verifyAboutScope(sessionId, platformName) {
  await clickAccessibilityElement(sessionId, "关于与隐私");
  const observedTexts = new Set();
  let scrollCount = 0;
  for (; scrollCount <= 8; scrollCount += 1) {
    const source = await webdriverRequest(`/session/${sessionId}/source`);
    for (const text of REQUIRED_SCOPE_TEXTS) {
      if (source.includes(text)) observedTexts.add(text);
    }
    if (source.includes(OBSOLETE_SCOPE_TEXT)) {
      throw new Error(`${platformName} 关于页仍包含已废弃的多期范围描述`);
    }
    if (observedTexts.size === REQUIRED_SCOPE_TEXTS.length) break;
    await scrollUp(sessionId);
  }
  const missingTexts = REQUIRED_SCOPE_TEXTS.filter((text) => !observedTexts.has(text));
  if (missingTexts.length > 0) {
    throw new Error(`${platformName} 关于页缺少可访问文案：${missingTexts.join("、")}`);
  }
  process.stdout.write(`${platformName} 最大字号关于页通过，滚动 ${scrollCount} 次\n`);
}

/** 删除会话，失败时不覆盖原始验收错误。 */
async function deleteSession(sessionId) {
  if (!sessionId) return;
  try {
    await webdriverRequest(`/session/${sessionId}`, "DELETE");
  } catch {
    // 会话退出失败由外层 Appium 进程清理，不改变页面验收结论。
  }
}

/** 顺序验证 Android 与 iOS，避免两个驱动并发争用本机端口。 */
async function main() {
  let sessionId;
  try {
    sessionId = await createSession({
      platformName: "Android",
      "appium:automationName": "UiAutomator2",
      "appium:udid": androidConfig.udid,
      "appium:deviceName": androidConfig.deviceName,
      "appium:appPackage": androidConfig.packageName,
      "appium:appActivity": androidConfig.activityName,
      "appium:noReset": true,
      "appium:newCommandTimeout": 120,
    });
    await verifyAboutScope(sessionId, "Android");
  } finally {
    await deleteSession(sessionId);
  }

  sessionId = undefined;
  try {
    sessionId = await createSession({
      platformName: "iOS",
      "appium:automationName": "XCUITest",
      "appium:udid": iosConfig.udid,
      "appium:deviceName": iosConfig.deviceName,
      "appium:platformVersion": iosConfig.platformVersion,
      "appium:bundleId": iosConfig.bundleId,
      "appium:noReset": true,
      "appium:shouldTerminateApp": false,
      "appium:useNewWDA": false,
      "appium:wdaLocalPort": 8102,
      "appium:newCommandTimeout": 120,
    });
    await verifyAboutScope(sessionId, "iOS");
  } finally {
    await deleteSession(sessionId);
  }
}

await main();
