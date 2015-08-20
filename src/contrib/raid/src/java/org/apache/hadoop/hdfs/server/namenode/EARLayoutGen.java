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
package org.apache.hadoop.hdfs.server.namenode;

import org.apache.commons.logging.*;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.FSConstants;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.protocol.LocatedBlocks;
import org.apache.hadoop.net.DNSToSwitchMapping;
import org.apache.hadoop.net.NetworkTopology;
import org.apache.hadoop.net.Node;
import org.apache.hadoop.net.NodeBase;
import org.apache.hadoop.util.HostsFileReader;
import org.apache.hadoop.raid.Codec;
import org.apache.hadoop.hdfs.server.namenode.BlockPlacementPolicyDefault;
/* Added by RH Oct 21st, 2014 begins */
import org.apache.hadoop.hdfs.server.namenode.PreEncodingStripeStore;
/* Added by RH Oct 21st, 2014 ends */

import java.io.IOException;
import java.util.*;
import org.apache.commons.lang.ArrayUtils;

/**
 * Helper class for EAR placement to generate valid EAR stripe layout.
 */
public class EARLayoutGen{
  public static final Log LOG =
        LogFactory.getLog(BlockPlacementPolicyRaid.class);
  /**
   * Random number and list generator
   */
  private class randomGen{ 
      private Random _rand;
      /**
       * Constructor
       */
      randomGen(){
        _rand=new Random();
      } 

      /**
       * Generate a random list.
       *
       * @param tot int total number of items
       * @param req int requested number of items
       * @param buf int[] list of item indices
       */
      public int generateList(int tot,int req,int[] buf){ 
        if(tot<req){ 
          return -1; 
        } 
        for(int i=0;i<req;i++){ 
          while(true){ 
            int generated=_rand.nextInt(tot);
            /* check for duplication */
            int duplicated=0;
            for(int j=0;j<i;j++){ 
              if(generated==buf[j]){ 
                duplicated=1; 
                break; 
              }
            }
            if(duplicated==0){ 
              buf[i]=generated; 
              break; 
            } 
          } 
        } 
        return 0;
      }

      /**
       * Generate a random integer within range
       * @param range int 
       */
      public int generateInt(int range){
        return _rand.nextInt(range);
      }
  };

  /**
   * Graph representation of EAR
   */
  public class graph{
    /**
     * Vertex metadata used in graph.
     */
    public class vertexInfo{
      private int _vertexID;
      private int _inDegree;
      private int _outDegree;
      vertexInfo(){_vertexID=-1;_inDegree=0;_outDegree=0;}
      public void setVertexID(int id){_vertexID=id;};
      public int getVertexID(){return _vertexID;};
      public void inDegreeInc(){_inDegree++;};
      public void inDegreeDec(){_inDegree--;};
      public void outDegreeInc(){_outDegree++;};
      public void outDegreeDec(){_outDegree--;};
      public int getInDegree(){return _inDegree;};
      public int getOutDegree(){return _outDegree;};
      public void setInDegree(int val){_inDegree=val;};
      public void setOutDegree(int val){_outDegree=val;};
      public boolean noInEdge(){return _inDegree==0;};
      public boolean noOutEdge(){return _outDegree==0;};
      public boolean isFree(){return noInEdge()||noOutEdge();};
      public void reset(){_vertexID=-1;_inDegree=0;_outDegree=0;}; 
    }; 

    /* the value of the matrix represent capacity of some edge */
    private int[] _veGraph;
    private int[] _resGraph;

    /* For testing the validity of a block placement */
    private int[] _backVeGraph;
    private int[] _backResGraph;
    private int _backMaxFlow; 

    private vertexInfo[] _nodeInd;
    private vertexInfo[] _rackInd;

    /* for calculating max flow */
    private int[] _vertexColor;
    private int[] _path;
    private int _pathLen;

    private int _blockOffset;
    private int _nodeOffset;
    private int _rackOffset;
    private int _sinkOffset;

    /* parameters of the graph */
    private int _vertexNum;
    private int _maxFlow; /* We use an incremental method to compute max flow, so keep this */

    /* Information of the system */
    private int _blockNum;/* k in erasure coding */
    private int _repFac;
    private int _nodeNum;/* replica number times k */
    private int _rackNum;
    private int _nodePerRack;
    private int _maxInRack;/* the value of (n-k) */

