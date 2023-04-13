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

package alluxio.client.file.cache;

import alluxio.AlluxioURI;
import alluxio.CloseableSupplier;
import alluxio.PositionReader;
import alluxio.client.file.CacheContext;
import alluxio.client.file.URIStatus;
import alluxio.conf.AlluxioConfiguration;
import alluxio.conf.PropertyKey;
import alluxio.exception.runtime.AlluxioRuntimeException;
import alluxio.exception.runtime.FailedPreconditionRuntimeException;
import alluxio.file.FileId;
import alluxio.file.ReadTargetBuffer;
import alluxio.metrics.MetricKey;
import alluxio.metrics.MetricsSystem;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.base.Ticker;

import java.io.IOException;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Implementation of {@link PositionReader} that reads from a local cache if possible.
 */
@ThreadSafe
public class LocalCachePositionReader implements PositionReader {
  /** Page size in bytes. */
  protected final long mPageSize;

  /** Local store to store pages. */
  private final CacheManager mCacheManager;
  /** Cache related context of this file. */
  private final CacheContext mCacheContext;
  /** External position reader to read data from source and cache. */
  private final CloseableSupplier<PositionReader> mFallbackReader;
  private final FileId mFileId;
  private final long mFileSize;
  private volatile boolean mClosed;

  /**
   * @param conf
   * @param cacheManager
   * @param fallbackReader
   * @param status
   * @return LocalCachePositionReader
   */
  public static LocalCachePositionReader create(AlluxioConfiguration conf,
      CacheManager cacheManager, CloseableSupplier<PositionReader> fallbackReader,
      URIStatus status) {
    // In Dora, the fileId is generated by Worker or by local client, which maybe is not unique.
    // So we use the ufs path hash as its fileId.
    String fileId = conf.getBoolean(PropertyKey.DORA_CLIENT_READ_LOCATION_POLICY_ENABLED)
        ? new AlluxioURI(status.getUfsPath()).hash() :
        Long.toString(status.getFileId());
    return LocalCachePositionReader.create(conf, cacheManager, fallbackReader,
        new FileId.StringId(fileId), status.getLength());
  }

  /**
   * @param conf
   * @param cacheManager
   * @param fallbackReader
   * @param fileId
   * @param fileSize
   * @return LocalCachePositionReader
   */
  public static LocalCachePositionReader create(AlluxioConfiguration conf,
      CacheManager cacheManager, CloseableSupplier<PositionReader> fallbackReader,
      FileId fileId, long fileSize) {
    return new LocalCachePositionReader(conf, cacheManager, fallbackReader,
        fileId, fileSize, null);
  }

  /**
   * @param conf
   * @param cacheManager
   * @param fallbackReader
   * @param fileId
   * @param fileSize
   * @param cacheContext
   * @return LocalCachePositionReader
   */
  public static LocalCachePositionReader create(AlluxioConfiguration conf,
      CacheManager cacheManager, CloseableSupplier<PositionReader> fallbackReader,
      FileId fileId, long fileSize, @Nullable CacheContext cacheContext) {
    return new LocalCachePositionReader(conf, cacheManager, fallbackReader,
        fileId, fileSize, cacheContext);
  }

  private LocalCachePositionReader(AlluxioConfiguration conf, CacheManager cacheManager,
      CloseableSupplier<PositionReader> fallbackReader, FileId fileId, long fileSize,
      @Nullable CacheContext context) {
    mCacheManager = cacheManager;
    mPageSize = conf.getBytes(PropertyKey.USER_CLIENT_CACHE_PAGE_SIZE);
    mFallbackReader = fallbackReader;
    mFileId = fileId;
    mFileSize = fileSize;
    if (conf.getBoolean(PropertyKey.USER_CLIENT_CACHE_QUOTA_ENABLED)
        && context != null) {
      mCacheContext = context;
    } else {
      mCacheContext = CacheContext.defaults();
    }
    Metrics.registerGauges();
  }

