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

package alluxio.dora.master.journal.ufs;

import alluxio.dora.conf.Configuration;
import alluxio.dora.conf.PropertyKey;
import alluxio.dora.exception.JournalClosedException;
import alluxio.dora.exception.status.CancelledException;
import alluxio.dora.exception.status.UnavailableException;
import alluxio.dora.master.Master;
import alluxio.dora.master.journal.AbstractCatchupThread;
import alluxio.dora.master.journal.AsyncJournalWriter;
import alluxio.dora.master.journal.CatchupFuture;
import alluxio.dora.master.journal.Journal;
import alluxio.dora.master.journal.JournalContext;
import alluxio.dora.master.journal.JournalReader;
import alluxio.dora.master.journal.JournalUtils;
import alluxio.dora.master.journal.MasterJournalContext;
import alluxio.dora.master.journal.sink.JournalSink;
import alluxio.dora.metrics.MetricKey;
import alluxio.dora.metrics.MetricsSystem;
import alluxio.proto.journal.Journal.JournalEntry;
import alluxio.dora.resource.CloseableResource;
import alluxio.dora.retry.ExponentialTimeBoundedRetry;
import alluxio.dora.retry.RetryPolicy;
import alluxio.dora.underfs.UfsStatus;
import alluxio.dora.underfs.UnderFileSystem;
import alluxio.dora.underfs.UnderFileSystemConfiguration;
import alluxio.dora.underfs.options.DeleteOptions;
import alluxio.dora.util.URIUtils;
import alluxio.dora.util.UnderFileSystemUtils;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Implementation of UFS-based journal.
 *
 * The journal is made up of 2 components:
 * - The checkpoint:  a snapshot of the master state
 * - The log entries: incremental entries to apply to the checkpoint.
 *
 * The journal log entries must be self-contained. Checkpoint is considered as a compaction of
 * a set of journal log entries. If the master does not do any checkpoint, the journal should
 * still be sufficient.
 *
 * Journal file structure:
 * journal_folder/version/logs/StartSequenceNumber-EndSequenceNumber
 * journal_folder/version/checkpoints/0-EndSequenceNumber
 * journal_folder/version/.tmp/random_id
 */
@ThreadSafe
public class UfsJournal implements Journal {
  private static final Logger LOG = LoggerFactory.getLogger(UfsJournal.class);
  /**
   * This is set to Long.MAX_VALUE such that the current log can be sorted after any other
   * completed logs.
   */
  public static final long UNKNOWN_SEQUENCE_NUMBER = Long.MAX_VALUE;
  /** The journal version. */
  public static final String VERSION = "v1";

  /** Directory for journal edit logs including the incomplete log file. */
  private static final String LOG_DIRNAME = "logs";
  /** Directory for committed checkpoints. */
  private static final String CHECKPOINT_DIRNAME = "checkpoints";
  /** Directory for temporary files. */
  private static final String TMP_DIRNAME = ".tmp";

  private final URI mLogDir;
  private final URI mCheckpointDir;
  private final URI mTmpDir;

  /** The location where this journal is stored. */
  private final URI mLocation;
  /** The master managed by this journal. */
  private final Master mMaster;
  /** The UFS where the journal is being written to. */
  private final UnderFileSystem mUfs;
  /** The amount of time to wait to pass without seeing a new journal entry when gaining primacy. */
  private final long mQuietPeriodMs;
  /** The current log writer. Null when in standby mode. */
  private UfsJournalLogWriter mWriter;
  /** Asynchronous journal writer. */
  private volatile AsyncJournalWriter mAsyncWriter;
  /**
   * Thread for tailing the journal, taking snapshots, and applying updates to the state machine.
   * Null when in primary mode.
   */
  private UfsJournalCheckpointThread mTailerThread;

  /** Whether the journal is suspended. */
  private volatile boolean mSuspended = false;
  /** Store where the journal was suspended. */
  private volatile long mSuspendSequence = -1;
  /** Used to store latest catch-up task. */
  private volatile AbstractCatchupThread mCatchupThread;
  /** Used to stop catching up when cancellation requested.  */
  private volatile boolean mStopCatchingUp = false;

