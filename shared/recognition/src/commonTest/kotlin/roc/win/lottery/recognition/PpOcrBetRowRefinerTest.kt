package roc.win.lottery.recognition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PP-OCR 投注行二次识别的结构约束测试。 */
class PpOcrBetRowRefinerTest {
    /** 注序号锚点必须把弯曲分布的小号码框恢复成两条独立完整投注行。 */
    @Test
    fun indexedRowsReplaceCurvedFragmentsWithCompleteRows() {
        val regions =
            buildList {
                add(region("①", x = 20f, y = 100f))
                add(region("②", x = 20f, y = 140f))
                addAll(curvedNumberRegions(y = 100f, values = listOf("01", "07", "14", "22", "39", "03", "11")))
                addAll(curvedNumberRegions(y = 140f, values = listOf("02", "08", "15", "23", "39", "04", "12")))
            }

        val refined =
            PpOcrBetRowRefiner.refine(regions) { box ->
                val centerY = (box.topLeft.y + box.bottomLeft.y) / 2f
                listOf(
                    recognizedLine(
                        if (centerY < 120f) {
                            "① 01 07 14 22 35 + 03 11"
                        } else {
                            "② 02 08 15 23 34 + 04 12"
                        },
                    ),
                )
            }

        assertEquals(
            listOf("① 01 07 14 22 35 + 03 11", "② 02 08 15 23 34 + 04 12"),
            refined.sortedBy { line -> line.text }.map(OcrTextLine::text),
        )
    }

    /** 两种图像候选形成不同合法号码时必须保留原小框，不得选择任一候选。 */
    @Test
    fun conflictingValidCandidatesDoNotReplaceFragments() {
        val regions =
            buildList {
                add(region("①", x = 20f, y = 100f))
                add(region("②", x = 20f, y = 140f))
                addAll(curvedNumberRegions(y = 100f, values = listOf("01", "07", "14", "22", "39", "03", "11")))
                addAll(curvedNumberRegions(y = 140f, values = listOf("02", "08", "15", "23", "39", "04", "12")))
            }

        val refined =
            PpOcrBetRowRefiner.refine(regions) {
                listOf(
                    recognizedLine("① 01 07 14 22 35 + 03 11"),
                    recognizedLine("① 02 08 15 23 34 + 04 12"),
                )
            }

        assertEquals(regions.size, refined.size)
        assertEquals(regions.map { region -> region.line.text }.sorted(), refined.map(OcrTextLine::text).sorted())
    }

    /** 双图像版本均识别到明确圆圈行号时，可覆盖恰好拼成另一合法号码的错误碎片。 */
    @Test
    fun circledMarkerCropConsensusOverridesConflictingExistingFragments() {
        val correctText = "③ 05 08 12 17 24 + 02 05"
        val regions =
            numberRegions(y = 100f, values = listOf("03", "08", "12", "17", "21", "02", "05"))

        val refined =
            PpOcrBetRowRefiner.refine(
                regions = regions,
                recognizeMergedRow = {
                    listOf(
                        recognizedLine(correctText),
                        recognizedLine(correctText),
                    )
                },
                recognizeLatinMergedRow = { emptyList() },
            )

        assertEquals(listOf(correctText), refined.map(OcrTextLine::text))
    }

    /** 注序号只覆盖部分基线时必须合并密集拟合行，不能在两套结果之间二选一。 */
    @Test
    fun partialIndexedRowsMergeWithAdditionalDenseRows() {
        val regions =
            buildList {
                add(region("①", x = 20f, y = 100f))
                add(region("②", x = 20f, y = 130f))
                addAll(numberRegions(y = 100f, values = listOf("01", "07", "14", "22")))
                addAll(numberRegions(y = 130f, values = listOf("02", "08", "15", "23", "34", "04", "12")))
                addAll(numberRegions(y = 160f, values = listOf("03", "09", "16", "24", "35", "05", "11")))
            }

        val refined =
            PpOcrBetRowRefiner.refine(regions) { box ->
                val centerY = (box.topLeft.y + box.bottomLeft.y) / 2f
                listOf(
                    recognizedLine(
                        when {
                            centerY < 115f -> "① 01 07 14 22 35 + 03 11"
                            centerY < 145f -> "② 02 08 15 23 34 + 04 12"
                            else -> "③ 03 09 16 24 35 + 05 11"
                        },
                    ),
                )
            }

        assertEquals(3, refined.size)
        assertTrue(refined.all { line -> line.text.count(Char::isDigit) >= 14 })
    }

    /** 锚点行和紧邻的无标记强行没有共享证据时必须保持独立，避免清除下一注号码。 */
    @Test
    fun adjacentAnchoredAndDenseRowsWithoutSharedEvidenceStaySeparate() {
        val regions =
            buildList {
                add(region("①", x = 20f, y = 100f))
                add(region("②", x = 20f, y = 130f))
                addAll(numberRegions(y = 100f, values = listOf("01", "07", "14", "22", "35", "03", "11")))
                addAll(numberRegions(y = 130f, values = listOf("02", "08", "15", "23", "34", "04", "12")))
                addAll(numberRegions(y = 139.4f, values = listOf("03", "09", "16", "24", "33", "05", "09")))
            }

        val refined =
            PpOcrBetRowRefiner.refine(regions) { box ->
                val centerY = (box.topLeft.y + box.bottomLeft.y) / 2f
                val normalizedTop = centerY / 1_000f
                listOf(
                    recognizedLine(
                        when {
                            centerY < 115f -> "01 07 14 22 35 + 03 11"
                            centerY < 135f -> "02 08 15 23 34 + 04 12"
                            else -> "03 09 16 24 33 + 05 09"
                        },
                        NormalizedBounds(0.1f, normalizedTop, 0.9f, normalizedTop + 0.008f),
                    ),
                )
            }

        assertEquals(3, refined.size)
        assertEquals(
            listOf(
                "01 07 14 22 35 + 03 11",
                "02 08 15 23 34 + 04 12",
                "03 09 16 24 33 + 05 09",
            ),
            refined.sortedBy { line -> line.bounds.top }.map(OcrTextLine::text),
        )
    }

