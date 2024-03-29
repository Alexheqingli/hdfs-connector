/*
 * Copyright (c) MuleSoft, Inc. All rights reserved. http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.md file.
 */

package org.mule.modules.hdfs;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.fs.permission.FsPermission;
import org.mule.api.ConnectionException;
import org.mule.api.ConnectionExceptionCode;
import org.mule.api.MuleEvent;
import org.mule.api.MuleRuntimeException;
import org.mule.api.annotations.*;
import org.mule.api.annotations.display.Placement;
import org.mule.api.annotations.param.ConnectionKey;
import org.mule.api.annotations.param.Default;
import org.mule.api.annotations.param.Optional;
import org.mule.api.callback.SourceCallback;
import org.mule.util.CollectionUtils;
import org.mule.util.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Pattern;

import static org.apache.commons.collections.MapUtils.isNotEmpty;
import static org.apache.commons.lang.StringUtils.isBlank;
import static org.apache.commons.lang.StringUtils.isNotBlank;

/**
 * <p>
 * Hadoop Distributed File System (HDFS) Connector.
 * </p>
 * {@sample.config ../../../doc/mule-module-hdfs.xml.sample hdfs:config-1}
 * {@sample.config ../../../doc/mule-module-hdfs.xml.sample hdfs:config-2}
 * {@sample.config ../../../doc/mule-module-hdfs.xml.sample hdfs:config-3}
 *
 * @author MuleSoft Inc.
 */
@Connector(name = HDFSConnector.HDFS, schemaVersion = "3.4", friendlyName = "HDFS", minMuleVersion = "3.5",
        description = "HDFS Connector", metaData = MetaDataSwitch.OFF, connectivityTesting = ConnectivityTesting.DISABLED)
@ReconnectOn(exceptions = IOException.class)
public class HDFSConnector {
    public static final String HDFS = "hdfs";
    public static final String HDFS_PATH_EXISTS = HDFS + ".path.exists";
    public static final String HDFS_FILE_STATUS = HDFS + ".file.status";
    public static final String HDFS_FILE_CHECKSUM = HDFS + ".file.checksum";
    public static final String HDFS_CONTENT_SUMMARY = HDFS + ".content.summary";

    private final static Logger LOGGER = LoggerFactory.getLogger(HDFSConnector.class);
    /**
     * The name of the file system to connect to. It is passed to HDFS client as the
     * {FileSystem#FS_DEFAULT_NAME_KEY} configuration entry. It can be
     * overriden by values in configurationResources and configurationEntries.
     */
    @Configurable
    @Optional
    private String defaultFileSystemName;
    /**
     * A {@link java.util.List} of configuration resource files to be loaded by the HDFS
     * client.
     */
    @Configurable
    @Optional
    @Placement(group = "Advanced")
    private List<String> configurationResources;
    /**
     * A {@link java.util.Map} of configuration entries to be used by the HDFS client.
     */
    @Configurable
    @Optional
    @Placement(group = "Advanced")
    private Map<String, String> configurationEntries;

    private FileSystem fileSystem;

    /**
     * Establish the connection to the Hadoop Distributed File System.
     *
     * @param connectionKey a connection key.
     * @throws ConnectionException Holding one of the possible values in
     *                             {@link ConnectionExceptionCode}.
     */
    @Connect
    public void connect(@ConnectionKey final String connectionKey)
            throws ConnectionException {
        final Configuration configuration = new Configuration();
        if (isNotBlank(defaultFileSystemName)) {
            configuration.set(FileSystem.FS_DEFAULT_NAME_KEY, defaultFileSystemName);
        }

        final boolean hasConfigurationResources = CollectionUtils.isNotEmpty(configurationResources);
        if (hasConfigurationResources) {
            for (final String configurationResource : configurationResources) {
                configuration.addResource(new Path(configurationResource));
            }
        }

        if (isNotEmpty(configurationEntries)) {
            for (final Entry<String, String> configurationEntry : configurationEntries.entrySet()) {
                configuration.set(configurationEntry.getKey(), configurationEntry.getValue());
            }
        }

        try {
            fileSystem = FileSystem.get(configuration);
        } catch (final IOException ioe) {
            throw new ConnectionException(ConnectionExceptionCode.CANNOT_REACH, null, ioe.getMessage(), ioe);
        }

        LOGGER.info("Connected to: " + getFileSystemUri());
    }

