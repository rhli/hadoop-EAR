#!/bin/bash

if [ "$1" == "192.168.0.21" ] 
then
	echo "/rack1"
elif [ "$1" == "192.168.0.22" ] 
then
	echo "/rack2"
elif [ "$1" == "192.168.0.23" ] 
then
	echo "/rack3"
elif [ "$1" == "192.168.0.24" ] 
then
	echo "/rack4"
elif [ "$1" == "192.168.0.25" ] 
then
	echo "/rack5"
elif [ "$1" == "192.168.0.26" ] 
then
	echo "/rack6"
elif [ "$1" == "192.168.0.27" ] 
then
	echo "/rack7"
elif [ "$1" == "192.168.0.28" ] 
then
	echo "/rack8"
elif [ "$1" == "192.168.0.29" ] 
then
	echo "/rack9"
elif [ "$1" == "192.168.0.30" ] 
then
	echo "/rack10"
elif [ "$1" == "192.168.0.31" ] 
then
	echo "/rack11"
elif [ "$1" == "192.168.0.32" ] 
then
	echo "/rack12"
elif [ "$1" == "192.168.0.33" ] 
then
	echo "/rack13"
else
	echo "/default-rack"
fi