    /**
     * this function operates on residual graphs, it gets a path from source to sink 
     */
    private int pathSearch(){
      Arrays.fill(_vertexColor,0);
      _pathLen=-1;
      return dfs(0);
    }

    /**
     * depth-first search for a path
     * @param vID int destination node
     * @return value 0 means there is no path to sink and 1 means yes 
     */
    private int dfs(int vID){
      int retVal=0;
      _pathLen++;
      _vertexColor[vID]=1;
      if(vID==_vertexNum-1){
        retVal=1;
        _path[_pathLen]=vID;
        _pathLen--;
        return retVal;
      }
      for(int i=0;i<_vertexNum;i++){
        if((_resGraph[vID*_vertexNum+i]!=0)&& 
            (_vertexColor[i]==0)){ 
          if(dfs(i)==1){ 
            retVal=1; 
            break; 
          }
        }
      }
      if(retVal!=0){
        _path[_pathLen]=vID;
      }
      _vertexColor[vID]=2;
      _pathLen--;
      return retVal;
    }

    graph(int bN,int maxInRack,int repFac,int nodeNum,int rackNum){
      _blockNum=bN;
      _maxInRack=maxInRack;
      _repFac=repFac;
      _nodeNum=nodeNum;
      _rackNum=rackNum;
      _nodePerRack=nodeNum/rackNum;
      _nodeNum=_nodeNum<_repFac*_blockNum?_nodeNum:_repFac*_blockNum;
      _rackNum=_rackNum<_repFac*_blockNum?_rackNum:_repFac*_blockNum;
      _vertexNum=2+_rackNum+_nodeNum+_blockNum;
      _blockOffset=1;
      _nodeOffset=1+_blockNum;
      _rackOffset=_nodeNum+_nodeOffset;
      _sinkOffset=_rackNum+_rackOffset;
      _nodeInd=new vertexInfo[_nodeNum];
      _rackInd=new vertexInfo[_rackNum];
      //for(int i=0;i<_nodeNum;i++){
      //    _nodeInd[i]=new vertexInfo();
      //}
      //for(int i=0;i<_rackNum;i++){
      //    _rackInd[i]=new vertexInfo();
      //}
      _vertexColor=new int[_vertexNum];
      _path=new int[_vertexNum];
      _veGraph=new int[_vertexNum*_vertexNum];
      _resGraph=new int[_vertexNum*_vertexNum];
      _backVeGraph=new int[_vertexNum*_vertexNum];
      _backResGraph=new int[_vertexNum*_vertexNum];
      _maxFlow=0;
      graphInit();
    }

    /**
     * Initilization
     */
    public int graphInit(){
      Arrays.fill(_resGraph,0);
      Arrays.fill(_backVeGraph,0);
      Arrays.fill(_backResGraph,0);
      Arrays.fill(_veGraph,0);
      for(int i=0;i<_blockNum;i++){
          _veGraph[i+_blockOffset]=1;
          _resGraph[i+_blockOffset]=1;
      }
      for(int i=0;i<_nodeNum;i++){
        _nodeInd[i]=new vertexInfo();
        _nodeInd[i].reset();
      }
      for(int i=0;i<_rackNum;i++){
        _rackInd[i]=new vertexInfo();
        _rackInd[i].reset();
      }
      for(int i=0;i<_rackNum;i++){
          _veGraph[(i+_rackOffset)*_vertexNum+_vertexNum-1]=_maxInRack;
          _resGraph[(i+_rackOffset)*_vertexNum+_vertexNum-1]=_maxInRack;
      }
      return 0;
    }

