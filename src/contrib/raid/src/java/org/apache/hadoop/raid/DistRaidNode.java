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

package org.apache.hadoop.raid;

import java.io.IOException;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.apache.hadoop.conf.Configuration;

import org.apache.hadoop.util.Daemon;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.FileStatus;

import org.apache.hadoop.raid.DistRaid.EncodingCandidate;
import org.apache.hadoop.raid.protocol.PolicyInfo;

/**
 * Implementation of {@link RaidNode} that uses map reduce jobs to raid files.
 */
public class DistRaidNode extends RaidNode {

  public static final Log LOG = LogFactory.getLog(DistRaidNode.class);

  /** Daemon thread to monitor raid job progress */
  JobMonitor jobMonitor = null;
  Daemon jobMonitorThread = null;

  /** Added by RH begins.  Identify whether or not EAR is used */
  private static boolean EARflag = true;
  /** Added by RH ends */

  public DistRaidNode(Configuration conf) throws IOException {
    super(conf);
    /* TODO: add EAR flag begins */
    //Class<?> placementClass =
    //    conf.getClass("", DistRaidNode.class);
    /* add EAR flag ends */
    this.jobMonitor = new JobMonitor(conf);
    this.jobMonitorThread = new Daemon(this.jobMonitor);
    this.jobMonitorThread.start();
    
    LOG.info("created");
  }

  /**
   * {@inheritDocs}
   */
  @Override
  public void join() {
    super.join();
    try {
      if (jobMonitorThread != null) jobMonitorThread.join();
    } catch (InterruptedException ie) {
      // do nothing
    }
  }
  
  /**
   * {@inheritDocs}
   */
  @Override
  public void stop() {
    if (stopRequested) {
      return;
    }
    super.stop();
    if (jobMonitor != null) jobMonitor.running = false;
    if (jobMonitorThread != null) jobMonitorThread.interrupt();
  }


  /**
   * {@inheritDocs}
   */
  @Override
  void raidFiles(PolicyInfo info, List<FileStatus> paths) throws IOException {
    raidFiles(conf, jobMonitor, paths, info);
  }

  final static DistRaid raidFiles(Configuration conf, JobMonitor jobMonitor,
      List<FileStatus> paths, PolicyInfo info) throws IOException {
    /** Updated by RH Mar 11th 2015 begins */
    List<EncodingCandidate> lec; 
    if (EARflag) {
      lec = splitPathsFromPreEncStripeStore(conf, 
          Codec.getCodec(info.getCodecId()), paths);
    } else {
      lec = splitPaths(conf, Codec.getCodec(info.getCodecId()), paths);
    }
    /* --------------------------OUTDATED----------------------------------*/
    ///** Commented by RH Oct 27th, 2014 begins */
    ////List<EncodingCandidate> lec = splitPaths(conf,
    ////    Codec.getCodec(info.getCodecId()), paths);
    ///** Commented by RH Oct 27th, 2014 ends */
    //List<EncodingCandidate> lec = splitPathsFromPreEncStripeStore(conf,
    //    Codec.getCodec(info.getCodecId()), paths);
    /** Updated by RH Mar 11th 2015 ends */
    
    // We already checked that no job for this policy is running
    // So we can start a new job.
    DistRaid dr = new DistRaid(conf);
    //add paths for distributed raiding
    dr.addRaidPaths(info, lec);
    boolean started = dr.startDistRaid();
    if (started) {
      jobMonitor.monitorJob(info.getName(), dr);
    } else {
      return null;
    }
    return dr;
  }
  /**
   * {@inheritDocs}
   */
  @Override
  int getRunningJobsForPolicy(String policyName) {
     //LOG.info(policyName); 
     //LOG.info(jobMonitor.runningJobsCount(policyName)); 
    return jobMonitor.runningJobsCount(policyName);
  }

  @Override
  public String raidJobsHtmlTable(JobMonitor.STATUS st) {
    return jobMonitor.toHtml(st);
  }
}
