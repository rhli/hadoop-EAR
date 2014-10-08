/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.mapred;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocalFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.CleanupQueue.PathDeletionContext;
import org.apache.hadoop.mapred.JobHistory.Values;
import org.apache.hadoop.mapreduce.TaskType;
import org.apache.hadoop.mapreduce.server.jobtracker.TaskTracker;
import org.apache.hadoop.metrics.MetricsContext;
import org.apache.hadoop.metrics.MetricsRecord;
import org.apache.hadoop.metrics.MetricsUtil;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.net.NetworkTopology;
import org.apache.hadoop.net.Node;
import org.apache.hadoop.util.StringUtils;

/*************************************************************
 * JobInProgress maintains all the info for keeping
 * a Job on the straight and narrow.  It keeps its JobProfile
 * and its latest JobStatus, plus a set of tables for
 * doing bookkeeping of its Tasks.
 * ***********************************************************
 */
public class JobInProgress extends JobInProgressTraits {
  /**
   * Used when the a kill is issued to a job which is initializing.
   */
  static class KillInterruptedException extends InterruptedException {
   private static final long serialVersionUID = 1L;
    public KillInterruptedException(String msg) {
      super(msg);
    }
  }

  static final Log LOG = LogFactory.getLog(JobInProgress.class);
  static final Log countersLog = LogFactory.getLog("Counters");

  JobProfile profile;
  JobStatus status;
  Path jobFile = null;
  Path localJobFile = null;

  int numMapTasks = 0;
  int numReduceTasks = 0;
  long memoryPerMap;
  long memoryPerReduce;
  volatile int numSlotsPerMap = 1;
  volatile int numSlotsPerReduce = 1;
  int maxTaskFailuresPerTracker;
  volatile long totalMapWaitTime = 0L;
  volatile long totalReduceWaitTime = 0L;
  volatile long firstMapStartTime = 0;
  volatile long firstReduceStartTime = 0;

  // Counters to track currently running/finished/failed Map/Reduce task-attempts
  int runningMapTasks = 0;
  int runningReduceTasks = 0;
  int pendingMapTasks = 0;
  int pendingReduceTasks = 0;
  int neededMapTasks = 0;
  int neededReduceTasks = 0;
  int finishedMapTasks = 0;
  int finishedReduceTasks = 0;
  int failedMapTasks = 0;
  int failedReduceTasks = 0;
  int killedMapTasks = 0;
  int killedReduceTasks = 0;

  static final float DEFAULT_COMPLETED_MAPS_PERCENT_FOR_REDUCE_SLOWSTART = 0.05f;
  int completedMapsForReduceSlowstart = 0;
  int rushReduceReduces = 5;
  int rushReduceMaps = 5;

  // Speculate when the percentage of the unfinished maps is lower than this
  public static final String SPECULATIVE_MAP_UNFINISHED_THRESHOLD_KEY =
      "mapred.map.tasks.speculation.unfinished.threshold";
  private float speculativeMapUnfininshedThreshold = 0.001F;
  private float speculativeMapLogRateThreshold = 0.001F;
  private float speculativeReduceLogRateThreshold = 0.001F;
  private int speculativeMapLogNumThreshold = 3;
  private int speculativeReduceLogNumThreshold = 3;
  private long lastTimeCannotspeculativeMapLog = 0;
  private long lastTimeCannotspeculativeReduceLog = 0;
  private final long logCannotspeculativeInterval = 150000L;

  // Speculate when the percentage of the unfinished reduces is lower than this
  public static final String SPECULATIVE_REDUCE_UNFINISHED_THRESHOLD_KEY =
      "mapred.reduce.tasks.speculation.unfinished.threshold";
  private float speculativeReduceUnfininshedThreshold = 0.001F;

  // runningMapTasks include speculative tasks, so we need to capture
  // speculative tasks separately
  int speculativeMapTasks = 0;
  int speculativeReduceTasks = 0;
  boolean garbageCollected = false;
  private static AtomicInteger totalSpeculativeMapTasks = new AtomicInteger(0);
  private static AtomicInteger totalSpeculativeReduceTasks =
      new AtomicInteger(0);

  int mapFailuresPercent = 0;
  int reduceFailuresPercent = 0;
  int failedMapTIPs = 0;
  int failedReduceTIPs = 0;
  private volatile boolean launchedCleanup = false;
  private volatile boolean launchedSetup = false;
  private volatile boolean jobKilled = false;
  private volatile boolean jobFailed = false;
  boolean jobSetupCleanupNeeded;
  boolean jobFinishWhenReducesDone;
  boolean taskCleanupNeeded;

  JobPriority priority = JobPriority.NORMAL;
  JobTracker jobtracker;

  // NetworkTopology Node to the set of TIPs
  Map<Node, List<TaskInProgress>> nonRunningMapCache;

  // Map of NetworkTopology Node to set of running TIPs
  Map<Node, Set<TaskInProgress>> runningMapCache;

  // A list of non-local non-running maps
  List<TaskInProgress> nonLocalMaps;

  // A set of non-local running maps
  Set<TaskInProgress> nonLocalRunningMaps;

  // A list of non-running reduce TIPs
  List<TaskInProgress> nonRunningReduces;

  // A set of running reduce TIPs
  Set<TaskInProgress> runningReduces;

  // A list of cleanup tasks for the map task attempts, to be launched
  List<TaskAttemptID> mapCleanupTasks = new LinkedList<TaskAttemptID>();

  // A list of cleanup tasks for the reduce task attempts, to be launched
  List<TaskAttemptID> reduceCleanupTasks = new LinkedList<TaskAttemptID>();

  int maxLevel;

  /**
   * A special value indicating that
   * {@link #findNewMapTask(TaskTrackerStatus, int, int, int)} should
   * schedule any available map tasks for this job, including speculative tasks.
   */
  int anyCacheLevel;

  /**
   * A special value indicating that
   * {@link #findNewMapTask(TaskTrackerStatus, int, int, int)} should
   * schedule any only off-switch and speculative map tasks for this job.
   */
  private static final int NON_LOCAL_CACHE_LEVEL = -1;

  private int taskCompletionEventTracker = 0;
  List<TaskCompletionEvent> taskCompletionEvents;

  // The maximum percentage of trackers in cluster added to the 'blacklist'.
  private static final double CLUSTER_BLACKLIST_PERCENT = 0.25;

  // The maximum percentage of fetch failures allowed for a map
  private static final double MAX_ALLOWED_FETCH_FAILURES_PERCENT = 0.5;

  // No. of tasktrackers in the cluster
  private volatile int clusterSize = 0;

  // The no. of tasktrackers where >= conf.getMaxTaskFailuresPerTracker()
  // tasks have failed
  private volatile int flakyTaskTrackers = 0;
  // Map of trackerHostName -> no. of task failures
  private final Map<String, List<String>> trackerToFailuresMap =
    new TreeMap<String, List<String>>();

  //Confine estimation algorithms to an "oracle" class that JIP queries.
  ResourceEstimator resourceEstimator;

  volatile long startTime;
  long launchTime;
  long finishTime;

  // Indicates how many times the job got restarted
  int restartCount;

  JobConf conf;
  AtomicBoolean tasksInited = new AtomicBoolean(false);
  private final JobInitKillStatus jobInitKillStatus = new JobInitKillStatus();

  LocalFileSystem localFs;
  JobID jobId;
  volatile private boolean hasSpeculativeMaps;
  volatile private boolean hasSpeculativeReduces;
  long inputLength = 0;
  private String user;
  private String historyFile = "";
  private boolean historyFileCopied;

  // Per-job counters
  public static enum Counter {
    NUM_FAILED_MAPS,
    NUM_FAILED_REDUCES,
    TOTAL_LAUNCHED_MAPS,
    TOTAL_LAUNCHED_REDUCES,
    OTHER_LOCAL_MAPS,
    DATA_LOCAL_MAPS,
    RACK_LOCAL_MAPS,
    SLOTS_MILLIS_MAPS,
    SLOTS_MILLIS_REDUCES,
    SLOTS_MILLIS_REDUCES_COPY,
    SLOTS_MILLIS_REDUCES_SORT,
    SLOTS_MILLIS_REDUCES_REDUCE,
    FALLOW_SLOTS_MILLIS_MAPS,
    FALLOW_SLOTS_MILLIS_REDUCES,
    LOCAL_MAP_INPUT_BYTES,
    RACK_MAP_INPUT_BYTES,
    TOTAL_MAP_WAIT_MILLIS,
    TOTAL_REDUCE_WAIT_MILLIS,
    TOTAL_HIGH_MEMORY_MAP_TASK_KILLED,
    TOTAL_HIGH_MEMORY_REDUCE_TASK_KILLED,
    TOTAL_CGROUP_MEMORY_MAP_TASK_KILLED,
    TOTAL_CGROUP_MEMORY_REDUCE_TASK_KILLED,
    MAX_MAP_MEM_BYTES,
    MAX_MAP_RSS_MEM_BYTES,
    MAX_MAP_INST_MEM_BYTES,
    MAX_REDUCE_MEM_BYTES,
    MAX_REDUCE_RSS_MEM_BYTES,
    MAX_REDUCE_INST_MEM_BYTES,
    NUM_SESSION_DRIVER_CM_CLIENT_RETRY,
    NUM_RJT_FAILOVER,
    NUM_SAVED_MAPPERS,
    NUM_SAVED_REDUCERS,
    SAVED_MAP_CPU_MILLIS,
    SAVED_REDUCE_CPU_MILLIS,
    SAVED_MAP_WALLCLOCK_MILLIS,
    SAVED_REDUCE_WALLCLOCK_MILLIS,
    STATE_FETCH_COST_MILLIS
  }
  Counters jobCounters = new Counters();

  MetricsRecord jobMetrics;

  // Maximum no. of fetch-failure notifications after which
  // the map task is killed
  private static final int MAX_FETCH_FAILURES_NOTIFICATIONS = 3;

  private static final int MAX_FETCH_FAILURES_PER_MAP_DEFAULT = 50;
  private static final String MAX_FETCH_FAILURES_PER_MAP_KEY =
    "mapred.job.per.map.maxfetchfailures";
  private int maxFetchFailuresPerMapper;

  // Map of mapTaskId -> no. of fetch failures
  private final Map<TaskAttemptID, Integer> mapTaskIdToFetchFailuresMap =
    new TreeMap<TaskAttemptID, Integer>();

  private Object schedulingInfo;

  // Don't lower speculativeCap below one TT's worth (for small clusters)
  private static final int MIN_SPEC_CAP = 10;
  private static final float MIN_SLOTS_CAP = 0.01f;
  private static final float TOTAL_SPECULATIVECAP = 0.1f;
  public static final String SPECULATIVE_SLOWTASK_THRESHOLD =
    "mapreduce.job.speculative.slowtaskthreshold";
  public static final String RUSH_REDUCER_MAP_THRESHOLD =
    "mapred.job.rushreduce.map.threshold";
  public static final String RUSH_REDUCER_REDUCE_THRESHOLD =
    "mapred.job.rushreduce.reduce.threshold";
  public static final String SPECULATIVECAP =
    "mapreduce.job.speculative.speculativecap";
  public static final String SPECULATIVE_SLOWNODE_THRESHOLD =
    "mapreduce.job.speculative.slownodethreshold";
  public static final String REFRESH_TIMEOUT =
    "mapreduce.job.refresh.timeout";
  public static final String SPECULATIVE_STDDEVMEANRATIO_MAX =
    "mapreduce.job.speculative.stddevmeanratio.max";

  //thresholds for speculative execution
  float slowTaskThreshold;
  float speculativeCap;
  float slowNodeThreshold;

  //Statistics are maintained for a couple of things
  //mapTaskStats is used for maintaining statistics about
  //the completion time of map tasks on the trackers. On a per
  //tracker basis, the mean time for task completion is maintained
  private final DataStatistics mapTaskStats = new DataStatistics();
  //reduceTaskStats is used for maintaining statistics about
  //the completion time of reduce tasks on the trackers. On a per
  //tracker basis, the mean time for task completion is maintained
  private final DataStatistics reduceTaskStats = new DataStatistics();
  //trackerMapStats used to maintain a mapping from the tracker to the
  //the statistics about completion time of map tasks
  private Map<String,DataStatistics> trackerMapStats =
    new HashMap<String,DataStatistics>();
  //trackerReduceStats used to maintain a mapping from the tracker to the
  //the statistics about completion time of reduce tasks
  private Map<String,DataStatistics> trackerReduceStats =
    new HashMap<String,DataStatistics>();
  //runningMapStats used to maintain the RUNNING map tasks' statistics
  private final DataStatistics runningMapTaskStats = new DataStatistics();
  //runningReduceStats used to maintain the RUNNING reduce tasks' statistics
  private final DataStatistics runningReduceTaskStats = new DataStatistics();

  //Stores stats for processing rates for all the tasks in each phase
  private DataStatistics runningTaskMapByteProcessingRateStats =
      new DataStatistics();
  private DataStatistics runningTaskMapRecordProcessingRateStats =
      new DataStatistics();
  private DataStatistics runningTaskCopyProcessingRateStats =
      new DataStatistics();
  private DataStatistics runningTaskSortProcessingRateStats =
      new DataStatistics();
  private DataStatistics runningTaskReduceProcessingRateStats =
      new DataStatistics();

  private static class FallowSlotInfo {
    long timestamp;
    int numSlots;

    public FallowSlotInfo(long timestamp, int numSlots) {
      this.timestamp = timestamp;
      this.numSlots = numSlots;
    }

    public long getTimestamp() {
      return timestamp;
    }

    public void setTimestamp(long timestamp) {
      this.timestamp = timestamp;
    }

    public int getNumSlots() {
      return numSlots;
    }

    public void setNumSlots(int numSlots) {
      this.numSlots = numSlots;
    }
  }

  private final Map<TaskTracker, FallowSlotInfo> trackersReservedForMaps =
    new HashMap<TaskTracker, FallowSlotInfo>();
  private final Map<TaskTracker, FallowSlotInfo> trackersReservedForReduces =
    new HashMap<TaskTracker, FallowSlotInfo>();

  private long lastRefresh;
  private final long refreshTimeout;
  private final float speculativeStddevMeanRatioMax;
  private List<TaskInProgress> candidateSpeculativeMaps, candidateSpeculativeReduces;

  // For tracking what task caused the job to fail.
  private TaskID taskIdThatCausedFailure = null;

  public static final String ENABLE_NO_FETCH_MAP_OUTPUTS = "mapred.enable.no.fetch.map.outputs";
  private final boolean enableNoFetchEmptyMapOutputs;

  /**
   * Create an almost empty JobInProgress, which can be used only for tests
   */
  protected JobInProgress(JobID jobid, JobConf conf, JobTracker tracker) {
    this.conf = conf;
    this.jobId = jobid;
    this.numMapTasks = conf.getNumMapTasks();
    this.numReduceTasks = conf.getNumReduceTasks();
    this.maxLevel = NetworkTopology.DEFAULT_HOST_LEVEL;
    this.anyCacheLevel = this.maxLevel+1;
    this.jobtracker = tracker;
    this.restartCount = 0;
    this.status = new JobStatus(jobid, 0.0f, 0.0f, JobStatus.PREP);
    this.profile = new JobProfile(conf.getUser(), jobid, "", "",
                                  conf.getJobName(), conf.getQueueName());
    this.memoryPerMap = conf.getMemoryForMapTask();
    this.memoryPerReduce = conf.getMemoryForReduceTask();
    this.maxTaskFailuresPerTracker = conf.getMaxTaskFailuresPerTracker();
    this.nonLocalMaps = new LinkedList<TaskInProgress>();
    this.nonLocalRunningMaps = new LinkedHashSet<TaskInProgress>();
    this.runningMapCache = new IdentityHashMap<Node, Set<TaskInProgress>>();
    this.nonRunningReduces = new LinkedList<TaskInProgress>();
    this.runningReduces = new LinkedHashSet<TaskInProgress>();
    this.resourceEstimator = new ResourceEstimator(this);

    this.nonLocalMaps = new LinkedList<TaskInProgress>();
    this.nonLocalRunningMaps = new LinkedHashSet<TaskInProgress>();
    this.runningMapCache = new IdentityHashMap<Node, Set<TaskInProgress>>();
    this.nonRunningReduces = new LinkedList<TaskInProgress>();
    this.runningReduces = new LinkedHashSet<TaskInProgress>();
    this.jobSetupCleanupNeeded = true;
    this.jobFinishWhenReducesDone = false;
    this.taskCompletionEvents = new ArrayList<TaskCompletionEvent>
    (numMapTasks + numReduceTasks + 10);

    this.slowTaskThreshold = Math.max(0.0f,
        conf.getFloat(JobInProgress.SPECULATIVE_SLOWTASK_THRESHOLD,1.0f));
    this.speculativeCap = conf.getFloat(
        JobInProgress.SPECULATIVECAP,0.1f);
    this.slowNodeThreshold = conf.getFloat(
        JobInProgress.SPECULATIVE_SLOWNODE_THRESHOLD,1.0f);
    this.refreshTimeout = conf.getLong(JobInProgress.REFRESH_TIMEOUT, 5000L);
    this.speculativeStddevMeanRatioMax = conf.getFloat(
        JobInProgress.SPECULATIVE_STDDEVMEANRATIO_MAX, 0.33f);
    this.speculativeMapUnfininshedThreshold = conf.getFloat(
        SPECULATIVE_MAP_UNFINISHED_THRESHOLD_KEY,
        speculativeMapUnfininshedThreshold);
    this.speculativeReduceUnfininshedThreshold = conf.getFloat(
        SPECULATIVE_REDUCE_UNFINISHED_THRESHOLD_KEY,
        speculativeReduceUnfininshedThreshold);

    hasSpeculativeMaps = conf.getMapSpeculativeExecution();
    hasSpeculativeReduces = conf.getReduceSpeculativeExecution();
    LOG.info(jobId + ": hasSpeculativeMaps = " + hasSpeculativeMaps +
             ", hasSpeculativeReduces = " + hasSpeculativeReduces);
    enableNoFetchEmptyMapOutputs = conf.getBoolean(ENABLE_NO_FETCH_MAP_OUTPUTS, false);
    LOG.info(jobId + ": enableNoFetchEmptyMapOutputs = " + enableNoFetchEmptyMapOutputs);
  }

