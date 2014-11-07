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
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
/* Added by RH Oct 24th, 2014 begins */
import java.util.*;
/* Added by RH Oct 24th, 2014 ends */

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;
/* Added by RH Oct 24th, 2014 begins */
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.DistributedRaidFileSystem;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.protocol.LocatedBlocks;
import org.apache.hadoop.hdfs.server.namenode.PreEncodingStripeStore;
/* Added by RH Oct 24th, 2014 ends */

/*
 * DirectoryStripeReader is used in directory-raid encoder and
 * decoder to return a bunch of input streams in a stripe.
 * When it's initiated, it lists all blocks under the source directory
 * and puts them in a list stripeBlocks. We use stripeBlocks to
 * locate the file and block index for specific block.
 * For encoder case, we use hasNext() and getNextStripeInputs()
 * to iterate each stripe in the leaf directory
 * For decoder case, buildOneInput is used to return the input
 * stream for a specific block of a file.
 */

public class DirectoryStripeReader extends StripeReader {
  /* source directory */
  Path srcDir;
  /* list of file status under source directory */
  List<FileStatus> lfs;
  /* the block size of parity file */
  long parityBlockSize;

  
  public static class BlockInfo {
    public int fileIdx;
    public int blockId;
    public BlockInfo(int fileIdx, int blockId) {
      this.fileIdx = fileIdx;
      this.blockId = blockId;
    }
  }
  List<BlockInfo> stripeBlocks = null;

  /* list of srcStripes under source, this is a alternate of stripeBlocks
   * Added by RH Oct 26th, 2014 begins */
  List<List<BlockInfo>> srcStripeList = new ArrayList<List<BlockInfo>>();
  Map<String,Integer> fileIndexMap;

  /**
   * Helper function of Encoder.
   */
  public List<List<Block>> getSrcStripes() {
    List<List<Block>> srcStripes = new ArrayList<List<Block>>();
    for (int i=0;i<srcStripeList.size();i++) {
      List<BlockInfo> biList=srcStripeList.get(i);
      List curSrcStripe = new ArrayList<Block>();
      for (int j=0;j<biList.size();j++) {
        int fileIdx = biList.get(j).fileIdx;
        int blockId = biList.get(j).blockId;
        FileStatus curFs = lfs.get(fileIdx);

        try {
          if (fs instanceof DistributedRaidFileSystem) {
            curSrcStripe.add(
                ((DistributedRaidFileSystem)fs).toDistributedFileSystem().getLocatedBlocks(curFs.getPath(),
                0L, curFs.getLen()).get(blockId).getBlock());
          }
        } catch (IOException e) {
          ;
        }
      }
      srcStripes.add(curSrcStripe);
    }
    return srcStripes;
  }

  public int getNumStripes(){
    return srcStripeList.size();
  }
  /* Added by RH Oct 26th, 2014 ends */
  
  long numBlocks = 0L;
  
  public static long getParityBlockSize(Configuration conf,
      List<FileStatus> lfs) {
    long parityBlockSize = 0L; 
    for (FileStatus fsStat: lfs) {
      long size = Math.min(fsStat.getBlockSize(), fsStat.getLen());
      if ( size > parityBlockSize) {
        parityBlockSize = size;
      }
    }
    int bytesPerChecksum = conf.getInt("io.bytes.per.checksum", 512);
    if (parityBlockSize % bytesPerChecksum != 0) {
      // block size need to be the multiple of bytesPerChecksum;
      parityBlockSize = parityBlockSize / bytesPerChecksum * bytesPerChecksum
          + bytesPerChecksum;
    }
    return parityBlockSize;
  }
  
  public static long getBlockNum(List<FileStatus> lfs) {
    long blockNum = 0L;
    if (lfs == null) 
      return blockNum;
    for (FileStatus fsStat: lfs) {
      blockNum += RaidNode.getNumBlocks(fsStat);
    }
    return blockNum;
  }
  
  /**
   * Get the total logical size in the directory
   * @param lfs the Files under the directory
   * @return
   */
  public static long getDirLogicalSize(List<FileStatus> lfs) {
    long totalSize = 0L;
    if (null == lfs) {
      return totalSize;
    }
    
    for (FileStatus fsStat : lfs) {
      totalSize += fsStat.getLen();
    }
    return totalSize;
  }
  
  /**
   * Get the total physical size in the directory
   * @param lfs the Files under the directory
   * @return
   */
  public static long getDirPhysicalSize(List<FileStatus> lfs) {
    long totalSize = 0L;
    if (null == lfs) {
      return totalSize;
    }
    
    for (FileStatus fsStat : lfs) {
      totalSize += fsStat.getLen() * fsStat.getReplication();
    }
    return totalSize;
  }
  
  public static short getReplication(List<FileStatus> lfs) {
    short maxRepl = 0;
    for (FileStatus fsStat: lfs) {
      if (fsStat.getReplication() > maxRepl) {
        maxRepl = fsStat.getReplication();
      }
    }
    return maxRepl;
  }

