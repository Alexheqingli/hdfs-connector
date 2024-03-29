/**
 * Copyright (c) MuleSoft, Inc. All rights reserved. http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.md file.
 */

package org.mule.modules.hdfs.automation.testcases;

import org.apache.hadoop.fs.ContentSummary;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.MD5MD5CRC32FileChecksum;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mule.api.MuleMessage;
import org.mule.modules.hdfs.HDFSConnector;
import org.mule.modules.hdfs.automation.RegressionTests;
import org.mule.modules.hdfs.automation.SmokeTests;
import org.mule.modules.tests.ConnectorTestUtils;

import static org.junit.Assert.*;


public class GetMetadataTestCases extends HDFSTestParent {

    @Before
    public void setUp() throws Exception {
        initializeTestRunMessage("getMetadataTestData");
        runFlowAndGetPayload("write-default-values");
    }

    @Category({SmokeTests.class, RegressionTests.class})
    @Test
    public void testGetMetadata() {
        try {
            MuleMessage muleMessage = runFlowAndGetMessage("get-metadata");

            boolean exists = (Boolean) muleMessage.getInvocationProperty(HDFSConnector.HDFS_PATH_EXISTS);
            assertTrue(exists);

            MD5MD5CRC32FileChecksum fileMd5 = muleMessage.getInvocationProperty(HDFSConnector.HDFS_FILE_CHECKSUM);
            assertNotNull(fileMd5);

            FileStatus fileStatus = muleMessage.getInvocationProperty(HDFSConnector.HDFS_FILE_STATUS);
            assertFalse(fileStatus.isDir());

            ContentSummary contentSummary = muleMessage.getInvocationProperty(HDFSConnector.HDFS_CONTENT_SUMMARY);
            assertNotNull(contentSummary);

        } catch (Exception e) {
            fail(ConnectorTestUtils.getStackTrace(e));
        }
    }

    @After
    public void tearDown() throws Exception {
        runFlowAndGetMessage("delete-file");
    }

}
