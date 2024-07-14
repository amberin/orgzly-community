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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

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
     * @return All file nodes in the repo tree which are not excluded by .orgzlyignore
     */
    private List<DocumentFile> walkFileTree() {
        List<DocumentFile> result = new ArrayList<>();
        List<DocumentFile> directoryNodes = new ArrayList<>();
        RepoIgnoreNode ignores = new RepoIgnoreNode(this);
        directoryNodes.add(repoDocumentFile);
        while (!directoryNodes.isEmpty()) {
            DocumentFile currentDir = directoryNodes.remove(0);
            for (DocumentFile node : currentDir.listFiles()) {
                String relativeFileName = BookName.getFileName(repoUri, node.getUri());
                if (node.isDirectory()) {
                    if (Build.VERSION.SDK_INT >= 26) {
                        if (ignores.isPathIgnored(relativeFileName, true)) {
                            continue;
                        }
                    }
                    directoryNodes.add(node);
                } else {
                    if (Build.VERSION.SDK_INT >= 26) {
                        if (ignores.isPathIgnored(relativeFileName, false)) {
                            continue;
                        }
                    } result.add(node);
                }
            }
        }
        return result;
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
    public InputStream openRepoFileInputStream(String fileName) throws IOException {
        DocumentFile sourceFile = getDocumentFileFromFileName(fileName);
        if (!sourceFile.exists()) throw new FileNotFoundException();
        return context.getContentResolver().openInputStream(sourceFile.getUri());
    }

    @Override
    public VersionedRook storeBook(File file, String fileName) throws IOException {
        if (!file.exists()) {
            throw new FileNotFoundException("File " + file + " does not exist");
        }
        DocumentFile destinationFile = getDocumentFileFromFileName(fileName);
        if (!destinationFile.exists()) {
            if (fileName.contains("/")) {
                DocumentFile destinationDir = ensureSubDirectoriesExist(fileName);
                assert destinationDir != null;
                destinationFile = destinationDir.createFile("text/*",
                        Objects.requireNonNull(Uri.parse(fileName).getLastPathSegment()));
            } else {
                repoDocumentFile.createFile("text/*", fileName);
            }
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

    private DocumentFile ensureSubDirectoriesExist(String fileName) {
        List<String> levels = new ArrayList<>(Arrays.asList(fileName.split("/")));
        DocumentFile parentDir = repoDocumentFile;
        while (levels.size() > 1) {
            String currentDirName = levels.remove(0);
            assert parentDir != null;
            if (parentDir.findFile(currentDirName) == null) {
                parentDir = parentDir.createDirectory(currentDirName);
            }
        }
        return parentDir;
    }

    @Override
    public VersionedRook renameBook(Uri from, String name) throws IOException {
        DocumentFile fromDocFile = DocumentFile.fromSingleUri(context, from);
        BookName bookName = BookName.fromFileName(BookName.getFileName(repoUri, from));
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
