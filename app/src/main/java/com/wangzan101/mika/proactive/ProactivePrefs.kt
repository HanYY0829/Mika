package com.wangzan101.mika.proactive

import android.content.SharedPreferences
import com.wangzan101.mika.core.proactive.TriageConsent

/**
 * 主动能力的几项小状态:打开功能的时间、上次锁屏的时间、去重表(只有通知键的哈希和时间,不含通知内容,也没有原始的键)、
 * 用户同意过的服务商 id 与模型名;几个子开关和各个 App 的来源档位;归纳里还没落盘的删除([LearnPurges]:
 * 包名、「敏感类」这个记号,和单删的那些——话题、句子的随机编号,某天某周某月的日子,联系人的身份 id。
 * 联系人的身份 id 是从 App 和会话名算出来的哈希,和去重表里通知键的哈希是一类东西:没有名字本身,但不是随机的);
 * 今天的提醒对话是哪一个(对话 id 和日子)。收件箱、判定结果都不落盘;
 * 通知文字只在用户点开提醒时写进那个对话(见 [NoticeTalks]),不在这里。
 */
class ProactivePrefs(private val prefs: SharedPreferences) : LearnPurges, AllowRevokes {
    var enabledAtMs: Long
        get() = prefs.getLong(ENABLED_AT, 0L)
        set(v) { prefs.edit().putLong(ENABLED_AT, v).apply() }

    var lastLockedAtMs: Long
        get() = prefs.getLong(LAST_LOCKED_AT, 0L)
        set(v) { prefs.edit().putLong(LAST_LOCKED_AT, v).apply() }

    var dedup: String?
        get() = prefs.getString(DEDUP, null)
        set(v) { prefs.edit().putString(DEDUP, v).apply() }

    fun clearDedup() { prefs.edit().remove(DEDUP).apply() }

    /**
     * 用户同意把通知文字发给哪一家的哪个模型(判定、归纳都用它);没同意过、或关掉总开关之后为 null。
     * 没有它,什么都不发。
     */
    var consent: TriageConsent?
        get() {
            val provider = prefs.getString(CONSENT_PROVIDER, null) ?: return null
            val model = prefs.getString(CONSENT_MODEL, null) ?: return null
            return TriageConsent(provider, model)
        }
        set(v) {
            val e = prefs.edit()
            if (v == null) e.remove(CONSENT_PROVIDER).remove(CONSENT_MODEL)
            else e.putString(CONSENT_PROVIDER, v.providerId).putString(CONSENT_MODEL, v.model)
            e.commit()
        }

    /** 「敏感类推断」(健康、财务、宗教、政治、性取向);默认关。 */
    var learnSensitive: Boolean
        get() = prefs.getBoolean(LEARN_SENSITIVE, false)
        set(v) { prefs.edit().putBoolean(LEARN_SENSITIVE, v).commit() }

    /**
     * 「当日消息归纳」:默认关。开着时归纳的 App 的通知原文会加密存在本机(最多两天,见 [NoticeJournalFile]);
     * 关掉时存着的原文一并删掉(已经归纳出的句子和各级归纳留着)。有自己的说明,不跟着总开关的同意自动打开。
     */
    var learnDigest: Boolean
        get() = prefs.getBoolean(LEARN_DIGEST, false)
        set(v) { prefs.edit().putBoolean(LEARN_DIGEST, v).commit() }

    /** 激进程度(见 [com.wangzan101.mika.core.proactive.LearnLevel]);存的是它的名字,默认最保守的一档。 */
    var learnLevel: String?
        get() = prefs.getString(LEARN_LEVEL, null)
        set(v) { prefs.edit().putString(LEARN_LEVEL, v).commit() }

    /** 来源档位调成「高」的 App(只有包名);「低」的在 [learnOptOut] 里,读而两边都不在的是「中」。 */
    var learnHigh: Set<String>
        get() = prefs.getStringSet(LEARN_HIGH, null)?.toSet().orEmpty()
        set(v) { prefs.edit().putStringSet(LEARN_HIGH, HashSet(v)).commit() }