  /**
   * Create a JobInProgress with the given job file, plus a handle
   * to the tracker.
   */
  public JobInProgress(JobID jobid, JobTracker jobtracker,
                       JobConf default_conf) throws IOException {
    this(jobid, jobtracker, default_conf, 0);
  }

  public JobInProgress(JobID jobid, JobTracker jobtracker,
                       JobConf default_conf, int rCount) throws IOException {
    this(jobid, jobtracker, default_conf, null, rCount);
  }

  JobInProgress(JobID jobid, JobTracker jobtracker,
                JobConf default_conf, String user, int rCount)
  throws IOException {
    this.restartCount = rCount;
    this.jobId = jobid;
    String url = "http://" + jobtracker.getJobTrackerMachine() + ":"
        + jobtracker.getInfoPort() + "/jobdetails.jsp?jobid=" + jobid;
    this.jobtracker = jobtracker;
    this.status = new JobStatus(jobid, 0.0f, 0.0f, JobStatus.PREP);
    this.jobtracker.getInstrumentation().addPrepJob(conf, jobid);
    this.startTime = JobTracker.getClock().getTime();
    status.setStartTime(startTime);
    this.localFs = FileSystem.getLocal(default_conf);

    JobConf default_job_conf = new JobConf(default_conf);
    this.localJobFile = default_job_conf.getLocalPath(JobTracker.SUBDIR
                                                      +"/"+jobid + ".xml");

    if (user == null) {
      this.user = conf.getUser();
    } else {
      this.user = user;
    }
    LOG.info("User : " +  this.user);

    Path jobDir = jobtracker.getSystemDirectoryForJob(jobId);
    FileSystem fs = jobDir.getFileSystem(default_conf);
    jobFile = new Path(jobDir, "job.xml");

    if (!localFs.exists(localJobFile)) {
      fs.copyToLocalFile(jobFile, localJobFile);
    }

    conf = new JobConf(localJobFile);
    this.priority = conf.getJobPriority();
    this.status.setJobPriority(this.priority);
    this.profile = new JobProfile(user, jobid,
                                  jobFile.toString(), url, conf.getJobName(),
                                  conf.getQueueName());

    this.numMapTasks = conf.getNumMapTasks();
    this.numReduceTasks = conf.getNumReduceTasks();
    this.memoryPerMap = conf.getMemoryForMapTask();
    this.memoryPerReduce = conf.getMemoryForReduceTask();
    this.taskCompletionEvents = new ArrayList<TaskCompletionEvent>
       (numMapTasks + numReduceTasks + 10);
    this.jobSetupCleanupNeeded = conf.getJobSetupCleanupNeeded();
    this.jobFinishWhenReducesDone = conf.getJobFinishWhenReducesDone();
    this.taskCleanupNeeded = conf.getTaskCleanupNeeded();
    LOG.info("Setup and cleanup tasks: jobSetupCleanupNeeded = " +
        jobSetupCleanupNeeded + ", taskCleanupNeeded = " + taskCleanupNeeded);

    this.mapFailuresPercent = conf.getMaxMapTaskFailuresPercent();
    this.reduceFailuresPercent = conf.getMaxReduceTaskFailuresPercent();
    this.maxTaskFailuresPerTracker = conf.getMaxTaskFailuresPerTracker();

    MetricsContext metricsContext = MetricsUtil.getContext("mapred");
    this.jobMetrics = MetricsUtil.createRecord(metricsContext, "job");
    this.jobMetrics.setTag("user", conf.getUser());
    this.jobMetrics.setTag("sessionId", conf.getSessionId());
    this.jobMetrics.setTag("jobName", conf.getJobName());
    this.jobMetrics.setTag("jobId", jobid.toString());
    hasSpeculativeMaps = conf.getMapSpeculativeExecution();
    hasSpeculativeReduces = conf.getReduceSpeculativeExecution();
    this.maxLevel = jobtracker.getNumTaskCacheLevels();
    this.anyCacheLevel = this.maxLevel+1;
    this.nonLocalMaps = new LinkedList<TaskInProgress>();
    this.nonLocalRunningMaps = new LinkedHashSet<TaskInProgress>();
    this.runningMapCache = new IdentityHashMap<Node, Set<TaskInProgress>>();
    this.nonRunningReduces = new LinkedList<TaskInProgress>();
    this.runningReduces = new LinkedHashSet<TaskInProgress>();
    this.resourceEstimator = new ResourceEstimator(this);
    this.slowTaskThreshold = Math.max(0.0f,
        conf.getFloat(SPECULATIVE_SLOWTASK_THRESHOLD,1.0f));
    this.speculativeCap = conf.getFloat(SPECULATIVECAP,0.1f);
    this.slowNodeThreshold = conf.getFloat(SPECULATIVE_SLOWNODE_THRESHOLD,1.0f);
    this.refreshTimeout = conf.getLong(JobInProgress.REFRESH_TIMEOUT,
        jobtracker.getJobTrackerReconfigurable().
        getInitialJobRefreshTimeoutMs());
    this.speculativeStddevMeanRatioMax = conf.getFloat(
        JobInProgress.SPECULATIVE_STDDEVMEANRATIO_MAX, 0.33f);
    this.speculativeMapUnfininshedThreshold = conf.getFloat(
        SPECULATIVE_MAP_UNFINISHED_THRESHOLD_KEY,
        speculativeMapUnfininshedThreshold);
    this.speculativeReduceUnfininshedThreshold = conf.getFloat(
        SPECULATIVE_REDUCE_UNFINISHED_THRESHOLD_KEY,
        speculativeReduceUnfininshedThreshold);
    enableNoFetchEmptyMapOutputs = conf.getBoolean(ENABLE_NO_FETCH_MAP_OUTPUTS, false);
    LOG.info(jobId + ": enableNoFetchEmptyMapOutputs = " + enableNoFetchEmptyMapOutputs);
  }

  public static void copyJobFileLocally(Path jobDir, JobID jobid,
      JobConf default_conf) throws IOException {

    FileSystem fs = jobDir.getFileSystem(default_conf);
    JobConf default_job_conf = new JobConf(default_conf);
    Path localJobFile = default_job_conf.getLocalPath(JobTracker.SUBDIR + "/"
        + jobid + ".xml");
    Path jobFile = new Path(jobDir, "job.xml");
    fs.copyToLocalFile(jobFile, localJobFile);
  }

  /**
   * Called periodically by JobTrackerMetrics to update the metrics for
   * this job.
   */
  public void updateMetrics() {
    Counters counters = getCounters();
    for (Counters.Group group : counters) {
      jobMetrics.setTag("group", group.getDisplayName());
      for (Counters.Counter counter : group) {
        jobMetrics.setTag("counter", counter.getDisplayName());
        jobMetrics.setMetric("value", (float) counter.getCounter());
        jobMetrics.update();
      }
    }
  }

  /**
   * Called when the job is complete
   */
  public void cleanUpMetrics() {
    // Deletes all metric data for this job (in internal table in metrics package).
    // This frees up RAM and possibly saves network bandwidth, since otherwise
    // the metrics package implementation might continue to send these job metrics
    // after the job has finished.
    jobMetrics.removeTag("group");
    jobMetrics.removeTag("counter");
    jobMetrics.remove();
  }

  private void printCache (Map<Node, List<TaskInProgress>> cache) {
    LOG.info("The taskcache info:");
    for (Map.Entry<Node, List<TaskInProgress>> n : cache.entrySet()) {
      List <TaskInProgress> tips = n.getValue();
      LOG.info("Cached TIPs on node: " + n.getKey());
      for (TaskInProgress tip : tips) {
        LOG.info("tip : " + tip.getTIPId());
      }
    }
  }

  Map<Node, List<TaskInProgress>> createCache(JobClient.RawSplit[] splits,
                                              int maxLevel) {
    Map<Node, List<TaskInProgress>> cache =
      new IdentityHashMap<Node, List<TaskInProgress>>(maxLevel);

    for (int i = 0; i < splits.length; i++) {
      String[] splitLocations = splits[i].getLocations();
      if (splitLocations.length == 0) {
        nonLocalMaps.add(maps[i]);
        continue;
      }

      for(String host: splitLocations) {
        Node node = jobtracker.getNode(host);
        if (node == null) {
          node = jobtracker.resolveAndAddToTopology(host);
        }
        LOG.debug("tip:" + maps[i].getTIPId() + " has split on node:" + node);
        for (int j = 0; j < maxLevel; j++) {
          List<TaskInProgress> hostMaps = cache.get(node);
          if (hostMaps == null) {
            hostMaps = new ArrayList<TaskInProgress>();
            cache.put(node, hostMaps);
            hostMaps.add(maps[i]);
          }
          //check whether the hostMaps already contains an entry for a TIP
          //This will be true for nodes that are racks and multiple nodes in
          //the rack contain the input for a tip. Note that if it already
          //exists in the hostMaps, it must be the last element there since
          //we process one TIP at a time sequentially in the split-size order
          if (hostMaps.get(hostMaps.size() - 1) != maps[i]) {
            hostMaps.add(maps[i]);
          }
          node = node.getParent();
        }
      }
    }
    return cache;
  }

  /**
   * Check if the job has been initialized.
   * @return <code>true</code> if the job has been initialized,
   *         <code>false</code> otherwise
   */
  @Override
  public boolean inited() {
    return tasksInited.get();
  }

  boolean hasRestarted() {
    return restartCount > 0;
  }

  /**
   * Get the number of slots required to run a single map task-attempt.
   * @return the number of slots required to run a single map task-attempt
   */
  int getNumSlotsPerMap() {
    return numSlotsPerMap;
  }

  /**
   * Set the number of slots required to run a single map task-attempt.
   * This is typically set by schedulers which support high-ram jobs.
   * @param slots the number of slots required to run a single map task-attempt
   */
  void setNumSlotsPerMap(int numSlotsPerMap) {
    this.numSlotsPerMap = numSlotsPerMap;
  }

  /**
   * Get the number of slots required to run a single reduce task-attempt.
   * @return the number of slots required to run a single reduce task-attempt
   */
  int getNumSlotsPerReduce() {
    return numSlotsPerReduce;
  }

  /**
   * Set the number of slots required to run a single reduce task-attempt.
   * This is typically set by schedulers which support high-ram jobs.
   * @param slots the number of slots required to run a single reduce
   *              task-attempt
   */
  void setNumSlotsPerReduce(int numSlotsPerReduce) {
    this.numSlotsPerReduce = numSlotsPerReduce;
  }

  /**
   * Construct the splits, etc.  This is invoked from an async
   * thread so that split-computation doesn't block anyone.
   */
  public synchronized void initTasks()
  throws IOException, KillInterruptedException {
    if (tasksInited.get() || isComplete()) {
      return;
    }
    synchronized(jobInitKillStatus){
      if(jobInitKillStatus.killed || jobInitKillStatus.initStarted) {
        return;
      }
      jobInitKillStatus.initStarted = true;
    }

    LOG.info("Initializing " + jobId);

    // log job info
    JobHistory.JobInfo.logSubmitted(getJobID(), conf, jobFile.toString(),
                                    this.startTime, hasRestarted());
    // log the job priority
    setPriority(this.priority);

    //
    // read input splits and create a map per a split
    //
    String jobFile = profile.getJobFile();

    Path sysDir = new Path(this.jobtracker.getSystemDir());
    FileSystem fs = sysDir.getFileSystem(conf);
    DataInputStream splitFile =
      fs.open(new Path(conf.get("mapred.job.split.file")));
    JobClient.RawSplit[] splits;
    try {
      splits = JobClient.readSplitFile(splitFile);
    } finally {
      splitFile.close();
    }
    numMapTasks = splits.length;


    // if the number of splits is larger than a configured value
    // then fail the job.
    int maxTasks = jobtracker.getMaxTasksPerJob();
    if (maxTasks > 0 && numMapTasks + numReduceTasks > maxTasks) {
      throw new IOException(
                "The number of tasks for this job " +
                (numMapTasks + numReduceTasks) +
                " exceeds the configured limit " + maxTasks);
    }
    jobtracker.getInstrumentation().addWaitingMaps(getJobID(), numMapTasks);
    jobtracker.getInstrumentation().addWaitingReduces(getJobID(), numReduceTasks);

    maps = new TaskInProgress[numMapTasks];
    for(int i=0; i < numMapTasks; ++i) {
      inputLength += splits[i].getDataLength();
      maps[i] = new TaskInProgress(jobId, jobFile,
                                   splits[i],
                                   conf, this, i, numSlotsPerMap);
    }
    LOG.info("Input size for job " + jobId + " = " + inputLength
        + ". Number of splits = " + splits.length);
    if (numMapTasks > 0) {
      nonRunningMapCache = createCache(splits, maxLevel);
    }

    // set the launch time
    this.launchTime = JobTracker.getClock().getTime();
    jobtracker.getInstrumentation().addLaunchedJobs(
      this.launchTime - this.startTime);

    //
    // Create reduce tasks
    //
    this.reduces = new TaskInProgress[numReduceTasks];
    for (int i = 0; i < numReduceTasks; i++) {
      reduces[i] = new TaskInProgress(jobId, jobFile,
                                      numMapTasks, i,
                                      conf, this, numSlotsPerReduce);
      nonRunningReduces.add(reduces[i]);
    }

    // Calculate the minimum number of maps to be complete before
    // we should start scheduling reduces
    completedMapsForReduceSlowstart =
      (int)Math.ceil(
          (conf.getFloat("mapred.reduce.slowstart.completed.maps",
                         DEFAULT_COMPLETED_MAPS_PERCENT_FOR_REDUCE_SLOWSTART) *
           numMapTasks));
    // The thresholds of total maps and reduces for scheduling reducers
    // immediately.
    rushReduceMaps =
      conf.getInt(RUSH_REDUCER_MAP_THRESHOLD, rushReduceMaps);
    rushReduceReduces =
      conf.getInt(RUSH_REDUCER_REDUCE_THRESHOLD, rushReduceReduces);
    maxFetchFailuresPerMapper = conf.getInt(MAX_FETCH_FAILURES_PER_MAP_KEY,
        MAX_FETCH_FAILURES_PER_MAP_DEFAULT);

    initSetupCleanupTasks(jobFile);

    synchronized(jobInitKillStatus){
      jobInitKillStatus.initDone = true;
      if(jobInitKillStatus.killed) {
        throw new KillInterruptedException("Job " + jobId + " killed in init");
      }
    }

    tasksInited.set(true);
    JobHistory.JobInfo.logInited(profile.getJobID(), this.launchTime,
        numMapTasks, numReduceTasks);

    // Log the number of map and reduce tasks
    LOG.info("Job " + jobId + " initialized successfully with " + numMapTasks
        + " map tasks and " + numReduceTasks + " reduce tasks.");
    refreshIfNecessary();
  }

  // Returns true if the job is empty (0 maps, 0 reduces and no setup-cleanup)
  // else return false.
  synchronized boolean isJobEmpty() {
    return maps.length == 0 && reduces.length == 0 && !jobSetupCleanupNeeded;
  }

  // Should be called once the init is done. This will complete the job
  // because the job is empty (0 maps, 0 reduces and no setup-cleanup).
  synchronized void completeEmptyJob() {
    jobComplete();
  }

  synchronized void completeSetup() {
    setupComplete();
  }

  private void initSetupCleanupTasks(String jobFile) {
    if (!jobSetupCleanupNeeded) {
      LOG.info("Setup/Cleanup not needed for job" + jobId);
      // nothing to initialize
      return;
    }

    // create cleanup two cleanup tips, one map and one reduce.
    cleanup = new TaskInProgress[2];

    // cleanup map tip. This map doesn't use any splits. Just assign an empty
    // split.
    JobClient.RawSplit emptySplit = new JobClient.RawSplit();
    cleanup[0] = new TaskInProgress(jobId, jobFile, emptySplit,
            conf, this, numMapTasks, 1);
    cleanup[0].setJobCleanupTask();

    // cleanup reduce tip.
    cleanup[1] = new TaskInProgress(jobId, jobFile, numMapTasks,
                       numReduceTasks, conf, this, 1);
    cleanup[1].setJobCleanupTask();

    // create two setup tips, one map and one reduce.
    setup = new TaskInProgress[2];

    // setup map tip. This map doesn't use any split. Just assign an empty
    // split.
    setup[0] = new TaskInProgress(jobId, jobFile, emptySplit,
            conf, this, numMapTasks + 1, 1);
    setup[0].setJobSetupTask();

    // setup reduce tip.
    setup[1] = new TaskInProgress(jobId, jobFile, numMapTasks,
                       numReduceTasks + 1, conf, this, 1);
    setup[1].setJobSetupTask();
  }

