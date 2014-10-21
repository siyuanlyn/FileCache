package com.nutanix;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The implement of FileCache
 * 
 * @author siyuanlyn
 *
 */
public class FileCacheImpl extends FileCache {

	// Cache block size - 10KB
	static final int BLOCK_SIZE = 1024 * 10;

	/*
	 * Inner class to restore 10KB cache block
	 */
	class CacheBlock {
		private String fileName;
		// the memory that local files map to
		private ByteBuffer buffer;
		// when pinned by one thread, openCount will increase one
		private AtomicInteger openCount;

		public CacheBlock(String fileName, ByteBuffer inputFileBuffer) {
			this.fileName = fileName;
			buffer = inputFileBuffer;
			openCount = new AtomicInteger(1);
		}

		public String getFileName() {
			return fileName;
		}

		public ByteBuffer getBuffer() {
			return buffer;
		}

		public void setBuffer(ByteBuffer buffer) {
			this.buffer = buffer;
		}

		/*
		 * return deep copy of the cache block and provide read only access
		 */
		public ByteBuffer copyBuffer() {
			ByteBuffer copy = buffer.asReadOnlyBuffer();
			copy.position(0);
			return copy;
		}

		public AtomicInteger getOpenCount() {
			return openCount;
		}

		public void increaseOpenCount() {
			this.openCount.incrementAndGet();
		}

		public int decreaseOpenCount() {
			return this.openCount.decrementAndGet();
		}
	}

	// The main container of cache blocks, keyed by their file name.
	private Map<String, CacheBlock> cache;
	// When a block is unpinned, it will be added to this queue waiting to be
	// evicted. Before it's evicted, it still stays in the cache.
	// If it's pinned again before it's evicted, it will be removed from this
	// queue.
	private ConcurrentLinkedQueue<CacheBlock> unpinnedBlocks;
	// how many slots are still available in the cache.
	private Semaphore availableEntry;

	public FileCacheImpl(int maxCacheEntries) {
		super(maxCacheEntries);
		cache = new HashMap<String, CacheBlock>();
		unpinnedBlocks = new ConcurrentLinkedQueue<CacheBlock>();
		availableEntry = new Semaphore(maxCacheEntries, true);
	}

	@Override
	synchronized void pinFiles(Collection<String> fileNames) {
		for (String fileName : fileNames) {
			try {
				// try to P() the semaphore to get an available slot
				availableEntry.acquire();
				// once permitted to pin, cache is locked so other thread won't
				// mess up the cache with premature pin or unpin.
				synchronized (cache) {
					System.out.println("pin: " + fileName);
					if (cache.containsKey(fileName)) {
						// if it has been pinned before, increase the open count
						// by one.
						cache.get(fileName).increaseOpenCount();
					} else {
						// pin the local file which has not been pinned before
						// into the cache
						RandomAccessFile inputFile = new RandomAccessFile(fileName, "rw");
						FileChannel inputFileChannel = inputFile.getChannel();
						ByteBuffer inputFileBuffer = ByteBuffer.allocate(BLOCK_SIZE);
						inputFileChannel.read(inputFileBuffer);
						// construct the new cache block
						CacheBlock newCacheBlock = new CacheBlock(fileName, inputFileBuffer);
						// pub the new cache block into cache
						cache.put(fileName, newCacheBlock);
						inputFile.close();
					}
				}
			} catch (InterruptedException e) {
				System.err.println("Interrupted while waiting for the pin lock!");
				// Assume that client want to abort these pins.
				return;
			} catch (FileNotFoundException e) {
				// create the inexistent file, initiate the block with demand
				// zero
				System.out.println("Requested file: " + fileName + " does not exist");
				System.out.println("Creating new file: " + fileName);
				File newFile = new File(fileName);
				byte[] demandZero = new byte[BLOCK_SIZE];
				Arrays.fill(demandZero, (byte) 0);
				try {
					newFile.createNewFile();
					FileOutputStream out = new FileOutputStream(fileName);
					out.write(demandZero);
					out.close();
				} catch (IOException e1) {
					System.err.println("Failed to create new file: " + fileName);
					// fail to create new file so this slot is not taken,
					// release it (V() the semaphore).
					availableEntry.release();
					continue;
				}
				ByteBuffer inputFileBuffer = ByteBuffer.allocate(BLOCK_SIZE);
				inputFileBuffer.put(demandZero, 0, BLOCK_SIZE);
				CacheBlock newCacheBlock = new CacheBlock(fileName, inputFileBuffer);
				// put the newly created file into the cache
				cache.put(fileName, newCacheBlock);
			} catch (IOException e) {
				System.err.println("Failed to close the input file: " + fileName);
				availableEntry.release();
			}
		}
	}

	@Override
	void unpinFiles(Collection<String> fileNames) {
		for (String fileName : fileNames) {
			System.out.print("unpin: " + fileName);
			if (cache.containsKey(fileName)) {
				System.out.println(" found!");
				// decrease the open count by one
				int currentCount = cache.get(fileName).decreaseOpenCount();
				// add the unpinned block to the unpinned block queue, so they
				// can be evicted later
				unpinnedBlocks.add(cache.get(fileName));
				// lock the cache in case availableEntry.hasQueuedThreads()
				// returns fake value
				synchronized (cache) {
					// if there is no more slot and some threads are waiting to
					// acquire slots.
					if (currentCount == 0 && availableEntry.hasQueuedThreads()) {
						evict();
					}
				}
			}
		}
	}

	@Override
	ByteBuffer fileData(String fileName) {
		if (cache.containsKey(fileName)) {
			// return the read only copy of the cache block
			return cache.get(fileName).copyBuffer();
		}
		// return null if the file name is not found
		return null;
	}

	@Override
	ByteBuffer mutableFileData(String fileName) {
		if (cache.containsKey(fileName)) {
			// return the reference of the memory of the certain cache block
			return cache.get(fileName).getBuffer();
		}
		// return null if the file name is not found
		return null;
	}

	@Override
	synchronized void shutdown() {
		// flush all the cache blocks in the cache to the local drive
		synchronized (cache) {
			for (CacheBlock cacheBlock : cache.values()) {
				flushCacheBlock(cacheBlock);
			}
		}
	}

	/*
	 * find the oldest unpinned cache block and evict it.
	 */
	void evict() {
		// Because upinned block will not be accessed unless it's pinned again,
		// which will remove the block from this queue,
		// First In First Out here means the LRU
		CacheBlock blockToEvict = unpinnedBlocks.poll();
		System.out.println("evict: " + blockToEvict.getFileName());
		synchronized (cache) {
			// after the block is evicted, remove it from the queue.
			cache.remove(blockToEvict.getFileName());
		}
		// release one slot in the cache (V() the semaphore).
		availableEntry.release();
		// flush the dirty block back to the local drive.
		// We don't know if it's modified by the client, so we don't know
		// if it's really "dirty", so write it back anyway.
		flushCacheBlock(blockToEvict);
	}

	/*
	 * flush the given dirty cache block back to local drive
	 */
	void flushCacheBlock(CacheBlock blockToFlush) {
		try {
			FileOutputStream out = new FileOutputStream(blockToFlush.getFileName());
			out.write(blockToFlush.getBuffer().array());
			out.flush();
			out.close();
		} catch (FileNotFoundException e) {
			System.err.println("Fail to open local file: " + blockToFlush.getFileName());
		} catch (IOException e) {
			System.err.println("Fail to write dirty cache back to file: " + blockToFlush.getFileName());
		}
	}

}