    /** 旧版双色球 A-E 行标必须能在号码小框不完整时锚定整行裁图。 */
    @Test
    fun legacyLetterMarkersRecoverIncompleteDoubleColorBallRows() {
        val regions =
            buildList {
                add(region("A.", x = 20f, y = 100f))
                add(region("B.", x = 20f, y = 130f))
                addAll(numberRegions(y = 100f, values = listOf("01", "07", "14", "22")))
                addAll(numberRegions(y = 130f, values = listOf("02", "08", "15", "23")))
            }

        val refined =
            PpOcrBetRowRefiner.refine(regions) { box ->
                val centerY = (box.topLeft.y + box.bottomLeft.y) / 2f
                listOf(
                    recognizedLine(
                        if (centerY < 115f) {
                            "A. 01 07 14 22 27 31 + 09 x1"
                        } else {
                            "B. 02 08 15 23 28 32 + 10 x1"
                        },
                    ),
                )
            }

        assertEquals(
            listOf("A. 01 07 14 22 27 31 + 09 x1", "B. 02 08 15 23 28 32 + 10 x1"),
            refined.sortedBy(OcrTextLine::text).map(OcrTextLine::text),
        )
    }

    /** 旧版双色球行标漏识别时，重复的行尾 `x1` 列仍应建立安全裁图。 */
    @Test
    fun legacyMultiplierColumnRecoversRowsWithoutLetterMarkers() {
        val regions =
            buildList {
                addAll(numberRegions(y = 100f, values = listOf("01", "07", "14", "22")))
                add(region("x1", x = 280f, y = 100f))
                addAll(numberRegions(y = 130f, values = listOf("02", "08", "15", "23")))
                add(region("x1", x = 280f, y = 130f))
            }

        val refined =
            PpOcrBetRowRefiner.refine(regions) { box ->
                val centerY = (box.topLeft.y + box.bottomLeft.y) / 2f
                listOf(
                    recognizedLine(
                        if (centerY < 115f) {
                            "01 07 14 22 27 31 + 09 x1"
                        } else {
                            "02 08 15 23 28 32 + 10 x1"
                        },
                    ),
                )
            }

        assertEquals(2, refined.size)
        assertTrue(refined.all { line -> line.text.contains("x1") })
    }

    /** 密集拟合行必须共享整块投注区宽度，恢复某行未检测到的后区号码。 */
    @Test
    fun denseRowsShareFullHorizontalExtent() {
        val regions =
            buildList {
                addAll(numberRegions(y = 100f, values = listOf("01", "07", "14", "22", "35")))
                add(region("+", x = 230f, y = 100f))
                addAll(numberRegions(y = 140f, values = listOf("02", "08", "15", "23", "34", "04", "12")))
            }
        var firstRowRightEdge = 0f

        val refined =
            PpOcrBetRowRefiner.refine(regions) { box ->
                val centerY = (box.topLeft.y + box.bottomLeft.y) / 2f
                if (centerY < 120f) firstRowRightEdge = maxOf(firstRowRightEdge, box.topRight.x)
                listOf(
                    recognizedLine(
                        if (centerY < 120f) {
                            "01 07 14 22 35 + 03 11"
                        } else {
                            "02 08 15 23 34 + 04 12"
                        },
                    ),
                )
            }

        assertEquals(2, refined.size)
        assertTrue(firstRowRightEdge > 250f)
    }

    /** 检测框高度超过密排行距时仍必须形成互不串行的独立紧裁图。 */
    @Test
    fun tightlySpacedRowsProduceIndependentCrops() {
        val regions =
            buildList {
                addAll(numberRegions(y = 100f, values = listOf("01", "07", "14", "22", "35", "03", "11")))
                addAll(numberRegions(y = 116f, values = listOf("02", "08", "15", "23", "34", "04", "12")))
            }
        val cropHeights = mutableListOf<Float>()

        val refined =
            PpOcrBetRowRefiner.refine(regions) { box ->
                val centerY = (box.topLeft.y + box.bottomLeft.y) / 2f
                cropHeights += box.topLeft.distanceTo(box.bottomLeft)
                listOf(
                    recognizedLine(
                        if (centerY < 108f) {
                            "01 07 14 22 35 + 03 11"
                        } else {
                            "02 08 15 23 34 + 04 12"
                        },
                    ),
                )
            }

        assertEquals(2, refined.size)
        assertEquals(2, cropHeights.size)
        assertTrue(cropHeights.all { height -> height <= 16.1f })
    }

    /** 唯一合法整行替换成功后必须清除同行乱码长框，避免解析阶段再次拼坏号码。 */
    @Test
    fun successfulReplacementRemovesSameRowGarbageRegions() {
        val regions =
            buildList {
                addAll(numberRegions(y = 100f, values = listOf("01", "07", "14", "22", "35", "03", "11")))
                add(region("残缺号码片段9", x = 210f, y = 100f))
                addAll(numberRegions(y = 130f, values = listOf("02", "08", "15", "23", "34", "04", "12")))
            }

        val refined =
            PpOcrBetRowRefiner.refine(regions) { box ->
                val centerY = (box.topLeft.y + box.bottomLeft.y) / 2f
                listOf(
                    recognizedLine(
                        if (centerY < 115f) {
                            "01 07 14 22 35 + 03 11"
                        } else {
                            "02 08 15 23 34 + 04 12"
                        },
                    ),
                )
            }

        assertEquals(2, refined.size)
        assertTrue(refined.none { line -> line.text.contains("残缺") })
    }

