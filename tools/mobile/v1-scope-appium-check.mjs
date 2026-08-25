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

/** 手动录入输入框使用的本地合成值，不会进入开奖查询。 */
const REVIEW_INPUT_VALUES = {
  issue: "26999",
  paidAmount: "4.00",
};

/** 最大字号手动录入流程必须能够访问的稳定文案。 */
const REQUIRED_REVIEW_TEXTS = [
  "手动录入彩票",
  "请逐项核对",
  "彩种",
  "超级大乐透",
  "双色球",
  "开奖期号",
  "投注号码",
  "前区（已选 0/5）",
  "后区（已选 0/2）",
  "添加一注",
  "投注属性",
  "投注倍数",
  "追加投注",
  "投注期数",
  "票面金额",
  "已核对，继续",
];

/** 双色球手动录入必须能够访问且不会混入大乐透属性的稳定文案。 */
const REQUIRED_DOUBLE_COLOR_BALL_TEXTS = [
  "红球（已选 0/6）",
  "蓝球（已选 0/1）",
  "投注倍数",
  "投注期数",
  "票面金额",
  "已核对，继续",
];

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
  if (!elementId) {
    throw new Error(`元素缺少 W3C 标识：${accessibilityName}`);
  }
  return elementId;
}

/** 通过辅助功能名称或平台可见文本精确查找页面元素。 */
async function findAccessibilityElement(sessionId, accessibilityName) {
  try {
    return await webdriverRequest(`/session/${sessionId}/element`, "POST", {
      using: "accessibility id",
      value: accessibilityName,
    });
  } catch (error) {
    if (error.webdriverError !== "no such element") throw error;
  }
  const nameLiteral = JSON.stringify(accessibilityName);
  return webdriverRequest(`/session/${sessionId}/element`, "POST", {
    using: "xpath",
    value:
      `//*[@content-desc=${nameLiteral} or @text=${nameLiteral} or ` +
      `@name=${nameLiteral} or @label=${nameLiteral} or @value=${nameLiteral}]`,
  });
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

/** 判断两次元素矩形快照是否一致。 */
function isSameRect(firstRect, secondRect) {
  return (
    firstRect?.x === secondRect?.x &&
    firstRect?.y === secondRect?.y &&
    firstRect?.width === secondRect?.width &&
    firstRect?.height === secondRect?.height
  );
}

/** 等待 iOS 滚动减速结束并返回稳定的元素矩形。 */
async function waitForStableAccessibilityElementRect(sessionId, accessibilityName) {
  let previousRect;
  for (let attempt = 0; attempt < 10; attempt += 1) {
    const element = await findAccessibilityElement(sessionId, accessibilityName);
    const elementId = readElementId(element, accessibilityName);
    const elementRect = await webdriverRequest(`/session/${sessionId}/element/${elementId}/rect`);
    if (isSameRect(previousRect, elementRect)) return elementRect;
    previousRect = elementRect;
    await new Promise((resolve) => setTimeout(resolve, 300));
  }
  throw new Error(`等待 iOS 元素位置稳定超时：${accessibilityName}`);
}

/** 按需真实滚动，直到指定辅助功能元素完整进入窗口。 */
async function scrollAccessibilityElementIntoView(sessionId, accessibilityName) {
  let lastElementRect;
  let lastWindowRect;
  for (let scrollCount = 0; scrollCount <= 12; scrollCount += 1) {
    try {
      const element = await findAccessibilityElement(sessionId, accessibilityName);
      const elementId = readElementId(element, accessibilityName);
      const [elementRect, windowRect, displayed] = await Promise.all([
        webdriverRequest(`/session/${sessionId}/element/${elementId}/rect`),
        webdriverRequest(`/session/${sessionId}/window/rect`),
        webdriverRequest(`/session/${sessionId}/element/${elementId}/displayed`),
      ]);
      lastElementRect = elementRect;
      lastWindowRect = windowRect;
      if (displayed && isRectInsideWindow(elementRect, windowRect)) {
        return { elementId, elementRect, scrollCount };
      }
    } catch (error) {
      if (error.webdriverError !== "no such element") throw error;
    }
    await scrollUp(sessionId);
  }
  const source = await webdriverRequest(`/session/${sessionId}/source`);
  throw new Error(
    `滚动后仍无法完整访问元素：${accessibilityName}；` +
      `元素矩形=${JSON.stringify(lastElementRect)}；` +
      `窗口矩形=${JSON.stringify(lastWindowRect)}；` +
      `页面含目标文本=${source.includes(accessibilityName)}`,
  );
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
    await waitForPageUpdate();
    return;
  }
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
  await waitForPageUpdate();
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
  const element = await findAccessibilityElement(sessionId, accessibilityName);
  const elementId = readElementId(element, accessibilityName);
  await webdriverRequest(`/session/${sessionId}/element/${elementId}/click`, "POST", {});
  await waitForPageUpdate();
}

