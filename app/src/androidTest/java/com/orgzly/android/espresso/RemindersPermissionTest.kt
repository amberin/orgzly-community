package com.orgzly.android.espresso

import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import com.google.android.material.snackbar.Snackbar
import com.orgzly.R
import com.orgzly.android.OrgzlyTest
import com.orgzly.android.espresso.util.EspressoUtils.denyAlarmsAndRemindersSpecialPermission
import com.orgzly.android.espresso.util.EspressoUtils.grantAlarmsAndRemindersPermission
import com.orgzly.android.espresso.util.EspressoUtils.onBook
import com.orgzly.android.espresso.util.EspressoUtils.onNoteInBook
import com.orgzly.android.espresso.util.EspressoUtils.onSnackbar
import com.orgzly.android.espresso.util.EspressoUtils.scroll
import com.orgzly.android.ui.main.MainActivity
import org.hamcrest.core.AllOf.allOf
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class RemindersPermissionTest : OrgzlyTest() {

    private lateinit var scenario: ActivityScenario<MainActivity>

    // Ensure we have permission to post notifications
    @get:Rule
    val grantPermissionRule: GrantPermissionRule = if (Build.VERSION.SDK_INT >= 33) {
        GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    } else {
        GrantPermissionRule.grant()
    }

    @Before
    override fun setUp() {
        super.setUp()

        testUtils.setupBook(
            "book-name",
            """
                    * Note #1.
            """.trimIndent()
        )

        scenario = ActivityScenario.launch(MainActivity::class.java)

        onBook(0).perform(click())
    }

    @After
    override fun tearDown() {
        super.tearDown()
        scenario.close()
    }

    @Test
    fun testScheduleTodoReminderWithoutExactAlarmPermission() {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 31)
        denyAlarmsAndRemindersSpecialPermission()
        onNoteInBook(1).perform(click())
        onView(withId(R.id.scheduled_button)).check(matches(withText("")))
        onView(withId(R.id.scheduled_button)).perform(click())
        onView(withId(R.id.time_used_checkbox)).perform(scroll(), click())
        onView(withText(R.string.set)).perform(click())
        // Espresso can't see the settings screen - let's use UIAutomator
        val mDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        mDevice.pressBack()
        onView(withId(R.id.done)).perform(click())
        onSnackbar().check(matches(withText(R.string.permissions_rationale_for_schedule_exact_alarms)))
    }

    @Test
    fun testScheduleTodoReminderWithExactAlarmPermission() {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 31)
        grantAlarmsAndRemindersPermission()
        onNoteInBook(1).perform(click())
        onView(withId(R.id.scheduled_button)).check(matches(withText("")))
        onView(withId(R.id.scheduled_button)).perform(click())
        onView(withId(R.id.time_used_checkbox)).perform(scroll(), click())
        onView(withText(R.string.set)).perform(click())
        onView(withId(R.id.done)).perform(click())
        onView(
            allOf(
                isAssignableFrom(Snackbar.SnackbarLayout::class.java)
            )
        ).check(doesNotExist())
    }
}