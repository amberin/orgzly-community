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
import com.orgzly.android.sync.BookSyncStatus
import com.orgzly.android.ui.main.MainActivity
import com.orgzly.android.ui.repos.ReposActivity
import com.orgzly.android.util.MiscUtils
import io.github.atetzner.webdav.server.MiltonWebDAVFileServer
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
class SyncRepoTest(private val repoType: RepoType) : OrgzlyTest() {

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

    // Used by WebdavRepo
    private val webDavServerUrl = "http://localhost:8081/"
    private lateinit var serverRootDir: File
    private lateinit var localServer: MiltonWebDAVFileServer
    private lateinit var tmpFile: File

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Array<RepoType> {
            return arrayOf(
//                GIT,
//                DOCUMENT,
//                DROPBOX,
                WEBDAV,
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
                WEBDAV -> tearDownWebdavRepo()
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

    @Test
    fun testLoadBookWithSpaceInName() {
        setupSyncRepo(repoType)
        val tmpFile = dataRepository.getTempBookFile()
        try {
            MiscUtils.writeStringToFile("...", tmpFile)
            syncRepo.storeBook(tmpFile, "book one.org")
        } finally {
            tmpFile.delete()
        }
        val repoBooks = syncRepo.books
        assertEquals(1, repoBooks.size.toLong())
        assertEquals(repo.url, repoBooks[0].repoUri.toString())
        // Check that the notebook gets the right name based on the repository file's name
        assertEquals("book one", BookName.getInstance(context, repoBooks[0]).name)
        // Check that the remote filename is parsed and stored correctly
        assertEquals("book one.org", BookName.getInstance(context, repoBooks[0]).fileName)
        // Check that the resulting local book gets the right name
        testUtils.sync()
        val books = dataRepository.getBooks()
        assertEquals(1, books.size)
        assertEquals("book one", books[0].book.name)
    }

    @Test
    @Throws(IOException::class)
    fun testExtension() {
        setupSyncRepo(repoType)
        // Add multiple files to repo
        for (fileName in arrayOf("file one.txt", "file two.o", "file three.org")) {
            val tmpFile = File.createTempFile("orgzly-test", null)
            MiscUtils.writeStringToFile("book content", tmpFile)
            syncRepo.storeBook(tmpFile, fileName)
            tmpFile.delete()
        }
        val books = syncRepo.books
        assertEquals(1, books.size.toLong())
        assertEquals("file three", BookName.getInstance(context, books[0]).name)
        assertEquals("file three.org", BookName.getInstance(context, books[0]).fileName)
        assertEquals(repo.id, books[0].repoId)
        assertEquals(repo.url, books[0].repoUri.toString())
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

    @Test
    fun testRenameBookToExistingRepoFileName() {
        setupSyncRepo(repoType)
        testUtils.setupBook("a", "")
        testUtils.sync()

        // Create "unsynced" file in repo
        val tmpFile = File.createTempFile("orgzly-test", null)
        MiscUtils.writeStringToFile("bla bla", tmpFile)
        syncRepo.storeBook(tmpFile, "b.org")
        tmpFile.delete()
        assertEquals(2, syncRepo.books.size) // The remote repo should now contain 2 books

        dataRepository.renameBook(dataRepository.getBookView("a")!!, "b")

        // The remote repo should still contain 2 books - otherwise the existing b.org has been
        // overwritten.
        assertEquals(2, syncRepo.books.size)
        assertTrue(dataRepository.getBook("a")!!.lastAction!!.message.contains("Renaming failed:"))
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

    @Test
    fun testIgnoreRulePreventsLoadingBook() {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 26) // .orgzlyignore not supported below API 26
        val ignoreRules = """
            ignoredbook.org
            ignored-*.org
        """.trimIndent()
        setupSyncRepo(repoType, ignoreRules)
        // Add multiple files to repo
        for (fileName in arrayOf("ignoredbook.org", "ignored-3.org", "notignored.org")) {
            val tmpFile = File.createTempFile("orgzly-test", null)
            MiscUtils.writeStringToFile("book content", tmpFile)
            syncRepo.storeBook(tmpFile, fileName)
            tmpFile.delete()
        }
        testUtils.sync()
        assertEquals(1, syncRepo.books.size)
        assertEquals(1, dataRepository.getBooks().size)
        assertEquals("notignored", dataRepository.getBooks()[0].book.name)
    }

    @Test
    fun testUnIgnoredFilesInRepoAreLoaded() {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 26)
        val ignoreFileContents = """
            *.org
            !notignored.org
        """.trimIndent()
        setupSyncRepo(repoType, ignoreFileContents)
        // Add multiple files to repo
        for (fileName in arrayOf("ignoredbook.org", "ignored-3.org", "notignored.org")) {
            val tmpFile = File.createTempFile("orgzlytest", null)
            MiscUtils.writeStringToFile("book content", tmpFile)
            syncRepo.storeBook(tmpFile, fileName)
            tmpFile.delete()
        }
        testUtils.sync()
        assertEquals(1, syncRepo.books.size)
        assertEquals(1, dataRepository.getBooks().size)
        assertEquals("notignored", dataRepository.getBooks()[0].book.name)
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

    @Test
    fun testStoreBookInSubfolder() {
        setupSyncRepo(repoType)
        testUtils.setupBook("a folder/a book", "")
        testUtils.sync()
        assertEquals(1, syncRepo.books.size)
        val expectedRookUri = when (repoType) {
            GIT -> "/a folder/a book.org"
            DOCUMENT -> repo.url + documentTreeSegment + "a%20folder%2Fa%20book.org"
            else -> { repo.url + "/a%20folder/a%20book.org" }
        }
        assertEquals(expectedRookUri, dataRepository.getBooks()[0].syncedTo!!.uri.toString())
        assertEquals("a folder/a book", dataRepository.getBooks()[0].book.name)
    }

    @Test
    @Throws(IOException::class)
    fun testLoadBookFromSubfolder() {
        setupSyncRepo(repoType)
        val tmpFile = dataRepository.getTempBookFile()
        try {
            MiscUtils.writeStringToFile("...", tmpFile)
            syncRepo.storeBook(tmpFile, "a folder/a book.org")
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
        assertEquals("a folder/a book", books[0].book.name)
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

    @Test
    fun testIgnoreFileInSubfolder() {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 26)
        setupSyncRepo(repoType, "subfolder1/book1.org")
        // Write 2 org files to subfolder in repo
        for (fileName in arrayOf("subfolder1/book1.org", "subfolder1/book2.org")) {
            val tmpFile = File.createTempFile("orgzlytest", null)
            MiscUtils.writeStringToFile("book content", tmpFile)
            syncRepo.storeBook(tmpFile, fileName)
            tmpFile.delete()
        }

        testUtils.sync()

        val books = dataRepository.getBooks()
        assertEquals(1, books.size.toLong())
        assertEquals("subfolder1/book2", books[0].book.name)
    }

    @Test
    fun testUnIgnoreSingleFileInSubfolder() {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 26)
        setupSyncRepo(repoType, "subfolder1/**\n!subfolder1/book2.org")
        // Write 2 org files to subfolder in repo
        for (fileName in arrayOf("subfolder1/book1.org", "subfolder1/book2.org")) {
            val tmpFile = File.createTempFile("orgzlytest", null)
            MiscUtils.writeStringToFile("book content", tmpFile)
            syncRepo.storeBook(tmpFile, fileName)
            tmpFile.delete()
        }

        testUtils.sync()

        val books = dataRepository.getBooks()
        assertEquals(1, books.size.toLong())
        assertEquals("subfolder1/book2", books[0].book.name)
    }

    @Test
    fun testStoreBookAndRetrieveBookProducesSameRookUri() {
        setupSyncRepo(repoType)

        val repoFilePath = "folder one/book one.org"

        // Upload file to repo
        val storedBook: VersionedRook?
        var tmpFile = dataRepository.getTempBookFile()
        try {
            MiscUtils.writeStringToFile("content", tmpFile)
            storedBook = syncRepo.storeBook(tmpFile, repoFilePath)
        } finally {
            tmpFile.delete()
        }

        // Download file from repo
        tmpFile = dataRepository.getTempBookFile()
        val retrievedBook: VersionedRook?
        try {
            retrievedBook = syncRepo.retrieveBook(repoFilePath, tmpFile)
        } finally {
            tmpFile.delete()
        }

        assertEquals(storedBook!!.uri, retrievedBook!!.uri!!)
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
    fun testRenameBookFromRootToSubfolder() {
        setupSyncRepo(repoType)
        testUtils.setupBook("booky", "")
        testUtils.sync()
        dataRepository.renameBook(dataRepository.getBookView("booky")!!, "a/b")
        assertTrue(dataRepository.getBookView("a/b")!!.book.lastAction!!.message.contains("Renamed from "))
        testUtils.sync()
        assertEquals(1, dataRepository.getBooks().size.toLong())
        val bookView = dataRepository.getBookView("a/b")
        assertEquals(BookSyncStatus.NO_CHANGE.toString(), bookView!!.book.syncStatus)
        val expectedRookUri = when (repoType) {
            GIT -> "/a/b.org"
            DOCUMENT -> repo.url + documentTreeSegment + "a%2Fb.org"
            else -> { repo.url + "/a/b.org" }
        }
        assertEquals(
            expectedRookUri,
            bookView.syncedTo!!.uri.toString()
        )
    }

    @Test
    fun testRenameBookFromSubfolderToRoot() {
        setupSyncRepo(repoType)
        testUtils.setupBook("a/b", "")
        testUtils.sync()
        dataRepository.renameBook(dataRepository.getBookView("a/b")!!, "booky")
        assertTrue(dataRepository.getBookView("booky")!!.book.lastAction!!.message.contains("Renamed from "))
        testUtils.sync()
        assertEquals(1, dataRepository.getBooks().size.toLong())
        val bookView = dataRepository.getBookView("booky")
        assertEquals(bookView!!.book.syncStatus, BookSyncStatus.NO_CHANGE.toString())
        val expectedRookUri = when (repoType) {
            GIT -> "/booky.org"
            DOCUMENT -> repo.url + documentTreeSegment + "booky.org"
            else -> { repo.url + "/booky.org" }
        }
        assertEquals(
            expectedRookUri,
            bookView.syncedTo!!.uri.toString()
        )
    }

    @Test
    fun testRenameBookNewSubfolderSameLeafName() {
        setupSyncRepo(repoType)
        testUtils.setupBook("a/b", "")
        testUtils.sync()
        dataRepository.renameBook(dataRepository.getBookView("a/b")!!, "b/b")
        assertTrue(dataRepository.getBookView("b/b")!!.book.lastAction!!.message.contains("Renamed from "))
        testUtils.sync()
        assertEquals(1, dataRepository.getBooks().size.toLong())
        val bookView = dataRepository.getBookView("b/b")
        assertEquals(bookView!!.book.syncStatus, BookSyncStatus.NO_CHANGE.toString())
        val expectedRookUri = when (repoType) {
            GIT -> "/b/b.org"
            DOCUMENT -> repo.url + documentTreeSegment + "b%2Fb.org"
            else -> { repo.url + "/b/b.org" }
        }
        assertEquals(
            expectedRookUri,
            bookView.syncedTo!!.uri.toString()
        )
    }

    @Test
    fun testRenameBookNewSubfolderAndLeafName() {
        setupSyncRepo(repoType)
        testUtils.setupBook("a/b", "")
        testUtils.sync()
        dataRepository.renameBook(dataRepository.getBookView("a/b")!!, "b/c")
        assertTrue(dataRepository.getBookView("b/c")!!.book.lastAction!!.message.contains("Renamed from "))
        testUtils.sync()
        assertEquals(1, dataRepository.getBooks().size.toLong())
        val bookView = dataRepository.getBookView("b/c")
        assertEquals(bookView!!.book.syncStatus, BookSyncStatus.NO_CHANGE.toString())
        val expectedRookUri = when (repoType) {
            GIT -> "/b/c.org"
            DOCUMENT -> repo.url + documentTreeSegment + "b%2Fc.org"
            else -> { repo.url + "/b/c.org" }
        }
        assertEquals(
            expectedRookUri,
            bookView.syncedTo!!.uri.toString()
        )
    }

    @Test
    fun testRenameBookSameSubfolderNewLeafName() {
        setupSyncRepo(repoType)
        testUtils.setupBook("a/b", "")
        testUtils.sync()
        dataRepository.renameBook(dataRepository.getBookView("a/b")!!, "a/c")
        assertTrue(dataRepository.getBookView("a/c")!!.book.lastAction!!.message.contains("Renamed from "))
        testUtils.sync()
        assertEquals(1, dataRepository.getBooks().size.toLong())
        val bookView = dataRepository.getBookView("a/c")
        assertEquals(bookView!!.book.syncStatus, BookSyncStatus.NO_CHANGE.toString())
        val expectedRookUri = when (repoType) {
            GIT -> "/a/c.org"
            DOCUMENT -> repo.url + documentTreeSegment + "a%2Fc.org"
            else -> { repo.url + "/a/c.org" }
        }
        assertEquals(
            expectedRookUri,
            bookView.syncedTo!!.uri.toString()
        )
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

    @Test
    fun testGetBooks_singleOrgFile() {
        // N.B. Expected book name contains space
        val remoteBookFile = File(serverRootDir.absolutePath + "/book one.org")
        MiscUtils.writeStringToFile("...", remoteBookFile)
        val books = syncRepo.books
        assertEquals(1, books.size)
        assertEquals(webDavServerUrl + "book%20one.org", books[0].uri.toString())
        val retrievedBookFile = kotlin.io.path.createTempFile().toFile()
        syncRepo.retrieveBook("book one.org", retrievedBookFile)
        // Assert that the two files are identical
        assertEquals(remoteBookFile.readText(), retrievedBookFile.readText())
        // Assert reported file name
        val rookFileName = BookName.getFileName(syncRepo.uri, books[0].uri)
        assertEquals("book one.org", rookFileName)
    }

    private fun setupSyncRepo(repoType: RepoType, ignoreRules: String? = null) {
        when (repoType) {
            GIT -> setupGitRepo()
            MOCK -> TODO()
            DROPBOX -> setupDropboxRepo()
            DIRECTORY -> TODO()
            DOCUMENT -> setupDocumentRepo()
            WEBDAV -> setupWebdavRepo()
        }
        if (ignoreRules != null) {
            val tmpFile = File.createTempFile("orgzly-test", null)
            MiscUtils.writeStringToFile(ignoreRules, tmpFile)
            syncRepo.storeBook(tmpFile, RepoIgnoreNode.IGNORE_FILE)
            tmpFile.delete()
        }
    }

    private fun setupWebdavRepo() {
        serverRootDir = java.nio.file.Files.createTempDirectory("orgzly-webdav-test-").toFile()
        localServer = MiltonWebDAVFileServer(serverRootDir)
        localServer.userCredentials["user"] = "secret"
        localServer.start()
        val repo = Repo(0, WEBDAV, webDavServerUrl)
        val repoPropsMap = HashMap<String, String>()
        repoPropsMap[WebdavRepo.USERNAME_PREF_KEY] = "user"
        repoPropsMap[WebdavRepo.PASSWORD_PREF_KEY] = "secret"
        val repoWithProps = RepoWithProps(repo, repoPropsMap)
        syncRepo = WebdavRepo.getInstance(repoWithProps)
        assertEquals(webDavServerUrl, repo.url)
        tmpFile = kotlin.io.path.createTempFile().toFile()
    }

    private fun tearDownWebdavRepo() {
        tmpFile.delete()
        if (this::localServer.isInitialized) {
            localServer.stop()
        }
        if (this::serverRootDir.isInitialized) {
            serverRootDir.deleteRecursively()
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
