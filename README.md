This is the release version of EAR replication.

User's Guide
======

I. Download Hadoop-20
---------
Hadoop-20 can be downloaded from [here](https://github.com/facebookarchive/hadoop-20).  
Just download and extract it in one of your hosts.

---------

II. Installation and Configuration
---------
0. Configure hadoop:  
To simplify our description, we refer the hadoop-20/ directory as hadoop directory.  
Suppose your username is hadoop.  

  - Basic system settings
    1. In your ~/.bashrc, add the following line
      > export HADOOP_HOME=*absolute address of hadoop directory* 
      > export PATH=PATH:$HADOOP_HOME/bin 
    2. Configure masters and slaves
    3. Other configurations
  - Rack awareness configuration  
    Hadoop uses a topology definition script to realize rack awareness.
    1. Create the scripts

1. Run `bash install.sh` to install EAR.

2. Configure EAR placement:
  - Configure placement.
  - Configure locations of pre-encoding stripe store.

3. Configure Raid:
  - Configure the directory to raid.

IV. Walk through
---------
We walk through a simple example to examine whether everything is alright.

Developer's Guide
======

IMPORTANT NOTE: EAR replication will be installed as a patch to Hadoop-20,
after installation.  If you want to revert, please back up the original source
code.

II. Understanding the Source Code. 
------

There are mainly three components of the codes we modified.

1. The Placement module
   (src/contrib/raid/src/java/org/apache/hadoop/hdfs/server/namenode/): We
   start from placing the replicated blocks.  
      - EARLayoutGen.java generates layout for a stripe of data blocks.  
      - EARBlockPlacementPolicy.java returns block locations for a specific
        block.

2. The RaidNode module (src/contrib/raid/src/java/org/apache/hadoop/raid/): We
   mainly modified DistRaid.java and RaidNode.java.

3. The MapReduce module (src/mapred/org/apache/hadoop/mapred/): We modified
   JobConf.java to add flag to identify encoding job.  
   We add EARSequenceFileRecordReader.java for analyzing input file of encoding
   job. 




