This is the release version of EAR replication.

User's Guide
======

I. Installation
---------

1. Run `bash install-jar.sh` to install.

II. Configuration
---------

1. Configure the placement:
2. Configure raid:

Developer's Guide
======

IMPORTANT NOTE: EAR replication will be installed as a patch to Hadoop-20,
after installation.  If you want to revert, please back up the original source
code.

I. Installation
------

1. Run `install-source.sh` to copy the source file and re-compile the whole
   system.
2. Configure according to the User's Guide.

II. Understanding the Source Code. 
------

There are mainly three components of the codes we modified.

1. The Placement module
   (src/contrib/raid/src/java/org/apache/hadoop/hdfs/server/namenode/): We
   start from placing the replicated blocks.  EARLayoutGen.java generates
   layout for a stripe of data blocks.  EARBlockPlacementPolicy.java places
   blocks.
2. The RaidNode module (src/contrib/raid/src/java/org/apache/hadoop/raid/): We
   mainly modified DistRaid.java and RaidNode.java.
3. The MapReduce module (src/mapred/org/apache/hadoop/mapred/): We modified
   JobConf.java to add flag to identify encoding job.  
   We add EARSequenceFileRecordReader.java for analyzing input file of encoding
   job. 