/** 按需滚动后点击完整可见的辅助功能元素。 */
async function clickScrollableAccessibilityElement(sessionId, accessibilityName, platformName) {
  const visibleElement = await scrollAccessibilityElementIntoView(sessionId, accessibilityName);
  const elementRect =
    platformName === "iOS"
      ? await waitForStableAccessibilityElementRect(sessionId, accessibilityName)
      : visibleElement.elementRect;
  await tapElementCenter(sessionId, elementRect, platformName);
  return visibleElement.scrollCount;
}

/** 查找指定字段当前暴露的 Compose 文本输入节点。 */
async function findEditableElements(sessionId, fieldName, platformName) {
  const iosEditablePredicate =
    fieldName === "票面金额"
      ? 'starts-with(@name, "合计金额")'
      : `@name=${JSON.stringify(fieldName)}`;
  return platformName === "Android"
    ? webdriverRequest(`/session/${sessionId}/elements`, "POST", {
        using: "class name",
        value: "android.widget.EditText",
      })
    : [
        await webdriverRequest(`/session/${sessionId}/element`, "POST", {
          using: "xpath",
          value: `//XCUIElementTypeTextView[${iosEditablePredicate}]`,
        }),
      ];
}

/** 查找当前窗口中完整可见的 Compose 文本输入节点。 */
async function findVisibleEditableElement(sessionId, fieldName, platformName) {
  const windowRect = await webdriverRequest(`/session/${sessionId}/window/rect`);
  const elements = await findEditableElements(sessionId, fieldName, platformName);
  for (const element of elements) {
    const elementId = readElementId(element, fieldName);
    const [elementRect, displayed] = await Promise.all([
      webdriverRequest(`/session/${sessionId}/element/${elementId}/rect`),
      webdriverRequest(`/session/${sessionId}/element/${elementId}/displayed`),
    ]);
    if (displayed && isRectInsideWindow(elementRect, windowRect)) return { elementId, elementRect };
  }
  throw new Error(`${platformName} 当前窗口缺少完整可见的${fieldName}输入框`);
}

/** 等待指定输入节点暴露目标文本值。 */
async function waitForEditableElementValue(sessionId, elementId, fieldName, platformName, expectedValue) {
  let actualValue;
  let currentElementId = elementId;
  for (let attempt = 0; attempt < 10; attempt += 1) {
    try {
      actualValue = await webdriverRequest(
        `/session/${sessionId}/element/${currentElementId}/attribute/${platformName === "Android" ? "text" : "value"}`,
      );
    } catch (error) {
      if (error.webdriverError !== "stale element reference") throw error;
      const currentElements = await findEditableElements(sessionId, fieldName, platformName);
      if (currentElements.length !== 1) {
        throw new Error(`${platformName} ${fieldName}重组后输入节点数量异常：${currentElements.length}`);
      }
      currentElementId = readElementId(currentElements[0], fieldName);
      continue;
    }
    if (actualValue === expectedValue) return;
    await new Promise((resolve) => setTimeout(resolve, 300));
  }
  throw new Error(`${platformName} ${fieldName}输入值不正确：${actualValue}`);
}

/** 等待当前窗口中的可见输入节点暴露目标文本值。 */
async function waitForEditableValue(sessionId, fieldName, platformName, expectedValue) {
  const { elementId } = await findVisibleEditableElement(sessionId, fieldName, platformName);
  await waitForEditableElementValue(sessionId, elementId, fieldName, platformName, expectedValue);
}

/** 在完整可见的 Compose 输入框中写入文本，并等待状态更新。 */
async function enterEditableValue(sessionId, fieldName, platformName, value) {
  const { elementId, elementRect } = await findVisibleEditableElement(sessionId, fieldName, platformName);
  if (platformName === "Android") {
    await tapElementCenter(sessionId, elementRect, platformName);
    await webdriverRequest(`/session/${sessionId}/execute/sync`, "POST", {
      script: "mobile: type",
      args: [{ text: value }],
    });
  } else {
    await webdriverRequest(`/session/${sessionId}/element/${elementId}/click`, "POST", {});
    await webdriverRequest(`/session/${sessionId}/element/${elementId}/value`, "POST", {
      text: value,
      value: [...value],
    });
  }
  await waitForPageUpdate();
}

/** 等待系统输入法退出当前应用窗口。 */
async function waitForKeyboardHidden(sessionId, platformName) {
  let keyboardShown = true;
  for (let attempt = 0; attempt < 10; attempt += 1) {
    keyboardShown = await webdriverRequest(`/session/${sessionId}/appium/device/is_keyboard_shown`);
    if (!keyboardShown) return;
    await new Promise((resolve) => setTimeout(resolve, 300));
  }
  throw new Error(`${platformName} 完成输入后系统键盘仍未隐藏`);
}

