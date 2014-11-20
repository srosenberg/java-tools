To run the simple profiler on a hadoop cluster, follow these steps.

First, deploy the jar assembly to all data nodes.  Assuming it's deployed to /usr/local/lib, here is an example of running hadoop:

hadoop jar /usr/lib/hadoop/hadoop-examples-0.20.2-cdh3u3.jar wordcount -Dmapred.map.child.java.opts="-javaagent:/usr/local/lib/core-tools-0.0.1-SNAPSHOT-common-assembly.jar=classes=(org.apache|java.lang).*" -Dmapred.reduce.child.java.opts="-javaagent:/usr/local/lib/core-tools-0.0.1-SNAPSHOT-common-assembly.jar" inputDir outputDir

The 'classes' argument instructs the profiler to instrument only those classes whose fully-qualified names match the regular expression.

In pig/hive, set 'mapred.map.child.java.opts' and 'mapred.reduce.child.java.opts'.




