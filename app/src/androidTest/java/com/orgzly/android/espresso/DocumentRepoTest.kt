package com.orgzly.android.espresso

import android.os.Build
import android.os.SystemClock
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObjectNotFoundException
import androidx.test.uiautomator.UiSelector
import com.orgzly.R
import com.orgzly.android.OrgzlyTest
import com.orgzly.android.espresso.util.EspressoUtils.waitId
import com.orgzly.android.repos.DocumentRepo
import com.orgzly.android.repos.RepoType
import com.orgzly.android.ui.repos.ReposActivity
import com.orgzly.android.util.MiscUtils
import org.hamcrest.core.AllOf.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException


class DocumentRepoTest : OrgzlyTest() {

    private var repoDirName = "orgzly-android-tests"
    private lateinit var syncRepo: DocumentRepo

    @Before
    @Throws(Exception::class)
    override fun setUp() {
        super.setUp()
    }

    @After
    @Throws(Exception::class)
    override fun tearDown() {
        super.tearDown()
        if (this::syncRepo.isInitialized) {
            DocumentFile.fromTreeUri(context, syncRepo.uri)!!.delete()
        }
    }

    @Test
    @Throws(FileNotFoundException::class)
    fun testSyncWithDirectoryContainingPercent() {
        repoDirName = "space separated"
        setupDocumentRepo()
        writeStringToRepoFile("Notebook content 1", "notebook.org")
        testUtils.sync()
        assertEquals(1, dataRepository.getBooks().size.toLong())
        assertTrue(syncRepo.uri.toString().contains("space%20separated"))
    }

    private fun writeStringToRepoFile(content: String, fileName: String) {
        val tmpFile = File.createTempFile("abc", null)
        MiscUtils.writeStringToFile(content, tmpFile)
        syncRepo.storeBook(tmpFile, fileName)
        tmpFile.delete()
    }

    /**
     * An activity is required when creating this type of repo, because of the way Android handles
     * access permissions to content:// URLs.
     * @throws UiObjectNotFoundException
     */
    @Throws(UiObjectNotFoundException::class)
    private fun setupDocumentRepo() {
        setupDocumentRepoInUi(repoDirName)
        val repo = dataRepository.getRepos()[0]
        syncRepo = testUtils.repoInstance(RepoType.DOCUMENT, repo.url, repo.id) as DocumentRepo
    }

    companion object {
        fun setupDocumentRepoInUi(repoDirName: String) {
            ActivityScenario.launch(ReposActivity::class.java).use {
                onView(withId(R.id.activity_repos_directory)).perform(click())
                onView(withId(R.id.activity_repo_directory_browse_button))
                    .perform(click())
                SystemClock.sleep(200)
                // In Android file browser (Espresso cannot be used):
                val mDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                if (Build.VERSION.SDK_INT < 33) {
                    // Older system file picker UI
                    mDevice.findObject(UiSelector().description("More options")).click()
                    mDevice.findObject(UiSelector().text("New folder")).click()
                    SystemClock.sleep(100)
                    mDevice.findObject(UiSelector().text("Folder name")).text = repoDirName
                    mDevice.findObject(UiSelector().text("OK")).click()
                    mDevice.findObject(UiSelector().textContains("ALLOW ACCESS TO")).click()
                    mDevice.findObject(UiSelector().text("ALLOW")).click()
                } else {
                    mDevice.findObject(UiSelector().text("CREATE NEW FOLDER")).click()
                    SystemClock.sleep(100)
                    mDevice.findObject(UiSelector().text("Folder name")).text = repoDirName
                    mDevice.findObject(UiSelector().text("OK")).click()
                    mDevice.findObject(UiSelector().text("USE THIS FOLDER")).click()
                    mDevice.findObject(UiSelector().text("ALLOW")).click()
                }
                // Back in Orgzly:
                SystemClock.sleep(200)
                onView(isRoot()).perform(waitId(R.id.fab, 5000))
                onView(allOf(withId(R.id.fab), isDisplayed())).perform(click())
            }
        }
    }
}