  /** 
   * remove prefix of path.
   * TODO: de-hardcode.
   * Added by RH Oct 24th, begins 
   */
  private String removePrefix(String str){
    return str.substring(str.indexOf("/user/rhli/raidTest"),str.length());
  }

  private String removePrefix2(String str){
    return str.substring(str.indexOf("/user/rhli/raidTest")+11,str.length());
  }
  /* Added by RH Oct 24th, ends */
  
  public DirectoryStripeReader(Configuration conf, Codec codec,
      FileSystem fs, long stripeStartIdx, long encodingUnit,
      Path srcDir, List<FileStatus> lfs) 
      throws IOException {
    super(conf, codec, fs, stripeStartIdx);
    if (lfs == null) {
      throw new IOException("Couldn't get files under directory " + srcDir);
    }
    /* Added by RH Oct 24th, 2014 begins */
    LOG.info("Dir path: " + srcDir);
    fileIndexMap = new HashMap<String,Integer>();
    /* Added by RH Oct 24th, 2014 ends */
    this.parityBlockSize = getParityBlockSize(conf, lfs);
    this.srcDir = srcDir;
    this.lfs = lfs;
    /* Commented by RH Oct 24th, 2014 begins */
    //this.stripeBlocks = new ArrayList<BlockInfo>();
    /* Commented by RH Oct 24th, 2014 ends */
    long blockNum = 0L;
    for (int fid = 0; fid < lfs.size(); fid++) {
      FileStatus fsStat = lfs.get(fid);
      /* Added by RH Oct 24th, 2014 begins */
      fileIndexMap.put(removePrefix(fsStat.getPath().toString()),fid);
      /* Added by RH Oct 24th, 2014 ends */
      /* Commented by RH Oct 24th, 2014 begins */
      long numBlock = RaidNode.getNumBlocks(fsStat);
      blockNum += numBlock;
      //for (int bid = 0; bid < numBlock; bid++) {
      //  stripeBlocks.add(new BlockInfo(fid, bid));
      //}
      /* Commented by RH Oct 24th, 2014 ends */
    }
    /* Initialize using preEncStripeStore.
     * Added by RH Oct 26th, 2014 begins. */
    PreEncodingStripeStore preEncStripeStore = new PreEncodingStripeStore();
    List<List<String>> preEncStripes = preEncStripeStore.getPreEncStripes(
        removePrefix2(srcDir.toString()));
    for (int i=0;i<preEncStripes.size();i++) {
      ArrayList<BlockInfo> temp = new ArrayList<BlockInfo>();
      for (String item:preEncStripes.get(i)){
        String[] keys = item.split(":",2);
        temp.add(new BlockInfo(fileIndexMap.get(keys[0]),Integer.parseInt(keys[1])));
      }
      srcStripeList.add(temp);
    }
    /* Added by RH Oct 26th, 2014 ends */
    this.numBlocks = blockNum; 
    //long totalStripe = RaidNode.numStripes(blockNum, codec.stripeLength);
    long totalStripe = preEncStripes.size();
    if (stripeStartIdx >= totalStripe) {
      throw new IOException("stripe start idx " + stripeStartIdx + 
          " is equal or larger than total stripe number " + totalStripe);
    }
    if (encodingUnit < 0) {
      encodingUnit = totalStripe;
    }
    stripeEndIdx = Math.min(totalStripe, stripeStartIdx + encodingUnit); 
  }

  @Override
  public StripeInputInfo getStripeInputs(long stripeIdx) 
      throws IOException {
    InputStream[] blocks = new InputStream[codec.stripeLength];
    Path[] srcPaths = new Path[codec.stripeLength];
    long[] offsets = new long[codec.stripeLength];
    try {
      /* Commented by RH Oct 26th, 2014 begins */
      //int startOffset = (int)stripeIdx * codec.stripeLength;
      //for (int i = 0; i < codec.stripeLength; i++) {
      //  if (startOffset + i < this.stripeBlocks.size()) {
      //    BlockInfo bi = this.stripeBlocks.get(startOffset + i);
      /* Commented by RH Oct 26th, 2014 ends */
      /* Added by RH Oct 26th, 2014 begins */
      for (int i = 0; i < codec.stripeLength; i++) {
        if (i < this.srcStripeList.get((int)stripeIdx).size()) {
          BlockInfo bi = this.srcStripeList.get((int)stripeIdx).get(i);
      /* Added by RH Oct 26th, 2014 ends */
          FileStatus curFile = lfs.get(bi.fileIdx);
          long seekOffset = bi.blockId * curFile.getBlockSize();
          Path srcFile = curFile.getPath();
          FSDataInputStream in = fs.open(srcFile, bufferSize);
          in.seek(seekOffset);
          LOG.info("Opening stream at " + srcFile + ":" + seekOffset);
          blocks[i] = in;
          srcPaths[i] = srcFile;
          offsets[i] = seekOffset;
        } else {
          LOG.info("Using zeros at block " + i);
          // We have no src data at this offset.
          blocks[i] = new RaidUtils.ZeroInputStream(parityBlockSize);
          srcPaths[i] = null;
          offsets[i] = -1;
        }
      }
      return new StripeInputInfo(blocks, srcPaths, offsets);
    } catch (IOException e) {
      // If there is an error during opening a stream, close the previously
      // opened streams and re-throw.
      RaidUtils.closeStreams(blocks);
      throw e;
    }
  }

