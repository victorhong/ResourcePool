package ResourcePool;

import static org.junit.Assert.*;
import java.util.ArrayList;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.Before;
import org.junit.Test;

public class ResourcePoolTest {
	FairResourcePool<Pair<String, StringBuffer>> bp;
	
	// class for a producer thread
	class MessageWriter extends Thread {
		private ResourcePool<Pair<String, StringBuffer>> buffers;
		private int maxNumOfAttempts = 0; 
		private long bufferHoldingTime = 0;
		private long timeBetweenAttempts = 0;
		private int numOfAttempts = 0;
		
		public MessageWriter(ResourcePool<Pair<String, StringBuffer>> buffers, int maxNumOfAttempts, long bufferHoldingTime, long timeBetweenAttepmts) {
			this.buffers = buffers;
			this.maxNumOfAttempts = maxNumOfAttempts;
			this.bufferHoldingTime = bufferHoldingTime;
			this.timeBetweenAttempts = timeBetweenAttempts;
		}
		public void run() {
			for(int i = 0; i < maxNumOfAttempts; i++) {
				System.out.println("writer "+Thread.currentThread().getName()+" ready for write attempt "+i);
				Pair<String, StringBuffer> b = null;
				try {
					b = buffers.acquire();
				} catch (InterruptedException e) {
					System.out.println("writer "+Thread.currentThread().getName()+" was interrupted");
					System.out.println("writer "+Thread.currentThread().getName()+" acquire return ");
							continue;
				}
				if (b == null) {
					System.out.println("writer "+Thread.currentThread().getName()+" acquire returned null");
					return;
				}
				
				// if buffer is empty, we can write a message
				if (b.getValue().length() == 0) {
					// write to the buffer
					String m = Thread.currentThread().getName() + ", Message-"+i;
					b.getValue().replace(0, 256, m);
					System.out.println(Thread.currentThread().getName()+ " wrote in buffer "+b.getKey()+": "+m);
				}
				else {
					// otherwise we need to wait for next attempt
					System.out.println(Thread.currentThread().getName()+ " tried to write in buffer "+b.getKey());
				}
				
				// hold the resource for a while
				try {
					sleep(bufferHoldingTime);
				} catch (InterruptedException e) {
					buffers.release(b);
					System.out.println("writer "+Thread.currentThread().getName()+" interrupted");
					return;
				}
				
				// release resource
				buffers.release(b);
				System.out.println("writer "+Thread.currentThread().getName()+" completed write attempt "+i);
				numOfAttempts++;
				
				// wait a little before the next attempt
				try {
					sleep(timeBetweenAttempts);
				} catch (InterruptedException e) {
					buffers.release(b);
					System.out.println("writer "+Thread.currentThread().getName()+" interrupted");
					return;
				}
			}
		}
		public int getNumOfAttempts() {
			return numOfAttempts;
		}
	}
	
	// class for a consumer thread
	class MessageReader extends Thread {
		private ResourcePool<Pair<String, StringBuffer>> buffers;
		private int maxNumOfAttempts = 0; 
		private long bufferHoldingTime = 0;
		private long timeBetweenAttempts = 0;
		private int numOfAttempts = 0;
		
