package org.apache.hadoop.hdfs.server.namenode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.server.namenode.BlockPlacementPolicyDefault;
import org.apache.hadoop.hdfs.server.namenode.BlockPlacementPolicy.NotEnoughReplicasException;
import org.apache.hadoop.hdfs.util.InjectionEvent;
import org.apache.hadoop.net.DNSToSwitchMapping;
import org.apache.hadoop.net.NetworkTopology;
import org.apache.hadoop.net.Node;
import org.apache.hadoop.raid.DirectoryStripeReader.BlockInfo;
import org.apache.hadoop.raid.RaidNode;
import org.apache.hadoop.raid.Codec;
import org.apache.hadoop.util.HostsFileReader;
import org.apache.hadoop.util.InjectionHandler;
import org.apache.hadoop.util.StringUtils;

/**
 * This class stores which blocks are going to be encoded as a stripe.
 *
 * Used for EARBlockPlacement to write and RaidNode to read.
 */

public class PreEncodingStripeStore {
  public static final Log LOG = LogFactory.getLog(PreEncodingStripeStore.class);
  //public ConcurrentHashMap<String, LocalStripeInfo> blockToStripeStore =
  //    new ConcurrentHashMap<String, LocalStripeInfo>();
  //public ConcurrentHashMap<List<Block>, Long> stripeSet = 
  //    new ConcurrentHashMap<List<Block>, Long>();
  //public static final String PRE_ENC_STRIPE_STORE_DIR_KEY =
  //    "hdfs.preencoding.stripe.dir";
  //TODO: de-hardcode!!
  //private String storeDirName = "/home/ncsgroup/hadoop-20/preEncStripeStore";
  private static String STORE_DIR_KEY = "hdfs.raid.preencoding.stripe.dir";
  private static String storeDirName;
  /*
  public static final String[] STRIPESTORE_SPECIFIC_KEYS = 
      new String[] {
        LOCAL_STRIPE_STORE_DIR_KEY
      };
      */
  //public static final String DELIMITER = ":"; 
  //private File storeDir;
  //private String storeDirName;
  //Random rand = new Random();

  //public PreEncodingStripeStore() {
  //  File storeDir = new File(storeDirName);
  //  if (!storeDir.exists()) {
  //    storeDir.mkdirs();
  //  }
  //}

  /**
   * With configuration to get store position.
   */
  public PreEncodingStripeStore(Configuration conf) {
    storeDirName=conf.get(STORE_DIR_KEY,"/home/hadoop-20/preEncStripeStore");
    LOG.info("storeDirName: " + storeDirName);
    File storeDir = new File(storeDirName);
    if (!storeDir.exists()) {
      storeDir.mkdirs();
    }
  }

  public void putStripe(int stripeID,List<String> blks,String dirLoc) throws IOException {
    File stripeStore = new File(storeDirName,dirLoc);
    if (!stripeStore.exists()) {
      stripeStore.mkdirs();
    }
    File stripeStoreFile = new File(stripeStore, "stripe" + stripeID);
    PrintWriter out = new PrintWriter(new BufferedWriter(
          new FileWriter(stripeStoreFile)));
    for (String blk : blks) {
      out.println(blk);
    }
    out.close();
  }

  public void putStripe(int stripeID,String blk,String dirLoc) throws IOException {
    File stripeStore = new File(storeDirName,dirLoc);
    if (!stripeStore.exists()) {
      if(stripeStore.mkdirs()){
        LOG.info("create preEncStripeStore success");
      }else{
        LOG.info("create preEncStripeStore fail");
      }
    }
    File stripeStoreFile = new File(stripeStore, "stripe" + stripeID);
    LOG.info("preEncStripeFile: " +  stripeStore + " stripe" + stripeID);
    PrintWriter out = new PrintWriter(new BufferedWriter(
          new FileWriter(stripeStoreFile,true)));
    out.println(blk);
    out.close();
  }

  public static List<List<String>> getPreEncStripes(String dirLoc) {
    LOG.info("preEncodingStripeStore getPreEncStripes() " + dirLoc);
    File stripeStore = new File(storeDirName,dirLoc);
    if (!stripeStore.exists()) {
      return null;
    }
    int stripeID=0;
    List<List<String>> retVal = new ArrayList<List<String>>();
    while (true) {
      LOG.info("getPreEncStripes: stripeID " + stripeID);
      File stripeStoreFile = new File(stripeStore, "stripe" + stripeID);
      if (!stripeStoreFile.exists()) {
        break;
      }
      try {
        BufferedReader br = new BufferedReader(new FileReader(stripeStoreFile));
        List<String> tmp = new ArrayList<String>();
        String line = br.readLine();
        while (line!=null) {
          tmp.add(line);
          line = br.readLine();
        }
        retVal.add(tmp);
        br.close();
      } catch (IOException e) {
        LOG.error("read failed");
      }
      stripeID++;
    }
    return retVal;
  }

  public boolean verifyStore(String dirLoc) {
    File stripeStore = new File(storeDirName,dirLoc);
    return stripeStore.exists();
  }

}



