#!/usr/bin/env bash

# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.


# Stop hadoop Hmon process on machine specified on file conf/hmon

usage="Usage: stop-hmon-remote.sh"

params=$#
bin=`dirname "$0"`
bin=`cd "$bin"; pwd`

. "$bin"/hadoop-config.sh

# get arguments
if [ $# -ge 1 ]; then
    echo $usage
fi

if [ -f "${HADOOP_CONF_DIR}/hmon" ]; then
  export HADOOP_SLAVES="${HADOOP_CONF_DIR}/hmon"
  echo "Stopping hmon at "`cat ${HADOOP_SLAVES}`
  "$bin"/slaves.sh --config $HADOOP_CONF_DIR cd "$HADOOP_HOME" \; "$bin/stop-hmon.sh"
else
  echo "No hmon file in ${HADOOP_CONF_DIR}/hmon"
fi