    /**
     * Add set of edges source-block-node-rack-sink
     *
     * @param blockID int the index in the stripe
     * @param nodeID int 
     * @param rackID int 
     */
    public int addEdge(int blockID,int nodeID,int rackID){
      /* The capacity of every edge is 1 */
      /* check whether the node is already in nodeInd */
      int marker=-1;
      int firstFree=-1;
      for(int i=0;i<_nodeNum;i++){ 
        if(_nodeInd[i].isFree()&&(firstFree==-1)){ 
          firstFree=i; 
          continue; 
        }else if(_nodeInd[i].getVertexID()==nodeID){ 
          marker=i; 
          break; 
        }
      }
      if(marker==-1){ 
        marker=firstFree;
      }
      _nodeInd[marker].setVertexID(nodeID);
      _nodeInd[marker].inDegreeInc();
      _veGraph[(blockID+_blockOffset)*_vertexNum+marker+_nodeOffset]=1;
      _resGraph[(blockID+_blockOffset)*_vertexNum+marker+_nodeOffset]=1;

      int rackMarker=-1;
      int firstRackFree=-1;
      for(int i=0;i<_rackNum;i++){
          if(_rackInd[i].noInEdge()&&(firstRackFree==-1)){
              firstRackFree=i;
              continue;
          }else if(_rackInd[i].getVertexID()==rackID){
              rackMarker=i;
              break;
          }
      }
      if(rackMarker==-1){
          rackMarker=firstRackFree;
      }
      _rackInd[rackMarker].setVertexID(rackID);
      _rackInd[rackMarker].inDegreeInc();
      _nodeInd[marker].outDegreeInc();
      _veGraph[(marker+_nodeOffset)*_vertexNum+rackMarker+_rackOffset]=1;
      _resGraph[(marker+_nodeOffset)*_vertexNum+rackMarker+_rackOffset]=1;
      return 0;
    }

    /**
     * Remove set of edges source-block-node-rack-sink, sister function of addEdge()
     *
     * @param blockID int the index in the stripe
     * @param nodeID int 
     * @param rackID int 
     */
    public int removeEdge(int blockID,int nodeID,int rackID){
      if(blockID>=_blockNum||blockID<0){
          return -1;
      }
      int nodeMarker=-1;
      int rackMarker=-1;
      for(int i=0;i<_nodeNum;i++){
          if(_nodeInd[i].getVertexID()==nodeID){
              nodeMarker=i;
          }
      }
      for(int i=0;i<_rackNum;i++){
          if(_rackInd[i].getVertexID()==rackID){
              rackMarker=i;
          }
      }
      if((nodeMarker!=-1)&&
              (rackMarker!=-1)&&
              (_veGraph[(blockID+_blockOffset)*_vertexNum+nodeMarker+_nodeOffset]==1)){
          _veGraph[(blockID+_blockOffset)*_vertexNum+nodeMarker+_nodeOffset]=0;
          _nodeInd[nodeMarker].inDegreeDec();
      }else{
          //fprintf(stderr,"no such edge!");
      }
      /* mark node/rack as free vertex if necessary */
      if(_nodeInd[nodeMarker].noInEdge()){
          _nodeInd[nodeMarker].setVertexID(-1);
          _veGraph[(_nodeOffset+nodeMarker)*_vertexNum+_rackOffset+rackMarker]=0;
          _rackInd[rackMarker].inDegreeDec();
          if(_rackInd[rackMarker].noInEdge()){
              _rackInd[rackMarker].setVertexID(-1);
          }
      }
      return 0;
    }

    /**
     * Compute max flow of graph
     */
    public int maxFlow(){ 
      int retVal=0;
      System.arraycopy(_veGraph,0,_resGraph,0,_vertexNum*_vertexNum);
      while(pathSearch()!=0){
        /* Find a path, update _resGraph and search for the next round */
        int edgeCount=0;
        int minCap=Integer.MAX_VALUE;
        /*
         * Rules for updating residual graph:
         *  1. find minimal capacity of the path
         *  2. add reverse edges
         */
        while(_path[edgeCount]!=_vertexNum-1){
          if(_resGraph[_path[edgeCount]*_vertexNum+_path[edgeCount+1]]<minCap){
              minCap=_resGraph[_path[edgeCount]*_vertexNum+_path[edgeCount+1]];
          }
          edgeCount++;
        }
        edgeCount=0;
        while(_path[edgeCount]!=_vertexNum-1){
          _resGraph[_path[edgeCount]*_vertexNum+_path[edgeCount+1]]-=minCap;
          _resGraph[_path[edgeCount+1]*_vertexNum+_path[edgeCount]]+=minCap;
          edgeCount++;
        }
        retVal+=minCap;
      }
      return retVal;
    }