  private long mLastCheckPointTime = -1;
  private long mEntriesSinceLastCheckPoint = 0;

  private enum State {
    STANDBY, PRIMARY, CLOSED
  }

  private final AtomicReference<State> mState = new AtomicReference<>(State.STANDBY);

  /** A supplier of journal sinks for this journal. */
  private final Supplier<Set<JournalSink>> mJournalSinks;

  /**
   * @return the ufs configuration to use for the journal operations
   */
  public static UnderFileSystemConfiguration getJournalUfsConf() {
    Map<String, Object> ufsConf =
        Configuration.getNestedProperties(PropertyKey.MASTER_JOURNAL_UFS_OPTION);
    return UnderFileSystemConfiguration.defaults(Configuration.global())
               .createMountSpecificConf(ufsConf);
  }

  /**
   * Creates a new instance of {@link UfsJournal}.
   *
   * @param location the location for this journal
   * @param master the master to manage
   * @param quietPeriodMs the amount of time to wait to pass without seeing a new journal entry when
   *        gaining primacy
   * @param journalSinks a supplier for journal sinks
   */
  public UfsJournal(URI location, Master master, long quietPeriodMs,
      Supplier<Set<JournalSink>> journalSinks) {
    try (CloseableResource<UnderFileSystem> ufs =
             master.getMasterContext().getUfsManager().getJournal(location).acquireUfsResource()) {
      mLocation = URIUtils.appendPathOrDie(location, VERSION);
      mMaster = master;
      mUfs = ufs.get();
      mQuietPeriodMs = quietPeriodMs;

      mLogDir = URIUtils.appendPathOrDie(mLocation, LOG_DIRNAME);
      mCheckpointDir = URIUtils.appendPathOrDie(mLocation, CHECKPOINT_DIRNAME);
      mTmpDir = URIUtils.appendPathOrDie(mLocation, TMP_DIRNAME);
      mJournalSinks = journalSinks;
      init();
    }
  }

  @VisibleForTesting
  UfsJournal(URI location, Master master, UnderFileSystem ufs,
      long quietPeriodMs, Supplier<Set<JournalSink>> journalSinks) {
    mLocation = URIUtils.appendPathOrDie(location, VERSION);
    mMaster = master;
    mUfs = ufs;
    mQuietPeriodMs = quietPeriodMs;

    mLogDir = URIUtils.appendPathOrDie(mLocation, LOG_DIRNAME);
    mCheckpointDir = URIUtils.appendPathOrDie(mLocation, CHECKPOINT_DIRNAME);
    mTmpDir = URIUtils.appendPathOrDie(mLocation, TMP_DIRNAME);
    mJournalSinks = journalSinks;
    init();
  }

  protected void init() {
    mState.set(State.STANDBY);
    MetricsSystem.registerGaugeIfAbsent(
        MetricKey.MASTER_JOURNAL_ENTRIES_SINCE_CHECKPOINT.getName() + "." + mMaster.getName(),
        this::getEntriesSinceLastCheckPoint);
    MetricsSystem.registerGaugeIfAbsent(
        MetricKey.MASTER_JOURNAL_LAST_CHECKPOINT_TIME.getName() + "." + mMaster.getName(),
        this::getLastCheckPointTime);
  }

  @Override
  public URI getLocation() {
    return mLocation;
  }

  private synchronized long getEntriesSinceLastCheckPoint() {
    return mEntriesSinceLastCheckPoint;
  }

  private synchronized long getLastCheckPointTime() {
    return mLastCheckPointTime;
  }

  /**
   * Writes a journal entry.
   * This is only used by tests where journal entries are manually written.
   * In the real logic the flush is handled by the {@link AsyncJournalWriter}
   * and no direct access to the {@link UfsJournalLogWriter}.
   * @param entry an entry to write to the journal
   */
  @VisibleForTesting
  synchronized void write(JournalEntry entry) throws IOException, JournalClosedException {
    writer().write(entry);
    mEntriesSinceLastCheckPoint++;
  }

