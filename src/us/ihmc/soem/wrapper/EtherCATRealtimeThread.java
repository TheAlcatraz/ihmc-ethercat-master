package us.ihmc.soem.wrapper;

import java.io.IOException;

import us.ihmc.realtime.MonotonicTime;
import us.ihmc.realtime.PeriodicParameters;
import us.ihmc.realtime.PriorityParameters;
import us.ihmc.realtime.RealtimeThread;

/**
 * Thread that is synchronized to the DC Master clock or Free running.
 * 
 * This is the main class to interact with the Master. It takes care of 
 * all the details of the EtherCAT transmission and the usage of Distributed Clocks.
 * 
 * It also takes care of taring down EtherCAT slaves properly on shutdown.
 * 
 * @author Jesper Smith
 *
 */
public abstract class EtherCATRealtimeThread
{
   
   private final RealtimeThread realtimeThread;
   private final Master master;
   private boolean enableDC;
   
   private long dcControlIntegral = 0;
   private long syncOffset = 0;

   private final long cycleTimeInNs;
   private volatile boolean running = true;
   
   private long lastEtherCATTransactionTime = 0;
   private long lastCycleDuration = 0;
   private long lastIdleTime = 0;
   private long startTimeFreeRun = 0;
   
   
   /**
    * Create new Thread that is free running with respect to the slaves
    * 
    * @param iface Network interface that is connected to the EtherCAT network
    * @param priorityParameters Desired priority
    * @param period Desired period
    * @param enableDC false
    */
   public EtherCATRealtimeThread(String iface, PriorityParameters priorityParameters, MonotonicTime period, boolean enableDC)
   {
      this(iface, priorityParameters, period, false, -1);
      
      if(enableDC)
      {
         throw new RuntimeException("Please  provide a syncOffset to enable Distributed Clocks");
      }
   }
   
   /**
    * Create new Thread for EtherCAT communication.  
    * 
    * @param iface Network interface that is connected to the EtherCAT network
    * @param priorityParameters Desired priority
    * @param period Desired period
    * @param enableDC Enable Distributed clocks and synchronize this thread to the EtherCAT Master Clock 
    * @param syncOffset Waiting time between the DC Master Clock and calling master.send(). Recommended to be between 50000ns and 100000ns depending on system, CPU load and loop times.
    * 
    */
   public EtherCATRealtimeThread(String iface, PriorityParameters priorityParameters, MonotonicTime period, boolean enableDC, long syncOffset)
   {      
      this.realtimeThread = new RealtimeThread(priorityParameters, new PeriodicParameters(period), new Runnable()
      {
         @Override
         public void run()
         {
            EtherCATRealtimeThread.this.run();
         }
      });


      this.cycleTimeInNs = period.asNanoseconds();
      this.syncOffset = syncOffset;
      this.master = new Master(iface);
      this.enableDC = enableDC;
      if(enableDC)
      {
         master.enableDC(period.asNanoseconds());
      }
      

      Runtime.getRuntime().addShutdownHook(new Thread()
      {
         public void run()
         {
            EtherCATRealtimeThread.this.stopController();
            EtherCATRealtimeThread.this.join();
         }
      });
   }

   /**
    * Starts the EtherCAT controller.
    */
   public void start()
   {
      realtimeThread.start();
   }
   
   /** 
    * Delegate function to be able to switch to non-realtime threads in the future
    * 
    * @return Current monotonic clock time
    */
   private long getCurrentMonotonicClockTime()
   {
      return RealtimeThread.getCurrentMonotonicClockTime();
   }
   
   /**
    * Returns the current cycle timestamp
    * 
    * If enableDC = true, the timestamp will be the sync0 time on the DC Master Clock
    * If enableDC = false, the timestamp will be monontonic time taken from the computer running this code
    * 
    * @return
    */
   public long getCurrentCycleTimestamp()
   {
      if(enableDC)
      {
         // Rounding the DC time down to the cycle time gives the previous sync0 time.
         return (master.getDCTime() / cycleTimeInNs) * cycleTimeInNs;
      }
      else
      {
         return getCurrentMonotonicClockTime();
      }
   }
   
   


   /**
    * Returns the timestamp at the start of the cyclic execution
    * 
    * If enableDC = true, the timestamp will be the sync0 time on the DC Master Clock taken during master.init()
    * If enableDC = false, the timestamp will be monontonic time taken from the computer running this code at the end of master.init()
    * 
    */
   public long getInitTimestamp()
   {
      if(enableDC)
      {
         return (master.getStartDCTime() / cycleTimeInNs) * cycleTimeInNs;         
      }
      else
      {
         return startTimeFreeRun;
      }
   }
   
   /**
    * @return Time spent parked waiting for next execution. Does not include EtherCAT transaction time.
    */
   public long getLastIdleTime()
   {
      return lastIdleTime;
   }
   
   /**
    * 
    * @return Time spent doing the EtherCAT transaction. 
    */
   public long getLastEtherCATTransactionTime()
   {
      return lastEtherCATTransactionTime;
   }
   
