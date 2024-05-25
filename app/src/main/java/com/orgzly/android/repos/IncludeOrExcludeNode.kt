package com.orgzly.android.repos

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.eclipse.jgit.ignore.IgnoreNode
import java.io.File
import java.io.FileInputStream

/**
 * Represents a bundle of include or exclude rules from a base directory. Searches a directory for an
 * include file, reads it, and returns the include rules. If no include file is found, the procedure
 * is repeated for an exclude file. Both files use the syntax of a .gitignore file, which is why
 * we inherit from JGit's IgnoreNode class.
 */
class IncludeOrExcludeNode() : IgnoreNode() {
    private val includeFileName = ".orgzlyinclude"
    private val excludeFileName = ".orgzlyexclude"
    private var isIncludeNode = true

    constructor(rootDocumentFile: DocumentFile, context: Context) : this() {
        var fileToParse: DocumentFile? = null
        val includeFilePath = getDocumentFileFromFileName(rootDocumentFile, includeFileName, context)
        if (includeFilePath?.exists() == true) {
            fileToParse = includeFilePath
            isIncludeNode = true
        } else {
            val excludeFilePath = getDocumentFileFromFileName(rootDocumentFile, excludeFileName, context)
            if (excludeFilePath?.exists() == true) {
                fileToParse = includeFilePath
                isIncludeNode = false
            }
        }
        if (fileToParse != null) {
            context.contentResolver.openInputStream(fileToParse.uri).use { inputStream ->
                this.parse(
                    inputStream
                )
            }
        }
    }

    constructor(rootFile: File) : this() {
        var fileToParse: File? = null
        val includeFile = File(rootFile, includeFileName)
        if (includeFile.exists()) {
            fileToParse = includeFile
            isIncludeNode = true
        } else {
            val excludeFile = File(rootFile, excludeFileName)
            if (excludeFile.exists()) {
                fileToParse = excludeFile
                isIncludeNode = false
            }
        }
        if (fileToParse != null) {
            FileInputStream(fileToParse).use { inputStream ->
                this.parse(inputStream)
            }
        }
    }

    @Override
    fun isIgnored(node: DocumentFile): Boolean {
        if (rules.isEmpty()) {
            return false
        }
        val matchingRuleExists = isIgnored(node.uri.path, node.isDirectory) == MatchResult.IGNORED
        return if (isIncludeNode) {
            !matchingRuleExists
        } else {
            matchingRuleExists
        }
    }

    @Override
    fun isIgnored(node: File): Boolean {
        if (rules.isEmpty()) {
            return false
        }
        val matchingRuleExists = isIgnored(node.path, node.isDirectory) == MatchResult.IGNORED
        return if (isIncludeNode) {
            !matchingRuleExists
        } else {
            matchingRuleExists
        }
    }

    private fun getDocumentFileFromFileName(root: DocumentFile, fileName: String, context: Context): DocumentFile? {
        val fullUri: String = root.uri.toString() + Uri.encode("/$fileName")
        return DocumentFile.fromSingleUri(context, Uri.parse(fullUri))
    }
}