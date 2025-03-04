/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.master.file.scheduler;

import static alluxio.master.file.scheduler.JobTestUtils.generateRandomFileInfoUnderRoot;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import alluxio.Constants;
import alluxio.exception.AccessControlException;
import alluxio.exception.FileDoesNotExistException;
import alluxio.exception.InvalidPathException;
import alluxio.exception.runtime.InternalRuntimeException;
import alluxio.grpc.JobProgressReportFormat;
import alluxio.grpc.Route;
import alluxio.master.file.FileSystemMaster;
import alluxio.master.job.CopyJob;
import alluxio.master.job.FileIterable;
import alluxio.scheduler.job.JobState;
import alluxio.wire.FileInfo;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

public class CopyJobTest {
  // test CopyJob get next task
  @Test
  public void testGetNextTask()
      throws FileDoesNotExistException, AccessControlException, IOException, InvalidPathException {
    String srcPath = "/src";
    String dstPath = "/dst";
    List<FileInfo> fileInfos = generateRandomFileInfoUnderRoot(5, 20, 64 * Constants.MB, srcPath);
    FileSystemMaster fileSystemMaster = mock(FileSystemMaster.class);
    when(fileSystemMaster.listStatus(any(), any())).thenReturn(fileInfos);
    Optional<String> user = Optional.of("user");
    FileIterable files =
        new FileIterable(fileSystemMaster, srcPath, user, false, CopyJob.QUALIFIED_FILE_FILTER);
    CopyJob copy = new CopyJob(srcPath, dstPath, false, user, "1",
        OptionalLong.empty(), false, false, files);
    Optional<CopyJob.CopyTask> nextTask = copy.getNextTask(null);
    Assert.assertEquals(5, nextTask.get().getRoutes().size());
  }

  @Test
  public void testIsHealthy()
      throws FileDoesNotExistException, AccessControlException, IOException, InvalidPathException {
    String srcPath = "/src";
    String dstPath = "/dst";
    List<FileInfo> fileInfos = generateRandomFileInfoUnderRoot(500, 20, 64 * Constants.MB, srcPath);
    FileSystemMaster fileSystemMaster = mock(FileSystemMaster.class);
    when(fileSystemMaster.listStatus(any(), any())).thenReturn(fileInfos);
    Optional<String> user = Optional.of("user");
    FileIterable files =
        new FileIterable(fileSystemMaster, srcPath, user, false, CopyJob.QUALIFIED_FILE_FILTER);
    CopyJob copy = new CopyJob(srcPath, dstPath, false, user, "1",
        OptionalLong.empty(), false, false, files);
    List<Route> routes = copy.getNextRoutes(100);
    assertTrue(copy.isHealthy());
    routes.forEach(copy::addToRetry);
    assertTrue(copy.isHealthy());
    routes = copy.getNextRoutes(100);
    assertTrue(copy.isHealthy());
    routes.forEach(copy::addToRetry);
    assertFalse(copy.isHealthy());
  }

  @Test
  public void testProgressReport() throws Exception {
    String srcPath = "/src";
    String dstPath = "/dst";
    List<FileInfo> fileInfos = generateRandomFileInfoUnderRoot(500, 20, 64 * Constants.MB, srcPath);
    FileSystemMaster fileSystemMaster = mock(FileSystemMaster.class);
    when(fileSystemMaster.listStatus(any(), any())).thenReturn(fileInfos);
    Optional<String> user = Optional.of("user");
    FileIterable files =
        new FileIterable(fileSystemMaster, srcPath, user, false, CopyJob.QUALIFIED_FILE_FILTER);
    CopyJob job = spy(new CopyJob(srcPath, dstPath, false, user, "1",
        OptionalLong.empty(), false, false, files));
    when(job.getDurationInSec()).thenReturn(0L);
    job.setJobState(JobState.RUNNING);
    List<Route> nextRoutes = job.getNextRoutes(25);
    job.addCopiedBytes(640 * Constants.MB);
    String expectedTextReport = "\tSettings:\tbandwidth: unlimited\tverify: false\n"
        + "\tJob State: RUNNING\n"
        + "\tFiles Processed: 25\n"
        + "\tBytes Copied: 640.00MB out of 31.25GB\n"
        + "\tFiles failure rate: 0.00%\n"
        + "\tFiles Failed: 0\n";
    assertEquals(expectedTextReport, job.getProgress(JobProgressReportFormat.TEXT, false));
    assertEquals(expectedTextReport, job.getProgress(JobProgressReportFormat.TEXT, true));
    String expectedJsonReport = "{\"mVerbose\":false,\"mJobState\":\"RUNNING\","
        + "\"mVerificationEnabled\":false,\"mProcessedFileCount\":25,"
        + "\"mByteCount\":671088640,\"mTotalByteCount\":33554432000,"
        + "\"mFailurePercentage\":0.0,\"mFailedFileCount\":0,\"mFailedFilesWithReasons\":{}}";
    assertEquals(expectedJsonReport, job.getProgress(JobProgressReportFormat.JSON, false));
    job.addFailure(nextRoutes.get(0).getSrc(), "Test error 1", 2);
    job.addFailure(nextRoutes.get(4).getSrc(), "Test error 2", 2);
    job.addFailure(nextRoutes.get(10).getSrc(),  "Test error 3", 2);
    job.failJob(new InternalRuntimeException("test"));
    assertEquals(JobState.FAILED, job.getJobState());
    String expectedTextReportWithError = "\tSettings:\tbandwidth: unlimited\tverify: false\n"
        + "\tJob State: FAILED (alluxio.exception.runtime.InternalRuntimeException: test)\n"
        + "\tFiles Processed: 25\n"
        + "\tBytes Copied: 640.00MB out of 31.25GB\n"
        + "\tFiles failure rate: 12.00%\n"
        + "\tFiles Failed: 3\n";
    assertEquals(expectedTextReportWithError,
        job.getProgress(JobProgressReportFormat.TEXT, false));
    String textReport = job.getProgress(JobProgressReportFormat.TEXT, true);
    assertTrue(textReport.contains("Test error 1"));
    assertTrue(textReport.contains("Test error 2"));
    assertTrue(textReport.contains("Test error 3"));
    String jsonReport = job.getProgress(JobProgressReportFormat.JSON, false);
    assertTrue(jsonReport.contains("FAILED"));
    assertTrue(jsonReport.contains("mFailureReason"));
    assertFalse(jsonReport.contains("Test error 2"));
    jsonReport = job.getProgress(JobProgressReportFormat.JSON, true);
    assertTrue(jsonReport.contains("Test error 1"));
    assertTrue(jsonReport.contains("Test error 2"));
    assertTrue(jsonReport.contains("Test error 3"));
  }
}
