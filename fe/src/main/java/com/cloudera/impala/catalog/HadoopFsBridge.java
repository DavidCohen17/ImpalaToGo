package com.cloudera.impala.catalog;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.BlockStorageLocation;
import org.apache.hadoop.fs.CommonConfigurationKeys;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.security.token.block.InvalidBlockTokenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudera.impala.catalog.FsObject.ObjectState;
import com.cloudera.impala.common.ITPool;
import com.cloudera.impala.common.InterruptableCallable;
import com.cloudera.impala.util.FsKey;

/** Hadoop FileSystem bridge.
 *  Controls FileSystem APIs invocation : handling hung invocation
 */
public class HadoopFsBridge {

  private HadoopFsBridge(){  }

  /** bridged operation status */
  public static enum BridgeOpStatus{
    OK,
    TIMEOUT,
    FAILURE,
    NOT_RUN
  }

  /** exponential base delay, for simplicity we use ( base * 2 * retry_number ) on retry */
  private static final long EXP_DELAY_BASE = 2000;

  /** remote API call permitted timeout */
  private static final long TIMEOUT_BASE = 20000;

  /** number of retries should be attempted while delegated API invocation is timed out */
  private static final int RETRIES = 5;

  /** Managed FileSystem API pool executor */
  private static ITPool pool = new ITPool();

  /** Reference to FileSystems and their objects cache */
  private static final FsObjectCache fsCache = new FsObjectCache();

  /** Logging mechanism */
  private final static Logger LOG = LoggerFactory.getLogger(HadoopFsBridge.class);

  /** Bridged operation result */
  public class BridgeOpResult<T>{

    /** error message */
    String error;

    /** operation result */
    T result;

    /** operation status */
    BridgeOpStatus status;

    /** error getter */
    public String getError() { return error; }

    /** error setter */
    public void setError(String err) { error = err; }

    /** get operation result */
    public T getResult() { return result; }

    /** set operation result*/
    void setResult(T res) { result = res; }

    /** get operation status */
    public BridgeOpStatus getStatus() { return status; }

    /** set operation status */
    void setStatus(BridgeOpStatus stat) { status = stat; }
  }

  /**
   * run the constructed callable in a managed way, controlling its lifecycle
   *
   * @param callable             - callable to invoke
   * @param result               - operation compound result
   * @param timeout              - operation timeout
   * @param messageInterruptedEx - message to print when "InterruptedException" happens
   * @param messageExexEx        - message to print when "ExecutionException" happens
   * @param retries              - number of retries operation will be reinvoked in case was timed out
   *
   * @return operation result
   */
  private static <T> BridgeOpStatus run(InterruptableCallable<T> callable, AtomicReference<BridgeOpResult<T>> result, long timeout,
      String messageInterruptedEx, String messageExexEx, int retries){
    BridgeOpStatus status = null;
    T res = null;

    int countdown = 0;

    BridgeOpResult<T> temp = new HadoopFsBridge().new BridgeOpResult<T>();
    temp.setResult(null);
    temp.setStatus(BridgeOpStatus.NOT_RUN);

    boolean go = true;
    while(go){
      try {
        res = pool.run(callable, timeout);
        temp.setStatus(BridgeOpStatus.OK);
        temp.setResult(res);
        }
      catch (TimeoutException e) {
        temp.setStatus(BridgeOpStatus.TIMEOUT);
        temp.setError(e.getMessage());
        LOG.error(messageInterruptedEx + "Ex : \"" + e.getMessage() + "\"." );
        }
      catch (ExecutionException e) {
        temp.setStatus(BridgeOpStatus.FAILURE);
        temp.setError(e.getMessage());
        LOG.error(messageExexEx + "Ex : \"" + e.getMessage() + "\"; cause = \"" + e.getCause().getMessage() + "\"." );
        }
      catch (InterruptedException e) {
        temp.setStatus(BridgeOpStatus.FAILURE);
        temp.setError(e.getMessage());
        LOG.error(messageExexEx + "Ex : \"" + e.getMessage() + "\"; cause = \"" + e.getCause().getMessage() + "\"." );
      }

      status = temp.getStatus();
      switch(status){
      case OK:
        go = false;
        break;

      case TIMEOUT:
      case FAILURE:
        if(--retries == 0)
          go = false;
        else{
          // sleep a bit and retry
          try {
            Thread.sleep(EXP_DELAY_BASE * (2 * countdown++));
          } catch (InterruptedException e) {
            go = false;
          }
        }
        break;
      default:
        break;
      }
    }
    // reassign output result finally when retries exceeded
    result.set(temp);
    return status;
  }

