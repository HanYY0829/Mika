package com.wangzan101.mika.cap.phone.display

/**
 * 对话里虚拟屏小窗的输入口:用户在预览画面上点 / 滑,换算成虚拟屏逻辑坐标注入。
 * [fx] / [fy] 是相对预览画面的 0..1 坐标(左上角 0,0、右下角 1,1),由实现按虚拟屏当前逻辑宽高换算。
 * 只在用户开了「允许我在小窗里直接操作」、且小窗已展开时才会被调用。
 */
interface PipInput {
    /** 在预览画面的 (fx, fy) 点一下。 */
    suspend fun tap(fx: Float, fy: Float)

    /** 一根手指沿 [points](0..1 坐标)滑一笔,从按下到抬起约 [durationMs] 毫秒;至少两个点。 */
    suspend fun swipe(points: List<Pair<Float, Float>>, durationMs: Long)

    /** 在虚拟屏上按一次返回键。 */
    suspend fun back()
}