    @ConnectionIdentifier
    public String getFileSystemUri() {
        return fileSystem == null ? null : fileSystem.getUri().toString();
    }

    /**
     * Are we connected?
     *
     * @return boolean <i>true</i> if the connection is still valid or <i>false</i>
     * otherwise.
     */
    @ValidateConnection
    public boolean isConnected() {
        try {
            if (fileSystem != null) {
                // ignore the result: an exception will be thrown in case of issue
                fileSystem.listStatus(new Path("/"));
                return true;
            }
        } catch (final IOException ioe) {
            LOGGER.error("Failed to connect to HDFS", ioe);
        }
        return false;
    }

    /**
     * Disconnect from the Hadoop Distributed File System.
     *
     * @throws IOException if there is an issue connecting with the file system.
     */
    @Disconnect
    public void disconnect() throws IOException {
        if (fileSystem != null) {
            try {
                fileSystem.close();
            } finally {
                fileSystem = null;
            }
        }
    }

    /**
     * Read the content of a file designated by its path and streams it to the rest
     * of the flow, while adding the path metadata in the following inbound
     * properties:
     * <ul>
     * <li>{@link HDFSConnector#HDFS_PATH_EXISTS}: a boolean set to true if the path
     * exists</li>
     * <li>{@link HDFSConnector#HDFS_CONTENT_SUMMARY}: an instance of
     * {@link ContentSummary} if the path exists.</li>
     * <li>{@link HDFSConnector#HDFS_FILE_STATUS}: an instance of {@link FileStatus}
     * if the path exists.</li>
     * <li>{@link HDFSConnector#HDFS_FILE_CHECKSUM}: an instance of
     * {@link FileChecksum} if the path exists, is a file and has a checksum.</li>
     * </ul>
     * {@sample.xml ../../../doc/mule-module-hdfs.xml.sample hdfs:read-1}
     * <p/>
     * {@sample.xml ../../../doc/mule-module-hdfs.xml.sample hdfs:read-2}
     *
     * @param path           the path of the file to read.
     * @param bufferSize     the buffer size to use when reading the file.
     * @param sourceCallback the {@link SourceCallback} used to propagate the event
     *                       to the rest of the flow.
     * @return the result from executing the rest of the flow.
     * @throws Exception if any issue occurs during the execution.
     */
    @Source(friendlyName = "Read from path", primaryNodeOnly = true)
    public Object read(final String path,
                       @Default("4096") final int bufferSize,
                       final SourceCallback sourceCallback) throws Exception {
        return runHdfsPathAction(path, new HdfsPathAction<Object>() {
            public Object run(final Path hdfsPath) throws Exception {
                return sourceCallback.process(fileSystem.open(hdfsPath, bufferSize),
                        getPathMetaData(hdfsPath));
            }
        });
    }

    private Map<String, Object> getPathMetaData(final Path hdfsPath) throws IOException {
        final Map<String, Object> metaData = new HashMap<String, Object>();

        final boolean pathExists = fileSystem.exists(hdfsPath);
        metaData.put(HDFS_PATH_EXISTS, pathExists);
        if (!pathExists) {
            return metaData;
        }

        metaData.put(HDFS_CONTENT_SUMMARY, fileSystem.getContentSummary(hdfsPath));

        final FileStatus fileStatus = fileSystem.getFileStatus(hdfsPath);
        metaData.put(HDFS_FILE_STATUS, fileStatus);
        if (fileStatus.isDirectory()) {
            return metaData;
        }

        final FileChecksum fileChecksum = fileSystem.getFileChecksum(hdfsPath);
        if (fileChecksum != null) {
            metaData.put(HDFS_FILE_CHECKSUM, fileChecksum);
        }

        return metaData;
    }

