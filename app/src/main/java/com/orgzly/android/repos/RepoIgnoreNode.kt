package com.orgzly.android.repos

import com.orgzly.R
import com.orgzly.android.App
import org.eclipse.jgit.ignore.IgnoreNode
import java.io.FileNotFoundException
import java.io.IOException

class RepoIgnoreNode(repo: SyncRepo) : IgnoreNode() {

    private val context = App.getAppContext()

    init {
        try {
            val inputStream = repo.streamFile(context.getString(R.string.repo_ignore_rules_file))
            inputStream.use {
                parse(it)
            }
            inputStream.close()
        } catch (ignored: FileNotFoundException) {}
    }

    /**
     * Simplify the parent class' isIgnored method. We should only ever call this method
     * on files -- never on directories.
     */
    @Override
    fun isIgnored(path: String): Boolean {
        if (rules.isEmpty()) {
            return false
        }
        return isIgnored(path, false) == MatchResult.IGNORED
    }

    fun ensurePathIsNotIgnored(filePath: String) {
        if (isIgnored(filePath)) {
            throw IOException(
                context.getString(
                    R.string.error_file_matches_repo_exclude_rule,
                    context.getString(R.string.repo_ignore_rules_file),
                )
            )
        }
    }
}