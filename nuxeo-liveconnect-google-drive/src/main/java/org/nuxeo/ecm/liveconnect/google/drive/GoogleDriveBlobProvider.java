/*
 * (C) Copyright 2015 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-2.1.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Florent Guillaume
 *     Nelson Silva
 */
package org.nuxeo.ecm.liveconnect.google.drive;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import com.google.api.client.json.JsonObjectParser;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.ObjectParser;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.blob.BlobManager.BlobInfo;
import org.nuxeo.ecm.core.blob.BlobManager.UsageHint;
import org.nuxeo.ecm.core.blob.ExtendedBlobProvider;
import org.nuxeo.ecm.core.blob.ManagedBlob;
import org.nuxeo.ecm.core.blob.SimpleManagedBlob;
import org.nuxeo.ecm.core.cache.Cache;
import org.nuxeo.ecm.core.cache.CacheService;
import org.nuxeo.ecm.core.model.Document;
import org.nuxeo.ecm.liveconnect.google.drive.credential.CredentialFactory;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import org.nuxeo.runtime.api.Framework;

/**
 * Provider for blobs getting information from Google Drive.
 *
 * @since 7.3
 */
public class GoogleDriveBlobProvider implements ExtendedBlobProvider {

    private static final Log log = LogFactory.getLog(GoogleDriveBlobProvider.class);

    private static final String APPLICATION_NAME = "Nuxeo/0";

    private static final String FILE_CACHE_NAME = "googleDrive";

    private final CredentialFactory credentialFactory;

    /** {@link File} resource cache */
    private Cache fileCache;

    public GoogleDriveBlobProvider(CredentialFactory credentialFactory) {
        this.credentialFactory = credentialFactory;
    }

    @Override
    public Blob readBlob(BlobInfo blobInfo, Document doc) {
        return new SimpleManagedBlob(blobInfo);
    }

    @Override
    public BlobInfo writeBlob(Blob blob, Document doc) {
        throw new UnsupportedOperationException("Writing a blob to Google Drive is not supported");
    }

    @Override
    public URI getURI(ManagedBlob blob, UsageHint usage) throws IOException {
        String url = null;
        File file = getFile(blob);
        switch (usage) {
        case STREAM:
            url = file.getDownloadUrl();
            break;
        case DOWNLOAD:
            url = file.getWebContentLink();
            if (url == null) {
                url = file.getAlternateLink();
            }
            break;
        case VIEW:
        case EDIT:
            url = file.getAlternateLink();
            break;
        case EMBED:
            url = file.getEmbedLink();
            if (url == null) {
                // non-native file resources do not return an embedLink but it is available
                url = file.getAlternateLink();
                URI uri = asURI(url).resolve("./preview");
                url = uri.toString();
            }
            break;
        }
        return url != null ? asURI(url) : null;
    }

    protected InputStream getStream(String blobKey, URI uri) throws IOException {
        String info = getFileInfo(blobKey);
        String user = getUser(info);
        HttpResponse resp = doGet(user, uri);
        return resp.getContent();
    }

    @Override
    public Map<String, URI> getAvailableConversions(ManagedBlob blob, UsageHint hint) throws IOException {
        File file = getFile(blob);
        Map<String, String> exportLinks = file.getExportLinks();
        if (exportLinks == null) {
            return Collections.emptyMap();
        }
        Map<String, URI> conversions = new HashMap<>();
        for (String mimeType : exportLinks.keySet()) {
            conversions.put(mimeType, asURI(exportLinks.get(mimeType)));
        }
        return conversions;
    }

    @Override
    public InputStream getThumbnail(ManagedBlob blob) throws IOException {
        URI uri = asURI(getFile(blob).getThumbnailLink());
        return getStream(blob.getKey(), uri);
    }

    @Override
    public InputStream getStream(ManagedBlob blob) throws IOException {
        URI uri = getURI(blob, UsageHint.STREAM);
        return getStream(blob.getKey(), uri);
    }

