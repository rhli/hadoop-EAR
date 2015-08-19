#!/bin/bash

# get PreEncStripeStore from namenode
while read line
do
  rsync -rtv $line:$HADOOP_HOME/PreEncStripeStore/ $HADOOP_HOME/PreEncStripeStore/
  #echo $line
done < $HADOOP_HOME/conf/masters

# spread PreEncStripeStore to slaves
while read line
do
  rsync -rtv $HADOOP_HOME/PreEncStripeStore/ $line:$HADOOP_HOME/PreEncStripeStore/
  #ssh $line \'ls\'
done < $HADOOP_HOME/conf/slaves

