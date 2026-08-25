import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { isDeepStrictEqual } from "node:util";

/** 源平台导出的逻辑包路径。 */
const sourcePackagePath = process.argv[2];

/** 目标平台导入后再次导出的逻辑包路径。 */
const targetPackagePath = process.argv[3];

/** 本轮需要逐字段核对的合成记录名称。 */
const expectedRecordName = process.argv[4];

/** 本轮需要逐字段核对的合成记录期号。 */
const expectedIssue = process.argv[5];

if (!sourcePackagePath || !targetPackagePath || !expectedRecordName || !expectedIssue) {
  throw new Error("缺少源逻辑包、目标逻辑包、记录名称或期号参数");
}

/** 读取并核对逻辑包外层、数量摘要和 SHA-256。 */
async function readVerifiedPackage(packagePath) {
  const content = await readFile(packagePath, "utf8");
  const envelope = JSON.parse(content);
  const payload = envelope?.payload;
  if (!payload || envelope.payloadSha256 == null) {
    throw new Error(`${packagePath} 缺少 payload 或 payloadSha256`);
  }
  if (payload.format !== "win-lottery-tickets" || payload.formatVersion !== 1) {
    throw new Error(`${packagePath} 的格式标识或版本不符合 V1.1`);
  }
  if (!Array.isArray(payload.records) || payload.recordCount !== payload.records.length) {
    throw new Error(`${packagePath} 的记录数量摘要不一致`);
  }
  const actualSha256 = createHash("sha256").update(JSON.stringify(payload), "utf8").digest("hex");
  if (actualSha256 !== envelope.payloadSha256) {
    throw new Error(`${packagePath} 的规范化负载 SHA-256 不一致`);
  }
  return payload;
}

/** 精确查找本轮唯一的合成记录。 */
function findAcceptanceRecord(payload, packagePath) {
  const records = payload.records.filter(
    (record) => record.displayName === expectedRecordName && record.ticket?.issue === expectedIssue,
  );
  if (records.length !== 1) {
    throw new Error(`${packagePath} 中符合名称和期号的记录数量不是 1：${records.length}`);
  }
  return records[0];
}

const sourcePayload = await readVerifiedPackage(sourcePackagePath);
const targetPayload = await readVerifiedPackage(targetPackagePath);
const sourceRecord = findAcceptanceRecord(sourcePayload, sourcePackagePath);
const targetRecord = findAcceptanceRecord(targetPayload, targetPackagePath);

if (!isDeepStrictEqual(targetRecord, sourceRecord)) {
  throw new Error(
    `跨端记录字段不一致\n源记录=${JSON.stringify(sourceRecord)}\n目标记录=${JSON.stringify(targetRecord)}`,
  );
}

process.stdout.write(
  `${expectedRecordName}（${expectedIssue}）的 UUID、名称、来源、时间、版本、票据和值来源逐字段一致\n`,
);
