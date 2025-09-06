package org.danilorossi.mailmanager.helpers;

import lombok.Cleanup;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.java.Log;
import lombok.val;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

@Log
public final class SingleInstanceLock implements AutoCloseable {

  @Getter private final Path lockPath;
  private final FileChannel channel;
  private final FileLock lock;
  private final Thread shutdownHook;

  private SingleInstanceLock(@NonNull final Path lockPath,
                             @NonNull final FileChannel channel,
                             @NonNull final FileLock lock,
                             @NonNull final Thread shutdownHook) {
    this.lockPath = lockPath;
    this.channel = channel;
    this.lock = lock;
    this.shutdownHook = shutdownHook;
  }

  /**
   * Prova ad acquisire un lock esclusivo (non bloccante). Se già esiste, lancia AlreadyRunningException.
   */
  public static SingleInstanceLock acquire() throws IOException {
    return acquire(defaultLockPath());
  }

  /**
   * Prova ad acquisire un lock esclusivo (non bloccante) su un percorso specifico.
   */
  public static SingleInstanceLock acquire(@NonNull final Path lockPath) throws IOException {
    // Il data dir è già garantito da FileSystemUtils.getDataDir().
    @Cleanup val raf = new RandomAccessFile(lockPath.toFile(), "rw");
    val ch = raf.getChannel();

    val fl = ch.tryLock(); // non-blocking
    if (fl == null) {
      // Qualcun altro detiene il lock: proviamo a leggere la nota (senza eccezioni rumorose)
      try {
        val info = FileSystemUtils.readUtf8(lockPath);
        throw new AlreadyRunningException("Lock held. Info: " + info.trim());
      } catch (RuntimeException __) {
        throw new AlreadyRunningException("Lock held by another process.");
      }
    }

    // Lock ottenuto: scriviamo nota informativa (via canale, senza reinventare path/dir)
    ch.truncate(0);
    val note = buildLockNote();
    ch.write(ByteBuffer.wrap(note.getBytes(StandardCharsets.UTF_8)));
    ch.force(true);

    // Rilascio sicuro su shutdown
    val hook = new Thread(() -> {
      try { fl.release(); } catch (Throwable __) {}
      try { ch.close(); }   catch (Throwable __) {}
      try { java.nio.file.Files.deleteIfExists(lockPath); } catch (Throwable __) {}
    }, "single-instance-lock-shutdown");
    Runtime.getRuntime().addShutdownHook(hook);

    return new SingleInstanceLock(lockPath, ch, fl, hook);
  }

  /** Attende fino a maxWaitMillis provando periodicamente ad acquisire il lock. */
  public static SingleInstanceLock acquireWithWait(final long maxWaitMillis, final long pollMillis) throws IOException {
    val deadline = System.currentTimeMillis() + Math.max(0, maxWaitMillis);
    while (true) {
      try {
        return acquire(defaultLockPath());
      } catch (AlreadyRunningException busy) {
        if (System.currentTimeMillis() >= deadline) throw busy;
        try { Thread.sleep(Math.max(10L, pollMillis)); } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw new IOException("Interrupted while waiting for lock", ie);
        }
      } catch (IOException ioe) {
        if (System.currentTimeMillis() >= deadline) throw ioe;
        try { Thread.sleep(Math.max(10L, pollMillis)); } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw new IOException("Interrupted while waiting for lock", ie);
        }
      }
    }
  }

  /** Percorso predefinito del lockfile sotto data/ usando FileSystemUtils. */
  public static Path defaultLockPath() {
    // Consente override con -Dmailmanager.lock=nomefile.lock (sempre dentro data/)
    val override = System.getProperty("mailmanager.lock", "");
    val fileName = (!LangUtils.empty(override)) ? override : FileSystemUtils.LOCK_FILENAME;
    return FileSystemUtils.getDataPath(fileName);
  }

  private static String buildLockNote() {
    long pid = -1L;
    try { pid = ProcessHandle.current().pid(); } catch (Throwable __) {}
    return LangUtils.s("pid={} startedAt={} cmd={}",
        pid, Instant.now(), java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
  }

  @Override
  public void close() {
    try { Runtime.getRuntime().removeShutdownHook(shutdownHook); } catch (IllegalStateException __) { } catch (Throwable __) { }
    try { lock.release(); } catch (Throwable __) { }
    try { channel.close(); } catch (Throwable __) { }
    try { Files.deleteIfExists(lockPath); } catch (Throwable __) { }
  }

  /** Throw quando è già in esecuzione un'altra istanza. */
  public static final class AlreadyRunningException extends RuntimeException {
    public AlreadyRunningException(final String msg) { super(msg); }
  }
}