  synchronized boolean isSetupCleanupRequired() {
    return jobSetupCleanupNeeded;
  }

  void setupComplete() {
    status.setSetupProgress(1.0f);
    if (this.status.getRunState() == JobStatus.PREP) {
      changeStateTo(JobStatus.RUNNING);
      JobHistory.JobInfo.logStarted(profile.getJobID());
    }
  }

  /////////////////////////////////////////////////////
  // Accessors for the JobInProgress
  /////////////////////////////////////////////////////
  public String getConf(String key) {
    return this.conf.get(key);
  }

  public JobProfile getProfile() {
    return profile;
  }
  @Override
  public JobStatus getStatus() {
    return status;
  }
  public synchronized long getLaunchTime() {
    return launchTime;
  }
  public long getStartTime() {
    return startTime;
  }
  public long getFinishTime() {
    return finishTime;
  }
  public int desiredMaps() {
    return numMapTasks;
  }
  public int desiredReduces() {
    return numReduceTasks;
  }
  public boolean getMapSpeculativeExecution() {
    return hasSpeculativeMaps;
  }
  public boolean getReduceSpeculativeExecution() {
    return hasSpeculativeReduces;
  }
  long getMemoryForMapTask() {
    return memoryPerMap;
  }

  long getMemoryForReduceTask() {
    return memoryPerReduce;
  }
  public synchronized int finishedMaps() {
    return finishedMapTasks;
  }
  public synchronized int finishedReduces() {
    return finishedReduceTasks;
  }
  public synchronized int runningMaps() {
    return runningMapTasks;
  }
  public synchronized int runningReduces() {
    return runningReduceTasks;
  }
  public synchronized int pendingMaps() {
    return pendingMapTasks;
  }
  public synchronized int pendingReduces() {
    return pendingReduceTasks;
  }
  public synchronized int neededMaps() {
    return neededMapTasks;
  }
  public synchronized int neededReduces() {
    return neededReduceTasks;
  }
  // This are used in UI only, so the variable
  // is volatile and non synchronized
  public long getTotalMapWaitTime() {
    return totalMapWaitTime;
  }
  // This are used in UI only, so the variable
  // is volatile and non synchronized
  public long getTotalReduceWaitTime() {
    return totalReduceWaitTime;
  }
  public long getFirstMapWaitTime() {
    long startTime = getStartTime();
    if (firstMapStartTime == 0) {
      return JobTracker.getClock().getTime() - startTime;
    } else {
      return firstMapStartTime - startTime;
    }
  }
  public long getFirstReduceWaitTime() {
    long startTime = getStartTime();
    if (firstReduceStartTime == 0) {
      return JobTracker.getClock().getTime() - startTime;
    } else {
      return firstReduceStartTime - startTime;
    }
  }

  public int getNumSlotsPerTask(TaskType taskType) {
    if (taskType == TaskType.MAP) {
      return numSlotsPerMap;
    } else if (taskType == TaskType.REDUCE) {
      return numSlotsPerReduce;
    } else {
      return 1;
    }
  }
  public JobPriority getPriority() {
    return this.priority;
  }
  public void setPriority(JobPriority priority) {
    if(priority == null) {
      priority = JobPriority.NORMAL;
    }

    synchronized (this) {
      this.priority = priority;
      status.setJobPriority(priority);
    }
    // log and change to the job's priority
    JobHistory.JobInfo.logJobPriority(jobId, priority);
  }

  // Update the job start/launch time (upon restart) and log to history
  synchronized void updateJobInfo(long startTime, long launchTime) {
    // log and change to the job's start/launch time
    this.startTime = startTime;
    this.launchTime = launchTime;
    JobHistory.JobInfo.logJobInfo(jobId, startTime, launchTime);
  }

  /**
   * Get the number of times the job has restarted
   */
  @Override
  public int getNumRestarts() {
    return restartCount;
  }

  long getInputLength() {
    return inputLength;
  }

  boolean isCleanupLaunched() {
    return launchedCleanup;
  }

  boolean isSetupLaunched() {
    return launchedSetup;
  }

  /**
   * Get all the tasks of the desired type in this job.
   * @param type {@link TaskType} of the tasks required
   * @return An array of {@link TaskInProgress} matching the given type.
   *         Returns an empty array if no tasks are found for the given type.
   */
  TaskInProgress[] getTasks(TaskType type) {
    TaskInProgress[] tasks = null;
    switch (type) {
      case MAP:
        {
          tasks = maps;
        }
        break;
      case REDUCE:
        {
          tasks = reduces;
        }
        break;
      case JOB_SETUP:
        {
          tasks = setup;
        }
        break;
      case JOB_CLEANUP:
        {
          tasks = cleanup;
        }
        break;
      default:
        {
          tasks = new TaskInProgress[0];
        }
        break;
    }

    return tasks;
  }

  /**
   * Return the nonLocalRunningMaps
   * @return
   */
  Set<TaskInProgress> getNonLocalRunningMaps()
  {
    return nonLocalRunningMaps;
  }

  /**
   * Return the runningMapCache
   * @return
   */
  Map<Node, Set<TaskInProgress>> getRunningMapCache()
  {
    return runningMapCache;
  }

  /**
   * Return runningReduces
   * @return
   */
  Set<TaskInProgress> getRunningReduces()
  {
    return runningReduces;
  }

  /**
   * Get the job configuration
   * @return the job's configuration
   */
  JobConf getJobConf() {
    return conf;
  }

  /**
   * Get the job user/owner
   * @return the job's user/owner
   */
  @Override
  public String getUser() {
    return user;
  }

  @Override
  public synchronized Vector<TaskInProgress> reportTasksInProgress(boolean shouldBeMap,
                                                      boolean shouldBeComplete) {
    return super.reportTasksInProgress(shouldBeMap, shouldBeComplete);
  }

  @Override
  public synchronized Vector<TaskInProgress> reportCleanupTIPs(
                                               boolean shouldBeComplete) {
    return super.reportCleanupTIPs(shouldBeComplete);
  }

  @Override
  public synchronized Vector<TaskInProgress> reportSetupTIPs(
                                               boolean shouldBeComplete) {
    return super.reportSetupTIPs(shouldBeComplete);
  }


  ////////////////////////////////////////////////////
  // Status update methods
  ////////////////////////////////////////////////////

  /**
   * Assuming {@link JobTracker} is locked on entry.
   */
  public synchronized void updateTaskStatus(TaskInProgress tip,
                                            TaskStatus status) {

    double oldProgress = tip.getProgress();   // save old progress
    boolean wasRunning = tip.isRunning();
    boolean wasComplete = tip.isComplete();
    boolean wasPending = tip.isOnlyCommitPending();
    TaskAttemptID taskid = status.getTaskID();
    boolean wasAttemptRunning = tip.isAttemptRunning(taskid);

    // If the TIP is already completed and the task reports as SUCCEEDED then
    // mark the task as KILLED.
    // In case of task with no promotion the task tracker will mark the task
    // as SUCCEEDED.
    // User has requested to kill the task, but TT reported SUCCEEDED,
    // mark the task KILLED.
    if ((wasComplete || tip.wasKilled(taskid)) &&
        (status.getRunState() == TaskStatus.State.SUCCEEDED)) {
      status.setRunState(TaskStatus.State.KILLED);
    }

    // When a task has just reported its state as FAILED_UNCLEAN/KILLED_UNCLEAN,
    // if the job is complete or cleanup task is switched off,
    // make the task's state FAILED/KILLED without launching cleanup attempt.
    // Note that if task is already a cleanup attempt,
    // we don't change the state to make sure the task gets a killTaskAction
    if ((this.isComplete() || jobFailed || jobKilled || !taskCleanupNeeded) &&
        !tip.isCleanupAttempt(taskid)) {
      if (status.getRunState() == TaskStatus.State.FAILED_UNCLEAN) {
        status.setRunState(TaskStatus.State.FAILED);
      } else if (status.getRunState() == TaskStatus.State.KILLED_UNCLEAN) {
        status.setRunState(TaskStatus.State.KILLED);
      }
    }

    boolean change = tip.updateStatus(status);
    if (change) {
      TaskStatus.State state = status.getRunState();
      // get the TaskTrackerStatus where the task ran
      TaskTracker taskTracker =
        this.jobtracker.getTaskTracker(tip.machineWhereTaskRan(taskid));
      TaskTrackerStatus ttStatus =
        (taskTracker == null) ? null : taskTracker.getStatus();
      String httpTaskLogLocation = null;

      if (null != ttStatus){
        String host;
        if (NetUtils.getStaticResolution(ttStatus.getHost()) != null) {
          host = NetUtils.getStaticResolution(ttStatus.getHost());
        } else {
          host = ttStatus.getHost();
        }
        httpTaskLogLocation = "http://" + host + ":" + ttStatus.getHttpPort();
           //+ "/tasklog?plaintext=true&taskid=" + status.getTaskID();
      }

      TaskCompletionEvent taskEvent = null;
      if (state == TaskStatus.State.SUCCEEDED) {
        TaskCompletionEvent.Status taskCompletionStatus = TaskCompletionEvent.Status.SUCCEEDED;
        // Ensure that this is a map task.
        boolean isMapTask = status.getIsMap() && !tip.isJobCleanupTask() && !tip.isJobSetupTask();
        if (enableNoFetchEmptyMapOutputs && isMapTask) {
          long outBytes = status.getCounters().getCounter(Task.Counter.MAP_OUTPUT_BYTES);
          if (outBytes == 0) {
            taskCompletionStatus = TaskCompletionEvent.Status.SUCCEEDED_NO_OUTPUT;
          }
        }
        taskEvent = new TaskCompletionEvent(
                                            taskCompletionEventTracker,
                                            taskid,
                                            tip.idWithinJob(),
                                            isMapTask,
                                            taskCompletionStatus,
                                            httpTaskLogLocation
                                           );
        taskEvent.setTaskRunTime((int)(status.getFinishTime()
                                       - status.getStartTime()));
        tip.setSuccessEventNumber(taskCompletionEventTracker);
      } else if (state == TaskStatus.State.COMMIT_PENDING) {
        // If it is the first attempt reporting COMMIT_PENDING
        // ask the task to commit.
        if (!wasComplete && !wasPending) {
          tip.doCommit(taskid);
        }
        return;
      } else if (state == TaskStatus.State.FAILED_UNCLEAN ||
                 state == TaskStatus.State.KILLED_UNCLEAN) {
        tip.incompleteSubTask(taskid, this.status);
        // add this task, to be rescheduled as cleanup attempt
        if (tip.isMapTask()) {
          mapCleanupTasks.add(taskid);
        } else {
          reduceCleanupTasks.add(taskid);
        }
        // Remove the task entry from jobtracker
        jobtracker.removeTaskEntry(taskid);
      }
      //For a failed task update the JT datastructures.
      else if (state == TaskStatus.State.FAILED ||
               state == TaskStatus.State.KILLED) {
        // Get the event number for the (possibly) previously successful
        // task. If there exists one, then set that status to OBSOLETE
        int eventNumber;
        if ((eventNumber = tip.getSuccessEventNumber()) != -1) {
          TaskCompletionEvent t =
            this.taskCompletionEvents.get(eventNumber);
          if (t.getTaskAttemptId().equals(taskid))
            t.setTaskStatus(TaskCompletionEvent.Status.OBSOLETE);
        }

        // Tell the job to fail the relevant task
        failedTask(tip, taskid, status, taskTracker,
                   wasRunning, wasComplete, wasAttemptRunning);

        // Did the task failure lead to tip failure?
        TaskCompletionEvent.Status taskCompletionStatus =
          (state == TaskStatus.State.FAILED ) ?
              TaskCompletionEvent.Status.FAILED :
              TaskCompletionEvent.Status.KILLED;
        if (tip.isFailed()) {
          taskCompletionStatus = TaskCompletionEvent.Status.TIPFAILED;
        }
        taskEvent = new TaskCompletionEvent(taskCompletionEventTracker,
                                            taskid,
                                            tip.idWithinJob(),
                                            status.getIsMap() &&
                                            !tip.isJobCleanupTask() &&
                                            !tip.isJobSetupTask(),
                                            taskCompletionStatus,
                                            httpTaskLogLocation
                                           );
      }

      // Add the 'complete' task i.e. successful/failed
      // It _is_ safe to add the TaskCompletionEvent.Status.SUCCEEDED
      // *before* calling TIP.completedTask since:
      // a. One and only one task of a TIP is declared as a SUCCESS, the
      //    other (speculative tasks) are marked KILLED by the TaskCommitThread
      // b. TIP.completedTask *does not* throw _any_ exception at all.
      if (taskEvent != null) {
        this.taskCompletionEvents.add(taskEvent);
        taskCompletionEventTracker++;
        JobTrackerStatistics.TaskTrackerStat ttStat = jobtracker.
           getStatistics().getTaskTrackerStat(tip.machineWhereTaskRan(taskid));
        if(ttStat != null) { // ttStat can be null in case of lost tracker
          ttStat.incrTotalTasks();
        }
        if (state == TaskStatus.State.SUCCEEDED) {
          completedTask(tip, status);
          if(ttStat != null) {
            ttStat.incrSucceededTasks();
          }
        }
        countersLog.info(status.getTaskID() + " completion counters "
            + status.getCounters().makeJsonString());
      }
    }

    //
    // Update JobInProgress status
    //
    if(LOG.isDebugEnabled()) {
      LOG.debug("Taking progress for " + tip.getTIPId() + " from " +
                 oldProgress + " to " + tip.getProgress());
    }

    if (!tip.isJobCleanupTask() && !tip.isJobSetupTask()) {
      double progressDelta = tip.getProgress() - oldProgress;
      if (tip.isMapTask()) {
          this.status.setMapProgress((float) (this.status.mapProgress() +
                                              progressDelta / maps.length));
      } else {
        this.status.setReduceProgress((float) (this.status.reduceProgress() +
                                           (progressDelta / reduces.length)));
      }
    }
  }

  String getHistoryFile() {
    return historyFile;
  }

  synchronized void setHistoryFile(String file) {
    this.historyFile = file;
  }

  boolean isHistoryFileCopied() {
    return historyFileCopied;
  }

  synchronized void setHistoryFileCopied() {
    this.historyFileCopied = true;
  }

  /**
   * Returns the job-level counters.
   *
   * @return the job-level counters.
   */
  public synchronized Counters getJobCounters() {
    return jobCounters;
  }

  /**
   *  Returns map phase counters by summing over all map tasks in progress.
   */
  public synchronized Counters getMapCounters() {
    return incrementTaskCounters(new Counters(), maps);
  }

  /**
   *  Returns map phase counters by summing over all map tasks in progress.
   */
  public synchronized Counters getReduceCounters() {
    return incrementTaskCounters(new Counters(), reduces);
  }

  /**
   *  Returns the total job counters, by adding together the job,
   *  the map and the reduce counters.
   */
  public Counters getCounters() {
    Counters result = new Counters();
    synchronized (this) {
      result.incrAllCounters(getJobCounters());
    }

    incrementTaskCounters(result, maps);
    return incrementTaskCounters(result, reduces);
  }

  /**
   * Increments the counters with the counters from each task.
   * @param counters the counters to increment
   * @param tips the tasks to add in to counters
   * @return counters the same object passed in as counters
   */
  private Counters incrementTaskCounters(Counters counters,
                                         TaskInProgress[] tips) {
    for (TaskInProgress tip : tips) {
      counters.incrAllCounters(tip.getCounters());
    }
    return counters;
  }

  /////////////////////////////////////////////////////
  // Create/manage tasks
  /////////////////////////////////////////////////////
  /**
   * Return a MapTask, if appropriate, to run on the given tasktracker
   */
  public synchronized Task obtainNewMapTask(TaskTrackerStatus tts,
                                            int clusterSize,
                                            int numUniqueHosts
                                           ) throws IOException {
    return obtainNewMapTask(tts, clusterSize, numUniqueHosts, anyCacheLevel);
  }
  /**
   * Return a MapTask, if appropriate, to run on the given tasktracker
   */
  public synchronized Task obtainNewMapTask(TaskTrackerStatus tts,
                                            int clusterSize,
                                            int numUniqueHosts,
                                            int maxCacheLevel
                                           ) throws IOException {
    if (status.getRunState() != JobStatus.RUNNING) {
      LOG.info("Cannot create task split for " + profile.getJobID());
      return null;
    }

    int target = findNewMapTask(tts, clusterSize, numUniqueHosts,
                                maxCacheLevel);
    if (target == -1) {
      return null;
    }

    Task result = maps[target].getTaskToRun(tts.getTrackerName());
    if (result != null) {
      addRunningTaskToTIP(maps[target], result.getTaskID(), tts, true);
    }

    return result;
  }

  public synchronized int neededSpeculativeMaps() {
    if (!hasSpeculativeMaps)
      return 0;
    return (candidateSpeculativeMaps != null) ?
      candidateSpeculativeMaps.size() : 0;
  }


  public synchronized int neededSpeculativeReduces() {
    if (!hasSpeculativeReduces)
      return 0;
    return (candidateSpeculativeReduces != null) ?
      candidateSpeculativeReduces.size() : 0;
  }

