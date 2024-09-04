package com.orgzly.android.repos

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orgzly.android.db.entity.Repo
import com.orgzly.android.repos.WebdavRepo.Companion.PASSWORD_PREF_KEY
import com.orgzly.android.repos.WebdavRepo.Companion.USERNAME_PREF_KEY
import com.orgzly.android.util.MiscUtils
import io.github.atetzner.webdav.server.MiltonWebDAVFileServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.runner.RunWith
import java.io.File
import kotlin.io.path.Path


@RunWith(AndroidJUnit4::class)
class WebdavRepoTest : SyncRepoTest {

    private val serverUrl = "http://localhost:8081"

    private lateinit var serverRootDir: File
    private lateinit var localServer: MiltonWebDAVFileServer
    private lateinit var mSyncRepo: SyncRepo
    override var syncRepo: SyncRepo
        get() = mSyncRepo
        set(value) {}
    override val repoManipulationPoint: Any
        get() = serverRootDir

    override fun writeFileToRepo(content: String, repoRelativePath: String): String {
        val targetFile = File(serverRootDir.absolutePath + "/" + Path(repoRelativePath))
        val targetDir = File(targetFile.parent!!)
        targetDir.mkdirs()
        val expectedRookUri = mSyncRepo.uri.toString() + "/" + Uri.encode(repoRelativePath, "/")
        val remoteBookFile = File(targetDir.absolutePath + "/" + targetFile.name)
        MiscUtils.writeStringToFile(content, remoteBookFile)
        return expectedRookUri
    }

    private lateinit var tmpFile: File

    @Before
    fun setup() {
        serverRootDir = java.nio.file.Files.createTempDirectory("orgzly-webdav-test-").toFile()
        localServer = MiltonWebDAVFileServer(serverRootDir)
        localServer.userCredentials["user"] = "secret"
        localServer.start()
        val repo = Repo(0, RepoType.WEBDAV, serverUrl)
        val repoPropsMap = HashMap<String, String>()
        repoPropsMap[USERNAME_PREF_KEY] = "user"
        repoPropsMap[PASSWORD_PREF_KEY] = "secret"
        val repoWithProps = RepoWithProps(repo, repoPropsMap)
        mSyncRepo = WebdavRepo.getInstance(repoWithProps)
        assertEquals(serverUrl, repo.url)
        tmpFile = kotlin.io.path.createTempFile().toFile()
    }

    @After
    fun tearDown() {
        tmpFile.delete()
        if (this::localServer.isInitialized) {
            localServer.stop()
        }
        if (this::serverRootDir.isInitialized) {
            serverRootDir.deleteRecursively()
        }
    }
}
