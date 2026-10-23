package com.mistirain.SongRecorder;

import android.net.Uri;

import java.io.File;

public final class AudioItem {
    public final String id;
    public final String name;
    public final long sizeBytes;
    public final long lastModified;
    public final Uri uri;
    public final File file;

    public AudioItem(String id, String name, long sizeBytes, long lastModified, Uri uri, File file) {
        this.id = id;
        this.name = name;
        this.sizeBytes = sizeBytes;
        this.lastModified = lastModified;
        this.uri = uri;
        this.file = file;
    }
}
