package com.orgzly.android.repos

import android.net.Uri
import android.os.Build
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.matcher.ViewMatchers
import com.orgzly.BuildConfig
import com.orgzly.R
import com.orgzly.android.BookName
import com.orgzly.android.OrgzlyTest
import com.orgzly.android.db.entity.BookView
import com.orgzly.android.db.entity.Repo
import com.orgzly.android.espresso.ContentRepoTest
import com.orgzly.android.espresso.util.EspressoUtils
import com.orgzly.android.git.GitFileSynchronizer
import com.orgzly.android.git.GitPreferencesFromRepoPrefs
import com.orgzly.android.prefs.AppPreferences
import com.orgzly.android.prefs.RepoPreferences
import com.orgzly.android.repos.RepoType.DIRECTORY
import com.orgzly.android.repos.RepoType.DOCUMENT
import com.orgzly.android.repos.RepoType.DROPBOX
import com.orgzly.android.repos.RepoType.GIT
import com.orgzly.android.repos.RepoType.MOCK
import com.orgzly.android.repos.RepoType.WEBDAV
import com.orgzly.android.ui.main.MainActivity
import com.orgzly.android.util.MiscUtils
import com.thegrizzlylabs.sardineandroid.impl.SardineException
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createTempDirectory

@RunWith(value = Parameterized::class)
class SyncRepoTest(private val param: Parameter) : OrgzlyTest() {

    private val repoDirectoryName = "orgzly-android-tests"
    private lateinit var repo: Repo
    private lateinit var syncRepo: SyncRepo
    // Used by GitRepo
    private lateinit var gitWorkingTree: File
    private lateinit var gitBareRepoPath: Path
    private lateinit var gitFileSynchronizer: GitFileSynchronizer
    // used by ContentRepo
    private lateinit var documentTreeSegment: String