    /** 一行只剩三个数字框和两个有效号码时仍应尝试整行识别，并由严格结构决定是否替换。 */
    @Test
    fun sparseNumericFragmentsCanRecoverACompleteRow() {
        val regions =
            buildList {
                addAll(numberRegions(y = 100f, values = listOf("01", "14", "3")))
                addAll(numberRegions(y = 130f, values = listOf("02", "08", "15", "23", "34", "04", "12")))
            }

        val refined =
            PpOcrBetRowRefiner.refine(regions) { box ->
                val centerY = (box.topLeft.y + box.bottomLeft.y) / 2f
                listOf(
                    recognizedLine(
                        if (centerY < 115f) {
                            "01 07 14 22 35 + 03 11"
                        } else {
                            "02 08 15 23 34 + 04 12"
                        },
                    ),
                )
            }

        assertEquals(2, refined.size)
        assertTrue(refined.all { line -> line.text.count(Char::isDigit) >= 14 })
    }

    /** 长框只保留两个号码时应复用密集投注区宽度执行整行识别。 */
    @Test
    fun longSparseRegionCanRecoverACompleteRow() {
        val regions =
            buildList {
                addAll(numberRegions(y = 100f, values = listOf("01", "07", "14", "22", "35", "03", "11")))
                add(wideRegion("残缺 23 34 后段", x = 170f, y = 130f))
                addAll(numberRegions(y = 160f, values = listOf("03", "09", "16", "24", "33", "05", "09")))
            }

        val refined =
            PpOcrBetRowRefiner.refine(regions) { box ->
                val centerY = (box.topLeft.y + box.bottomLeft.y) / 2f
                listOf(
                    recognizedLine(
                        when {
                            centerY < 115f -> "01 07 14 22 35 + 03 11"
                            centerY < 145f -> "02 08 15 23 34 + 04 12"
                            else -> "03 09 16 24 33 + 05 09"
                        },
                    ),
                )
            }

        assertEquals(3, refined.size)
        assertTrue(refined.all { line -> line.text.count(Char::isDigit) >= 14 })
    }

    /** 稀疏长框必须复用整票文字方向生成裁图，不能因单框回归退化为水平线。 */
    @Test
    fun sparseRowCropUsesGlobalTextSlope() {
        val regions =
            buildList {
                addAll(
                    slopedNumberRegions(
                        y = 100f,
                        values = listOf("01", "07", "14", "22", "35", "03", "11"),
                        slope = 0.05f,
                    ),
                )
                add(slopedWideRegion("残缺 23 34 后段", x = 170f, y = 130f, slope = 0.05f))
                addAll(
                    slopedNumberRegions(
                        y = 160f,
                        values = listOf("03", "09", "16", "24", "33", "05", "09"),
                        slope = 0.05f,
                    ),
                )
            }
        var sparseCropSlope = 0f
        val observedCrops = mutableListOf<Pair<Float, Float>>()

        PpOcrBetRowRefiner.refine(regions) { box ->
            val centerY = (box.topLeft.y + box.bottomLeft.y) / 2f
            val slope = (box.topRight.y - box.topLeft.y) / (box.topRight.x - box.topLeft.x)
            observedCrops += centerY to slope
            if (centerY in 120f..140f) {
                sparseCropSlope = slope
            }
            listOf(
                recognizedLine(
                    when {
                        centerY < 115f -> "01 07 14 22 35 + 03 11"
                        centerY < 145f -> "02 08 15 23 34 + 04 12"
                        else -> "03 09 16 24 33 + 05 09"
                    },
                ),
            )
        }

        assertTrue(sparseCropSlope > 0.04f, "实际稀疏裁图斜率为 $sparseCropSlope，全部裁图为 $observedCrops")
    }

    /** 单数字行标与首个号码粘连时，只在旧框存在独立行标证据后移除该前缀。 */
    @Test
    fun leadingSingleDigitMarkerCanBeRemovedFromMergedCandidate() {
        val regions =
            buildList {
                add(region("5", x = 20f, y = 100f))
                addAll(numberRegions(y = 100f, values = listOf("01", "07", "14", "22", "35", "03", "11")))
                add(region("6", x = 20f, y = 130f))
                addAll(numberRegions(y = 130f, values = listOf("02", "08", "15", "23", "34", "04", "12")))
            }

        val refined =
            PpOcrBetRowRefiner.refine(regions) { box ->
                val centerY = (box.topLeft.y + box.bottomLeft.y) / 2f
                listOf(
                    recognizedLine(
                        if (centerY < 115f) {
                            "50107142235+0311"
                        } else {
                            "60208152334+0412"
                        },
                    ),
                )
            }

        assertEquals(2, refined.size)
        assertEquals(
            listOf("0107142235+0311", "0208152334+0412"),
            refined.sortedBy { line -> line.bounds.top }.map(OcrTextLine::text),
        )
    }

    /** 旧版字母行标夹带一个数字时，也可作为移除粘连数字的受限证据。 */
    @Test
    fun degradedLegacyMarkerCanProveLeadingDigitRemoval() {
        val regions =
            buildList {
                add(region("A5", x = 20f, y = 100f))
                addAll(numberRegions(y = 100f, values = listOf("01", "07", "14", "22", "35", "03", "11")))
                add(region("B6", x = 20f, y = 130f))
                addAll(numberRegions(y = 130f, values = listOf("02", "08", "15", "23", "34", "04", "12")))
            }

        val refined =
            PpOcrBetRowRefiner.refine(regions) { box ->
                val centerY = (box.topLeft.y + box.bottomLeft.y) / 2f
                listOf(
                    recognizedLine(
                        if (centerY < 115f) {
                            "50107142235+0311"
                        } else {
                            "60208152334+0412"
                        },
                    ),
                )
            }

        assertEquals(
            listOf("0107142235+0311", "0208152334+0412"),
            refined.sortedBy { line -> line.bounds.top }.map(OcrTextLine::text),
        )
    }

