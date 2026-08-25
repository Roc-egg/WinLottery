/** Appium 服务根地址。 */
const appiumBaseUrl = process.argv[2];

/** 当前验收模式。 */
const acceptanceMode = process.argv[3];

/** Android 会话参数。 */
const androidConfig = {
  udid: process.argv[4],
  deviceName: process.argv[5],
  packageName: process.argv[6],
  activityName: process.argv[7],
};

/** iOS 会话参数。 */
const iosConfig = {
  udid: process.argv[8],
  deviceName: process.argv[9],
  platformVersion: process.argv[10],
  bundleId: process.argv[11],
};

/** 标准字号验收模式。 */
const STANDARD_MODE = "standard";

/** 最大字号验收模式。 */
const MAXIMUM_MODE = "maximum";

/** 选号页必须持续展示的责任边界。 */
const DISCLAIMER_TEXT = "生成结果仅供娱乐，不代表预测，不构成购彩建议。";

if (![STANDARD_MODE, MAXIMUM_MODE].includes(acceptanceMode)) {
  throw new Error(`未知 V1.2 验收模式：${acceptanceMode}`);
}

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
    const error = new Error(`Appium 请求失败：${message}`);
    error.webdriverError = payload.value?.error;
    throw error;
  }
  if (path === "/session" && method === "POST") {
    return {
      ...payload.value,
      sessionId: payload.value?.sessionId ?? payload.sessionId,
    };
  }
  return payload.value;
}

/** 从 Appium 元素响应中读取 W3C 元素标识。 */
function readElementId(element, accessibilityName) {
  const elementId = element?.["element-6066-11e4-a52e-4f735466cecf"];
  if (!elementId) throw new Error(`元素缺少 W3C 标识：${accessibilityName}`);
  return elementId;
}

/** 判断元素矩形是否完整位于当前窗口中。 */
function isRectInsideWindow(elementRect, windowRect) {
  return (
    elementRect.width > 0 &&
    elementRect.height > 0 &&
    elementRect.x >= windowRect.x &&
    elementRect.y >= windowRect.y &&
    elementRect.x + elementRect.width <= windowRect.x + windowRect.width &&
    elementRect.y + elementRect.height <= windowRect.y + windowRect.height
  );
}

/** 等待 Compose 页面完成一次状态更新。 */
async function waitForPageUpdate(durationMillis = 500) {
  await new Promise((resolve) => setTimeout(resolve, durationMillis));
}

