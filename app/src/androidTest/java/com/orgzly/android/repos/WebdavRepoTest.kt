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

    companion object {
        private val repoProps: MutableMap<String, String> = mutableMapOf(
            WebdavRepo.USERNAME_PREF_KEY to BuildConfig.WEBDAV_USERNAME,
            WebdavRepo.PASSWORD_PREF_KEY to BuildConfig.WEBDAV_PASSWORD)
    }
}