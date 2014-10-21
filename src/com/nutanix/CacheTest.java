package com.nutanix;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

/**
 * JUnit test for the File Cache implementation
 * 
 * @author siyuanlyn
 *
 */
public class CacheTest {

	// the testing cache
	static FileCache cache;
	// fileList contains only one file name - testFileName
	static List<String> fileList;
	// file name that is used in the single-threaded test
	static String testFileName;
	// newly created testing files that should be deleted
	// once the tests are done
	static List<String> clearList;
	// former time stamp for concurrent test
	static long ts1;
	// latter time stamp for concurrent test
	static long ts2;

	static {
		// assume the cache starts with 5 slots
		cache = new FileCacheImpl(5);
		fileList = new ArrayList<String>();
		clearList = new ArrayList<String>();
		testFileName = "newFile";
		fileList.add(testFileName);
		clearList.addAll(fileList);
	}

	/*
	 * Test to verify the functionality of creating new files when the
	 * requesting files don't exist on the local drive
	 */
	@Test
	public void newFileTest() throws IOException {
		// pin the file that doesn't exist
		cache.pinFiles(fileList);
		// if the file inexistent before is not created, FileNotFound exception
		// will be thrown
		RandomAccessFile newFile = new RandomAccessFile(testFileName, "rw");
		// check the buffer, if it's all demand zero?
		ByteBuffer newFileCacheBuffer = cache.mutableFileData(testFileName);
		Assert.assertNotNull(newFileCacheBuffer);
		byte[] demandZero = new byte[FileCacheImpl.BLOCK_SIZE];
		Arrays.fill(demandZero, (byte) 0);
		Assert.assertArrayEquals(demandZero, newFileCacheBuffer.array());
		newFile.close();
	}

	/*
	 * Test to verify the functionality of reading existing block in the cache
	 */
	@Test
	public void readFileTest() throws IOException {
		// create a new local file first
		File testFile = new File(testFileName);
		testFile.createNewFile();
		FileOutputStream out = new FileOutputStream(testFileName);
		// populate the local file with first 5 byte
		// 0x01, 0x02, 0x03, 0x04, 0x05
		byte[] testBytes = new byte[] { 1, 2, 3, 4, 5 };
		out.write(testBytes);
		out.close();
		// pin the test file
		cache.pinFiles(fileList);
		ByteBuffer testBuffer = cache.fileData(testFileName);
		Assert.assertNotNull(testBuffer);
		byte[] actual = new byte[5];
		testBuffer.get(actual);
		// check if the content in the cache block is consistent with the local
		// file
		Assert.assertArrayEquals(testBytes, actual);
	}

	/*
	 * Test to verify the functionality of writing existing block in the cache
	 */
	@Test
	public void writeFileTest() throws IOException {
		// pin an inexistent file. The cache will create a new one.
		cache.pinFiles(fileList);
		ByteBuffer testBuffer = cache.mutableFileData(testFileName);
		byte[] demandOne = new byte[FileCacheImpl.BLOCK_SIZE];
		Arrays.fill(demandOne, (byte) 1);
		testBuffer.put(demandOne);
		// unpin the file and use shutdown to fluch it back to the local drive
		cache.unpinFiles(fileList);
		cache.shutdown();
		FileInputStream in = new FileInputStream(testFileName);
		byte[] actual = new byte[FileCacheImpl.BLOCK_SIZE];
		in.read(actual);
		in.close();
		// check if the content of the block is correct after the dirty block is
		// written back
		Assert.assertArrayEquals(demandOne, actual);
	}

	/*
	 * Test thread 1 for the concurrent test
	 */
	class Thread1 extends Thread {

		// t2 is the instance of test thread 2 for the concurrent test
		Thread t2;

		public Thread1(Thread thread2) {
			t2 = thread2;
		}

		@Override
		public void run() {
			System.out.println("t1 starts");
			List<String> fileList1 = new ArrayList<String>();
			// thread 1 pin 5 new block first
			fileList1.addAll(Arrays.asList("test1", "test2", "test3", "test4", "test5"));
			// these test files should be cleaned after all tests finished
			clearList.addAll(fileList1);
			// pin 5 new cache block
			cache.pinFiles(fileList1);
			for (int i = 0; i < fileList1.size(); i++) {
				// modified the writing copy of testing cache blocks
				ByteBuffer testBuffer = cache.mutableFileData(fileList1.get(i));
				Assert.assertNotNull(testBuffer);
				byte[] testBytes = new byte[FileCacheImpl.BLOCK_SIZE];
				Arrays.fill(testBytes, (byte) (i + 1));
				testBuffer.put(testBytes);
			}
			System.out.println("t1 pin done");
			// Only after t1 has pinned all the block can t2 start, or the test
			// will end with deadlock because of t2's premature pin
			t2.start();
			List<String> unpinList = new ArrayList<String>(Arrays.asList("test3", "test4", "test5"));
			// sleep for 2 seconds, during which the t2 should block
			try {
				Thread.sleep(2000);
			} catch (InterruptedException e) {
				System.err.println("Sleep interrupted!");
			}
			// upin files, there will be available slots released, t2 should
			// continue
			cache.unpinFiles(unpinList);
			System.out.println("t1 unpin done");
		}
	}