    /**
     * Get the metadata of a path, as described in
     * {@link HDFSConnector#read(String, int, SourceCallback)}, and store it
     * in flow variables.
     * <p/>
     * This flow variables are:
     * <ul>
     * <li>hdfs.path.exists - Indicates if the path exists (true or false)</li>
     * <li>hdfs.content.summary - A resume of the path info</li>
     * <li>hdfs.file.checksum - MD5 digest of the file (if it is a file and exists)</li>
     * <li>hdfs.file.status - A Hadoop object that contains info about the status of the file (org.apache.hadoop.fs.FileStatus</li>
     * </ul>
     * <p/>
     * {@sample.xml ../../../doc/mule-module-hdfs.xml.sample hdfs:get-metadata}
     *
     * @param path      the path whose existence must be checked.
     * @param muleEvent the {@link MuleEvent} currently being processed.
     * @return the result of executing the next message processors if the path
     * exists, otherwise null.
     * @throws Exception if any issue occurs during the execution.
     */
    @Processor(friendlyName = "Get path meta data")
    @Inject
    public void getMetadata(final String path, final MuleEvent muleEvent) throws Exception {
        runHdfsPathAction(path, new VoidHdfsPathAction() {
            public void run(final Path hdfsPath) throws Exception {
                final Map<String, Object> pathMetaData = getPathMetaData(hdfsPath);
                for (final Entry<String, Object> pathMetaDatum : pathMetaData.entrySet()) {
                    muleEvent.setFlowVariable(pathMetaDatum.getKey(), pathMetaDatum.getValue());
                }
            }
        });
    }

    /**
     * Write the current payload to the designated path, either creating a new file
     * or appending to an existing one.
     * <p/>
     * {@sample.xml ../../../doc/mule-module-hdfs.xml.sample hdfs:write-1}
     * <p/>
     * {@sample.xml ../../../doc/mule-module-hdfs.xml.sample hdfs:write-2}
     *
     * @param path           the path of the file to write to.
     * @param permission     the file system permission to use if a new file is created,
     *                       either in octal or symbolic format (umask).
     * @param overwrite      if a pre-existing file should be overwritten with the new
     *                       content.
     * @param bufferSize     the buffer size to use when appending to the file.
     * @param replication    block replication for the file.
     * @param blockSize      the buffer size to use when appending to the file.
     * @param ownerUserName  the username owner of the file.
     * @param ownerGroupName the group owner of the file.
     * @param payload        the payload to write to the file.
     * @throws Exception if any issue occurs during the execution.
     */
    @Processor(friendlyName = "Write to path")
    public void write(final String path,
                      @Default("700") final String permission,
                      @Default("true") final boolean overwrite,
                      @Default("4096") final int bufferSize,
                      @Default("1") final int replication,
                      @Default("1048576") final long blockSize,
                      @Optional final String ownerUserName,
                      @Optional final String ownerGroupName,
                      @Default("#[payload]") final InputStream payload) throws Exception {
        runHdfsPathAction(path, new VoidHdfsPathAction() {
            public void run(final Path hdfsPath) throws Exception {
                final FSDataOutputStream fsDataOutputStream = fileSystem.create(hdfsPath,
                        getFileSystemPermission(permission), overwrite, bufferSize, (short) replication,
                        blockSize, null);
                IOUtils.copyLarge(payload, fsDataOutputStream);
                fsDataOutputStream.hsync();
                IOUtils.closeQuietly(fsDataOutputStream);

                if ((isNotBlank(ownerUserName)) || (isNotBlank(ownerGroupName))) {
                    fileSystem.setOwner(hdfsPath, ownerUserName, ownerGroupName);
                }
            }
        });
    }