  /**
   * Flushes the journal.
   * This is only used by tests where journal entries are manually flushed.
   * In the real logic the flush is handled by the {@link AsyncJournalWriter}
   * and no direct access to the {@link UfsJournalLogWriter}.
   */
  @VisibleForTesting
  public synchronized void flush() throws IOException, JournalClosedException {
    writer().flush();
  }

  @Override
  public synchronized JournalContext createJournalContext()
      throws UnavailableException {
    if (mState.get() != State.PRIMARY) {
      // We throw UnavailableException here so that clients will retry with the next primary master.
      throw new UnavailableException(
          mMaster.getName() + ": Not allowed to write to journal in state: " + mState.get());
    }
    AsyncJournalWriter writer = mAsyncWriter;
    if (writer == null) {
      throw new UnavailableException(
          mMaster.getName() + ": Failed to write to journal: journal is shutdown.");
    }
    return new MasterJournalContext(writer);
  }

  private synchronized UfsJournalLogWriter writer() {
    Preconditions.checkState(mState.get() == State.PRIMARY,
        "Cannot write to the journal in state " + mState.get());
    return mWriter;
  }

  /**
   * Starts the journal in standby mode.
   */
  public synchronized void start() {
    mMaster.resetState();
    mTailerThread = new UfsJournalCheckpointThread(mMaster, this, mJournalSinks);
    mTailerThread.start();
  }

  /**
   * Transitions the journal from standby to primary mode. The journal will apply the latest
   * journal entries to the state machine, then begin to allow writes.
   */
  public synchronized void gainPrimacy() throws IOException {
    Preconditions.checkState(mWriter == null, "writer must be null in standby mode");
    Preconditions.checkState(mSuspended || mTailerThread != null,
        "tailer thread must not be null in standby mode");

    // Resume first if suspended.
    if (mSuspended) {
      resume();
    }

    // If the tailing thread has crashed, the standby master will crash here
    // instead of gaining primacy
    mTailerThread.awaitTermination(true);
    long nextSequenceNumber = mTailerThread.getNextSequenceNumber();
    mTailerThread = null;
    // Read all the rest of journal entries if any, in the main thread
    nextSequenceNumber = catchUp(nextSequenceNumber);
    mWriter = new UfsJournalLogWriter(this, nextSequenceNumber);
    mAsyncWriter = new AsyncJournalWriter(mWriter, mJournalSinks, mMaster.getName());
    mState.set(State.PRIMARY);
    LOG.info("{}: journal switched to primary mode. location: {}", mMaster.getName(), mLocation);
  }

  /**
   * Notifies this journal that it is no longer primary. After this returns, the journal will not
   * allow any writes.
   *
   * The method {@link #awaitLosePrimacy()} must be called afterwards to complete the transition
   * from primary.
   */
  public synchronized void signalLosePrimacy() {
    Preconditions
        .checkState(mState.get() == State.PRIMARY, "unexpected journal state " + mState.get());
    mState.set(State.STANDBY);
    LOG.info("{}: journal switched to standby mode, starting transition. location: {}",
        mMaster.getName(), mLocation);
  }

  /**
   * Transitions the journal from primary to standby mode. The journal will no longer allow
   * writes, and the state machine is rebuilt from the journal and kept up to date.
   *
   * This must be called after {@link #signalLosePrimacy()} to finish the transition from primary.
   */
  public synchronized void awaitLosePrimacy() {
    Preconditions.checkState(mState.get() == State.STANDBY,
        "Should already be set to STANDBY state. unexpected state: " + mState.get());
    Preconditions.checkState(mWriter != null, "writer thread must not be null in primary mode");
    Preconditions.checkState(mTailerThread == null, "tailer thread must be null in primary mode");

    // Close async writer first to flush pending entries.
    mAsyncWriter.close();
    mAsyncWriter = null;
    mWriter.close();
    mWriter = null;
    mMaster.resetState();
    mTailerThread = new UfsJournalCheckpointThread(mMaster, this, mJournalSinks);
    mTailerThread.start();
  }

