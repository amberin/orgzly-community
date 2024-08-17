package com.orgzly.android.repos

import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import com.orgzly.R
import com.orgzly.android.BookName
import com.orgzly.android.OrgzlyTest
import com.orgzly.android.db.entity.BookView
import com.orgzly.android.db.entity.Repo
import com.orgzly.android.espresso.util.EspressoUtils
import com.orgzly.android.git.GitFileSynchronizer
import com.orgzly.android.git.GitPreferencesFromRepoPrefs
import com.orgzly.android.prefs.AppPreferences
import com.orgzly.android.prefs.RepoPreferences
import com.orgzly.android.repos.RepoType.*
import com.orgzly.android.ui.main.MainActivity
import com.orgzly.android.ui.repos.ReposActivity
import com.orgzly.android.util.MiscUtils
import org.eclipse.jgit.api.Git
import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.core.AllOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createTempDirectory

@RunWith(value = Parameterized::class)
class SyncRepoIntegTest(private val repoType: RepoType) : OrgzlyTest() {

    private val permanentRepoTestDir = "orgzly-android-tests"
    private var topDirName = RANDOM_UUID
    private lateinit var repo: Repo
    private lateinit var syncRepo: SyncRepo

    // Used by GitRepo
    private lateinit var gitWorkingTree: File
    private lateinit var gitBareRepoPath: Path
    private lateinit var gitFileSynchronizer: GitFileSynchronizer

    // Used by DocumentRepo
    private lateinit var documentTreeSegment: String

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Array<RepoType> {
            return arrayOf(
                GIT,
                DOCUMENT,
                DROPBOX,
            )
        }

