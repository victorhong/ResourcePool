package ResourcePool;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;



public interface ResourcePool<T> {
	public void open();
	public void close();
	public void closeNow();
	public boolean isOpen();
	public T acquire() throws InterruptedException;
	public T acquire(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException;
	public void release(T resource);
	public boolean add(T resource);
	public boolean remove(T resource) throws InterruptedException;	
	public boolean removeNow(T resource);
}