  /*
   * Return task cleanup attempt if any, to run on a given tracker
   */
  public Task obtainTaskCleanupTask(TaskTrackerStatus tts,
                                                 boolean isMapSlot)
  throws IOException {
    if (!tasksInited.get()) {
      return null;
    }

    if (this.status.getRunState() != JobStatus.RUNNING ||
        jobFailed || jobKilled) {
      return null;
    }

    if (isMapSlot) {
      if (mapCleanupTasks.isEmpty())
        return null;
    } else {
      if (reduceCleanupTasks.isEmpty())
        return null;
    }

    synchronized (this) {
      if (this.status.getRunState() != JobStatus.RUNNING ||
          jobFailed || jobKilled) {
        return null;
      }
      String taskTracker = tts.getTrackerName();
      if (!shouldRunOnTaskTracker(taskTracker)) {
        return null;
      }
      TaskAttemptID taskid = null;
      TaskInProgress tip = null;
      if (isMapSlot) {
        if (!mapCleanupTasks.isEmpty()) {
          taskid = mapCleanupTasks.remove(0);
          tip = maps[taskid.getTaskID().getId()];
        }
      } else {
        if (!reduceCleanupTasks.isEmpty()) {
          taskid = reduceCleanupTasks.remove(0);
          tip = reduces[taskid.getTaskID().getId()];
        }
      }
      if (tip != null) {
        return tip.addRunningTask(taskid, taskTracker, true);
      }
      return null;
    }
  }

  public synchronized Task obtainNewLocalMapTask(TaskTrackerStatus tts,
                                                     int clusterSize,
                                                     int numUniqueHosts)
  throws IOException {
    if (!tasksInited.get()) {
      LOG.info("Cannot create task split for " + profile.getJobID());
      return null;
    }

    int target = findNewMapTask(tts, clusterSize, numUniqueHosts, maxLevel);
    if (target == -1) {
      return null;
    }

    Task result = maps[target].getTaskToRun(tts.getTrackerName());
    if (result != null) {
      addRunningTaskToTIP(maps[target], result.getTaskID(), tts, true);
    }

    return result;
  }

  public synchronized Task obtainNewNonLocalMapTask(TaskTrackerStatus tts,
                                                    int clusterSize,
                                                    int numUniqueHosts)
  throws IOException {

    /* Added by RH Oct 8th, 2014 begins 
     * We forbid assigning a remote task in an encoding job */
    if (getJobConf().getEncoding()) {
        return null;
    }
    /* Added by RH Oct 8th, 2014 ends */
    if (!tasksInited.get()) {
      LOG.info("Cannot create task split for " + profile.getJobID());
      return null;
    }

    int target = findNewMapTask(tts, clusterSize, numUniqueHosts,
                                NON_LOCAL_CACHE_LEVEL);
    if (target == -1) {
      return null;
    }

    Task result = maps[target].getTaskToRun(tts.getTrackerName());
    if (result != null) {
      addRunningTaskToTIP(maps[target], result.getTaskID(), tts, true);
    }

    return result;
  }

  /**
   * Return a CleanupTask, if appropriate, to run on the given tasktracker
   *
   */
  public Task obtainJobCleanupTask(TaskTrackerStatus tts,
                                             int clusterSize,
                                             int numUniqueHosts,
                                             boolean isMapSlot
                                            ) throws IOException {
    if(!tasksInited.get() || !jobSetupCleanupNeeded) {
      return null;
    }

    synchronized(this) {
      if (!canLaunchJobCleanupTask()) {
        return null;
      }

      String taskTracker = tts.getTrackerName();
      // Update the last-known clusterSize
      this.clusterSize = clusterSize;
      if (!shouldRunOnTaskTracker(taskTracker)) {
        return null;
      }

      List<TaskInProgress> cleanupTaskList = new ArrayList<TaskInProgress>();
      if (isMapSlot) {
        cleanupTaskList.add(cleanup[0]);
      } else {
        cleanupTaskList.add(cleanup[1]);
      }
      TaskInProgress tip = findTaskFromList(cleanupTaskList,
                             tts, numUniqueHosts, false);
      if (tip == null) {
        return null;
      }

      // Now launch the cleanupTask
      Task result = tip.getTaskToRun(tts.getTrackerName());

      if (result != null) {
        addRunningTaskToTIP(tip, result.getTaskID(), tts, true);
        if (jobFailed) {
          result.setJobCleanupTaskState
          (org.apache.hadoop.mapreduce.JobStatus.State.FAILED);
        } else if (jobKilled) {
          result.setJobCleanupTaskState
          (org.apache.hadoop.mapreduce.JobStatus.State.KILLED);
        } else {
          result.setJobCleanupTaskState
          (org.apache.hadoop.mapreduce.JobStatus.State.SUCCEEDED);
        }
      }
      return result;
    }

  }

  /**
   * Check whether cleanup task can be launched for the job.
   *
   * Cleanup task can be launched if it is not already launched
   * or job is Killed
   * or all maps and reduces are complete
   * @return true/false
   */
  private synchronized boolean canLaunchJobCleanupTask() {
    // check if the job is running
    if (status.getRunState() != JobStatus.RUNNING &&
        status.getRunState() != JobStatus.PREP) {
      return false;
    }
    // check if cleanup task has been launched already or if setup isn't
    // launched already. The later check is useful when number of maps is
    // zero.
    if (launchedCleanup || !isSetupFinished()) {
      return false;
    }
    // check if job has failed or killed
    if (jobKilled || jobFailed) {
      return true;
    }

    boolean mapsDone = ((finishedMapTasks + failedMapTIPs) == (numMapTasks));
    boolean reducesDone = ((finishedReduceTasks + failedReduceTIPs) == numReduceTasks);
    boolean mapOnlyJob = (numReduceTasks == 0);

    if (mapOnlyJob) {
      return mapsDone;
    }
    if (jobFinishWhenReducesDone) {
      return reducesDone;
    }
    return mapsDone && reducesDone;
  }

  /**
   * Return a SetupTask, if appropriate, to run on the given tasktracker
   *
   */
  public Task obtainJobSetupTask(TaskTrackerStatus tts,
                                             int clusterSize,
                                             int numUniqueHosts,
                                             boolean isMapSlot
                                            ) throws IOException {
    if(!tasksInited.get() || !jobSetupCleanupNeeded) {
      return null;
    }

    synchronized(this) {
      if (!canLaunchSetupTask()) {
        return null;
      }
      String taskTracker = tts.getTrackerName();
      // Update the last-known clusterSize
      this.clusterSize = clusterSize;
      if (!shouldRunOnTaskTracker(taskTracker)) {
        return null;
      }

      List<TaskInProgress> setupTaskList = new ArrayList<TaskInProgress>();
      if (isMapSlot) {
        setupTaskList.add(setup[0]);
      } else {
        setupTaskList.add(setup[1]);
      }
      TaskInProgress tip = findTaskFromList(setupTaskList,
                             tts, numUniqueHosts, false);
      if (tip == null) {
        return null;
      }

      // Now launch the setupTask
      Task result = tip.getTaskToRun(tts.getTrackerName());
      if (result != null) {
        addRunningTaskToTIP(tip, result.getTaskID(), tts, true);
      }
      return result;
    }
  }

  /**
   * Can we start schedule reducers?
   * @return true/false
   */
  public synchronized boolean scheduleReduces() {
    // Start scheduling reducers if we have enough maps finished or
    // if the job has very few mappers or reducers.
    return numMapTasks <= rushReduceMaps ||
           numReduceTasks <= rushReduceReduces ||
           finishedMapTasks >= completedMapsForReduceSlowstart;
  }

  /**
   * Check whether setup task can be launched for the job.
   *
   * Setup task can be launched after the tasks are inited
   * and Job is in PREP state
   * and if it is not already launched
   * or job is not Killed/Failed
   * @return true/false
   */
  private synchronized boolean canLaunchSetupTask() {
    return (tasksInited.get() && status.getRunState() == JobStatus.PREP &&
           !launchedSetup && !jobKilled && !jobFailed);
  }


  /**
   * Return a ReduceTask, if appropriate, to run on the given tasktracker.
   * We don't have cache-sensitivity for reduce tasks, as they
   *  work on temporary MapRed files.
   */
  public synchronized Task obtainNewReduceTask(TaskTrackerStatus tts,
                                               int clusterSize,
                                               int numUniqueHosts
                                              ) throws IOException {
    if (status.getRunState() != JobStatus.RUNNING) {
      LOG.info("Cannot create task split for " + profile.getJobID());
      return null;
    }

    // Ensure we have sufficient map outputs ready to shuffle before
    // scheduling reduces
    if (!scheduleReduces()) {
      return null;
    }

    int  target = findNewReduceTask(tts, clusterSize, numUniqueHosts);
    if (target == -1) {
      return null;
    }

    Task result = reduces[target].getTaskToRun(tts.getTrackerName());
    if (result != null) {
      addRunningTaskToTIP(reduces[target], result.getTaskID(), tts, true);
    }

    return result;
  }

  // returns the (cache)level at which the nodes matches
  private int getMatchingLevelForNodes(Node n1, Node n2) {
    int count = 0;
    do {
      if (n1.equals(n2)) {
        return count;
      }
      ++count;
      n1 = n1.getParent();
      n2 = n2.getParent();
    } while (n1 != null && n2 != null);
    return this.maxLevel;
  }

  /**
   * Populate the data structures as a task is scheduled.
   *
   * Assuming {@link JobTracker} is locked on entry.
   *
   * @param tip The tip for which the task is added
   * @param id The attempt-id for the task
   * @param tts task-tracker status
   * @param isScheduled Whether this task is scheduled from the JT or has
   *        joined back upon restart
   */
  synchronized void addRunningTaskToTIP(TaskInProgress tip, TaskAttemptID id,
                                        TaskTrackerStatus tts,
                                        boolean isScheduled) {
    // Make an entry in the tip if the attempt is not scheduled i.e externally
    // added
    if (!isScheduled) {
      tip.addRunningTask(id, tts.getTrackerName());
    }
    final JobTrackerInstrumentation metrics = jobtracker.getInstrumentation();

    // keeping the earlier ordering intact
    String name;
    String splits = "";
    Enum counter = null;
    if (tip.isJobSetupTask()) {
      launchedSetup = true;
      name = Values.SETUP.name();
    } else if (tip.isJobCleanupTask()) {
      launchedCleanup = true;
      name = Values.CLEANUP.name();
    } else if (tip.isMapTask()) {
      if (firstMapStartTime == 0) {
        firstMapStartTime = JobTracker.getClock().getTime();
      }
      name = Values.MAP.name();
      counter = Counter.TOTAL_LAUNCHED_MAPS;
      splits = tip.getSplitNodes();
      if (tip.getActiveTasks().size() > 1) {
        speculativeMapTasks++;
        if (!garbageCollected) {
          totalSpeculativeMapTasks.incrementAndGet();
        }
        metrics.speculateMap(id, tip.isUsingProcessingRateForSpeculation());
      }
      metrics.launchMap(id);
    } else {
      if (firstReduceStartTime == 0) {
        firstReduceStartTime = JobTracker.getClock().getTime();
      }
      name = Values.REDUCE.name();
      counter = Counter.TOTAL_LAUNCHED_REDUCES;
      if (tip.getActiveTasks().size() > 1) {
        speculativeReduceTasks++;
        if (!garbageCollected) {
          totalSpeculativeReduceTasks.incrementAndGet();
        }
        metrics.speculateReduce(id, tip.isUsingProcessingRateForSpeculation());
      }
      metrics.launchReduce(id);
    }
    // Note that the logs are for the scheduled tasks only. Tasks that join on
    // restart has already their logs in place.
    if (tip.isFirstAttempt(id)) {
      JobHistory.Task.logStarted(tip.getTIPId(), name,
                                 tip.getExecStartTime(), splits);
    }
    if (!tip.isJobSetupTask() && !tip.isJobCleanupTask()) {
      jobCounters.incrCounter(counter, 1);
    }

    //TODO The only problem with these counters would be on restart.
    // The jobtracker updates the counter only when the task that is scheduled
    // if from a non-running tip and is local (data, rack ...). But upon restart
    // as the reports come from the task tracker, there is no good way to infer
    // when exactly to increment the locality counters. The only solution is to
    // increment the counters for all the tasks irrespective of
    //    - whether the tip is running or not
    //    - whether its a speculative task or not
    //
    // So to simplify, increment the data locality counter whenever there is
    // data locality.
    if (tip.isMapTask() && !tip.isJobSetupTask() && !tip.isJobCleanupTask()) {
      // increment the data locality counter for maps
      Node tracker = jobtracker.getNode(tts.getHost());
      int level = this.maxLevel;
      // find the right level across split locations
      for (String local : maps[tip.getIdWithinJob()].getSplitLocations()) {
        Node datanode = jobtracker.getNode(local);
        int newLevel = this.maxLevel;
        if (tracker != null && datanode != null) {
          newLevel = getMatchingLevelForNodes(tracker, datanode);
        }
        if (newLevel < level) {
          level = newLevel;
          // an optimization
          if (level == 0) {
            break;
          }
        }
      }
      switch (level) {
      case 0 :
        LOG.info("Choosing data-local task " + tip.getTIPId());
        jobCounters.incrCounter(Counter.DATA_LOCAL_MAPS, 1);
        metrics.launchDataLocalMap(id);
        break;
      case 1:
        LOG.info("Choosing rack-local task " + tip.getTIPId());
        jobCounters.incrCounter(Counter.RACK_LOCAL_MAPS, 1);
        metrics.launchRackLocalMap(id);
        break;
      default :
        // check if there is any locality
        if (level != this.maxLevel) {
          LOG.info("Choosing cached task at level " + level + tip.getTIPId());
          jobCounters.incrCounter(Counter.OTHER_LOCAL_MAPS, 1);
        }
        break;
      }
    }
  }

  /**
   * Note that a task has failed on a given tracker and add the tracker
   * to the blacklist iff too many trackers in the cluster i.e.
   * (clusterSize * CLUSTER_BLACKLIST_PERCENT) haven't turned 'flaky' already.
   *
   * @param taskTracker task-tracker on which a task failed
   */
  synchronized void addTrackerTaskFailure(String trackerName,
                                          TaskTracker taskTracker,
                                          String lastFailureReason) {
    if (flakyTaskTrackers < (clusterSize * CLUSTER_BLACKLIST_PERCENT)) {
      String trackerHostName = convertTrackerNameToHostName(trackerName);

      List<String> trackerFailures = trackerToFailuresMap.get(trackerHostName);

      if (trackerFailures == null) {
        trackerFailures = new LinkedList<String>();
        trackerToFailuresMap.put(trackerHostName, trackerFailures);
      }
      trackerFailures.add(lastFailureReason);

      // Check if this tasktracker has turned 'flaky'
      if (trackerFailures.size() == maxTaskFailuresPerTracker) {
        ++flakyTaskTrackers;

        // Cancel reservations if appropriate
        if (taskTracker != null) {
          if (trackersReservedForMaps.containsKey(taskTracker)) {
            taskTracker.unreserveSlots(TaskType.MAP, this);
          }
          if (trackersReservedForReduces.containsKey(taskTracker)) {
            taskTracker.unreserveSlots(TaskType.REDUCE, this);
          }
        }
        LOG.info("TaskTracker at '" + trackerHostName + "' turned 'flaky'");
      }
    }
  }

  public synchronized void reserveTaskTracker(TaskTracker taskTracker,
                                              TaskType type, int numSlots) {
    Map<TaskTracker, FallowSlotInfo> map =
      (type == TaskType.MAP) ? trackersReservedForMaps : trackersReservedForReduces;

    long now = JobTracker.getClock().getTime();

    FallowSlotInfo info = map.get(taskTracker);
    int reservedSlots = 0;
    if (info == null) {
      info = new FallowSlotInfo(now, numSlots);
      reservedSlots = numSlots;
    } else {
      // Increment metering info if the reservation is changing
      if (info.getNumSlots() != numSlots) {
        Enum<Counter> counter =
          (type == TaskType.MAP) ?
              Counter.FALLOW_SLOTS_MILLIS_MAPS :
              Counter.FALLOW_SLOTS_MILLIS_REDUCES;
        long fallowSlotMillis = (now - info.getTimestamp()) * info.getNumSlots();
        jobCounters.incrCounter(counter, fallowSlotMillis);

        // Update
        reservedSlots = numSlots - info.getNumSlots();
        info.setTimestamp(now);
        info.setNumSlots(numSlots);
      }
    }
    map.put(taskTracker, info);
    if (type == TaskType.MAP) {
      jobtracker.getInstrumentation().addReservedMapSlots(reservedSlots);
    }
    else {
      jobtracker.getInstrumentation().addReservedReduceSlots(reservedSlots);
    }
    jobtracker.incrementReservations(type, reservedSlots);
  }

  public synchronized void unreserveTaskTracker(TaskTracker taskTracker,
                                                TaskType type) {
    Map<TaskTracker, FallowSlotInfo> map =
      (type == TaskType.MAP) ? trackersReservedForMaps :
                               trackersReservedForReduces;

    FallowSlotInfo info = map.get(taskTracker);
    if (info == null) {
      LOG.warn("Cannot find information about fallow slots for " +
               taskTracker.getTrackerName());
      return;
    }

    long now = JobTracker.getClock().getTime();

    Enum<Counter> counter =
      (type == TaskType.MAP) ?
          Counter.FALLOW_SLOTS_MILLIS_MAPS :
          Counter.FALLOW_SLOTS_MILLIS_REDUCES;
    long fallowSlotMillis = (now - info.getTimestamp()) * info.getNumSlots();
    jobCounters.incrCounter(counter, fallowSlotMillis);

    map.remove(taskTracker);
    if (type == TaskType.MAP) {
      jobtracker.getInstrumentation().decReservedMapSlots(info.getNumSlots());
    }
    else {
      jobtracker.getInstrumentation().decReservedReduceSlots(
        info.getNumSlots());
    }
    jobtracker.decrementReservations(type, info.getNumSlots());
  }

