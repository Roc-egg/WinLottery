import { mkdir, writeFile } from "node:fs/promises";
import { join } from "node:path";

/** Appium 服务根地址。 */
const appiumBaseUrl = process.argv[2];

/** 当前验收阶段。 */
const acceptancePhase = process.argv[3];

/** 本轮截图输出目录。 */
const screenshotDirectory = process.argv[4];

/** 可选的单平台诊断目标，正式入口默认执行双端。 */
const targetPlatform = process.argv[13] ?? "both";

/** 真实导入阶段由宿主放入系统文件区的逻辑包名称。 */
const transferFileName = process.argv[14];

/** Android 会话参数。 */
const androidConfig = {
  platformName: "Android",
  udid: process.argv[5],
  deviceName: process.argv[6],
  packageName: process.argv[7],
  activityName: process.argv[8],
  issue: "26997",
  defaultRecordName: "大乐透 26997",
  recordName: "V1.1 Android 自动验收",
};

/** iOS 会话参数。 */
const iosConfig = {
  platformName: "iOS",
  udid: process.argv[9],
  deviceName: process.argv[10],
  platformVersion: process.argv[11],
  bundleId: process.argv[12],
  issue: "26998",
  defaultRecordName: "大乐透 26998",
  recordName: "v11 ios test",
};

/** 导出前必须出现的投注信息提示。 */
const EXPORT_PRIVACY_MESSAGE =
  "导出文件包含彩种、期号、投注号码、倍数、期数和金额等投注信息。请保存到你信任的位置。";

/** 大乐透前区合成号码。 */
const PRIMARY_NUMBERS = ["01", "02", "03", "04", "05"];

/** 大乐透后区合成号码。 */
const SECONDARY_NUMBERS = ["01", "02"];

/** 支持的标准字号准备阶段。 */
const PREPARE_PHASE = "prepare";

/** 支持的最大字号验收阶段。 */
const ACCEPT_PHASE = "accept";

/** 支持的真实逻辑包导出阶段。 */
const EXPORT_PHASE = "export";

/** 支持的真实逻辑包导入阶段。 */
const IMPORT_PHASE = "import";

/** 支持的合成记录清理阶段。 */
const CLEANUP_PHASE = "cleanup";

/** 早期诊断阶段使用过的合成记录名称。 */
const LEGACY_SYNTHETIC_RECORD_NAME = "大乐透 26999";

/** 单个合成名称允许清理的最大记录数。 */
const MAX_SYNTHETIC_RECORD_DELETE_COUNT = 20;

