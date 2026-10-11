package dev.r1ptt.ui

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.TextView
import dev.r1ptt.App
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The orange stage tells a deliberate tap or swipe from a hand that merely touches the glass. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = App::class)
class VoiceStageTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var edits = 0
    private var cancels = 0
    private var inTime = true
    private val stage by lazy {
        VoiceStage(context).apply {
            onEdit = { edits++; inTime }
            onCancel = { cancels++; inTime }
            measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
            layout(0, 0, 300, 400)
            show()
        }
    }
    private val down = SystemClock.uptimeMillis()

    private fun touch(action: Int, x: Float, y: Float, afterMs: Long = 0) =
        MotionEvent.obtain(down, down + afterMs, action, x, y, 0).also { stage.dispatchTouchEvent(it) }.recycle()

    /** A second contact, such as another finger or the side of a hand. */
    private fun second(action: Int, afterMs: Long) {
        val pointers = Array(2) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(2) { MotionEvent.PointerCoords().apply { x = 150f + 80 * it; y = 200f; pressure = 1f; size = 1f } }
        MotionEvent.obtain(down, down + afterMs, action or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, pointers, coords,
            0, 0, 1f, 1f, 0, 0, 0, 0).also { stage.dispatchTouchEvent(it) }.recycle()
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun aQuickTapKeepsTheWordsToEdit() {
        touch(MotionEvent.ACTION_DOWN, 150f, 200f)
        touch(MotionEvent.ACTION_UP, 152f, 201f, 90)
        assertEquals(1, edits)
        assertEquals(0, cancels)
        assertTrue(stage.showing)
    }

    @Test fun aThumbRestingOnTheGlassIsNotATap() {
        touch(MotionEvent.ACTION_DOWN, 150f, 200f)
        touch(MotionEvent.ACTION_UP, 150f, 200f, ViewConfiguration.getLongPressTimeout() + 100L)
        assertEquals(0, edits)
        assertEquals(0, cancels)
    }

    @Test fun aSwipeFollowsTheFingerAndCancelsPastAThirdOfTheScreen() {
        touch(MotionEvent.ACTION_DOWN, 150f, 120f)
        touch(MotionEvent.ACTION_MOVE, 150f, 170f, 40)
        assertEquals(50f, stage.translationY, .01f)
        assertTrue(stage.alpha < 1f)
        touch(MotionEvent.ACTION_UP, 150f, 225f, 80) // 105 px, past a third of the 300 px side
        assertEquals(1, cancels)
        assertEquals(0, edits)
        assertFalse(stage.showing)
        settle()
        assertEquals(View.GONE, stage.visibility)
    }

    @Test fun aShortSwipeSettlesBackAndDoesNothing() {
        touch(MotionEvent.ACTION_DOWN, 150f, 200f)
        touch(MotionEvent.ACTION_MOVE, 190f, 200f, 40)
        assertEquals(40f, stage.translationX, .01f)
        touch(MotionEvent.ACTION_UP, 190f, 200f, 80)
        assertEquals(0, cancels)
        assertEquals(0, edits)
        settle()
        assertTrue(stage.showing)
        assertEquals(0f, stage.translationX, .01f)
        assertEquals(1f, stage.alpha, .01f)
    }

    @Test fun whenItIsTooLateToCancelTheStageSettlesBack() {
        inTime = false
        touch(MotionEvent.ACTION_DOWN, 50f, 200f)
        touch(MotionEvent.ACTION_MOVE, 120f, 200f, 40)
        touch(MotionEvent.ACTION_UP, 250f, 200f, 80)
        assertEquals(1, cancels)
        assertTrue(stage.showing)
        settle()
        assertEquals(0f, stage.translationX, .01f)
    }

    @Test fun aSecondContactIsNeitherATapNorASwipe() {
        touch(MotionEvent.ACTION_DOWN, 150f, 200f)
        second(MotionEvent.ACTION_POINTER_DOWN, 20)
        second(MotionEvent.ACTION_POINTER_UP, 40)
        touch(MotionEvent.ACTION_UP, 150f, 200f, 60)
        // A swipe that picks up a second contact on the way is dropped too.
        touch(MotionEvent.ACTION_DOWN, 50f, 200f, 100)
        touch(MotionEvent.ACTION_MOVE, 150f, 200f, 120)
        second(MotionEvent.ACTION_POINTER_DOWN, 140)
        touch(MotionEvent.ACTION_UP, 250f, 200f, 160)
        assertEquals(0, edits)
        assertEquals(0, cancels)
        settle()
        assertTrue(stage.showing)
        assertEquals(0f, stage.translationX, .01f)
    }

    @Test fun onceHiddenItIgnoresTouches() {
        stage.hide()
        touch(MotionEvent.ACTION_DOWN, 150f, 200f)
        touch(MotionEvent.ACTION_UP, 150f, 200f, 50)
        assertEquals(0, edits)
    }

    @Test fun theWordsSayWhatLettingGoWillDo() {
        stage.update("Text Sam", .2f, sending = false, editing = false, elapsedMs = 2000)
        assertTrue(texts(stage).containsAll(listOf("Listening", "Release to send", "Tap to edit · swipe to cancel", "0:02")))
        stage.update("Text Sam", .2f, sending = false, editing = true, elapsedMs = 3000)
        assertTrue(texts(stage).containsAll(listOf("Dictating", "Release to edit", "Swipe to cancel")))
        stage.update("Text Sam", 0f, sending = true, editing = true, elapsedMs = 3000)
        assertTrue(texts(stage).containsAll(listOf("Dictating", "Transcribing")))
        stage.update("Text Sam", 0f, sending = true, editing = false, elapsedMs = 3000)
        assertTrue(texts(stage).containsAll(listOf("Sending", "Transcribing", "Tap to edit · swipe to cancel")))
    }
}