  public int getNumReservedTaskTrackersForMaps() {
    return trackersReservedForMaps.size();
  }

  public int getNumReservedTaskTrackersForReduces() {
    return trackersReservedForReduces.size();
  }

  private int getTrackerTaskFailures(String trackerName) {
    String trackerHostName = convertTrackerNameToHostName(trackerName);
    List<String> failedTasks = trackerToFailuresMap.get(trackerHostName);
    return (failedTasks != null) ? failedTasks.size() : 0;
  }

  /**
   * Get the black listed trackers for the job and corresponding errors.
   *
   * @return Map of blacklisted tracker names and the errors for each tracker
   *         that triggered blacklisting
   */
  Map<String, List<String>> getBlackListedTrackers() {

    Map<String, List<String>> blackListedTrackers
        = new HashMap<String, List<String>>();

    for (Map.Entry<String,List<String>> e : trackerToFailuresMap.entrySet()) {
       if (e.getValue().size() >= maxTaskFailuresPerTracker) {
         blackListedTrackers.put(e.getKey(), e.getValue());
       }
    }
    return blackListedTrackers;
  }

  /**
   * Get the no. of 'flaky' tasktrackers for a given job.
   *
   * @return the no. of 'flaky' tasktrackers for a given job.
   */
  int getNoOfBlackListedTrackers() {
    return flakyTaskTrackers;
  }

  /**
   * Get the information on tasktrackers and no. of errors which occurred
   * on them for a given job.
   *
   * @return the map of tasktrackers and no. of errors which occurred
   *         on them for a given job.
   */
  synchronized Map<String, List<String>> getTaskTrackerErrors() {
    // Clone the 'trackerToFailuresMap' and return the copy
    Map<String, List<String>> trackerErrors =
      new TreeMap<String, List<String>>(trackerToFailuresMap);
    return trackerErrors;
  }

  /**
   * Remove a map TIP from the lists for running maps.
   * Called when a map fails/completes (note if a map is killed,
   * it won't be present in the list since it was completed earlier)
   * @param tip the tip that needs to be retired
   */
  private synchronized void retireMap(TaskInProgress tip) {
    if (runningMapCache == null) {
      LOG.warn("Running cache for maps missing!! "
               + "Job details are missing.");
      return;
    }

    String[] splitLocations = tip.getSplitLocations();

    // Remove the TIP from the list for running non-local maps
    if (splitLocations.length == 0) {
      nonLocalRunningMaps.remove(tip);
      return;
    }

    // Remove from the running map caches
    for(String host: splitLocations) {
      Node node = jobtracker.getNode(host);

      for (int j = 0; j < maxLevel; ++j) {
        Set<TaskInProgress> hostMaps = runningMapCache.get(node);
        if (hostMaps != null) {
          hostMaps.remove(tip);
          if (hostMaps.size() == 0) {
            runningMapCache.remove(node);
          }
        }
        node = node.getParent();
      }
    }
  }

  /**
   * Remove a reduce TIP from the list for running-reduces
   * Called when a reduce fails/completes
   * @param tip the tip that needs to be retired
   */
  private synchronized void retireReduce(TaskInProgress tip) {
    if (runningReduces == null) {
      LOG.warn("Running list for reducers missing!! "
               + "Job details are missing.");
      return;
    }
    runningReduces.remove(tip);
  }

  /**
   * Adds a map tip to the list of running maps.
   * @param tip the tip that needs to be scheduled as running
   */
  protected synchronized void scheduleMap(TaskInProgress tip) {
    runningMapTaskStats.add(0.0f);
    runningTaskMapByteProcessingRateStats.add(0.0f);
    if (runningMapCache == null) {
      LOG.warn("Running cache for maps is missing!! "
               + "Job details are missing.");
      return;
    }
    String[] splitLocations = tip.getSplitLocations();

    // Add the TIP to the list of non-local running TIPs
    if (splitLocations.length == 0) {
      nonLocalRunningMaps.add(tip);
      return;
    }

    for(String host: splitLocations) {
      Node node = jobtracker.getNode(host);

      for (int j = 0; j < maxLevel; ++j) {
        Set<TaskInProgress> hostMaps = runningMapCache.get(node);
        if (hostMaps == null) {
          // create a cache if needed
          hostMaps = new LinkedHashSet<TaskInProgress>();
          runningMapCache.put(node, hostMaps);
        }
        hostMaps.add(tip);
        node = node.getParent();
      }
    }
  }

  /**
   * Adds a reduce tip to the list of running reduces
   * @param tip the tip that needs to be scheduled as running
   */
  protected synchronized void scheduleReduce(TaskInProgress tip) {
    runningReduceTaskStats.add(0.0f);
    runningTaskCopyProcessingRateStats.add(0.0f);
    runningTaskSortProcessingRateStats.add(0.0f);
    runningTaskReduceProcessingRateStats.add(0.0f);
    if (runningReduces == null) {
      LOG.warn("Running cache for reducers missing!! "
               + "Job details are missing.");
      return;
    }
    runningReduces.add(tip);
  }

  /**
   * Adds the failed TIP in the front of the list for non-running maps
   * @param tip the tip that needs to be failed
   */
  private synchronized void failMap(TaskInProgress tip) {
    if (nonRunningMapCache == null) {
      LOG.warn("Non-running cache for maps missing!! "
               + "Job details are missing.");
      return;
    }

    // 1. Its added everywhere since other nodes (having this split local)
    //    might have removed this tip from their local cache
    // 2. Give high priority to failed tip - fail early

    String[] splitLocations = tip.getSplitLocations();

    // Add the TIP in the front of the list for non-local non-running maps
    if (splitLocations.length == 0) {
      nonLocalMaps.add(0, tip);
      return;
    }

    for(String host: splitLocations) {
      Node node = jobtracker.getNode(host);

      for (int j = 0; j < maxLevel; ++j) {
        List<TaskInProgress> hostMaps = nonRunningMapCache.get(node);
        if (hostMaps == null) {
          hostMaps = new LinkedList<TaskInProgress>();
          nonRunningMapCache.put(node, hostMaps);
        }
        hostMaps.add(0, tip);
        node = node.getParent();
      }
    }
  }

  /**
   * Adds a failed TIP in the front of the list for non-running reduces
   * @param tip the tip that needs to be failed
   */
  private synchronized void failReduce(TaskInProgress tip) {
    if (nonRunningReduces == null) {
      LOG.warn("Failed cache for reducers missing!! "
               + "Job details are missing.");
      return;
    }
    nonRunningReduces.add(0, tip);
  }

  /**
   * Find a non-running task in the passed list of TIPs
   * @param tips a collection of TIPs
   * @param ttStatus the status of tracker that has requested a task to run
   * @param numUniqueHosts number of unique hosts that run trask trackers
   * @param removeFailedTip whether to remove the failed tips
   */
  private synchronized TaskInProgress findTaskFromList(
      Collection<TaskInProgress> tips, TaskTrackerStatus ttStatus,
      int numUniqueHosts,
      boolean removeFailedTip) {
    Iterator<TaskInProgress> iter = tips.iterator();
    while (iter.hasNext()) {
      TaskInProgress tip = iter.next();

      // Select a tip if
      //   1. runnable   : still needs to be run and is not completed
      //   2. ~running   : no other node is running it
      //   3. earlier attempt failed : has not failed on this host
      //                               and has failed on all the other hosts
      // A TIP is removed from the list if
      // (1) this tip is scheduled
      // (2) if the passed list is a level 0 (host) cache
      // (3) when the TIP is non-schedulable (running, killed, complete)
      if (tip.isRunnable() && !tip.isRunning()) {
        // check if the tip has failed on this host
        if (!tip.hasFailedOnMachine(ttStatus.getHost()) ||
             tip.getNumberOfFailedMachines() >= numUniqueHosts) {
          // check if the tip has failed on all the nodes
          iter.remove();
          return tip;
        } else if (removeFailedTip) {
          // the case where we want to remove a failed tip from the host cache
          // point#3 in the TIP removal logic above
          iter.remove();
        }
      } else {
        // see point#3 in the comment above for TIP removal logic
        iter.remove();
      }
    }
    return null;
  }

  @Override
  public boolean hasSpeculativeMaps() {
    return hasSpeculativeMaps;
  }

  @Override
  public boolean hasSpeculativeReduces() {
    return hasSpeculativeReduces;
  }


  @Override
  public boolean shouldLogCannotspeculativeMaps() {
    long now = JobTracker.getClock().getTime();
    if ((now - lastTimeCannotspeculativeMapLog) <= logCannotspeculativeInterval)
        return false;
    int unfinished = desiredMaps() - finishedMaps();
    if (unfinished <= desiredMaps() * speculativeMapLogRateThreshold ||
        unfinished <= speculativeMapLogNumThreshold) {
        lastTimeCannotspeculativeMapLog = now; 
        return true;
    }
    return false;
  }

  
  @Override
  public boolean shouldLogCannotspeculativeReduces() {
    long now = JobTracker.getClock().getTime();
    if ((now - lastTimeCannotspeculativeReduceLog) <= logCannotspeculativeInterval)
      return false;
    int unfinished = desiredReduces() - finishedReduces();
    if (unfinished <= desiredReduces() * speculativeReduceLogRateThreshold ||
        unfinished <= speculativeReduceLogNumThreshold) {
        lastTimeCannotspeculativeReduceLog = now; 
        return true;
    }
    return false;
  }

  @Override
  public boolean shouldSpeculateAllRemainingMaps() {
    if (speculativeMapUnfininshedThreshold == 0) {
      return false;
    }
    int unfinished = desiredMaps() - finishedMaps();
    if (unfinished < desiredMaps() * speculativeMapUnfininshedThreshold ||
        unfinished == 1) {
      return true;
    }
    return false;
  }

  @Override
  public boolean shouldSpeculateAllRemainingReduces() {
    if (speculativeReduceUnfininshedThreshold == 0) {
      return false;
    }
    int unfinished = desiredReduces() - finishedReduces();
    if (unfinished < desiredReduces() * speculativeReduceUnfininshedThreshold ||
        unfinished == 1) {
      return true;
    }
    return false;
  }

  /**
   * Given a candidate set of tasks, find and order the ones that
   * can be speculated and return the same.
   */
  protected synchronized List<TaskInProgress> findSpeculativeTaskCandidates
    (Collection<TaskInProgress> list) {
    ArrayList<TaskInProgress> candidates = new ArrayList<TaskInProgress>();

    long now = JobTracker.getClock().getTime();
    Iterator<TaskInProgress> iter = list.iterator();
    while (iter.hasNext()) {
      TaskInProgress tip = iter.next();
      if (tip.canBeSpeculated(now)) {
        candidates.add(tip);
      }
    }
    if (candidates.size() > 0 ) {
      Comparator<TaskInProgress> LateComparator =
        new EstimatedTimeLeftComparator(now);

      Collections.sort(candidates, LateComparator);
    }
    return candidates;
  }

  protected synchronized TaskInProgress findSpeculativeTask(
      List<TaskInProgress> candidates, String taskTrackerName,
      String taskTrackerHost, TaskType taskType) {
    if ((candidates == null) || candidates.isEmpty()) {
      return null;
    }

    if (isSlowTracker(taskTrackerName) || atSpeculativeCap(taskType)) {
      return null;
    }

    long now = JobTracker.getClock().getTime();
    Iterator<TaskInProgress> iter = candidates.iterator();
    while (iter.hasNext()) {
      TaskInProgress tip = iter.next();
      if (tip.hasRunOnMachine(taskTrackerHost, taskTrackerName)) {
        continue;
      }

      // either we are going to speculate this task or it's not speculatable
      iter.remove();

      if (!tip.canBeSpeculated(now)) {
        // if it can't be speculated, then:
        // A. it has completed/failed etc. - in which case makes sense to never
        //    speculate again
        // B. it's relative progress does not allow speculation. in this case
        //    it's fair to treat it as if it was never eligible for speculation
        //    to begin with.
        continue;
      }

      if(tip.isUsingProcessingRateForSpeculation()) {
        LOG.info("Using processing rate for speculation. Chose task " +
            tip.getTIPId() + " to speculate." +
            " Phase: " + tip.getProcessingPhase() +
            " Statistics: Task's : " +
            tip.getProcessingRate(tip.getProcessingPhase()) +
            " Job's : " + getRunningTaskStatistics(tip.getProcessingPhase()));
      } else {
        LOG.info("Using progress rate for speculation. Chose task " +
            tip.getTIPId() + " to speculate." +
            " Statistics: Task's : " +
            tip.getProgressRate() +
            " Job's : " + (tip.isMapTask() ?
                runningMapTaskStats : runningReduceTaskStats));
      }
      return tip;
    }
    return null;
  }

  /**
   * Find new map task
   * @param tts The task tracker that is asking for a task
   * @param clusterSize The number of task trackers in the cluster
   * @param numUniqueHosts The number of hosts that run task trackers
   * @param avgProgress The average progress of this kind of task in this job
   * @param maxCacheLevel The maximum topology level until which to schedule
   *                      maps.
   *                      A value of {@link #anyCacheLevel} implies any
   *                      available task (node-local, rack-local, off-switch and
   *                      speculative tasks).
   *                      A value of {@link #NON_LOCAL_CACHE_LEVEL} implies only
   *                      off-switch/speculative tasks should be scheduled.
   * @return the index in tasks of the selected task (or -1 for no task)
   */
  private synchronized int findNewMapTask(final TaskTrackerStatus tts,
                                          final int clusterSize,
                                          final int numUniqueHosts,
                                          final int maxCacheLevel) {
    if (numMapTasks == 0) {
      if(LOG.isDebugEnabled()) {
        LOG.debug("No maps to schedule for " + profile.getJobID());
      }
      return -1;
    }

    String taskTracker = tts.getTrackerName();
    TaskInProgress tip = null;

    //
    // Update the last-known clusterSize
    //
    this.clusterSize = clusterSize;

    if (!shouldRunOnTaskTracker(taskTracker)) {
      return -1;
    }

    // Check to ensure this TaskTracker has enough resources to
    // run tasks from this job
    long outSize = resourceEstimator.getEstimatedMapOutputSize();
    long availSpace = tts.getResourceStatus().getAvailableSpace();
    final long SAVETY_BUFFER =
      conf.getLong("mapred.map.reserved.disk.mb", 300) * 1024 * 1024;
    if (availSpace < outSize + SAVETY_BUFFER) {
      LOG.warn("No room for map task. Node " + tts.getHost() +
               " has " + availSpace +
               " bytes free; The safty buffer is " + SAVETY_BUFFER +
               " bytes; but we expect map to take " + outSize);

      return -1; //see if a different TIP might work better.
    }


    // For scheduling a map task, we have two caches and a list (optional)
    //  I)   one for non-running task
    //  II)  one for running task (this is for handling speculation)
    //  III) a list of TIPs that have empty locations (e.g., dummy splits),
    //       the list is empty if all TIPs have associated locations

    // First a look up is done on the non-running cache and on a miss, a look
    // up is done on the running cache. The order for lookup within the cache:
    //   1. from local node to root [bottom up]
    //   2. breadth wise for all the parent nodes at max level

    // We fall to linear scan of the list (III above) if we have misses in the
    // above caches

    Node node = jobtracker.getNode(tts.getHost());

    //
    // I) Non-running TIP :
    //

    // 1. check from local node to the root [bottom up cache lookup]
    //    i.e if the cache is available and the host has been resolved
    //    (node!=null)
    if (node != null) {
      Node key = node;
      int level = 0;
      // maxCacheLevel might be greater than this.maxLevel if findNewMapTask is
      // called to schedule any task (local, rack-local, off-switch or speculative)
      // tasks or it might be NON_LOCAL_CACHE_LEVEL (i.e. -1) if findNewMapTask is
      //  (i.e. -1) if findNewMapTask is to only schedule off-switch/speculative
      // tasks
      int maxLevelToSchedule = Math.min(maxCacheLevel, maxLevel);
      for (level = 0;level < maxLevelToSchedule; ++level) {
        List <TaskInProgress> cacheForLevel = nonRunningMapCache.get(key);
        if (cacheForLevel != null) {
          tip = findTaskFromList(cacheForLevel, tts,
              numUniqueHosts,level == 0);
          if (tip != null) {
            // Add to running cache
            scheduleMap(tip);

            // remove the cache if its empty
            if (cacheForLevel.size() == 0) {
              nonRunningMapCache.remove(key);
            }

            return tip.getIdWithinJob();
          }
        }
        key = key.getParent();
      }

      // Check if we need to only schedule a local task (node-local/rack-local)
      if (level == maxCacheLevel) {
        return -1;
      }
    }

    //2. Search breadth-wise across parents at max level for non-running
    //   TIP if
    //     - cache exists and there is a cache miss
    //     - node information for the tracker is missing (tracker's topology
    //       info not obtained yet)

    // collection of node at max level in the cache structure
    Collection<Node> nodesAtMaxLevel = jobtracker.getNodesAtMaxLevel();

    // get the node parent at max level
    Node nodeParentAtMaxLevel =
      (node == null) ? null : JobTracker.getParentNode(node, maxLevel - 1);

    for (Node parent : nodesAtMaxLevel) {

      // skip the parent that has already been scanned
      if (parent == nodeParentAtMaxLevel) {
        continue;
      }

      List<TaskInProgress> cache = nonRunningMapCache.get(parent);
      if (cache != null) {
        tip = findTaskFromList(cache, tts, numUniqueHosts, false);
        if (tip != null) {
          // Add to the running cache
          scheduleMap(tip);

          // remove the cache if empty
          if (cache.size() == 0) {
            nonRunningMapCache.remove(parent);
          }
          LOG.info("Choosing a non-local task " + tip.getTIPId());
          return tip.getIdWithinJob();
        }
      }
    }

    // 3. Search non-local tips for a new task
    tip = findTaskFromList(nonLocalMaps, tts, numUniqueHosts, false);
    if (tip != null) {
      // Add to the running list
      scheduleMap(tip);

      LOG.info("Choosing a non-local task " + tip.getTIPId());
      return tip.getIdWithinJob();
    }

    //
    // II) Running TIP :
    //

    if (hasSpeculativeMaps) {
      tip = getSpeculativeMap(tts.getTrackerName(), tts.getHost());
      if (tip != null) {
        LOG.info("Choosing a non-local task " + tip.getTIPId()
                 + " for speculation");
        return tip.getIdWithinJob();
      }
    }

    return -1;
  }