  /**
   * Suspends applying this journal until resumed.
   */
  public synchronized void suspend() {
    Preconditions.checkState(!mSuspended, "journal is already suspended");
    Preconditions.checkState(mState.get() == State.STANDBY, "unexpected state " + mState.get());
    Preconditions.checkState(mSuspendSequence == -1, "suspend sequence already set");
    // The standby suspends first in order to take a snapshot/backup
    // So if the tailing thread has crashed before that,
    // the crash will propagate and kill the standby master here
    mTailerThread.awaitTermination(false);
    mSuspendSequence = mTailerThread.getNextSequenceNumber() - 1;
    mTailerThread = null;
    mSuspended = true;
  }

  /**
   * Initiates catching up of the journal up to given sequence.
   * Note: Journal should have been suspended prior to calling this.
   *
   * @param sequence sequence to catch up
   * @return the catch-up task
   */
  public synchronized CatchupFuture catchup(long sequence) {
    Preconditions.checkState(mSuspended, "journal is not suspended");
    Preconditions.checkState(mState.get() == State.STANDBY, "unexpected state %s", mState.get());
    Preconditions.checkState(mTailerThread == null, "tailer is not null");
    Preconditions.checkState(sequence >= mSuspendSequence, "can't catch-up before suspend");
    Preconditions.checkState(mCatchupThread == null || !mCatchupThread.isAlive(),
        "Catch-up thread active");

    // Return completed if already at target.
    if (sequence == mSuspendSequence) {
      return CatchupFuture.completed();
    }

    // Create an async task to catch up to target sequence.
    // The thread is closed when the Future is completed
    mCatchupThread = new UfsJournalCatchupThread(mSuspendSequence + 1, sequence);
    mCatchupThread.start();
    return new CatchupFuture(mCatchupThread);
  }

  /**
   * Resumes the journal.
   * Note: Journal should have been suspended prior to calling this.
   */
  public synchronized void resume() {
    Preconditions.checkState(mSuspended, "journal is not suspended");
    Preconditions.checkState(mState.get() == State.STANDBY, "unexpected state " + mState.get());
    Preconditions.checkState(mTailerThread == null, "tailer is not null");

    // Cancel and wait for active catch-up thread.
    // If the catch-up thread crashed silently, the next catchup() will just create another one
    if (mCatchupThread != null && mCatchupThread.isAlive()) {
      mCatchupThread.cancel();
      mCatchupThread.waitTermination();
      mCatchupThread = null;
      mStopCatchingUp = false;
    }

    mTailerThread =
        new UfsJournalCheckpointThread(mMaster, this, mSuspendSequence + 1, mJournalSinks);
    mTailerThread.start();
    mSuspendSequence = -1;
    mSuspended = false;
  }

  /**
   * @return the quiet period for this journal
   */
  public long getQuietPeriodMs() {
    return mQuietPeriodMs;
  }

  /**
   * @param readIncompleteLogs whether the reader should read the latest incomplete log
   * @return a reader for reading from the start of the journal
   */
  public UfsJournalReader getReader(boolean readIncompleteLogs) {
    return new UfsJournalReader(this, readIncompleteLogs);
  }

  /**
   * @param checkpointSequenceNumber the next sequence number after the checkpoint
   * @return a writer for writing a checkpoint
   */
  public UfsJournalCheckpointWriter getCheckpointWriter(long checkpointSequenceNumber)
      throws IOException {
    return UfsJournalCheckpointWriter.create(this, checkpointSequenceNumber);
  }

  /**
   * @return the next sequence number to write
   */
  public long getNextSequenceNumberToWrite() {
    return writer().getNextSequenceNumber();
  }

  /**
   * @return the first log sequence number that hasn't yet been checkpointed
   */
  public long getNextSequenceNumberToCheckpoint() throws IOException {
    return UfsJournalSnapshot.getNextLogSequenceNumberToCheckpoint(this);
  }

