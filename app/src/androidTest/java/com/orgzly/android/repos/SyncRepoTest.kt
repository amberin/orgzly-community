package com.orgzly.android.repos

import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import com.orgzly.BuildConfig
import com.orgzly.R
import com.orgzly.android.OrgzlyTest
import com.orgzly.android.db.entity.Repo
import com.orgzly.android.espresso.util.EspressoUtils
import com.orgzly.android.git.GitPreferencesFromRepoPrefs
import com.orgzly.android.prefs.AppPreferences
import com.orgzly.android.prefs.RepoPreferences
import com.orgzly.android.repos.RepoType.*
import com.orgzly.android.ui.repos.ReposActivity
import org.eclipse.jgit.api.Git
import org.hamcrest.core.AllOf
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createTempDirectory

@RunWith(value = Parameterized::class)
class SyncRepoTest(private val param: Parameter) : OrgzlyTest() {

    private val testDirectoryName = "orgzly-android-tests"
    private lateinit var repo: Repo
    private lateinit var syncRepo: SyncRepo
    // Used by GitRepo
    private lateinit var gitWorkingTree: File
    private lateinit var gitBareRepoPath: Path
    // used by ContentRepo
    private lateinit var documentTreeSegment: String
    private lateinit var treeDocumentFileUrl: String

    data class Parameter(val repoType: RepoType)

    companion object {
        @JvmStatic
        @Parameterized.Parameters
        fun data(): Collection<Parameter> {
            return listOf(
                Parameter(repoType = GIT),
                Parameter(repoType = WEBDAV),
                Parameter(repoType = DOCUMENT),
            )
        }
    }

    override fun tearDown() {
        super.tearDown()
        when (repo.type) {
            GIT -> tearDownGitRepo()
            MOCK -> TODO()
            DROPBOX -> TODO()
            DIRECTORY -> TODO()
            DOCUMENT -> tearDownContentRepo()
            WEBDAV -> tearDownWebdavRepo()
        }
    }

    @JvmField
    @Rule
    var exceptionRule: ExpectedException = ExpectedException.none()

    @Test
    fun testSyncNewBookWithoutLinkAndOneRepo() {
        setupSyncRepo(param.repoType)
        testUtils.setupBook("book 1", "content")
        testUtils.sync()
        val bookView = dataRepository.getBooks()[0]
        Assert.assertEquals(repo.url, bookView.linkRepo?.url)
        Assert.assertEquals(1, syncRepo.books.size)
        Assert.assertEquals(bookView.syncedTo.toString(), syncRepo.books[0].toString())
        Assert.assertEquals(
            context.getString(R.string.sync_status_saved, repo.url),
            bookView.book.lastAction!!.message
        )
        val expectedUriString = when (param.repoType) {
            GIT -> "/book 1.org"
            MOCK -> TODO()
            DROPBOX -> TODO()
            DIRECTORY -> TODO()
            DOCUMENT -> "content://com.android.externalstorage.documents/tree/primary%3A$testDirectoryName/document/primary%3A$testDirectoryName%2Fbook%201.org"
            WEBDAV -> "https://use10.thegood.cloud/remote.php/dav/files/orgzlyrevived%40gmail.com/$testDirectoryName/book 1.org"
        }
        Assert.assertEquals(expectedUriString, bookView.syncedTo!!.uri.toString())
    }

    private fun setupSyncRepo(repoType: RepoType) {
        when (repoType) {
            GIT -> setupGitRepo()
            MOCK -> TODO()
            DROPBOX -> TODO()
            DIRECTORY -> TODO()
            DOCUMENT -> setupContentRepo()
            WEBDAV -> setupWebdavRepo()
        }
    }

    private fun setupContentRepo() {
        ActivityScenario.launch(ReposActivity::class.java).use {
            Espresso.onView(ViewMatchers.withId(R.id.activity_repos_directory))
                .perform(ViewActions.click())
            Espresso.onView(ViewMatchers.withId(R.id.activity_repo_directory_browse_button))
                .perform(ViewActions.click())
            // In Android file browser (Espresso cannot be used):
            val mDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            mDevice.findObject(UiSelector().text("CREATE NEW FOLDER")).click()
            mDevice.findObject(UiSelector().text("Folder name")).text = testDirectoryName
            mDevice.findObject(UiSelector().text("OK")).click()
            mDevice.findObject(UiSelector().text("USE THIS FOLDER")).click()
            mDevice.findObject(UiSelector().text("ALLOW")).click()
            // Back in Orgzly:
            Espresso.onView(ViewMatchers.isRoot()).perform(EspressoUtils.waitId(R.id.fab, 5000))
            Espresso.onView(AllOf.allOf(ViewMatchers.withId(R.id.fab), ViewMatchers.isDisplayed()))
                .perform(ViewActions.click())
        }
        repo = dataRepository.getRepos()[0]
        syncRepo = testUtils.repoInstance(RepoType.DOCUMENT, repo.url, repo.id) as ContentRepo
        val encodedRepoDirName = Uri.encode(testDirectoryName)
        documentTreeSegment = "/document/primary%3A$encodedRepoDirName%2F"
        treeDocumentFileUrl = "content://com.android.externalstorage.documents/tree/primary%3A$encodedRepoDirName"
        Assert.assertEquals(treeDocumentFileUrl, repo.url)
    }

    private fun tearDownContentRepo() {
        DocumentFile.fromTreeUri(context, treeDocumentFileUrl.toUri())!!.delete()
    }

    private fun setupWebdavRepo() {
        testUtils.webdavTestPreflight()
        val repoProps: MutableMap<String, String> = mutableMapOf(
            WebdavRepo.USERNAME_PREF_KEY to BuildConfig.WEBDAV_USERNAME,
            WebdavRepo.PASSWORD_PREF_KEY to BuildConfig.WEBDAV_PASSWORD)
        repo = testUtils.setupRepo(WEBDAV, BuildConfig.WEBDAV_REPO_URL + "/" + testDirectoryName, repoProps)
        syncRepo = dataRepository.getRepoInstance(repo.id, WEBDAV, repo.url) as WebdavRepo
    }

    private fun tearDownWebdavRepo() {
        syncRepo.delete(repo.url.toUri())
    }

    private fun setupGitRepo() {
        gitBareRepoPath = createTempDirectory()
        Git.init().setBare(true).setDirectory(gitBareRepoPath.toFile()).call()
        AppPreferences.gitIsEnabled(context, true)
        repo = testUtils.setupRepo(GIT, gitBareRepoPath.toFile().toUri().toString())
        val repoPreferences = RepoPreferences(context, repo.id, repo.url.toUri())
        val gitPreferences = GitPreferencesFromRepoPrefs(repoPreferences)
        gitWorkingTree = File(gitPreferences.repositoryFilepath())
        gitWorkingTree.mkdirs()
        GitRepo.ensureRepositoryExists(gitPreferences, true, null)
        syncRepo = dataRepository.getRepoInstance(repo.id, GIT, repo.url) as GitRepo
    }

    private fun tearDownGitRepo() {
        testUtils.deleteRepo(repo.url)
        gitWorkingTree.deleteRecursively()
        gitBareRepoPath.toFile()!!.deleteRecursively()
    }
}