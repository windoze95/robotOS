package dev.r1ptt

import android.app.Application
import dev.r1ptt.data.BatteryLog
import dev.r1ptt.data.ConfigStore
import dev.r1ptt.data.History
import dev.r1ptt.messages.SmsController
import dev.r1ptt.power.RadioPolicy
import dev.r1ptt.power.ScreenPolicy
import dev.r1ptt.update.UpdateManager

/**
 * Process-wide singletons. Everything runs in one process: the launcher, the settings screen and
 * the always-on [PttService] share these instances.
 */
class App : Application() {
    private val smsInstance = lazy { SmsController(this) }
    val sms: SmsController get() = smsInstance.value
    val smsBusy: Boolean get() = smsInstance.isInitialized() && smsInstance.value.busy
    /** Hermes's way into the R1 (texts, status) over the tailnet. */
    val deviceApi by lazy { dev.r1ptt.hermes.DeviceApi(this) }
    /** The owner's texts to the R1's number, passed through to Hermes. */
    val hermesTexts by lazy { dev.r1ptt.hermes.HermesTexts(this) }
    val hermesRuns by lazy { dev.r1ptt.hermes.HermesRuns(filesDir) { store.value } }
    val notices by lazy { dev.r1ptt.hermes.DeviceNotices(this) }
    val hermesQuota by lazy { dev.r1ptt.hermes.HermesSmsQuota(this) }
    lateinit var store: ConfigStore
        private set
    lateinit var history: History
        private set
    lateinit var radio: RadioPolicy
        private set
    lateinit var screen: ScreenPolicy
        private set
    lateinit var battery: BatteryLog
        private set
    lateinit var turns: TurnController
        private set
    lateinit var updates: UpdateManager
        private set

    override fun onCreate() {
        super.onCreate()
        store = ConfigStore(this)
        history = History(filesDir)
        radio = RadioPolicy(this, store)
        screen = ScreenPolicy(this)
        battery = BatteryLog(filesDir)
        turns = TurnController(this)
        updates = UpdateManager(this)
        updates.recover()
    }
}
