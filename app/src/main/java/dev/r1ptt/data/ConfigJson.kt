package dev.r1ptt.data

import org.json.JSONObject

/**
 * Config <-> JSON. [merge] lays a partial document over an existing config, so an import can set a
 * single key (say `providers.openai.apiKey`) without restating everything else.
 */
object ConfigJson {

    fun toJson(c: Config): JSONObject = JSONObject().apply {
        put("activeProvider", c.activeProvider.takeUnless { it in Config.RETIRED_PROVIDER_IDS } ?: c.provider.id)
        put("providers", JSONObject().apply { c.providers.forEach { (id, p) -> put(id, provider(p)) } })
        put("systemPrompt", c.systemPrompt)
        put("historyMessages", c.historyMessages)
        put("stt", endpoint(c.stt).put("language", c.sttLanguage).put("live", c.sttLive).put("liveModel", c.sttLiveModel)
            .put("liveUrl", c.sttLiveUrl).put("delay", c.sttDelay))
        put("tts", endpoint(c.tts.endpoint).apply {
            put("enabled", c.tts.enabled)
            put("voice", c.tts.voice)
            put("format", c.tts.format)
            put("sampleRate", c.tts.sampleRate)
            put("instructions", c.tts.instructions)
        })
        put("live", JSONObject().apply {
            put("enabled", c.live.enabled)
            put("url", c.live.url)
            put("model", c.live.model)
            put("voice", c.live.voice)
            put("backendModel", c.live.backendModel)
            put("reasoningEffort", c.live.reasoningEffort)
            put("webSearch", c.live.webSearch)
            put("warm", c.live.warm)
            put("idleCloseSec", c.live.idleCloseSec)
        })
        put("power", JSONObject().apply {
            put("wifiIdleMinutes", c.power.wifiIdleMinutes)
            put("screenTimeoutSec", c.power.screenTimeoutSec)
            put("brightness", c.power.brightness)
            put("cellular", c.power.cellular)
        })
        put("hermes", JSONObject().apply {
            put("deviceApi", c.hermes.deviceApi); put("token", c.hermes.token); put("port", c.hermes.port)
            put("passthrough", c.hermes.passthrough); put("owner", c.hermes.owner)
            put("smsSession", c.hermes.smsSession); put("dailySendLimit", c.hermes.dailySendLimit)
        })
        put("earcons", c.earcons)
        put("textSizeSp", c.textSizeSp)
        put("saveClips", c.saveClips)
        put("buttonDevice", c.buttonDevice)
    }