  @Override
  public int readInternal(long position, ReadTargetBuffer buffer, int length)
      throws IOException {
    Preconditions.checkArgument(!mClosed, "position reader is closed");
    if (position >= mFileSize) { // at end of file
      return -1;
    }
    Stopwatch stopwatch = createUnstartedStopwatch();
    int totalBytesRead = 0;
    long lengthToRead = Math.min(length, mFileSize - position);
    // used in positionedRead, so make stopwatch a local variable rather than class member
    // for each page, check if it is available in the cache
    while (totalBytesRead < lengthToRead) {
      int bytesRead = localCachedRead(buffer,
          (int) (lengthToRead - totalBytesRead), position, stopwatch);
      totalBytesRead += bytesRead;
      position += bytesRead;
    }
    if (totalBytesRead > length
        || (totalBytesRead < length && position < mFileSize)) {
      throw new IOException(String.format("Invalid number of bytes read - "
              + "bytes to read = %d, actual bytes read = %d, bytes remains in file %d",
          length, totalBytesRead, mFileSize - position));
    }
    return totalBytesRead;
  }

  @Override
  public synchronized void close() throws IOException {
    if (mClosed) {
      return;
    }
    mClosed = true;
    mFallbackReader.close();
  }

  private int localCachedRead(ReadTargetBuffer bytesBuffer, int length,
      long position, Stopwatch stopwatch) {
    long currentPage = position / mPageSize;
    PageId pageId;
    if (mCacheContext.getCacheIdentifier() != null) {
      pageId = new PageId(mCacheContext.getCacheIdentifier(), currentPage);
    } else {
      pageId = new PageId(mFileId.toString(), currentPage);
    }
    int currentPageOffset = (int) (position % mPageSize);
    int bytesLeftInPage = (int) (mPageSize - currentPageOffset);
    int bytesToReadInPage = Math.min(bytesLeftInPage, length);
    return mCacheManager.getAndLoad(pageId, currentPageOffset, bytesToReadInPage,
            bytesBuffer, mCacheContext, stopwatch, () -> readExternalPage(position));
  }

  private byte[] readExternalPage(long position) {
    long pageStart = position - (position % mPageSize);
    int pageSize = (int) Math.min(mPageSize, mFileSize - pageStart);
    byte[] page = new byte[pageSize];
    int totalBytesRead = 0;
    int bytesRead;
    while (totalBytesRead < pageSize) {
      try {
        bytesRead = mFallbackReader.get()
            .read(position, page, totalBytesRead, pageSize - totalBytesRead);
      } catch (IOException e) {
        throw AlluxioRuntimeException.from(e);
      }
      if (bytesRead <= 0) {
        break;
      }
      totalBytesRead += bytesRead;
    }
    // Bytes read from external, may be larger than requests due to reading complete pages
    MetricsSystem.meter(MetricKey.CLIENT_CACHE_BYTES_READ_EXTERNAL.getName()).mark(totalBytesRead);
    if (totalBytesRead != pageSize) {
      throw new FailedPreconditionRuntimeException(
          "Failed to read complete page from external storage. Bytes read: "
          + totalBytesRead + " Page size: " + pageSize);
    }
    return page;
  }

  @VisibleForTesting
  protected Stopwatch createUnstartedStopwatch() {
    return Stopwatch.createUnstarted(Ticker.systemTicker());
  }

  private static final class Metrics {
    // Note that only counter/guage can be added here.
    // Both meter and timer need to be used inline
    // because new meter and timer will be created after {@link MetricsSystem.resetAllMetrics()}

    private static void registerGauges() {
      // Cache hit rate = Cache hits / (Cache hits + Cache misses).
      MetricsSystem.registerGaugeIfAbsent(
          MetricsSystem.getMetricName(MetricKey.CLIENT_CACHE_HIT_RATE.getName()),
          () -> {
            long cacheHits = MetricsSystem.meter(
                MetricKey.CLIENT_CACHE_BYTES_READ_CACHE.getName()).getCount();
            long cacheMisses = MetricsSystem.meter(
                MetricKey.CLIENT_CACHE_BYTES_REQUESTED_EXTERNAL.getName()).getCount();
            long total = cacheHits + cacheMisses;
            if (total > 0) {
              return cacheHits / (1.0 * total);
            }
            return 0;
          });
    }
  }
}