    @Override
    public InputStream getConvertedStream(ManagedBlob blob, String mimeType) throws IOException {
        Map<String, URI> conversions = getAvailableConversions(blob, UsageHint.STREAM);
        URI uri = conversions.get(mimeType);
        if (uri == null) {
            return null;
        }
        return getStream(blob.getKey(), uri);
    }

    /**
     * Gets the blob for a Google Drive file.
     *
     * @param fileInfo the file info ({email}:{fileId})
     * @return the blob
     */
    protected Blob getBlob(String fileInfo) throws IOException {
        String user = getUser(fileInfo);
        String fileId = getFileId(fileInfo);
        File file = getFile(user, fileId);
        String key = String.format("%s:%s:%s", GoogleDriveComponent.GOOGLE_DRIVE_PREFIX, user, fileId);
        String filename = file.getOriginalFilename();
        if (filename == null) {
            filename = file.getTitle().replace("/", "-");
        }
        BlobInfo blobInfo = new BlobInfo();
        blobInfo.key = key;
        blobInfo.mimeType = file.getMimeType();
        blobInfo.encoding = null; // TODO extract from mimeType
        blobInfo.filename = filename;
        blobInfo.length = file.getFileSize();
        // etag for native docs and md5 for everything else
        String digest = file.getMd5Checksum();
        if (digest == null) {
            digest = file.getEtag();
        }
        blobInfo.digest = digest;
        return new SimpleManagedBlob(blobInfo);
    }

    protected String getFileInfo(String key) {
        int colon = key.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException(key);
        }
        String fileInfo = key.substring(colon + 1);
        return fileInfo;
    }

    protected String getUser(String fileInfo) {
        return getFileInfoParts(fileInfo)[0];
    }

    protected String getFileId(String fileInfo) {
        return getFileInfoParts(fileInfo)[1];
    }

    protected String[] getFileInfoParts(String fileInfo) {
        String[] parts = fileInfo.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException(fileInfo);
        }
        return parts;
    }

    protected Credential getCredential(String user) throws IOException {
        return credentialFactory.build(user);
    }

    protected Drive getService(String user) throws IOException {
        Credential credential = getCredential(user);
        HttpTransport httpTransport = credential.getTransport();
        JsonFactory jsonFactory = credential.getJsonFactory();
        return new Drive.Builder(httpTransport, jsonFactory, credential) //
        .setApplicationName(APPLICATION_NAME) // set application name to avoid a WARN
        .build();
    }

    protected File getFile(ManagedBlob blob) throws IOException {
        String fileInfo = getFileInfo(blob.getKey());
        String user = getUser(fileInfo);
        String fileId = getFileId(fileInfo);
        return getFile(user, fileId);
    }

    /**
     * Retrieves a {@link File} resource and caches the unparsed response.
     *
     * @return a {@link File} resource.
     */
    protected File getFile(String user, String fileId) throws IOException {
        String fileResource = (String) getFileCache().get(fileId);
        if (fileResource == null) {
            HttpResponse response = getService(user).files().get(fileId).executeUnparsed();
            if (!response.isSuccessStatusCode()) {
                return null;
            }
            fileResource = response.parseAsString();
            getFileCache().put(fileId, fileResource);
        }
        return parseFile(fileResource);
    }

    /**
     * Executes a GET request with the user's credentials.
     */
    protected HttpResponse doGet(String user, URI url) throws IOException {
        return getService(user).getRequestFactory().buildGetRequest(new GenericUrl(url)).execute();
    }

    /**
     * Parse a {@link URI}.
     *
     * @return the {@link URI} or null if it fails
     */
    protected static URI asURI(String link) {
        try {
            return new URI(link);
        } catch (URISyntaxException e) {
            log.error("Invalid URI: " + link, e);
            return null;
        }
    }

    private Cache getFileCache() {
        if (fileCache == null) {
            fileCache = Framework.getService(CacheService.class).getCache(FILE_CACHE_NAME);
        }
        return fileCache;
    }

    private File parseFile(String json) throws IOException {
        JsonFactory jsonFactory = JacksonFactory.getDefaultInstance();
        ObjectParser parser = new JsonObjectParser(jsonFactory);
        return parser.parseAndClose(new StringReader(json), File.class);
    }
}