    data class Parameter(val repoType: RepoType)

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Parameter> {
            return listOf(
                Parameter(repoType = DOCUMENT),
                Parameter(repoType = GIT),
                Parameter(repoType = DROPBOX),
                Parameter(repoType = WEBDAV),
            )
        }
    }

    override fun tearDown() {
        super.tearDown()
        if (this::repo.isInitialized) {
            when (repo.type) {
                GIT -> tearDownGitRepo()
                MOCK -> TODO()
                DROPBOX -> tearDownDropboxRepo()
                DIRECTORY -> TODO()
                DOCUMENT -> tearDownContentRepo()
                WEBDAV -> tearDownWebdavRepo()
            }
        }
    }

    @JvmField
    @Rule
    var exceptionRule: ExpectedException = ExpectedException.none()

    @Test
    @Throws(IOException::class)
    fun testLoadBook() {
        setupSyncRepo(param.repoType, null)
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

    @Test
    @Throws(IOException::class)
    fun testForceLoadBook() {
        setupSyncRepo(param.repoType, null)
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
        setupSyncRepo(param.repoType, null)
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
        setupSyncRepo(param.repoType, null)
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

    @Test
    fun testSyncNewBookWithoutLinkAndOneRepo() {
        setupSyncRepo(param.repoType, null)
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
        val expectedUriString = when (param.repoType) {
            GIT -> "/Book 1.org"
            MOCK -> TODO()
            DROPBOX -> "dropbox:/orgzly-android-tests/Book%201.org"
            DIRECTORY -> TODO()
            DOCUMENT -> repo.url + documentTreeSegment + "Book%201.org"
            WEBDAV -> "https://use10.thegood.cloud/remote.php/dav/files/orgzlyrevived@gmail.com/$repoDirectoryName/Book%201.org"
        }
        assertEquals(expectedUriString, bookView.syncedTo!!.uri.toString())
    }

    @Test
    fun testRenameBook() {
        setupSyncRepo(param.repoType, null)
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

    @Test
    fun testRenameBookToNameWithSpace() {
        setupSyncRepo(param.repoType, null)
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
        val expectedRookUriName = when (param.repoType) {
            GIT -> "new name.org"
            else -> { "new%20name.org" }
        }
        assertTrue(bookView.syncedTo!!.uri.toString().endsWith(expectedRookUriName))
    }

    @Test
    fun testRenameBookToExistingRepoFileName() {
        setupSyncRepo(param.repoType, null)
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

    @Test
    fun testRenameBookToExistingBookName() {
        setupSyncRepo(param.repoType, null)
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
        setupSyncRepo(param.repoType, ignoreRules)
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
        setupSyncRepo(param.repoType, ignoreFileContents)
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

    @Test
    fun testIgnoreRulePreventsRenamingBook() {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 26)
        setupSyncRepo(param.repoType,"bad name*")

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

    @Test
    @Throws(java.lang.Exception::class)
    fun testIgnoreRulePreventsLinkingBook() {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 26)
        setupSyncRepo(param.repoType, "*.org")
        testUtils.setupBook("booky", "")
        exceptionRule.expect(IOException::class.java)
        exceptionRule.expectMessage("matches a rule in .orgzlyignore")
        testUtils.syncOrThrow()
    }

    @Test
    // @Ignore("Not yet implemented for all repo types")
    fun testStoreBookInSubfolder() {
        setupSyncRepo(param.repoType, null)
        testUtils.setupBook("a folder/a book", "")
        testUtils.sync()
        assertEquals(1, syncRepo.books.size)
        val expectedRookUri = when (param.repoType) {
            WEBDAV -> "https://use10.thegood.cloud/remote.php/dav/files/orgzlyrevived@gmail.com/orgzly-android-tests/a%20folder/a%20book.org"
            DOCUMENT -> repo.url + documentTreeSegment + "a%20folder%2Fa%20book.org"
            MOCK -> TODO()
            DROPBOX -> "dropbox:/orgzly-android-tests/a%20folder/a%20book.org"
            DIRECTORY -> TODO()
            GIT -> "/a folder/a book.org"
        }
        assertEquals(expectedRookUri, dataRepository.getBooks()[0].syncedTo!!.uri.toString())
        assertEquals("a folder/a book", dataRepository.getBooks()[0].book.name)
    }

    @Test
    @Throws(IOException::class)
    fun testLoadBookFromSubfolder() {
        setupSyncRepo(param.repoType, null)
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
     */
    @Test
    @Throws(IOException::class)
    fun testForceLoadBookInSubfolder() {
        setupSyncRepo(param.repoType, null)
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
        setupSyncRepo(param.repoType, "subfolder1/book1.org")
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
        setupSyncRepo(param.repoType, "subfolder1/**\n!subfolder1/book2.org")
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
    fun testUpdateBookInSubfolder() {
        setupSyncRepo(param.repoType, null)
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
            // EspressoUtils.onBook(0, R.id.item_book_last_action).check(ViewAssertions.matches(ViewMatchers.withText(CoreMatchers.endsWith("Saved to content://com.android.externalstorage.documents/tree/primary%3A$repoDirName"))) )
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

    private fun setupSyncRepo(repoType: RepoType, ignoreRules: String?) {
        when (repoType) {
            GIT -> setupGitRepo()
            MOCK -> TODO()
            DROPBOX -> setupDropboxRepo()
            DIRECTORY -> TODO()
            DOCUMENT -> setupContentRepo()
            WEBDAV -> setupWebdavRepo()
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
        repo = testUtils.setupRepo(DROPBOX, "dropbox:/$repoDirectoryName")
        syncRepo = testUtils.repoInstance(DROPBOX, repo.url, repo.id)
    }

    private fun tearDownDropboxRepo() {
        val dropboxRepo = syncRepo as DropboxRepo
        try {
            dropboxRepo.deleteDirectory(syncRepo.uri)
        } catch (_: IOException) {}
    }

    private fun setupContentRepo() {
        val encodedRepoDirName = Uri.encode(repoDirectoryName)
        documentTreeSegment = if (Build.VERSION.SDK_INT < 33) {
            "/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2F$encodedRepoDirName%2F"
        } else {
            "/document/primary%3A$encodedRepoDirName%2F"
        }
        val treeDocumentFileUrl = if (Build.VERSION.SDK_INT < 33) {
            "content://com.android.providers.downloads.documents/tree/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2F$encodedRepoDirName"
        } else {
            "content://com.android.externalstorage.documents/tree/primary%3A$encodedRepoDirName"
        }
        val repoDirDocumentFile = DocumentFile.fromTreeUri(context, treeDocumentFileUrl.toUri())
        if (repoDirDocumentFile?.exists() == false) {
            ContentRepoTest.addContentRepoInUi(repoDirectoryName)
            repo = dataRepository.getRepos()[0]
        } else {
            repo = testUtils.setupRepo(DOCUMENT, treeDocumentFileUrl)
        }
        syncRepo = testUtils.repoInstance(DOCUMENT, repo.url, repo.id)
        assertEquals(treeDocumentFileUrl, repo.url)
    }

    private fun tearDownContentRepo() {
        val repoDirectory = DocumentFile.fromTreeUri(context, repo.url.toUri())
        for (file in repoDirectory!!.listFiles()) {
            file.delete()
        }
    }

    private fun setupWebdavRepo() {
        testUtils.webdavTestPreflight()
        val repoProps: MutableMap<String, String> = mutableMapOf(
            WebdavRepo.USERNAME_PREF_KEY to BuildConfig.WEBDAV_USERNAME,
            WebdavRepo.PASSWORD_PREF_KEY to BuildConfig.WEBDAV_PASSWORD)
        repo = testUtils.setupRepo(WEBDAV, BuildConfig.WEBDAV_REPO_URL + "/" + repoDirectoryName, repoProps)
        syncRepo = dataRepository.getRepoInstance(repo.id, WEBDAV, repo.url)
        testUtils.sync() // Required to create the remote directory
    }

    private fun tearDownWebdavRepo() {
        try {
            syncRepo.delete(repo.url.toUri())
        } catch (e: SardineException) {
            if (e.statusCode != 404) {
                throw e
            }
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
