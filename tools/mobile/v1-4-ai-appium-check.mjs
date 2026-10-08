import { createHash } from "node:crypto";
import { mkdir, writeFile } from "node:fs/promises";
import { join } from "node:path";

/** Appium 服务根地址。 */
const appiumBaseUrl = process.argv[2];

/** 截图证据目录。 */
const screenshotDirectory = process.argv[3];

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

/** 本机只停留在 TLS 握手阶段的验收端口。 */
const stallServerPort = Number.parseInt(process.argv[12] ?? "", 10);

/** 可选的单平台诊断目标；正式脚本不传时固定执行双端。 */
const targetPlatform = process.argv[13] ?? "both";

/** WebDriver 横屏方向值。 */
const LANDSCAPE_ORIENTATION = "LANDSCAPE";

/** WebDriver 竖屏方向值。 */
const PORTRAIT_ORIENTATION = "PORTRAIT";

/** 移动端必须完整展示的五个一级目的地。 */
const MAIN_DESTINATIONS = ["核对", "选号", "走势", "AI", "记录"];

/** 产品冻结的五档真实历史样本。 */
const SAMPLE_OPTIONS = ["50 期", "80 期", "120 期", "300 期", "500 期"];

/** 产品冻结的三个分析模板。 */
const TEMPLATE_OPTIONS = ["综合历史分析", "频次与遗漏", "分布结构"];

/** 只用于本轮自动化且明确不是真实凭据的会话密钥。 */
const ACCEPTANCE_API_KEY = "acceptance-key-not-real";

/** 验收页展示的服务商名称。 */
const ACCEPTANCE_PROVIDER_NAME = "V1.4 Acceptance";

/** 验收页展示的模型标识。 */
const ACCEPTANCE_MODEL = "acceptance-model";

/** 非 HTTPS 本地阻断的稳定提示。 */
const INSECURE_ENDPOINT_MESSAGE = "AI 服务地址必须使用 HTTPS";

/** 主动取消后的稳定提示。 */
const CANCELLED_REQUEST_MESSAGE = "已取消本次 AI 请求，未自动重试";

/** 确认框底部的一次发送与费用责任提示。 */
const SINGLE_SEND_NOTICE =
  "本次确认只发送一次，失败不会自动重试或切换服务商；费用由上述服务商账户承担。";

/** Android UiAutomation 瞬态未连接时允许的会话创建总次数。 */
const SESSION_CREATION_ATTEMPT_COUNT = 3;

