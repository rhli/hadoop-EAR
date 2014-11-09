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

import java.io.IOException;


import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.BlockMissingException;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.*;
import org.apache.hadoop.util.ReflectionUtils;
/* Added by RH begins */
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
/* Added by RH ends */

/** An {@link RecordReader} for {@link SequenceFile}s. */
public class SequenceFileRecordReader<K, V> implements RecordReader<K, V> {
  public static final String SEQUENCE_FILE_TOLERATE_CORRUPTIONS_CONF =
    "mapred.seqfile.tolerate.corruptions";
  /* Added by RH begins */
  private static final Log LOG = LogFactory.getLog(SequenceFileRecordReader.class.getName());
  /* Added by RH ends */
  
  private SequenceFile.Reader in;
  private long start;
  private long end;
  private boolean more = true;
  protected Configuration conf;
  private boolean tolerateCorruptions = false;

  public SequenceFileRecordReader(Configuration conf, FileSplit split)
    throws IOException {
    Path path = split.getPath();
    FileSystem fs = path.getFileSystem(conf);
    this.in = new SequenceFile.Reader(fs, path, conf);
    this.end = split.getStart() + split.getLength();
    this.conf = conf;
    this.tolerateCorruptions = conf.getBoolean(
      SEQUENCE_FILE_TOLERATE_CORRUPTIONS_CONF, false);

    /* Commented by RH begins */
    //if (split.getStart() > in.getPosition())
    //  in.sync(split.getStart());                  // sync to start

    //this.start = in.getPosition();
    /* Commented by RH ends */
    /* Added by RH begins */
    /* TODO: de-hardcode.. */
    this.start = split.getStart();
    if (split.getStart()==0) {
      in.sync(this.start);
      this.start = in.getPosition();
    } else {
      seek(this.start);
    }
    LOG.info("seqRecReader start: " + this.start + " end: " + this.end);
    /* Added by RH ends */
    more = start < end;
  }


  /** The class of key that must be passed to {@link
   * #next(Object, Object)}.. */
  public Class getKeyClass() { return in.getKeyClass(); }

  /** The class of value that must be passed to {@link
   * #next(Object, Object)}.. */
  public Class getValueClass() { return in.getValueClass(); }
  
  @SuppressWarnings("unchecked")
  public K createKey() {
    return (K) ReflectionUtils.newInstance(getKeyClass(), conf);
  }
  
  @SuppressWarnings("unchecked")
  public V createValue() {
    return (V) ReflectionUtils.newInstance(getValueClass(), conf);
  }
    
  public synchronized boolean next(K key, V value) throws IOException {
    /* Added by RH begins */
    LOG.info("seqRecReader: next(key,val) begins " + in.getPosition());
    /* Added by RH ends */
    if (!more) return false;
    LOG.info("seqRecReader: here");
    long pos = in.getPosition();
    /* Added by RH begins */
    if (pos>=this.end) {
      more = false;
      return more;
    }
    /* Added by RH ends */
    boolean remaining = false;
    if (tolerateCorruptions) {
      try {
        remaining = (in.next(key) != null);
        if (remaining) {
          getCurrentValue(value);
        }
      } catch (Throwable t) {
        if (t instanceof BlockMissingException) {
          // Missing blocks are not corruptions. Dont handle this error.
          throw (BlockMissingException) t;
        } else {
          return false;
        }
      }
    } else {
      remaining = (in.next(key) != null);
      if (remaining) {
        getCurrentValue(value);
      }
    }

    if (pos >= end && in.syncSeen()) {
      more = false;
    } else {
      more = remaining;
    }
    /* Added by RH begins */
    LOG.info("seqRecReader: next(key,val) ends " + in.getPosition());
    /* Added by RH ends */
    return more;
  }
  
  protected synchronized boolean next(K key)
    throws IOException {
    if (!more) return false;
    long pos = in.getPosition();
    boolean remaining = false;
    if (tolerateCorruptions) {
      try {
        remaining = (in.next(key) != null);
      } catch (Throwable t) {
        if (t instanceof BlockMissingException) {
          // Missing blocks are not corruptions. Dont handle this error.
          throw (BlockMissingException) t;
        } else {
          return false;
        }
      }
    } else {
      remaining = (in.next(key) != null);
    }

    if (pos >= end && in.syncSeen()) {
      more = false;
    } else {
      more = remaining;
    }
    return more;
  }
  
  protected synchronized void getCurrentValue(V value)
    throws IOException {
    in.getCurrentValue(value);
  }
  
  /**
   * Return the progress within the input split
   * @return 0.0 to 1.0 of the input byte range
   */
  public float getProgress() throws IOException {
    if (end == start) {
      return 0.0f;
    } else {
      return Math.min(1.0f, (in.getPosition() - start) / (float)(end - start));
    }
  }
  
  public synchronized long getPos() throws IOException {
    return in.getPosition();
  }
  
  protected synchronized void seek(long pos) throws IOException {
    in.seek(pos);
  }
  public synchronized void close() throws IOException { in.close(); }
  
}