    /** 来源档位是「低」的 App(只有包名):读它的通知、判定和提醒,但不从它归纳。默认空。 */
    var learnOptOut: Set<String>
        get() = prefs.getStringSet(LEARN_OPT_OUT, null)?.toSet().orEmpty()
        set(v) { prefs.edit().putStringSet(LEARN_OPT_OUT, HashSet(v)).commit() }

    /** 今天的提醒对话(见 [NoticeTalks]):对话 id,和它是哪一天开的(本地日期的纪元日)。没开过为 null。 */
    var hintTalk: Pair<String, Long>?
        get() {
            val id = prefs.getString(HINT_TALK_ID, null) ?: return null
            return id to prefs.getLong(HINT_TALK_DAY, Long.MIN_VALUE)
        }
        set(v) {
            val e = prefs.edit()
            if (v == null) e.remove(HINT_TALK_ID).remove(HINT_TALK_DAY) else e.putString(HINT_TALK_ID, v.first).putLong(HINT_TALK_DAY, v.second)
            e.apply()
        }

    /** 话题池上还没做成的删除(见 [LearnPurges])。 */
    override var topicPurges: Set<String>
        get() = prefs.getStringSet(LEARN_PURGE_TOPICS, null)?.toSet().orEmpty()
        set(v) { prefs.edit().putStringSet(LEARN_PURGE_TOPICS, HashSet(v)).commit() }

    /** 联系人上还没做成的删除。 */
    override var contactPurges: Set<String>
        get() = prefs.getStringSet(LEARN_PURGE_CONTACTS, null)?.toSet().orEmpty()
        set(v) { prefs.edit().putStringSet(LEARN_PURGE_CONTACTS, HashSet(v)).commit() }

    /** 句子和各级归纳上还没落盘的删除(包名,和单删的记号:句子的编号、某天某周某月的日子)。 */
    override var digestPurges: Set<String>
        get() = prefs.getStringSet(LEARN_PURGE_DIGEST, null)?.toSet().orEmpty()
        set(v) { prefs.edit().putStringSet(LEARN_PURGE_DIGEST, HashSet(v)).commit() }

    /** 通知原文上还没落盘的按 App 删除(包名)。 */
    override var journalPurges: Set<String>
        get() = prefs.getStringSet(LEARN_PURGE_JOURNAL, null)?.toSet().orEmpty()
        set(v) { prefs.edit().putStringSet(LEARN_PURGE_JOURNAL, HashSet(v)).commit() }

    /** 用户选了「不读」、名单文件没写成的 App(只有包名);启动时补做([AllowEdits.replay])。 */
    override var allowRevoked: Set<String>
        get() = prefs.getStringSet(ALLOW_REVOKED, null)?.toSet().orEmpty()
        set(v) { prefs.edit().putStringSet(ALLOW_REVOKED, HashSet(v)).commit() }

    companion object {
        const val FILE = "proactive"
        private const val ENABLED_AT = "enabled_at_ms"
        private const val LAST_LOCKED_AT = "last_locked_at_ms"
        private const val DEDUP = "dedup_v2"
        // 键名里只写着判定,存的是判定和归纳共用的那一份同意
        private const val CONSENT_PROVIDER = "triage_consent_provider"
        private const val CONSENT_MODEL = "triage_consent_model"
        private const val LEARN_OPT_OUT = "learn_opt_out"
        private const val LEARN_SENSITIVE = "learn_sensitive"
        private const val LEARN_DIGEST = "learn_digest"
        private const val LEARN_LEVEL = "learn_level"
        private const val LEARN_HIGH = "learn_high"
        private const val LEARN_PURGE_TOPICS = "learn_purge_topics"
        private const val HINT_TALK_ID = "hint_talk_id"
        private const val HINT_TALK_DAY = "hint_talk_day"
        private const val LEARN_PURGE_CONTACTS = "learn_purge_contacts"
        private const val LEARN_PURGE_DIGEST = "learn_purge_digest"
        private const val LEARN_PURGE_JOURNAL = "learn_purge_journal"
        private const val ALLOW_REVOKED = "allow_revoked"
    }
}