    fun merge(base: Config, j: JSONObject): Config {
        val providers = base.providers.toMutableMap()
        j.optJSONObject("providers")?.let { all ->
            for (id in all.keys()) {
                val o = all.optJSONObject(id) ?: continue
                val old = providers[id] ?: Provider(id = id, label = id, baseUrl = "", model = "")
                providers[id] = mergeProvider(old, o)
            }
        }
        val stt = j.optJSONObject("stt")
        val tts = j.optJSONObject("tts")
        val live = j.optJSONObject("live")
        val power = j.optJSONObject("power")
        val hermes = j.optJSONObject("hermes")
        return base.copy(
            activeProvider = j.str("activeProvider", base.activeProvider)
                .takeUnless { it in Config.RETIRED_PROVIDER_IDS } ?: base.provider.id,
            providers = providers,
            systemPrompt = j.str("systemPrompt", base.systemPrompt),
            historyMessages = j.int("historyMessages", base.historyMessages),
            stt = stt?.let { mergeEndpoint(base.stt, it) } ?: base.stt,
            sttLanguage = stt?.str("language", base.sttLanguage) ?: base.sttLanguage,
            sttLive = stt?.bool("live", base.sttLive) ?: base.sttLive,
            sttLiveModel = stt?.str("liveModel", base.sttLiveModel) ?: base.sttLiveModel,
            sttLiveUrl = stt?.str("liveUrl", base.sttLiveUrl) ?: base.sttLiveUrl,
            sttDelay = stt?.str("delay", base.sttDelay) ?: base.sttDelay,
            tts = tts?.let { t ->
                base.tts.copy(
                    enabled = t.bool("enabled", base.tts.enabled),
                    endpoint = mergeEndpoint(base.tts.endpoint, t),
                    voice = t.str("voice", base.tts.voice),
                    format = t.str("format", base.tts.format),
                    sampleRate = t.int("sampleRate", base.tts.sampleRate),
                    instructions = t.str("instructions", base.tts.instructions),
                )
            } ?: base.tts,
            live = live?.let { l ->
                base.live.copy(
                    enabled = l.bool("enabled", base.live.enabled),
                    url = l.str("url", base.live.url),
                    model = l.str("model", base.live.model),
                    voice = l.str("voice", base.live.voice),
                    backendModel = l.str("backendModel", base.live.backendModel),
                    reasoningEffort = l.str("reasoningEffort", base.live.reasoningEffort),
                    webSearch = l.bool("webSearch", base.live.webSearch),
                    warm = l.bool("warm", base.live.warm),
                    idleCloseSec = l.int("idleCloseSec", base.live.idleCloseSec),
                )
            } ?: base.live,
            power = power?.let { p ->
                base.power.copy(
                    wifiIdleMinutes = p.int("wifiIdleMinutes", base.power.wifiIdleMinutes),
                    screenTimeoutSec = p.int("screenTimeoutSec", base.power.screenTimeoutSec),
                    brightness = p.int("brightness", base.power.brightness),
                    cellular = p.bool("cellular", base.power.cellular),
                )
            } ?: base.power,
            hermes = hermes?.let { h -> base.hermes.copy(
                deviceApi = h.bool("deviceApi", base.hermes.deviceApi), token = h.str("token", base.hermes.token),
                port = h.int("port", base.hermes.port), passthrough = h.bool("passthrough", base.hermes.passthrough),
                owner = h.str("owner", base.hermes.owner).let { dev.r1ptt.messages.SmsAddress.normalize(it) ?: it },
                smsSession = h.str("smsSession", base.hermes.smsSession),
                dailySendLimit = h.int("dailySendLimit", base.hermes.dailySendLimit),
            ).also { require(it.valid()) { "Hermes needs a 32+ character device token and the owner's full phone number." } } } ?: base.hermes,
            earcons = j.bool("earcons", base.earcons),
            textSizeSp = j.int("textSizeSp", base.textSizeSp),
            saveClips = j.bool("saveClips", base.saveClips),
            buttonDevice = j.str("buttonDevice", base.buttonDevice),
        )
    }

    private fun provider(p: Provider) = JSONObject().apply {
        put("label", p.label)
        put("baseUrl", p.baseUrl)
        put("apiKey", p.apiKey)
        put("model", p.model)
        put("session", p.session.id)
        put("headers", JSONObject(p.headers))
        put("extraBody", runCatching { JSONObject(p.extraBody) }.getOrElse { JSONObject() })
        put("timeoutSec", p.timeoutSec)
    }

    private fun mergeProvider(old: Provider, o: JSONObject): Provider {
        val headers = o.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { h.getString(it) } }
        val extra = when (val e = o.opt("extraBody")) {
            is JSONObject -> e.toString()
            is String -> JSONObject(e.ifBlank { "{}" }).toString() // throws on invalid JSON: reject the import
            else -> old.extraBody
        }
        return old.copy(
            label = o.str("label", old.label),
            baseUrl = o.str("baseUrl", old.baseUrl),
            apiKey = o.str("apiKey", old.apiKey),
            model = o.str("model", old.model),
            session = if (o.has("session")) SessionMode.of(o.getString("session")) else old.session,
            headers = headers ?: old.headers,
            extraBody = extra,
            timeoutSec = o.int("timeoutSec", old.timeoutSec),
        )
    }

    private fun endpoint(e: Endpoint) = JSONObject().apply {
        put("baseUrl", e.baseUrl)
        put("apiKey", e.apiKey)
        put("model", e.model)
    }

    private fun mergeEndpoint(old: Endpoint, o: JSONObject) = old.copy(
        baseUrl = o.str("baseUrl", old.baseUrl),
        apiKey = o.str("apiKey", old.apiKey),
        model = o.str("model", old.model),
    )

    private fun JSONObject.str(k: String, d: String) = if (has(k) && !isNull(k)) getString(k) else d
    private fun JSONObject.int(k: String, d: Int) = if (has(k) && !isNull(k)) getInt(k) else d
    private fun JSONObject.bool(k: String, d: Boolean) = if (has(k) && !isNull(k)) getBoolean(k) else d
}