    /**
     * Compute incremental maxFlow of the graph
     */
    public int incrementalMaxFlow(){
      int retVal=0;
      while(pathSearch()!=0){
        /* Find a path, update _resGraph and search for the next round */
        int edgeCount=0;
        int minCap=Integer.MAX_VALUE;
        /*
         * Rules for updating residual graph:
         *  1. find minimal capacity of the path
         *  2. add reverse edges
         */
        while(_path[edgeCount]!=_vertexNum-1){
          if(_resGraph[_path[edgeCount]*_vertexNum+_path[edgeCount+1]]<minCap){
              minCap=_resGraph[_path[edgeCount]*_vertexNum+_path[edgeCount+1]];
          }
          edgeCount++;
        }
        edgeCount=0;
        while(_path[edgeCount]!=_vertexNum-1){
          _resGraph[_path[edgeCount]*_vertexNum+_path[edgeCount+1]]-=minCap;
          _resGraph[_path[edgeCount+1]*_vertexNum+_path[edgeCount]]+=minCap;
          edgeCount++;
        }
        retVal+=minCap;
      }
      _maxFlow+=retVal;
      return retVal;
    }

    /**
     * back up the graph
     */
    public int backGraph(){
      System.arraycopy(_veGraph,0,_backVeGraph,0,_vertexNum*_vertexNum);
      System.arraycopy(_resGraph,0,_backResGraph,0,_vertexNum*_vertexNum);
      _backMaxFlow=_maxFlow;
      return 0;
    }

    /**
     * restore the graph
     */
    public int restoreGraph(){
      System.arraycopy(_backVeGraph,0,_veGraph,0,_vertexNum*_vertexNum);
      System.arraycopy(_backResGraph,0,_resGraph,0,_vertexNum*_vertexNum);
      _maxFlow=_backMaxFlow;
      return 0;
    }

    public int getMaxFlow(){return _maxFlow;};

    /**
     * get max matching of the blocks and nodes
     *
     * @param retVal int[] list of edges
     */
    public int getMaxMatch(int[] retVal){
      /* In this function, we get the max matching from the residual graph */
      for(int i=0;i<_blockNum;i++){
        retVal[i]=-1;
      }
      /*
       * We comment the following lines because for conventional algorithm, it is
       * possible that we need re-allocation. 
       *
       * In this way, we just keep the value in array to be -1
       */
      maxFlow();
      int index=0;
      for(int i=_blockOffset;i<_blockNum+_blockOffset;i++){
        for(int j=_nodeOffset;j<_nodeOffset+_nodeNum;j++){
          if(_resGraph[j*_vertexNum+i]==1){
            retVal[index]=_nodeInd[j-_nodeOffset].getVertexID();
            break;
          }
        }
        index++;
      }
      return 0;
    }

    /**
     * Initiate a graph with a given placement 
     *
     * @param pla int[] placement
     */
    public int initFromPla(int[] pla){
      this.graphInit();
      for(int i=0;i<_blockNum;i++){
        for(int j=1;j<_repFac;j++){
          this.addEdge(i,pla[i*_repFac+j],pla[i*_repFac+j]/_nodePerRack);
        }
      }
      if(this.maxFlow()==_blockNum){
        return 0;
      }else{
        this.graphInit();
        for(int i=0;i<_blockNum;i++){
          for(int j=1;j<_repFac;j++){
            this.addEdge(i,pla[i*_repFac+j],pla[i*_repFac+j]/_nodePerRack);
          }
          for(int k=0;k<i;k++){
            this.addEdge(k,pla[i*_repFac],pla[i*_repFac]/_nodePerRack);
          }
          if(this.maxFlow()==_blockNum){
            return 0;
          }
        }
      }
      return 0;
    }

    /** 
     * Remove a vertex representing a node(slave) 
     */
    public int removeVertex(int vid){
      int vertexInd=-1;
      for(int i=0;i<_nodeNum;i++){
          if(_nodeInd[i].getVertexID()==vid){
              vertexInd=i+_nodeOffset;
              break;
          }
      }
      if(vertexInd==1){
          return -1;
      }
      /* remove the edges out of this vertex */
      for(int i=_blockOffset;i<_blockOffset+_blockNum;i++){
          if(_veGraph[i*_vertexNum+vertexInd]==1){
              removeEdge(i,vertexInd,vertexInd/_nodePerRack);
          }
      }
      _nodeInd[vertexInd-_nodeOffset].reset();
      return 0;
    }
  }

  private int _blockNum; /* # of data blocks in a stripe */
  private int _stripeSize; /* # of blocks in a stripe, both data and parity included */
  /* Num of replicas of a block */
  private int _repFac;
  private int _rackNum;
  private int _nodePerRack;
  private int _maxInRack;

