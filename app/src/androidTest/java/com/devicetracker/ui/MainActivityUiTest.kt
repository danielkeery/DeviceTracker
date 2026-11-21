package com.devicetracker.ui

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
 * Instrumentation UI tests for MainActivity.
 * Every UI element is tested: buttons, text views, status reads.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityUiTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    // ---- All view elements exist ----
    @Test
    fun test_StatusText_Exists() {
        onView(withId(R.id.status_text)).check(matches(isDisplayed()))
    }

    @Test
    fun test_RepoPath_Exists() {
        onView(withId(R.id.repo_path)).check(matches(isDisplayed()))
    }

    @Test
    fun test_RepoSize_Exists() {
        onView(withId(R.id.repo_size)).check(matches(isDisplayed()))
    }

    @Test
    fun test_PackagesCount_Exists() {
        onView(withId(R.id.packages_count)).check(matches(isDisplayed()))
    }

    @Test
    fun test_LastSnapshot_Exists() {
        onView(withId(R.id.last_snapshot)).check(matches(isDisplayed()))
    }

    @Test
    fun test_Changelog_Exists() {
        onView(withId(R.id.changelog)).check(matches(isDisplayed()))
    }

    @Test
    fun test_BtnSnapshot_ExistsAndClickable() {
        onView(withId(R.id.btn_snapshot))
            .check(matches(isDisplayed()))
            .check(matches(isEnabled()))
    }

    @Test
    fun test_BtnPush_ExistsAndClickable() {
        onView(withId(R.id.btn_push))
            .check(matches(isDisplayed()))
            .check(matches(isEnabled()))
    }

    @Test
    fun test_WatcherStatus_Exists() {
        onView(withId(R.id.watcher_status)).check(matches(isDisplayed()))
    }

    // ---- Button click scenarios ----
    @Test
    fun test_SnapshotClick_NotCrashes() {
        onView(withId(R.id.btn_snapshot)).perform(click())
        // If app didn't crash, views should still be displayed
        onView(withId(R.id.status_text)).check(matches(isDisplayed()))
    }

    @Test
    fun test_PushClick_OpensDialog() {
        onView(withId(R.id.btn_push)).perform(click())
        // AlertDialog title should appear
        onView(withText("Push to Remote")).check(matches(isDisplayed()))
    }

    // ---- Data binding / lifecycle ----
    @Test
    fun test_ActivityCanRecreate() {
        activityRule.scenario.recreate()
        onView(withId(R.id.status_text)).check(matches(isDisplayed()))
    }
}
