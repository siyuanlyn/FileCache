//
// Copyright (c) 2013 Nutanix Inc. All rights reserved.
//
// The problem is to implement a file cache in Java that derives the interface
// given below in class FileCache. The typical usage is for a client to call
// 'pinFiles()' to pin a bunch of files in the cache and then either read or
// write to their in-memory contents in the cache. Writing to a cache entry
// makes that entry 'dirty'. Before a dirty entry can be evicted from the
// cache, it must be unpinned and has to be cleaned by writing the
// corresponding data to storage.
//
// All files are assumed to have size 10KB. If a file doesn't exist to begin
// with, it should be created and filled with zeros - the size should be 10KB.
//
// FileCache should be a thread-safe object that can be simultaneously
// accessed by multiple threads. If you are not comfortable with concurrent
// programming, then it may be single-threaded (see alternative in the
// pinFiles() comment). To implement the problem in its entirety may require
// use of third party libraries. For the sake of convenience, it is permissible
// (although not preferred) to substitute external functions with stub
// implementations, but in doing so, please be clear what the intended behavior
// and side effects would be.
//
// The problem has an upper limit of two hours. Note that we'd rather see a
// stable and clean solution that implements a subset of the functionality than
// a complete but buggy one.
//
// If you have any questions, please email both brian@nutanix.com and
// bnc@nutanix.com. If no reply is received in a timely manner, please make a
// judgement call and state your assumptions in the code or response email.

package com.nutanix;

import java.nio.ByteBuffer;
import java.util.Collection;

public abstract class FileCache {
  // Maximum number of files that can be cached at any time.
  protected final int maxCacheEntries;

  // Constructor. 'maxCacheEntries' is the maximum number of files that can
  // be cached at any time.
  protected FileCache(final int maxCacheEntries) {
    this.maxCacheEntries = maxCacheEntries;
  }

  // Pins the given files in vector 'fileNames' in the cache. If any of these
  // files are not already cached, they are first read from the local
  // filesystem. If the cache is full, then some existing cache entries may be
  // evicted. If no entries can be evicted (e.g., if they are all pinned, or
  // dirty), then this method will block until a suitable number of cache
  // entries becomes available. It is OK for more than one thread to pin the
  // same file, however the file should not become unpinned until both pins
  // have been removed.
  //
  // Is is the application's responsibility to ensure that the files may
  // eventually be pinned. For example, if 'max_cache_entries' is 5, an
  // irresponsible client may try to pin 4 files, and then an additional 2
  // files without unpinning any, resulting in the client deadlocking. The
  // implementation *does not* have to handle this.
  //
  // If you are not comfortable with multi-threaded programming or
  // synchronization, this function may be modified to return a boolean if
  // the requested files cannot be pinned due to the cache being full. However,
  // note that entries in 'fileNames' may already be pinned and therefore even
  // a full cache may add additional pins to files.
  abstract void pinFiles(Collection<String> fileNames);

  // Unpin one or more files that were previously pinned. It is ok to unpin
  // only a subset of the files that were previously pinned using pinFiles().
  // It is undefined behavior to unpin a file that wasn't pinned.
  abstract void unpinFiles(Collection<String> fileNames);

  // Provide read-only access to a pinned file's data in the cache. This call
  // should never block (other than temporarily while contending on a lock).
  //
  // It is undefined behavior if the file is not pinned, or to access the
  // buffer when the file isn't pinned.
  abstract ByteBuffer fileData(String fileName);

  // Provide write access to a pinned file's data in the cache. This call marks
  // the file's data as 'dirty'. The caller may update the contents of the file
  // by writing to the memory pointed by the returned value. This call should
  // never block (other than temporarily while contending on a lock).
  //
  // Multiple clients may have access to the data, however the cache *does not*
  // have to worry about synchronizing the clients' accesses (you may assume
  // the application does this correctly).
  //
  // It is undefined behavior if the file is not pinned, or to access the
  // buffer when the file is not pinned.
  abstract ByteBuffer mutableFileData(String fileName);

  // Flushes all dirty buffers. This must be called before removing all
  // references. The cache cannot be used after it has been shut down.
  abstract void shutdown();
}