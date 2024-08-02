package com.orgzly.android

import com.orgzly.android.App.appComponent
import com.orgzly.android.db.entity.Repo
import com.orgzly.android.repos.RepoType
import com.orgzly.android.repos.RepoWithProps
import com.orgzly.android.repos.WebdavRepo
import com.orgzly.android.repos.WebdavRepo.Companion.PASSWORD_PREF_KEY
import com.orgzly.android.repos.WebdavRepo.Companion.USERNAME_PREF_KEY
import io.github.atetzner.webdav.server.MiltonWebDAVFileServer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WebdavTest {
    
    @Test
    fun testRetrieveBook() {
        val rootFolder = java.nio.file.Files.createTempDirectory("orgzly-webdav-test-").toFile()
        val server = MiltonWebDAVFileServer(rootFolder)
        server.userCredentials["user"] = "secret"
        server.start()
        val repoPropsMap = HashMap<String, String>()
        repoPropsMap[USERNAME_PREF_KEY] = "user"
        repoPropsMap[PASSWORD_PREF_KEY] = "secret"
        val repo = Repo(0, RepoType.WEBDAV, "http://localhost:8080/")
        val repoWithProps = RepoWithProps(repo, repoPropsMap)
        val syncRepo = WebdavRepo.getInstance(repoWithProps)
        val books = syncRepo.books
        assertEquals(0, books.size)
        Thread.sleep(2000)
        server.stop()
        rootFolder.deleteRecursively()
    }
}