/** 完成金额输入：Android 使用 IME 动作，iOS 使用应用内完成按钮。 */
async function completePaidAmountInput(sessionId, platformName) {
  if (platformName === "Android") {
    await webdriverRequest(`/session/${sessionId}/execute/sync`, "POST", {
      script: "mobile: performEditorAction",
      args: [{ action: "done" }],
    });
    await waitForPageUpdate();
  } else {
    await clickScrollableAccessibilityElement(sessionId, "完成金额输入", platformName);
    try {
      await waitForKeyboardHidden(sessionId, platformName);
      return;
    } catch {
      await clickAccessibilityElement(sessionId, "完成金额输入");
    }
  }
  await waitForKeyboardHidden(sessionId, platformName);
}

/** 在当前页面执行一次真实向上滑动。 */
async function scrollUp(sessionId) {
  const [rect, keyboardShown] = await Promise.all([
    webdriverRequest(`/session/${sessionId}/window/rect`),
    webdriverRequest(`/session/${sessionId}/appium/device/is_keyboard_shown`),
  ]);
  const x = Math.round(rect.width / 2);
  const startY = Math.round(rect.height * (keyboardShown ? 0.55 : 0.78));
  const endY = Math.round(rect.height * (keyboardShown ? 0.16 : 0.24));
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

/** 记录当前页面已经进入辅助功能树的手动录入文案。 */
async function observeReviewTexts(sessionId, observedTexts) {
  const source = await webdriverRequest(`/session/${sessionId}/source`);
  for (const text of REQUIRED_REVIEW_TEXTS) {
    if (source.includes(text)) observedTexts.add(text);
  }
  return source;
}

/** 等待 Compose 状态进入平台辅助功能快照。 */
async function waitForSourceText(sessionId, expectedText) {
  let source = "";
  for (let attempt = 0; attempt < 10; attempt += 1) {
    source = await webdriverRequest(`/session/${sessionId}/source`);
    if (source.includes(expectedText)) return source;
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  return source;
}

/** 查找 Android 文案元素对应的可选择父控件。 */
async function findAndroidSelectableElement(sessionId, accessibilityName) {
  const nameLiteral = JSON.stringify(accessibilityName);
  return webdriverRequest(`/session/${sessionId}/element`, "POST", {
    using: "xpath",
    value: `//*[@text=${nameLiteral}]/..`,
  });
}

/** 等待可选择控件进入平台对应的选中状态。 */
async function waitForAccessibilityElementSelected(sessionId, accessibilityName, platformName) {
  let selectedValue;
  for (let attempt = 0; attempt < 10; attempt += 1) {
    const element =
      platformName === "Android"
        ? await findAndroidSelectableElement(sessionId, accessibilityName)
        : await findAccessibilityElement(sessionId, accessibilityName);
    const elementId = readElementId(element, accessibilityName);
    selectedValue = await webdriverRequest(
      `/session/${sessionId}/element/${elementId}/attribute/${platformName === "Android" ? "checked" : "selected"}`,
    );
    if (selectedValue === true || selectedValue === "true") return;
    await new Promise((resolve) => setTimeout(resolve, 300));
  }
  throw new Error(`控件未进入选中状态：${accessibilityName}；selected=${selectedValue}`);
}

/** 遍历双色球手动录入页并确认没有混入大乐透追加属性。 */
async function verifyDoubleColorBallEditor(sessionId, platformName) {
  await clickScrollableAccessibilityElement(sessionId, "双色球", platformName);
  await waitForAccessibilityElementSelected(sessionId, "双色球", platformName);
  const observedTexts = new Set();
  let scrollCount = 0;
  for (; scrollCount <= 8; scrollCount += 1) {
    const source = await webdriverRequest(`/session/${sessionId}/source`);
    if (source.includes("追加投注")) {
      throw new Error(`${platformName} 双色球手动录入意外显示大乐透追加属性`);
    }
    for (const text of REQUIRED_DOUBLE_COLOR_BALL_TEXTS) {
      if (source.includes(text)) observedTexts.add(text);
    }
    if (observedTexts.size === REQUIRED_DOUBLE_COLOR_BALL_TEXTS.length) break;
    await scrollUp(sessionId);
  }
  const missingTexts = REQUIRED_DOUBLE_COLOR_BALL_TEXTS.filter((text) => !observedTexts.has(text));
  if (missingTexts.length > 0) {
    throw new Error(`${platformName} 最大字号双色球手动录入缺少可访问文案：${missingTexts.join("、")}`);
  }
  return scrollCount;
}

/** 截取指定文案附近的页面源，避免失败日志输出整份辅助功能树。 */
function sourceContext(source, anchorText) {
  const anchorIndex = source.indexOf(anchorText);
  if (anchorIndex < 0) return "未找到锚点";
  const startIndex = Math.max(0, anchorIndex - 160);
  return source.slice(startIndex, anchorIndex + 640);
}

/** 验证最大字号手动录入控件可达，返回后再次进入时状态已经清空。 */
async function verifyManualReviewAccessibility(sessionId, platformName) {
  await clickAccessibilityElement(sessionId, "返回");
  let scrollCount = await clickScrollableAccessibilityElement(sessionId, "手动录入", platformName);
  const observedTexts = new Set();
  let source = await observeReviewTexts(sessionId, observedTexts);
  if (source.includes("投注号码")) {
    throw new Error(`${platformName} 新手动录入流程意外保留了彩种状态`);
  }

  await clickScrollableAccessibilityElement(sessionId, "超级大乐透", platformName);
  await observeReviewTexts(sessionId, observedTexts);
  await enterEditableValue(sessionId, "开奖期号", platformName, REVIEW_INPUT_VALUES.issue);
  scrollCount += await clickScrollableAccessibilityElement(sessionId, "完成期号输入", platformName);
  await waitForKeyboardHidden(sessionId, platformName);
  await waitForEditableValue(sessionId, "开奖期号", platformName, REVIEW_INPUT_VALUES.issue);
  for (const numberSectionText of ["前区（已选 0/5）", "后区（已选 0/2）"]) {
    scrollCount += (await scrollAccessibilityElementIntoView(sessionId, numberSectionText)).scrollCount;
    await observeReviewTexts(sessionId, observedTexts);
  }
  scrollCount += await clickScrollableAccessibilityElement(sessionId, "确认 1 倍", platformName);
  await observeReviewTexts(sessionId, observedTexts);
  scrollCount += await clickScrollableAccessibilityElement(sessionId, "基本", platformName);
  await waitForAccessibilityElementSelected(sessionId, "基本", platformName);
  await observeReviewTexts(sessionId, observedTexts);
  scrollCount += await clickScrollableAccessibilityElement(sessionId, "增加投注期数", platformName);
  source = await waitForSourceText(sessionId, "2 期");
  for (const text of REQUIRED_REVIEW_TEXTS) {
    if (source.includes(text)) observedTexts.add(text);
  }
  if (!source.includes("2 期")) {
    throw new Error(
      `${platformName} 最大字号下增加投注期数后未显示 2 期；` +
        `页面上下文=${sourceContext(source, "投注期数")}`,
    );
  }
  scrollCount += (await scrollAccessibilityElementIntoView(sessionId, "完成金额输入")).scrollCount;
  await enterEditableValue(sessionId, "票面金额", platformName, REVIEW_INPUT_VALUES.paidAmount);
  await completePaidAmountInput(sessionId, platformName);
  scrollCount += (await scrollAccessibilityElementIntoView(sessionId, "完成金额输入")).scrollCount;
  await waitForEditableValue(sessionId, "票面金额", platformName, REVIEW_INPUT_VALUES.paidAmount);
  scrollCount += (await scrollAccessibilityElementIntoView(sessionId, "已核对，继续")).scrollCount;
  await observeReviewTexts(sessionId, observedTexts);

  const missingTexts = REQUIRED_REVIEW_TEXTS.filter((text) => !observedTexts.has(text));
  if (missingTexts.length > 0) {
    throw new Error(`${platformName} 最大字号手动录入缺少可访问文案：${missingTexts.join("、")}`);
  }

  await clickAccessibilityElement(sessionId, "返回");
  scrollCount += await clickScrollableAccessibilityElement(sessionId, "手动录入", platformName);
  source = await webdriverRequest(`/session/${sessionId}/source`);
  if (
    source.includes("投注号码") ||
    source.includes("2 期") ||
    source.includes(REVIEW_INPUT_VALUES.issue) ||
    source.includes(REVIEW_INPUT_VALUES.paidAmount)
  ) {
    throw new Error(`${platformName} 返回首页后再次手动录入仍保留旧状态`);
  }
  scrollCount += await verifyDoubleColorBallEditor(sessionId, platformName);
  await clickAccessibilityElement(sessionId, "返回");
  scrollCount += await clickScrollableAccessibilityElement(sessionId, "手动录入", platformName);
  source = await webdriverRequest(`/session/${sessionId}/source`);
  if (source.includes("投注号码")) {
    throw new Error(`${platformName} 返回首页后再次手动录入仍保留双色球状态`);
  }
  await clickAccessibilityElement(sessionId, "返回");
  process.stdout.write(`${platformName} 最大字号双彩种手动录入通过，累计滚动 ${scrollCount} 次\n`);
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
    await verifyManualReviewAccessibility(sessionId, "Android");
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
    await verifyManualReviewAccessibility(sessionId, "iOS");
  } finally {
    await deleteSession(sessionId);
  }
}

await main();