  /* Added by RH Oct 26th, 2014, begins*/
  public BlockLocation[] getNextStripeBlockLocations() throws IOException {
    BlockLocation[] blocks = new BlockLocation[codec.stripeLength];
    for (int i = 0; i < codec.stripeLength; i++) {
      if (i < this.srcStripeList.get((int)currentStripeIdx).size()) {
        BlockInfo bi = this.srcStripeList.get((int)currentStripeIdx).get(i);
        FileStatus curFile = lfs.get(bi.fileIdx);
        BlockLocation[] curBlocks = 
            fs.getFileBlockLocations(curFile, 0, curFile.getLen());
        curFile = lfs.get(bi.fileIdx);
        curBlocks = fs.getFileBlockLocations(curFile, 0, curFile.getLen());
        blocks[i] = curBlocks[bi.blockId];
      } else {
        // We have no src data at this offset.
        blocks[i] = null; 
      }
    }
    currentStripeIdx++;
    return blocks;
  }
  /* Added by RH Oct 26th, 2014, ends*/
  
  /* Commented by RH, Oct 26th, 2014 begins */
  //public BlockLocation[] getNextStripeBlockLocations() throws IOException {
  //  BlockLocation[] blocks = new BlockLocation[codec.stripeLength];
  //  int startOffset = (int)currentStripeIdx * codec.stripeLength;
  //  int curFileIdx = this.stripeBlocks.get(startOffset).fileIdx;
  //  FileStatus curFile = lfs.get(curFileIdx);
  //  BlockLocation[] curBlocks = 
  //      fs.getFileBlockLocations(curFile, 0, curFile.getLen());
  //  for (int i = 0; i < codec.stripeLength; i++) {
  //    if (startOffset + i < this.stripeBlocks.size()) {
  //      BlockInfo bi = this.stripeBlocks.get(startOffset + i);
  //      if (bi.fileIdx != curFileIdx) {
  //        curFileIdx = bi.fileIdx;
  //        curFile = lfs.get(curFileIdx);
  //        curBlocks = 
  //            fs.getFileBlockLocations(curFile, 0, curFile.getLen());
  //      }
  //      blocks[i] = curBlocks[bi.blockId];
  //    } else {
  //      // We have no src data at this offset.
  //      blocks[i] = null; 
  //    }
  //  }
  //  currentStripeIdx++;
  //  return blocks;
  //}
  /* Commented by RH, Oct 26th, 2014 ends */
  
  @Override
  public InputStream buildOneInput(
      int locationIndex, long offsetInBlock,
      FileSystem srcFs, Path srcFile, FileStatus srcStat,
      FileSystem parityFs, Path parityFile, FileStatus parityStat
      ) throws IOException {
    final long blockSize = srcStat.getBlockSize();

    LOG.info("buildOneInput srcfile " + srcFile + " srclen " + srcStat.getLen() + 
        " parityfile " + parityFile + " paritylen " + parityStat.getLen() +
        " stripeindex " + stripeStartIdx + " locationindex " + locationIndex +
        " offsetinblock " + offsetInBlock);
    if (locationIndex < codec.parityLength) {
      return this.getParityFileInput(locationIndex, parityFile,
          parityFs, parityStat, offsetInBlock, parityStat.getBlockSize());
    } else {
      // Dealing with a src file here.
      int blockIdxInStripe = locationIndex - codec.parityLength;
      int curBlockIdx = (int)currentStripeIdx * codec.stripeLength + blockIdxInStripe;
      if (curBlockIdx >= this.stripeBlocks.size()) {
        LOG.info("Using zeros because we reach the end of the stripe");
        return new RaidUtils.ZeroInputStream(blockSize * (curBlockIdx + 1));
      }
      BlockInfo bi = this.stripeBlocks.get(curBlockIdx);
      FileStatus fstat = lfs.get(bi.fileIdx);
      long offset = fstat.getBlockSize() * bi.blockId +
          offsetInBlock;
      if (offset >= fstat.getLen()) {
        LOG.info("Using zeros for " + fstat.getPath() + ":" + offset +
          " for location " + locationIndex);
        return new RaidUtils.ZeroInputStream(blockSize * (curBlockIdx + 1));
      } else {
        LOG.info("Opening " + fstat.getPath() + ":" + offset +
                 " for location " + locationIndex);
        FSDataInputStream s = fs.open(
            fstat.getPath(), conf.getInt("io.file.buffer.size", 64 * 1024));
        s.seek(offset);
        return s;
      }
    }
  }

}