    /**
     * Append the current payload to a file located at the designated path.
     * <b>Note:</b> by default the Hadoop server has the append option disabled. In order to be able append any data to an existing file
     * refer to dfs.support.append configuration parameter
     * <p/>
     * {@sample.xml ../../../doc/mule-module-hdfs.xml.sample hdfs:append-1}
     * <p/>
     * {@sample.xml ../../../doc/mule-module-hdfs.xml.sample hdfs:append-2}
     *
     * @param path       the path of the file to write to.
     * @param bufferSize the buffer size to use when appending to the file.
     * @param payload    the payload to append to the file.
     * @throws Exception if any issue occurs during the execution.
     */
    @Processor(friendlyName = "Append to file")
    public void append(final String path,
                       @Default("4096") final int bufferSize,
                       @Default("#[payload]") final InputStream payload) throws Exception {
        runHdfsPathAction(path, new VoidHdfsPathAction() {
            public void run(final Path hdfsPath) throws Exception {
                final FSDataOutputStream fsDataOutputStream = fileSystem.append(hdfsPath, bufferSize);
                IOUtils.copyLarge(payload, fsDataOutputStream);
                IOUtils.closeQuietly(fsDataOutputStream);
            }
        });
    }

    /**
     * Delete the file or directory located at the designated path.
     * <p/>
     * {@sample.xml ../../../doc/mule-module-hdfs.xml.sample hdfs:delete-file}
     *
     * @param path the path of the file to delete.
     * @throws Exception if any issue occurs during the execution.
     */
    @Processor
    public void deleteFile(final String path) throws Exception {
        deletePath(path, false);
    }

    /**
     * Delete the file or directory located at the designated path.
     * <p/>
     * {@sample.xml ../../../doc/mule-module-hdfs.xml.sample hdfs:delete-directory}
     *
     * @param path the path of the directory to delete.
     * @throws Exception if any issue occurs during the execution.
     */
    @Processor
    public void deleteDirectory(final String path) throws Exception {
        deletePath(path, true);
    }

    private void deletePath(final String path, final boolean recursive) throws Exception {
        runHdfsPathAction(path, new VoidHdfsPathAction() {
            public void run(final Path hdfsPath) throws Exception {
                fileSystem.delete(hdfsPath, recursive);
            }
        });
    }

    /**
     * Make the given file and all non-existent parents into directories. Has the
     * semantics of Unix 'mkdir -p'. Existence of the directory hierarchy is not an
     * error.
     * <p/>
     * {@sample.xml ../../../doc/mule-module-hdfs.xml.sample hdfs:make-directories-1}
     * <p/>
     * {@sample.xml ../../../doc/mule-module-hdfs.xml.sample hdfs:make-directories-2}
     *
     * @param path       the path to create directories for.
     * @param permission the file system permission to use when creating the
     *                   directories, either in octal or symbolic format (umask).
     * @throws Exception if any issue occurs during the execution.
     */
    @Processor
    public void makeDirectories(final String path, @Optional final String permission) throws Exception {
        runHdfsPathAction(path, new VoidHdfsPathAction() {
            public void run(final Path hdfsPath) throws Exception {
                fileSystem.mkdirs(hdfsPath, getFileSystemPermission(permission));
            }
        });
    }

    /**
     * Renames path target to path destination.
     * <p/>
     * {@sample.xml ../../../doc/mule-module-hdfs.xml.sample hdfs:rename}
     *
     * @param source the source path to be renamed.
     * @param target the target new path after rename.
     * @return Boolean  true if rename is successful.
     * @throws Exception if any issue occurs during the execution.
     */
    @Processor
    public Boolean rename(final String source, final String target) throws Exception {
        return runHdfsPathAction(source, new HdfsPathAction<Boolean>() {
            public Boolean run(final Path hdfsPath) throws Exception {
                return fileSystem.rename(hdfsPath, new Path(target));
            }
        });
    }

