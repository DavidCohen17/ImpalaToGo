package com.cloudera.impala.catalog;

import java.util.concurrent.ConcurrentHashMap;

import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudera.impala.util.FsKey;
import com.google.common.base.Preconditions;

/** Cache for remote file systems within their state */
public class FsObjectCache {

  /** Logging mechanism */
  private final static Logger LOG = LoggerFactory.getLogger(FsObjectCache.class);

  /** Registry of File System objects constructed from given Configuration and given URI */
  private final ConcurrentHashMap<String, ConcurrentHashMap<Path, FileSystem>> _filesystemsCache =
      new ConcurrentHashMap<String, ConcurrentHashMap<Path, FileSystem>>();

  /** Registry of File System units statistics */
  private final ConcurrentHashMap<FsKey, ConcurrentHashMap<Path, FsObject>> _fsobjectsCache =
      new ConcurrentHashMap<FsKey, ConcurrentHashMap<Path, FsObject>>();

  /**
   * Add FileSystem into the cache if no one exist for given Configuration and a Path
   *
   * @param configuration - configuration to associate FileSystem with
   * @param path          - URI to associate the FileSystem with
   * @param filesystem    - file system object to cache
   *
   * @return the file system object to operate on
   */
  public synchronized FileSystem addFileSystem(String configuration, Path path, FileSystem filesystem) {
    Preconditions.checkNotNull(configuration);
    Preconditions.checkNotNull(path);

    ConcurrentHashMap<Path, FileSystem> existingFsCache =
        _filesystemsCache.putIfAbsent(configuration, new ConcurrentHashMap<Path, FileSystem>());

    existingFsCache = _filesystemsCache.get(configuration);
    existingFsCache.putIfAbsent(path, filesystem);

    return existingFsCache.get(path);
  }

  /**
   * Return true if there's filesystem is cached for given Configuration + URI
   *
   * @param configuration - configuration associated with the file system
   * @param path          - URI associated with the file system
   *
   * @return true if the filesystem is cached for given associations
   */
  public boolean containsFileSystem(String configuration, Path path) {
    return _filesystemsCache.containsKey(configuration) && (_filesystemsCache.get(configuration) != null) &&
        _filesystemsCache.get(configuration).contains(path);
  }

  /**
   * Return the file system associated with the given Configuration and a Path if one found,
   * null otherwise
   *
   * @param configuration - configuration associated with the file system
   * @param path          - URI associated with a file system
   *
   * @return File system object if one found, null otherwise
   */
  public FileSystem getFileSystem(String configuration, Path path) {
    if(_filesystemsCache.containsKey(configuration))
      return  _filesystemsCache.get(configuration).get(path);
    return null;
  }

  /**
   * Removes the filesystem from the cache of configured file systems.
   * Returns the removed item, or null if no item was removed.
   *
   * @param configuration - configuration associated with the File System we need to forget to
   * @param path          - path associated with the file system we need to remove association from
   *
   * @return file system which is not under cache association more
   */
  public synchronized FileSystem removeFileSystem(String configuration, Path path) {
    if(_filesystemsCache.containsKey(configuration))
      return _filesystemsCache.get(configuration).remove(configuration);
    return null;
  }

  /**
   * Set the Path metadata within the given FileSystem into registry
   *
   * @param filesystem   - file system to add the Path metadata for
   * @param path         - path to add the metadata for
   * @param statistic    - path metadata
   * @param state        - state of object
   *
   * @return added (or existing) list of file statuses
   */
  public synchronized void setPathStat(FsKey filesystem, Path path,
      FileStatus[] statistic, FsObject.ObjectState state) {
    Preconditions.checkNotNull(filesystem);
    Preconditions.checkNotNull(path);

    ConcurrentHashMap<Path, FsObject> objectsCache =
        _fsobjectsCache.putIfAbsent(filesystem, new ConcurrentHashMap<Path, FsObject>());

    objectsCache = _fsobjectsCache.get(filesystem);
    FsObject renewed = new FsObject(filesystem, path);
    // set those statistics that were provided:
    if(state != null)
      renewed.setState(state);
    if(statistic != null)
      renewed.setMetadata(statistic);

    renewed = objectsCache.putIfAbsent(path, renewed);
    // if object was present within the cache, it should be updated with the state and file status
    if(renewed != null){
      // set those statistics that were provided:
      if(state != null)
        renewed.setState(state);
      if(statistic != null)
        renewed.setMetadata(statistic);
    }
  }

  /**
   * Get given path metadata within the given file system
   *
   * @param filesystem - file system to get metadata within
   * @param path       - Path to get metafata for (FILE)
   *
   * @return path metadata if Path was cached for FileSystem, null otherwise
   */
  public FileStatus getFileStat(FsKey filesystem, Path path) {
    FsObject fsobject = null;
    // we hold the statistic for file within its parent directory,
    // look for parent location within the given filesystem:
    Path parent = path.getParent();
    if(_fsobjectsCache.containsKey(filesystem)){
      fsobject = _fsobjectsCache.get(filesystem).get(parent != null ? parent : path);
    }
    // ask parent about its file stats:
    return fsobject != null ?
        (parent != null ? fsobject.getChildMetadata(path.toString()) : fsobject.getMetadata()) : null;
  }

  /**
   * Get given directory content metadata within the given file system
   *
   * @param filesystem - file system to get metadata within
   * @param path       - Path to get metadata for (DIRECTORY)
   *
   * @return path metadata if Path was cached for FileSystem, null otherwise
   */
  public FileStatus[] getDirStat(FsKey filesystem, Path path) {
    FsObject fsobject = null;
    if(_fsobjectsCache.containsKey(filesystem))
      fsobject = _fsobjectsCache.get(filesystem).get(path);
    return fsobject != null ? fsobject.getChildrenMetadata() : null;
  }

  /**
   * Check given file status within the given file system
   *
   * @param filesystem - file system to check file status within
   * @param path       - Path to check for status within the specified file system
   *
   * @return file object existence, true if file system object exists within remote origin
   */
  public Boolean getPathExistence(FsKey filesystem, Path path) {
    FsObject fsobject = null;
    if(_fsobjectsCache.containsKey(filesystem))
      fsobject = _fsobjectsCache.get(filesystem).get(path);
    return fsobject != null ? fsobject.getExistance() : null;
  }

}
