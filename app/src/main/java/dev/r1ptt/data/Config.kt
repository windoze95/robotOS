package dev.r1ptt.data

import java.net.URI

/** How a backend remembers the conversation between turns. */
enum class SessionMode(val id: String) {
    /** Stateless server: the app sends the last N messages every turn (OpenAI and most servers). */
    HISTORY("history"),

    /** Hermes Agent: only the new message is sent; `X-Hermes-Session-Id` continues the server-side transcript. */
    HERMES_SESSION("hermes-session");

    companion object {
        fun of(id: String?): SessionMode = entries.firstOrNull { it.id == id } ?: HISTORY
    }
}

/** A chat backend that speaks OpenAI-style `/chat/completions`. */
data class Provider(
    val id: String,
    val label: String,
    val baseUrl: String,
    val apiKey: String = "",
    val model: String,
    val session: SessionMode = SessionMode.HISTORY,
    /** Extra request headers, e.g. Cloudflare Access service-token headers. */
    val headers: Map<String, String> = emptyMap(),
    /** A JSON object merged into every chat request body (e.g. reasoning_effort, verbosity). */
    val extraBody: String = "{}",
    /** Read timeout for the streamed reply; agents can work for minutes. */
    val timeoutSec: Int = 120,
)

/** An OpenAI-compatible audio endpoint (`/audio/transcriptions` or `/audio/speech`). */
data class Endpoint(
    val baseUrl: String,
    /** Blank means "reuse the key of a provider on the same host". */
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

/**
 * Speech-to-speech for voice turns (keyboard closed) on the OpenAI provider: gpt-live-1 hears the
 * user and answers in voice, handing anything that needs real thinking to [backendModel]. Typed and
 * dictated turns keep using chat + text-to-speech.
 */
data class Live(
    val enabled: Boolean = true,
    val url: String = "wss://api.openai.com/v1/live/sessions",
    val model: String = "gpt-live-1",
    val voice: String = "marin",
    val backendModel: String = "gpt-6.1-sol",
    val reasoningEffort: String = "low",
    val webSearch: Boolean = true,
    /** Open a session as soon as the screen turns on (or at a press), so a hold doesn't wait for the
     *  handshake. It closes at screen-off, or idleCloseSec after the last exchange. */
    val warm: Boolean = true,
    /** The session is billed per second while open; close it this long after the last reply. */
    val idleCloseSec: Int = 20,
)

data class Power(
    /** Turn Wi-Fi off after this many minutes with the screen off; 0 keeps it on. */
    val wifiIdleMinutes: Int = 3,
    val screenTimeoutSec: Int = 15,
    /** Manual backlight level, 1..255. */
    val brightness: Int = 60,
    /** Keep the cellular modem on (needs a SIM). Off by default: an idle modem is the biggest drain. */
    val cellular: Boolean = false,
)

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
) {
    fun valid(): Boolean = (!deviceApi || (token.length in 32..256 && port in 1024..65535)) &&
        (!passthrough || dev.r1ptt.messages.SmsAddress.normalize(owner) == owner) && smsSession.length in 1..128
}

data class Config(
    val activeProvider: String = "openai",
    val providers: Map<String, Provider> = Presets.providers,
    val systemPrompt: String = DEFAULT_STYLE,
    /** Messages of context sent each turn in [SessionMode.HISTORY] mode. */
    val historyMessages: Int = 12,
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
    val live: Live = Live(),
    val power: Power = Power(),
    val hermes: HermesLink = HermesLink(),
    /** Short beeps when listening starts and on errors. */
    val earcons: Boolean = true,
    val textSizeSp: Int = 17,
    /** Keep each recorded clip under Android/data/dev.r1ptt/files/clips, for tuning the mic. */
    val saveClips: Boolean = false,
    /** evdev name of the side button's input device. */
    val buttonDevice: String = "mtk-kpd",
) {
    /** Retired profiles stay in encrypted storage, but cannot be selected or supply credentials. */
    val availableProviders: Map<String, Provider> get() = providers.filterKeys { it !in RETIRED_PROVIDER_IDS }
    val provider: Provider get() = availableProviders[activeProvider] ?: availableProviders.values.firstOrNull()
        ?: Presets.providers.getValue("openai")

    /**
     * Hermes is the agent: every turn goes to it unchanged, and it performs device actions (texts)
     * through its robotos tools. The R1 does no SMS interpretation of its own.
     */
    val hermesAgent: Boolean get() = provider.session == SessionMode.HERMES_SESSION

    /** Voice turns go speech-to-speech only on OpenAI; agent backends need their own pipeline. */
    val liveVoice: Boolean get() = live.enabled && provider.id == "openai" && keyFor(liveEndpoint).isNotBlank()

    val liveEndpoint: Endpoint get() = Endpoint(live.url, model = live.model)

    /** Dictation (and agent voice turns) stream through gpt-live-transcribe when transcription is on OpenAI. */
    val liveStt: Boolean get() = sttLive && hostOf(stt.baseUrl) == "api.openai.com" && keyFor(stt).isNotBlank()

    /** The key for [endpoint]: its own if set, else the key of a provider on the same host. */
    fun keyFor(endpoint: Endpoint): String {
        if (endpoint.apiKey.isNotBlank()) return endpoint.apiKey
        val host = hostOf(endpoint.baseUrl) ?: return ""
        return (listOf(provider) + availableProviders.values)
            .firstOrNull { hostOf(it.baseUrl) == host && it.apiKey.isNotBlank() }
            ?.apiKey.orEmpty()
    }

    companion object {
        const val OPENAI_URL = "https://api.openai.com/v1"
        // Compatibility marker only: preserve old credentials without keeping the integration active.
        internal val RETIRED_PROVIDER_IDS = setOf("openclaw")

        const val DEFAULT_STYLE =
            "You are answering through a tiny push-to-talk device with a 2.9-inch screen, and your " +
                "replies are also read aloud. Be brief: one to three short sentences unless asked for " +
                "more. Plain text only: no markdown, lists, tables, code blocks or emoji."

        fun hostOf(url: String): String? = runCatching { URI(url.trim()).host?.lowercase() }.getOrNull()
    }
}

object Presets {
    val providers: Map<String, Provider> = listOf(
        Provider(
            id = "openai", label = "ChatGPT (OpenAI)", baseUrl = Config.OPENAI_URL, model = "gpt-6.1-sol",
            extraBody = """{"reasoning_effort":"low","verbosity":"low"}""",
        ),
        Provider(
            id = "hermes", label = "Hermes Agent", baseUrl = "http://hermes.local:8642/v1",
            model = "hermes-agent", session = SessionMode.HERMES_SESSION, timeoutSec = 300,
        ),
        Provider(id = "custom", label = "Custom", baseUrl = "http://localhost:8080/v1", model = "default"),
    ).associateBy { it.id }
}