  /**
   * @return whether the journal has been formatted
   */
  public boolean isFormatted() {
    try {
      UfsStatus[] files = mUfs.listStatus(mLocation.toString());
      if (files == null) {
        return false;
      }
      // Search for the format file.
      String filePrefix = Configuration.getString(PropertyKey.MASTER_FORMAT_FILE_PREFIX);
      return Arrays.stream(files).anyMatch(file -> file.getName().startsWith(filePrefix));
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * @return true if the journal is allowed to be written to
   */
  public boolean isWritable() {
    return mState.get() == State.PRIMARY;
  }

  /**
   * Formats the journal.
   */
  public void format() throws IOException {
    URI location = getLocation();
    LOG.info("Formatting {}", location);
    if (mUfs.isDirectory(location.toString())) {
      for (UfsStatus status : Objects.requireNonNull(mUfs.listStatus(location.toString()))) {
        String childPath = URIUtils.appendPathOrDie(location, status.getName()).toString();
        if (status.isDirectory()
            && !mUfs.deleteDirectory(childPath, DeleteOptions.defaults().setRecursive(true))
            || status.isFile() && !mUfs.deleteFile(childPath)) {
          throw new IOException(String.format("Failed to delete %s", childPath));
        }
      }
    } else if (!mUfs.mkdirs(location.toString())) {
      throw new IOException(String.format("Failed to create %s", location));
    }

    // Create a breadcrumb that indicates that the journal folder has been formatted.
    UnderFileSystemUtils.touch(mUfs, URIUtils.appendPathOrDie(location,
        Configuration.getString(
            PropertyKey.MASTER_FORMAT_FILE_PREFIX) + System.currentTimeMillis())
        .toString());
  }

  /**
   * Creates a checkpoint in this ufs journal.
   */
  public synchronized void checkpoint() throws IOException {
    long nextSequenceNumber = getNextSequenceNumberToWrite();
    if (nextSequenceNumber == getNextSequenceNumberToCheckpoint()) {
      LOG.info("{}: No entries have been written since the last checkpoint.",
          mMaster.getName());
      return;
    }
    try (UfsJournalCheckpointWriter journalWriter
             = getCheckpointWriter(nextSequenceNumber)) {
      LOG.info("{}: Writing checkpoint [sequence number {}].",
          mMaster.getName(), nextSequenceNumber);
      mMaster.writeToCheckpoint(journalWriter);
      LOG.info("{}: Finished checkpoint [sequence number {}].",
          mMaster.getName(), nextSequenceNumber);
      mEntriesSinceLastCheckPoint = 0;
      mLastCheckPointTime = System.currentTimeMillis();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CancelledException(mMaster.getName() + ": Checkpoint is interrupted");
    }
  }

  /**
   * @return the state of the master process journal catchup
   */
  public synchronized UfsJournalCheckpointThread.CatchupState getCatchupState() {
    if (mTailerThread == null) {
      // tailer thread not active yet
      return UfsJournalCheckpointThread.CatchupState.NOT_STARTED;
    }
    return mTailerThread.getCatchupState();
  }

  /**
   * @return the log directory location
   */
  @VisibleForTesting
  public URI getLogDir() {
    return mLogDir;
  }

  /**
   * @return the checkpoint directory location
   */
  URI getCheckpointDir() {
    return mCheckpointDir;
  }

  /**
   * @return the temporary directory location
   */
  URI getTmpDir() {
    return mTmpDir;
  }

  /**
   * @return the under file system instance
   */
  UnderFileSystem getUfs() {
    return mUfs;
  }

  /**
   * Reads and applies all journal entries starting from the specified sequence number.
   *
   * @param nextSequenceNumber the sequence number to continue catching up from
   * @return the next sequence number after the final sequence number read
   */
  private synchronized long catchUp(long nextSequenceNumber) {
    return catchUp(nextSequenceNumber, -1);
  }

  /**
   * Reads and applies journal entries starting from the specified sequence number upto given limit.
   *
   * @param nextSequenceNumber the sequence number to continue catching up from
   * @param endSequenceNumber the inclusive sequence number to end catching up
   * @return the next sequence number after the final sequence number read
   */
  private long catchUp(long nextSequenceNumber, long endSequenceNumber) {
    JournalReader journalReader = new UfsJournalReader(this, nextSequenceNumber, true);
    try {
      return catchUp(journalReader, endSequenceNumber);
    } finally {
      try {
        journalReader.close();
      } catch (IOException e) {
        LOG.warn("Failed to close journal reader: {}", e.toString());
      }
    }
  }

  /**
   * This method only throws unchecked exceptions like {@code RuntimeException}.
   * If journal replay fails due to corrupted journal contents, this method will crash the master.
   * If the journal cannot be accessed due to IOException, this method will retry forever.
   * If an exception is thrown from this method that suggests an uncaught exception
   * or the master is failing over or shutting down.
   */
  private long catchUp(JournalReader journalReader, long limit) {
    RetryPolicy retry =
        ExponentialTimeBoundedRetry.builder()
            .withInitialSleep(Duration.ofSeconds(1))
            .withMaxSleep(Duration.ofSeconds(10))
            .withMaxDuration(Duration.ofDays(365))
            .build();
    while (true) {
      // Finish catching up, if reader is beyond given limit.
      if (limit != -1 && journalReader.getNextSequenceNumber() > limit) {
        return journalReader.getNextSequenceNumber();
      }
      if (mStopCatchingUp) {
        return journalReader.getNextSequenceNumber();
      }
      try {
        switch (journalReader.advance()) {
          case CHECKPOINT:
            mMaster.restoreFromCheckpoint(journalReader.getCheckpoint());
            break;
          case LOG:
            JournalEntry entry = journalReader.getEntry();
            try {
              if (!mMaster.processJournalEntry(entry)) {
                JournalUtils
                    .handleJournalReplayFailure(LOG, null, "%s: Unrecognized journal entry: %s",
                        mMaster.getName(), entry);
              } else {
                JournalUtils.sinkAppend(mJournalSinks, entry);
              }
            }  catch (Throwable t) {
              JournalUtils.handleJournalReplayFailure(LOG, t,
                    "%s: Failed to process journal entry %s", mMaster.getName(), entry);
            }
            break;
          default:
            return journalReader.getNextSequenceNumber();
        }
      } catch (IOException e) {
        LOG.warn("{}: Failed to read from journal: {}", mMaster.getName(), e);
        if (retry.attempt()) {
          continue;
        }
        throw new RuntimeException(
            String.format("%s: failed to catch up journal", mMaster.getName()), e);
      }
    }
  }

  @Override
  public String toString() {
    return "UfsJournal(" + mLocation + ")";
  }

  @Override
  public synchronized void close() {
    if (mAsyncWriter != null) {
      mAsyncWriter.close();
      mAsyncWriter = null;
    }
    if (mWriter != null) {
      mWriter.close();
      mWriter = null;
    }
    if (mTailerThread != null) {
      try {
        // If the tailing thread has crashed before the close,
        // an exception will be thrown, containing what has originally caused the crash
        mTailerThread.awaitTermination(false);
      } catch (Throwable t) {
        // We want to let the thread finish normally, however this call might throw if it already
        // finished exceptionally. We do not rethrow as we want the shutdown sequence to be smooth
        // (aka not throw exceptions).
        LOG.warn("exception caught when closing {}'s journal", mMaster.getName(), t);
      }
      mTailerThread = null;
    }
    mState.set(State.CLOSED);
  }

  /**
   * UFS implementation for {@link AbstractCatchupThread}.
   * This thread is only used on the standby master to catch up before taking a backup.
   */
  class UfsJournalCatchupThread extends AbstractCatchupThread {
    /** Where to start catching up. */
    private final long mCatchUpStartSequence;
    /** Where to end catching up. */
    private final long mCatchUpEndSequence;

    /**
     * Creates UFS catch-up thread for given range.
     *
     * @param start start sequence (inclusive)
     * @param end end sequence (inclusive)
     */
    public UfsJournalCatchupThread(long start, long end) {
      mCatchUpStartSequence = start;
      mCatchUpEndSequence = end;
      setName(String.format("ufs-catchup-thread-%s", mMaster.getName()));
    }

    @Override
    public void cancel() {
      // Used by catchup() to bail early.
      mStopCatchingUp = true;
    }

    @Override
    protected void runCatchup() {
      // Update suspended sequence after catch-up is finished.
      mSuspendSequence = catchUp(mCatchUpStartSequence, mCatchUpEndSequence) - 1;
    }
  }
}