package dev.r1ptt.data

import java.net.URI

/**
 * Hermes Agent's OpenAI-compatible API server. Every turn goes here; `X-Hermes-Session-Id` keeps
 * the conversation on the server, so only the new message is sent.
 */
data class Agent(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "hermes-agent",
    /** Optional explicit provider paired with [model]; blank keeps the gateway's default. */
    val provider: String = "",
    /** Extra request headers, e.g. an access proxy's service-token headers. */
    val headers: Map<String, String> = emptyMap(),
    /** Read timeout for the streamed reply; an agent can work for minutes. */
    val timeoutSec: Int = 300,
)

/** An OpenAI-compatible audio endpoint (`/audio/transcriptions` or `/audio/speech`). */
data class Endpoint(
    val baseUrl: String,
    /** Blank means [Config.openaiKey] when the endpoint is OpenAI's. */
    val apiKey: String = "",
    val model: String,
)

data class Tts(
    val enabled: Boolean = true,
    val endpoint: Endpoint = Endpoint(Config.OPENAI_URL, model = "gpt-4o-mini-tts-2025-12-15"),
    val voice: String = "marin",
    /** "pcm" (raw 16-bit, OpenAI) or "wav" (servers without raw PCM, e.g. Speaches). */
    val format: String = "pcm",
    /** Sample rate of raw PCM replies (OpenAI and Kokoro: 24 kHz); WAV replies carry their own. */
    val sampleRate: Int = 24_000,
    /** Optional speaking-style hint; only sent when set (gpt-4o-mini-tts understands it). */
    val instructions: String = "",
)

data class Power(
    /** Pocket-mode delay. Legacy 0 means stay reachable and is normalized when settings load. */
    val wifiIdleMinutes: Int = 3,
    val screenTimeoutSec: Int = 15,
    /** Manual backlight level, 1..255. */
    val brightness: Int = 60,
    /** Keep the cellular modem on (needs a SIM). Off by default: an idle modem is the biggest drain. */
    val cellular: Boolean = false,
    /** Keep radios available on battery without discarding the pocket-mode idle interval. */
    val stayReachable: Boolean = false,
) {
    val reachableOnBattery: Boolean get() = stayReachable || wifiIdleMinutes <= 0
    val pocketIdleMinutes: Int get() = wifiIdleMinutes.takeIf { it > 0 } ?: 3

    /** An explicit mode change always leaves a usable, remembered pocket-mode delay. */
    fun withReachability(enabled: Boolean) = copy(stayReachable = enabled, wifiIdleMinutes = pocketIdleMinutes)
    fun normalized() = withReachability(reachableOnBattery)
}

/**
 * Hermes as the agent and this R1 as its remote. Hermes's `robotos` MCP server reaches [port] over
 * the tailnet with [token] to send texts and read device state; texts from [owner] pass through to
 * Hermes and its reply is texted back. Power state never changes any of this; it only keeps radios up.
 */
data class HermesLink(
    /** Serve the device API to Hermes. */
    val deviceApi: Boolean = false,
    /** The bearer token Hermes's MCP server presents (32–256 characters). */
    val token: String = "",
    val port: Int = 8765,
    /** Forward texts from [owner] to Hermes and text back its reply. */
    val passthrough: Boolean = false,
    /** The owner's own phone number; only its texts reach Hermes. */
    val owner: String = "",
    /** The Hermes session that holds the SMS conversation. */
    val smsSession: String = "robotos-sms",
    /** Texts Hermes may send through this R1 per day (0 = no limit). */
    val dailySendLimit: Int = 100,
    val allowSpeak: Boolean = false,
    val allowShow: Boolean = false,
    val allowVolume: Boolean = false,
    val allowSaveRecipient: Boolean = false,
) {
    fun valid(): Boolean = (!deviceApi || (token.length in 32..256 && port in 1024..65535)) &&
        (!passthrough || dev.r1ptt.messages.SmsAddress.normalize(owner) == owner) && smsSession.length in 1..128
}

data class Config(
    /** Hermes, the agent: the R1 is its remote. */
    val agent: Agent = Agent(),
    /** OpenAI API key, used only for speech: transcription and reading replies aloud. */
    val openaiKey: String = "",
    val systemPrompt: String = DEFAULT_STYLE,
    val stt: Endpoint = Endpoint(OPENAI_URL, model = "gpt-transcribe"),
    /** ISO-639-1 language hint for transcription; blank = auto-detect. */
    val sttLanguage: String = "",
    /** Stream push-to-talk transcription (gpt-live-transcribe) while the button is held: words appear as you
     *  speak and the final text is ready ~0.5 s after release. Only used when transcription is on OpenAI. */
    val sttLive: Boolean = true,
    val sttLiveModel: String = "gpt-live-transcribe",
    val sttLiveUrl: String = "wss://api.openai.com/v1/realtime?intent=transcription",
    /** Live transcript latency vs stability: minimal, low, medium, high or xhigh. */
    val sttDelay: String = "low",
    val tts: Tts = Tts(),
    val power: Power = Power(),
    val hermes: HermesLink = HermesLink(),
    /** Short beeps when listening starts and on errors. */
    val earcons: Boolean = true,
    /** Mute speech and feedback sounds; independent of automatic reply playback. */
    val quiet: Boolean = false,
    val textSizeSp: Int = 17,
    /** Keep each recorded clip under Android/data/dev.r1ptt/files/clips, for tuning the mic. */
    val saveClips: Boolean = false,
    /** evdev name of the side button's input device. */
    val buttonDevice: String = "mtk-kpd",
) {
    /** Push-to-talk streams through gpt-live-transcribe when transcription is on OpenAI. */
    val liveStt: Boolean get() = sttLive && isOpenAi(stt) && keyFor(stt).isNotBlank()

    /** Speech goes to OpenAI with no key for it; a self-hosted speech server needs none. */
    val missingSpeechKey: Boolean
        get() = listOfNotNull(stt, tts.endpoint.takeIf { tts.enabled }).any { isOpenAi(it) && keyFor(it).isBlank() }

    /** The key for a speech [endpoint]: its own if set, else [openaiKey] for OpenAI's. */
    fun keyFor(endpoint: Endpoint): String = endpoint.apiKey.ifBlank { if (isOpenAi(endpoint)) openaiKey else "" }

    private fun isOpenAi(endpoint: Endpoint) = hostOf(endpoint.baseUrl) == hostOf(OPENAI_URL)

    companion object {
        const val OPENAI_URL = "https://api.openai.com/v1"

        const val DEFAULT_STYLE =
            "You are answering through a tiny push-to-talk device with a 2.9-inch screen, and your " +
                "replies are also read aloud. Be brief: one to three short sentences unless asked for " +
                "more. Plain text only: no markdown, lists, tables, code blocks or emoji."

        fun hostOf(url: String): String? = runCatching { URI(url.trim()).host?.lowercase() }.getOrNull()
    }
}