  private synchronized TaskInProgress getSpeculativeMap(String taskTrackerName,
      String taskTrackerHost) {

    ///////// Select a TIP to run on
    TaskInProgress tip = findSpeculativeTask(candidateSpeculativeMaps, taskTrackerName,
        taskTrackerHost, TaskType.MAP);

    if (tip != null) {
      LOG.info("Choosing map task " + tip.getTIPId() +
               " for speculative execution");
    } else {
      if (LOG.isDebugEnabled()) {
        LOG.debug("No speculative map task found for tracker " + taskTrackerName);
      }
    }

    return tip;
  }

  /**
   * Find new reduce task
   * @param tts The task tracker that is asking for a task
   * @param clusterSize The number of task trackers in the cluster
   * @param numUniqueHosts The number of hosts that run task trackers
   * @param avgProgress The average progress of this kind of task in this job
   * @return the index in tasks of the selected task (or -1 for no task)
   */
  private synchronized int findNewReduceTask(TaskTrackerStatus tts,
                                             int clusterSize,
                                             int numUniqueHosts) {
    if (numReduceTasks == 0) {
      if(LOG.isDebugEnabled()) {
        LOG.debug("No reduces to schedule for " + profile.getJobID());
      }
      return -1;
    }

    String taskTracker = tts.getTrackerName();
    TaskInProgress tip = null;

    // Update the last-known clusterSize
    this.clusterSize = clusterSize;

    if (!shouldRunOnTaskTracker(taskTracker)) {
      return -1;
    }

    long outSize = resourceEstimator.getEstimatedReduceInputSize();
    long availSpace = tts.getResourceStatus().getAvailableSpace();
    final long SAVETY_BUFFER =
      conf.getLong("mapred.reduce.reserved.disk.mb", 300) * 1024 * 1024;
    if (availSpace < outSize + SAVETY_BUFFER) {
      LOG.warn("No room for reduce task. Node " + taskTracker +
               " has " + availSpace +
               " bytes free; The safty buffer is " + SAVETY_BUFFER +
               " bytes; but we expect map to take " + outSize);

      return -1; //see if a different TIP might work better.
    }

    // 1. check for a never-executed reduce tip
    // reducers don't have a cache and so pass -1 to explicitly call that out
    tip = findTaskFromList(nonRunningReduces, tts, numUniqueHosts, false);
    if (tip != null) {
      scheduleReduce(tip);
      return tip.getIdWithinJob();
    }

    // 2. check for a reduce tip to be speculated
    if (hasSpeculativeReduces) {
      tip = getSpeculativeReduce(tts.getTrackerName(), tts.getHost());
      if (tip != null) {
        scheduleReduce(tip);
        return tip.getIdWithinJob();
      }
    }

    return -1;
  }

  private synchronized TaskInProgress getSpeculativeReduce(
      String taskTrackerName, String taskTrackerHost) {

    TaskInProgress tip = findSpeculativeTask(
        candidateSpeculativeReduces, taskTrackerName, taskTrackerHost, TaskType.REDUCE);

    if (tip != null) {
      LOG.info("Choosing reduce task " + tip.getTIPId() +
                " for speculative execution");
    } else {
      if (LOG.isDebugEnabled()) {
        LOG.debug("No speculative reduce task found for tracker " + taskTrackerHost);
      }
    }
    return tip;
  }

  private boolean shouldRunOnTaskTracker(String taskTracker) {
    //
    // Check if too many tasks of this job have failed on this
    // tasktracker prior to assigning it a new one.
    //
    int taskTrackerFailedTasks = getTrackerTaskFailures(taskTracker);
    if ((flakyTaskTrackers < (clusterSize * CLUSTER_BLACKLIST_PERCENT)) &&
        taskTrackerFailedTasks >= maxTaskFailuresPerTracker) {
      if (LOG.isDebugEnabled()) {
        String flakyTracker = convertTrackerNameToHostName(taskTracker);
        LOG.debug("Ignoring the black-listed tasktracker: '" + flakyTracker
                  + "' for assigning a new task");
      }
      return false;
    }
    return true;
  }


  /**
   * Metering: Occupied Slots * (Finish - Start)
   * @param tip {@link TaskInProgress} to be metered which just completed,
   *            cannot be <code>null</code>
   * @param status {@link TaskStatus} of the completed task, cannot be
   *               <code>null</code>
   */
  private void meterTaskAttempt(TaskInProgress tip, TaskStatus status) {
    Counter slotCounter =
      (tip.isMapTask()) ? Counter.SLOTS_MILLIS_MAPS :
                          Counter.SLOTS_MILLIS_REDUCES;
    jobCounters.incrCounter(slotCounter,
                            tip.getNumSlotsRequired() *
                            (status.getFinishTime() - status.getStartTime()));
    if (!tip.isMapTask()) {
      jobCounters.incrCounter(Counter.SLOTS_MILLIS_REDUCES_COPY,
                  tip.getNumSlotsRequired() *
                  (status.getShuffleFinishTime() - status.getStartTime()));
      jobCounters.incrCounter(Counter.SLOTS_MILLIS_REDUCES_SORT,
                  tip.getNumSlotsRequired() *
                  (status.getSortFinishTime() - status.getShuffleFinishTime()));
      jobCounters.incrCounter(Counter.SLOTS_MILLIS_REDUCES_REDUCE,
                  tip.getNumSlotsRequired() *
                  (status.getFinishTime() - status.getSortFinishTime()));
    }
  }

  /**
   * A taskid assigned to this JobInProgress has reported in successfully.
   */
  public synchronized boolean completedTask(TaskInProgress tip,
                                            TaskStatus status)
  {
    TaskAttemptID taskid = status.getTaskID();
    int oldNumAttempts = tip.getActiveTasks().size();
    final JobTrackerInstrumentation metrics = jobtracker.getInstrumentation();

    // Metering
    meterTaskAttempt(tip, status);

    // Sanity check: is the TIP already complete?
    // It _is_ safe to not decrement running{Map|Reduce}Tasks and
    // finished{Map|Reduce}Tasks variables here because one and only
    // one task-attempt of a TIP gets to completedTask. This is because
    // the TaskCommitThread in the JobTracker marks other, completed,
    // speculative tasks as _complete_.
    if (tip.isComplete()) {
      // Mark this task as KILLED
      tip.alreadyCompletedTask(taskid);

      // Let the JobTracker cleanup this taskid if the job isn't running
      if (this.status.getRunState() != JobStatus.RUNNING) {
        jobtracker.markCompletedTaskAttempt(status.getTaskTracker(), taskid);
      }
      return false;
    }


    LOG.info("Task '" + taskid + "' has completed " + tip.getTIPId() +
             " successfully.");

    // Mark the TIP as complete
    tip.completed(taskid);
    resourceEstimator.updateWithCompletedTask(status, tip);

    // Update jobhistory
    TaskTrackerStatus ttStatus =
      this.jobtracker.getTaskTrackerStatus(status.getTaskTracker());
    String trackerHostname = jobtracker.getNode(ttStatus.getHost()).toString();
    String taskType = getTaskType(tip);
    if (status.getIsMap()){
      JobHistory.MapAttempt.logStarted(status.getTaskID(), status.getStartTime(),
                                       status.getTaskTracker(),
                                       ttStatus.getHttpPort(),
                                       taskType);
      JobHistory.MapAttempt.logFinished(status.getTaskID(), status.getFinishTime(),
                                        trackerHostname, taskType,
                                        status.getStateString(),
                                        status.getCounters());
    }else{
      JobHistory.ReduceAttempt.logStarted( status.getTaskID(), status.getStartTime(),
                                          status.getTaskTracker(),
                                          ttStatus.getHttpPort(),
                                          taskType);
      JobHistory.ReduceAttempt.logFinished(status.getTaskID(), status.getShuffleFinishTime(),
                                           status.getSortFinishTime(), status.getFinishTime(),
                                           trackerHostname,
                                           taskType,
                                           status.getStateString(),
                                           status.getCounters());
    }
    JobHistory.Task.logFinished(tip.getTIPId(),
                                taskType,
                                tip.getExecFinishTime(),
                                status.getCounters());

    int newNumAttempts = tip.getActiveTasks().size();
    if (tip.isJobSetupTask()) {
      // setup task has finished. kill the extra setup tip
      killSetupTip(!tip.isMapTask());
      setupComplete();
    } else if (tip.isJobCleanupTask()) {
      // cleanup task has finished. Kill the extra cleanup tip
      if (tip.isMapTask()) {
        // kill the reduce tip
        cleanup[1].kill();
      } else {
        cleanup[0].kill();
      }

      //
      // The Job is done
      // if the job is failed, then mark the job failed.
      if (jobFailed) {
        terminateJob(JobStatus.FAILED);
      }
      // if the job is killed, then mark the job killed.
      if (jobKilled) {
        terminateJob(JobStatus.KILLED);
      }
      else {
        jobComplete();
      }
      // The job has been killed/failed/successful
      // JobTracker should cleanup this task
      jobtracker.markCompletedTaskAttempt(status.getTaskTracker(), taskid);
    } else if (tip.isMapTask()) {
      // check if this was a sepculative task
      if (oldNumAttempts > 1) {
        speculativeMapTasks -= (oldNumAttempts - newNumAttempts);
        if (!garbageCollected) {
          totalSpeculativeMapTasks.addAndGet(newNumAttempts - oldNumAttempts);
        }
      }
      if (tip.isSpeculativeAttempt(taskid)) {
        metrics.speculativeSucceededMap(taskid,
            tip.isUsingProcessingRateForSpeculation());
      }
      int level = getLocalityLevel(tip, ttStatus);
      long inputBytes = tip.getCounters()
                .getGroup("org.apache.hadoop.mapred.Task$Counter")
                .getCounter("Map input bytes");
      switch (level) {
      case 0: jobCounters.incrCounter(Counter.LOCAL_MAP_INPUT_BYTES,
                inputBytes);
              metrics.addLocalMapInputBytes(inputBytes);
              break;
      case 1: jobCounters.incrCounter(Counter.RACK_MAP_INPUT_BYTES,
                inputBytes);
              metrics.addRackMapInputBytes(inputBytes);
              break;
      default:metrics.addMapInputBytes(inputBytes);
              break;
      }
      finishedMapTasks += 1;
      metrics.completeMap(taskid);
      if (!garbageCollected) {
        if (!tip.isJobSetupTask() && hasSpeculativeMaps) {
          updateTaskTrackerStats(tip,ttStatus,trackerMapStats,mapTaskStats);
        }
      }
      // remove the completed map from the resp running caches
      retireMap(tip);
      if ((finishedMapTasks + failedMapTIPs) == (numMapTasks)) {
        this.status.setMapProgress(1.0f);
      }
    } else {
      if (oldNumAttempts > 1) {
        speculativeReduceTasks -= (oldNumAttempts - newNumAttempts);
        if (!garbageCollected) {
          totalSpeculativeReduceTasks.addAndGet(newNumAttempts - oldNumAttempts);
        }
      }
      if (tip.isSpeculativeAttempt(taskid)) {
        metrics.speculativeSucceededReduce(taskid,
            tip.isUsingProcessingRateForSpeculation());
      }
      finishedReduceTasks += 1;
      metrics.completeReduce(taskid);
      if (!garbageCollected) {
        if (!tip.isJobSetupTask() && hasSpeculativeReduces) {
          updateTaskTrackerStats(tip,ttStatus,trackerReduceStats,reduceTaskStats);
        }
      }
      // remove the completed reduces from the running reducers set
      retireReduce(tip);
      if ((finishedReduceTasks + failedReduceTIPs) == (numReduceTasks)) {
        this.status.setReduceProgress(1.0f);
      }
    }

    // is job complete?
    if (!jobSetupCleanupNeeded && canLaunchJobCleanupTask()) {
      jobComplete();
    }

    return true;
  }

  /**
   * Job state change must happen thru this call
   */
  private void changeStateTo(int newState) {
    int oldState = this.status.getRunState();
    if (oldState == newState) {
      return; //old and new states are same
    }
    this.status.setRunState(newState);

    //update the metrics
    if (oldState == JobStatus.PREP) {
      this.jobtracker.getInstrumentation().decPrepJob(conf, jobId);
    } else if (oldState == JobStatus.RUNNING) {
      this.jobtracker.getInstrumentation().decRunningJob(conf, jobId);
    }

    if (newState == JobStatus.PREP) {
      this.jobtracker.getInstrumentation().addPrepJob(conf, jobId);
    } else if (newState == JobStatus.RUNNING) {
      this.jobtracker.getInstrumentation().addRunningJob(conf, jobId);
    }

  }

  /**
   * The job is done since all it's component tasks are either
   * successful or have failed.
   */
  private void jobComplete() {
    final JobTrackerInstrumentation metrics = jobtracker.getInstrumentation();
    //
    // All tasks are complete, then the job is done!
    //
    if (this.status.getRunState() == JobStatus.RUNNING ||
        this.status.getRunState() == JobStatus.PREP) {
      changeStateTo(JobStatus.SUCCEEDED);
      this.status.setCleanupProgress(1.0f);
      if (maps.length == 0) {
        this.status.setMapProgress(1.0f);
      }
      if (reduces.length == 0) {
        this.status.setReduceProgress(1.0f);
      }
      this.finishTime = JobTracker.getClock().getTime();
      LOG.info("Job " + this.status.getJobID() +
               " has completed successfully.");

      // Log the job summary (this should be done prior to logging to
      // job-history to ensure job-counters are in-sync
      JobSummary.logJobSummary(this, jobtracker.getClusterStatus(false));

      Counters counters = getCounters();
      // Log job-history
      JobHistory.JobInfo.logFinished(this.status.getJobID(), finishTime,
                                     this.finishedMapTasks,
                                     this.finishedReduceTasks, failedMapTasks,
                                     failedReduceTasks, killedMapTasks,
                                     killedReduceTasks, getMapCounters(),
                                     getReduceCounters(), counters);
      // Note that finalize will close the job history handles which garbage collect
      // might try to finalize
      garbageCollect();

      metrics.completeJob(this.conf, this.status.getJobID());
    }
  }

  private synchronized void terminateJob(int jobTerminationState) {
    if ((status.getRunState() == JobStatus.RUNNING) ||
        (status.getRunState() == JobStatus.PREP)) {
      this.finishTime = JobTracker.getClock().getTime();
      this.status.setMapProgress(1.0f);
      this.status.setReduceProgress(1.0f);
      this.status.setCleanupProgress(1.0f);

      Counters counters = getCounters();
      if (jobTerminationState == JobStatus.FAILED) {
        changeStateTo(JobStatus.FAILED);

        // Log the job summary
        JobSummary.logJobSummary(this, jobtracker.getClusterStatus(false));

        // Log to job-history
        JobHistory.JobInfo.logFailed(this.status.getJobID(), finishTime,
                                     this.finishedMapTasks,
                                     this.finishedReduceTasks, counters);
      } else {
        changeStateTo(JobStatus.KILLED);

        // Log the job summary
        JobSummary.logJobSummary(this, jobtracker.getClusterStatus(false));

        // Log to job-history
        JobHistory.JobInfo.logKilled(this.status.getJobID(), finishTime,
                                     this.finishedMapTasks,
                                     this.finishedReduceTasks, counters);
      }
      garbageCollect();

      jobtracker.getInstrumentation().terminateJob(
          this.conf, this.status.getJobID());
      if (jobTerminationState == JobStatus.FAILED) {
        jobtracker.getInstrumentation().failedJob(
            this.conf, this.status.getJobID());
      } else {
        jobtracker.getInstrumentation().killedJob(
            this.conf, this.status.getJobID());
      }
    }
  }