    /** 紧裁图失败时应尝试受限扩高裁图，并继续依赖唯一合法结构决定是否替换。 */
    @Test
    fun expandedFallbackCropCanRecoverClippedRows() {
        val regions =
            buildList {
                addAll(numberRegions(y = 100f, values = listOf("01", "07", "14", "22", "35", "03", "11")))
                addAll(numberRegions(y = 130f, values = listOf("02", "08", "15", "23", "34", "04", "12")))
            }

        val refined =
            PpOcrBetRowRefiner.refine(regions) { box ->
                val cropHeight = box.topLeft.distanceTo(box.bottomLeft)
                val centerY = (box.topLeft.y + box.bottomLeft.y) / 2f
                listOf(
                    recognizedLine(
                        if (cropHeight >= 20f) {
                            if (centerY < 115f) "01 07 14 22 35 + 03 11" else "02 08 15 23 34 + 04 12"
                        } else {
                            "号码裁切不完整"
                        },
                    ),
                )
            }

        assertEquals(2, refined.size)
        assertTrue(refined.all { line -> line.text.contains("+") })
    }

    /** 紧裁图中心偏上时应优先向下校正，并在两种图像版本一致后立即停止额外裁图。 */
    @Test
    fun downwardFallbackCropRecoversVerticallyBiasedRowWithoutExtraAttempts() {
        val expectedText = "05 08 14 20 26 30 + 15"
        val regions = numberRegions(y = 100f, values = listOf("05", "08", "14", "20", "26", "30", "15"))
        val cropCenters = mutableListOf<Float>()

        val refined =
            PpOcrBetRowRefiner.refine(
                regions = regions,
                recognizeMergedRow = { box ->
                    val centerY = (box.topLeft.y + box.bottomLeft.y) / 2f
                    cropCenters += centerY
                    val text = if (centerY in 104.7f..104.9f) expectedText else "号码裁切不完整"
                    listOf(recognizedLine(text), recognizedLine(text))
                },
                recognizeLatinMergedRow = { emptyList() },
            )

        assertEquals(listOf(expectedText), refined.map(OcrTextLine::text))
        assertEquals(2, cropCenters.size, "实际裁图中心为 $cropCenters")
    }

    /** 整行 OCR 全部失败时，基线上唯一合法的高置信度数字碎片应规范化保留。 */
    @Test
    fun uniqueExistingFragmentsCanFormCanonicalRows() {
        val regions =
            buildList {
                addAll(numberRegions(y = 100f, values = listOf("01", "07", "14", "22", "35", "03", "11")))
                addAll(numberRegions(y = 130f, values = listOf("02", "08", "15", "23", "34", "04", "12")))
            }

        val refined =
            PpOcrBetRowRefiner.refine(regions) {
                listOf(recognizedLine("号码裁切不完整"))
            }

        assertEquals(
            listOf("01 07 14 22 35 + 03 11", "02 08 15 23 34 + 04 12"),
            refined.sortedBy { line -> line.bounds.top }.map(OcrTextLine::text),
        )
    }

    /** 不同备用裁图形成不同合法号码时必须保留原碎片，不得按置信度强选。 */
    @Test
    fun conflictingFallbackCropsRejectAutomaticReplacement() {
        val regions =
            buildList {
                addAll(numberRegions(y = 100f, values = listOf("01", "07", "14", "22", "35", "03", "11")))
                addAll(numberRegions(y = 130f, values = listOf("02", "08", "15", "23", "34", "04", "12")))
            }

        val refined =
            PpOcrBetRowRefiner.refine(regions) { box ->
                val cropHeight = box.topLeft.distanceTo(box.bottomLeft)
                val centerY = (box.topLeft.y + box.bottomLeft.y) / 2f
                val text =
                    when {
                        cropHeight < 20f && (centerY in 99.9f..100.1f || centerY in 129.9f..130.1f) -> {
                            "号码裁切不完整"
                        }

                        cropHeight >= 20f && centerY < 115f -> {
                            "01 07 14 22 35 + 03 11"
                        }

                        cropHeight >= 20f -> {
                            "02 08 15 23 34 + 04 12"
                        }

                        else -> {
                            "03 09 16 24 33 + 05 09"
                        }
                    }
                listOf(recognizedLine(text))
            }

        assertEquals(14, refined.size)
    }

    /** 已确认投注区内的短框即使多数被识别成文字，也应按稳定基线补回整行裁图。 */
    @Test
    fun geometricFragmentsRecoverRowWithDamagedDigits() {
        val regions =
            buildList {
                addAll(numberRegions(y = 100f, values = listOf("01", "07", "14", "22", "35", "03", "11")))
                add(region("错", x = 60f, y = 130f))
                add(region("14", x = 100f, y = 130f))
                add(region("字", x = 140f, y = 130f))
                add(region("5", x = 180f, y = 130f))
                addAll(numberRegions(y = 160f, values = listOf("03", "09", "16", "24", "33", "05", "09")))
            }

        val refined =
            PpOcrBetRowRefiner.refine(regions) { box ->
                val centerY = (box.topLeft.y + box.bottomLeft.y) / 2f
                listOf(
                    recognizedLine(
                        when {
                            centerY < 115f -> "01 07 14 22 35 + 03 11"
                            centerY < 145f -> "02 08 15 23 34 + 04 12"
                            else -> "03 09 16 24 33 + 05 09"
                        },
                    ),
                )
            }

        assertEquals(3, refined.size)
        assertTrue(refined.all { line -> line.text.contains("+") })
    }