if (!Number.isInteger(stallServerPort) || stallServerPort < 1024 || stallServerPort > 65535) {
  throw new Error("V1.4 本机停滞服务端口无效");
}
if (!["both", "android", "ios"].includes(targetPlatform)) {
  throw new Error(`未知 V1.4 目标平台：${targetPlatform}`);
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

/** 判断两个元素矩形是否存在实质交叠。 */
function doRectsOverlap(firstRect, secondRect) {
  return (
    firstRect.x < secondRect.x + secondRect.width &&
    firstRect.x + firstRect.width > secondRect.x &&
    firstRect.y < secondRect.y + secondRect.height &&
    firstRect.y + firstRect.height > secondRect.y
  );
}

/** 等待 Compose 页面完成一次状态更新。 */
async function waitForPageUpdate(durationMillis = 500) {
  await new Promise((resolve) => setTimeout(resolve, durationMillis));
}

/** 创建指定平台的 Appium 会话。 */
async function createSession(capabilities) {
  for (let attempt = 1; attempt <= SESSION_CREATION_ATTEMPT_COUNT; attempt += 1) {
    try {
      const value = await webdriverRequest("/session", "POST", {
        capabilities: { alwaysMatch: capabilities },
      });
      if (!value?.sessionId) throw new Error("Appium 未返回会话标识");
      return value.sessionId;
    } catch (error) {
      const isTransientUiAutomationFailure = error.message.includes(
        "UiAutomation not connected",
      );
      if (!isTransientUiAutomationFailure || attempt === SESSION_CREATION_ATTEMPT_COUNT) {
        throw error;
      }
      process.stderr.write(
        `Android UiAutomation 尚未就绪，等待后重试会话（${attempt}/${SESSION_CREATION_ATTEMPT_COUNT}）\n`,
      );
      await waitForPageUpdate(3000);
    }
  }
  throw new Error("Appium 会话创建重试次数耗尽");
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
async function waitForSourceText(sessionId, expectedText, attemptCount = 30) {
  let source = "";
  for (let attempt = 0; attempt < attemptCount; attempt += 1) {
    source = await readSource(sessionId);
    if (source.includes(expectedText)) return source;
    await waitForPageUpdate(300);
  }
  throw new Error(`等待页面文案超时：${expectedText}`);
}

/** 等待辅助功能树不再包含指定文案。 */
async function waitForSourceWithoutText(sessionId, unexpectedText, attemptCount = 30) {
  let source = "";
  for (let attempt = 0; attempt < attemptCount; attempt += 1) {
    source = await readSource(sessionId);
    if (!source.includes(unexpectedText)) return source;
    await waitForPageUpdate(300);
  }
  throw new Error(`页面文案未按时消失：${unexpectedText}`);
}

/** 等待辅助功能树出现符合指定规则的动态文案。 */
async function waitForSourcePattern(sessionId, expectedPattern, attemptCount = 30) {
  let source = "";
  for (let attempt = 0; attempt < attemptCount; attempt += 1) {
    source = await readSource(sessionId);
    const match = source.match(expectedPattern);
    if (match) return { source, match };
    await waitForPageUpdate(300);
  }
  throw new Error(`等待页面动态文案超时：${expectedPattern}`);
}

/** 使用 XPath 查找所有匹配元素。 */
async function findElementsByXPath(sessionId, xpath) {
  return webdriverRequest(`/session/${sessionId}/elements`, "POST", {
    using: "xpath",
    value: xpath,
  });
}

/** 精确查找所有同名辅助功能元素。 */
async function findExactElements(sessionId, accessibilityName) {
  const nameLiteral = JSON.stringify(accessibilityName);
  return findElementsByXPath(
    sessionId,
    `//*[@content-desc=${nameLiteral} or @text=${nameLiteral} or ` +
      `@name=${nameLiteral} or @label=${nameLiteral} or @value=${nameLiteral}]`,
  );
}

/** 返回一组元素中完整位于当前窗口的元素快照。 */
async function filterVisibleElements(sessionId, elements, description) {
  const windowRect = await webdriverRequest(`/session/${sessionId}/window/rect`);
  const visibleElements = [];
  for (const element of elements) {
    const elementId = readElementId(element, description);
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

/** 返回当前窗口内完整可见的同名元素快照。 */
async function findVisibleExactElements(sessionId, accessibilityName) {
  const elements = await findExactElements(sessionId, accessibilityName);
  return filterVisibleElements(sessionId, elements, accessibilityName);
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

/** 发送一组单指触摸动作。 */
async function performTouchSwipe(sessionId, startX, startY, endX, endY, durationMillis) {
  await webdriverRequest(`/session/${sessionId}/actions`, "POST", {
    actions: [
      {
        type: "pointer",
        id: "finger",
        parameters: { pointerType: "touch" },
        actions: [
          { type: "pointerMove", duration: 0, x: startX, y: startY, origin: "viewport" },
          { type: "pointerDown", button: 0 },
          { type: "pause", duration: 120 },
          {
            type: "pointerMove",
            duration: durationMillis,
            x: endX,
            y: endY,
            origin: "viewport",
          },
          { type: "pointerUp", button: 0 },
        ],
      },
    ],
  });
  await webdriverRequest(`/session/${sessionId}/actions`, "DELETE");
}

/** 在元素矩形中心发送一次真实触摸点按。 */
async function tapElementCenter(sessionId, elementRect) {
  const x = Math.round(elementRect.x + elementRect.width / 2);
  const y = Math.round(elementRect.y + elementRect.height / 2);
  await performTouchSwipe(sessionId, x, y, x, y, 120);
  await waitForPageUpdate();
}

/** 在当前页面执行一次指定方向的真实纵向滑动。 */
async function swipeVertically(sessionId, direction) {
  const windowRect = await webdriverRequest(`/session/${sessionId}/window/rect`);
  const x = Math.round(windowRect.x + windowRect.width / 2);
  const upperY = Math.round(windowRect.y + windowRect.height * 0.23);
  const lowerY = Math.round(windowRect.y + windowRect.height * 0.76);
  const startY = direction === "up" ? lowerY : upperY;
  const endY = direction === "up" ? upperY : lowerY;
  await performTouchSwipe(sessionId, x, startY, x, endY, 520);
  await waitForPageUpdate();
}

/** 按指定方向滚动，直到目标元素完整进入窗口。 */
async function scrollElementIntoView(sessionId, accessibilityName, direction = "up") {
  for (let scrollCount = 0; scrollCount <= 30; scrollCount += 1) {
    const elements = await findVisibleExactElements(sessionId, accessibilityName);
    if (elements.length > 0) return { ...elements[0], scrollCount };
    await swipeVertically(sessionId, direction);
  }
  const source = await readSource(sessionId);
  throw new Error(
    `滚动后仍无法访问元素：${accessibilityName}；页面含文案=${source.includes(accessibilityName)}`,
  );
}

/** 返回当前平台可滚动容器的 XPath。 */
function scrollableContainerXPath(platformName) {
  return platformName === "Android"
    ? "//*[@scrollable='true']"
    : "//XCUIElementTypeScrollView";
}

/** 在当前最小可见滚动容器中执行一次指定方向的滑动。 */
async function swipeSmallestVisibleContainer(sessionId, platformName, direction) {
  const containers = await findElementsByXPath(
    sessionId,
    scrollableContainerXPath(platformName),
  );
  const visibleContainers = await filterVisibleElements(
    sessionId,
    containers,
    `${platformName} 可滚动容器`,
  );
  if (visibleContainers.length === 0) {
    throw new Error(`${platformName} 当前窗口缺少可滚动容器`);
  }
  visibleContainers.sort((first, second) => {
    const firstArea = first.elementRect.width * first.elementRect.height;
    const secondArea = second.elementRect.width * second.elementRect.height;
    return firstArea - secondArea;
  });
  const containerRect = visibleContainers[0].elementRect;
  const x = Math.round(containerRect.x + containerRect.width / 2);
  const upperY = Math.round(containerRect.y + containerRect.height * 0.18);
  const lowerY = Math.round(containerRect.y + containerRect.height * 0.82);
  await performTouchSwipe(
    sessionId,
    x,
    direction === "up" ? lowerY : upperY,
    x,
    direction === "up" ? upperY : lowerY,
    520,
  );
  await waitForPageUpdate(350);
}

/** 在当前最小可见滚动容器内部滑动，直到目标元素完整出现。 */
async function scrollContainerUntilVisible(
  sessionId,
  accessibilityName,
  platformName,
  direction = "up",
) {
  for (let attempt = 0; attempt <= 24; attempt += 1) {
    const targets = await findVisibleExactElements(sessionId, accessibilityName);
    if (targets.length > 0) return targets[0];
    await swipeSmallestVisibleContainer(sessionId, platformName, direction);
  }
  throw new Error(`${platformName} 容器内滚动后仍无法访问：${accessibilityName}`);
}

/** 把当前最小滚动容器稳定推到末端，确保末项文本没有被操作区裁切。 */
async function scrollSmallestContainerToEnd(sessionId, platformName) {
  await swipeSmallestVisibleContainer(sessionId, platformName, "up");
  await swipeSmallestVisibleContainer(sessionId, platformName, "up");
}

/** 点击当前窗口垂直位置最靠下的同名元素。 */
async function clickBottommostExactElement(sessionId, accessibilityName) {
  const elements = await findVisibleExactElements(sessionId, accessibilityName);
  if (elements.length === 0) throw new Error(`当前窗口缺少元素：${accessibilityName}`);
  elements.sort((first, second) => second.elementRect.y - first.elementRect.y);
  await tapElementCenter(sessionId, elements[0].elementRect);
}

/** 点击滚动后完整可见的指定元素。 */
async function clickScrollableElement(sessionId, accessibilityName, direction = "up") {
  const element = await scrollElementIntoView(sessionId, accessibilityName, direction);
  await tapElementCenter(sessionId, element.elementRect);
}

/** 切换设备方向，并等待应用窗口尺寸与目标方向一致。 */
async function setOrientation(sessionId, orientation) {
  await webdriverRequest(`/session/${sessionId}/orientation`, "POST", { orientation });
  for (let attempt = 0; attempt < 24; attempt += 1) {
    const [actualOrientation, windowRect] = await Promise.all([
      webdriverRequest(`/session/${sessionId}/orientation`),
      webdriverRequest(`/session/${sessionId}/window/rect`),
    ]);
    const sizeMatches =
      orientation === LANDSCAPE_ORIENTATION
        ? windowRect.width > windowRect.height
        : windowRect.height > windowRect.width;
    if (actualOrientation === orientation && sizeMatches) {
      await waitForPageUpdate(800);
      return windowRect;
    }
    await waitForPageUpdate(300);
  }
  throw new Error(`设备未稳定切换到 ${orientation}`);
}

/** 尽力恢复竖屏，不覆盖原始验收异常。 */
async function restorePortraitOrientation(sessionId) {
  if (!sessionId) return;
  try {
    await setOrientation(sessionId, PORTRAIT_ORIENTATION);
  } catch {
    // 会话清理仍会继续，原始验收结论优先保留。
  }
}

/** 保存一张当前平台的验收截图。 */
async function saveScreenshot(sessionId, platformName, label) {
  await mkdir(screenshotDirectory, { recursive: true });
  const encoded = await webdriverRequest(`/session/${sessionId}/screenshot`);
  const bytes = Buffer.from(encoded, "base64");
  const fileName = `${platformName.toLowerCase()}-${label}.png`;
  await writeFile(join(screenshotDirectory, fileName), bytes);
  if (bytes.length < 24 || bytes.toString("ascii", 1, 4) !== "PNG") {
    throw new Error(`${platformName} 截图不是有效 PNG`);
  }
  return {
    bytes,
    width: bytes.readUInt32BE(16),
    height: bytes.readUInt32BE(20),
    sha256: createHash("sha256").update(bytes).digest("hex"),
  };
}

/** 从任意一级页面进入 AI 工作区。 */
async function openAiWorkspace(sessionId) {
  const source = await readSource(sessionId);
  if (source.includes("公开历史数据分析")) return;
  await clickBottommostExactElement(sessionId, "AI");
  await waitForSourceText(sessionId, "公开历史数据分析", 40);
}

/** 核对五项一级导航均完整可见且互不重叠。 */
async function verifyMainNavigationLayout(sessionId, platformName) {
  const destinations = [];
  for (const name of MAIN_DESTINATIONS) {
    const elements = await findVisibleExactElements(sessionId, name);
    if (elements.length === 0) throw new Error(`${platformName} 一级导航缺少：${name}`);
    elements.sort((first, second) => second.elementRect.y - first.elementRect.y);
    destinations.push({ name, rect: elements[0].elementRect });
  }
  for (let firstIndex = 0; firstIndex < destinations.length; firstIndex += 1) {
    for (let secondIndex = firstIndex + 1; secondIndex < destinations.length; secondIndex += 1) {
      const first = destinations[firstIndex];
      const second = destinations[secondIndex];
      if (doRectsOverlap(first.rect, second.rect)) {
        throw new Error(`${platformName} 一级导航发生重叠：${first.name} 与 ${second.name}`);
      }
    }
  }
}

/** 查找 Android 文案元素对应的可选择父控件。 */
async function findAndroidSelectableElement(sessionId, accessibilityName) {
  const nameLiteral = JSON.stringify(accessibilityName);
  const elements = await findElementsByXPath(sessionId, `//*[@text=${nameLiteral}]/..`);
  return elements[0];
}

/** 查找 iOS 文案对应的可选择按钮控件。 */
async function findIOSSelectableElement(sessionId, accessibilityName) {
  const nameLiteral = JSON.stringify(accessibilityName);
  const elements = await findElementsByXPath(
    sessionId,
    `//XCUIElementTypeButton[@name=${nameLiteral} or @label=${nameLiteral} or @value=${nameLiteral}]`,
  );
  return elements[0];
}

/** 等待分段控件或筛选项进入平台对应的选中状态。 */
async function waitForElementSelected(sessionId, accessibilityName, platformName) {
  let selectedValue;
  for (let attempt = 0; attempt < 16; attempt += 1) {
    const element =
      platformName === "Android"
        ? await findAndroidSelectableElement(sessionId, accessibilityName)
        : await findIOSSelectableElement(sessionId, accessibilityName);
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

/** 选择一个可滚动到达的分段控件或筛选项。 */
async function selectOption(sessionId, accessibilityName, platformName, direction = "up") {
  const element = await scrollElementIntoView(sessionId, accessibilityName, direction);
  if (platformName === "iOS") {
    const selectableElement = await findIOSSelectableElement(sessionId, accessibilityName);
    const elementId = readElementId(selectableElement, accessibilityName);
    await webdriverRequest(`/session/${sessionId}/element/${elementId}/click`, "POST", {});
    await waitForPageUpdate();
  } else {
    await tapElementCenter(sessionId, element.elementRect);
  }
  await waitForElementSelected(sessionId, accessibilityName, platformName);
}

/** 在横向筛选条中找到并选择指定选项。 */
async function selectHorizontalOption(
  sessionId,
  anchorName,
  optionNames,
  targetName,
  platformName,
) {
  const targetIndex = optionNames.indexOf(targetName);
  if (targetIndex < 0) throw new Error(`未知横向筛选项：${targetName}`);
  await scrollElementIntoView(sessionId, anchorName, "up");
  for (let attempt = 0; attempt < 16; attempt += 1) {
    const targets = await findVisibleExactElements(sessionId, targetName);
    if (targets.length > 0) {
      if (platformName === "iOS") {
        const selectableElement = await findIOSSelectableElement(sessionId, targetName);
        const elementId = readElementId(selectableElement, targetName);
        await webdriverRequest(`/session/${sessionId}/element/${elementId}/click`, "POST", {});
      } else {
        await tapElementCenter(sessionId, targets[0].elementRect);
      }
      await waitForElementSelected(sessionId, targetName, platformName);
      return;
    }

    const visibleOptions = [];
    for (const optionName of optionNames) {
      const elements = await findVisibleExactElements(sessionId, optionName);
      if (elements.length > 0) {
        visibleOptions.push({ index: optionNames.indexOf(optionName), rect: elements[0].elementRect });
      }
    }
    if (visibleOptions.length === 0) throw new Error(`当前窗口无法定位筛选条：${anchorName}`);
    visibleOptions.sort((first, second) => first.index - second.index);
    const rowRect = visibleOptions[0].rect;
    const windowRect = await webdriverRequest(`/session/${sessionId}/window/rect`);
    const centerY = Math.round(rowRect.y + rowRect.height / 2);
    const movesTowardLargerOptions = targetIndex > visibleOptions.at(-1).index;
    const leftX = Math.round(windowRect.x + windowRect.width * 0.28);
    const rightX = Math.round(windowRect.x + windowRect.width * 0.88);
    await performTouchSwipe(
      sessionId,
      movesTowardLargerOptions ? rightX : leftX,
      centerY,
      movesTowardLargerOptions ? leftX : rightX,
      centerY,
      520,
    );
    await waitForPageUpdate(350);
  }
  throw new Error(`横向滚动后仍无法选择：${targetName}`);
}

/** 滚动到状态卡并等待完整 500 期官方历史开奖就绪。 */
async function waitForFullHistoryReady(sessionId, platformName) {
  await scrollElementIntoView(sessionId, "查看发送内容", "up");
  for (let attempt = 0; attempt < 120; attempt += 1) {
    const source = await readSource(sessionId);
    if (source.includes("历史开奖暂不可用")) {
      throw new Error(`${platformName} 历史开奖加载失败`);
    }
    const readyMatch = source.match(/已就绪 (\d+) 期/);
    if (readyMatch) {
      const drawCount = Number.parseInt(readyMatch[1], 10);
      if (drawCount !== 500) {
        throw new Error(`${platformName} AI 历史开奖不是完整 500 期：${drawCount}`);
      }
      return source;
    }
    await waitForPageUpdate(500);
  }
  throw new Error(`${platformName} 等待完整 500 期官方历史开奖超时`);
}

/** 返回当前平台的可编辑文本框 XPath。 */
function editableFieldXPath(platformName) {
  return platformName === "Android"
    ? "//android.widget.EditText"
    : "//XCUIElementTypeTextView";
}

/** 查找与可见标签位于同一边界内的可编辑文本框。 */
async function findEditableFieldByLabel(sessionId, label, platformName) {
  if (platformName === "iOS") {
    const labelLiteral = JSON.stringify(label);
    const fields = await findElementsByXPath(
      sessionId,
      `//XCUIElementTypeTextView[contains(@name, ${labelLiteral}) or ` +
        `contains(@label, ${labelLiteral})]`,
    );
    if (fields.length === 0) throw new Error(`iOS 无法关联输入框标签：${label}`);
    return { elementId: readElementId(fields[0], `${label} 输入框`) };
  }
  const labelElement = await findVisibleExactElement(sessionId, label);
  const fieldElements = await findElementsByXPath(sessionId, editableFieldXPath(platformName));
  const visibleFields = await filterVisibleElements(sessionId, fieldElements, `${label} 输入框`);
  const labelCenterX = labelElement.elementRect.x + labelElement.elementRect.width / 2;
  const labelCenterY = labelElement.elementRect.y + labelElement.elementRect.height / 2;
  const containingField = visibleFields.find(({ elementRect }) => {
    return (
      labelCenterX >= elementRect.x &&
      labelCenterX <= elementRect.x + elementRect.width &&
      labelCenterY >= elementRect.y &&
      labelCenterY <= elementRect.y + elementRect.height
    );
  });
  if (containingField) return containingField;
  if (visibleFields.length === 1) return visibleFields[0];
  throw new Error(`${platformName} 无法关联输入框标签：${label}`);
}

/** 尽力收起当前平台软键盘。 */
async function hideKeyboard(sessionId) {
  try {
    await webdriverRequest(`/session/${sessionId}/appium/device/hide_keyboard`, "POST", {});
  } catch {
    // 键盘已经隐藏时 Appium 可能返回平台错误，不改变输入验收结论。
  }
  await waitForPageUpdate(250);
}

/** iOS Compose 重组导致元素标识更新时，重新定位并聚焦输入框。 */
async function focusIOSInputField(sessionId, label) {
  let lastError;
  for (let attempt = 0; attempt < 6; attempt += 1) {
    const field = await findEditableFieldByLabel(sessionId, label, "iOS");
    try {
      await webdriverRequest(`/session/${sessionId}/element/${field.elementId}/click`, "POST", {});
      return;
    } catch (error) {
      if (error.webdriverError !== "stale element reference") throw error;
      lastError = error;
      await waitForPageUpdate(200);
    }
  }
  throw lastError ?? new Error(`iOS 无法聚焦输入框：${label}`);
}

/** 使用当前 iOS 键盘向已聚焦输入框发送文本，避免继续引用易失效元素。 */
async function sendIOSKeyboardValue(sessionId, label, value) {
  await focusIOSInputField(sessionId, label);
  await webdriverRequest(`/session/${sessionId}/execute/sync`, "POST", {
    script: "mobile: keys",
    args: [{ keys: [...value] }],
  });
}

/** Android 元素瞬态更新时，重新定位并完成一次完整输入。 */
async function sendInputValueWithRetry(sessionId, label, value, platformName) {
  if (platformName === "iOS") {
    await sendIOSKeyboardValue(sessionId, label, value);
    return;
  }
  let lastError;
  for (let attempt = 0; attempt < 6; attempt += 1) {
    const field = await findEditableFieldByLabel(sessionId, label, platformName);
    try {
      await webdriverRequest(`/session/${sessionId}/element/${field.elementId}/value`, "POST", {
        text: value,
        value: [...value],
      });
      return;
    } catch (error) {
      if (error.webdriverError !== "stale element reference") throw error;
      lastError = error;
      await waitForPageUpdate(200);
    }
  }
  throw lastError ?? new Error(`${platformName} 无法填写输入框：${label}`);
}

/** 替换一个标签输入框的完整文本。 */
async function replaceInputValue(sessionId, label, value, platformName) {
  await scrollElementIntoView(sessionId, label, "down");
  const field = await findEditableFieldByLabel(sessionId, label, platformName);
  await webdriverRequest(`/session/${sessionId}/element/${field.elementId}/clear`, "POST", {});
  await waitForPageUpdate(250);
  await sendInputValueWithRetry(sessionId, label, value, platformName);
  await hideKeyboard(sessionId);
}

/** 读取标签输入框的平台值。 */
async function readInputValue(sessionId, label, platformName) {
  const field = await findEditableFieldByLabel(sessionId, label, platformName);
  return webdriverRequest(
    `/session/${sessionId}/element/${field.elementId}/attribute/${platformName === "Android" ? "text" : "value"}`,
  );
}

/** 验证会话密钥默认隐藏、可主动显示并能再次隐藏。 */
async function verifySecretVisibility(sessionId, platformName) {
  let source = await readSource(sessionId);
  if (source.includes(ACCEPTANCE_API_KEY)) {
    throw new Error(`${platformName} 会话密钥在默认隐藏状态进入辅助功能树`);
  }
  await clickScrollableElement(sessionId, "显示密钥", "down");
  source = await waitForSourceText(sessionId, "隐藏密钥");
  const visibleValue = await readInputValue(sessionId, "会话密钥", platformName);
  const valueCanBeRead = source.includes(ACCEPTANCE_API_KEY) || visibleValue !== null;
  if (valueCanBeRead && !source.includes(ACCEPTANCE_API_KEY) && visibleValue !== ACCEPTANCE_API_KEY) {
    throw new Error(`${platformName} 显示密钥后输入值不可复核`);
  }
  if (platformName === "Android" && !valueCanBeRead) {
    throw new Error("Android 显示密钥后未暴露可复核输入值");
  }
  if (platformName === "iOS") {
    await saveScreenshot(sessionId, platformName, "secret-visible");
  }
  await clickScrollableElement(sessionId, "隐藏密钥", "down");
  source = await waitForSourceText(sessionId, "显示密钥");
  if (source.includes(ACCEPTANCE_API_KEY)) {
    throw new Error(`${platformName} 再次隐藏后会话密钥仍进入辅助功能树`);
  }
}

/** 从当前辅助功能树读取候选注数。 */
async function readCandidateCount(sessionId) {
  const source = await readSource(sessionId);
  const match = source.match(/(?:text|name|label|value)="([1-5]) 注"/);
  if (!match) throw new Error("无法读取当前候选注数");
  return Number.parseInt(match[1], 10);
}

/** 使用步进器验证候选注数严格限制为 1 至 5。 */
async function verifyCandidateCountBounds(sessionId) {
  await scrollElementIntoView(sessionId, "候选注数", "up");
  let count = await readCandidateCount(sessionId);
  while (count > 1) {
    await clickScrollableElement(sessionId, "减少候选注数", "up");
    count = await readCandidateCount(sessionId);
  }
  await clickScrollableElement(sessionId, "减少候选注数", "up");
  if ((await readCandidateCount(sessionId)) !== 1) {
    throw new Error("候选注数可以低于 1 注");
  }
  while (count < 5) {
    await clickScrollableElement(sessionId, "增加候选注数", "up");
    count = await readCandidateCount(sessionId);
  }
  await clickScrollableElement(sessionId, "增加候选注数", "up");
  if ((await readCandidateCount(sessionId)) !== 5) {
    throw new Error("候选注数可以高于 5 注");
  }
}

/** 验证大乐透与双色球均可取得完整会话历史，并复用返回彩种缓存。 */
async function verifyLotteryHistoryWorkflow(sessionId, platformName) {
  await waitForFullHistoryReady(sessionId, platformName);
  await selectOption(sessionId, "双色球", platformName, "down");
  await waitForFullHistoryReady(sessionId, platformName);
  await selectOption(sessionId, "大乐透", platformName, "down");
  await waitForFullHistoryReady(sessionId, platformName);
}

/** 依次选择五档样本和三个模板，并以 500 期分布结构结束。 */
async function verifyAnalysisSelectors(sessionId, platformName) {
  for (const sampleOption of SAMPLE_OPTIONS) {
    await selectHorizontalOption(
      sessionId,
      "历史样本",
      SAMPLE_OPTIONS,
      sampleOption,
      platformName,
    );
  }
  for (const templateOption of TEMPLATE_OPTIONS) {
    await selectHorizontalOption(
      sessionId,
      "分析模板",
      TEMPLATE_OPTIONS,
      templateOption,
      platformName,
    );
  }
  await verifyCandidateCountBounds(sessionId);
}

/** 填写本轮不包含真实凭据的连接配置。 */
async function fillAcceptanceConfiguration(sessionId, platformName, endpointUrl) {
  await replaceInputValue(sessionId, "服务商名称", ACCEPTANCE_PROVIDER_NAME, platformName);
  await replaceInputValue(sessionId, "Responses HTTPS 地址", endpointUrl, platformName);
  await replaceInputValue(sessionId, "模型", ACCEPTANCE_MODEL, platformName);
  await replaceInputValue(sessionId, "会话密钥", ACCEPTANCE_API_KEY, platformName);
}

/** 验证非 HTTPS 配置在联网前被本地阻断。 */
async function verifyInvalidConfiguration(sessionId, platformName) {
  await fillAcceptanceConfiguration(
    sessionId,
    platformName,
    `http://127.0.0.1:${stallServerPort}/v1/responses`,
  );
  await verifySecretVisibility(sessionId, platformName);
  await clickScrollableElement(sessionId, "查看发送内容", "up");
  await waitForSourceText(sessionId, INSECURE_ENDPOINT_MESSAGE);
  const source = await readSource(sessionId);
  if (source.includes("确认发送公开历史数据")) {
    throw new Error(`${platformName} 非 HTTPS 配置仍生成发送确认`);
  }
  await saveScreenshot(sessionId, platformName, "invalid-configuration");
}

/** 返回当前平台可以访问宿主机停滞端口的 HTTPS 地址。 */
function acceptanceEndpoint(platformName) {
  const host = platformName === "Android" ? "10.0.2.2" : "127.0.0.1";
  return `https://${host}:${stallServerPort}/v1/responses`;
}

/** 核对确认框包含实际发送摘要且从未回显密钥。 */
async function verifyPreviewDialog(sessionId, platformName, layoutName) {
  let source = await waitForSourceText(sessionId, "确认发送公开历史数据");
  for (const text of [
    ACCEPTANCE_PROVIDER_NAME,
    ACCEPTANCE_MODEL,
    "历史样本",
    "500 期",
  ]) {
    if (!source.includes(text)) throw new Error(`${platformName} 发送确认缺少：${text}`);
  }
  const expectedHost = platformName === "Android" ? "10.0.2.2" : "127.0.0.1";
  if (!source.includes(`${expectedHost}:${stallServerPort}`)) {
    throw new Error(`${platformName} 发送确认主机与实际请求不一致`);
  }
  if (source.includes(ACCEPTANCE_API_KEY)) {
    throw new Error(`${platformName} 发送确认回显会话密钥`);
  }
  await waitForSourcePattern(sessionId, /\d{5} 至 \d{5}，共 500 期/);
  await saveScreenshot(sessionId, platformName, `${layoutName}-preview-top`);
  await scrollContainerUntilVisible(sessionId, "历史快照", platformName, "up");
  await scrollContainerUntilVisible(sessionId, "候选数量", platformName, "up");
  await scrollContainerUntilVisible(sessionId, "5 注", platformName, "up");
  source = await readSource(sessionId);
  for (const text of ["分析模板", "分布结构", "候选数量", "5 注"]) {
    if (!source.includes(text)) throw new Error(`${platformName} 精确发送摘要缺少：${text}`);
  }
  await scrollContainerUntilVisible(sessionId, "发送字段", platformName, "up");
  await scrollContainerUntilVisible(sessionId, "完整请求体", platformName, "up");
  await scrollContainerUntilVisible(sessionId, "输出上限", platformName, "up");
  source = await readSource(sessionId);
  if (!/\d+ 字节/.test(source)) throw new Error(`${platformName} 确认框缺少请求字节数`);
  await scrollContainerUntilVisible(sessionId, "2048 token", platformName, "up");
  source = await readSource(sessionId);
  if (!source.includes("2048 token")) throw new Error(`${platformName} 确认框输出上限不匹配`);
  await scrollContainerUntilVisible(sessionId, "确认标识", platformName, "up");
  await scrollContainerUntilVisible(sessionId, SINGLE_SEND_NOTICE, platformName, "up");
  await scrollSmallestContainerToEnd(sessionId, platformName);
  const [dismissButton, confirmButton] = await Promise.all([
    findVisibleExactElement(sessionId, "返回修改"),
    findVisibleExactElement(sessionId, "确认并发送"),
  ]);
  if (doRectsOverlap(dismissButton.elementRect, confirmButton.elementRect)) {
    throw new Error(`${platformName} 发送确认按钮发生重叠`);
  }
  await saveScreenshot(sessionId, platformName, `${layoutName}-preview-details`);
}

/** 验证竖屏确认框的精确摘要、内部滚动和按钮布局。 */
async function verifyPortraitPreview(sessionId, platformName) {
  await clickScrollableElement(sessionId, "查看发送内容", "up");
  await verifyPreviewDialog(sessionId, platformName, "portrait");
  await clickBottommostExactElement(sessionId, "返回修改");
  await waitForSourceWithoutText(sessionId, "确认发送公开历史数据");
}

/** 验证 AI 页面和确认框横屏后仍可完整操作。 */
async function verifyLandscapeLayout(sessionId, platformName) {
  await scrollElementIntoView(sessionId, "公开历史数据分析", "down");
  const landscapeWindow = await setOrientation(sessionId, LANDSCAPE_ORIENTATION);
  await waitForSourceText(sessionId, "公开历史数据分析");
  await verifyMainNavigationLayout(sessionId, platformName);
  const pageScreenshot = await saveScreenshot(sessionId, platformName, "landscape-page");
  if (
    landscapeWindow.width <= landscapeWindow.height ||
    pageScreenshot.width <= pageScreenshot.height
  ) {
    throw new Error(`${platformName} AI 页面横屏证据宽高异常`);
  }
  await scrollElementIntoView(sessionId, "查看发送内容", "up");
  await clickScrollableElement(sessionId, "查看发送内容", "up");
  await verifyPreviewDialog(sessionId, platformName, "landscape");
  const dialogScreenshot = await saveScreenshot(sessionId, platformName, "landscape-preview");
  if (dialogScreenshot.width <= dialogScreenshot.height) {
    throw new Error(`${platformName} AI 确认框横屏证据宽高异常`);
  }
  await setOrientation(sessionId, PORTRAIT_ORIENTATION);
  await waitForSourceText(sessionId, "确认发送公开历史数据");
  await clickBottommostExactElement(sessionId, "返回修改");
  await waitForSourceWithoutText(sessionId, "确认发送公开历史数据");
}

/** 验证单次确认进入请求状态后可以主动取消且不会产生结果。 */
async function verifyRequestCancellation(sessionId, platformName) {
  await clickScrollableElement(sessionId, "查看发送内容", "up");
  await waitForSourceText(sessionId, "确认发送公开历史数据");
  await clickBottommostExactElement(sessionId, "确认并发送");
  let source = await waitForSourceText(sessionId, "正在等待 AI 服务", 20);
  for (const text of ["请求不会自动重试", "取消请求"]) {
    if (!source.includes(text)) throw new Error(`${platformName} 请求中状态缺少：${text}`);
  }
  if (source.includes(ACCEPTANCE_API_KEY)) {
    throw new Error(`${platformName} 请求中状态回显会话密钥`);
  }
  await saveScreenshot(sessionId, platformName, "requesting");
  await clickBottommostExactElement(sessionId, "取消请求");
  source = await waitForSourceText(sessionId, CANCELLED_REQUEST_MESSAGE);
  if (source.includes("分析结果") || source.includes("第 1 注")) {
    throw new Error(`${platformName} 主动取消后仍展示 AI 结果`);
  }
  await waitForSourceWithoutText(sessionId, "正在等待 AI 服务");
  await saveScreenshot(sessionId, platformName, "cancelled");
}

/** 执行当前平台完整 V1.4 AI 交互和布局验收。 */
async function verifyPlatform(sessionId, platformName) {
  await openAiWorkspace(sessionId);
  await verifyMainNavigationLayout(sessionId, platformName);
  await saveScreenshot(sessionId, platformName, "portrait-top");
  await verifyLotteryHistoryWorkflow(sessionId, platformName);
  await saveScreenshot(sessionId, platformName, "history-ready");
  await verifyAnalysisSelectors(sessionId, platformName);
  await verifyInvalidConfiguration(sessionId, platformName);
  await replaceInputValue(
    sessionId,
    "Responses HTTPS 地址",
    acceptanceEndpoint(platformName),
    platformName,
  );
  await verifyPortraitPreview(sessionId, platformName);
  await verifyLandscapeLayout(sessionId, platformName);
  await verifyRequestCancellation(sessionId, platformName);
  process.stdout.write(`${platformName} V1.4 AI 工作区检查通过\n`);
}

/** 创建 Android 会话并执行 V1.4 专项。 */
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
      "appium:newCommandTimeout": 240,
    });
    await verifyPlatform(sessionId, "Android");
  } finally {
    await restorePortraitOrientation(sessionId);
    await deleteSession(sessionId);
  }
}

/** 创建 iOS 会话并执行 V1.4 专项。 */
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
      "appium:wdaLocalPort": 8105,
      "appium:newCommandTimeout": 240,
    });
    await verifyPlatform(sessionId, "iOS");
  } finally {
    await restorePortraitOrientation(sessionId);
    await deleteSession(sessionId);
  }
}

if (targetPlatform === "both" || targetPlatform === "android") await verifyAndroid();
if (targetPlatform === "both" || targetPlatform === "ios") await verifyIOS();
