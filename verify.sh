#!/bin/bash
JAR="target/lanchat-core-1.0-SNAPSHOT.jar"

# Cleanup old dbs
rm -f lanchat_*.db*

echo "Starting Node 1..."
java -jar $JAR 19000 Node1 > node1.log 2>&1 &
PID1=$!

sleep 2

echo "Starting Node 2..."
java -jar $JAR 19002 Node2 > node2.log 2>&1 &
PID2=$!

echo "Waiting for discovery (15s)..."
sleep 15

echo "Stopping nodes..."
kill $PID1 $PID2

echo "--- Node 1 Log (Last 20 lines) ---"
tail -n 20 node1.log

echo "--- Node 2 Log (Last 20 lines) ---"
tail -n 20 node2.log

echo "--- Verification ---"
if grep -q "Node2" node1.log; then
    echo "Node 1 found Node 2: YES"
else
    echo "Node 1 found Node 2: NO"
fi

if grep -q "Node1" node2.log; then
    echo "Node 2 found Node 1: YES"
else
    echo "Node 2 found Node 1: NO"
fi