    /** 弱几何框与密集行重合时不得加入强候选，否则合法误读会制造整行冲突。 */
    @Test
    fun geometricCropDoesNotContaminateDenseRowCandidates() {
        val regions =
            buildList {
                addAll(numberRegions(y = 100f, values = listOf("01", "07", "14", "22", "35", "03", "11")))
                add(region("错", x = 210f, y = 105f))
                addAll(numberRegions(y = 140f, values = listOf("02", "08", "15", "23", "34", "04", "12")))
            }
        val cropCenters = mutableListOf<Float>()

        val refined =
            PpOcrBetRowRefiner.refine(regions) { box ->
                val centerY = (box.topLeft.y + box.bottomLeft.y) / 2f
                cropCenters += centerY
                listOf(
                    recognizedLine(
                        when {
                            centerY > 100.3f && centerY < 120f -> "03 09 16 24 33 + 05 09"
                            centerY < 120f -> "01 07 14 22 35 + 03 11"
                            else -> "02 08 15 23 34 + 04 12"
                        },
                    ),
                )
            }

        assertEquals(2, cropCenters.size)
        assertEquals(
            listOf("01 07 14 22 35 + 03 11", "02 08 15 23 34 + 04 12"),
            refined.sortedBy { line -> line.bounds.top }.map(OcrTextLine::text),
        )
    }

    /** 强紧裁图无合法结果时应先采用唯一合法的几何紧裁图，不得继续受错误备用裁图干扰。 */
    @Test
    fun geometricTightCropPrecedesFallbackCandidates() {
        val regions =
            buildList {
                addAll(numberRegions(y = 100f, values = listOf("01", "07", "14", "22", "35", "03", "11")))
                add(region("错", x = 210f, y = 105f))
                addAll(numberRegions(y = 140f, values = listOf("02", "08", "15", "23", "34", "04", "12")))
            }
        val firstRowCropHeights = mutableListOf<Float>()
        val firstRowCropCenters = mutableListOf<Float>()

        val refined =
            PpOcrBetRowRefiner.refine(regions) { box ->
                val centerY = (box.topLeft.y + box.bottomLeft.y) / 2f
                val cropHeight = box.topLeft.distanceTo(box.bottomLeft)
                if (centerY < 120f) {
                    firstRowCropHeights += cropHeight
                    firstRowCropCenters += centerY
                }
                listOf(
                    recognizedLine(
                        when {
                            centerY in 100.3f..100.9f && cropHeight <= 16.1f -> "01 07 14 22 35 + 03 11"
                            centerY < 120f && cropHeight > 16.1f -> "03 09 16 24 33 + 05 09"
                            centerY < 120f -> "无法识别"
                            else -> "02 08 15 23 34 + 04 12"
                        },
                    ),
                )
            }

        assertEquals(2, firstRowCropHeights.size, "实际裁图中心为 $firstRowCropCenters")
        assertTrue(firstRowCropHeights.all { height -> height <= 16.1f })
        assertEquals(
            listOf("01 07 14 22 35 + 03 11", "02 08 15 23 34 + 04 12"),
            refined.sortedBy { line -> line.bounds.top }.map(OcrTextLine::text),
        )
    }

    /** 纯几何候选重复识别相邻强行时应保留碎片转人工，不得新增重复投注。 */
    @Test
    fun duplicateGeometricReplacementIsRejected() {
        val repeatedText = "01 07 14 22 35 + 03 11"
        val regions =
            buildList {
                addAll(numberRegions(y = 100f, values = listOf("01", "07", "14", "22", "35", "03", "11")))
                add(region("错", x = 60f, y = 130f))
                add(region("1", x = 120f, y = 130f))
                add(region("2", x = 180f, y = 130f))
                addAll(numberRegions(y = 160f, values = listOf("02", "08", "15", "23", "34", "04", "12")))
            }

        val refined =
            PpOcrBetRowRefiner.refine(regions) { box ->
                val centerY = (box.topLeft.y + box.bottomLeft.y) / 2f
                listOf(
                    recognizedLine(
                        if (centerY < 145f) repeatedText else "02 08 15 23 34 + 04 12",
                    ),
                )
            }

        assertEquals(1, refined.count { line -> line.text == repeatedText })
        assertTrue(refined.any { line -> line.text == "错" })
        assertEquals(1, refined.count { line -> line.text == "02 08 15 23 34 + 04 12" })
    }

    /** 锚点与密集定位重复识别同一物理行时，只应保留来源更可靠的一条合法号码。 */
    @Test
    fun nearbyStrongReplacementsWithSameSignatureAreDeduplicated() {
        val firstText = "01 07 14 22 35 + 03 11"
        val secondText = "02 08 15 23 34 + 04 12"
        val regions =
            buildList {
                add(region("①", x = 20f, y = 105f))
                addAll(numberRegions(y = 100f, values = listOf("01", "07", "14", "22", "35", "03", "11")))
                add(region("②", x = 20f, y = 140f))
                addAll(numberRegions(y = 140f, values = listOf("02", "08", "15", "23", "34", "04", "12")))
            }

        val refined =
            PpOcrBetRowRefiner.refine(regions) { box ->
                val centerY = (box.topLeft.y + box.bottomLeft.y) / 2f
                listOf(recognizedLine(if (centerY < 120f) firstText else secondText))
            }

        assertEquals(1, refined.count { line -> line.text == firstText })
        assertEquals(1, refined.count { line -> line.text == secondText })
    }

    /** 同一物理行的裁图明显重叠时，即使中心距离略大也只能保留一条相同号码。 */
    @Test
    fun verticallyOverlappingReplacementsWithSameSignatureAreDeduplicated() {
        val repeatedText = "01 07 14 22 35 + 03 11"
        val regions =
            buildList {
                addAll(numberRegions(y = 100f, values = listOf("01", "07", "14", "22", "35", "03", "11")))
                addAll(numberRegions(y = 116f, values = listOf("01", "07", "14", "22", "35", "03", "11")))
            }

        val refined =
            PpOcrBetRowRefiner.refine(regions) { box ->
                val centerY = (box.topLeft.y + box.bottomLeft.y) / 2f
                val top = if (centerY < 108f) 0.10f else 0.12f
                listOf(recognizedLine(repeatedText, NormalizedBounds(0.1f, top, 0.9f, top + 0.05f)))
            }

        assertEquals(1, refined.count { line -> line.text == repeatedText })
    }