        /* For creating a unique directory per test suite instance for tests which interact with
        the cloud (Dropbox), to avoid collisions when they are run simultaneously on
        different devices. */
        val RANDOM_UUID = UUID.randomUUID().toString()
    }

    override fun tearDown() {
        super.tearDown()
        if (this::repo.isInitialized) {
            when (repo.type) {
                GIT -> tearDownGitRepo()
                MOCK -> TODO()
                DROPBOX -> tearDownDropboxRepo()
                DIRECTORY -> TODO()
                DOCUMENT -> tearDownDocumentRepo()
                WEBDAV -> TODO()
            }
        }
    }

    @JvmField
    @Rule
    var exceptionRule: ExpectedException = ExpectedException.none()

    // TODO: Move to DataRepository tests
    @Test
    @Throws(IOException::class)
    fun testLoadBook() {
        setupSyncRepo(repoType)
        val tmpFile = dataRepository.getTempBookFile()
        try {
            MiscUtils.writeStringToFile("...", tmpFile)
            syncRepo.storeBook(tmpFile, "booky.org")
        } finally {
            tmpFile.delete()
        }
        val repoBooks = syncRepo.books
        assertEquals(1, repoBooks.size.toLong())
        assertEquals(repo.url, repoBooks[0].repoUri.toString())
        testUtils.sync()
        val books = dataRepository.getBooks()
        assertEquals(1, books.size)
        // Check that the resulting notebook gets the right name
        assertEquals("booky", books[0].book.name)
    }

    // TODO: Move to DataRepository tests
    @Test
    @Throws(IOException::class)
    fun testForceLoadBook() {
        setupSyncRepo(repoType)
        val bookView = testUtils.setupBook("booky", "content")
        testUtils.sync()
        var books = dataRepository.getBooks()
        assertEquals(1, books.size)
        assertEquals("booky", books[0].book.name)
        dataRepository.forceLoadBook(bookView.book.id)
        books = dataRepository.getBooks()
        assertEquals(1, books.size)
        // Check that the name has not changed
        assertEquals("booky", books[0].book.name)
    }

    // TODO: Move to DataRepository tests
    @Test
    fun testSyncNewBookWithoutLinkAndOneRepo() {
        setupSyncRepo(repoType)
        testUtils.setupBook("Book 1", "content")
        testUtils.sync()
        val bookView = dataRepository.getBooks()[0]
        assertEquals(repo.url, bookView.linkRepo?.url)
        assertEquals(1, syncRepo.books.size)
        assertEquals(bookView.syncedTo.toString(), syncRepo.books[0].toString())
        assertEquals(
            context.getString(R.string.sync_status_saved, repo.url),
            bookView.book.lastAction!!.message
        )
    }

    // TODO: Move to DataRepository tests
    @Test
    fun testRenameBook() {
        setupSyncRepo(repoType)
        testUtils.setupBook("oldname", "")
        testUtils.sync()
        var bookView = dataRepository.getBookView("oldname")
        assertEquals(repo.url, bookView!!.linkRepo!!.url)
        assertEquals(repo.url, bookView.syncedTo!!.repoUri.toString())
        assertTrue(bookView.syncedTo!!.uri.toString().contains("oldname.org"))

        dataRepository.renameBook(bookView, "newname")

        assertEquals(1, syncRepo.books.size.toLong())
        assertEquals(
            "newname.org",
            BookName.getInstance(context, syncRepo.books[0]).fileName
        )
        bookView = dataRepository.getBookView("newname")
        assertEquals(repo.url, bookView!!.linkRepo!!.url)
        assertEquals(repo.url, bookView.syncedTo!!.repoUri.toString())
        assertTrue(bookView.syncedTo!!.uri.toString().contains("newname.org"))
    }

    // TODO: Move to DataRepository tests
    @Test
    fun testRenameBookToNameWithSpace() {
        setupSyncRepo(repoType)
        testUtils.setupBook("oldname", "")
        testUtils.sync()
        var bookView = dataRepository.getBookView("oldname")
        assertEquals(repo.url, bookView!!.linkRepo!!.url)
        assertEquals(repo.url, bookView.syncedTo!!.repoUri.toString())
        assertTrue(bookView.syncedTo!!.uri.toString().contains("oldname.org"))

        dataRepository.renameBook(bookView, "new name")

        assertEquals(1, syncRepo.books.size.toLong())
        assertEquals(
            "new name.org",
            BookName.getInstance(context, syncRepo.books[0]).fileName
        )
        bookView = dataRepository.getBookView("new name")
        assertEquals(repo.url, bookView!!.linkRepo!!.url)
        assertEquals(repo.url, bookView.syncedTo!!.repoUri.toString())
        val expectedRookUriName = when (repoType) {
            GIT -> "new name.org"
            else -> { "new%20name.org" }
        }
        assertTrue(bookView.syncedTo!!.uri.toString().endsWith(expectedRookUriName))
    }

    // TODO: Move to DataRepository tests (check happens there)
    @Test
    fun testRenameBookToExistingBookName() {
        setupSyncRepo(repoType)
        testUtils.setupBook("a", "")
        testUtils.setupBook("b", "")
        assertEquals(2, dataRepository.getBooks().size)
        dataRepository.renameBook(dataRepository.getBookView("a")!!, "b")
        assertTrue(dataRepository.getBook("a")!!.lastAction!!.message.contains("Renaming failed: Notebook b already exists"))
    }

    // TODO: Move to DataRepository tests (check happens there)
    @Test
    fun testIgnoreRulePreventsRenamingBook() {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 26)
        setupSyncRepo(repoType,"bad name*")

        // Create book and sync it
        testUtils.setupBook("good name", "")
        testUtils.sync()
        var bookView: BookView? = dataRepository.getBookView("good name")
        dataRepository.renameBook(bookView!!, "bad name")
        bookView = dataRepository.getBooks()[0]
        assertTrue(
            bookView.book.lastAction.toString().contains("matches a rule in .orgzlyignore")
        )
    }

    // TODO: Move to DataRepository tests (check happens there)
    @Test
    @Throws(java.lang.Exception::class)
    fun testIgnoreRulePreventsLinkingBook() {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 26)
        setupSyncRepo(repoType, "*.org")
        testUtils.setupBook("booky", "")
        exceptionRule.expect(IOException::class.java)
        exceptionRule.expectMessage("matches a rule in .orgzlyignore")
        testUtils.syncOrThrow()
    }

    /**
     * Ensures that file names and book names are not parsed/created differently during
     * force-loading.
     *
     * TODO: Move - tests code in DataRepository, not SyncRepo
     */
    @Test
    @Throws(IOException::class)
    fun testForceLoadBookInSubfolder() {
        setupSyncRepo(repoType)
        val bookView = testUtils.setupBook("a folder/a book", "content")
        testUtils.sync()
        var books = dataRepository.getBooks()
        assertEquals(1, books.size)
        assertEquals("a folder/a book", books[0].book.name)
        dataRepository.forceLoadBook(bookView.book.id)
        books = dataRepository.getBooks()
        assertEquals(1, books.size)
        // Check that the name has not changed
        assertEquals("a folder/a book", books[0].book.name)
    }

    // TODO: Move - does not test SyncRepo code
    @Test
    fun testUpdateBookInSubfolder() {
        setupSyncRepo(repoType)
        // Create org file in subfolder
        val tmpFile = dataRepository.getTempBookFile()
        try {
            MiscUtils.writeStringToFile("* DONE Heading 1", tmpFile)
            syncRepo.storeBook(tmpFile, "folder one/book one.org")
        } finally {
            tmpFile.delete()
        }

        testUtils.sync()
        assertEquals(1, dataRepository.getBooks().size.toLong())

        ActivityScenario.launch(MainActivity::class.java).use {
            // Modify book
            EspressoUtils.onBook(0).perform(ViewActions.click())
            EspressoUtils.onNoteInBook(1).perform(ViewActions.longClick())
            Espresso.onView(ViewMatchers.withId(R.id.toggle_state)).perform(ViewActions.click())
            Espresso.pressBack()
            Espresso.pressBack()
            EspressoUtils.sync()
            EspressoUtils.onBook(0, R.id.item_book_last_action).check(matches(ViewMatchers.withText(containsString("Saved to "))))
            // Delete notebook from Orgzly and reload it to verify that our change was successfully written
            EspressoUtils.onBook(0).perform(ViewActions.longClick())
            EspressoUtils.contextualToolbarOverflowMenu().perform(ViewActions.click())
            Espresso.onView(ViewMatchers.withText(R.string.delete)).perform(ViewActions.click())
            Espresso.onView(ViewMatchers.withText(R.string.delete)).perform(ViewActions.click())
        }

        testUtils.sync()
        assertEquals(1, dataRepository.getBooks().size.toLong())
        testUtils.assertBook("folder one/book one", "* TODO Heading 1\n")
    }

    @Test
    @Throws(FileNotFoundException::class)
    fun testSyncWithDirectoryWithSpaceInName() {
        Assume.assumeTrue(repoType != GIT) // Git repo URLs will never contain a space
        topDirName = "space separated"
        if (repoType == DOCUMENT) {
            setupDocumentRepo(topDirName)
        } else {
            setupSyncRepo(repoType)
        }
        val tmpFile = dataRepository.getTempBookFile()
        try {
            MiscUtils.writeStringToFile("content", tmpFile)
            syncRepo.storeBook(tmpFile, "notebook.org")
        } finally {
            tmpFile.delete()
        }
        testUtils.sync()
        assertEquals(1, dataRepository.getBooks().size.toLong())
        if (repoType == DOCUMENT) {
            assertTrue(syncRepo.uri.toString().contains("space%20separated"))
        } else {
            assertTrue(syncRepo.uri.toString().contains("space separated"))
        }
    }

    private fun setupSyncRepo(repoType: RepoType, ignoreRules: String? = null) {
        when (repoType) {
            GIT -> setupGitRepo()
            MOCK -> TODO()
            DROPBOX -> setupDropboxRepo()
            DIRECTORY -> TODO()
            DOCUMENT -> setupDocumentRepo()
            WEBDAV -> TODO()
        }
        if (ignoreRules != null) {
            val tmpFile = File.createTempFile("orgzly-test", null)
            MiscUtils.writeStringToFile(ignoreRules, tmpFile)
            syncRepo.storeBook(tmpFile, RepoIgnoreNode.IGNORE_FILE)
            tmpFile.delete()
        }
    }

    private fun setupDropboxRepo() {
        testUtils.dropboxTestPreflight()
        repo = testUtils.setupRepo(DROPBOX, "dropbox:/$permanentRepoTestDir/$topDirName")
        syncRepo = testUtils.repoInstance(DROPBOX, repo.url, repo.id)
    }

    private fun tearDownDropboxRepo() {
        val dropboxRepo = syncRepo as DropboxRepo
        try {
            dropboxRepo.deleteDirectory(syncRepo.uri)
        } catch (_: IOException) {}
    }

    private fun setupDocumentRepo(extraDir: String? = null) {
        documentTreeSegment = if (Build.VERSION.SDK_INT < 30) {
            "/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2F$permanentRepoTestDir%2F"
        } else {
            "/document/primary%3A$permanentRepoTestDir%2F"
        }
        var treeDocumentFileUrl = if (Build.VERSION.SDK_INT < 30) {
            "content://com.android.providers.downloads.documents/tree/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2F$permanentRepoTestDir"
        } else {
            "content://com.android.externalstorage.documents/tree/primary%3A$permanentRepoTestDir"
        }
        if (extraDir != null) {
            treeDocumentFileUrl = "$treeDocumentFileUrl%2F" + Uri.encode(extraDir)
        }
        val repoDirDocumentFile = DocumentFile.fromTreeUri(context, treeDocumentFileUrl.toUri())
        repo = if (repoDirDocumentFile?.exists() == false) {
            if (extraDir != null) {
                setupDocumentRepoInUi(extraDir)
            } else {
                setupDocumentRepoInUi(permanentRepoTestDir)
            }
            dataRepository.getRepos()[0]
        } else {
            testUtils.setupRepo(DOCUMENT, treeDocumentFileUrl)
        }
        syncRepo = testUtils.repoInstance(DOCUMENT, repo.url, repo.id)
        assertEquals(treeDocumentFileUrl, repo.url)
    }

    /**
     * Note that this solution only works the first time the tests are run on any given virtual
     * device. On the second run, the file picker will start in a different folder, resulting in
     * a different repo URL, making some tests fail. If you are running locally, you must work
     * around this by wiping the device's data between test suite runs.
     */
    private fun setupDocumentRepoInUi(repoDirName: String) {
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
                mDevice.findObject(UiSelector().text("Folder name")).text = repoDirName
                mDevice.findObject(UiSelector().text("OK")).click()
                mDevice.findObject(UiSelector().textContains("ALLOW ACCESS TO")).click()
                mDevice.findObject(UiSelector().text("ALLOW")).click()
            } else {
                mDevice.findObject(UiSelector().description("New folder")).click()
                SystemClock.sleep(500)
                mDevice.findObject(UiSelector().text("Folder name")).text = repoDirName
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

    private fun tearDownDocumentRepo() {
        val repoDirectory = DocumentFile.fromTreeUri(context, repo.url.toUri())
        for (file in repoDirectory!!.listFiles()) {
            file.delete()
        }
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
        val git = GitRepo.ensureRepositoryExists(gitPreferences, true, null)
        gitFileSynchronizer = GitFileSynchronizer(git, gitPreferences)
        syncRepo = dataRepository.getRepoInstance(repo.id, GIT, repo.url)
    }

    private fun tearDownGitRepo() {
        testUtils.deleteRepo(repo.url)
        gitWorkingTree.deleteRecursively()
        gitBareRepoPath.toFile()!!.deleteRecursively()
    }
}