    /**
     * List the statuses of the files/directories in the given path if the path
     * is a directory
     * <p/>
     * {@sample.xml ../../../doc/mule-module-hdfs.xml.sample hdfs:list-status}
     *
     * @param path   the given path
     * @param filter the user supplied path filter
     * @return FileStatus   the statuses of the files/directories in the given path
     * @throws Exception if any issue occurs during the execution.
     */
    @Processor
    public FileStatus[] listStatus(final String path, @Optional final String filter) throws Exception {
        return runHdfsPathAction(path, new HdfsPathAction<FileStatus[]>() {
            public FileStatus[] run(final Path hdfsPath) throws Exception {
                if (StringUtils.isNotEmpty(filter)) {
                    final Pattern pattern = Pattern.compile(filter);
                    PathFilter pathFilter = new PathFilter() {
                        @Override
                        public boolean accept(Path path) {
                            try {
                                if (fileSystem.isDirectory(path))
                                    return true;
                                else {
                                    return pattern.matcher(path.toString()).matches();
                                }
                            } catch (IOException e) {
                                throw new MuleRuntimeException(e);
                            }
                        }
                    };
                    return fileSystem.listStatus(hdfsPath, pathFilter);
                }

                return fileSystem.listStatus(hdfsPath);
            }
        });
    }

    /**
     * Return all the files that match file pattern and are not checksum files. Results
     * are sorted by their names.
     * <p/>
     * {@sample.xml ../../../doc/mule-module-hdfs.xml.sample hdfs:glob-status}
     *
     * @param pathPattern a regular expression specifying the path pattern.
     * @param filter      the user supplied path filter
     * @return FileStatus an array of paths that match the path pattern.
     * @throws Exception if any issue occurs during the execution.
     */
    @Processor
    public FileStatus[] globStatus(final String pathPattern, @Optional final PathFilter filter) throws Exception {
        return runHdfsPathAction(pathPattern, new HdfsPathAction<FileStatus[]>() {
            public FileStatus[] run(final Path hdfsPath) throws Exception {
                if (filter == null) {
                    return fileSystem.globStatus(hdfsPath, new PathFilter() {
                        @Override
                        public boolean accept(Path path) {
                            return true;
                        }
                    });
                }
                return fileSystem.globStatus(hdfsPath, filter);
            }
        });
    }

    /**
     * Copy the source file on the local disk to the FileSystem at the given target
     * path, set deleteSource if the source should be removed.
     * <p/>
     * {@sample.xml ../../../doc/mule-module-hdfs.xml.sample hdfs:copy-from-local-file}
     *
     * @param deleteSource whether to delete the source.
     * @param overwrite    whether to overwrite a existing file.
     * @param source       the source path on the local disk.
     * @param target       the target path on the File System.
     * @throws Exception if any issue occurs during the execution.
     */
    @Processor
    public void copyFromLocalFile(@Default("false") final boolean deleteSource, @Default("true") final boolean overwrite, final String source, final String target) throws Exception {
        runHdfsPathAction(source, new VoidHdfsPathAction() {
            public void run(final Path hdfsPath) throws Exception {
                fileSystem.copyFromLocalFile(deleteSource, overwrite, hdfsPath, new Path(target));
            }
        });
    }

    /**
     * Copy the source file on the FileSystem to local disk at the given target
     * path, set deleteSource if the source should be removed. useRawLocalFileSystem
     * indicates whether to use RawLocalFileSystem as it is a non CRC File System.
     * <p/>
     * {@sample.xml ../../../doc/mule-module-hdfs.xml.sample hdfs:copy-to-local-file}
     *
     * @param deleteSource          whether to delete the source.
     * @param useRawLocalFileSystem whether to use RawLocalFileSystem as local file system or not.
     * @param source                the source path on the File System.
     * @param target                the target path on the local disk.
     * @throws Exception if any issue occurs during the execution.
     */
    @Processor
    public void copyToLocalFile(@Default("false") final boolean deleteSource, @Default("false") final boolean useRawLocalFileSystem, final String source, final String target) throws Exception {
        runHdfsPathAction(source, new VoidHdfsPathAction() {
            public void run(final Path hdfsPath) throws Exception {
                fileSystem.copyToLocalFile(deleteSource, hdfsPath, new Path(target), useRawLocalFileSystem);
            }
        });
    }