  /**
   * Execute FileSystem.exists(Path path) in the controlled way
   *
   * @param fs      - hadoop FileSystem
   * @param path    - hadoop Path
   *
   * @return operation compound result, if OK, contain Boolean
   */
  public static BridgeOpResult<Boolean> exists(final FsKey fs, final Path path, boolean force){

    BridgeOpResult<Boolean> res = new HadoopFsBridge().new BridgeOpResult<Boolean>();
    if(!force){
      // check within the cache for requested result:
      Boolean flag = fsCache.getPathExistence(fs, path);

      if (flag != null) {
        res.setStatus(BridgeOpStatus.OK);

        if (flag) {
          res.setResult(true);
          return res;
        }
        res.setResult(false);
        return res;
      }
    }

    // if still here, we have no cached information yet or cached object was not succeed to sync with remote file system
    AtomicReference<BridgeOpResult<Boolean>> result = new AtomicReference<BridgeOpResult<Boolean>>();

    //declaration of the anonymous class
    InterruptableCallable<Boolean> callable = new InterruptableCallable<Boolean>("FileSystem.exists") {
      @Override
      protected Boolean dowork() throws IOException{
        return fs.filesystem.exists(path);
      }
    };

    String messageInterruptedEx = "Timeout exception in \"FileSystem.exists\" operation for \"" + path +
        "\" on filesystem \"" + fs.filesystem.getUri() + "\". ";
    String messageExexEx = "Execution exception in \"FileSystem.exists\" operation for \"" + path +
        "\" on filesystem \"" + fs.filesystem.getUri() + "\". ";

    // run specified task with retries (we only make retries on timed out tasks):
    run(callable, result, TIMEOUT_BASE, messageInterruptedEx, messageExexEx, RETRIES);

    // wait for result to be ready:
    res = result.get();

    // update the cache:
    FsObject.ObjectState state = null;
    switch(res.getStatus()){
    case FAILURE:
    case TIMEOUT:
      state = ObjectState.SYNC_FAILURE;
      break;
    case OK:
      state = res.getResult() ? ObjectState.EXISTS_ORIGIN : ObjectState.DOES_NOT_EXIST_ORIGIN;
      fsCache.setPathStat(fs, path, null, state);
      break;
    default:
      break;
    }

    return res;
  }

  /**
   * Execute Path.getFileSystem(Configuration configuration) in the controlled way
   *
   * @param path          - hadoop Path
   * @param configuration - hadoop Configuration
   *
   * @return operation compound result, if OK, contain the FileSystem
   */
  public static BridgeOpResult<FileSystem> getFilesystem(final Path path, final Configuration configuration){

    // check within the cache for requested result:
    String key = configuration.get(CommonConfigurationKeys.FS_DEFAULT_NAME_KEY, CommonConfigurationKeys.FS_DEFAULT_NAME_DEFAULT);
    FileSystem filesystem = fsCache.getFileSystem(key, path);

    BridgeOpResult<FileSystem> res =  new HadoopFsBridge().new BridgeOpResult<FileSystem>();
    if(filesystem != null){
      res.setResult(filesystem);
      res.setStatus(BridgeOpStatus.OK);
      return res;
    }

    AtomicReference<BridgeOpResult<FileSystem>> result = new AtomicReference<BridgeOpResult<FileSystem>>();

    //declaration of the anonymous class
    InterruptableCallable<FileSystem> callable = new InterruptableCallable<FileSystem>("Path.getFilesystem") {
      @Override
      protected FileSystem dowork() throws IOException, InterruptedException{
        return path.getFileSystem(configuration);
      }
    };

    String messageInterruptedEx = "Timeout exception in \"Path.getFilesystem\" operation for \"" + path + "\". ";
    String messageExexEx = "Execution exception in \"Path.getFilesystem\" operation for \"" + path + "\". ";

    // run specified task with retries (we only make retries on timed out tasks):
    run(callable, result, TIMEOUT_BASE, messageInterruptedEx, messageExexEx, RETRIES);

    // wait for result to be ready:
    res = result.get();

    // update the cache:
    switch(res.getStatus()){
    case FAILURE:
    case TIMEOUT:
      break;
    case OK:
      filesystem = res.getResult();
      fsCache.addFileSystem(key, path, filesystem);
      break;
    default:
      break;
    }

    return res;
  }

