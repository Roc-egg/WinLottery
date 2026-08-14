package roc.win.lottery.recognition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** PP-OCRv5 Desktop 模型锁解析和边界测试。 */
class DesktopOcrModelManifestTest {
    /** 包内模型锁必须固定版本、三段模型哈希、字典和张量接口。 */
    @Test
    fun bundledModelLockMatchesValidatedArtifacts() {
        val manifest = DesktopOcrModelManifestLoader.load()

        assertEquals(1, manifest.schemaVersion)
        assertEquals("paddleocr-3.7.0-ppocrv5-mobile-opset17", manifest.bundleId)
        assertEquals(17, manifest.opsetVersion)
        assertEquals(18_383, manifest.dictionaryLineCount)
        assertEquals(
            "d1979e9f794c464c0d2e0b70a7fe14dd978e9dc644c0e71f14158cdf8342af1b",
            manifest.dictionary.sha256,
        )
        assertEquals(
            listOf("detection", "orientation", "recognition"),
            manifest.models.map(DesktopOcrLockedModel::id),
        )
        assertEquals(
            listOf(
                "c8d9b07063420ce5365c74e42532de48238feeeedcdb7a330b195708bc38a93f",
                "4d6027cad43b04171d6eafa20e7342fea7a657724e96d642564392b6df1e9768",
                "bcb195e3463eb9e46ef419b8a01ea4729577de5fd63c64f0a762e43bd64256e7",
            ),
            manifest.models.map { model -> model.file.sha256 },
        )
        assertEquals(
            listOf(-1, -1, 18_385),
            manifest.models
                .last()
                .output.shape,
        )
    }

    /** 模型资源文件名包含路径穿越时必须在任何文件读取前拒绝。 */
    @Test
    fun modelLockRejectsResourcePathTraversal() {
        val invalidLock =
            bundledLockText().replace(
                "\"resourceFile\": \"detection.onnx\"",
                "\"resourceFile\": \"../detection.onnx\"",
            )

        assertFailsWith<IllegalArgumentException> {
            DesktopOcrModelManifestLoader.parse(invalidLock)
        }
    }

    /** 读取测试运行时实际打包的模型锁原文。 */
    private fun bundledLockText(): String =
        checkNotNull(javaClass.getResource(MODEL_LOCK_RESOURCE)) {
            "测试模型锁资源缺失"
        }.readText(Charsets.UTF_8)

    /** 测试资源中的模型锁绝对类路径。 */
    private companion object {
        /** 与生产加载器相同的模型锁资源位置。 */
        const val MODEL_LOCK_RESOURCE =
            "/roc/win/lottery/recognition/models/ppocrv5/model-lock.json"
    }
}
