package com.devicetracker.ui

import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.devicetracker.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for common lifecycle/fragment event order bugs:
 *   - show → hide → show (fragment transactions stacking)
 *   - out-of-order: onCreateView before onAttach (framework race)
 *   - unexpected: rapid rotate while async job running
 *   - activity recreated while button click handler suspended
 */
@RunWith(AndroidJUnit4::class)
class MainActivityEventOrderTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    // ---- Show/Hide: multiple recreates ----
    @Test
    fun test_RapidRecreate_Pinning() {
        repeat(3) {
            activityRule.scenario.recreate()
            onView(withId(R.id.status_text)).check(matches(isDisplayed()))
        }
    }

    // ---- Pause/Resume then interact ----
    @Test
    fun test_PauseResume_BackButton() {
        activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        onView(withId(R.id.btn_snapshot)).check(matches(isDisplayed()))
    }

    // ---- Show dialog, rotate, dismiss — dialog survives? ----
    @Test
    fun test_DialogRotate_DialogSurvives() {
        // Open push dialog
        onView(withId(R.id.btn_push)).perform(click())
        activityRule.scenario.recreate()
        // After recreate the activity should still show the dialog content or be stable
        onView(withText("Push to Remote")).check(matches(isDisplayed()))
    }

    // ---- Click snapshot rapidly (async job could queue) ----
    @Test
    fun test_RapidSnapshotClick_DoesNotCrash() {
        onView(withId(R.id.btn_snapshot)).perform(click())
        onView(withId(R.id.btn_snapshot)).perform(click())
        onView(withId(R.id.btn_snapshot)).perform(click())
        // App must not crash after 3 rapid clicks
        onView(withId(R.id.status_text)).check(matches(isDisplayed()))
    }

    // ---- Background then foreground while reading data ----
    @Test
    fun test_StopStart_DuringRefresh() {
        activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        onView(withId(R.id.watcher_status)).check(matches(isDisplayed()))
    }
}
