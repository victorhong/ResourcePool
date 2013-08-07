package ResourcePool;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Implements ResourcePool<T> interface. 
 * The constructor for this class accepts an optional fairness parameter.
 * The ReentrantLock class used in this class supports fairness. According to ReentrantLock
 * class documentation, the fair option, "When set true, under contention,
 * locks favor granting access to the longest-waiting thread. Otherwise 
 * this lock does not guarantee any particular access order. Programs 
 * using fair locks accessed by many threads may display lower overall 
 * throughput (i.e., are slower; often much slower) than those using the 
 * default setting, but have smaller variances in times to obtain locks 
 * and guarantee lack of starvation." 
 *
 * @param <T>
 */
final public class FairResourcePool<T> implements ResourcePool<T> {
	final private  Lock lock;
	final private Condition resourceAvailable;
	final private Condition resourceRemove;
	
	final private AtomicBoolean open;
	
	// these member should only be modified after lock is locked
	final private LinkedList<T> availableResources;
	final private Hashtable<T, Thread> acquiredResources;
	final private Hashtable<T, Thread> removeList;
	final private AtomicInteger resourceCount;

	public FairResourcePool() {
		lock = new ReentrantLock();
		resourceAvailable = lock.newCondition();
		resourceRemove = lock.newCondition();
		open = new AtomicBoolean(false);
		availableResources = new LinkedList<T>();
		acquiredResources = new Hashtable<T, Thread>();
		removeList = new Hashtable<T, Thread>();
		resourceCount = new AtomicInteger(0);
	}
	
	/**
	 * Constructor with fair option.
	 * @param fair
	 */
	public FairResourcePool(boolean fair) {
		lock  = new ReentrantLock(fair);
		resourceAvailable = lock.newCondition();
		resourceRemove = lock.newCondition();
		open = new AtomicBoolean(false);
		availableResources = new LinkedList<T>();
		acquiredResources = new Hashtable<T, Thread>();
		removeList = new Hashtable<T, Thread>();
		resourceCount = new AtomicInteger(0);
	}

	public void open() {
		open.set(true);
	}
	public void close() {
		open.set(false);
		lock.tryLock();
		try {
			// let all waiting threads know that the pool is closed
			resourceAvailable.signalAll();
		} finally {
			lock.unlock();
		}
	}
	public void closeNow() {
		open.set(false);
		
		// use tryLock to get ahead of threads waiting for the lock
		lock.tryLock();
		try {
			// let all waiting threads know that the pool is closed
			resourceAvailable.signalAll();
			
			// interrupt threads currently using resources
			for(Entry<T, Thread> e: acquiredResources.entrySet()) {
				Thread t = e.getValue();
				if (t != null && t.isAlive())
					t.interrupt();
			}
			// interrupt threads currently using resources which have been placed on the remove list
			for(Entry<T, Thread> e: removeList.entrySet()) {
				Thread t = e.getValue();
				if (t != null && t.isAlive())
					t.interrupt();
			}
		} finally {
			lock.unlock();
		}
		
	}
	public boolean isOpen() {
		return open.get();
	}
	/**
	 * Acquires a resource from the resource pool. If the pool is not open,
	 * the call return null immediately. If the pool is If no resource available 
	 * in the pool, the method will cause the calling thread to suspend to 
	 * wait for resources becoming available. While suspended, the calling 
	 * thread could be interrupted, caller must be prepared to handle the interrupt exception.
	 * @return a resource of type T
	 * @throws InterruptedException
	 */
	public T acquire() throws InterruptedException {
		if (!isOpen()) {
			return null;
		}
		lock.lockInterruptibly();
		try {
			while (isOpen() && availableResources.isEmpty()) {
				// available pool is empty, so wait for any resource becoming available
				resourceAvailable.await();
			}
			
			if (isOpen()) {
				T resource = availableResources.poll();
				acquiredResources.put(resource, Thread.currentThread());
				return resource;
			}
			return null;
		} finally {
			lock.unlock();
		}
	}