/** 创建指定平台的 Appium 会话。 */
async function createSession(capabilities) {
  const value = await webdriverRequest("/session", "POST", {
    capabilities: { alwaysMatch: capabilities },
  });
  if (!value?.sessionId) throw new Error("Appium 未返回会话标识");
  return value.sessionId;
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

/** 读取当前辅助功能树。 */
async function readSource(sessionId) {
  return webdriverRequest(`/session/${sessionId}/source`);
}

/** 等待辅助功能树出现指定文案。 */
async function waitForSourceText(sessionId, expectedText, attemptCount = 20) {
  let source = "";
  for (let attempt = 0; attempt < attemptCount; attempt += 1) {
    source = await readSource(sessionId);
    if (source.includes(expectedText)) return source;
    await waitForPageUpdate(300);
  }
  throw new Error(`等待页面文案超时：${expectedText}`);
}

/** 等待辅助功能树同时出现一组指定文案。 */
async function waitForSourceTexts(sessionId, expectedTexts, attemptCount = 20) {
  let source = "";
  for (let attempt = 0; attempt < attemptCount; attempt += 1) {
    source = await readSource(sessionId);
    if (expectedTexts.every((text) => source.includes(text))) return source;
    await waitForPageUpdate(300);
  }
  const missingTexts = expectedTexts.filter((text) => !source.includes(text));
  throw new Error(`等待页面文案超时：${missingTexts.join("、")}`);
}

/** 精确查找所有同名辅助功能元素。 */
async function findExactElements(sessionId, accessibilityName) {
  const nameLiteral = JSON.stringify(accessibilityName);
  return webdriverRequest(`/session/${sessionId}/elements`, "POST", {
    using: "xpath",
    value:
      `//*[@content-desc=${nameLiteral} or @text=${nameLiteral} or ` +
      `@name=${nameLiteral} or @label=${nameLiteral} or @value=${nameLiteral}]`,
  });
}

/** 返回当前窗口内完整可见的同名元素快照。 */
async function findVisibleExactElements(sessionId, accessibilityName) {
  const [elements, windowRect] = await Promise.all([
    findExactElements(sessionId, accessibilityName),
    webdriverRequest(`/session/${sessionId}/window/rect`),
  ]);
  const visibleElements = [];
  for (const element of elements) {
    const elementId = readElementId(element, accessibilityName);
    try {
      const [elementRect, displayed] = await Promise.all([
        webdriverRequest(`/session/${sessionId}/element/${elementId}/rect`),
        webdriverRequest(`/session/${sessionId}/element/${elementId}/displayed`),
      ]);
      if (displayed && isRectInsideWindow(elementRect, windowRect)) {
        visibleElements.push({ elementId, elementRect });
      }
    } catch (error) {
      if (error.webdriverError !== "stale element reference") throw error;
    }
  }
  return visibleElements;
}

/** 等待一个同名元素完整进入当前窗口。 */
async function findVisibleExactElement(sessionId, accessibilityName) {
  for (let attempt = 0; attempt < 12; attempt += 1) {
    const elements = await findVisibleExactElements(sessionId, accessibilityName);
    if (elements.length > 0) return elements[0];
    await waitForPageUpdate(250);
  }
  throw new Error(`当前窗口缺少完整可见元素：${accessibilityName}`);
}

/** 在元素矩形中心发送一次平台原生触摸点按。 */
async function tapElementCenter(sessionId, elementRect, platformName) {
  const x = Math.round(elementRect.x + elementRect.width / 2);
  const y = Math.round(elementRect.y + elementRect.height / 2);
  if (platformName === "iOS") {
    await webdriverRequest(`/session/${sessionId}/execute/sync`, "POST", {
      script: "mobile: tap",
      args: [{ x, y }],
    });
  } else {
    await webdriverRequest(`/session/${sessionId}/actions`, "POST", {
      actions: [
        {
          type: "pointer",
          id: "finger",
          parameters: { pointerType: "touch" },
          actions: [
            { type: "pointerMove", duration: 0, x, y, origin: "viewport" },
            { type: "pointerDown", button: 0 },
            { type: "pause", duration: 120 },
            { type: "pointerUp", button: 0 },
          ],
        },
      ],
    });
    await webdriverRequest(`/session/${sessionId}/actions`, "DELETE");
  }
  await waitForPageUpdate();
}

/** 在当前页面执行一次指定方向的真实纵向滑动。 */
async function swipeVertically(sessionId, direction) {
  const windowRect = await webdriverRequest(`/session/${sessionId}/window/rect`);
  const x = Math.round(windowRect.x + windowRect.width / 2);
  const upperY = Math.round(windowRect.y + windowRect.height * 0.24);
  const lowerY = Math.round(windowRect.y + windowRect.height * 0.76);
  const startY = direction === "up" ? lowerY : upperY;
  const endY = direction === "up" ? upperY : lowerY;
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

/** 按指定方向滚动，直到目标元素完整进入窗口。 */
async function scrollElementIntoView(sessionId, accessibilityName, direction = "up") {
  for (let scrollCount = 0; scrollCount <= 18; scrollCount += 1) {
    const elements = await findVisibleExactElements(sessionId, accessibilityName);
    if (elements.length > 0) return { ...elements[0], scrollCount };
    await swipeVertically(sessionId, direction);
  }
  const source = await readSource(sessionId);
  throw new Error(
    `滚动后仍无法访问元素：${accessibilityName}；页面含文案=${source.includes(accessibilityName)}`,
  );
}

/** 按需滚动后点击指定元素。 */
async function clickScrollableExactElement(
  sessionId,
  accessibilityName,
  platformName,
  direction = "up",
) {
  const element = await scrollElementIntoView(sessionId, accessibilityName, direction);
  await tapElementCenter(sessionId, element.elementRect, platformName);
  return element.scrollCount;
}

/** 点击当前窗口垂直位置最靠下的同名元素，稳定命中一级导航。 */
async function clickBottommostExactElement(sessionId, accessibilityName, platformName) {
  const elements = await findVisibleExactElements(sessionId, accessibilityName);
  if (elements.length === 0) throw new Error(`当前窗口缺少一级导航：${accessibilityName}`);
  elements.sort((first, second) => second.elementRect.y - first.elementRect.y);
  await tapElementCenter(sessionId, elements[0].elementRect, platformName);
}

/** 查找 Android 文案元素对应的可选择父控件。 */
async function findAndroidSelectableElement(sessionId, accessibilityName) {
  const nameLiteral = JSON.stringify(accessibilityName);
  return webdriverRequest(`/session/${sessionId}/element`, "POST", {
    using: "xpath",
    value: `//*[@text=${nameLiteral}]/..`,
  });
}

/** 等待分段控件进入平台对应的选中状态。 */
async function waitForElementSelected(sessionId, accessibilityName, platformName) {
  let selectedValue;
  for (let attempt = 0; attempt < 12; attempt += 1) {
    const element =
      platformName === "Android"
        ? await findAndroidSelectableElement(sessionId, accessibilityName)
        : (await findVisibleExactElements(sessionId, accessibilityName))[0];
    if (element) {
      const elementId = readElementId(element, accessibilityName);
      selectedValue = await webdriverRequest(
        `/session/${sessionId}/element/${elementId}/attribute/${platformName === "Android" ? "checked" : "selected"}`,
      );
      if (selectedValue === true || selectedValue === "true") return;
    }
    await waitForPageUpdate(250);
  }
  throw new Error(`控件未进入选中状态：${accessibilityName}；selected=${selectedValue}`);
}

/** 核对指定步进器在冻结上限处已经禁用。 */
async function assertCounterMaximumDisabled(sessionId, accessibilityName) {
  const element = await findVisibleExactElement(sessionId, accessibilityName);
  const enabledValue = await webdriverRequest(
    `/session/${sessionId}/element/${element.elementId}/attribute/enabled`,
  );
  if (enabledValue === true || enabledValue === "true") {
    throw new Error(`${accessibilityName} 在冻结上限处仍可用`);
  }
}

/** 从任意一级页面进入核对工作区。 */
async function openVerification(sessionId, platformName) {
  const source = await readSource(sessionId);
  if (source.includes("纸质彩票核对")) return;
  await clickBottommostExactElement(sessionId, "核对", platformName);
  await waitForSourceText(sessionId, "纸质彩票核对");
}

/** 从任意一级页面进入随机选号工作区。 */
async function openNumberPicker(sessionId, platformName) {
  const source = await readSource(sessionId);
  if (source.includes("随机选号") && source.includes("跨期方式")) return;
  await clickBottommostExactElement(sessionId, "选号", platformName);
  await waitForSourceText(sessionId, DISCLAIMER_TEXT);
}

/** 从任意一级页面进入记录工作区。 */
async function openRecords(sessionId, platformName) {
  await clickBottommostExactElement(sessionId, "记录", platformName);
  await waitForSourceText(sessionId, "名称或起始期号");
}

/** 点击指定步进器固定次数，并在每次重组后重新读取元素。 */
async function incrementCounter(sessionId, accessibilityName, count, platformName) {
  for (let index = 0; index < count; index += 1) {
    await clickScrollableExactElement(sessionId, accessibilityName, platformName);
  }
}

/** 选择彩种并核对分段控件真实选中。 */
async function selectLottery(sessionId, lotteryName, platformName) {
  await clickScrollableExactElement(sessionId, lotteryName, platformName, "down");
  await waitForElementSelected(sessionId, lotteryName, platformName);
}

/** 读取当前期指定注单内的两位号码，作为会话结果稳定性证据。 */
async function readLineBallSignature(sessionId, lineNumber) {
  const title = await scrollElementIntoView(sessionId, `第 ${lineNumber} 注`, "up");
  const nextTitles = await findVisibleExactElements(sessionId, `第 ${lineNumber + 1} 注`);
  const windowRect = await webdriverRequest(`/session/${sessionId}/window/rect`);
  const lowerBoundary =
    nextTitles.length > 0
      ? Math.min(...nextTitles.map((element) => element.elementRect.y))
      : windowRect.y + windowRect.height;
  const upperBoundary = title.elementRect.y + title.elementRect.height;
  const balls = [];
  for (let number = 1; number <= 35; number += 1) {
    const label = number.toString().padStart(2, "0");
    const elements = await findVisibleExactElements(sessionId, label);
    for (const element of elements) {
      const centerY = element.elementRect.y + element.elementRect.height / 2;
      if (centerY > upperBoundary && centerY < lowerBoundary) {
        balls.push({ label, elementRect: element.elementRect });
      }
    }
  }
  balls.sort(
    (first, second) =>
      first.elementRect.y - second.elementRect.y || first.elementRect.x - second.elementRect.x,
  );
  if (balls.length !== 7) {
    throw new Error(`第 ${lineNumber} 注完整可见号码数量不是 7：${balls.map((ball) => ball.label).join("、")}`);
  }
  return balls.map((ball) => ball.label).join("-");
}

/** 在有限次重新生成后确认当前可见注单确实发生更新。 */
async function verifyRegeneration(sessionId, previousSignature, platformName) {
  let currentSignature = previousSignature;
  for (let attempt = 0; attempt < 3 && currentSignature === previousSignature; attempt += 1) {
    await clickScrollableExactElement(sessionId, "重新生成", platformName, "down");
    currentSignature = await readLineBallSignature(sessionId, 1);
  }
  if (currentSignature === previousSignature) {
    throw new Error(`${platformName} 连续重新生成后首注号码没有变化`);
  }
}

/** 核对双色球逐期独立、期次切换、重新生成和一级页面会话保留。 */
async function verifyDoubleColorBallWorkflow(sessionId, platformName) {
  await selectLottery(sessionId, "双色球", platformName);
  await incrementCounter(sessionId, "增加期数", 2, platformName);
  await incrementCounter(sessionId, "增加每期注数", 1, platformName);
  await clickScrollableExactElement(sessionId, "逐期独立", platformName);
  await waitForElementSelected(sessionId, "逐期独立", platformName);
  await clickScrollableExactElement(sessionId, "生成号码", platformName);
  await scrollElementIntoView(sessionId, "生成结果", "up");
  await waitForSourceTexts(sessionId, [
    "双色球 · 3 期 · 每期 2 注",
    "每一期按相同注数独立随机",
    "第 1 期号码",
  ]);

  const firstPeriodSignature = await readLineBallSignature(sessionId, 1);
  await clickScrollableExactElement(sessionId, "第 2 期", platformName, "down");
  await waitForSourceText(sessionId, "第 2 期号码");
  await readLineBallSignature(sessionId, 1);

  await openRecords(sessionId, platformName);
  await openNumberPicker(sessionId, platformName);
  await scrollElementIntoView(sessionId, "生成结果", "up");
  await waitForSourceTexts(sessionId, [
    "双色球 · 3 期 · 每期 2 注",
    "每一期按相同注数独立随机",
    "第 1 期号码",
  ]);
  const retainedSignature = await readLineBallSignature(sessionId, 1);
  if (retainedSignature !== firstPeriodSignature) {
    throw new Error(`${platformName} 切换一级页面后双色球会话结果发生变化`);
  }
  await verifyRegeneration(sessionId, retainedSignature, platformName);
}

/** 核对大乐透冻结的 20 期、每期 10 注和 200 个投注实例上限。 */
async function verifySuperLottoMaximum(sessionId, platformName) {
  await selectLottery(sessionId, "大乐透", platformName);
  await waitForSourceText(sessionId, "所有期次复用同一批号码");
  await incrementCounter(sessionId, "增加期数", 17, platformName);
  await incrementCounter(sessionId, "增加每期注数", 8, platformName);
  await waitForSourceText(sessionId, "共 200 注（20 期 × 每期 10 注）");
  await assertCounterMaximumDisabled(sessionId, "增加期数");
  await assertCounterMaximumDisabled(sessionId, "增加每期注数");
  const source = await readSource(sessionId);
  if (source.includes("逐期独立")) {
    throw new Error(`${platformName} 大乐透配置仍暴露逐期独立入口`);
  }

  await clickScrollableExactElement(sessionId, "生成号码", platformName);
  await scrollElementIntoView(sessionId, "生成结果", "up");
  await waitForSourceTexts(sessionId, [
    "大乐透 · 20 期 · 每期 10 注",
    "所有期次复用同一批号码",
    "第 1 期号码",
  ]);
  await readLineBallSignature(sessionId, 10);
}

/** 执行标准字号完整 V1.2 交互验收。 */
async function verifyStandardMode(sessionId, platformName) {
  await openVerification(sessionId, platformName);
  await openNumberPicker(sessionId, platformName);
  await waitForSourceTexts(sessionId, [DISCLAIMER_TEXT, "大乐透", "期数", "每期注数"]);
  for (const destination of ["核对", "选号", "记录"]) {
    await findVisibleExactElement(sessionId, destination);
  }
  await verifyDoubleColorBallWorkflow(sessionId, platformName);
  await verifySuperLottoMaximum(sessionId, platformName);
  process.stdout.write(`${platformName} 标准字号 V1.2 随机选号检查通过\n`);
}

/** 执行最大字号控件可达、期次切换和会话保留验收。 */
async function verifyMaximumMode(sessionId, platformName) {
  await openVerification(sessionId, platformName);
  await openNumberPicker(sessionId, platformName);
  await findVisibleExactElement(sessionId, DISCLAIMER_TEXT);
  await selectLottery(sessionId, "双色球", platformName);
  await incrementCounter(sessionId, "增加期数", 1, platformName);
  await incrementCounter(sessionId, "增加每期注数", 1, platformName);
  await clickScrollableExactElement(sessionId, "逐期独立", platformName);
  await waitForElementSelected(sessionId, "逐期独立", platformName);
  await clickScrollableExactElement(sessionId, "生成号码", platformName);
  await scrollElementIntoView(sessionId, "生成结果", "up");
  await waitForSourceTexts(sessionId, [
    "双色球 · 2 期 · 每期 2 注",
    "每一期按相同注数独立随机",
    "第 1 期号码",
  ]);
  await readLineBallSignature(sessionId, 2);
  await clickScrollableExactElement(sessionId, "第 2 期", platformName, "down");
  await waitForSourceText(sessionId, "第 2 期号码");
  await readLineBallSignature(sessionId, 2);

  await openVerification(sessionId, platformName);
  await openNumberPicker(sessionId, platformName);
  await scrollElementIntoView(sessionId, "生成结果", "up");
  await waitForSourceTexts(sessionId, [
    "双色球 · 2 期 · 每期 2 注",
    "每一期按相同注数独立随机",
  ]);
  await scrollElementIntoView(sessionId, DISCLAIMER_TEXT, "down");
  await scrollElementIntoView(sessionId, "重新生成", "down");
  process.stdout.write(`${platformName} 最大字号 V1.2 随机选号检查通过\n`);
}

/** 创建 Android 会话并执行当前字号模式。 */
async function verifyAndroid() {
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
      "appium:newCommandTimeout": 180,
    });
    if (acceptanceMode === STANDARD_MODE) await verifyStandardMode(sessionId, "Android");
    if (acceptanceMode === MAXIMUM_MODE) await verifyMaximumMode(sessionId, "Android");
  } finally {
    await deleteSession(sessionId);
  }
}

/** 创建 iOS 会话并执行当前字号模式。 */
async function verifyIOS() {
  let sessionId;
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
      "appium:wdaLocalPort": 8103,
      "appium:newCommandTimeout": 180,
    });
    if (acceptanceMode === STANDARD_MODE) await verifyStandardMode(sessionId, "iOS");
    if (acceptanceMode === MAXIMUM_MODE) await verifyMaximumMode(sessionId, "iOS");
  } finally {
    await deleteSession(sessionId);
  }
}

await verifyAndroid();
await verifyIOS();
