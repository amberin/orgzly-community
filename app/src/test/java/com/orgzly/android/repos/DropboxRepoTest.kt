package com.orgzly.android.repos

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orgzly.BuildConfig
import com.orgzly.android.db.entity.Repo
import com.orgzly.android.prefs.AppPreferences
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DropboxRepoTest : SyncRepoTest {

    private lateinit var syncRepo: SyncRepo
    private lateinit var client: DropboxClient

    companion object {
        val RANDOM_UUID = UUID.randomUUID().toString()
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
        val repo = Repo(0, RepoType.DROPBOX, "dropbox:/${SyncRepoTest.repoDirName}/$RANDOM_UUID")
        val repoPropsMap = HashMap<String, String>()
        val repoWithProps = RepoWithProps(repo, repoPropsMap)
        syncRepo = DropboxRepo(repoWithProps, ApplicationProvider.getApplicationContext())
        client = DropboxClient(ApplicationProvider.getApplicationContext(), repo.id)
    }

    @After
    fun tearDown() {
        val dropboxRepo = syncRepo as DropboxRepo
        try {
            dropboxRepo.deleteDirectory(syncRepo.uri)
        } catch (_: IOException) {}
    }

    @Test
    override fun testGetBooks_singleOrgFile() {
        SyncRepoTest.testGetBooks_singleOrgFile(client, syncRepo)
    }

    @Test
    override fun testGetBooks_singleFileInSubfolder() {
        SyncRepoTest.testGetBooks_singleFileInSubfolder(client, syncRepo)
    }

    @Test
    override fun testGetBooks_allFilesAreIgnored() {
        SyncRepoTest.testGetBooks_allFilesAreIgnored(client, syncRepo)
    }

    @Test
    override fun testGetBooks_specificFileInSubfolderIsIgnored() {
        SyncRepoTest.testGetBooks_specificFileInSubfolderIsIgnored(client, syncRepo)
    }

    @Test
    override fun testGetBooks_specificFileIsUnignored() {
        SyncRepoTest.testGetBooks_specificFileIsUnignored(client, syncRepo)
    }

    @Test
    override fun testGetBooks_ignoredExtensions() {
        SyncRepoTest.testGetBooks_ignoredExtensions(client, syncRepo)
    }

    @Test
    override fun testStoreBook_expectedUri() {
        SyncRepoTest.testStoreBook_expectedUri(syncRepo)
    }

    @Test
    override fun testStoreBook_producesSameUriAsRetrieveBook() {
        SyncRepoTest.testStoreBook_producesSameUriAsRetrieveBook(syncRepo)
    }

    @Test
    override fun testStoreBook_producesSameUriAsGetBooks() {
        SyncRepoTest.testStoreBook_producesSameUriAsGetBooks(client, syncRepo)
    }

    @Test
    override fun testStoreBook_inSubfolder() {
        SyncRepoTest.testStoreBook_inSubfolder(client, syncRepo)
    }

    @Test
    override fun testRenameBook_expectedUri() {
        SyncRepoTest.testRenameBook_expectedUri(syncRepo)
    }
}