	public T acquire(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {

		long startTime = System.nanoTime();
		boolean locked = lock.tryLock(timeout, unit);
		// if timed out
		if (!locked)
			return null;
		long elapsedTime = System.nanoTime() - startTime;

		// subtract the time we waited for lock
		long nanosTimeout = unit.toNanos(timeout) - elapsedTime;

		try {
			while (isOpen() && availableResources.isEmpty()) {
				nanosTimeout = resourceAvailable.awaitNanos(nanosTimeout);
				// if timed out, return null
				if (nanosTimeout <= 0)
			        return null;
			}
			if (isOpen()) {
				T resource = availableResources.poll();
				acquiredResources.put(resource, Thread.currentThread());
				return resource;
			}
			return null;
		} finally {
			lock.unlock();
		}
	}

	public void release(T resource) {
		if (resource == null)
			return;
		lock.lock();
		try {
			// if the resource was previously checked-out, release it
			if (acquiredResources.containsKey(resource)) {
				acquiredResources.remove(resource);
				
				availableResources.add(resource);
				resourceAvailable.signal();
			}
			
			// if resource is on remove list, send a signal to blocked threads
			if (removeList.containsKey(resource))
				resourceRemove.signalAll();
			
		} finally {
			lock.unlock();
		}
	}	

	public boolean add(T resource) {
		if (resource == null)
			return false;
		lock.lock();
		try {
			// if resource has been added previous, do nothing
			if ( availableResources.contains(resource) || acquiredResources.containsKey(resource))
				return false;
			
			// if resource were previously removed and still used by a live thread
			if ( removeList.containsKey(resource)) {
				Thread t = removeList.remove(resource);

				if (t != null && t.isAlive()) {
					acquiredResources.put(resource, t);
					resourceCount.set(availableResources.size()+acquiredResources.size());
					return true;
				}
			}
			availableResources.add(resource);
			resourceCount.set(availableResources.size()+acquiredResources.size());
			resourceAvailable.signal();
			return true;
		} finally {
			lock.unlock();
		}
	}
	public int size() {
		return resourceCount.get();
	}
	public boolean remove(T resource) throws InterruptedException {
		// if nothing to remove
		if ( resourceCount.get() == 0 || resource == null )
			return false;
		lock.lock();
		try {
			if (availableResources.remove(resource)) {
				resourceCount.set(availableResources.size()+acquiredResources.size());
				return true;
			}
			
			
			if ( acquiredResources.containsKey(resource) ) {
				Thread t = acquiredResources.remove(resource);
				resourceCount.set(availableResources.size()+acquiredResources.size());

				// if the same thread acquired a resource and trys to remove it, no waiting needed
				if (t == Thread.currentThread()) 
					return true;
				
				removeList.put(resource, t);

				System.out.println(Thread.currentThread().getName()+" waits for resourceRemove condition");
				resourceRemove.await();
					
				removeList.remove(resource);
				return true;
			}
			
			return false;
		} finally {
			lock.unlock();
		}
	
	}
	public boolean removeNow(T resource) {
		// if nothing to remove
		if ( resourceCount.get() == 0 || resource == null )
			return false;

		// Take advantage of "barging" feature to get ahead of threads waiting for the lock
		lock.tryLock();
		try {
		
			if (availableResources.remove(resource)) {
				resourceCount.set(availableResources.size()+acquiredResources.size());
				return true;
			}
			
			if (!acquiredResources.containsKey(resource))
				return false;
			
			Thread t = acquiredResources.remove(resource); 
			resourceCount.set(availableResources.size()+acquiredResources.size());
			
			// if the same thread acquired a resource and trys to remove it
			if (t == Thread.currentThread()) 
				return true;
			
			if (t != null && t.isAlive()) 
				t.interrupt();
			return true;
		} finally {
			lock.unlock();
		}
	}
}
