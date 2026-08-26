import { createHash } from "node:crypto";
import { mkdir, writeFile } from "node:fs/promises";
import { join } from "node:path";

/** Appium 服务根地址。 */
const appiumBaseUrl = process.argv[2];

/** 当前验收模式。 */
const acceptanceMode = process.argv[3];

/** 截图证据目录。 */
const screenshotDirectory = process.argv[4];

/** Android 会话参数。 */
const androidConfig = {
  udid: process.argv[5],
  deviceName: process.argv[6],
  packageName: process.argv[7],
  activityName: process.argv[8],
};

/** iOS 会话参数。 */
const iosConfig = {
  udid: process.argv[9],
  deviceName: process.argv[10],
  platformVersion: process.argv[11],
  bundleId: process.argv[12],
};

/** 可选的单平台诊断目标；正式脚本不传时固定执行双端。 */
const targetPlatform = process.argv[13] ?? "both";

/** 标准字号验收模式。 */
const STANDARD_MODE = "standard";

/** 最大字号验收模式。 */
const MAXIMUM_MODE = "maximum";

/** WebDriver 横屏方向值。 */
const LANDSCAPE_ORIENTATION = "LANDSCAPE";

/** WebDriver 竖屏方向值。 */
const PORTRAIT_ORIENTATION = "PORTRAIT";

/** 走势图必须持续展示的真实数据来源标记。 */
const OFFICIAL_HISTORY_NOTICE = "官方历史开奖";

/** 走势图加载失败标记。 */
const HISTORY_LOAD_FAILURE_NOTICE = "历史开奖暂时不可用";

/** 产品冻结的真实开奖样本档位。 */
const TREND_SAMPLE_SIZES = [50, 80, 120, 300, 500];

/** 走势图必须持续展示的责任边界。 */
const RESPONSIBLE_USE_NOTICE = "历史分布不代表未来规律，不构成购彩建议。";

/** 数学研究固定策略标题。 */
const RESEARCH_STRATEGY_TITLE = "频次遗漏排序 · v1";

/** 数学研究固定策略标识。 */
const RESEARCH_STRATEGY_ID_NOTICE = "策略标识：frequency-omission-ranking-v1";

/** 数学研究回测基线标题。 */
const RESEARCH_BASELINE_TITLE = "均匀随机理论基线";

/** 数学研究必须持续展示的样本结论。 */
const RESEARCH_UNPROVEN_NOTICE = "当前前推样本尚未证明稳定优于均匀随机";

/** 数学研究必须持续展示的责任边界。 */
const RESEARCH_RESPONSIBILITY_NOTICE =
  "历史排序不会改变下一期各号码概率，仅供娱乐和研究，不构成购彩建议。";

/**
 * 两个彩种的数学研究验收口径。
 *
 * @property key 截图文件使用的稳定短名。
 * @property displayName 页面展示的彩种名称。
 * @property sourceName 页面展示的官方历史来源名称。
 * @property issueLength 当前彩种期号长度。
 * @property primaryArea 主号码区域名称。
 * @property secondaryArea 次号码区域名称。
 * @property primaryCount 一注候选的主号码数量。
 * @property secondaryCount 一注候选的次号码数量。
 * @property primaryBaseline 主号码区域的精确随机理论基线。
 * @property secondaryBaseline 次号码区域的精确随机理论基线。
 */
const RESEARCH_CONFIGS = {
  superLotto: {
    key: "super-lotto",
    displayName: "大乐透",
    sourceName: "中国体彩网历史开奖",
    issueLength: 5,
    primaryArea: "前区",
    secondaryArea: "后区",
    primaryCount: 5,
    secondaryCount: 2,
    primaryBaseline: "0.714",
    secondaryBaseline: "0.333",
  },
  doubleColorBall: {
    key: "double-color-ball",
    displayName: "双色球",
    sourceName: "中国福彩网开奖公告",
    issueLength: 7,
    primaryArea: "红球",
    secondaryArea: "蓝球",
    primaryCount: 6,
    secondaryCount: 1,
    primaryBaseline: "1.091",
    secondaryBaseline: "0.063",
  },
};

/** Android UiAutomation 瞬态未连接时允许的会话创建总次数。 */
const SESSION_CREATION_ATTEMPT_COUNT = 3;

