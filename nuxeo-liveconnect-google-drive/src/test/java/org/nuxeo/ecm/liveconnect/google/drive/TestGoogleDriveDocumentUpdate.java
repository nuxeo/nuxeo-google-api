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
 *     <a href="mailto:grenard@nuxeo.com">Guillaume Renard</a>
 *
 */

package org.nuxeo.ecm.liveconnect.google.drive;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.nuxeo.ecm.liveconnect.google.drive.GoogleDriveBlobProvider.PREFIX;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.junit.Test;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.blob.BlobManager;
import org.nuxeo.ecm.core.blob.BlobManagerFeature;
import org.nuxeo.ecm.core.blob.SimpleManagedBlob;
import org.nuxeo.ecm.core.blob.BlobManager.BlobInfo;
import org.nuxeo.ecm.core.test.TransactionalFeature;
import org.nuxeo.ecm.core.work.api.WorkManager;
import org.nuxeo.ecm.liveconnect.update.BatchUpdateBlobProvider;
import org.nuxeo.ecm.platform.test.PlatformFeature;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.transaction.TransactionHelper;

/**
 * @since 7.3
 */
@Features({ BlobManagerFeature.class, TransactionalFeature.class, PlatformFeature.class })
public class TestGoogleDriveDocumentUpdate extends GoogleDriveTestCase {

    private static final String TEST_FILE_NAME = "GoogleDriveFile";

    @Inject
    CoreSession session;

    @Inject
    WorkManager workManager;

    @Inject
    protected BlobManager blobManager;

    @Test
    public void testDocumentUpdate() throws Exception {
        // Create test document
        DocumentModel testWorkspace = session.createDocumentModel("/default-domain/workspaces", "testWorkspace",
                "Workspace");
        testWorkspace = session.createDocument(testWorkspace);
        List<DocumentModel> testFiles = new ArrayList<DocumentModel>();
        for (int i = 0; i < GoogleDriveBlobProvider.MAX_RESULT + 10; i++) {
            DocumentModel testFile = session.createDocumentModel(testWorkspace.getPathAsString(), TEST_FILE_NAME + i,
                    "File");
            BlobInfo blobInfo = new BlobInfo();
            blobInfo.key = PREFIX + ":" + USERID + ":" + FILEID_JPEG;
            blobInfo.digest = "pouet";
            SimpleManagedBlob blob = new SimpleManagedBlob(blobInfo);
            testFile.setPropertyValue("content", blob);
            testFile = session.createDocument(testFile);
            testFiles.add(testFile);
        }

        TransactionHelper.commitOrRollbackTransaction();
        TransactionHelper.startTransaction();

        BatchUpdateBlobProvider provider = (BatchUpdateBlobProvider) blobManager.getBlobProvider("googledrive");
        provider.processDocumentsUpdate();

        awaitWorks();
        for (DocumentModel testFile : testFiles) {
            testFile = session.getDocument(testFile.getRef());

            SimpleManagedBlob blob = (SimpleManagedBlob) testFile.getPropertyValue("content");

            assertTrue(StringUtils.isNotBlank(blob.getDigest()));
            assertNotEquals("pouet", blob.getDigest());
        }

    }

    protected void awaitWorks() throws InterruptedException {
        TransactionHelper.commitOrRollbackTransaction();
        TransactionHelper.startTransaction();

        final boolean allCompleted = workManager.awaitCompletion("blobProviderDocumentUpdate", 20000,
                TimeUnit.MILLISECONDS);
        assertTrue(allCompleted);

    }

}