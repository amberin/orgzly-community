package com.orgzly.android.repos

import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import com.orgzly.R
import com.orgzly.android.OrgzlyTest
import com.orgzly.android.db.entity.Repo
import com.orgzly.android.espresso.util.EspressoUtils
import com.orgzly.android.repos.SyncRepoTest.Companion.repoDirName
import com.orgzly.android.repos.SyncRepoTest.Companion.treeDocumentFileExtraSegment
import com.orgzly.android.ui.repos.ReposActivity
import com.orgzly.android.util.MiscUtils
import org.hamcrest.core.AllOf
import org.junit.After
import org.junit.Assert
import org.junit.Before
import kotlin.io.path.Path

class DocumentRepoTest : SyncRepoTest, OrgzlyTest() {

    private lateinit var repo: Repo
    private lateinit var repoDirectory: DocumentFile
    private lateinit var mSyncRepo: SyncRepo
    private var treeDocumentFileUrl = if (Build.VERSION.SDK_INT < 30) {
        "content://com.android.providers.downloads.documents/tree/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2F$repoDirName"
    } else {
        "content://com.android.externalstorage.documents/tree/primary%3A$repoDirName"
    }

    @Before
    override fun setUp() {
        super.setUp()
        setupDocumentRepo()
    }

    @After
    override fun tearDown() {
        super.tearDown()
        for (file in repoDirectory.listFiles()) {
            file.delete()
        }
    }

    override var syncRepo: SyncRepo
        get() = mSyncRepo
        set(value) {}
    override val repoManipulationPoint: Any
        get() = repoDirectory

    override fun writeFileToRepo(content: String, repoRelativePath: String): String {
        val targetPath = Path(repoRelativePath)
        var expectedRookUri = mSyncRepo.uri.toString() + treeDocumentFileExtraSegment + Uri.encode(repoRelativePath)
        var targetDir = repoDirectory
        if (repoRelativePath.contains("/")) {
            targetDir = targetDir.createDirectory(targetPath.parent.toString())!!
            expectedRookUri = mSyncRepo.uri.toString() + treeDocumentFileExtraSegment + Uri.encode(repoRelativePath)
        }
        MiscUtils.writeStringToDocumentFile(content, targetPath.fileName.toString(), targetDir.uri)
        return expectedRookUri
    }

    private fun setupDocumentRepo() {
        repoDirectory = DocumentFile.fromTreeUri(context, treeDocumentFileUrl.toUri())!!
        repo = if (!repoDirectory.exists()) {
            setupDocumentRepoInUi()
            dataRepository.getRepos()[0]
        } else {
            testUtils.setupRepo(RepoType.DOCUMENT, treeDocumentFileUrl)
        }
        mSyncRepo = testUtils.repoInstance(RepoType.DOCUMENT, repo.url, repo.id)
        Assert.assertEquals(treeDocumentFileUrl, repo.url)
    }

    /**
     * Note that this solution only works the first time the tests are run on any given virtual
     * device. On the second run, the file picker will start in a different folder, resulting in
     * a different repo URL, making some tests fail. If you are running locally, you must work
     * around this by wiping the device's data between test suite runs.
     */
    private fun setupDocumentRepoInUi() {
        ActivityScenario.launch(ReposActivity::class.java).use {
            Espresso.onView(ViewMatchers.withId(R.id.activity_repos_directory))
                .perform(ViewActions.click())
            Espresso.onView(ViewMatchers.withId(R.id.activity_repo_directory_browse_button))
                .perform(ViewActions.click())
            SystemClock.sleep(500)
            // In Android file browser (Espresso cannot be used):
            val mDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            if (Build.VERSION.SDK_INT < 30) {
                // Older system file picker UI
                mDevice.findObject(UiSelector().description("More options")).click()
                SystemClock.sleep(300)
                mDevice.findObject(UiSelector().text("New folder")).click()
                SystemClock.sleep(500)
                mDevice.findObject(UiSelector().text("Folder name")).setText(repoDirName)
                mDevice.findObject(UiSelector().text("OK")).click()
                mDevice.findObject(UiSelector().textContains("ALLOW ACCESS TO")).click()
                mDevice.findObject(UiSelector().text("ALLOW")).click()
            } else {
                mDevice.findObject(UiSelector().description("New folder")).click()
                SystemClock.sleep(500)
                mDevice.findObject(UiSelector().text("Folder name")).setText(repoDirName)
                mDevice.findObject(UiSelector().text("OK")).click()
                mDevice.findObject(UiSelector().text("USE THIS FOLDER")).click()
                mDevice.findObject(UiSelector().text("ALLOW")).click()
            }
            // Back in Orgzly:
            SystemClock.sleep(500)
            Espresso.onView(ViewMatchers.isRoot()).perform(EspressoUtils.waitId(R.id.fab, 5000))
            Espresso.onView(AllOf.allOf(ViewMatchers.withId(R.id.fab), ViewMatchers.isDisplayed()))
                .perform(ViewActions.click())
        }
    }
}