		public MessageReader(ResourcePool<Pair<String, StringBuffer>> buffers, int maxNumOfAttempts, long bufferHoldingTime, long timeBetweenAttempts) {
			this.buffers = buffers;
			this.maxNumOfAttempts = maxNumOfAttempts;
			this.bufferHoldingTime = bufferHoldingTime;
			this.timeBetweenAttempts = timeBetweenAttempts;
		}
		public void run() {
			for(int i = 0; i < maxNumOfAttempts; i++) {
				System.out.println("reader "+Thread.currentThread().getName()+" ready for read attempt "+i);
				Pair<String, StringBuffer> b = null;
				try {
					b = buffers.acquire();
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				
				// if buffer is not empty, read message
				if (b.getValue().length() > 0) {
					// read from the buffer
					String m = Thread.currentThread().getName() + " read from buffer "
						+ b.getKey()+": " + b.getValue().toString();
					System.out.println(m);
					b.getValue().setLength(0);
				}
				else {
					// otherwise try next time
					System.out.println(Thread.currentThread().getName()+ " tried to read from buffer "+b.getKey());
				}
				
				// hold the buffer for a while
				try {
					sleep(bufferHoldingTime);
				} catch (InterruptedException e) {
					buffers.release(b);
					System.out.println("reader "+Thread.currentThread().getName()+" interrupted");
					return;
				}
				buffers.release(b);
				System.out.println("reader "+Thread.currentThread().getName()+" completed read attampt "+i);
				numOfAttempts++;

				// wait some time before next attempt
				try {
					sleep(timeBetweenAttempts);
				} catch (InterruptedException e) {
					buffers.release(b);
					System.out.println("reader "+Thread.currentThread().getName()+" interrupted");
					return;
				}

			}
		}
		public int getNumOfAttempts() {
			return numOfAttempts;
		}
	}


	// This class is for creating a thread to remove a resource from pool.
	class TestRunnable<T> implements Runnable {
		final private ResourcePool<T> pool;
		final private T resource;
		public TestRunnable(ResourcePool<T> pool, T resource) {
			this.pool = pool;
			this.resource = resource;
		}
		public void run() {
			try {
				pool.remove(resource);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		};
	}
	