   /**
    * Main loop.
    */
   private final void run()
   {
      try
      {
         master.init();
      }
      catch (IOException e)
      {
         throw new RuntimeException(e);
      }
      
      this.enableDC = master.getDCEnabled();
      
      if(!enableDC)
      {
         startTimeFreeRun = getCurrentMonotonicClockTime();
      }
      
      while(running)
      {
         long startTime = getCurrentMonotonicClockTime();
         if(waitForNextPeriodAndDoTransfer())
         {
            doControl();
         }
         else
         {
            deadlineMissed();
         }
         lastCycleDuration = getCurrentMonotonicClockTime() - startTime;
      }
      
      boolean allSlavesShutdown = false;
      while(!allSlavesShutdown)
      {
         if(waitForNextPeriodAndDoTransfer())
         {
            allSlavesShutdown = master.shutdownSlaves();
         }
         else
         {
            deadlineMissed();
         }
      }
      
      master.shutdown();
   }

   
   /**
    * Call every tick to wait for the next trigger time followed by the EtherCAT transaction.
    * 
    * @return true if deadline was met and the EtherCAT transaction was successful
    */
   private boolean waitForNextPeriodAndDoTransfer()
   {
      lastIdleTime = waitForNextPeriodInternal(); 
      if(lastIdleTime > 0)
      {
         long startTime = getCurrentMonotonicClockTime();
         master.send();
         boolean receive = master.receive();
         lastEtherCATTransactionTime = getCurrentMonotonicClockTime() - startTime;
         
         if(master.isWorkingCounterMismatch())
         {
            slaveNotResponding();
         }
         
         return receive;
      }
      return false;
   }
   
   /* PI calculation to get linux time synced to DC time */

   /**
    * Simple PI controller to be used to synchronize the control loop with the 
    * Distributed Clocks feature of EtherCAT.   
    * 
    * @param syncOffset Offset from the start of the DC sync pulse.
    * 
    * @return Offset in NS to add to the current tick duration to synchronize the clocks
    */
   private long calculateDCOffsetTime(long syncOffset)
   {
      long reftime = master.getDCTime();

      /* set linux sync point 50us later than DC sync, just as example */
      long delta = (reftime - syncOffset) % cycleTimeInNs;
      if (delta > (cycleTimeInNs / 2))
      {
         delta = delta - cycleTimeInNs;
      }
      if (delta > 0)
      {
         dcControlIntegral++;
      }
      if (delta < 0)
      {
         dcControlIntegral--;
      }
      return -(delta / 100) - (dcControlIntegral / 20);
   }

   /**
    * Internal function. Delegate to wait for next period, depending on DC enabled/disabled. 
    * 
    * @return time waited
    */
   private final long waitForNextPeriodInternal()
   {
      if(enableDC)
      {
         long offset = calculateDCOffsetTime(syncOffset);
         return realtimeThread.waitForNextPeriod(offset);         
      }
      else
      {
         return realtimeThread.waitForNextPeriod();
      }
   }

   /**
    * Calling this function stops the EtherCAT thread at the next iteration. This function is safe to be called from any thread.
    */
   public final void stopController()
   {
      running = false;
   }

   /**
    * Wait till the EtherCAT thread has finished executing.
    */
   public void join()
   {
      realtimeThread.join();
   }
   
   
   /**
    * @see us.ihmc.soem.wrapper.Master#setEtherCATStatusCallback(us.ihmc.soem.wrapper.EtherCATStatusCallback)
    */
   public void setEtherCATStatusCallback(EtherCATStatusCallback callback)
   {
      master.setEtherCATStatusCallback(callback);
   }

   /**
    * @see us.ihmc.soem.wrapper.Master#enableTrace()
    */
   public void enableTrace()
   {
      master.enableTrace();
   }

   /**
    * @see us.ihmc.soem.wrapper.Master#registerSDO(us.ihmc.soem.wrapper.SDO)
    */
   public void registerSDO(SDO sdo)
   {
      master.registerSDO(sdo);
   }

   /**
    * @see us.ihmc.soem.wrapper.Master#registerSlave(us.ihmc.soem.wrapper.Slave)
    */
   public void registerSlave(Slave slave)
   {
      master.registerSlave(slave);
   }

   /**
    * @see us.ihmc.soem.wrapper.Master#getJitterEstimate()
    */
   public long getJitterEstimate()
   {
      return master.getJitterEstimate();
   }

   /**
    * @see us.ihmc.soem.wrapper.Master#setMaximumExecutionJitter(long)
    */
   public void setMaximumExecutionJitter(long jitterInNanoseconds)
   {
      master.setMaximumExecutionJitter(jitterInNanoseconds);
   }
   
   /**
    * The measured cycle time. Should be equal to the desired period with a small amount of jitter.
    * 
    * @return Duration of the complete control cycle, including time spent waiting for the next cycle.
    */
   public long getLastCycleDuration()
   {
      return lastCycleDuration;
   }


   /**
    * Callback to notify controller that one or more slaves are not responding.
    * 
    * This gets called when the expected working counter and actual working counter differ. 
    * It is recommended to go to a safe state when this function gets called.
    * 
    * This function gets called before the state of the slaves is updated. All slaves will probably be
    * in OP mode till the EtherCAT householder thread checks the state. This can take 10ms or more.
    * 
    */
   protected abstract void slaveNotResponding();
   
   /**
    * Callback to notify controller of missed deadline
    */
   protected abstract void deadlineMissed();
   
   /**
    * Callback called cyclically to do the control loop
    */
   protected abstract void doControl();

}
