package com.wangzan101.mika.calls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallFarewellPolicyTest {
    @Test fun recognizesCompleteDirectClosingsInNaturalSpeech() {
        listOf(
            "再见。", "拜拜！", "拜拜再见", "好谢谢拜拜", "谢谢您，再见！", "再见，谢谢！",
            "好的，那就先这样吧，再见。", "我这边没别的事了，回头再聊，拜拜。",
            "那我就先挂了啊", "我这边先挂电话了。", "你挂吧！", "可以挂了。",
            "麻烦您帮我挂断电话吧。", "麻烦挂一下电话。", "请挂断。", "可以结束这次通话了。",
        ).forEach { assertTrue(it, CallFarewellPolicy.isExplicitCallerFarewell(it)) }
    }

    @Test fun ordinaryAcknowledgementsAndOpenEndedClosuresAreNotHangupConsent() {
        listOf("", "。", "好", "好的，谢谢。", "没问题", "我没别的事了。", "那就先这样吧。",
            "回头再聊", "拜", "挂了", "还行，辛苦了", "通话结束").forEach {
            assertFalse(it, CallFarewellPolicy.isExplicitCallerFarewell(it))
        }
    }

    @Test fun questionsNegationsAndQuotesDoNotTriggerHangup() {
        listOf(
            "我说再见了吗？", "现在可以挂断电话了吗", "可以挂了？", "你挂吧?", "可以挂了吧？",
            "不要挂断电话。", "先别挂。", "我还没说再见", "不是叫你挂断电话",
            "‘再见’", "他说再见", "我让他挂断电话", "他说：‘你挂吧’", "请把再见这个词记下来",
            "请问可以先挂了再打回来吗？再见", "没事，不用挂，再见",
        ).forEach { assertFalse(it, CallFarewellPolicy.isExplicitCallerFarewell(it)) }
    }

    @Test fun aFarewellWithMoreBusinessStillLeavesTheCallOpen() {
        listOf(
            "再见，等一下我还有个问题", "我先挂了吧，不过再确认一遍", "好，改成三点，再见",
            "拜拜，顺便帮我问下时间", "刚才说再见是开玩笑", "那就这样吧，再见。别挂！",
            "再见".repeat(60),
        ).forEach { assertFalse(it, CallFarewellPolicy.isExplicitCallerFarewell(it)) }
    }
}
