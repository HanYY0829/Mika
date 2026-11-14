package com.wangzan101.mika.core.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnTextTest {
    private fun auth(s: String) = LearnText.authCode(LearnText.nfkc(s))

    @Test fun loginCodesUnderOtherNamesAreCaught() {
        listOf(
            "登录码 482913", "登录码482913", "登录码:482913", "登录码是 482913", "你的登陆码为 4829",
            "482913 是你的登录码", "授权码 A-482913", "确认码 482 913", "登录码 4 8 2 9 1 3",
            "登录码 ４８２９１３", "驗證碼 482913", "登入碼：482913", "登录码(482913)", "登录码【482913】",
            "密碼 abc12345", "パスワード: hunter2x", "비밀번호 1234", "contraseña: abc12345", "senha 482913", "пароль qwerty12",
            "認証コード 482913", "인증번호 482913", "인증 코드: 482913",
            "código de verificación: 482913", "Código de verificação 482913",
            "Код подтверждения: 482913", "Kode verifikasi 482913", "رمز التحقق 482913", "کد تایید ۴۸۲۹۱۳",
        ).forEach { assertTrue(it, auth(it)) }
    }

    @Test fun aBareEnglishCodeIsCaughtUnlessTheWordBeforeSaysOtherwise() {
        listOf(
            "Login code: 482913", "Your code is 482913", "Your Example ID Code is: 482913", "sign-in code 482913",
            "Your chat code: 482-913", "482913 is your login code", "482913 is your Example verification code",
            "code 482913", "Code: 4829", "code is 4829",
        ).forEach { assertTrue(it, auth(it)) }
        listOf(
            "zip code 10001", "Postal code: 100080", "error code 5001", "QR code 1234", "promo code 2024",
            "code review 2026", "barcode 12345678", "decode 12345", "10001 is your zip code",
            // 只隔着空格的 4 位数多半是年份
            "Advent of Code 2026", "Summer of Code 2026", "Code 2026 大会", "the code issue 12345",
        ).forEach { assertFalse(it, auth(it)) }
    }

    @Test fun codesThatAreNotCredentialsAndOrdinaryNumbersStay() {
        listOf(
            "取件码 482913", "兑换码 482913", "邀请码 482913", "扫二维码领 5000 积分", "邮编 100080", "页码 1024",
            "到账 3500 元", "订单 2026 年 11 月发货", "错误代码 5001", "电话号码 4829", "登录码已发送,5 分钟内有效",
            "确认码头的 2026 班次", "contraseña nueva enviada", "senha incorreta", "パスワード reset", "密碼已更新",
            // 密码这个词后面的句子里有个数字,不是密码
            "senha alterada em 12 de novembro", "contraseña cambiada el 12 de noviembre", "kata sandi diubah pada 12 November",
        ).forEach { assertFalse(it, auth(it)) }
    }

    private fun mask(s: String) = LearnText.maskLinksAndLongNumbers(s, LearnTuning.NOTE_DIGIT_RUN_MAX)

    @Test fun datesTimesAndAmountsAreNotTakenForASplitNumber() {
        listOf(
            "2026-11-12 开会", "2026-11-12 15:30 开会", "2026-11-12 15 点开会", "11-12 至 11-15 出差", "比分 3-2",
            "10-12 点之间送到", "到账 1 200 元", "2026-11-12 2026-11-15 两天", "2026-1-5 至 2026-1-9",
            "比分 3-2 1-0 2-2 4-1 0-0", "号码 03 12 25 31 33 08 11", "11-12 15:30 到 16:00", "3 号楼 2 单元 1201",
            "第 12 期 2026 年 11 月", "100-200 元,共 3 件", "2026 11 12",
            // 两段的是范围
            "薪资 15000-25000 元", "10000-12000 步", "10000 12000",
            // 日在前的日期、别的横线
            "12-11-2026 15:30 meeting", "5-1-2026 9:00", "2026–11–12 15:30", "2026‐11‐12 15 点",
        ).forEach {
            assertEquals(it, mask(it))
            assertFalse(it, LearnText.longDigits(LearnText.nfkc(it)))
        }
        // 六位的一段在句子里留着(名字里本来就不许有六位连着的数字,那是原有的规矩)
        assertEquals("预算 100000-200000 元", mask("预算 100000-200000 元"))
    }

    @Test fun aNumberWrittenInGroupsIsMaskedWhole() {
        assertEquals("回电 …", mask("回电 138 1234 5678"))
        assertEquals("回电 … 找王", mask("回电 138-1234-5678 找王"))
        assertEquals("座机 …", mask("座机 010-12345678")) // 后一段本来就够长,前面的区号一起遮
        assertEquals("卡 … 已扣款", mask("卡 6222 0212 3456 7890 已扣款"))
        assertEquals("客服 …", mask("客服 1-800-123-4567"))
        assertEquals("回电 …", mask("回电 １３８ １２３４ ５６７８")) // 全角
        // 日期留着,后面的号码照遮
        assertEquals("2026-11-12 回电 …", mask("2026-11-12 回电 138 1234 5678"))
        assertEquals("2026-11-12 …", mask("2026-11-12 138 1234 5678"))
        listOf("138 1234 5678", "王 138-1234-5678", "010-12345678").forEach {
            assertTrue(it, LearnText.longDigits(LearnText.nfkc(it)))
        }
    }

    @Test fun moreDomainEndingsAreRecognised() {
        listOf("example.dev", "看 campus.example.edu 的通知", "agency.gov/notice", "news.example.uk", "shop.example.tech/abc", "a.example.jp").forEach {
            assertTrue(it, LearnText.urlLike(LearnText.nfkc(it)))
            assertFalse(it, "example" in mask(it) || "agency" in mask(it))
        }
        assertEquals("详情见 … 和 …", mask("详情见 docs.example.dev/start 和 example.edu"))
    }

    @Test fun ordinaryNamesWithADotAreNotTakenForAddresses() {
        listOf(
            "Node.js", "Vue.js 和 Next.js", "README.md", "main.py", "v1.2.3", "Mr.Wang 来电", "3.5 折", "10 a.m. 开会",
            "No.1 中学", "e.g. 周五", "report.pdf", "build.gradle.kts", "example.devices", "foo.community",
            "ABC Co.Ltd", "XYZ Pte.Ltd", "Dr.Au", "Mr.Page", "Mr.Art",
        ).forEach {
            assertFalse(it, LearnText.urlLike(LearnText.nfkc(it)))
            assertEquals(it, mask(it))
        }
    }

    @Test fun theWholeGateStillIncludesTheMainFilter() {
        assertTrue(LearnText.sensitive("验证码 482913"))
        assertTrue(LearnText.sensitive("登录码 482913"))
        assertFalse(LearnText.sensitive("取件码 482913"))
        assertFalse(LearnText.sensitive("周五 15:30 开会"))
    }
}