  /**
   * Execute FileSystem.listStatus(Path path) in the controlled way
   *
   * @param fs    - hadoop FileSystem
   * @param path  - hadoop Path
   * @param force - flag, indicates that if the operation result is cached, it should be reloaded
   *
   * @return operation compound result, if status is OK, contain list of FileStatus found on the path
   */
  public static BridgeOpResult<FileStatus[]> listStatus(final FsKey fs, final Path path, boolean force){
    LOG.info("listStatus() for \"" + path + "\"");
    // check within the cache for requested result:
    FileStatus[] statistic = fsCache.getDirStat(fs, path);

    BridgeOpResult<FileStatus[]> res =  new HadoopFsBridge().new BridgeOpResult<FileStatus[]>();
    if(statistic != null){
      if(!force){
        res.setResult(statistic);
        res.setStatus(BridgeOpStatus.OK);
        return res;
      }
      // for force reload, reload it - remained part
    }

    AtomicReference<BridgeOpResult<FileStatus[]>> result = new AtomicReference<BridgeOpResult<FileStatus[]>>();

    //declaration of the anonymous class
    InterruptableCallable<FileStatus[]> callable = new InterruptableCallable<FileStatus[]>("FileSystem.listStatus") {
      @Override
      protected FileStatus[] dowork() throws IOException, FileNotFoundException, InterruptedException{
        return fs.filesystem.listStatus(path);
      }
    };

    String messageInterruptedEx = "Timeout exception in \"FileSystem.listStatus\" operation for \"" + path +
        "\" on filesystem \"" + fs.filesystem.getUri() + "\". ";
    String messageExexEx = "Execution exception in \"FileSystem.listStatus\" operation for \"" + path +
        "\" on filesystem \"" + fs.filesystem.getUri() + "\". ";

    // run specified task with retries (we only make retries on timed out tasks):
    run(callable, result, TIMEOUT_BASE, messageInterruptedEx, messageExexEx, RETRIES);

    // wait for result to be ready:
    res = result.get();

    // update the cache on success:
    if(res.getStatus().equals(BridgeOpStatus.OK)){
      statistic = res.getResult();
      fsCache.setPathStat(fs, path, statistic, null);
      }
    else{
      LOG.warn("FileSystem.listStatus() for \"" + path + "\" completed with status \"" + res.getStatus() + "\"");
    }

    return res;
  }

  /**
   * Execute FileSystem.getFileStatus(Path path) in the controlled way
   *
   * @param fs   - hadoop FileSystem
   * @param path - hadoop Path
   *
   * @return operation compound result, if status is OK, contain FileStatus
   */
  public static BridgeOpResult<FileStatus> getFileStatus(final FsKey fs, final Path path){

    LOG.info("\"FileSystem.getFileStatus\" is invoked on \"" + path + "\"");
    // check within the cache for requested result:
    FileStatus statistic = fsCache.getFileStat(fs, path);

    LOG.info("\"FileSystem.getFileStatus\" statistics requested from cache for \"" + path + "\"");

    BridgeOpResult<FileStatus> res =  new HadoopFsBridge().new BridgeOpResult<FileStatus>();
    if(statistic != null){
      res.setResult(statistic);
      res.setStatus(BridgeOpStatus.OK);
      return res;
    }

    // if no cached result so far, this is bug in the outer flow:
    LOG.warn("\"FileSystem.getFileStatus\" is invoked on \"" + path + "\"");

    AtomicReference<BridgeOpResult<FileStatus>> result = new AtomicReference<BridgeOpResult<FileStatus>>();

    //declaration of the anonymous class
    InterruptableCallable<FileStatus> callable = new InterruptableCallable<FileStatus>("FileSystem.getFileStatus") {
      @Override
      protected FileStatus dowork() throws IOException, FileNotFoundException, InterruptedException{
        return fs.filesystem.getFileStatus(path);
      }
    };

    String messageInterruptedEx = "Timeout exception in \"FileSystem.getFileStatus\" operation for \"" + path +
        "\" on filesystem \"" + fs.filesystem.getUri() + "\". ";
    String messageExexEx = "Execution exception in \"FileSystem.getFileStatus\" operation for \"" + path +
        "\" on filesystem \"" + fs.filesystem.getUri() + "\". ";

    // run specified task with retries (we only make retries on timed out tasks):
    run(callable, result, TIMEOUT_BASE, messageInterruptedEx, messageExexEx, RETRIES);

    // wait for result to be ready:
    res = result.get();

    // update the cache on success:
    if(res.getStatus().equals(BridgeOpStatus.OK))
      fsCache.setPathStat(fs, path, new FileStatus[]{res.getResult()}, ObjectState.SYNC_OK);

    return res;
  }