  /**
   * Terminate the job and all its component tasks.
   * Calling this will lead to marking the job as failed/killed. Cleanup
   * tip will be launched. If the job has not inited, it will directly call
   * terminateJob as there is no need to launch cleanup tip.
   * This method is reentrant.
   * @param jobTerminationState job termination state
   */
  private synchronized void terminate(int jobTerminationState) {
    if(!tasksInited.get()) {
      //init could not be done, we just terminate directly.
      terminateJob(jobTerminationState);
      return;
    }

    if ((status.getRunState() == JobStatus.RUNNING) ||
         (status.getRunState() == JobStatus.PREP)) {
      LOG.info("Killing job '" + this.status.getJobID() + "'");
      if (jobTerminationState == JobStatus.FAILED) {
        if(jobFailed) {//reentrant
          return;
        }
        jobFailed = true;
      } else if (jobTerminationState == JobStatus.KILLED) {
        if(jobKilled) {//reentrant
          return;
        }
        jobKilled = true;
      }
      // clear all unclean tasks
      clearUncleanTasks();
      //
      // kill all TIPs.
      //
      for (int i = 0; i < setup.length; i++) {
        setup[i].kill();
      }
      for (int i = 0; i < maps.length; i++) {
        maps[i].kill();
      }
      for (int i = 0; i < reduces.length; i++) {
        reduces[i].kill();
      }

      if (!jobSetupCleanupNeeded) {
        terminateJob(jobTerminationState);
      }
    }
  }

  private void cancelReservedSlots() {
    // Make a copy of the set of TaskTrackers to prevent a
    // ConcurrentModificationException ...
    Set<TaskTracker> tm =
      new HashSet<TaskTracker>(trackersReservedForMaps.keySet());
    for (TaskTracker tt : tm) {
      tt.unreserveSlots(TaskType.MAP, this);
    }

    Set<TaskTracker> tr =
      new HashSet<TaskTracker>(trackersReservedForReduces.keySet());
    for (TaskTracker tt : tr) {
      tt.unreserveSlots(TaskType.REDUCE, this);
    }
  }
  private void clearUncleanTasks() {
    TaskAttemptID taskid = null;
    TaskInProgress tip = null;
    while (!mapCleanupTasks.isEmpty()) {
      taskid = mapCleanupTasks.remove(0);
      tip = maps[taskid.getTaskID().getId()];
      updateTaskStatus(tip, tip.getTaskStatus(taskid));
    }
    while (!reduceCleanupTasks.isEmpty()) {
      taskid = reduceCleanupTasks.remove(0);
      tip = reduces[taskid.getTaskID().getId()];
      updateTaskStatus(tip, tip.getTaskStatus(taskid));
    }
  }

  /**
   * Kill the job and all its component tasks. This method should be called from
   * jobtracker and should return fast as it locks the jobtracker.
   */
  public void kill() {
    boolean killNow = false;
    synchronized(jobInitKillStatus) {
      jobInitKillStatus.killed = true;
      //if not in middle of init, terminate it now
      if(!jobInitKillStatus.initStarted || jobInitKillStatus.initDone) {
        //avoiding nested locking by setting flag
        killNow = true;
      }
    }
    if(killNow) {
      terminate(JobStatus.KILLED);
    }
  }

  /**
   * Fails the job and all its component tasks. This should be called only from
   * {@link JobInProgress} or {@link JobTracker}. Look at
   * {@link JobTracker#failJob(JobInProgress)} for more details.
   */
  synchronized void fail() {
    terminate(JobStatus.FAILED);
  }

  /**
   * A task assigned to this JobInProgress has reported in as failed.
   * Most of the time, we'll just reschedule execution.  However, after
   * many repeated failures we may instead decide to allow the entire
   * job to fail or succeed if the user doesn't care about a few tasks failing.
   *
   * Even if a task has reported as completed in the past, it might later
   * be reported as failed.  That's because the TaskTracker that hosts a map
   * task might die before the entire job can complete.  If that happens,
   * we need to schedule reexecution so that downstream reduce tasks can
   * obtain the map task's output.
   */
  private void failedTask(TaskInProgress tip, TaskAttemptID taskid,
                          TaskStatus status,
                          TaskTracker taskTracker, boolean wasRunning,
                          boolean wasComplete, boolean wasAttemptRunning) {
    this.jobtracker.getTaskErrorCollector().collect(
        tip, taskid, JobTracker.getClock().getTime());
    final JobTrackerInstrumentation metrics = jobtracker.getInstrumentation();
    // check if the TIP is already failed
    boolean wasFailed = tip.isFailed();

    // Mark the taskid as FAILED or KILLED
    tip.incompleteSubTask(taskid, this.status);

    boolean isRunning = tip.isRunning();
    boolean isComplete = tip.isComplete();

    if (wasAttemptRunning) {
      if (!tip.isJobCleanupTask() && !tip.isJobSetupTask()) {
        boolean isSpeculative= tip.isSpeculativeAttempt(taskid);
        boolean isUsingSpeculationByProcessingRate =
            tip.isUsingProcessingRateForSpeculation();
        long taskStartTime = status.getStartTime();
        if (tip.isMapTask()) {
          metrics.failedMap(taskid, wasFailed, isSpeculative,
              isUsingSpeculationByProcessingRate, taskStartTime);
        } else {
          metrics.failedReduce(taskid, wasFailed, isSpeculative,
              isUsingSpeculationByProcessingRate, taskStartTime);
        }
      }

      // Metering
      meterTaskAttempt(tip, status);
    }

    //update running  count on task failure.
    if (wasRunning && !isRunning) {
      if (tip.isJobCleanupTask()) {
        launchedCleanup = false;
      } else if (tip.isJobSetupTask()) {
        launchedSetup = false;
      } else if (tip.isMapTask()) {
        // remove from the running queue and put it in the non-running cache
        // if the tip is not complete i.e if the tip still needs to be run
        if (!isComplete) {
          retireMap(tip);
          failMap(tip);
        }
      } else {
        // remove from the running queue and put in the failed queue if the tip
        // is not complete
        if (!isComplete) {
          retireReduce(tip);
          failReduce(tip);
        }
      }
    }

    // The case when the map was complete but the task tracker went down.
    // However, we don't need to do any metering here...
    if (wasComplete && !isComplete) {
      if (tip.isMapTask()) {
        // Put the task back in the cache. This will help locality for cases
        // where we have a different TaskTracker from the same rack/switch
        // asking for a task.
        // We bother about only those TIPs that were successful
        // earlier (wasComplete and !isComplete)
        // (since they might have been removed from the cache of other
        // racks/switches, if the input split blocks were present there too)
        failMap(tip);
        finishedMapTasks -= 1;
      }
    }

    // update job history
    // get taskStatus from tip
    TaskStatus taskStatus = tip.getTaskStatus(taskid);
    String taskTrackerName = taskStatus.getTaskTracker();
    String taskTrackerHostName = convertTrackerNameToHostName(taskTrackerName);
    int taskTrackerPort = -1;
    TaskTrackerStatus taskTrackerStatus =
      (taskTracker == null) ? null : taskTracker.getStatus();
    if (taskTrackerStatus != null) {
      taskTrackerPort = taskTrackerStatus.getHttpPort();
    }
    long startTime = taskStatus.getStartTime();
    long finishTime = taskStatus.getFinishTime();
    List<String> taskDiagnosticInfo = tip.getDiagnosticInfo(taskid);
    String diagInfo = taskDiagnosticInfo == null ? "" :
      StringUtils.arrayToString(taskDiagnosticInfo.toArray(new String[0]));
    String taskType = getTaskType(tip);
    if (taskStatus.getIsMap()) {
      JobHistory.MapAttempt.logStarted(taskid, startTime,
        taskTrackerName, taskTrackerPort, taskType);
      if (taskStatus.getRunState() == TaskStatus.State.FAILED) {
        JobHistory.MapAttempt.logFailed(taskid, finishTime,
          taskTrackerHostName, diagInfo, taskType);
      } else {
        JobHistory.MapAttempt.logKilled(taskid, finishTime,
          taskTrackerHostName, diagInfo, taskType);
      }
    } else {
      JobHistory.ReduceAttempt.logStarted(taskid, startTime,
        taskTrackerName, taskTrackerPort, taskType);
      if (taskStatus.getRunState() == TaskStatus.State.FAILED) {
        JobHistory.ReduceAttempt.logFailed(taskid, finishTime,
          taskTrackerHostName, diagInfo, taskType);
      } else {
        JobHistory.ReduceAttempt.logKilled(taskid, finishTime,
          taskTrackerHostName, diagInfo, taskType);
      }
    }

    // After this, try to assign tasks with the one after this, so that
    // the failed task goes to the end of the list.
    if (!tip.isJobCleanupTask() && !tip.isJobSetupTask()) {
      if (tip.isMapTask()) {
        failedMapTasks++;
        if (taskStatus.getRunState() != TaskStatus.State.FAILED) {
          killedMapTasks++;
        }
      } else {
        failedReduceTasks++;
        if (taskStatus.getRunState() != TaskStatus.State.FAILED) {
          killedReduceTasks++;
        }
      }
    }

    //
    // Note down that a task has failed on this tasktracker
    //
    if (status.getRunState() == TaskStatus.State.FAILED) {
      List<String> infos = tip.getDiagnosticInfo(status.getTaskID());
      if (infos != null && infos.size() > 0) {
        String lastFailureReason = infos.get(infos.size()-1);
        addTrackerTaskFailure(taskTrackerName, taskTracker, lastFailureReason);
      }
    }

    //
    // Let the JobTracker know that this task has failed
    //
    jobtracker.markCompletedTaskAttempt(status.getTaskTracker(), taskid);

    //
    // Check if we need to kill the job because of too many failures or
    // if the job is complete since all component tasks have completed

    // We do it once per TIP and that too for the task that fails the TIP
    if (!wasFailed && tip.isFailed()) {
      //
      // Allow upto 'mapFailuresPercent' of map tasks to fail or
      // 'reduceFailuresPercent' of reduce tasks to fail
      //
      boolean killJob = tip.isJobCleanupTask() || tip.isJobSetupTask() ? true :
                        tip.isMapTask() ?
            ((++failedMapTIPs*100) > (mapFailuresPercent*numMapTasks)) :
            ((++failedReduceTIPs*100) > (reduceFailuresPercent*numReduceTasks));

      if (killJob) {
        LOG.info("Aborting job " + profile.getJobID());
        // Record the task that caused the job failure for viewing on
        // the job details page
        recordTaskIdThatCausedFailure(tip.getTIPId());
        JobHistory.Task.logFailed(tip.getTIPId(),
                                  taskType,
                                  finishTime,
                                  diagInfo);
        if (tip.isJobCleanupTask()) {
          // kill the other tip
          if (tip.isMapTask()) {
            cleanup[1].kill();
          } else {
            cleanup[0].kill();
          }
          terminateJob(JobStatus.FAILED);
        } else {
          if (tip.isJobSetupTask()) {
            // kill the other tip
            killSetupTip(!tip.isMapTask());
          }
          fail();
        }
      }

      //
      // Update the counters
      //
      if (!tip.isJobCleanupTask() && !tip.isJobSetupTask()) {
        if (tip.isMapTask()) {
          jobCounters.incrCounter(Counter.NUM_FAILED_MAPS, 1);
        } else {
          jobCounters.incrCounter(Counter.NUM_FAILED_REDUCES, 1);
        }
      }
    }
  }

  void killSetupTip(boolean isMap) {
    if (isMap) {
      setup[0].kill();
    } else {
      setup[1].kill();
    }
  }

  boolean isSetupFinished() {
    // if there is no setup to be launched, consider setup is finished.
    if ((tasksInited.get() && setup.length == 0) ||
        setup[0].isComplete() || setup[0].isFailed() || setup[1].isComplete()
        || setup[1].isFailed()) {
      return true;
    }
    return false;
  }

  /**
   * Fail a task with a given reason, but without a status object.
   *
   * Assuming {@link JobTracker} is locked on entry.
   *
   * @param tip The task's tip
   * @param taskid The task id
   * @param reason The reason that the task failed
   * @param trackerName The task tracker the task failed on
   */
  public void failedTask(TaskInProgress tip, TaskAttemptID taskid, String reason,
                         TaskStatus.Phase phase, TaskStatus.State state,
                         String trackerName) {
    TaskStatus status = TaskStatus.createTaskStatus(tip.isMapTask(),
                                                    taskid,
                                                    0.0f,
                                                    tip.isMapTask() ?
                                                        numSlotsPerMap :
                                                        numSlotsPerReduce,
                                                    state,
                                                    reason,
                                                    reason,
                                                    trackerName, phase,
                                                    new Counters());
    // update the actual start-time of the attempt
    TaskStatus oldStatus = tip.getTaskStatus(taskid);
    long startTime = oldStatus == null
                     ? JobTracker.getClock().getTime()
                     : oldStatus.getStartTime();
    status.setStartTime(startTime);
    long finishTime = JobTracker.getClock().getTime();
    // update finish time only if needed, as map tasks can fail after completion
    if (tip.isMapTask() && oldStatus != null) {
      long oldFinishTime = oldStatus.getFinishTime();
      if (oldFinishTime > 0) {
        finishTime = oldFinishTime;
      }
    }
    status.setFinishTime(finishTime);
    boolean wasComplete = tip.isComplete();
    updateTaskStatus(tip, status);
    boolean isComplete = tip.isComplete();
    if (wasComplete && !isComplete) { // mark a successful tip as failed
      String taskType = getTaskType(tip);
      JobHistory.Task.logFailed(tip.getTIPId(), taskType,
                                tip.getExecFinishTime(), reason, taskid);
    }
  }


  /**
   * The job is dead.  We're now GC'ing it, getting rid of the job
   * from all tables.  Be sure to remove all of this job's tasks
   * from the various tables.
   */
  synchronized void garbageCollect() {
    // Cancel task tracker reservation
    cancelReservedSlots();

    // Remove the remaining speculative tasks counts
    totalSpeculativeReduceTasks.addAndGet(-speculativeReduceTasks);
    totalSpeculativeMapTasks.addAndGet(-speculativeMapTasks);
    garbageCollected = true;

    // Let the JobTracker know that a job is complete
    jobtracker.getInstrumentation().decWaitingMaps(getJobID(), pendingMaps());
    jobtracker.getInstrumentation().decWaitingReduces(getJobID(), pendingReduces());
    jobtracker.storeCompletedJob(this);
    jobtracker.finalizeJob(this);

    try {
      // Definitely remove the local-disk copy of the job file
      if (localJobFile != null) {
        localFs.delete(localJobFile, true);
        localJobFile = null;
      }

      // clean up splits
      for (int i = 0; i < maps.length; i++) {
        maps[i].clearSplit();
      }

      // JobClient always creates a new directory with job files
      // so we remove that directory to cleanup
      // Delete temp dfs dirs created if any, like in case of
      // speculative exn of reduces.
      Path tempDir = jobtracker.getSystemDirectoryForJob(getJobID());
      new CleanupQueue().addToQueue(new PathDeletionContext(
          FileSystem.get(conf), tempDir.toUri().getPath()));
    } catch (IOException e) {
      LOG.warn("Error cleaning up "+profile.getJobID()+": "+e);
    }

    cleanUpMetrics();
    // free up the memory used by the data structures
    this.nonRunningMapCache = null;
    this.runningMapCache = null;
    this.nonRunningReduces = null;
    this.runningReduces = null;
    this.trackerMapStats = null;
    this.trackerReduceStats = null;
  }

  @Override
  public synchronized TaskInProgress getTaskInProgress(TaskID tipid) {
    return super.getTaskInProgress(tipid);
  }

  /**
   * Find the details of someplace where a map has finished
   * @param mapId the id of the map
   * @return the task status of the completed task
   */
  public synchronized TaskStatus findFinishedMap(int mapId) {
    TaskInProgress tip = maps[mapId];
    if (tip.isComplete()) {
      TaskStatus[] statuses = tip.getTaskStatuses();
      for(int i=0; i < statuses.length; i++) {
        if (statuses[i].getRunState() == TaskStatus.State.SUCCEEDED) {
          return statuses[i];
        }
      }
    }
    return null;
  }

  synchronized int getNumTaskCompletionEvents() {
    return taskCompletionEvents.size();
  }

  synchronized public TaskCompletionEvent[] getTaskCompletionEvents(
                                                                    int fromEventId, int maxEvents) {
    TaskCompletionEvent[] events = TaskCompletionEvent.EMPTY_ARRAY;
    if (taskCompletionEvents.size() > fromEventId) {
      int actualMax = Math.min(maxEvents,
                               (taskCompletionEvents.size() - fromEventId));
      events = taskCompletionEvents.subList(fromEventId, actualMax + fromEventId).toArray(events);
    }
    return events;
  }

  synchronized public int getTaskCompletionEventsSize() {
    return taskCompletionEvents.size();
  }

  synchronized void fetchFailureNotification(TaskAttemptID reportingAttempt,
                                             TaskInProgress tip,
                                             TaskAttemptID mapTaskId,
                                             String trackerName) {
    final JobTrackerInstrumentation metrics = jobtracker.getInstrumentation();
    metrics.mapFetchFailure();
    Integer fetchFailures = mapTaskIdToFetchFailuresMap.get(mapTaskId);
    fetchFailures = (fetchFailures == null) ? 1 : (fetchFailures+1);
    mapTaskIdToFetchFailuresMap.put(mapTaskId, fetchFailures);
    LOG.info("Failed fetch notification #" + fetchFailures + " by " + reportingAttempt +
        " for task " + mapTaskId + " tracker " + trackerName);

    float failureRate = (float)fetchFailures / runningReduceTasks;
    // declare faulty if fetch-failures >= max-allowed-failures
    boolean isMapFaulty = (failureRate >= MAX_ALLOWED_FETCH_FAILURES_PERCENT) ||
        fetchFailures > maxFetchFailuresPerMapper;
    if (fetchFailures >= MAX_FETCH_FAILURES_NOTIFICATIONS
        && isMapFaulty) {
      String reason = "Too many fetch-failures (" + fetchFailures + ")" +
        " at " + (new Date());
      LOG.info(reason + " for " + mapTaskId + " ... killing it");
      metrics.mapFailedByFetchFailures();
      failedTask(tip, mapTaskId, reason,
                 (tip.isMapTask() ? TaskStatus.Phase.MAP :
                                    TaskStatus.Phase.REDUCE),
                 TaskStatus.State.FAILED, trackerName);

      mapTaskIdToFetchFailuresMap.remove(mapTaskId);
    }
  }

