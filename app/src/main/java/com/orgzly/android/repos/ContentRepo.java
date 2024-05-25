package com.orgzly.android.repos;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import com.orgzly.BuildConfig;
import com.orgzly.android.BookName;
import com.orgzly.android.db.entity.Repo;
import com.orgzly.android.util.LogUtils;
import com.orgzly.android.util.MiscUtils;

import org.eclipse.jgit.ignore.IgnoreNode;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Using DocumentFile, for devices running Lollipop or later.
 */
public class ContentRepo implements SyncRepo {
    private static final String TAG = ContentRepo.class.getName();

    public static final String SCHEME = "content";

    private final long repoId;
    private final Uri repoUri;

    private final Context context;

    private final DocumentFile repoDocumentFile;

    public ContentRepo(RepoWithProps repoWithProps, Context context) {
        Repo repo = repoWithProps.getRepo();

        this.repoId = repo.getId();
        this.repoUri = Uri.parse(repo.getUrl());

        this.context = context;

        this.repoDocumentFile = DocumentFile.fromTreeUri(context, repoUri);
    }

    @Override
    public boolean isConnectionRequired() {
        return false;
    }

    @Override
    public boolean isAutoSyncSupported() {
        return true;
    }

    @Override
    public boolean isExcludeIncludeFileSupported() { return true; }

    @Override
    public Uri getUri() {
        return repoUri;
    }

    @Override
    public List<VersionedRook> getBooks() throws IOException {
        List<VersionedRook> result = new ArrayList<>();

        List<DocumentFile> files = walkFileTree();

        if (files.size() > 0) {
            for (DocumentFile file : files) {
                if (BookName.isSupportedFormatFileName(file.getName())) {
                    if (BuildConfig.LOG_DEBUG) {
                        LogUtils.d(TAG,
                                "file.getName()", file.getName(),
                                "getUri()", getUri(),
                                "repoDocumentFile.getUri()", repoDocumentFile.getUri(),
                                "file", file,
                                "file.getUri()", file.getUri(),
                                "file.getParentFile()", file.getParentFile().getUri());
                    }
                    result.add(new VersionedRook(
                            repoId,
                            RepoType.DOCUMENT,
                            getUri(),
                            file.getUri(),
                            String.valueOf(file.lastModified()),
                            file.lastModified()
                    ));
                }
            }
        } else {
            Log.e(TAG, "Listing files in " + getUri() + " returned null.");
        }

        return result;
    }

    /**
     * @return All file nodes in the repo tree
     */
    private List<DocumentFile> walkFileTree() throws IOException {
        List<DocumentFile> result = new ArrayList<>();
        List<DocumentFile> directoryNodes = new ArrayList<>();
        IgnoreNode ignores = getIgnores();
        directoryNodes.add(repoDocumentFile);
        while (!directoryNodes.isEmpty()) {
            DocumentFile currentDir = directoryNodes.remove(0);
            for (DocumentFile node : currentDir.listFiles()) {
                if (ignores.isIgnored(node.getUri().getPath(), node.isDirectory()) == IgnoreNode.MatchResult.IGNORED) {
                    continue;
                }
                if (node.isDirectory()) {
                    directoryNodes.add(node);
                } else {
                    result.add(node);
                }
            }
        }
        return result;
    }

    private IgnoreNode getIgnores() throws IOException {
        IgnoreNode ignores = new IgnoreNode();
        DocumentFile ignoreFile = getDocumentFileFromFileName(".orgzlyignore");
        if (ignoreFile.exists()) {
            try (InputStream in =
                         context.getContentResolver().openInputStream(ignoreFile.getUri())) {
                ignores.parse(in);
            }
        }
        return ignores;
    }

    public static String getContentRepoUriRootSegment(String repoUri) {
        String repoUriLastSegment = repoUri.replaceAll("^.*/", "");
        return repoUri + "/document/" + repoUriLastSegment + "%2F";
    }

    private DocumentFile getDocumentFileFromFileName(String fileName) {
        String fullUri = repoDocumentFile.getUri() + Uri.encode("/" + fileName);
        return DocumentFile.fromSingleUri(context, Uri.parse(fullUri));
    }

    @Override
    public VersionedRook retrieveBook(String fileName, File destinationFile) throws IOException {
        DocumentFile sourceFile = getDocumentFileFromFileName(fileName);
        if (sourceFile == null) {
            throw new FileNotFoundException("Book " + fileName + " not found in " + repoUri);
        } else {
            if (BuildConfig.LOG_DEBUG) {
                LogUtils.d(TAG, "Found DocumentFile for " + fileName + ": " + sourceFile.getUri());
            }
        }

        /* "Download" the file. */
        try (InputStream is = context.getContentResolver().openInputStream(sourceFile.getUri())) {
            MiscUtils.writeStreamToFile(is, destinationFile);
        }

        String rev = String.valueOf(sourceFile.lastModified());
        long mtime = sourceFile.lastModified();

        return new VersionedRook(repoId, RepoType.DOCUMENT, repoUri, sourceFile.getUri(), rev, mtime);
    }

    @Override
    public VersionedRook storeBook(File file, String fileName) throws IOException {
        if (!file.exists()) {
            throw new FileNotFoundException("File " + file + " does not exist");
        }
        DocumentFile destinationFile = getDocumentFileFromFileName(fileName);
        if (!destinationFile.exists()) {
            if (fileName.contains("/")) {
                throw new UnsupportedOperationException("Invalid book name. (Creating files in " +
                        "folders is not supported.)");
            }
            repoDocumentFile.createFile("text/*", fileName);
        }
        OutputStream out = context.getContentResolver().openOutputStream(destinationFile.getUri());

        try {
            MiscUtils.writeFileToStream(file, out);
        } finally {
            if (out != null) {
                out.close();
            }
        }

        String rev = String.valueOf(destinationFile.lastModified());
        long mtime = System.currentTimeMillis();
        return new VersionedRook(repoId, RepoType.DOCUMENT, getUri(), destinationFile.getUri(), rev, mtime);
    }

    @Override
    public VersionedRook renameBook(Uri from, String name) throws IOException {
        DocumentFile fromDocFile = DocumentFile.fromSingleUri(context, from);
        BookName bookName = BookName.fromFileName(fromDocFile.getName());
        String newFileName = BookName.fileName(name, bookName.getFormat());

        /* Check if document already exists. */
        DocumentFile existingFile = repoDocumentFile.findFile(newFileName);
        if (existingFile != null) {
            throw new IOException("File at " + existingFile.getUri() + " already exists");
        }

        Uri newUri = DocumentsContract.renameDocument(context.getContentResolver(), from, newFileName);

        long mtime = fromDocFile.lastModified();
        String rev = String.valueOf(mtime);

        return new VersionedRook(repoId, RepoType.DOCUMENT, getUri(), newUri, rev, mtime);
    }

    @Override
    public void delete(Uri uri) throws IOException {
        DocumentFile docFile = DocumentFile.fromSingleUri(context, uri);

        if (docFile != null && docFile.exists()) {
            if (! docFile.delete()) {
                throw new IOException("Failed deleting document " + uri);
            }
        }
    }

    @Override
    public String toString() {
        return getUri().toString();
    }
}