    /** 同一重叠行出现不同合法号码时，只能由明确圆圈行标证明应保留的结果。 */
    @Test
    fun circledMarkerWinsConflictingOverlappingReplacement() {
        val markedText = "④ 01 07 14 22 35 + 03 11"
        val unmarkedText = "02 08 15 23 34 + 04 12"
        val regions =
            buildList {
                add(region("④", x = 20f, y = 100f))
                addAll(numberRegions(y = 100f, values = listOf("01", "07", "14", "22", "35", "03", "11")))
                addAll(numberRegions(y = 112f, values = listOf("02", "08", "15", "23", "34", "04", "12")))
            }

        val refined =
            PpOcrBetRowRefiner.refine(regions) { box ->
                val centerY = (box.topLeft.y + box.bottomLeft.y) / 2f
                val first = centerY < 108f
                val bounds =
                    if (first) {
                        NormalizedBounds(0.1f, 0.10f, 0.9f, 0.15f)
                    } else {
                        NormalizedBounds(0.1f, 0.12f, 0.9f, 0.17f)
                    }
                listOf(recognizedLine(if (first) markedText else unmarkedText, bounds))
            }

        assertEquals(1, refined.count { line -> line.text == markedText })
        assertEquals(0, refined.count { line -> line.text == unmarkedText })
    }

    /** 同一重叠行出现异号结果时，只有证据数量明显悬殊才保留碎片完整的一方。 */
    @Test
    fun completeEvidenceWinsConflictingOverlappingReplacement() {
        val degradedText = "06 11 16 17 25 + 01 04"
        val completeText = "06 11 16 17 35 + 01 04"
        val regions =
            buildList {
                addAll(numberRegions(y = 100f, values = listOf("06", "11", "16", "17")))
                addAll(numberRegions(y = 112f, values = listOf("06", "11", "16", "17", "35", "01", "04")))
            }

        val refined =
            PpOcrBetRowRefiner.refine(regions) { box ->
                val centerY = (box.topLeft.y + box.bottomLeft.y) / 2f
                val degraded = centerY < 108f
                val bounds =
                    if (degraded) {
                        NormalizedBounds(0.1f, 0.10f, 0.9f, 0.15f)
                    } else {
                        NormalizedBounds(0.1f, 0.12f, 0.9f, 0.17f)
                    }
                listOf(recognizedLine(if (degraded) degradedText else completeText, bounds))
            }

        assertEquals(0, refined.count { line -> line.text == degradedText })
        assertEquals(1, refined.count { line -> line.text == completeText })
        assertTrue(refined.any { line -> line.text == "06" && line.bounds.top == 0f })
    }

    /** 两条异号结果带相同圆圈行号且极近重叠时，应保留整行置信度更高的一条。 */
    @Test
    fun confidenceResolvesConflictingReplacementWithSameCircledMarker() {
        val correctText = "③ 05 08 12 17 24 + 02 05"
        val degradedText = "③ 05 08 12 17 24 + 02 03"
        val regions =
            buildList {
                addAll(numberRegions(y = 100f, values = listOf("05", "08", "12", "17", "24", "02", "05")))
                addAll(numberRegions(y = 112f, values = listOf("05", "08", "12", "17", "24", "02", "03")))
            }

        val refined =
            PpOcrBetRowRefiner.refine(regions) { box ->
                val centerY = (box.topLeft.y + box.bottomLeft.y) / 2f
                val correct = centerY < 106f
                val bounds =
                    if (correct) {
                        NormalizedBounds(0.1f, 0.10f, 0.9f, 0.15f)
                    } else {
                        NormalizedBounds(0.1f, 0.12f, 0.9f, 0.17f)
                    }
                listOf(
                    recognizedLine(
                        text = if (correct) correctText else degradedText,
                        bounds = bounds,
                        confidence = if (correct) 0.97f else 0.93f,
                    ),
                )
            }

        assertEquals(1, refined.count { line -> line.text == correctText })
        assertEquals(0, refined.count { line -> line.text == degradedText })
    }

    /** 同一圆圈行号的跨行误裁图即使中心偏移较大，只要文字框重叠也应按置信度去重。 */
    @Test
    fun confidenceResolvesOverlappingSameMarkerBeyondCenterThreshold() {
        val correctText = "③ 05 08 12 17 24 + 02 05"
        val degradedText = "③ 05 08 16 17 35 + 01 04"
        val regions =
            buildList {
                addAll(numberRegions(y = 100f, values = listOf("05", "08", "12", "17", "24", "02", "05")))
                addAll(numberRegions(y = 116f, values = listOf("05", "08", "16", "17", "35", "01", "04")))
            }

        val refined =
            PpOcrBetRowRefiner.refine(regions) { box ->
                val correct = (box.topLeft.y + box.bottomLeft.y) / 2f < 108f
                val bounds =
                    if (correct) {
                        NormalizedBounds(0.1f, 0.10f, 0.9f, 0.15f)
                    } else {
                        NormalizedBounds(0.1f, 0.12f, 0.9f, 0.18f)
                    }
                listOf(
                    recognizedLine(
                        text = if (correct) correctText else degradedText,
                        bounds = bounds,
                        confidence = if (correct) 0.97f else 0.81f,
                    ),
                )
            }

        assertEquals(1, refined.count { line -> line.text == correctText })
        assertEquals(0, refined.count { line -> line.text == degradedText })
    }