    /**
     * Set permission of a path (i.e., a file or a directory).
     * <p/>
     * {@sample.xml ../../../doc/mule-module-hdfs.xml.sample hdfs:set-permission}
     *
     * @param path       the path of the file or directory to set permission.
     * @param permission the file system permission to be set.
     * @throws Exception if any issue occurs during the execution.
     */
    @Processor
    public void setPermission(final String path, final String permission) throws Exception {
        runHdfsPathAction(path, new VoidHdfsPathAction() {
            public void run(final Path hdfsPath) throws Exception {
                fileSystem.setPermission(hdfsPath, getFileSystemPermission(permission));
            }
        });
    }

    /**
     * Set owner of a path (i.e., a file or a directory). The parameters username and groupname
     * cannot both be null.
     * <p/>
     * {@sample.xml ../../../doc/mule-module-hdfs.xml.sample hdfs:set-owner}
     *
     * @param path      the path of the file or directory to set owner.
     * @param ownername If it is null, the original username remains unchanged.
     * @param groupname If it is null, the original groupname remains unchanged.
     * @throws Exception if any issue occurs during the execution.
     */
    @Processor
    public void setOwner(final String path, @Optional final String ownername, @Optional final String groupname) throws Exception {
        runHdfsPathAction(path, new VoidHdfsPathAction() {
            public void run(final Path hdfsPath) throws Exception {
                if ((isNotBlank(ownername)) || (isNotBlank(groupname))) {
                    fileSystem.setOwner(hdfsPath, ownername, groupname);
                }
            }
        });
    }

    private void runHdfsPathAction(final String path, final VoidHdfsPathAction action) throws Exception {
        runHdfsPathAction(path, new HdfsPathAction<Void>() {
            public Void run(final Path hdfsPath) throws Exception {
                action.run(hdfsPath);
                return null;
            }
        });
    }

    private <T> T runHdfsPathAction(final String path, final HdfsPathAction<T> action) throws Exception {
        try {
            final Path hdfsPath = new Path(path);
            return action.run(hdfsPath);
        } catch (final FileNotFoundException fnfe) {
            // FileNotFoundException being an IOException: rethrow it wrapped with
            // another exception to prevent the connection to be invalidated
            throw new MuleRuntimeException(fnfe);
        }
    }

    private FsPermission getFileSystemPermission(final String permission) {
        return isBlank(permission) ? FsPermission.getDefault() : new FsPermission(permission);
    }

    public FileSystem getFileSystem() {
        return fileSystem;
    }

    public void setFileSystem(final FileSystem fileSystem) {
        this.fileSystem = fileSystem;
    }

    public String getDefaultFileSystemName() {
        return defaultFileSystemName;
    }

    public void setDefaultFileSystemName(final String defaultFileSystemName) {
        this.defaultFileSystemName = defaultFileSystemName;
    }

    public List<String> getConfigurationResources() {
        return configurationResources;
    }

    public void setConfigurationResources(final List<String> configurationResources) {
        this.configurationResources = configurationResources;
    }

    public Map<String, String> getConfigurationEntries() {
        return configurationEntries;
    }

    public void setConfigurationEntries(final Map<String, String> configurationEntries) {
        this.configurationEntries = configurationEntries;
    }

    private interface HdfsPathAction<T> {
        T run(Path hdfsPath) throws Exception;
    }

    private interface VoidHdfsPathAction {
        void run(Path hdfsPath) throws Exception;
    }
}