	public void testProducerConsumer(int numOfBuffers,
			int numOfMessageWriters, int maxNumOfWriteAttempts, int writerBufferHoldingTime, int writerTimeBetweenAcquireAttempts,
			int numOfMessageReaders, int maxNumOfReadAttempts, int readerBufferHoldingTime, int readerTimeBetweenAcquireAttempts, 
			int loopCount, int loopDuration ) {
		System.out.println("==> begin testFuction\n");

		// add buffers to buffer pool
		bp.open();
		for(int i = 1; i <= numOfBuffers; i++) {
			bp.add(new ImmutablePair<String, StringBuffer>("buf"+i, new StringBuffer(256) ));
		}
		assertTrue("pool size should be numOfBuffers", bp.size() == numOfBuffers);

		// create producer threads
		ArrayList<MessageWriter> writerThreads = new ArrayList<MessageWriter>();
		
		// Each producer will repeatedly attempt to acquire buffer resource, write a message, and release buffer
		// writeAttemptCounts keep track attempts for each producer
		ArrayList<Integer> writeAttemptCounts = new ArrayList<Integer>();
		for(int i = 0; i < numOfMessageWriters; i++) {
			MessageWriter t = new MessageWriter(bp, maxNumOfWriteAttempts, writerBufferHoldingTime, writerTimeBetweenAcquireAttempts);
			writerThreads.add(t);
			writeAttemptCounts.add(new Integer(0));
			t.start();
			
		}
		// create consumer threads 
		ArrayList<MessageReader> readerThreads = new ArrayList<MessageReader>();
	
		// Each consumer will repeatedly attempt to acquire buffer resource, read if there is a message, and release buffer
		// readAttemptCounts keep track attempts for each consumer thread
		ArrayList<Integer> readAttemptCounts = new ArrayList<Integer>();
		for(int i = 0; i < numOfMessageReaders; i++) {
			MessageReader t = new MessageReader(bp, maxNumOfReadAttempts, readerBufferHoldingTime, readerTimeBetweenAcquireAttempts);
			readerThreads.add(t);
			readAttemptCounts.add(new Integer(0));
			t.start();
		}
		// loop and wait for producers and consumers to do their work
		// after each waiting period, check for attempt count change for each thread
		// if the change is not great than 0, then the thread might be starving, in deadlock or dead
		for (int l=0; l< loopCount; l++) {
			try {
				Thread.sleep(loopDuration);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		
			for(int i=0; i < numOfMessageWriters; i++) {
				int currentCount = writerThreads.get(i).getNumOfAttempts();
				int previousCount = writeAttemptCounts.get(i);
				Integer change = currentCount - previousCount;
				writeAttemptCounts.set(i, currentCount);
				if ( currentCount < maxNumOfWriteAttempts ) {
					assertTrue("thread is alive", writerThreads.get(i).isAlive());
					assertTrue("attempt count change should be greater than 0", change > 0);
				}
			}
			for(int i=0; i < numOfMessageReaders; i++) {
				int currentCount = readerThreads.get(i).getNumOfAttempts();
				int previousCount = readAttemptCounts.get(i);
				Integer change = currentCount - previousCount;
				readAttemptCounts.set(i, currentCount);
				if ( currentCount < maxNumOfReadAttempts ) {
					assertTrue("thread is alive", readerThreads.get(i).isAlive());
					assertTrue("attempt count change should be greater than 0", change > 0);
				}
			}
		}
		System.out.println("==> end testFuction\n");
	}

	@Before
	public void setup() {
		bp = new FairResourcePool<Pair<String, StringBuffer>>(true);
	}

	@Test
	public void testAddingNullResource() {
		assertFalse("adding null resource should return false", bp.add(null));
		assertTrue("resource count should be 0", 0 == bp.size());
	}
	@Test
	public void testAddingAResource() {
		Pair<String, StringBuffer> b = new ImmutablePair<String, StringBuffer>("buf1", new StringBuffer(256));
		assertTrue("adding a resource should return true", bp.add(b));
		assertTrue("pool size should be 1", 1 == bp.size());
	}
	@Test
	public void testAcquire() {
		System.out.println("begin testAcquire");

		Pair<String, StringBuffer> r = new ImmutablePair<String, StringBuffer>("buf1", new StringBuffer(256));
		assertTrue("adding a resource should return true", bp.add(r));
		assertTrue("pool size should be 1", bp.size() == 1);
		bp.open();
		Pair<String, StringBuffer> r1 = null;
		try {
			r1 = bp.acquire();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		assertNotNull("Aquired resource should not be null", r1);
		bp.release(r1);

		System.out.println("end testAcquire\n");

	}
	@Test
	public void testAcquireWithTimeout() {
		System.out.println("begin testAcquireWithTimeout");

		Pair<String, StringBuffer> r = new ImmutablePair<String, StringBuffer>("buf1", new StringBuffer(256));
		assertTrue("adding a resource should return true", bp.add(r));
		assertTrue("pool size should be 1", bp.size() == 1);
		bp.open();
		Pair<String, StringBuffer> r1 = null;
		try {
			r1 = bp.acquire();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		assertNotNull("Aquired resource should not be null", r1);
		Pair<String, StringBuffer> r2 = null;
		try {
			r2 = bp.acquire(2000, java.util.concurrent.TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		assertNull("Aquired resource should  be null", r2);
		
		// release resource 1
		bp.release(r1);

		// try acquire again, expecting success

		try {
			r2 = bp.acquire(2000, java.util.concurrent.TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		assertNotNull("Aquired resource should not be null", r2);

		System.out.println("end testAcquireWithTimeout\n");
	}
	@Test
	public void testAddingAResourceWakingUpWaitingThreads() {
		System.out.println("begin testAddingAResourceWakingUpWaitingThreads");

		Pair<String, StringBuffer> r = new ImmutablePair<String, StringBuffer>("buf1", new StringBuffer(256));
		assertTrue("pool size should be 0", bp.size() == 0);
		bp.open();
		
		int maxNumOfAcqureAttempts = 1;
		int bufferHoldingTime = 100;
		int timeBetweenAcquireAttempts = 0;
		// create a messagewriter thread which will block when it tries to acquire a resource
		new MessageWriter(bp, maxNumOfAcqureAttempts, bufferHoldingTime, timeBetweenAcquireAttempts).start();

		// now add a resource
		assertTrue("adding a resource should return true", bp.add(r));
		assertTrue("pool size should be 1", bp.size() == 1);

		// sleep a bit
		try {
			Thread.sleep(200);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		// now check buffer, we expect MessageWriter thread woke up and wrote something in the buffer
		Pair<String, StringBuffer> r1 = null;
		try {
			r1 = bp.acquire();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		assertNotNull("Aquired resource should not be null", r1);
		assertTrue("buffer should not be empty", r1.getValue().length() > 0);
		System.out.println("buffer content: "+r1.getValue().toString());

		System.out.println("end testAddingAResourceWakingUpWaitingThreads\n");
	}

	@Test
	public void testRemovingNullorUnknownResource() {
		System.out.println("begin testRemovingNullorUnknownResource");

		Pair<String, StringBuffer> r = new ImmutablePair<String, StringBuffer>("buf1", new StringBuffer(256));
		bp.open();
		assertTrue("pool size should be 0", bp.size() == 0);
		
		// now add a resource
		assertTrue("adding a resource should return true", bp.add(r));
		assertTrue("pool size should be 1", bp.size() == 1);

		
		// now remove null resource
		boolean result=false;
		try {
			result = bp.remove(null);
		} catch (InterruptedException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		assertFalse("Remove null resource should return false", result);

		// now remove a resource that's not in resource pool
		Pair<String, StringBuffer> r2 = new ImmutablePair<String, StringBuffer>("buf2", new StringBuffer(256));
		try {
			result = bp.remove(r2);
		} catch (InterruptedException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		assertFalse("Removing an unknown resource should return false", result);
		
		System.out.println("end testRemovingNullorUnknownResource\n");
	}

	@Test
	public void testRemovingAvailableResource() {
		System.out.println("begin testRemovingUnusedResource");

		Pair<String, StringBuffer> r = new ImmutablePair<String, StringBuffer>("buf1", new StringBuffer(256));
		bp.open();
		assertTrue("pool size should be 0", bp.size() == 0);
		
		// now add a resource
		assertTrue("adding a resource should return true", bp.add(r));
		assertTrue("pool size should be 1", bp.size() == 1);

		
		// now remove the resource
		boolean result=false;
		try {
			result = bp.remove(r);
		} catch (InterruptedException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		assertTrue("adding a resource should return true", result);
		assertTrue("pool size should be 0", bp.size() == 0);

		Pair<String, StringBuffer> r2 = null;
		try {
			r2 = bp.acquire(2000, java.util.concurrent.TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		assertNull("Aquired resource should  be null", r2);

		System.out.println("end testRemovingUnusedResource\n");
	}

	@Test
	public void testRemovingAcquiredResource() {
		System.out.println("begin testRemovingAcquiredResource");

		Pair<String, StringBuffer> r = new ImmutablePair<String, StringBuffer>("buf1", new StringBuffer(256));
		bp.open();
		assertTrue("pool size should be 0", bp.size() == 0);
		
		// now add a resource
		assertTrue("adding a resource should return true", bp.add(r));
		assertTrue("pool size should be 1", bp.size() == 1);

		int maxNumOfAcquireAttempts = 1;
		int bufferHoldingTime = 2000;
		int timeBetweenAcquireAttempts = 0;
		// create a messagewriter thread which will acquire the resource
		new MessageWriter(bp, maxNumOfAcquireAttempts, bufferHoldingTime, timeBetweenAcquireAttempts).start();

		// now remove the resource
		boolean result=false;
		try {
			result = bp.remove(r);
		} catch (InterruptedException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		assertTrue("adding a resource should return true", result);
		assertTrue("pool size should be 0", bp.size() == 0);

		Pair<String, StringBuffer> r2 = null;
		try {
			r2 = bp.acquire(2000, java.util.concurrent.TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		assertNull("Aquired resource should  be null", r2);

		System.out.println("end testRemovingAcquiredResource\n");
	}
	@Test
	public void testRemoveNowForAnAcquiredResource() {
		System.out.println("begin testRemoveNowForAnAcquiredResource");

		Pair<String, StringBuffer> r = new ImmutablePair<String, StringBuffer>("buf1", new StringBuffer(256));
		bp.open();
		assertTrue("pool size should be 0", bp.size() == 0);
		
		// now add a resource
		assertTrue("adding a resource should return true", bp.add(r));
		assertTrue("pool size should be 1", bp.size() == 1);

		int maxNumOfAcquireAttempts = 1;
		int bufferHoldingTime = 4000;
		int timeBetweenAcquireAttempts = 0;

		// create a messagewriter thread which will acquire the resource, and sleep for 4 seconds
		new MessageWriter(bp, maxNumOfAcquireAttempts, bufferHoldingTime, timeBetweenAcquireAttempts).start();
		System.out.println("writer started");

		// wait for the MessageWriter thread to acquired the resource
		try {
			Thread.sleep(1000);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		// verify resource has been acquired
		Pair<String, StringBuffer> r1 = null;
		try {
			r1 = bp.acquire(1000, java.util.concurrent.TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		assertNull("Aquired resource should  be null", r1);
		
		// removeNow() will issue interrupt
		boolean result=false;
		result = bp.removeNow(r);
		
		// removeNow will return true
		assertTrue("removeNow() should return true", result);
		assertTrue("pool size should be 0", bp.size() == 0);

		Pair<String, StringBuffer> r2 = null;
		try {
			r2 = bp.acquire(1000, java.util.concurrent.TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		assertNull("Aquired resource should  be null", r2);

		System.out.println("end testRemoveNowForAnAcquiredResource\n");
	}

	@Test
	public void testClose() {
		System.out.println("begin testClose");
		int maxNumOfAcqureAttempts = 1;
		long bufferHoldingTime = 5000;
		long timeBetweenAcquireAttempts = 0;
		int targetThreadCount = 3;
		
		// add buffers to buffer pool
		bp.open();
		Pair<String, StringBuffer>	r1 = new ImmutablePair<String, StringBuffer>("buf1", new StringBuffer(256) );
		Pair<String, StringBuffer>	r2 = new ImmutablePair<String, StringBuffer>("buf2", new StringBuffer(256) );
		bp.add(r1);
		bp.add(r2);
		
		assertTrue("pool size should be 2", bp.size() == 2);

		System.out.println("starting 3 writer threads");
		// create producers, each will make one attempt to acquire a buffer and hold for 5 seconds
		ArrayList<Thread> threads = new ArrayList<Thread>();
		for(int i = 0; i < targetThreadCount; i++) {
			Thread t = new MessageWriter(bp, maxNumOfAcqureAttempts, bufferHoldingTime, timeBetweenAcquireAttempts);
			t.start();
			threads.add(t);
		}
		
		int aliveCount;
		
		// wait a little
		try {
			Thread.sleep(500);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		aliveCount= 0;
		for(int i=0; i<targetThreadCount; i++) {
			if (threads.get(i).isAlive())
				aliveCount++;
		}
		System.out.println("alive count "+aliveCount);
		
		// remove a resource
		System.out.println("removing a resource");
		
		// use a separate thread to remove a resource r1 so that main thread will not be blocked
		Thread removeThread = new Thread(new TestRunnable(bp, r1));
		removeThread.start();

		// wait a little
		try {
			Thread.sleep(500);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		// at this point, we have
		// one thread waiting for resource, 
		// one thread on a resource,
		// and one thread with resource that is being removed pending on the thread releasing it
		
		aliveCount = 0;
		for(int i=0; i<targetThreadCount; i++) {
			if (threads.get(i).isAlive()) {
				aliveCount++;
				System.out.println(threads.get(i).getName()+" alive");
			}
		}
		System.out.println("alive count "+aliveCount);
		assertTrue("all thread should be alive", aliveCount == targetThreadCount);

		System.out.println("call close");
		bp.close();

		// As result of close(),
		// two threads that acquired resource should be able to continue finish execution,
		// one thread should wake up from signal
		aliveCount=0;
		for(int i=0; i<targetThreadCount; i++) {
			if (threads.get(i).isAlive()) {
				aliveCount++;
				System.out.println(threads.get(i).getName()+" alive");
			}
		}
		System.out.println("alive count "+aliveCount);
		assertTrue("all thread should be alive", aliveCount==3);
		
		// wait a little more
		try {
			Thread.sleep(5000);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		// now all threads should finished
		aliveCount=0;
		for(int i=0; i<targetThreadCount; i++) {
			if (threads.get(i).isAlive()) {
				aliveCount++;
				System.out.println(threads.get(i).getName()+" alive");
			}
		}
		System.out.println("alive count "+aliveCount);
		assertTrue("No thread should be alive", aliveCount==0);

		System.out.println("end testClose\n");
		
	}
	@Test
	public void testCloseNow() {
		System.out.println("begin testCloseNow");
		int maxNumOfAcqureAttempts = 1;
		long bufferHoldingTime = 5000;
		long timeBetweenAcquireAttempts = 0;
		int targetThreadCount = 3;
		
		// add buffers to buffer pool
		bp.open();
		Pair<String, StringBuffer>	r1 = new ImmutablePair<String, StringBuffer>("buf1", new StringBuffer(256) );
		Pair<String, StringBuffer>	r2 = new ImmutablePair<String, StringBuffer>("buf2", new StringBuffer(256) );
		bp.add(r1);
		bp.add(r2);
		
		assertTrue("pool size should be 2", bp.size() == 2);

		System.out.println("starting 3 writer threads");
		// create producers
		ArrayList<Thread> threads = new ArrayList<Thread>();
		for(int i = 0; i < targetThreadCount; i++) {
			Thread t = new MessageWriter(bp, maxNumOfAcqureAttempts, bufferHoldingTime, timeBetweenAcquireAttempts);
			t.start();
			threads.add(t);
		}
		
		int aliveCount;
		
		try {
			Thread.sleep(500);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		aliveCount= 0;
		for(int i=0; i<targetThreadCount; i++) {
			if (threads.get(i).isAlive())
				aliveCount++;
		}
		System.out.println("alive count "+aliveCount);
		
		// remove a resource
		System.out.println("removing a resource");
		
		// use a separate thread to remove a resource r1 so that main thread will not be blocked
		Thread removeThread = new Thread(new TestRunnable(bp, r1));
		removeThread.start();
		
		// at this point, we have
		// one thread waiting for resource, 
		// one thread on a resource,
		// and one thread with resource that is being removed pending on the thread releasing it
		
		aliveCount = 0;
		for(int i=0; i<targetThreadCount; i++) {
			if (threads.get(i).isAlive()) {
				aliveCount++;
				System.out.println(threads.get(i).getName()+" alive");
			}
		}
		System.out.println("alive count "+aliveCount);
		assertTrue("all thread should be alive", aliveCount == targetThreadCount);

		System.out.println("close now");
		bp.closeNow();

		try {
			Thread.sleep(100);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		// As result of closeNow(),
		// two threads should be interrupted,
		// one thread should wake up from signal
		// they all should complete execution
		aliveCount=0;
		for(int i=0; i<targetThreadCount; i++) {
			if (threads.get(i).isAlive()) {
				aliveCount++;
				System.out.println(threads.get(i).getName()+" alive");
			}
		}
		System.out.println("alive count "+aliveCount);
		assertTrue("No thread should be alive", aliveCount==0);
		
		
		System.out.println("end testCloseNow\n");
		
	}
	

	@Test
	public void testRun1() {
		testProducerConsumer(
			100,  // number of buffers
			500,  // number of producers (writers)
			1000, // max number of write attempts
			50,   // writer buffer holding time
			0,    // writer time between attempts
			510,  // number of consumers (readers)
			1000, // max number of read attempts
			50,   // reader buffer holding time
			0,    // reader time between attempts
			8,    // number of test loop
			5000  // loop duration in milliseconds
		);
	}
	@Test
	public void testRun2() {
		testProducerConsumer(
				200,  // number of buffers
				500,  // number of producers (writers)
				1000, // max number of write attempts
				20,   // writer buffer holding time
				20,    // writer time between attempts
				500,  // number of consumers (readers)
				1000, // max number of read attempts
				20,   // reader buffer holding time
				20,    // reader time between attempts
				10,    // number of test loop
				5000  // loop duration in milliseconds
		);
	}
}