  /**
   * Execute FileSystem.getFileBlockLocations(FileStatus file, long start, long len) in the controlled way
   *
   * @param fs    - Hadoop FileSystem
   * @param file  - Hadoop FileStatus
   * @param start - offset within the file
   * @param len   - length to explore for blocks
   *
   * @return compound result, if status is OK, contains the list of block locations found within the file
   * starting from offset "start" and up to len length
   */
  public static BridgeOpResult<BlockLocation[]> getFileBlockLocations(final FileSystem fs, final FileStatus file,
      final long start, final long len){
    AtomicReference<BridgeOpResult<BlockLocation[]>> result = new AtomicReference<BridgeOpResult<BlockLocation[]>>();

    LOG.info("\"FileSystem.getFileBlockLocations\" is invoked for \"" + file.getPath() + "\".");

    //declaration of the anonymous class
    InterruptableCallable<BlockLocation[]> callable = new InterruptableCallable<BlockLocation[]>("FileSystem.getFileBlockLocations") {
      @Override
      protected BlockLocation[] dowork() throws IOException, InterruptedException{
        return fs.getFileBlockLocations(file, start, len);
      }
    };

    String messageInterruptedEx = "Timeout exception in \"FileSystem.getFileBlockLocations\" operation for \"" + file.getPath() +
        "\" on filesystem \"" + fs.getUri() + "\". ";
    String messageExexEx = "Execution exception in \"FileSystem.getFileBlockLocations\" operation for \"" + file.getPath() +
        "\" on filesystem \"" + fs.getUri() + "\". ";

    // run specified task with retries (we only make retries on timed out tasks):
    run(callable, result, TIMEOUT_BASE, messageInterruptedEx, messageExexEx, RETRIES);
    return result.get();
  }

  /**
   * Execute DistributedFileSystem.getFileBlockStorageLocations(List<BlockLocation> blocks) in the controlled way
   *
   * @param dfs    - Hadoop DistributedFileSystem
   * @param blocks - list of Hadoop BlockLocation
   *
   * @return compound result, if status is OK, contains list of block storage locations for specified set of blocks
   */
  public static BridgeOpResult<BlockStorageLocation[]> getFileBlockStorageLocations(final DistributedFileSystem dfs,
      final List<BlockLocation> blocks){
    AtomicReference<BridgeOpResult<BlockStorageLocation[]>> result = new AtomicReference<BridgeOpResult<BlockStorageLocation[]>>();

    LOG.info("\"FileSystem.getFileBlockStorageLocations\"");

    //declaration of the anonymous class
    InterruptableCallable<BlockStorageLocation[]> callable = new InterruptableCallable<BlockStorageLocation[]>("DistributedFileSystem.getFileBlockStorageLocations") {
      @Override
      protected BlockStorageLocation[] dowork() throws IOException, UnsupportedOperationException,
                                                       InvalidBlockTokenException, InterruptedException {
        return dfs.getFileBlockStorageLocations(blocks);
      }
    };

    String messageInterruptedEx = "Timeout exception in \"DistributedFileSystem.getFileBlockStorageLocations\" operation for"
        + " filesystem \"" + dfs.getUri() + "\". ";
    String messageExexEx = "Execution exception in \"DistributedFileSystem.getFileBlockStorageLocations\" operation for "
        + " filesystem \"" + dfs.getUri() + "\". ";

    // run specified task with retries (we only make retries on timed out tasks):
    run(callable, result, TIMEOUT_BASE, messageInterruptedEx, messageExexEx, RETRIES);
    return result.get();
  }
}

