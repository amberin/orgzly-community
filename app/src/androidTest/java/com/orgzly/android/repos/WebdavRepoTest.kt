package com.orgzly.android.repos

import androidx.core.net.toUri
import com.orgzly.BuildConfig
import com.orgzly.android.BookName
import com.orgzly.android.OrgzlyTest
import com.orgzly.android.db.entity.BookView
import com.orgzly.android.db.entity.Repo
import com.orgzly.android.util.MiscUtils
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID

class WebdavRepoTest : OrgzlyTest() {

    private val repoUriString = BuildConfig.WEBDAV_REPO_URL + "/orgzly-android-tests"
    private lateinit var syncRepo: SyncRepo

    @Before
    @Throws(Exception::class)
    override fun setUp() {
        super.setUp()
        testUtils.webdavTestPreflight()
    }

    @After
    override fun tearDown() {
        super.tearDown()
        if (this::syncRepo.isInitialized) {
            syncRepo.delete(syncRepo.uri)
        }
    }

    @JvmField
    @Rule
    var exceptionRule: ExpectedException = ExpectedException.none()

    @Test
    fun testUrl() {
        val repo = testUtils.setupRepo(RepoType.WEBDAV, repoUriString, repoProps)
        Assert.assertEquals(
            "webdav:/dir", testUtils.repoInstance(RepoType.WEBDAV, "webdav:/dir", repo.id).uri.toString()
        )
    }

    @Test
    fun testSyncingUrlWithTrailingSlash() {
        val repo = testUtils.setupRepo(RepoType.WEBDAV, "$repoUriString/", repoProps)
        syncRepo = testUtils.repoInstance(RepoType.WEBDAV, repo.url, repo.id)
        Assert.assertNotNull(testUtils.sync())
    }

    @Test
    fun testRenameBook() {
        setupRepo()
        testUtils.setupBook("booky", "")
        testUtils.sync()
        var bookView: BookView? = dataRepository.getBookView("booky")
        Assert.assertEquals(repoUriString, bookView!!.linkRepo!!.url)
        Assert.assertEquals(repoUriString, bookView.syncedTo!!.getRepoUri().toString())
        Assert.assertEquals("$repoUriString/booky.org", bookView.syncedTo!!.getUri().toString())
        dataRepository.renameBook(bookView, "booky-renamed")
        bookView = dataRepository.getBookView("booky-renamed")
        Assert.assertEquals(repoUriString, bookView!!.linkRepo!!.url)
        Assert.assertEquals(repoUriString, bookView.syncedTo!!.getRepoUri().toString())
        Assert.assertEquals(
            "$repoUriString/booky-renamed.org",
            bookView.syncedTo!!.getUri().toString()
        )
    }

    @Test
    @Throws(Exception::class)
    fun testIgnoreRulePreventsLinkingBook() {
        setupRepo()
        testUtils.sync() // To ensure the remote directory exists
        writeStringToRepoFile(syncRepo, "*.org", RepoIgnoreNode.IGNORE_FILE)
        testUtils.setupBook("booky", "")
        exceptionRule.expect(IOException::class.java)
        exceptionRule.expectMessage("matches a rule in .orgzlyignore")
        testUtils.syncOrThrow()
    }

    @Test
    @Throws(Exception::class)
    fun testIgnoreRulePreventsLoadingBook() {
        setupRepo()
        testUtils.sync() // To ensure the remote directory exists

        // Create two .org files
        writeStringToRepoFile(syncRepo, "1 2 3", "ignored.org")
        writeStringToRepoFile(syncRepo, "1 2 3", "notignored.org")
        // Create .orgzlyignore
        writeStringToRepoFile(syncRepo, "ignored.org", RepoIgnoreNode.IGNORE_FILE)
        testUtils.sync()
        val bookViews = dataRepository.getBooks()
        Assert.assertEquals(1, bookViews.size.toLong())
        Assert.assertEquals("notignored", bookViews[0].book.name)
    }

    @Test
    @Throws(Exception::class)
    fun testIgnoreRulePreventsRenamingBook() {
        setupRepo()
        testUtils.sync() // To ensure the remote directory exists
        writeStringToRepoFile(syncRepo, "badname*", RepoIgnoreNode.IGNORE_FILE)
        testUtils.setupBook("goodname", "")
        testUtils.sync()
        var bookView: BookView? = dataRepository.getBookView("goodname")
        dataRepository.renameBook(bookView!!, "badname")
        bookView = dataRepository.getBooks()[0]
        Assert.assertTrue(
            bookView.book.lastAction.toString().contains("matches a rule in .orgzlyignore")
        )
    }

    @Test
    @Throws(IOException::class)
    fun testFileRename() {
        setupRepo()
        Assert.assertNotNull(syncRepo)
        Assert.assertEquals(0, syncRepo.books.size.toLong())
        val file = File.createTempFile("notebook.", ".org")
        MiscUtils.writeStringToFile("1 2 3", file)
        val vrook = syncRepo.storeBook(file, file.name)
        file.delete()
        Assert.assertEquals(1, syncRepo.books.size.toLong())
        syncRepo.renameBook(vrook.getUri(), "notebook-renamed")
        Assert.assertEquals(1, syncRepo.books.size.toLong())
        Assert.assertEquals(
            syncRepo.uri.toString() + "/notebook-renamed.org",
            syncRepo.books[0].getUri().toString()
        )
        Assert.assertEquals(
            "notebook-renamed.org",
            BookName.getInstance(context, syncRepo.books[0]).fileName
        )
    }

    private fun setupRepo() {
        val repo = testUtils.setupRepo(RepoType.WEBDAV, repoUriString, repoProps)
        syncRepo = testUtils.repoInstance(RepoType.WEBDAV, repo.url, repo.id)
    }

    companion object {
        private val repoProps: MutableMap<String, String> = mutableMapOf(
            WebdavRepo.USERNAME_PREF_KEY to BuildConfig.WEBDAV_USERNAME,
            WebdavRepo.PASSWORD_PREF_KEY to BuildConfig.WEBDAV_PASSWORD)

        fun writeStringToRepoFile(repo: SyncRepo, content: String, fileName: String) {
            val tmpFile = File.createTempFile("orgzly-test", null)
            MiscUtils.writeStringToFile(content, tmpFile)
            repo.storeBook(tmpFile, fileName)
            tmpFile.delete()
        }
    }
}