    /** 相邻真实投注行没有明显纵向重叠时，不能因证据数量悬殊而误删任一行。 */
    @Test
    fun evidenceDifferenceDoesNotRemoveAdjacentPhysicalRows() {
        val sparseText = "01 07 14 22 35 + 03 11"
        val completeText = "02 08 15 23 34 + 04 12"
        val regions =
            buildList {
                addAll(numberRegions(y = 100f, values = listOf("01", "07", "14", "22")))
                addAll(numberRegions(y = 130f, values = listOf("02", "08", "15", "23", "34", "04", "12")))
            }

        val refined =
            PpOcrBetRowRefiner.refine(regions) { box ->
                val centerY = (box.topLeft.y + box.bottomLeft.y) / 2f
                val first = centerY < 115f
                val bounds =
                    if (first) {
                        NormalizedBounds(0.1f, 0.10f, 0.9f, 0.14f)
                    } else {
                        NormalizedBounds(0.1f, 0.16f, 0.9f, 0.20f)
                    }
                listOf(recognizedLine(if (first) sparseText else completeText, bounds))
            }

        assertEquals(1, refined.count { line -> line.text == sparseText })
        assertEquals(1, refined.count { line -> line.text == completeText })
    }

    /** 现代数字票中文整行失败后，英文模型的同裁图共识可恢复唯一合法号码。 */
    @Test
    fun latinConsensusRecoversModernNumericRows() {
        val regions =
            buildList {
                addAll(numberRegions(y = 100f, values = listOf("01", "07", "14", "22", "35", "0:", "11")))
                addAll(numberRegions(y = 130f, values = listOf("02", "08", "15", "23", "34", "04", "12")))
            }

        val refined =
            PpOcrBetRowRefiner.refine(
                regions = regions,
                recognizeMergedRow = { listOf(recognizedLine("中文整行未形成合法结构")) },
                recognizeLatinMergedRow = { box ->
                    val centerY = (box.topLeft.y + box.bottomLeft.y) / 2f
                    val text =
                        if (centerY < 115f) {
                            "01 07 14 22 35 + 07 11"
                        } else {
                            "02 08 15 23 34 + 04 12"
                        }
                    listOf(recognizedLine(text), recognizedLine(text))
                },
            )

        assertEquals(2, refined.size)
        assertTrue(refined.all { line -> line.text.contains("+") })
    }

    /** 多条投注行锚点应排除紧邻的票号、流水号、日期和销售摘要候选。 */
    @Test
    fun bettingScopeAnchorsExcludeNumericHeaderAndFooterRows() {
        val validRows =
            listOf(
                "01 07 14 22 35 + 03 11",
                "02 08 15 23 34 + 04 12",
                "03 09 16 24 33 + 05 09",
            )
        val regions =
            buildList {
                add(wideRegion("83802483", x = 150f, y = 40f))
                add(wideRegion("5F36-C833-E02D-9D94-B3A5", x = 150f, y = 70f))
                validRows.forEachIndexed { index, text ->
                    val y = 100f + index * 30f
                    add(region(('A'.code + index).toChar().toString(), x = 20f, y = y))
                    addAll(numberRegions(y = y, values = text.filter(Char::isDigit).chunked(2)))
                    add(region("x1", x = 300f, y = y))
                }
                add(wideRegion("开奖期2026092 26-08-11", x = 150f, y = 190f))
                add(wideRegion("销售期2026092-42 26-08-10", x = 150f, y = 220f))
            }
        val cropCenters = mutableListOf<Float>()

        val refined =
            PpOcrBetRowRefiner.refine(
                regions = regions,
                recognizeMergedRow = { box ->
                    val centerY = (box.topLeft.y + box.bottomLeft.y) / 2f
                    cropCenters += centerY
                    val rowIndex = ((centerY - 100f) / 30f).toInt().coerceIn(validRows.indices)
                    listOf(recognizedLine(validRows[rowIndex]), recognizedLine(validRows[rowIndex]))
                },
                recognizeLatinMergedRow = { emptyList() },
            )

        assertEquals(3, cropCenters.size, "实际二次识别中心为 $cropCenters")
        assertTrue(cropCenters.all { center -> center in 85f..175f })
        assertEquals(3, refined.count { line -> line.text.contains("+") })
    }

    /** 中文全部失败时单条候选行的英文模型裁图次数必须保持固定上限。 */
    @Test
    fun latinFallbackHasPerRowCropLimit() {
        val regions =
            numberRegions(
                y = 100f,
                values = listOf("01", "07", "14", "22", "35", "03", "11"),
            )
        var latinCropCount = 0

        PpOcrBetRowRefiner.refine(
            regions = regions,
            recognizeMergedRow = { listOf(recognizedLine("中文整行未形成合法结构")) },
            recognizeLatinMergedRow = {
                latinCropCount += 1
                listOf(recognizedLine("LATIN OCR FAILED"))
            },
        )

        assertEquals(3, latinCropCount)
    }

    /** 密集基线出现单个异常大间距时，可在仍有原图短框证据的位置补回缺失行。 */
    @Test
    fun interpolatedCropRecoversOneMissingBaseline() {
        val regions =
            buildList {
                addAll(numberRegions(y = 100f, values = listOf("01", "07", "14", "22", "35", "03", "11")))
                addAll(numberRegions(y = 130f, values = listOf("02", "08", "15", "23", "34", "04", "12")))
                addAll(numberRegions(y = 160f, values = listOf("03", "09", "16", "24", "33", "05", "09")))
                add(region("错", x = 90f, y = 190f))
                add(region("5", x = 180f, y = 190f))
                addAll(numberRegions(y = 220f, values = listOf("05", "11", "18", "25", "31", "02", "10")))
                addAll(numberRegions(y = 250f, values = listOf("06", "12", "19", "26", "30", "03", "08")))
            }

        val refined =
            PpOcrBetRowRefiner.refine(regions) { box ->
                val centerY = (box.topLeft.y + box.bottomLeft.y) / 2f
                val text =
                    when {
                        centerY < 115f -> "01 07 14 22 35 + 03 11"
                        centerY < 145f -> "02 08 15 23 34 + 04 12"
                        centerY < 175f -> "03 09 16 24 33 + 05 09"
                        centerY < 205f -> "04 10 17 25 32 + 01 06"
                        centerY < 235f -> "05 11 18 25 31 + 02 10"
                        else -> "06 12 19 26 30 + 03 08"
                    }
                listOf(recognizedLine(text))
            }

        assertEquals(6, refined.size)
        assertTrue(refined.any { line -> line.text == "04 10 17 25 32 + 01 06" })
        assertTrue(refined.none { line -> line.text == "错" || line.text == "5" })
    }

