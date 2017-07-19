package info.ragozin.proflab;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ReadWriteInterlock implements Runnable {

    public interface Config {
        
        public String name();
        
        public int threadCount();
        
        public int resourceCount();

        public int lockPerTask();
        
        public int spinFactor();
        
        public int cycleCount();
        
    }
    
    public static abstract class ConfigBean implements Config {

        @Override
        public String name() {
            return ReadWriteInterlock.class.getName();
        }

        @Override
        public int resourceCount() {
            return 20;
        }

        @Override
        public int lockPerTask() {
            return 3;
        }

        @Override
        public int spinFactor() {
            return 50;
        }

        @Override
        public int cycleCount() {
            return 100;
        }
    }

    private final String name;
    private final Thread[] threads;
    private final ContentionResource<ReadWriteLock>[] resources; 
    private final CyclicBarrier barrier;
    private final CyclicBarrier jobBarrier = new CyclicBarrier(2);
    
    private final ThreadLocal<List<ContentionResource<ReadWriteLock>>> ownedLocks = new ThreadLocal<List<ContentionResource<ReadWriteLock>>>() {

        @Override
        protected List<ContentionResource<ReadWriteLock>> initialValue() {
            return new ArrayList<ContentionResource<ReadWriteLock>>();
        }        
    };
    
    private final int lockPerTask;
    private final int spinFactor;
    private final int cycleCount;
    
    private volatile int cycle;
    
    private boolean started;
    private volatile boolean terminated = false;
    
    @SuppressWarnings("unchecked")
    public ReadWriteInterlock(Config config) {
        
        name = config.name();
        
        threads = new Thread[config.threadCount()];
        for(int i = 0; i != threads.length; ++i) {
            threads[i] = newThread(i);
        }
        
        resources = new ContentionResource[config.resourceCount()];
        for(int i = 0; i != resources.length; ++i) {
            resources[i] = new ContentionResource<ReadWriteLock>(name + "@Res-" + i, new ReentrantReadWriteLock(false));
        }
        
        barrier = new CyclicBarrier(threads.length, new Runnable() {
            @Override
            public void run() {
                barrierPoint();                
            }
        });
        
        lockPerTask = config.lockPerTask();
        spinFactor = config.spinFactor();
        cycleCount = config.cycleCount();        
    }
    
    @Override
    public void run() {
        cycle = 0;
        if (!started) {
            started = true;
            for(Thread t: threads) {
                t.start();
            }        
        }
        try {
            jobBarrier.await();
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    public void terminate() {
        terminated = true;
        for(Thread t: threads) {
            t.interrupt();
        }
    }
    
    protected Thread newThread(int n) {
        Thread t = new Thread(new Runnable() {
            
            @Override
            public void run() {
                workerLoop();
            }
        }, name + "-" + n);
        return t;
    }

    protected void barrierPoint() {
        try {
            if (++cycle >= cycleCount) {
                jobBarrier.await();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (BrokenBarrierException e) {
            e.printStackTrace();
        }
    }

    protected void workerLoop() {
        try {
            Random rnd = new Random(Thread.currentThread().hashCode());
            while(!terminated) {
                barrier.await();
                lockIn(rnd, lockPerTask);
            }
        } catch (InterruptedException e) {
            return;
        } catch (BrokenBarrierException e) {
            e.printStackTrace();
        }
    }
    
    protected void lockIn(Random rnd, int n) {
        if (n > 0) {
            boolean write = rnd.nextInt(lockPerTask) == 0;
            ContentionResource<ReadWriteLock> lock = pickResource(rnd);
            
            ownedLocks.get().add(lock);
            
            (write ? lock.lock().writeLock() : lock.lock().readLock()).lock();
            try {
                spinMicros();
                lockIn(rnd, n - 1);
            }
            finally {
                (write ? lock.lock().writeLock() : lock.lock().readLock()).unlock();
                ownedLocks.get().remove(lock);
            }
        }
    }

    protected ContentionResource<ReadWriteLock> pickResource(Random rnd) {
        while(true) {
            ContentionResource<ReadWriteLock> r = resources[rnd.nextInt(resources.length)];
            if (ownedLocks.get().contains(r)) {
                continue;
            }
            return r;
        }
    }
    
    protected double spinMicros() {
        long deadline = System.nanoTime() + spinFactor * 1000;
        double x = 1.000001;
        while(deadline < System.nanoTime()) {
            for(int i = 0; i != 1000; ++i) {
                x = x * x;
            }
        }
        return x;
    }
}
