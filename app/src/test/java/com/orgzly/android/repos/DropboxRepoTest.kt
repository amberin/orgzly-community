package com.orgzly.android.repos

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orgzly.BuildConfig
import com.orgzly.android.db.entity.Repo
import com.orgzly.android.prefs.AppPreferences
import com.orgzly.android.repos.SyncRepoTest.Companion.repoDirName
import com.orgzly.android.util.MiscUtils
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DropboxRepoTest : SyncRepoTest {

    private lateinit var client: DropboxClient
    private lateinit var mSyncRepo: SyncRepo

    override var syncRepo: SyncRepo
        get() = mSyncRepo
        set(value) {}

    override val repoManipulationPoint: Any
        get() = client

    override fun writeFileToRepo(content: String, repoRelativePath: String): String {
        val tmpFile = kotlin.io.path.createTempFile().toFile()
        MiscUtils.writeStringToFile(content, tmpFile)
        val expectedRookUri = mSyncRepo.uri.toString() + "/" + Uri.encode(repoRelativePath, "/")
        client.upload(tmpFile, mSyncRepo.uri, repoRelativePath)
        tmpFile.delete()
        return expectedRookUri
    }

    @Before
    fun setup() {
        assumeTrue(BuildConfig.DROPBOX_APP_KEY.isNotEmpty())
        assumeTrue(BuildConfig.DROPBOX_REFRESH_TOKEN.isNotEmpty())
        val mockSerializedDbxCredential = JSONObject()
        mockSerializedDbxCredential.put("access_token", "dummy")
        mockSerializedDbxCredential.put("expires_at", System.currentTimeMillis())
        mockSerializedDbxCredential.put("refresh_token", BuildConfig.DROPBOX_REFRESH_TOKEN)
        mockSerializedDbxCredential.put("app_key", BuildConfig.DROPBOX_APP_KEY)
        AppPreferences.dropboxSerializedCredential(
            ApplicationProvider.getApplicationContext(),
            mockSerializedDbxCredential.toString()
        )
        val repo = Repo(0, RepoType.DROPBOX, "dropbox:/$repoDirName/" + UUID.randomUUID().toString())
        val repoPropsMap = HashMap<String, String>()
        val repoWithProps = RepoWithProps(repo, repoPropsMap)
        mSyncRepo = DropboxRepo(repoWithProps, ApplicationProvider.getApplicationContext())
        client = DropboxClient(ApplicationProvider.getApplicationContext(), repo.id)
    }

    @After
    fun tearDown() {
        if (this::mSyncRepo.isInitialized) {
            val dropboxRepo = mSyncRepo as DropboxRepo
            dropboxRepo.deleteDirectory(mSyncRepo.uri)
        }
    }
}
