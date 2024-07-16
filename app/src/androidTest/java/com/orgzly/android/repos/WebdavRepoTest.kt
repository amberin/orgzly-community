package com.orgzly.android.repos

import com.orgzly.BuildConfig
import com.orgzly.android.BookName
import com.orgzly.android.OrgzlyTest
import com.orgzly.android.db.entity.BookView
import com.orgzly.android.db.entity.Repo
import com.orgzly.android.util.MiscUtils
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import java.io.File
import java.io.IOException
import java.util.UUID

class WebdavRepoTest : OrgzlyTest() {

    @Before
    @Throws(Exception::class)
    override fun setUp() {
        super.setUp()
        testUtils.webdavTestPreflight()
    }

    @JvmField
    @Rule
    var exceptionRule: ExpectedException = ExpectedException.none()

    @Test
    fun testUrl() {
        val repo = setupRepo()
        Assert.assertEquals(
            "webdav:/dir", testUtils.repoInstance(RepoType.WEBDAV, "webdav:/dir", repo.id).uri.toString()
        )
    }

    @Test
    fun testSyncingUrlWithTrailingSlash() {
        testUtils.setupRepo(RepoType.WEBDAV, randomUrl() + "/", repoProps)
        Assert.assertNotNull(testUtils.sync())
    }

    @Test
    fun testRenameBook() {
        val repo = setupRepo()
        val repoUriString = repo.url
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
        val repo = setupRepo()
        val webdavRepo = testUtils.repoInstance(RepoType.WEBDAV, repo.url, repo.id) as WebdavRepo
        testUtils.sync() // To ensure the remote directory exists
        uploadFileToRepo(webdavRepo, RepoIgnoreNode.IGNORE_FILE, "*.org")
        testUtils.setupBook("booky", "")
        exceptionRule.expect(IOException::class.java)
        exceptionRule.expectMessage("matches a rule in .orgzlyignore")
        testUtils.syncOrThrow()
    }

    @Test
    @Throws(Exception::class)
    fun testIgnoreRulePreventsLoadingBook() {
        val repo = setupRepo()
        val webdavRepo = testUtils.repoInstance(RepoType.WEBDAV, repo.url, repo.id) as WebdavRepo
        testUtils.sync() // To ensure the remote directory exists

        // Create two .org files
        uploadFileToRepo(webdavRepo, "ignored.org", "1 2 3")
        uploadFileToRepo(webdavRepo, "notignored.org", "1 2 3")
        // Create .orgzlyignore
        uploadFileToRepo(webdavRepo, RepoIgnoreNode.IGNORE_FILE, "ignored.org")
        testUtils.sync()
        val bookViews = dataRepository.getBooks()
        Assert.assertEquals(1, bookViews.size.toLong())
        Assert.assertEquals("notignored", bookViews[0].book.name)
    }

    @Test
    @Throws(Exception::class)
    fun testIgnoreRulePreventsRenamingBook() {
        val repo = setupRepo()
        val webdavRepo = testUtils.repoInstance(RepoType.WEBDAV, repo.url, repo.id) as WebdavRepo
        testUtils.sync() // To ensure the remote directory exists
        uploadFileToRepo(webdavRepo, RepoIgnoreNode.IGNORE_FILE, "badname*")
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
        val repo = setupRepo()
        val syncRepo = testUtils.repoInstance(RepoType.WEBDAV, repo.url, repo.id)
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

    private fun randomUrl(): String {
        return BuildConfig.WEBDAV_REPO_URL + WEBDAV_TEST_DIR + "/" + UUID.randomUUID().toString()
    }

    private fun setupRepo(): Repo {
        return testUtils.setupRepo(RepoType.WEBDAV, randomUrl(), repoProps)
    }

    companion object {
        private const val WEBDAV_TEST_DIR = "/orgzly-android-tests"

        private val repoProps: MutableMap<String, String> = mutableMapOf(
            WebdavRepo.USERNAME_PREF_KEY to BuildConfig.WEBDAV_USERNAME,
            WebdavRepo.PASSWORD_PREF_KEY to BuildConfig.WEBDAV_PASSWORD)

        @Throws(IOException::class)
        fun uploadFileToRepo(repo: WebdavRepo, fileName: String, fileContents: String) {
            val tmpFile = File.createTempFile("abc", null)
            MiscUtils.writeStringToFile(fileContents, tmpFile)
            repo.uploadFile(tmpFile, fileName)
            tmpFile.delete()
        }
    }
}