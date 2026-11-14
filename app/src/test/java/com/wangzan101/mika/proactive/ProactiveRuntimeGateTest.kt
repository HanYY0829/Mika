package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.proactive.LearnLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class ProactiveRuntimeGateTest {
    // 读通知的工具:主动能力、「当日消息归纳」都开着,激进程度不是最保守的一档,才给任务
    @Test fun tasksReadNoticesOnlyWhenEverySwitchIsOnAndTheLevelAllowsIt() {
        for (proactive in listOf(true, false)) for (digest in listOf(true, false)) for (level in LearnLevel.entries) {
            val expected = proactive && digest && level != LearnLevel.Conservative
            assertEquals("$proactive $digest $level", expected, ProactiveRuntime.tasksMayReadNotices(proactive, digest, level))
        }
    }
}