if (![STANDARD_MODE, MAXIMUM_MODE].includes(acceptanceMode)) {
  throw new Error(`未知 V1.3 验收模式：${acceptanceMode}`);
}
if (!["both", "android", "ios"].includes(targetPlatform)) {
  throw new Error(`未知 V1.3 目标平台：${targetPlatform}`);
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

/** 判断两个元素矩形是否在允许的一像素误差内一致。 */
function isSameRect(firstRect, secondRect) {
  return ["x", "y", "width", "height"].every(
    (key) => Math.abs(firstRect[key] - secondRect[key]) <= 1,
  );
}

/** 等待 Compose 页面完成一次状态更新。 */
async function waitForPageUpdate(durationMillis = 500) {
  await new Promise((resolve) => setTimeout(resolve, durationMillis));
}

/** 切换设备方向，并等待应用窗口尺寸与目标方向一致。 */
async function setOrientation(sessionId, orientation) {
  await webdriverRequest(`/session/${sessionId}/orientation`, "POST", { orientation });
  for (let attempt = 0; attempt < 20; attempt += 1) {
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
async function waitForSourceText(sessionId, expectedText, attemptCount = 24) {
  let source = "";
  for (let attempt = 0; attempt < attemptCount; attempt += 1) {
    source = await readSource(sessionId);
    if (source.includes(expectedText)) return source;
    await waitForPageUpdate(300);
  }
  throw new Error(`等待页面文案超时：${expectedText}`);
}

/** 等待辅助功能树同时出现一组指定文案。 */
async function waitForSourceTexts(sessionId, expectedTexts, attemptCount = 24) {
  let source = "";
  for (let attempt = 0; attempt < attemptCount; attempt += 1) {
    source = await readSource(sessionId);
    if (expectedTexts.every((text) => source.includes(text))) return source;
    await waitForPageUpdate(300);
  }
  const missingTexts = expectedTexts.filter((text) => !source.includes(text));
  throw new Error(`等待页面文案超时：${missingTexts.join("、")}`);
}

/** 等待辅助功能树出现符合指定规则的动态文案。 */
async function waitForSourcePattern(sessionId, expectedPattern, attemptCount = 24) {
  let source = "";
  for (let attempt = 0; attempt < attemptCount; attempt += 1) {
    source = await readSource(sessionId);
    const match = source.match(expectedPattern);
    if (match) return { source, match };
    await waitForPageUpdate(300);
  }
  throw new Error(`等待页面动态文案超时：${expectedPattern}`);
}

/** 等待真实历史开奖状态条，并动态返回当前样本首末期号。 */
async function waitForTrendRange(
  sessionId,
  sampleSize,
  issueLength,
  expectedLatestIssue,
  attemptCount = 80,
) {
  const rangePattern = new RegExp(
    `${sampleSize} 期 · (\\d{${issueLength}}) 至 (\\d{${issueLength}})`,
  );
  let source = "";
  for (let attempt = 0; attempt < attemptCount; attempt += 1) {
    source = await readSource(sessionId);
    if (source.includes(HISTORY_LOAD_FAILURE_NOTICE)) {
      throw new Error(`页面未能加载官方历史开奖：${HISTORY_LOAD_FAILURE_NOTICE}`);
    }
    const match = source.match(rangePattern);
    if (source.includes(OFFICIAL_HISTORY_NOTICE) && match) {
      const [, firstIssue, lastIssue] = match;
      if (lastIssue !== expectedLatestIssue) {
        throw new Error(
          `页面最新期 ${lastIssue} 与官网当前最新期 ${expectedLatestIssue} 不一致`,
        );
      }
      if (firstIssue >= lastIssue) {
        throw new Error(`历史样本首末期号顺序异常：${firstIssue} 至 ${lastIssue}`);
      }
      return { source, firstIssue, lastIssue };
    }
    await waitForPageUpdate(500);
  }
  throw new Error(`等待 ${sampleSize} 期真实历史开奖超时`);
}

/** 从两家官网读取验收时刻的最新已发布期号。 */
async function fetchOfficialLatestIssues() {
  const [superLottoResponse, doubleColorBallResponse] = await Promise.all([
    fetch(
      "https://webapi.sporttery.cn/gateway/lottery/getHistoryPageListV1.qry" +
        "?gameNo=85&provinceId=0&pageSize=1&pageNo=1&isVerify=1",
      { headers: { "User-Agent": "WinLottery-V1.3-Acceptance/1.0" } },
    ),
    fetch(
      "https://www.cwl.gov.cn/cwl_admin/front/cwlkj/search/kjxx/findDrawNotice" +
        "?name=ssq&pageNo=1&pageSize=1&systemType=PC",
      {
        headers: {
          Referer: "https://www.cwl.gov.cn/",
          "User-Agent": "WinLottery-V1.3-Acceptance/1.0",
        },
      },
    ),
  ]);
  if (!superLottoResponse.ok || !doubleColorBallResponse.ok) {
    throw new Error(
      `无法读取官网最新期：体彩 HTTP ${superLottoResponse.status}，福彩 HTTP ${doubleColorBallResponse.status}`,
    );
  }
  const [superLottoPayload, doubleColorBallPayload] = await Promise.all([
    superLottoResponse.json(),
    doubleColorBallResponse.json(),
  ]);
  const superLotto = superLottoPayload?.value?.list?.[0]?.lotteryDrawNum;
  const doubleColorBall = doubleColorBallPayload?.result?.[0]?.code;
  if (!/^\d{5}$/.test(superLotto) || !/^\d{7}$/.test(doubleColorBall)) {
    throw new Error("官网最新期号响应结构异常");
  }
  return { superLotto, doubleColorBall };
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

/** 在元素矩形中心发送一次真实触摸点按。 */
async function tapElementCenter(sessionId, elementRect) {
  const x = Math.round(elementRect.x + elementRect.width / 2);
  const y = Math.round(elementRect.y + elementRect.height / 2);
  await performTouchSwipe(sessionId, x, y, x, y, 120);
  await waitForPageUpdate();
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
  for (let scrollCount = 0; scrollCount <= 26; scrollCount += 1) {
    const elements = await findVisibleExactElements(sessionId, accessibilityName);
    if (elements.length > 0) return { ...elements[0], scrollCount };
    await swipeVertically(sessionId, direction);
  }
  const source = await readSource(sessionId);
  throw new Error(
    `滚动后仍无法访问元素：${accessibilityName}；页面含文案=${source.includes(accessibilityName)}`,
  );
}

/** 点击当前窗口垂直位置最靠下的同名元素，稳定命中一级导航。 */
async function clickBottommostExactElement(sessionId, accessibilityName) {
  const elements = await findVisibleExactElements(sessionId, accessibilityName);
  if (elements.length === 0) throw new Error(`当前窗口缺少一级导航：${accessibilityName}`);
  elements.sort((first, second) => second.elementRect.y - first.elementRect.y);
  await tapElementCenter(sessionId, elements[0].elementRect);
}

/** 查找 Android 文案元素对应的可选择父控件。 */
async function findAndroidSelectableElement(sessionId, accessibilityName) {
  const nameLiteral = JSON.stringify(accessibilityName);
  return webdriverRequest(`/session/${sessionId}/element`, "POST", {
    using: "xpath",
    value: `//*[@text=${nameLiteral}]/..`,
  });
}

/** 查找 iOS 文案对应的可选择按钮控件。 */
async function findIOSSelectableElement(sessionId, accessibilityName) {
  const nameLiteral = JSON.stringify(accessibilityName);
  return webdriverRequest(`/session/${sessionId}/element`, "POST", {
    using: "xpath",
    value:
      `//XCUIElementTypeButton[@name=${nameLiteral} or ` +
      `@label=${nameLiteral} or @value=${nameLiteral}]`,
  });
}

/** 等待分段控件或筛选项进入平台对应的选中状态。 */
async function waitForElementSelected(sessionId, accessibilityName, platformName) {
  let selectedValue;
  for (let attempt = 0; attempt < 12; attempt += 1) {
    const androidElement =
      platformName === "Android"
        ? await findAndroidSelectableElement(sessionId, accessibilityName)
        : undefined;
    const iosElement =
      platformName === "iOS"
        ? await findIOSSelectableElement(sessionId, accessibilityName)
        : undefined;
    const elementId =
      platformName === "Android"
        ? androidElement && readElementId(androidElement, accessibilityName)
        : iosElement && readElementId(iosElement, accessibilityName);
    if (elementId) {
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
async function selectOption(sessionId, accessibilityName, platformName, direction = "down") {
  const element = await scrollElementIntoView(sessionId, accessibilityName, direction);
  await waitForPageUpdate(1000);
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

/** 在横向样本筛选条中找到并选择指定期数。 */
async function selectSampleSize(sessionId, sampleSize, platformName) {
  const targetName = `${sampleSize} 期`;
  const targetIndex = TREND_SAMPLE_SIZES.indexOf(sampleSize);
  if (targetIndex < 0) throw new Error(`未知样本期数：${sampleSize}`);
  await scrollElementIntoView(sessionId, "期数", "down");
  for (let attempt = 0; attempt < 12; attempt += 1) {
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
    for (const value of TREND_SAMPLE_SIZES) {
      const elements = await findVisibleExactElements(sessionId, `${value} 期`);
      if (elements.length > 0) {
        visibleOptions.push({ index: TREND_SAMPLE_SIZES.indexOf(value), rect: elements[0].elementRect });
      }
    }
    if (visibleOptions.length === 0) throw new Error("当前窗口无法定位样本筛选条");
    visibleOptions.sort((first, second) => first.index - second.index);
    const rowRect = visibleOptions[0].rect;
    const windowRect = await webdriverRequest(`/session/${sessionId}/window/rect`);
    const centerY = Math.round(rowRect.y + rowRect.height / 2);
    const movesTowardLargerSamples = targetIndex > visibleOptions.at(-1).index;
    const leftX = Math.round(windowRect.x + windowRect.width * 0.28);
    const rightX = Math.round(windowRect.x + windowRect.width * 0.88);
    await performTouchSwipe(
      sessionId,
      movesTowardLargerSamples ? rightX : leftX,
      centerY,
      movesTowardLargerSamples ? leftX : rightX,
      centerY,
      520,
    );
    await waitForPageUpdate(350);
  }
  throw new Error(`横向滚动后仍无法选择：${targetName}`);
}

/** 从任意一级页面进入走势工作区。 */
async function openTrends(sessionId) {
  const source = await readSource(sessionId);
  if (source.includes("期号与01至35号码列") && source.includes(OFFICIAL_HISTORY_NOTICE)) return;
  await clickBottommostExactElement(sessionId, "走势");
  await waitForSourceText(sessionId, OFFICIAL_HISTORY_NOTICE, 80);
}

/** 从任意一级页面进入核对工作区。 */
async function openVerification(sessionId) {
  await clickBottommostExactElement(sessionId, "核对");
  await waitForSourceText(sessionId, "纸质彩票核对");
}

/** 核对四个底部一级导航项均完整可见且互不重叠。 */
async function verifyBottomNavigationLayout(sessionId) {
  const destinations = [];
  for (const name of ["核对", "选号", "走势", "记录"]) {
    const elements = await findVisibleExactElements(sessionId, name);
    if (elements.length === 0) throw new Error(`底部一级导航缺少：${name}`);
    elements.sort((first, second) => second.elementRect.y - first.elementRect.y);
    destinations.push({ name, rect: elements[0].elementRect });
  }
  destinations.sort((first, second) => first.rect.x - second.rect.x);
  for (let index = 1; index < destinations.length; index += 1) {
    const previous = destinations[index - 1];
    const current = destinations[index];
    if (previous.rect.x + previous.rect.width > current.rect.x) {
      throw new Error(`底部一级导航发生重叠：${previous.name} 与 ${current.name}`);
    }
  }
}

/** 保存一张当前平台和字号模式的验收截图。 */
async function saveScreenshot(sessionId, platformName, label) {
  await mkdir(screenshotDirectory, { recursive: true });
  const encoded = await webdriverRequest(`/session/${sessionId}/screenshot`);
  const bytes = Buffer.from(encoded, "base64");
  const fileName = `${platformName.toLowerCase()}-${acceptanceMode}-${label}.png`;
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

/** 核对横屏顶部四项图标导航均可见且互不重叠。 */
async function verifyCompactNavigationLayout(sessionId) {
  const destinations = [];
  for (const name of ["核对", "选号", "走势", "记录"]) {
    const elements = await findVisibleExactElements(sessionId, name);
    if (elements.length === 0) throw new Error(`横屏顶部导航缺少：${name}`);
    elements.sort((first, second) => second.elementRect.x - first.elementRect.x);
    destinations.push({ name, rect: elements[0].elementRect });
  }
  destinations.sort((first, second) => first.rect.x - second.rect.x);
  for (let index = 1; index < destinations.length; index += 1) {
    const previous = destinations[index - 1];
    const current = destinations[index];
    if (previous.rect.x + previous.rect.width > current.rect.x) {
      throw new Error(`横屏顶部导航发生重叠：${previous.name} 与 ${current.name}`);
    }
  }
}

/** 核对最宽大乐透前区在横屏无需横向滑动即可完整预览。 */
async function verifyLandscapeTrend(sessionId, platformName, portraitRange, latestIssue) {
  const windowRect = await setOrientation(sessionId, LANDSCAPE_ORIENTATION);
  await saveScreenshot(sessionId, platformName, "landscape-before-assertions");
  const landscapeRange = await waitForTrendRange(sessionId, 50, 5, latestIssue);
  if (landscapeRange.firstIssue !== portraitRange.firstIssue) {
    throw new Error(`${platformName} 旋转后 50 期样本起点发生变化`);
  }
  const matrixDescription = "横屏完整号码矩阵，01至35全部可见";
  await waitForSourceTexts(
    sessionId,
    [
      matrixDescription,
      "大乐透 · 前区",
      "三区 25至35",
      "号码 35",
      `期号 ${portraitRange.firstIssue}`,
      OFFICIAL_HISTORY_NOTICE,
    ],
    40,
  );
  await verifyCompactNavigationLayout(sessionId);
  const [matrix, lastZone, lastNumber, firstIssue] = await Promise.all([
    findVisibleExactElement(sessionId, matrixDescription),
    findVisibleExactElement(sessionId, "三区 25至35"),
    findVisibleExactElement(sessionId, "号码 35"),
    findVisibleExactElement(sessionId, `期号 ${portraitRange.firstIssue}`),
  ]);
  if (lastZone.elementRect.x <= firstIssue.elementRect.x + firstIssue.elementRect.width) {
    throw new Error(`${platformName} 横屏最右号码分区没有位于固定期号列右侧`);
  }
  if (matrix.elementRect.width < windowRect.width * 0.7) {
    throw new Error(`${platformName} 横屏走势图没有使用主要可用宽度`);
  }
  if (lastNumber.elementRect.x + lastNumber.elementRect.width > windowRect.x + windowRect.width) {
    throw new Error(`${platformName} 横屏最右 35 号表头超出窗口`);
  }
  const screenshot = await saveScreenshot(sessionId, platformName, "landscape-full-matrix");
  if (screenshot.width <= screenshot.height) {
    throw new Error(`${platformName} 横屏证据截图宽高异常：${screenshot.width}x${screenshot.height}`);
  }

  await setOrientation(sessionId, PORTRAIT_ORIENTATION);
  const restoredRange = await waitForTrendRange(sessionId, 50, 5, latestIssue);
  if (restoredRange.firstIssue !== portraitRange.firstIssue) {
    throw new Error(`${platformName} 恢复竖屏后 50 期样本起点发生变化`);
  }
}

/**
 * 横向滑动号码矩阵，确认截图发生变化且固定期号列坐标不变。
 */
async function verifyHorizontalMatrixScroll(sessionId, platformName, issueValue) {
  const issueName = `期号 ${issueValue}`;
  await scrollElementIntoView(sessionId, issueName, "down");
  await waitForPageUpdate(1000);
  const issueBefore = await findVisibleExactElement(sessionId, issueName);
  const windowRect = await webdriverRequest(`/session/${sessionId}/window/rect`);
  const beforeScreenshot = await saveScreenshot(sessionId, platformName, "matrix-start");
  const startX = Math.round(windowRect.x + windowRect.width * 0.88);
  const endX = Math.round(windowRect.x + windowRect.width * 0.34);
  const centerY = Math.round(issueBefore.elementRect.y + issueBefore.elementRect.height / 2);
  if (endX >= startX) throw new Error(`${platformName} 号码矩阵没有可滑动的水平区域`);

  await performTouchSwipe(sessionId, startX, centerY, endX, centerY, 650);
  await waitForPageUpdate(650);

  const issueAfter = await findVisibleExactElement(sessionId, issueName);
  const afterScreenshot = await saveScreenshot(sessionId, platformName, "matrix-end");
  if (!isSameRect(issueBefore.elementRect, issueAfter.elementRect)) {
    throw new Error(`${platformName} 横向滑动后固定期号列发生位移`);
  }
  if (beforeScreenshot.sha256 === afterScreenshot.sha256) {
    throw new Error(`${platformName} 横向滑动前后截图完全一致`);
  }
}

/** 核对三行统计完整可见、顺序正确，并位于首期开奖行之前。 */
async function verifyStatisticsBeforeFirstDraw(
  sessionId,
  platformName,
  firstIssue,
  screenshotLabel,
) {
  const rowNames = ["出现次数", "当前遗漏", "最大遗漏", `期号 ${firstIssue}`];
  await scrollElementIntoView(sessionId, rowNames[0], "up");

  let rows = [];
  for (let attempt = 0; attempt < 8; attempt += 1) {
    const visibleRows = await Promise.all(
      rowNames.map((rowName) => findVisibleExactElements(sessionId, rowName)),
    );
    if (visibleRows.every((elements) => elements.length > 0)) {
      rows = visibleRows.map((elements, index) => ({
        name: rowNames[index],
        rect: elements[0].elementRect,
      }));
      break;
    }

    const windowRect = await webdriverRequest(`/session/${sessionId}/window/rect`);
    const x = Math.round(windowRect.x + windowRect.width / 2);
    const startY = Math.round(windowRect.y + windowRect.height * 0.7);
    const endY = Math.round(windowRect.y + windowRect.height * 0.6);
    await performTouchSwipe(sessionId, x, startY, x, endY, 280);
    await waitForPageUpdate(300);
  }
  if (rows.length !== rowNames.length) {
    throw new Error(`${platformName} 无法同时查看前置统计与首期开奖行`);
  }

  for (let index = 1; index < rows.length; index += 1) {
    const previous = rows[index - 1];
    const current = rows[index];
    if (previous.rect.y + previous.rect.height > current.rect.y) {
      throw new Error(`${platformName} 走势图行顺序或布局异常：${previous.name} 与 ${current.name}`);
    }
  }
  await saveScreenshot(sessionId, platformName, screenshotLabel);
}

/** 核对默认大乐透前区 50 期真实样本。 */
async function verifyDefaultTrendSnapshot(sessionId, platformName, latestIssue) {
  const range = await waitForTrendRange(sessionId, 50, 5, latestIssue);
  await scrollElementIntoView(sessionId, "大乐透 · 前区", "up");
  await scrollElementIntoView(
    sessionId,
    `50 期 · ${range.firstIssue} 至 ${range.lastIssue}`,
    "up",
  );
  await scrollElementIntoView(sessionId, `期号 ${range.firstIssue}`, "up");
  await waitForSourceText(sessionId, "期号与01至35号码列");
  await saveScreenshot(sessionId, platformName, "default-trend");
  return range;
}

/** 核对 50/80/120/300/500 五档均从同一官方最新期向前采样。 */
async function verifySampleSizeWorkflow(sessionId, platformName, latestIssue) {
  for (const sampleSize of [80, 120, 300, 500]) {
    await selectSampleSize(sessionId, sampleSize, platformName);
    const range = await waitForTrendRange(sessionId, sampleSize, 5, latestIssue);
    if (sampleSize === 500) {
      await saveScreenshot(sessionId, platformName, "five-hundred-draw-range");
      await verifyStatisticsBeforeFirstDraw(
        sessionId,
        platformName,
        range.firstIssue,
        "five-hundred-draw-statistics",
      );
    }
  }
  await selectSampleSize(sessionId, 50, platformName);
  return waitForTrendRange(sessionId, 50, 5, latestIssue);
}

/** 核对双色球蓝球真实 50 期、矩阵滚动、统计和会话保留。 */
async function verifyDoubleColorBallWorkflow(sessionId, platformName, latestIssue) {
  await selectOption(sessionId, "双色球", platformName, "down");
  await selectOption(sessionId, "蓝球", platformName, "up");
  const range = await waitForTrendRange(sessionId, 50, 7, latestIssue);
  await scrollElementIntoView(sessionId, "双色球 · 蓝球", "up");
  await scrollElementIntoView(
    sessionId,
    `50 期 · ${range.firstIssue} 至 ${range.lastIssue}`,
    "up",
  );
  await scrollElementIntoView(sessionId, "期号与01至16号码列", "up");
  await verifyStatisticsBeforeFirstDraw(
    sessionId,
    platformName,
    range.firstIssue,
    "fifty-draw-statistics",
  );
  await verifyHorizontalMatrixScroll(sessionId, platformName, range.firstIssue);
  await scrollElementIntoView(sessionId, `期号 ${range.lastIssue}`, "up");
  await findVisibleExactElement(sessionId, RESPONSIBLE_USE_NOTICE);
  await swipeVertically(sessionId, "up");
  await findVisibleExactElement(sessionId, RESPONSIBLE_USE_NOTICE);
  await saveScreenshot(sessionId, platformName, "fifty-draw-end");

  await openVerification(sessionId);
  await openTrends(sessionId);
  await scrollElementIntoView(sessionId, "双色球 · 蓝球", "up");
  const restoredRange = await waitForTrendRange(sessionId, 50, 7, latestIssue);
  if (restoredRange.firstIssue !== range.firstIssue) {
    throw new Error(`${platformName} 一级页面往返后双色球会话样本发生变化`);
  }
  return restoredRange;
}

/** 返回当前彩种一注候选的完整无障碍文案规则。 */
function researchCandidatePattern(config) {
  const numberPattern = "[0-9]{2}";
  const primaryNumbers = Array.from(
    { length: config.primaryCount },
    () => numberPattern,
  ).join("，");
  const secondaryNumbers = Array.from(
    { length: config.secondaryCount },
    () => numberPattern,
  ).join("，");
  return new RegExp(
    `${config.displayName}下一期候选，${config.primaryArea}${primaryNumbers}，` +
      `${config.secondaryArea}${secondaryNumbers}`,
  );
}

/** 返回一个号码区域完整回测指标的无障碍文案规则。 */
function researchMetricPattern(areaName, baseline) {
  const escapedBaseline = baseline.replaceAll(".", "\\.");
  return new RegExp(
    `${areaName}，策略平均命中 [0-9]+\\.[0-9]{3}，` +
      `随机理论 ${escapedBaseline}，差值 [+-][0-9]+\\.[0-9]{3}`,
  );
}

/** 核对 450 期前推范围最后一期与当前官网最新期一致。 */
async function verifyResearchBacktestRange(sessionId, config, expectedLatestIssue) {
  const rangePattern = new RegExp(
    `450 个样本外目标期 · ([0-9]{${config.issueLength}}) 至 ` +
      `([0-9]{${config.issueLength}})`,
  );
  for (let attempt = 0; attempt < 12; attempt += 1) {
    const source = await readSource(sessionId);
    const match = source.match(rangePattern);
    if (match) {
      const [, firstIssue, lastIssue] = match;
      if (lastIssue !== expectedLatestIssue) {
        throw new Error(
          `${config.displayName}数学回测最后一期 ${lastIssue} 与官网最新期 ` +
            `${expectedLatestIssue} 不一致`,
        );
      }
      if (firstIssue >= lastIssue) {
        throw new Error(`${config.displayName}数学回测首末期号顺序异常`);
      }
      return { firstIssue, lastIssue };
    }
    await swipeVertically(sessionId, "up");
  }
  throw new Error(`${config.displayName}未展示 450 个样本外目标期`);
}

/** 核对主次号码区域均展示策略均值、精确随机理论值和差值。 */
async function verifyResearchMetricRows(sessionId, config) {
  const expectations = [
    {
      areaName: config.primaryArea,
      pattern: researchMetricPattern(config.primaryArea, config.primaryBaseline),
    },
    {
      areaName: config.secondaryArea,
      pattern: researchMetricPattern(config.secondaryArea, config.secondaryBaseline),
    },
  ];
  const matchedAreas = new Set();
  for (let attempt = 0; attempt < 14; attempt += 1) {
    const source = await readSource(sessionId);
    for (const expectation of expectations) {
      if (expectation.pattern.test(source)) matchedAreas.add(expectation.areaName);
    }
    if (matchedAreas.size === expectations.length) return;
    await swipeVertically(sessionId, "up");
  }
  const missingAreas = expectations
    .filter((expectation) => !matchedAreas.has(expectation.areaName))
    .map((expectation) => expectation.areaName);
  throw new Error(`${config.displayName}数学回测指标缺少：${missingAreas.join("、")}`);
}

/** 核对一个彩种在竖屏下的策略、候选、前推基线与责任说明。 */
async function verifyResearchSnapshot(
  sessionId,
  platformName,
  config,
  trainingRange,
  expectedLatestIssue,
) {
  await waitForElementSelected(sessionId, "数学研究", platformName);
  const trainingNotice =
    `固定窗口 50 期 · ${trainingRange.firstIssue} 至 ${trainingRange.lastIssue}`;
  const sourceNotice = `官方历史开奖 · ${config.sourceName} · 仅当前会话内计算`;
  await scrollElementIntoView(sessionId, RESEARCH_STRATEGY_TITLE, "down");
  await scrollElementIntoView(sessionId, trainingNotice, "up");
  await scrollElementIntoView(sessionId, RESEARCH_STRATEGY_ID_NOTICE, "up");
  await scrollElementIntoView(sessionId, sourceNotice, "up");

  await scrollElementIntoView(sessionId, "下一期候选", "up");
  await waitForSourcePattern(sessionId, researchCandidatePattern(config));
  await saveScreenshot(sessionId, platformName, `${config.key}-research-candidate`);

  await scrollElementIntoView(sessionId, "时间前推回测", "up");
  await verifyResearchBacktestRange(sessionId, config, expectedLatestIssue);
  await scrollElementIntoView(sessionId, RESEARCH_BASELINE_TITLE, "up");
  await verifyResearchMetricRows(sessionId, config);
  await saveScreenshot(sessionId, platformName, `${config.key}-research-backtest`);

  await scrollElementIntoView(sessionId, RESEARCH_UNPROVEN_NOTICE, "up");
  await scrollElementIntoView(sessionId, RESEARCH_RESPONSIBILITY_NOTICE, "up");
  await saveScreenshot(sessionId, platformName, `${config.key}-research-responsibility`);
}

/** 核对横屏候选与回测双列可同时完整访问且互不重叠。 */
async function verifyLandscapeResearch(sessionId, platformName, config) {
  const windowRect = await setOrientation(sessionId, LANDSCAPE_ORIENTATION);
  await waitForElementSelected(sessionId, "数学研究", platformName);
  await scrollElementIntoView(sessionId, "下一期候选", "down");
  await waitForSourcePattern(sessionId, researchCandidatePattern(config));
  const [candidateTitle, backtestTitle] = await Promise.all([
    findVisibleExactElement(sessionId, "下一期候选"),
    findVisibleExactElement(sessionId, "时间前推回测"),
  ]);
  if (
    candidateTitle.elementRect.x + candidateTitle.elementRect.width >
    backtestTitle.elementRect.x
  ) {
    throw new Error(`${platformName} 横屏数学研究候选与回测标题发生重叠`);
  }
  await verifyCompactNavigationLayout(sessionId);
  const screenshot = await saveScreenshot(
    sessionId,
    platformName,
    "landscape-mathematical-research",
  );
  if (screenshot.width <= screenshot.height || windowRect.width <= windowRect.height) {
    throw new Error(`${platformName} 数学研究横屏证据宽高异常`);
  }

  await setOrientation(sessionId, PORTRAIT_ORIENTATION);
  await waitForElementSelected(sessionId, "数学研究", platformName);
}

/** 核对数学研究内部视图在一级页面往返后保持不变。 */
async function verifyResearchStatePreserved(sessionId, platformName) {
  await openVerification(sessionId);
  await openTrends(sessionId);
  await waitForElementSelected(sessionId, "数学研究", platformName);
  await scrollElementIntoView(sessionId, RESEARCH_STRATEGY_TITLE, "down");
  await findVisibleExactElement(sessionId, RESEARCH_STRATEGY_TITLE);
}

/** 核对双色球和大乐透共用的数学研究完整工作流。 */
async function verifyMathematicalResearchWorkflow(
  sessionId,
  platformName,
  superLottoRange,
  doubleColorBallRange,
) {
  await selectOption(sessionId, "数学研究", platformName, "down");
  await verifyResearchSnapshot(
    sessionId,
    platformName,
    RESEARCH_CONFIGS.doubleColorBall,
    doubleColorBallRange,
    officialLatestIssues.doubleColorBall,
  );

  await selectOption(sessionId, "大乐透", platformName, "down");
  await verifyResearchSnapshot(
    sessionId,
    platformName,
    RESEARCH_CONFIGS.superLotto,
    superLottoRange,
    officialLatestIssues.superLotto,
  );
  await verifyLandscapeResearch(sessionId, platformName, RESEARCH_CONFIGS.superLotto);
  await verifyResearchStatePreserved(sessionId, platformName);
}

/** 执行当前字号的完整 V1.3 走势与数学研究交互验收。 */
async function verifyCurrentMode(sessionId, platformName) {
  await openTrends(sessionId);
  await verifyBottomNavigationLayout(sessionId);
  const defaultRange =
    await verifyDefaultTrendSnapshot(sessionId, platformName, officialLatestIssues.superLotto);
  await verifyLandscapeTrend(
    sessionId,
    platformName,
    defaultRange,
    officialLatestIssues.superLotto,
  );
  await verifySampleSizeWorkflow(sessionId, platformName, officialLatestIssues.superLotto);
  const doubleColorBallRange = await verifyDoubleColorBallWorkflow(
    sessionId,
    platformName,
    officialLatestIssues.doubleColorBall,
  );
  await verifyMathematicalResearchWorkflow(
    sessionId,
    platformName,
    defaultRange,
    doubleColorBallRange,
  );
  process.stdout.write(`${platformName} ${acceptanceMode} V1.3 走势与数学研究检查通过\n`);
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
    await verifyCurrentMode(sessionId, "Android");
  } finally {
    await restorePortraitOrientation(sessionId);
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
      "appium:wdaLocalPort": 8104,
      "appium:newCommandTimeout": 180,
    });
    await verifyCurrentMode(sessionId, "iOS");
  } finally {
    await restorePortraitOrientation(sessionId);
    await deleteSession(sessionId);
  }
}

/** 本次验收启动时两家官网的最新已发布期号。 */
const officialLatestIssues = await fetchOfficialLatestIssues();
process.stdout.write(
  `官网当前最新期：大乐透 ${officialLatestIssues.superLotto}，` +
    `双色球 ${officialLatestIssues.doubleColorBall}\n`,
);

if (targetPlatform === "both" || targetPlatform === "android") await verifyAndroid();
if (targetPlatform === "both" || targetPlatform === "ios") await verifyIOS();
