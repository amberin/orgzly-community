package com.orgzly.android.repos

import com.orgzly.android.OrgzlyTest
import com.orgzly.android.db.entity.Repo
import com.orgzly.android.util.MiscUtils
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.util.UUID

@RunWith(value = Parameterized::class)
class SyncRepoTest(private val repoType: RepoType) : OrgzlyTest() {

    private lateinit var repo: Repo
    private lateinit var syncRepo: SyncRepo

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Array<RepoType> {
            return arrayOf(
//                GIT,
//                DOCUMENT,
//                DROPBOX,
                RepoType.WEBDAV,
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
                RepoType.WEBDAV -> TODO()
                RepoType.MOCK -> TODO()
                RepoType.DROPBOX -> TODO()
                RepoType.DIRECTORY -> TODO()
                RepoType.DOCUMENT -> TODO()
                RepoType.GIT -> TODO()
            }
        }
    }

    private fun setupSyncRepo(repoType: RepoType, ignoreRules: String? = null) {
        when (repoType) {
            RepoType.WEBDAV -> TODO()
            RepoType.MOCK -> TODO()
            RepoType.DROPBOX -> TODO()
            RepoType.DIRECTORY -> TODO()
            RepoType.DOCUMENT -> TODO()
            RepoType.GIT -> TODO()
        }
        if (ignoreRules != null) {
            val tmpFile = File.createTempFile("orgzly-test", null)
            MiscUtils.writeStringToFile(ignoreRules, tmpFile)
            syncRepo.storeBook(tmpFile, RepoIgnoreNode.IGNORE_FILE)
            tmpFile.delete()
        }
    }
}