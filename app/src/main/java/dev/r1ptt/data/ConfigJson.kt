package dev.r1ptt.data

import org.json.JSONObject

/**
 * Config <-> JSON. [merge] lays a partial document over an existing config, so an import can set a
 * single key (say `agent.apiKey`) without restating everything else.
 */
object ConfigJson {

    fun toJson(c: Config): JSONObject = JSONObject().apply {
        put("agent", JSONObject().apply {
            put("baseUrl", c.agent.baseUrl); put("apiKey", c.agent.apiKey); put("model", c.agent.model)
            put("headers", JSONObject(c.agent.headers)); put("timeoutSec", c.agent.timeoutSec)
        })
        put("openaiKey", c.openaiKey)
        put("systemPrompt", c.systemPrompt)
        put("stt", endpoint(c.stt).put("language", c.sttLanguage).put("live", c.sttLive).put("liveModel", c.sttLiveModel)
            .put("liveUrl", c.sttLiveUrl).put("delay", c.sttDelay))
        put("tts", endpoint(c.tts.endpoint).apply {
            put("enabled", c.tts.enabled)
            put("voice", c.tts.voice)
            put("format", c.tts.format)
            put("sampleRate", c.tts.sampleRate)
            put("instructions", c.tts.instructions)
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
        val agent = j.optJSONObject("agent")
        val stt = j.optJSONObject("stt")
        val tts = j.optJSONObject("tts")
        val power = j.optJSONObject("power")
        val hermes = j.optJSONObject("hermes")
        return base.copy(
            agent = agent?.let { a -> base.agent.copy(
                baseUrl = a.str("baseUrl", base.agent.baseUrl), apiKey = a.str("apiKey", base.agent.apiKey),
                model = a.str("model", base.agent.model), timeoutSec = a.int("timeoutSec", base.agent.timeoutSec),
                headers = a.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { h.getString(it) } } ?: base.agent.headers,
            ) } ?: base.agent,
            openaiKey = j.str("openaiKey", base.openaiKey),
            systemPrompt = j.str("systemPrompt", base.systemPrompt),
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