  /**
   * @return The JobID of this JobInProgress.
   */
  public JobID getJobID() {
    return jobId;
  }

  public synchronized Object getSchedulingInfo() {
    return this.schedulingInfo;
  }

  public synchronized void setSchedulingInfo(Object schedulingInfo) {
    this.schedulingInfo = schedulingInfo;
    this.status.setSchedulingInfo(schedulingInfo.toString());
  }

  /**
   * To keep track of kill and initTasks status of this job. initTasks() take
   * a lock on JobInProgress object. kill should avoid waiting on
   * JobInProgress lock since it may take a while to do initTasks().
   */
  private static class JobInitKillStatus {
    //flag to be set if kill is called
    boolean killed;

    boolean initStarted;
    boolean initDone;
  }

  boolean isComplete() {
    return status.isJobComplete();
  }

  /**
   * Get the task type for logging it to {@link JobHistory}.
   */
  private String getTaskType(TaskInProgress tip) {
    if (tip.isJobCleanupTask()) {
      return Values.CLEANUP.name();
    } else if (tip.isJobSetupTask()) {
      return Values.SETUP.name();
    } else if (tip.isMapTask()) {
      return Values.MAP.name();
    } else {
      return Values.REDUCE.name();
    }
  }

  /**
   * Get the level of locality that a given task would have if launched on
   * a particular TaskTracker. Returns 0 if the task has data on that machine,
   * 1 if it has data on the same rack, etc (depending on number of levels in
   * the network hierarchy).
   */
  int getLocalityLevel(TaskInProgress tip, TaskTrackerStatus tts) {
    Node tracker = jobtracker.getNode(tts.getHost());
    int level = this.maxLevel;
    // find the right level across split locations
    for (String local : maps[tip.getIdWithinJob()].getSplitLocations()) {
      Node datanode = jobtracker.getNode(local);
      int newLevel = this.maxLevel;
      if (tracker != null && datanode != null) {
        newLevel = getMatchingLevelForNodes(tracker, datanode);
      }
      if (newLevel < level) {
        level = newLevel;
        // an optimization
        if (level == 0) {
          break;
        }
      }
    }
    return level;
  }

  /**
   * Test method to set the cluster sizes
   */
  void setClusterSize(int clusterSize) {
    this.clusterSize = clusterSize;
  }

  static class JobSummary {
    static final Log LOG = LogFactory.getLog(JobSummary.class);

    // Escape sequences
    static final char EQUALS = '=';
    static final char[] charsToEscape =
      {StringUtils.COMMA, EQUALS, StringUtils.ESCAPE_CHAR};

    /**
     * Log a summary of the job's runtime.
     *
     * @param job {@link JobInProgress} whose summary is to be logged, cannot
     *            be <code>null</code>.
     * @param cluster {@link ClusterStatus} of the cluster on which the job was
     *                run, cannot be <code>null</code>
     */
    public static void logJobSummary(JobInProgress job, ClusterStatus cluster) {
      JobStatus status = job.getStatus();
      JobProfile profile = job.getProfile();
      String user = StringUtils.escapeString(profile.getUser(),
                                             StringUtils.ESCAPE_CHAR,
                                             charsToEscape);
      String queue = StringUtils.escapeString(profile.getQueueName(),
                                              StringUtils.ESCAPE_CHAR,
                                              charsToEscape);
      Counters jobCounters = job.getJobCounters();
      long mapSlotSeconds =
        (jobCounters.getCounter(Counter.SLOTS_MILLIS_MAPS) +
         jobCounters.getCounter(Counter.FALLOW_SLOTS_MILLIS_MAPS)) / 1000;
      long reduceSlotSeconds =
        (jobCounters.getCounter(Counter.SLOTS_MILLIS_REDUCES) +
         jobCounters.getCounter(Counter.FALLOW_SLOTS_MILLIS_REDUCES)) / 1000;

      LOG.info("jobId=" + job.getJobID() + StringUtils.COMMA +
               "submitTime" + EQUALS + job.getStartTime() + StringUtils.COMMA +
               "launchTime" + EQUALS + job.getLaunchTime() + StringUtils.COMMA +
               "finishTime" + EQUALS + job.getFinishTime() + StringUtils.COMMA +
               "numMaps" + EQUALS + job.getTasks(TaskType.MAP).length +
                           StringUtils.COMMA +
               "numSlotsPerMap" + EQUALS + job.getNumSlotsPerMap() +
                                  StringUtils.COMMA +
               "numReduces" + EQUALS + job.getTasks(TaskType.REDUCE).length +
                              StringUtils.COMMA +
               "numSlotsPerReduce" + EQUALS + job.getNumSlotsPerReduce() +
                                     StringUtils.COMMA +
               "user" + EQUALS + user + StringUtils.COMMA +
               "queue" + EQUALS + queue + StringUtils.COMMA +
               "status" + EQUALS +
                          JobStatus.getJobRunState(status.getRunState()) +
                          StringUtils.COMMA +
               "mapSlotSeconds" + EQUALS + mapSlotSeconds + StringUtils.COMMA +
               "reduceSlotsSeconds" + EQUALS + reduceSlotSeconds  +
                                      StringUtils.COMMA +
               "clusterMapCapacity" + EQUALS + cluster.getMaxMapTasks() +
                                      StringUtils.COMMA +
               "clusterReduceCapacity" + EQUALS + cluster.getMaxReduceTasks()
      );
    }
  }

    /**
     * Check to see if the maximum number of speculative tasks are
     * already being executed currently.
     * @param tasks the set of tasks to test
     * @param type the type of task (MAP/REDUCE) that we are considering
     * @return has the cap been reached?
     */
   private boolean atSpeculativeCap(TaskType type) {
     float numTasks = (type == TaskType.MAP) ?
       (float)(runningMapTasks - speculativeMapTasks) :
       (float)(runningReduceTasks - speculativeReduceTasks);
     if (numTasks == 0){
       return true; // avoid divide by zero
     }
     int speculativeTaskCount = type == TaskType.MAP ? speculativeMapTasks
         : speculativeReduceTasks;
     int totalSpeculativeTaskCount = type == TaskType.MAP ?
         totalSpeculativeMapTasks.get() : totalSpeculativeReduceTasks.get();
     //return true if totalSpecTask < max(10, 0.01 * total-slots,
     //                                   0.1 * total-running-tasks)

     if (speculativeTaskCount < MIN_SPEC_CAP) {
       return false; // at least one slow tracker's worth of slots(default=10)
     }
     ClusterStatus c = jobtracker.getClusterStatus(false);
     int numSlots = (type == TaskType.MAP ? c.getMaxMapTasks() : c.getMaxReduceTasks());
     if (speculativeTaskCount < numSlots * MIN_SLOTS_CAP) {
       return false;
     }
     // Check if the total CAP has been reached
     if (totalSpeculativeTaskCount >= numSlots * TOTAL_SPECULATIVECAP) {
       return true;
     }
     boolean atCap = (((speculativeTaskCount)/numTasks) >= speculativeCap);
     if (LOG.isDebugEnabled()) {
       LOG.debug("SpeculativeCap is "+speculativeCap+", specTasks/numTasks is " +
           ((speculativeTaskCount)/numTasks)+
           ", so atSpecCap() is returning "+atCap);
     }
     return atCap;
   }

  /**
   * A class for comparing the estimated time to completion of two tasks
   */
  static class EstimatedTimeLeftComparator implements Comparator<TaskInProgress> {
    private final long time;
    public EstimatedTimeLeftComparator(long now) {
      this.time = now;
    }
    /**
     * Estimated time to completion is measured as:
     *   % of task left to complete (1 - progress) / progress rate of the task.
     *
     * This assumes that tasks are linear in their progress, which is
     * often wrong, especially since progress for reducers is currently
     * calculated by evenly weighting their three stages (shuffle, sort, map)
     * which rarely account for 1/3 each. This should be fixed in the future
     * by calculating progressRate more intelligently or splitting these
     * multi-phase tasks into individual tasks.
     *
     * The ordering this comparator defines is: task1 < task2 if task1 is
     * estimated to finish farther in the future => compare(t1,t2) returns -1
     */
    @Override
    public int compare(TaskInProgress tip1, TaskInProgress tip2) {
      //we have to use the Math.max in the denominator to avoid divide by zero
      //error because prog and progRate can both be zero (if one is zero,
      //the other one will be 0 too).
      //We use inverse of time_reminaing=[(1- prog) / progRate]
      //so that (1-prog) is in denom. because tasks can have arbitrarily
      //low progRates in practice (e.g. a task that is half done after 1000
      //seconds will have progRate of 0.0000005) so we would rather
      //use Math.maxnon (1-prog) by putting it in the denominator
      //which will cause tasks with prog=1 look 99.99% done instead of 100%
      //which is okay
      double t1 = tip1.getProgressRate() / Math.max(0.0001,
          1.0 - tip1.getProgress());
      double t2 = tip2.getProgressRate() / Math.max(0.0001,
          1.0 - tip2.getProgress());
      if (t1 < t2) return -1;
      else if (t2 < t1) return 1;
      else return 0;
    }
  }
  /**
   * Compares the ave progressRate of tasks that have finished on this
   * taskTracker to the ave of all succesfull tasks thus far to see if this
   * TT one is too slow for speculating.
   * slowNodeThreshold is used to determine the number of standard deviations
   * @param taskTracker the name of the TaskTracker we are checking
   * @return is this TaskTracker slow
   */
  protected boolean isSlowTracker(String taskTracker) {
    if (trackerMapStats.get(taskTracker) != null &&
        trackerMapStats.get(taskTracker).mean() -
        mapTaskStats.mean() > mapTaskStats.std()*slowNodeThreshold) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Tracker " + taskTracker +
            " declared slow. trackerMapStats.get(taskTracker).mean() :" + trackerMapStats.get(taskTracker).mean() +
            " mapTaskStats :" + mapTaskStats);
      }
      return true;
    }
    if (trackerReduceStats.get(taskTracker) != null &&
        trackerReduceStats.get(taskTracker).mean() -
        reduceTaskStats.mean() > reduceTaskStats.std()*slowNodeThreshold) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Tracker " + taskTracker +
            " declared slow. trackerReduceStats.get(taskTracker).mean() :" + trackerReduceStats.get(taskTracker).mean() +
            " reduceTaskStats :" + reduceTaskStats);
      }
      return true;
    }
    return false;
  }

  private void updateTaskTrackerStats(TaskInProgress tip, TaskTrackerStatus ttStatus,
      Map<String,DataStatistics> trackerStats, DataStatistics overallStats) {
    float tipDuration = tip.getExecFinishTime() -
                        tip.getDispatchTime(tip.getSuccessfulTaskid());
    DataStatistics ttStats =
      trackerStats.get(ttStatus.getTrackerName());
    double oldMean = 0.0d;
    //We maintain the mean of TaskTrackers' means. That way, we get a single
    //data-point for every tracker (used in the evaluation in isSlowTracker)
    if (ttStats != null) {
      oldMean = ttStats.mean();
      ttStats.add(tipDuration);
      overallStats.updateStatistics(oldMean, ttStats.mean());
    } else {
      trackerStats.put(ttStatus.getTrackerName(),
          (ttStats = new DataStatistics(tipDuration)));
      overallStats.add(tipDuration);
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("Added mean of " +ttStats.mean() + " to trackerStats of type "+
          (tip.isMapTask() ? "Map" : "Reduce") +
          " on "+ttStatus.getTrackerName()+". DataStatistics is now: " +
          trackerStats.get(ttStatus.getTrackerName()));
    }
  }

  @Override
  public DataStatistics getRunningTaskStatistics(boolean isMap) {
    if (isMap) {
      return runningMapTaskStats;
    } else {
      return runningReduceTaskStats;
    }
  }

  @Override
  public DataStatistics getRunningTaskStatistics(TaskStatus.Phase phase) {
    switch(phase) {
      case MAP:     return runningTaskMapByteProcessingRateStats;
      case SHUFFLE: return runningTaskCopyProcessingRateStats;
      case SORT:    return runningTaskSortProcessingRateStats;
      case REDUCE:  return runningTaskReduceProcessingRateStats;
    }
    LOG.error("No Statistics for phase " + phase.toString() + " in job " +
        jobId);
    return null;
  }

  @Override
  public float getSlowTaskThreshold() {
    return slowTaskThreshold;
  }

  @Override
  public float getStddevMeanRatioMax() {
    return speculativeStddevMeanRatioMax;
  }

  public static int getTotalSpeculativeMapTasks() {
    return totalSpeculativeMapTasks.get();
  }

  public static int getTotalSpeculativeReduceTasks() {
    return totalSpeculativeReduceTasks.get();
  }

  synchronized void refreshIfNecessary() {
    if (getStatus().getRunState() != JobStatus.RUNNING) {
      return;
    }
    long now = JobTracker.getClock().getTime();
    if ((now - lastRefresh) > refreshTimeout) {
      lastRefresh = now;
      refresh(now);
    }
  }
  /**
   * Refresh speculative task candidates and running tasks. This needs to be
   * called periodically to obtain fresh values.
   */
  void refresh(long now) {
    refreshCandidateSpeculativeMaps(now);
    refreshCandidateSpeculativeReduces(now);
    refreshTaskCountsAndWaitTime(TaskType.MAP, now);
    refreshTaskCountsAndWaitTime(TaskType.REDUCE, now);
  }

  /**
   * Refresh runningTasks, neededTasks and pendingTasks counters
   * @param type TaskType to refresh
   */
  protected void refreshTaskCountsAndWaitTime(TaskType type, long now) {
    TaskInProgress[] allTips = getTasks(type);
    int finishedTips = 0;
    int runningTips = 0;
    int runningTaskAttempts = 0;
    long totalWaitTime = 0;
    long jobStartTime = this.getStartTime();
    for (TaskInProgress tip : allTips) {
      if (tip.isComplete()) {
        finishedTips += 1;
      } else if(tip.isRunning()) {
        runningTaskAttempts += tip.getActiveTasks().size();
        runningTips += 1;
      }
      if (tip.getExecStartTime() > 0) {
        totalWaitTime += tip.getExecStartTime() - jobStartTime;
      } else {
        totalWaitTime += now - jobStartTime;
      }
    }
    if (TaskType.MAP == type) {
      totalMapWaitTime = totalWaitTime;
      runningMapTasks = runningTaskAttempts;
      neededMapTasks = numMapTasks - runningTips - finishedTips
          + neededSpeculativeMaps();
      pendingMapTasks = numMapTasks - runningTaskAttempts
          - failedMapTIPs - finishedMapTasks + speculativeMapTasks;
    } else {
      totalReduceWaitTime = totalWaitTime;
      runningReduceTasks = runningTaskAttempts;
      neededReduceTasks = numReduceTasks - runningTips - finishedTips
          + neededSpeculativeReduces();
      pendingReduceTasks = numReduceTasks - runningTaskAttempts
          - failedReduceTIPs - finishedReduceTasks + speculativeReduceTasks;
    }
  }

  private void refreshCandidateSpeculativeMaps(long now) {
    if (!hasSpeculativeMaps()) {
      return;
    }
    //////// Populate allTips with all TaskInProgress
    Set<TaskInProgress> allTips = new HashSet<TaskInProgress>();

    // collection of node at max level in the cache structure
    Collection<Node> nodesAtMaxLevel = jobtracker.getNodesAtMaxLevel();
    // Add all tasks from max-level nodes breadth-wise
    for (Node parent : nodesAtMaxLevel) {
      Set<TaskInProgress> cache = runningMapCache.get(parent);
      if (cache != null) {
        allTips.addAll(cache);
      }
    }
    // Add all non-local TIPs
    allTips.addAll(nonLocalRunningMaps);

    // update the progress rates of all the candidate tips ..
    for (TaskInProgress tip: allTips) {
      tip.updateProgressRate(now);
    }

    candidateSpeculativeMaps = findSpeculativeTaskCandidates(allTips);
  }

  private void refreshCandidateSpeculativeReduces(long now) {
    if (!hasSpeculativeReduces()) {
      return;
    }
    // update the progress rates of all the candidate tips ..
    for (TaskInProgress tip: runningReduces) {
      tip.updateProgressRate(now);
    }
    candidateSpeculativeReduces = findSpeculativeTaskCandidates(runningReduces);
  }

  public TaskID getTaskIdThatCausedFailure() {
	  return taskIdThatCausedFailure;
  }

  private synchronized void recordTaskIdThatCausedFailure(TaskID tid) {
	  // Only the first task is considered to have caused the failure
	  if (taskIdThatCausedFailure == null) {
		  taskIdThatCausedFailure = tid;
	  }
  }
}