if (![PREPARE_PHASE, ACCEPT_PHASE, EXPORT_PHASE, IMPORT_PHASE, CLEANUP_PHASE].includes(acceptancePhase)) {
  throw new Error(`不支持的记录验收阶段：${acceptancePhase}`);
}
if (!["both", "android", "ios"].includes(targetPlatform)) {
  throw new Error(`不支持的记录验收平台：${targetPlatform}`);
}
if (acceptancePhase === IMPORT_PHASE && !transferFileName?.endsWith(".wltickets.json")) {
  throw new Error("真实导入阶段缺少 .wltickets.json 文件名");
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

/** 判断两个元素矩形是否发生正面积重叠。 */
function rectsOverlap(firstRect, secondRect) {
  return (
    Math.min(firstRect.x + firstRect.width, secondRect.x + secondRect.width) >
      Math.max(firstRect.x, secondRect.x) &&
    Math.min(firstRect.y + firstRect.height, secondRect.y + secondRect.height) >
      Math.max(firstRect.y, secondRect.y)
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

/** 等待 Compose 或系统页面完成一次状态更新。 */
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
    await waitForPageUpdate(300);
  }
  throw new Error(`当前窗口缺少完整可见元素：${accessibilityName}`);
}

/** 返回当前窗口内属性包含指定文本的可见元素快照。 */
async function findVisibleContainingElements(sessionId, text) {
  const textLiteral = JSON.stringify(text);
  const elements = await webdriverRequest(`/session/${sessionId}/elements`, "POST", {
    using: "xpath",
    value:
      `//*[contains(@content-desc, ${textLiteral}) or contains(@text, ${textLiteral}) or ` +
      `contains(@name, ${textLiteral}) or contains(@label, ${textLiteral}) or ` +
      `contains(@value, ${textLiteral})]`,
  });
  const windowRect = await webdriverRequest(`/session/${sessionId}/window/rect`);
  const visibleElements = [];
  for (const element of elements) {
    const elementId = readElementId(element, text);
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

/** 点击首个当前可见的候选文案，并返回实际命中的文案。 */
async function clickFirstVisibleCandidate(sessionId, accessibilityNames, platformName) {
  for (const accessibilityName of accessibilityNames) {
    const elements = await findVisibleExactElements(sessionId, accessibilityName);
    if (elements.length > 0) {
      await tapElementCenter(sessionId, elements[0].elementRect, platformName);
      return accessibilityName;
    }
  }
  throw new Error(`当前窗口缺少候选元素：${accessibilityNames.join("、")}`);
}

/** 点击当前窗口中垂直位置最靠下的同名元素，避开与按钮同名的标题。 */
async function clickBottommostVisibleExactElement(sessionId, accessibilityName, platformName) {
  const elements = await findVisibleExactElements(sessionId, accessibilityName);
  if (elements.length === 0) throw new Error(`当前窗口缺少完整可见元素：${accessibilityName}`);
  elements.sort((first, second) => second.elementRect.y - first.elementRect.y);
  await tapElementCenter(sessionId, elements[0].elementRect, platformName);
}

/** 等待 iOS 滚动减速结束并返回稳定的元素矩形。 */
async function waitForStableExactElementRect(sessionId, accessibilityName) {
  let previousRect;
  for (let attempt = 0; attempt < 12; attempt += 1) {
    const element = await findVisibleExactElement(sessionId, accessibilityName);
    if (isSameRect(previousRect, element.elementRect)) return element.elementRect;
    previousRect = element.elementRect;
    await waitForPageUpdate(300);
  }
  throw new Error(`等待 iOS 元素位置稳定超时：${accessibilityName}`);
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

/** 点击当前窗口内完整可见的同名元素。 */
async function clickVisibleExactElement(sessionId, accessibilityName, platformName) {
  const element = await findVisibleExactElement(sessionId, accessibilityName);
  await tapElementCenter(sessionId, element.elementRect, platformName);
  return element;
}

/** 在当前页面执行一次真实向上滑动。 */
async function scrollUp(sessionId) {
  const [windowRect, keyboardShown] = await Promise.all([
    webdriverRequest(`/session/${sessionId}/window/rect`),
    webdriverRequest(`/session/${sessionId}/appium/device/is_keyboard_shown`).catch(() => false),
  ]);
  const x = Math.round(windowRect.width / 2);
  const startY = Math.round(windowRect.height * (keyboardShown ? 0.55 : 0.78));
  const endY = Math.round(windowRect.height * (keyboardShown ? 0.16 : 0.24));
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

/** 按需真实滚动，直到指定元素完整进入窗口。 */
async function scrollElementIntoView(sessionId, accessibilityName) {
  for (let scrollCount = 0; scrollCount <= 14; scrollCount += 1) {
    const elements = await findVisibleExactElements(sessionId, accessibilityName);
    if (elements.length > 0) return { ...elements[0], scrollCount };
    await scrollUp(sessionId);
  }
  const source = await readSource(sessionId);
  throw new Error(`滚动后仍无法访问元素：${accessibilityName}；页面含文案=${source.includes(accessibilityName)}`);
}

/** 按需滚动后点击指定元素。 */
async function clickScrollableExactElement(sessionId, accessibilityName, platformName) {
  const element = await scrollElementIntoView(sessionId, accessibilityName);
  const elementRect =
    platformName === "iOS"
      ? await waitForStableExactElementRect(sessionId, accessibilityName)
      : element.elementRect;
  await tapElementCenter(sessionId, elementRect, platformName);
  return element.scrollCount;
}

/** 等待辅助功能树出现指定文案。 */
async function waitForSourceText(sessionId, expectedText, attemptCount = 20) {
  let source = "";
  for (let attempt = 0; attempt < attemptCount; attempt += 1) {
    source = await readSource(sessionId);
    if (source.includes(expectedText)) return source;
    await waitForPageUpdate(500);
  }
  throw new Error(`等待页面文案超时：${expectedText}`);
}

/** 等待辅助功能树出现任一指定文案。 */
async function waitForAnySourceText(sessionId, expectedTexts, attemptCount = 20) {
  let source = "";
  for (let attempt = 0; attempt < attemptCount; attempt += 1) {
    source = await readSource(sessionId);
    if (expectedTexts.some((text) => source.includes(text))) return source;
    await waitForPageUpdate(500);
  }
  throw new Error(`等待页面任一文案超时：${expectedTexts.join("、")}`);
}

/** 等待辅助功能树不再包含指定文案。 */
async function waitForSourceTextAbsent(sessionId, absentText, attemptCount = 20) {
  let source = "";
  for (let attempt = 0; attempt < attemptCount; attempt += 1) {
    source = await readSource(sessionId);
    if (!source.includes(absentText)) return source;
    await waitForPageUpdate(500);
  }
  throw new Error(`等待页面文案消失超时：${absentText}`);
}

/** 查找当前窗口最接近垂直中心的文本输入节点。 */
async function findVisibleEditableElement(sessionId, fieldName, platformName) {
  const elements =
    platformName === "Android"
      ? await webdriverRequest(`/session/${sessionId}/elements`, "POST", {
          using: "class name",
          value: "android.widget.EditText",
        })
      : await webdriverRequest(`/session/${sessionId}/elements`, "POST", {
          using: "xpath",
          value: `//XCUIElementTypeTextView[@name=${JSON.stringify(fieldName)}]`,
        });
  const windowRect = await webdriverRequest(`/session/${sessionId}/window/rect`);
  const candidates = [];
  for (const element of elements) {
    const elementId = readElementId(element, fieldName);
    const [elementRect, displayed] = await Promise.all([
      webdriverRequest(`/session/${sessionId}/element/${elementId}/rect`),
      webdriverRequest(`/session/${sessionId}/element/${elementId}/displayed`),
    ]);
    if (displayed && isRectInsideWindow(elementRect, windowRect)) {
      const centerDistance = Math.abs(elementRect.y + elementRect.height / 2 - windowRect.height / 2);
      candidates.push({ elementId, elementRect, centerDistance });
    }
  }
  candidates.sort((first, second) => first.centerDistance - second.centerDistance);
  if (candidates.length === 0) throw new Error(`${platformName} 缺少可见的${fieldName}输入框`);
  return candidates[0];
}

/** 等待文本输入节点精确暴露指定值。 */
async function waitForEditableElementValue(sessionId, elementId, expectedValue, platformName) {
  let actualValue;
  for (let attempt = 0; attempt < 20; attempt += 1) {
    actualValue = await webdriverRequest(
      `/session/${sessionId}/element/${elementId}/attribute/${platformName === "Android" ? "text" : "value"}`,
    );
    if (actualValue === expectedValue || (expectedValue === "" && actualValue == null)) return;
    await waitForPageUpdate(100);
  }
  throw new Error(`${platformName} 输入值未稳定为 ${expectedValue}：${actualValue}`);
}

/** 清空并写入指定文本输入节点。 */
async function replaceEditableValue(sessionId, fieldName, platformName, value) {
  const editable = await findVisibleEditableElement(sessionId, fieldName, platformName);
  await webdriverRequest(`/session/${sessionId}/element/${editable.elementId}/click`, "POST", {});
  await webdriverRequest(`/session/${sessionId}/element/${editable.elementId}/clear`, "POST", {});
  await waitForEditableElementValue(sessionId, editable.elementId, "", platformName);
  if (platformName === "Android") {
    await webdriverRequest(`/session/${sessionId}/execute/sync`, "POST", {
      script: "mobile: type",
      args: [{ text: value }],
    });
  } else {
    let expectedPrefix = "";
    for (const character of value) {
      await webdriverRequest(`/session/${sessionId}/element/${editable.elementId}/value`, "POST", {
        text: character,
        value: [character],
      });
      expectedPrefix += character;
      await waitForEditableElementValue(sessionId, editable.elementId, expectedPrefix, platformName);
    }
  }
  await waitForEditableElementValue(sessionId, editable.elementId, value, platformName);
  await waitForPageUpdate();
}

/** 尽力隐藏系统键盘，后续仍通过页面元素验证实际状态。 */
async function hideKeyboard(sessionId) {
  try {
    await webdriverRequest(`/session/${sessionId}/appium/device/hide_keyboard`, "POST", {});
  } catch {
    // 部分 iOS 驱动不支持通用隐藏命令，页面按钮仍可触发输入完成。
  }
  await waitForPageUpdate();
}

/** 点击指定号码区当前可见的合成号码。 */
async function clickNumberInSection(
  sessionId,
  sectionName,
  selectedCount,
  expectedCount,
  number,
  platformName,
) {
  const currentLabel = `${sectionName}（已选 ${selectedCount}/${expectedCount}）`;
  const nextLabel = `${sectionName}（已选 ${selectedCount + 1}/${expectedCount}）`;
  const section = await scrollElementIntoView(sessionId, currentLabel);
  const numberElements = await findVisibleExactElements(sessionId, number);
  const sectionBottom = section.elementRect.y + section.elementRect.height;
  const candidate = numberElements
    .filter((element) => element.elementRect.y >= sectionBottom - 2)
    .sort((first, second) => first.elementRect.y - second.elementRect.y)[0];
  if (!candidate) throw new Error(`${platformName} ${currentLabel} 下方缺少号码 ${number}`);
  await tapElementCenter(sessionId, candidate.elementRect, platformName);
  await waitForSourceText(sessionId, nextLabel);
}

/** 等待指定同名按钮进入可点击状态。 */
async function waitForElementEnabled(sessionId, accessibilityName) {
  let enabledValue;
  for (let attempt = 0; attempt < 15; attempt += 1) {
    const element = await findVisibleExactElement(sessionId, accessibilityName);
    enabledValue = await webdriverRequest(
      `/session/${sessionId}/element/${element.elementId}/attribute/enabled`,
    );
    if (enabledValue === true || enabledValue === "true") return element;
    await waitForPageUpdate(300);
  }
  const source = await readSource(sessionId);
  const problemAnchor = source.indexOf("请先修正以下信息");
  const problemContext =
    problemAnchor < 0
      ? "辅助功能树未暴露校验摘要"
      : source.slice(problemAnchor, problemAnchor + 1800);
  throw new Error(
    `元素未进入可点击状态：${accessibilityName}；enabled=${enabledValue}；` +
      `校验上下文=${problemContext}`,
  );
}

/** 从手动录入走真实保存链路，生成一条合成记录。 */
async function createSyntheticRecord(sessionId, config) {
  await clickScrollableExactElement(sessionId, "手动录入彩票", config.platformName);
  await clickScrollableExactElement(sessionId, "超级大乐透", config.platformName);
  await replaceEditableValue(sessionId, "开奖期号", config.platformName, config.issue);
  await clickScrollableExactElement(sessionId, "完成期号输入", config.platformName);
  await hideKeyboard(sessionId);

  for (let index = 0; index < PRIMARY_NUMBERS.length; index += 1) {
    await clickNumberInSection(
      sessionId,
      "前区",
      index,
      PRIMARY_NUMBERS.length,
      PRIMARY_NUMBERS[index],
      config.platformName,
    );
  }
  for (let index = 0; index < SECONDARY_NUMBERS.length; index += 1) {
    await clickNumberInSection(
      sessionId,
      "后区",
      index,
      SECONDARY_NUMBERS.length,
      SECONDARY_NUMBERS[index],
      config.platformName,
    );
  }

  await clickScrollableExactElement(sessionId, "确认 1 倍", config.platformName);
  await clickScrollableExactElement(sessionId, "基本", config.platformName);
  await clickScrollableExactElement(sessionId, "使用计算金额", config.platformName);
  const confirm = await scrollElementIntoView(sessionId, "已核对，继续");
  await waitForElementEnabled(sessionId, "已核对，继续");
  await tapElementCenter(sessionId, confirm.elementRect, config.platformName);
  await waitForAnySourceText(sessionId, ["查询开奖", "开奖核对", "中奖测算结果"]);

  await clickVisibleExactElement(sessionId, "返回", config.platformName);
  await waitForSourceText(sessionId, "手动录入彩票");
  await clickVisibleExactElement(sessionId, "返回", config.platformName);
  await waitForSourceText(sessionId, "本机记录");
  await clickScrollableExactElement(sessionId, "本机记录", config.platformName);
  await waitForSourceText(sessionId, config.defaultRecordName, 30);
}

/** 点击指定记录标题所在卡片中的操作图标。 */
async function clickRecordAction(sessionId, recordName, actionName, platformName) {
  const recordTitle = await scrollElementIntoView(sessionId, recordName);
  const actionElements = await findVisibleExactElements(sessionId, actionName);
  const titleCenterY = recordTitle.elementRect.y + recordTitle.elementRect.height / 2;
  const action = actionElements
    .map((element) => ({
      ...element,
      verticalDistance: Math.abs(element.elementRect.y + element.elementRect.height / 2 - titleCenterY),
    }))
    .sort((first, second) => first.verticalDistance - second.verticalDistance)[0];
  if (!action) throw new Error(`记录“${recordName}”附近缺少操作：${actionName}`);
  await tapElementCenter(sessionId, action.elementRect, platformName);
  return { recordTitle, action };
}

/** 把刚创建的默认记录重命名为可安全清理的验收名称。 */
async function renameSyntheticRecord(sessionId, config) {
  await clickRecordAction(sessionId, config.defaultRecordName, "重命名记录", config.platformName);
  await waitForSourceText(sessionId, "记录名称");
  await replaceEditableValue(sessionId, "记录名称", config.platformName, config.recordName);
  await waitForSourceText(sessionId, config.recordName);
  if (config.platformName === "Android") {
    await webdriverRequest(`/session/${sessionId}/execute/sync`, "POST", {
      script: "mobile: performEditorAction",
      args: [{ action: "done" }],
    });
    await waitForPageUpdate();
  } else {
    await hideKeyboard(sessionId);
    const saveElements = await findVisibleExactElements(sessionId, "保存");
    if (saveElements.length > 0) {
      await tapElementCenter(sessionId, saveElements[0].elementRect, config.platformName);
    }
  }
  await waitForSourceText(sessionId, config.recordName);
}

/** 断言指定元素完整位于当前窗口并返回其矩形。 */
async function assertElementInsideWindow(sessionId, accessibilityName) {
  const element = await findVisibleExactElement(sessionId, accessibilityName);
  return element.elementRect;
}

/** 断言两个界面元素没有发生正面积重叠。 */
function assertNoOverlap(firstRect, secondRect, description) {
  if (rectsOverlap(firstRect, secondRect)) {
    throw new Error(`${description}发生重叠：${JSON.stringify(firstRect)} / ${JSON.stringify(secondRect)}`);
  }
}

/** 保存当前设备原始截图。 */
async function saveScreenshot(sessionId, config, label) {
  await mkdir(screenshotDirectory, { recursive: true });
  const encoded = await webdriverRequest(`/session/${sessionId}/screenshot`);
  const fileName = `${config.platformName.toLowerCase()}-${label}.png`;
  const outputPath = join(screenshotDirectory, fileName);
  await writeFile(outputPath, Buffer.from(encoded, "base64"));
  process.stdout.write(`${config.platformName} 截图：${outputPath}\n`);
}

/** 核对记录页顶部、筛选和合成记录卡片没有出界或相互遮挡。 */
async function verifyRecordLayout(sessionId, config, screenshotLabel) {
  const requiredHeaderTexts = ["本机记录", "名称或起始期号", "全部", "大乐透", "双色球", "记录管理"];
  for (const text of requiredHeaderTexts) await assertElementInsideWindow(sessionId, text);

  const recordTitle = await scrollElementIntoView(sessionId, config.recordName);
  const renameElements = await findVisibleExactElements(sessionId, "重命名记录");
  const deleteElements = await findVisibleExactElements(sessionId, "删除记录");
  if (renameElements.length === 0 || deleteElements.length === 0) {
    throw new Error(`${config.platformName} 记录卡片操作未完整进入窗口`);
  }
  const titleCenterY = recordTitle.elementRect.y + recordTitle.elementRect.height / 2;
  const nearestRename = renameElements.sort(
    (first, second) =>
      Math.abs(first.elementRect.y + first.elementRect.height / 2 - titleCenterY) -
      Math.abs(second.elementRect.y + second.elementRect.height / 2 - titleCenterY),
  )[0];
  const nearestDelete = deleteElements.sort(
    (first, second) =>
      Math.abs(first.elementRect.y + first.elementRect.height / 2 - titleCenterY) -
      Math.abs(second.elementRect.y + second.elementRect.height / 2 - titleCenterY),
  )[0];
  assertNoOverlap(recordTitle.elementRect, nearestRename.elementRect, "记录标题与重命名按钮");
  assertNoOverlap(nearestRename.elementRect, nearestDelete.elementRect, "重命名与删除按钮");
  await scrollElementIntoView(sessionId, "查询开奖");
  await saveScreenshot(sessionId, config, screenshotLabel);
}

/** 打开记录管理菜单并核对三个操作完整可见。 */
async function openAndVerifyManagementMenu(sessionId, config) {
  await clickVisibleExactElement(sessionId, "记录管理", config.platformName);
  await waitForSourceText(sessionId, "导入记录");
  for (const text of ["导入记录", "导出记录", "清空全部"]) {
    await assertElementInsideWindow(sessionId, text);
  }
}

/** 核对删除确认对话框并取消，不改变记录。 */
async function verifyDeleteConfirmation(sessionId, config) {
  await clickRecordAction(sessionId, config.recordName, "删除记录", config.platformName);
  const source = await waitForSourceText(sessionId, "删除记录");
  if (!source.includes(config.recordName) || !source.includes("此操作无法撤销")) {
    throw new Error(`${config.platformName} 删除确认缺少记录名称或不可撤销说明`);
  }
  const cancelRect = await assertElementInsideWindow(sessionId, "取消");
  const deleteRect = await assertElementInsideWindow(sessionId, "删除");
  assertNoOverlap(cancelRect, deleteRect, "删除确认按钮");
  await clickVisibleExactElement(sessionId, "取消", config.platformName);
  await waitForSourceText(sessionId, config.recordName);
}

/** 核对清空全部确认对话框并取消，不删除其他记录。 */
async function verifyClearConfirmation(sessionId, config) {
  await openAndVerifyManagementMenu(sessionId, config);
  await clickVisibleExactElement(sessionId, "清空全部", config.platformName);
  const source = await waitForSourceText(sessionId, "清空全部记录");
  if (!source.includes("结构化票据记录") || !source.includes("此操作无法撤销")) {
    throw new Error(`${config.platformName} 清空确认缺少范围或不可撤销说明`);
  }
  const cancelRect = await assertElementInsideWindow(sessionId, "取消");
  const clearRect = await assertElementInsideWindow(sessionId, "全部清空");
  assertNoOverlap(cancelRect, clearRect, "清空确认按钮");
  await clickVisibleExactElement(sessionId, "取消", config.platformName);
  await waitForSourceText(sessionId, config.recordName);
}

/** 断言导出隐私提示仍处于应用内，系统选择器尚未出现。 */
async function verifyExportWarning(sessionId, config) {
  await openAndVerifyManagementMenu(sessionId, config);
  await clickVisibleExactElement(sessionId, "导出记录", config.platformName);
  const source = await waitForSourceText(sessionId, EXPORT_PRIVACY_MESSAGE);
  if (!source.includes("选择保存位置")) {
    throw new Error(`${config.platformName} 导出提示缺少继续操作`);
  }
  if (config.platformName === "Android") {
    const currentPackage = await webdriverRequest(`/session/${sessionId}/appium/device/current_package`);
    if (currentPackage !== config.packageName) {
      throw new Error(`Android 导出提示出现前已离开应用：${currentPackage}`);
    }
  } else {
    const activeApp = await readIOSActiveAppInfo(sessionId);
    if (activeApp.bundleId !== config.bundleId) {
      throw new Error(`iOS 导出提示出现前已离开应用：${activeApp.bundleId}`);
    }
  }
}

/** 读取 iOS 当前前台应用信息。 */
async function readIOSActiveAppInfo(sessionId) {
  return webdriverRequest(`/session/${sessionId}/execute/sync`, "POST", {
    script: "mobile: activeAppInfo",
    args: [],
  });
}

/** 判断 iOS 系统文档选择器是否暴露了可见操作。 */
async function hasVisibleIOSPickerAction(sessionId) {
  for (const actionName of ["Cancel", "取消", "Save", "保存"]) {
    const elements = await findVisibleExactElements(sessionId, actionName);
    if (elements.length > 0) return true;
  }
  return false;
}

/** 判断本机记录页是否已真正恢复可操作状态。 */
async function isRecordPageVisible(sessionId) {
  const elements = await findVisibleExactElements(sessionId, "记录管理");
  return elements.length > 0;
}

/** 从首页进入记录页；已经位于记录页时保持当前状态。 */
async function openRecordPage(sessionId, config) {
  if (await isRecordPageVisible(sessionId)) return;
  await clickScrollableExactElement(sessionId, "本机记录", config.platformName);
  await findVisibleExactElement(sessionId, "记录管理");
}

/** 等待平台原生文件选择器真正进入前台。 */
async function waitForNativePicker(sessionId, config) {
  let source = "";
  for (let attempt = 0; attempt < 30; attempt += 1) {
    if (config.platformName === "Android") {
      const currentPackage = await webdriverRequest(`/session/${sessionId}/appium/device/current_package`);
      if (currentPackage !== config.packageName) return readSource(sessionId);
    } else {
      if (await hasVisibleIOSPickerAction(sessionId)) return readSource(sessionId);
    }
    await waitForPageUpdate(500);
  }
  throw new Error(`${config.platformName} 系统文件选择器未进入前台`);
}

/** 在系统文件选择器中查找宿主预置的短文件名。 */
async function findTransferFileInPicker(sessionId, config, fileName) {
  if (config.platformName === "iOS") {
    for (let attempt = 0; attempt < 8; attempt += 1) {
      const elements = await findVisibleContainingElements(sessionId, fileName);
      if (elements.length > 0) return elements[0];
      await waitForPageUpdate(400);
    }
    const browseNames = ["浏览", "Browse"];
    for (const browseName of browseNames) {
      const browseElements = await findVisibleExactElements(sessionId, browseName);
      if (browseElements.length > 0) {
        await tapElementCenter(sessionId, browseElements[0].elementRect, config.platformName);
        break;
      }
    }
    await waitForPageUpdate(800);
    const localNames = ["我的 iPhone", "On My iPhone"];
    for (const localName of localNames) {
      const localElements = await findVisibleExactElements(sessionId, localName);
      if (localElements.length > 0) {
        await tapElementCenter(sessionId, localElements[0].elementRect, config.platformName);
        break;
      }
    }
  } else {
    const rootNames = ["Show roots", "显示根目录", "Open navigation drawer"];
    for (const rootName of rootNames) {
      const rootElements = await findVisibleExactElements(sessionId, rootName);
      if (rootElements.length > 0) {
        await tapElementCenter(sessionId, rootElements[0].elementRect, config.platformName);
        break;
      }
    }
    await waitForPageUpdate(800);
    const downloadNames = ["Downloads", "下载"];
    for (const downloadName of downloadNames) {
      const downloadElements = await findVisibleExactElements(sessionId, downloadName);
      if (downloadElements.length > 0) {
        await tapElementCenter(sessionId, downloadElements.at(-1).elementRect, config.platformName);
        break;
      }
    }
  }

  for (let attempt = 0; attempt < 12; attempt += 1) {
    const elements =
      config.platformName === "Android"
        ? await findVisibleExactElements(sessionId, fileName)
        : await findVisibleContainingElements(sessionId, fileName);
    if (elements.length > 0) return elements[0];
    await waitForPageUpdate(500);
  }
  const source = await readSource(sessionId);
  throw new Error(`${config.platformName} 系统文件选择器缺少 ${fileName}；页面含文件名=${source.includes(fileName)}`);
}

/** 取消平台原生文件选择器并等待返回记录页。 */
async function cancelNativePicker(sessionId, config) {
  if (config.platformName === "Android") {
    await webdriverRequest(`/session/${sessionId}/back`, "POST", {});
  } else {
    const cancelNames = ["取消", "Cancel"];
    let cancelled = false;
    for (let navigationCount = 0; navigationCount < 4 && !cancelled; navigationCount += 1) {
      for (const cancelName of cancelNames) {
        const elements = await findVisibleExactElements(sessionId, cancelName);
        if (elements.length > 0) {
          await webdriverRequest(
            `/session/${sessionId}/element/${elements[0].elementId}/click`,
            "POST",
            {},
          );
          await waitForPageUpdate();
          cancelled = await isRecordPageVisible(sessionId);
          break;
        }
      }

      if (cancelled) break;
      await webdriverRequest(`/session/${sessionId}/back`, "POST", {});
      await waitForPageUpdate();
      cancelled = await isRecordPageVisible(sessionId);
    }
    if (!cancelled) throw new Error("iOS 系统文件选择器缺少可用的退出路径");
  }
  await findVisibleExactElement(sessionId, "记录管理");
  await waitForPageUpdate(800);
}

/** 打开并取消导出系统选择器。 */
async function verifyExportPicker(sessionId, config) {
  await verifyExportWarning(sessionId, config);
  await saveScreenshot(sessionId, config, "maximum-export-warning");
  await clickVisibleExactElement(sessionId, "选择保存位置", config.platformName);
  const pickerSource = await waitForNativePicker(sessionId, config);
  if (pickerSource.includes(EXPORT_PRIVACY_MESSAGE)) {
    throw new Error(`${config.platformName} 点击保存位置后仍停留在应用提示`);
  }
  await saveScreenshot(sessionId, config, "maximum-export-picker");
  await cancelNativePicker(sessionId, config);
}

/** 打开并取消导入系统选择器。 */
async function verifyImportPicker(sessionId, config) {
  await openAndVerifyManagementMenu(sessionId, config);
  await clickVisibleExactElement(sessionId, "导入记录", config.platformName);
  await waitForNativePicker(sessionId, config);
  await saveScreenshot(sessionId, config, "maximum-import-picker");
  await cancelNativePicker(sessionId, config);
}

/** 在系统保存选择器中确认一次真实导出，并等待应用写入成功。 */
async function exportRecordsThroughNativePicker(sessionId, config, screenshotLabel) {
  await openRecordPage(sessionId, config);
  await verifyExportWarning(sessionId, config);
  await clickVisibleExactElement(sessionId, "选择保存位置", config.platformName);
  await waitForNativePicker(sessionId, config);
  await saveScreenshot(sessionId, config, `${screenshotLabel}-picker`);
  await clickFirstVisibleCandidate(sessionId, ["SAVE", "Save", "保存"], config.platformName);
  const source = await waitForAnySourceText(sessionId, ["已导出", "无法写入", "所选位置无法写入"], 40);
  if (!source.includes("已导出")) {
    throw new Error(`${config.platformName} 真实导出没有成功：${source.slice(0, 1600)}`);
  }
  await saveScreenshot(sessionId, config, `${screenshotLabel}-completed`);
}

/** 通过系统打开选择器真实导入宿主预置文件，并核对对端记录可见。 */
async function importRecordsThroughNativePicker(sessionId, config, fileName, expectedRecordName) {
  await openRecordPage(sessionId, config);
  await openAndVerifyManagementMenu(sessionId, config);
  await clickVisibleExactElement(sessionId, "导入记录", config.platformName);
  await waitForNativePicker(sessionId, config);
  const fileElement = await findTransferFileInPicker(sessionId, config, fileName);
  await tapElementCenter(sessionId, fileElement.elementRect, config.platformName);
  await saveScreenshot(sessionId, config, "roundtrip-file-selected");

  let previewSource;
  try {
    previewSource = await waitForAnySourceText(sessionId, ["文件包含", "导入文件校验失败"], 40);
  } catch (error) {
    if (config.platformName === "Android") {
      const currentPackage = await webdriverRequest(`/session/${sessionId}/appium/device/current_package`);
      throw new Error(`Android 点选文件后前台包为 ${currentPackage}`, { cause: error });
    }
    throw error;
  }
  if (!previewSource.includes("文件包含")) {
    throw new Error(`${config.platformName} 跨端文件没有通过导入预检`);
  }
  if (previewSource.includes("发现记录冲突")) {
    await clickBottommostVisibleExactElement(sessionId, "保留本机并导入其余", config.platformName);
  } else {
    await clickBottommostVisibleExactElement(sessionId, "导入", config.platformName);
  }

  const resultSource = await waitForAnySourceText(sessionId, ["已导入", "没有新增记录", "导入文件校验失败"], 40);
  if (!resultSource.includes("已导入")) {
    throw new Error(`${config.platformName} 跨端文件没有新增记录：${resultSource.slice(0, 1600)}`);
  }
  await replaceEditableValue(sessionId, "名称或起始期号", config.platformName, expectedRecordName);
  await hideKeyboard(sessionId);
  await waitForSourceText(sessionId, expectedRecordName, 30);
  await saveScreenshot(sessionId, config, "roundtrip-imported-record");
}

/** 删除指定名称的所有合成记录。 */
async function deleteSyntheticRecordsByName(sessionId, config, recordName) {
  for (let attempt = 0; attempt < MAX_SYNTHETIC_RECORD_DELETE_COUNT; attempt += 1) {
    const source = await readSource(sessionId);
    if (!source.includes(recordName)) return;
    await clickRecordAction(sessionId, recordName, "删除记录", config.platformName);
    await waitForSourceText(sessionId, "此操作无法撤销");
    await clickVisibleExactElement(sessionId, "删除", config.platformName);
    await waitForPageUpdate(1000);
  }
  const source = await readSource(sessionId);
  if (source.includes(recordName)) {
    throw new Error(`${config.platformName} 合成记录清理超过安全上限：${recordName}`);
  }
}

/** 删除所有明确命名的合成记录，避免影响其他本机数据。 */
async function deleteSyntheticRecords(sessionId, config) {
  const recordNames = [
    androidConfig.recordName,
    androidConfig.defaultRecordName,
    iosConfig.recordName,
    iosConfig.defaultRecordName,
    LEGACY_SYNTHETIC_RECORD_NAME,
  ];
  for (const recordName of new Set(recordNames)) {
    await deleteSyntheticRecordsByName(sessionId, config, recordName);
  }
}

/** 执行标准字号记录准备与界面检查。 */
async function runPreparePhase(sessionId, config) {
  await openRecordPage(sessionId, config);
  await deleteSyntheticRecords(sessionId, config);
  await clickVisibleExactElement(sessionId, "返回", config.platformName);
  await createSyntheticRecord(sessionId, config);
  await renameSyntheticRecord(sessionId, config);
  await verifyRecordLayout(sessionId, config, "standard-records");
  await verifyDeleteConfirmation(sessionId, config);
  await verifyClearConfirmation(sessionId, config);
  await verifyExportWarning(sessionId, config);
  await clickVisibleExactElement(sessionId, "取消", config.platformName);
  process.stdout.write(`${config.platformName} 标准字号记录创建与管理检查通过\n`);
}

/** 执行最大字号布局与系统文件选择器检查。 */
async function runAcceptPhase(sessionId, config) {
  await openRecordPage(sessionId, config);
  await waitForSourceText(sessionId, config.recordName, 30);
  await verifyRecordLayout(sessionId, config, "maximum-records");
  await verifyDeleteConfirmation(sessionId, config);
  await verifyClearConfirmation(sessionId, config);
  await verifyExportPicker(sessionId, config);
  await verifyImportPicker(sessionId, config);
  process.stdout.write(`${config.platformName} 最大字号记录与文件选择器检查通过\n`);
}

/** 执行真实系统文件导出。 */
async function runExportPhase(sessionId, config) {
  await exportRecordsThroughNativePicker(sessionId, config, "roundtrip-export");
  process.stdout.write(`${config.platformName} 真实逻辑包导出通过\n`);
}

/** 执行对端逻辑包导入，并核对导入记录进入列表。 */
async function runImportPhase(sessionId, config) {
  const expectedRecordName = config.platformName === "Android" ? iosConfig.recordName : androidConfig.recordName;
  await importRecordsThroughNativePicker(sessionId, config, transferFileName, expectedRecordName);
  process.stdout.write(`${config.platformName} 真实跨端逻辑包导入通过\n`);
}

/** 清理双端明确命名的合成记录，不影响其他本机数据。 */
async function runCleanupPhase(sessionId, config) {
  await openRecordPage(sessionId, config);
  await deleteSyntheticRecords(sessionId, config);
  process.stdout.write(`${config.platformName} 合成记录清理通过\n`);
}

/** 创建并执行 Android 当前阶段验收。 */
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
    if (acceptancePhase === PREPARE_PHASE) await runPreparePhase(sessionId, androidConfig);
    if (acceptancePhase === ACCEPT_PHASE) await runAcceptPhase(sessionId, androidConfig);
    if (acceptancePhase === EXPORT_PHASE) await runExportPhase(sessionId, androidConfig);
    if (acceptancePhase === IMPORT_PHASE) await runImportPhase(sessionId, androidConfig);
    if (acceptancePhase === CLEANUP_PHASE) await runCleanupPhase(sessionId, androidConfig);
  } finally {
    await deleteSession(sessionId);
  }
}

/** 创建并执行 iOS 当前阶段验收。 */
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
      "appium:wdaLocalPort": 8102,
      "appium:newCommandTimeout": 180,
    });
    if (acceptancePhase === PREPARE_PHASE) await runPreparePhase(sessionId, iosConfig);
    if (acceptancePhase === ACCEPT_PHASE) await runAcceptPhase(sessionId, iosConfig);
    if (acceptancePhase === EXPORT_PHASE) await runExportPhase(sessionId, iosConfig);
    if (acceptancePhase === IMPORT_PHASE) await runImportPhase(sessionId, iosConfig);
    if (acceptancePhase === CLEANUP_PHASE) await runCleanupPhase(sessionId, iosConfig);
  } finally {
    await deleteSession(sessionId);
  }
}

if (targetPlatform === "both" || targetPlatform === "android") await verifyAndroid();
if (targetPlatform === "both" || targetPlatform === "ios") await verifyIOS();