	/*
	 * Test thread 2 for concurrent test
	 */
	class Thread2 extends Thread {

		@Override
		public void run() {
			System.out.println("t2 starts");
			List<String> fileList2 = new ArrayList<String>();
			fileList2.addAll(Arrays.asList("test6", "test7"));
			clearList.addAll(fileList2);
			// get the time stamp before pin
			ts1 = System.currentTimeMillis();
			System.out.println("t2 pin start");
			// should block here until t1 unpins any block
			cache.pinFiles(fileList2);
			// get the time stamp after it finishes two pin
			ts2 = System.currentTimeMillis();
			System.out.println(ts2 - ts1);
			try {
				// test3 should have been evicted and flushed back to local
				// drive as it's the first block that should be evicted
				checkTestFile(3, true);
				// test 4 is the second block that should be evicted and flushed
				// back to local drive
				checkTestFile(4, true);
				// because t2 only pin 2 blocks, so the last block (test5)
				// unpinned by t1 should stay in the cache. In other word, it
				// shouldn't be flushed to local drive
				checkTestFile(5, false);
			} catch (IOException e) {
				System.err.println("Check test file fail!");
			}
		}
	}

	/*
	 * for each test file, check if the content is correct, according to if they
	 * should be flushed to local drive
	 */
	public void checkTestFile(int fileNo, boolean shouldEvict) throws IOException {
		// for the first 5 test files in the concurrent test, every byte of their content
		// should be their file number if flushed to local drive, otherwise should be 
		// demand zero
		if (fileNo <= 5) {
			byte[] testBytes = new byte[FileCacheImpl.BLOCK_SIZE];
			if (shouldEvict) {
				Arrays.fill(testBytes, (byte) (fileNo));
			} else {
				Arrays.fill(testBytes, (byte) (0));
			}
			FileInputStream in;
			in = new FileInputStream("test" + Integer.toString(fileNo));
			byte[] actual = new byte[FileCacheImpl.BLOCK_SIZE];
			in.read(actual);
			in.close();
			Assert.assertArrayEquals(testBytes, actual);
		} else {
			// for test 6 and test 7, they can only be demand zeros
			byte[] testBytes = new byte[FileCacheImpl.BLOCK_SIZE];
			Arrays.fill(testBytes, (byte) (0));
			FileInputStream in;
			in = new FileInputStream("test" + Integer.toString(fileNo));
			byte[] actual = new byte[FileCacheImpl.BLOCK_SIZE];
			in.read(actual);
			in.close();
			Assert.assertArrayEquals(testBytes, actual);
		}
	}

	/*
	 * Check the concurrent correctness of functionalities
	 *   t1 -----------------------t2
	 *   |                          |
	 *   | <-pin test 1~5           |
	 *   | <-----------------------t2.start()
	 *   | |                        |
	 *   | sleep 2 sec              | should be block
	 *   | |                        | because no available
	 *   | ---                      | slots to acquire
	 *   | upin test 3              |
	 *   | <-----------------------pin test 6
	 *   | upin test 4              |
	 *   | <-----------------------pin test 7
	 *   | upin test 5              |
	 */
	@Test
	public void concurrentTest() throws InterruptedException, IOException {
		Thread t2 = new Thread2();
		Thread t1 = new Thread1(t2);
		t1.start();
		t1.join();
		t2.join();
		// check if t2 was blocked during t1's sleeping
		// at that time there should be no available slot 
		// for t2 to require
		Assert.assertTrue(ts2 - ts1 > 2000);
		cache.shutdown();
		// now all the files should be flushed to local drive
		for (int i = 1; i <= 7; i++) {
			checkTestFile(i, true);
		}
	}

	@After
	public void clear() {
		for (String fileName : clearList) {
			File testFile = new File(fileName);
			testFile.delete();
		}
		cache = new FileCacheImpl(5);
	}
}