  /* Erasure coding parameters, for stripe-oriented placement */
  private randomGen _randGen=new randomGen();
  private graph _graph;

  /**
   * Constructor
   * @param bN int number of data blocks per stripe
   * @param repFac int number of replicas in the replication scheme
   * @param rackNum int number of racks in system
   * @param nodePerRack int number of nodes per rack
   */
  public EARLayoutGen(int bN,int stripeSize,int repFac,int rackNum,int nodePerRack,int maxInRack){
      _blockNum=bN;
      _stripeSize=stripeSize;
      _rackNum=rackNum;
      _repFac=repFac;
      _nodePerRack=nodePerRack;
      _graph=new graph(_blockNum,maxInRack,repFac,_rackNum*_nodePerRack,_rackNum);
  }

  /**
   * Complete implementation of EAR with appointed core rack
   *
   * @param coreRack int rackID of coreRack
   * @param output int[] list of blocks to be placed
   */
  public int SOP(int coreRack,int[] output){
    int[] rackInd=new int[2];
    int retVal=coreRack;
    _graph.graphInit();
    int[] pos=new int[_repFac];
    for(int i=0;i<_blockNum;i++){
      /* Every time, we **try** to generate a placement for ONE block. */
      int index=0;
      while(true){
        index++;
        if(i>=2)_graph.backGraph();
        rackInd[0]=retVal;
        while((rackInd[1]=_randGen.generateInt(_rackNum))==rackInd[0]);
        _randGen.generateList(_nodePerRack,_repFac-1,pos);
        pos[_repFac-1]=pos[0];
        _randGen.generateList(_nodePerRack,1,pos);
        for(int j=0;j<_repFac;j++){
          pos[j]+=j==0?rackInd[0]*_nodePerRack:rackInd[1]*_nodePerRack;
          _graph.addEdge(i,pos[j],pos[j]/_nodePerRack);
        }
        if(_graph.incrementalMaxFlow()==0){
          for(int j=0;j<_repFac;j++){
            _graph.removeEdge(i,pos[j],pos[j]/_nodePerRack);
          }
          _graph.restoreGraph();
        }else{
          System.arraycopy(pos,0,output,i*_repFac,_repFac);
          break;
        }
      }
    }
    return retVal;
  }

  /**
   * Complete implementation of EAR with out considering core rack since 
   * for default placement, the primary replica is always placed in the writer.
   *
   * NOTE: We compomises a little bit in the implementation by fix the flow in 
   * core-rack to one.  This will not affect performance and availability.
   *
   * @param coreRack int rackID of coreRack
   * @param output int[] list of blocks to be placed
   */
  public int SOPwoCoreRack(int coreRack,int[] output){
    int[] rackInd=new int[2];
    int retVal=coreRack;
    _graph.graphInit();
    int[] pos=new int[_repFac];
    for(int i=0;i<_blockNum;i++){
      /* Every time, we **try** to generate a placement for ONE block. */
      int index=0;
      while(true){
        index++;
        if(i>=1)_graph.backGraph();
        rackInd[0]=retVal;
        while((rackInd[1]=_randGen.generateInt(_rackNum))==rackInd[0]);
        _randGen.generateList(_nodePerRack,_repFac-1,pos);
        pos[_repFac-1]=pos[0];
        _randGen.generateList(_nodePerRack,1,pos);
        pos[0]+=_nodePerRack*_nodePerRack;
        for(int j=1;j<_repFac;j++){
          pos[j]+=rackInd[1]*_nodePerRack;
        }
        if(i==_blockNum-1) {
          // We have granted enough flow, we just do not interfere with the last block.
          for(int j=0;j<_repFac;j++){
            output[j+i*_repFac]=pos[j];
          }
          break;
        }
        for(int j=1;j<_repFac;j++){
          _graph.addEdge(i,pos[j],pos[j]/_nodePerRack);
        }
        if(_graph.incrementalMaxFlow()==0){
          for(int j=1;j<_repFac;j++){
            _graph.removeEdge(i,pos[j],pos[j]/_nodePerRack);
          }
          _graph.restoreGraph();
        }else{
          for(int j=0;j<_repFac;j++){
            output[j+i*_repFac]=pos[j];
          }
          break;
        }
      }
    }
    return retVal;
  }
}









