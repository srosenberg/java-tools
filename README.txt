Deploy the assembly to all data nodes.  Assuming it's deployed to /usr/local/lib, here is an example of running hadoop:

hadoop jar /usr/lib/hadoop/hadoop-examples-0.20.2-cdh3u3.jar wordcount -Dmapred.map.child.java.opts="-javaagent:/usr/local/lib/core-tools-0.0.1-SNAPSHOT-common-assembly.jar" -Dmapred.reduce.child.java.opts="-javaagent:/usr/local/lib/core-tools-0.0.1-SNAPSHOT-common-assembly.jar" inputDir outputDir

In pig/hive, set 'mapred.map.child.java.opts' and 'mapred.reduce.child.java.opts'.