    /** 创建一条中间略向上的合成号码格序列。 */
    private fun curvedNumberRegions(
        y: Float,
        values: List<String>,
    ): List<PpOcrRecognizedRegion> {
        val xValues = listOf(60f, 90f, 120f, 150f, 180f, 230f, 260f)
        val yOffsets = listOf(-2f, -6f, -8f, -6f, -2f, 0f, 0f)
        return values.indices.map { index -> region(values[index], xValues[index], y + yOffsets[index]) }
    }

    /** 创建一条水平分布的合成号码格序列。 */
    private fun numberRegions(
        y: Float,
        values: List<String>,
    ): List<PpOcrRecognizedRegion> {
        val xValues = listOf(60f, 90f, 120f, 150f, 180f, 230f, 260f)
        return values.indices.map { index -> region(values[index], xValues[index], y) }
    }

    /** 创建一条号码中心沿指定文字斜率排列的合成序列。 */
    private fun slopedNumberRegions(
        y: Float,
        values: List<String>,
        slope: Float,
    ): List<PpOcrRecognizedRegion> {
        val xValues = listOf(60f, 90f, 120f, 150f, 180f, 230f, 260f)
        return values.indices.map { index ->
            val x = xValues[index]
            region(values[index], x, y + slope * (x - 160f))
        }
    }

    /** 创建一个带轴对齐原图框的单框识别结果。 */
    private fun region(
        text: String,
        x: Float,
        y: Float,
    ): PpOcrRecognizedRegion {
        val box =
            SourceTextBox(
                topLeft = FloatPoint(x - HALF_BOX_WIDTH, y - HALF_BOX_HEIGHT),
                topRight = FloatPoint(x + HALF_BOX_WIDTH, y - HALF_BOX_HEIGHT),
                bottomRight = FloatPoint(x + HALF_BOX_WIDTH, y + HALF_BOX_HEIGHT),
                bottomLeft = FloatPoint(x - HALF_BOX_WIDTH, y + HALF_BOX_HEIGHT),
                confidence = TEST_CONFIDENCE,
            )
        return PpOcrRecognizedRegion(box, recognizedLine(text))
    }

    /** 创建一个模拟残缺整行检测结果的宽框。 */
    private fun wideRegion(
        text: String,
        x: Float,
        y: Float,
    ): PpOcrRecognizedRegion {
        val box =
            SourceTextBox(
                topLeft = FloatPoint(x - WIDE_HALF_BOX_WIDTH, y - HALF_BOX_HEIGHT),
                topRight = FloatPoint(x + WIDE_HALF_BOX_WIDTH, y - HALF_BOX_HEIGHT),
                bottomRight = FloatPoint(x + WIDE_HALF_BOX_WIDTH, y + HALF_BOX_HEIGHT),
                bottomLeft = FloatPoint(x - WIDE_HALF_BOX_WIDTH, y + HALF_BOX_HEIGHT),
                confidence = TEST_CONFIDENCE,
            )
        return PpOcrRecognizedRegion(box, recognizedLine(text))
    }

    /** 创建一个带指定文字斜率的残缺整行检测结果。 */
    private fun slopedWideRegion(
        text: String,
        x: Float,
        y: Float,
        slope: Float,
    ): PpOcrRecognizedRegion {
        val verticalDelta = WIDE_HALF_BOX_WIDTH * slope
        val box =
            SourceTextBox(
                topLeft = FloatPoint(x - WIDE_HALF_BOX_WIDTH, y - HALF_BOX_HEIGHT - verticalDelta),
                topRight = FloatPoint(x + WIDE_HALF_BOX_WIDTH, y - HALF_BOX_HEIGHT + verticalDelta),
                bottomRight = FloatPoint(x + WIDE_HALF_BOX_WIDTH, y + HALF_BOX_HEIGHT + verticalDelta),
                bottomLeft = FloatPoint(x - WIDE_HALF_BOX_WIDTH, y + HALF_BOX_HEIGHT - verticalDelta),
                confidence = TEST_CONFIDENCE,
            )
        return PpOcrRecognizedRegion(box, recognizedLine(text))
    }

    /** 创建固定置信度和无关坐标的合成 OCR 行。 */
    private fun recognizedLine(
        text: String,
        bounds: NormalizedBounds = NormalizedBounds(0f, 0f, 1f, 1f),
        confidence: Float = TEST_CONFIDENCE,
    ): OcrTextLine =
        OcrTextLine(
            text = text,
            bounds = bounds,
            confidence = confidence,
        )

    /** 合成号码格固定参数。 */
    private companion object {
        /** 单个号码格半宽。 */
        const val HALF_BOX_WIDTH = 10f

        /** 单个号码格半高。 */
        const val HALF_BOX_HEIGHT = 10f

        /** 残缺整行宽框的半宽。 */
        const val WIDE_HALF_BOX_WIDTH = 60f

        /** 合成 OCR 固定置信度。 */
        const val TEST_CONFIDENCE = 0.95f
    }
}
